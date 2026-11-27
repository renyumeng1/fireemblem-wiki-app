from __future__ import annotations

import argparse
from contextlib import closing
import hashlib
import json
import math
import os
from pathlib import Path
import sqlite3
import tempfile


APPLICATION_ID = 0x46454D50


def build_map_database(source: Path, output: Path) -> dict:
    if output.resolve().is_relative_to(source.resolve()):
        raise ValueError("地图数据库不能写入来源目录")
    bundle = source / "downloaded-bundle"
    manifest = json.loads((bundle / "manifest.json").read_text(encoding="utf8"))
    for asset in manifest["assets"]:
        name = asset["file"]
        if Path(name).name != name or hashlib.sha256((bundle / name).read_bytes()).hexdigest() != asset["sha256"]:
            raise ValueError(f"地图来源文件校验失败：{name}")
    payload = (source / "live-world-map.json").read_bytes()
    evidence = json.loads((source / "live-world-map.source.json").read_text(encoding="utf8"))
    if hashlib.sha256(payload).hexdigest() != evidence["sha256"]:
        raise ValueError("地图点位来源校验失败")
    data = json.loads(payload)
    width, height = data["img"]["W"], data["img"]["H"]
    if not isinstance(width, (int, float)) or not isinstance(height, (int, float)) or not math.isfinite(width) or not math.isfinite(height) or width <= 0 or height <= 0 or len(data["maps"]) != 5:
        raise ValueError("地图尺寸或篇章数量不正确")
    chapter_slugs = [chapter.get("slug") for chapter in data["maps"]]
    if len(set(chapter_slugs)) != 5 or set(chapter_slugs) != set(manifest["chapterSlugs"]):
        raise ValueError("地图篇章与路网清单不一致")
    if output.exists():
        with closing(sqlite3.connect(f"{output.resolve().as_uri()}?mode=ro", uri=True)) as previous:
            if previous.execute("PRAGMA application_id").fetchone()[0] != APPLICATION_ID:
                raise ValueError("拒绝替换非地图数据库")
    output.parent.mkdir(parents=True, exist_ok=True)
    handle, name = tempfile.mkstemp(prefix=".map-", suffix=".db", dir=output.parent)
    os.close(handle)
    temporary = Path(name)
    gaps = []
    try:
        with closing(sqlite3.connect(temporary)) as database:
            database.execute("PRAGMA foreign_keys=ON")
            database.executescript("""
CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL);
CREATE TABLE chapters(id TEXT PRIMARY KEY,title TEXT NOT NULL,start_node INTEGER NOT NULL,cell_px REAL NOT NULL,cells_per_turn REAL NOT NULL,hours_per_turn REAL NOT NULL);
CREATE TABLE point_types(id TEXT PRIMARY KEY,title TEXT NOT NULL,color TEXT NOT NULL);
CREATE TABLE nodes(chapter TEXT NOT NULL REFERENCES chapters(id),id INTEGER NOT NULL,x REAL NOT NULL,y REAL NOT NULL,PRIMARY KEY(chapter,id));
CREATE TABLE edges(chapter TEXT NOT NULL REFERENCES chapters(id),id INTEGER NOT NULL,start_node INTEGER NOT NULL,end_node INTEGER NOT NULL,distance REAL NOT NULL CHECK(distance>=0),need INTEGER NOT NULL,directed INTEGER NOT NULL,PRIMARY KEY(chapter,id),FOREIGN KEY(chapter,start_node) REFERENCES nodes(chapter,id),FOREIGN KEY(chapter,end_node) REFERENCES nodes(chapter,id));
CREATE TABLE edge_discoveries(chapter TEXT NOT NULL,edge_id INTEGER NOT NULL,place TEXT NOT NULL,FOREIGN KEY(chapter,edge_id) REFERENCES edges(chapter,id));
CREATE TABLE points(id TEXT PRIMARY KEY,chapter TEXT NOT NULL REFERENCES chapters(id),title TEXT NOT NULL,native_name TEXT NOT NULL,type TEXT NOT NULL REFERENCES point_types(id),description TEXT NOT NULL,quests TEXT NOT NULL,x REAL NOT NULL,y REAL NOT NULL,node INTEGER,FOREIGN KEY(chapter,node) REFERENCES nodes(chapter,id));
CREATE INDEX points_chapter ON points(chapter,type,title);
CREATE TABLE materials(id TEXT PRIMARY KEY,title TEXT NOT NULL,category TEXT NOT NULL,effect TEXT NOT NULL);
CREATE TABLE point_materials(point_id TEXT NOT NULL REFERENCES points(id),material_id TEXT NOT NULL REFERENCES materials(id),PRIMARY KEY(point_id,material_id));
""")
            with database:
                for item in data["types"]:
                    database.execute("INSERT INTO point_types VALUES(?,?,?)", (item["jp"], item["zh"], item["c"]))
                for chapter in data["maps"]:
                    slug = chapter["slug"]
                    if slug not in manifest["chapterSlugs"]:
                        raise ValueError("不支持的地图篇章")
                    if chapter["file"] != "/wiki/world-map/world-terrain-watermarked-4608-20261029.webp":
                        raise ValueError("地图底图已变化，必须重新验证坐标")
                    route = json.loads((bundle / f"{slug}-native-routing.json").read_text(encoding="utf8"))
                    if route["chapter"] != slug or route["coordinateSpace"] != "image" or len(route["edges"]) != len(route["edgeRules"]):
                        raise ValueError("地图路网身份或规则数量不符")
                    if not route["nodes"] or route["start"] not in range(len(route["nodes"])):
                        raise ValueError("地图起点超出路网节点")
                    travel_values = (route["cellpx"], route["cellsPerTurn"], route["hoursPerTurn"])
                    if any(not isinstance(value, (int, float)) or not math.isfinite(value) or value <= 0 for value in travel_values):
                        raise ValueError("地图行程参数无效")
                    database.execute("INSERT INTO chapters VALUES(?,?,?,?,?,?)", (slug, chapter["zh"], route["start"], route["cellpx"], route["cellsPerTurn"], route["hoursPerTurn"]))
                    for index, (x, y) in enumerate(route["nodes"]):
                        if not math.isfinite(x) or not math.isfinite(y) or not 0 <= x <= width or not 0 <= y <= height:
                            raise ValueError("路网坐标超出底图")
                        database.execute("INSERT INTO nodes VALUES(?,?,?,?)", (slug, index, x, y))
                    for index, ((start, end, distance), rule) in enumerate(zip(route["edges"], route["edgeRules"])):
                        if not isinstance(distance, (int, float)) or not math.isfinite(distance) or distance < 0:
                            raise ValueError("无效道路长度")
                        required_fame = rule.get("need")
                        required_fame = 0 if required_fame is None else required_fame
                        if not isinstance(required_fame, int) or isinstance(required_fame, bool) or required_fame < 0:
                            raise ValueError("道路名声要求无效")
                        database.execute("INSERT INTO edges VALUES(?,?,?,?,?,?,?)", (slug, index, start, end, distance, required_fame, int(rule.get("directed", False))))
                        for discovery in rule.get("discoveryAny", []):
                            if not isinstance(discovery, str) or not discovery.strip():
                                raise ValueError("道路探索条件无效")
                            database.execute("INSERT INTO edge_discoveries VALUES(?,?,?)", (slug, index, discovery))
                    for point in chapter["pins"]:
                        x, y = point["x"], point["y"]
                        if not isinstance(x, (int, float)) or not isinstance(y, (int, float)) or not math.isfinite(x) or not math.isfinite(y) or not 0 <= x <= width or not 0 <= y <= height:
                            raise ValueError("地点坐标超出底图")
                        node = route["pin2node"].get(point["k"])
                        if node is None:
                            gaps.append(point["k"])
                        database.execute("INSERT INTO points VALUES(?,?,?,?,?,?,?,?,?,?)", (point["k"], slug, point["n"], point.get("j", ""), point["t"], point.get("d", ""), point.get("rt", ""), x, y, node))
                for material in data["mats"]:
                    database.execute("INSERT INTO materials VALUES(?,?,?,?)", (material["jp"], material["zh"], material.get("cat", ""), material.get("eff", "")))
                    for pin in set(material["at"]):
                        if database.execute("SELECT 1 FROM points WHERE id=?", (pin,)).fetchone():
                            database.execute("INSERT INTO point_materials VALUES(?,?)", (pin, material["jp"]))
                database.executemany("INSERT INTO metadata VALUES(?,?)", [("width", str(width)), ("height", str(height)),
                    ("source_sha256", evidence["sha256"]), ("source_url", evidence["url"]),
                    ("terrain_sha256", hashlib.sha256((bundle / "world-terrain-watermarked-4608-20261029.webp").read_bytes()).hexdigest())])
                database.execute(f"PRAGMA application_id={APPLICATION_ID}")
                database.execute("PRAGMA user_version=1")
            if database.execute("PRAGMA foreign_key_check").fetchall() or database.execute("PRAGMA quick_check").fetchone() != ("ok",):
                raise ValueError("地图数据库完整性检查失败")
            counts = {table: database.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0] for table in ["chapters", "points", "nodes", "edges", "materials"]}
        os.replace(temporary, output)
    finally:
        temporary.unlink(missing_ok=True)
    output.with_suffix(".sha256").write_text(hashlib.sha256(output.read_bytes()).hexdigest() + "\n", encoding="ascii")
    report = {"counts": counts, "unrouted_points": gaps, "source_sha256": evidence["sha256"]}
    output.with_suffix(".report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf8")
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="将公开地图点位与路网转换为离线SQLite")
    parser.add_argument("--source", type=Path, default=Path("data/fireemblem-top/world-map"))
    parser.add_argument("--output", type=Path, default=Path("data/derived/world-map/maps.db"))
    args = parser.parse_args()
    print(json.dumps(build_map_database(args.source, args.output), ensure_ascii=False))
