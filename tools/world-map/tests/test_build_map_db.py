from __future__ import annotations

from contextlib import closing
import hashlib
import json
from pathlib import Path
import sqlite3
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "tools" / "world-map"))

from build_map_db import APPLICATION_ID, build_map_database


class MapDatabaseBuilderTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.source = self.root / "source"
        self.bundle = self.source / "downloaded-bundle"
        self.bundle.mkdir(parents=True)
        self.output = self.root / "derived" / "maps.db"
        slugs = ["kai", "dietrich", "theodora", "leda", "salvation"]
        terrain = b"validated image bytes"
        terrain_name = "world-terrain-watermarked-4608-20261029.webp"
        (self.bundle / terrain_name).write_bytes(terrain)
        (self.bundle / "manifest.json").write_text(json.dumps({
            "assets": [{"file": terrain_name, "sha256": hashlib.sha256(terrain).hexdigest()}],
            "chapterSlugs": slugs,
        }), encoding="utf8")
        maps = []
        for index, slug in enumerate(slugs):
            first_key = f"{slug}|start"
            second_key = f"{slug}|missing-route"
            maps.append({
                "slug": slug,
                "zh": slug,
                "file": "/wiki/world-map/world-terrain-watermarked-4608-20261029.webp",
                "pins": [
                    {"k": first_key, "n": f"地点{index}", "j": "場所", "t": "base", "x": 1, "y": 2, "d": ""},
                    {"k": second_key, "n": f"地点{index}未接路", "j": "場所", "t": "base", "x": 3, "y": 4, "d": ""},
                ],
            })
            route = {
                "chapter": slug,
                "coordinateSpace": "image",
                "nodes": [[1.0, 2.0], [3.0, 4.0]],
                "edges": [[0, 1, 2.0]],
                "edgeRules": [{"need": None, "directed": True, "discoveryAny": ["起点"]}],
                "pin2node": {first_key: 0},
                "start": 0,
                "cellpx": 10,
                "cellsPerTurn": 2,
                "hoursPerTurn": 12,
            }
            (self.bundle / f"{slug}-native-routing.json").write_text(json.dumps(route), encoding="utf8")
        data = {
            "img": {"W": 20, "H": 10},
            "maps": maps,
            "types": [{"jp": "base", "zh": "据点", "c": "#123456"}],
            "mats": [{"jp": "item-1", "zh": "草药", "cat": "材料", "eff": "恢复", "at": ["kai|start"]}],
        }
        self._write_live_data(data)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def _write_live_data(self, data: dict) -> None:
        payload = json.dumps(data, ensure_ascii=False, separators=(",", ":")).encode("utf8")
        (self.source / "live-world-map.json").write_bytes(payload)
        (self.source / "live-world-map.source.json").write_text(json.dumps({
            "url": "https://example.invalid/api/world-map",
            "sha256": hashlib.sha256(payload).hexdigest(),
        }), encoding="utf8")

    def test_builds_valid_semantic_database_and_reports_unrouted_points(self) -> None:
        report = build_map_database(self.source, self.output)

        self.assertEqual({"chapters": 5, "points": 10, "nodes": 10, "edges": 5, "materials": 1}, report["counts"])
        self.assertEqual(5, len(report["unrouted_points"]))
        with closing(sqlite3.connect(self.output)) as database:
            self.assertEqual(APPLICATION_ID, database.execute("PRAGMA application_id").fetchone()[0])
            self.assertEqual("ok", database.execute("PRAGMA quick_check").fetchone()[0])
            self.assertEqual([], database.execute("PRAGMA foreign_key_check").fetchall())
            self.assertEqual(0, database.execute("SELECT need FROM edges WHERE chapter='kai'").fetchone()[0])
            self.assertEqual("草药", database.execute("SELECT title FROM materials").fetchone()[0])
            self.assertEqual(1, database.execute("SELECT COUNT(*) FROM point_materials").fetchone()[0])
            self.assertIsNone(database.execute("SELECT node FROM points WHERE id='kai|missing-route'").fetchone()[0])
        self.assertTrue(self.output.with_suffix(".sha256").is_file())
        self.assertTrue(self.output.with_suffix(".report.json").is_file())

    def test_rejects_changed_coordinates_without_replacing_previous_database(self) -> None:
        build_map_database(self.source, self.output)
        original_database = self.output.read_bytes()
        original_checksum = self.output.with_suffix(".sha256").read_bytes()
        data = json.loads((self.source / "live-world-map.json").read_text(encoding="utf8"))
        data["maps"][0]["pins"][0]["x"] = 21
        self._write_live_data(data)

        with self.assertRaisesRegex(ValueError, "地点坐标"):
            build_map_database(self.source, self.output)

        self.assertEqual(original_database, self.output.read_bytes())
        self.assertEqual(original_checksum, self.output.with_suffix(".sha256").read_bytes())

    def test_generates_repeatable_database_bytes(self) -> None:
        build_map_database(self.source, self.output)
        first = self.output.read_bytes()
        build_map_database(self.source, self.output)

        self.assertEqual(first, self.output.read_bytes())

    def test_rejects_invalid_start_node_without_publishing_output(self) -> None:
        route_path = self.bundle / "kai-native-routing.json"
        route = json.loads(route_path.read_text(encoding="utf8"))
        route["start"] = 9
        route_path.write_text(json.dumps(route), encoding="utf8")

        with self.assertRaisesRegex(ValueError, "起点"):
            build_map_database(self.source, self.output)

        self.assertFalse(self.output.exists())

    def test_rejects_untrusted_source_hash_and_asset_traversal(self) -> None:
        source_evidence = self.source / "live-world-map.source.json"
        evidence = json.loads(source_evidence.read_text(encoding="utf8"))
        evidence["sha256"] = "0" * 64
        source_evidence.write_text(json.dumps(evidence), encoding="utf8")
        with self.assertRaisesRegex(ValueError, "点位来源校验失败"):
            build_map_database(self.source, self.output)

        manifest_path = self.bundle / "manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf8"))
        manifest["assets"][0]["file"] = "../outside.bin"
        manifest_path.write_text(json.dumps(manifest), encoding="utf8")
        with self.assertRaisesRegex(ValueError, "地图来源文件校验失败"):
            build_map_database(self.source, self.output)


if __name__ == "__main__":
    unittest.main()
