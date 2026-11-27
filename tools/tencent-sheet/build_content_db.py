from __future__ import annotations

import argparse
from contextlib import closing
from datetime import datetime, timedelta
import hashlib
import json
import os
import re
import sqlite3
import tempfile
import unicodedata
import uuid
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlsplit


SCHEMA_VERSION = 13
APPLICATION_ID = 0x46455749
IDENTIFIER = re.compile(r"[a-z][a-z0-9_]*\Z")
IMAGE_PATH = re.compile(r"assets/[a-zA-Z0-9_-]+\.webp\Z")
NUMBER = re.compile(r"[-+]?(?:[0-9]+(?:\.[0-9]+)?|\.[0-9]+)%?\Z")
SUPPORT_STAT_NAMES = ["物攻", "魔攻", "物防", "魔防", "命中", "回避", "必杀", "必杀回避", "攻速"]
SUPPORT_TYPE_LABELS = {
    "援護": "援护", "攻勢": "攻势", "連携": "连携", "防衛": "防卫",
    "魔道": "魔道", "騎兵": "骑兵", "飛兵": "飞兵", "無し": "无",
}
SUPPORT_TENCENT_GROUPS = [
    ("骑兵系", "騎兵", 3), ("斗士系", "攻勢", 7), ("魔道系", "魔道", 11),
    ("重装系", "防衛", 15), ("猎兵系", "援護", 19), ("剑士系", "連携", 23),
    ("飞行系", "飛兵", 27),
]


class ContentDatabaseError(Exception):
    pass


def build_content_database(package: Path, output: Path, adapters_path: Path,
                           record_sources: dict[str, Path] | None = None) -> dict[str, Any]:
    if output.suffix != ".db" or output.resolve().is_relative_to(package.resolve()):
        raise ContentDatabaseError("资料库输出必须为来源包目录之外的.db文件")
    manifest = _read_json(package / "manifest.json")
    adapters = _read_json(adapters_path)
    images = _read_json(package / "image-assets-manifest.json")
    if manifest.get("schema_version") != 1 or manifest.get("generator") != "fireemblem/tools/tencent-sheet":
        raise ContentDatabaseError("不支持的来源快照版本或生成器")
    if adapters.get("schema_version") != 1 or not adapters.get("categories"):
        raise ContentDatabaseError("图鉴适配器版本或分类列表不合法")
    source = manifest["source"]
    sheets = {item["sheet_id"]: item for item in manifest["sheets"]}
    if len(sheets) != len(manifest["sheets"]):
        raise ContentDatabaseError("来源快照有重复表ID")
    known_images = {item["file"]: item for item in images["assets"]}
    record_sources = record_sources or {}
    category_ids = [adapter["id"] for adapter in adapters["categories"]]
    if len(set(category_ids)) != len(category_ids):
        raise ContentDatabaseError("分类ID重复")
    if output.exists():
        with closing(sqlite3.connect(f"{output.resolve().as_uri()}?mode=ro", uri=True)) as previous:
            if previous.execute("PRAGMA application_id").fetchone()[0] != APPLICATION_ID:
                raise ContentDatabaseError("拒绝替换不属于本生成器的数据库")
    output.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(prefix=".content-", suffix=".db", dir=output.parent)
    os.close(descriptor)
    temporary = Path(temporary_name)
    category_counts = {}
    external_source_records: dict[str, dict[str, Any]] = {}
    external_source_digests: dict[str, str] = {}
    asset_records: dict[str, dict[str, Any]] = {}
    connection = sqlite3.connect(temporary)
    try:
        connection.execute("PRAGMA foreign_keys=ON")
        connection.executescript(_SCHEMA)
        field_views = []
        with connection:
            for position, adapter in enumerate(adapters["categories"]):
                _validate_adapter(adapter)
                record_source_id = adapter.get("record_source")
                if record_source_id:
                    record_source_path = record_sources.get(record_source_id)
                    if record_source_path is None:
                        raise ContentDatabaseError(f"缺少分类记录来源：{record_source_id}")
                    record_source_bytes = record_source_path.read_bytes()
                    record_source_data = json.loads(record_source_bytes)
                    rows = _record_source_rows(record_source_data, adapter)
                    sheet_id = adapter["source_id"]
                    sheet_name = adapter["source_name"]
                    external_source_digests[record_source_id] = _sha256(record_source_bytes)
                    external_source_records[record_source_id] = {
                        "uri": record_source_data["source"]["uri"],
                        "revision": record_source_data["source"]["revision"],
                        "sha256": external_source_digests[record_source_id],
                        "records": len(record_source_data["records"]),
                    }
                else:
                    sheet_id = adapter["sheet"]
                    sheet = sheets.get(sheet_id)
                    if sheet is None or sheet.get("status") != "available":
                        raise ContentDatabaseError(f"分类来源不可用：{sheet_id}")
                    if sheet.get("output_file") != f"tables/{sheet_id}.json":
                        raise ContentDatabaseError(f"来源表路径不合法：{sheet_id}")
                    table_bytes = (package / sheet["output_file"]).read_bytes()
                    if _sha256(table_bytes) != sheet["output_sha256"]:
                        raise ContentDatabaseError(f"来源表校验失败：{sheet_id}")
                    table = json.loads(table_bytes)
                    metadata = table["metadata"]
                    if (table.get("schema_version") != 1 or metadata["sheet_id"] != sheet_id
                            or metadata["revision"] != source["revision"]
                            or metadata["name"] != sheet["name"]
                            or len(table["cells"]) != sheet["stored_cell_count"]):
                        raise ContentDatabaseError(f"来源表身份或计数不匹配：{sheet_id}")
                    if adapter.get("expected_source_revision") not in {None, metadata["revision"]}:
                        raise ContentDatabaseError(f"分类来源版本不匹配：{adapter['id']}")
                    rows = _project_cells(table)
                    header_column = adapter.get("header_column", adapter["title_column"])
                    header = rows.get(adapter["header_row"], {}).get(header_column, {}).get("text", "")
                    if not header.startswith(adapter["header"]):
                        raise ContentDatabaseError(f"分类表头不匹配：{adapter['title']} ({header})")
                category_id = adapter["id"]
                table_name = category_id.replace("-", "_") + "_details"
                connection.execute("INSERT INTO wiki_categories VALUES(?,?,?,?,?,?)", (
                    category_id, adapter["title"], adapter["description"], position, sheet_id,
                    sheet_name if record_source_id else sheet["name"],
                ))
                field_columns = []
                insert_columns = ["entry_id"]
                for field_position, field in enumerate(adapter["fields"]):
                    key = field["key"]
                    field_columns.extend([f"{key} TEXT", f"{key}_image TEXT REFERENCES content_assets(path)"])
                    insert_columns.extend([key, key + "_image"])
                    if field.get("numeric"):
                        field_columns.append(f"{key}_numeric REAL")
                        insert_columns.append(key + "_numeric")
                    connection.execute("INSERT INTO wiki_field_definitions VALUES(?,?,?,?,?)", (
                        category_id, key, field_position, field["label"], int(field.get("numeric", False)),
                    ))
                    field_views.append(
                        f"SELECT entry_id,{field_position} AS position,definition.label,{key} AS value,"
                        f"{key}_image AS image_asset FROM {table_name} JOIN wiki_field_definitions definition "
                        f"ON definition.category_id='{category_id}' AND definition.field_key='{key}' "
                        f"WHERE ({key} IS NOT NULL AND {key}!='') OR {key}_image IS NOT NULL"
                    )
                detail_columns = ["entry_id TEXT PRIMARY KEY REFERENCES wiki_entries(id)", *field_columns]
                connection.execute(f"CREATE TABLE {table_name}(" + ",".join(detail_columns) + ")")
                insert_statement = f"INSERT INTO {table_name}({','.join(insert_columns)}) VALUES("
                insert_statement += ",".join("?" for column in insert_columns) + ")"
                entry_count = 0
                section_config = adapter.get("section_markers")
                section_column = section_config["column"] if section_config else None
                sections = section_config["values"] if section_config else {}
                row_stride = adapter.get("row_stride", 1)
                row_titles = adapter.get("row_titles", {})
                used_title_rows = set()
                current_section = None
                fields_by_column = {
                    field["column"]: field for field in adapter["fields"]
                    if field.get("source", "column") == "column"
                }
                if category_id == "characters":
                    last_affiliation = ""
                    for r_num in sorted(rows):
                        if r_num < adapter["first_row"] or r_num > adapter.get("last_row", r_num):
                            continue
                        row_map = rows[r_num]
                        marker_idx = row_map.get(1, {}).get("text", "").strip()
                        cell_aff = row_map.get(2, {}).get("text", "")
                        collapsed_aff = "".join(cell_aff.split())
                        if collapsed_aff and collapsed_aff not in {"--", "×"}:
                            row_map[2]["text"] = collapsed_aff
                            last_affiliation = collapsed_aff
                        elif marker_idx.startswith("#") and last_affiliation:
                            row_map[2] = {"text": last_affiliation, "image": None, "merged_fill": True}
                for row_number, row in sorted(rows.items()):
                    if row_number < adapter["first_row"] or row_number > adapter.get("last_row", row_number):
                        continue
                    if (row_number - adapter["first_row"]) % row_stride:
                        continue
                    marker_text = row.get(section_column, {}).get("text", "") if section_column else ""
                    source_title = row_titles.get(str(row_number), row.get(adapter["title_column"], {}).get("text", ""))
                    if str(row_number) in row_titles:
                        used_title_rows.add(str(row_number))
                    if marker_text in sections:
                        if source_title and source_title != marker_text:
                            raise ContentDatabaseError(f"分组标记行不能同时包含条目：{adapter['id']}/{row_number}")
                        current_section = sections[marker_text]
                        continue
                    if section_config and marker_text.endswith("篇") and source_title in {"", marker_text}:
                        raise ContentDatabaseError(f"分组标记未配置：{adapter['id']}/{marker_text}")
                    title = source_title
                    if title in {"--", "×"}:
                        title = ""
                    if not title:
                        title = next((row.get(column, {}).get("text", "")
                                      for column in adapter.get("title_fallback_columns", [])
                                      if row.get(column, {}).get("text", "") not in {"", "--", "×"}), "")
                    title = _strip_prefixes(title, adapter.get("strip_title_prefixes", []))
                    if not title or title in {"--", "×"}:
                        continue
                    if section_config and current_section is None:
                        raise ContentDatabaseError(f"条目缺少分组标记：{adapter['id']}/{row_number}")
                    identity = [category_id, title]
                    if section_config:
                        identity.append(current_section)
                    identity.extend(row.get(column, {}).get("text", "")
                                    for column in adapter.get("identity_columns", []))
                    normalized = [unicodedata.normalize("NFC", " ".join(value.split())) for value in identity]
                    entry_id = category_id + ":" + str(uuid.uuid5(uuid.NAMESPACE_URL, json.dumps(normalized, ensure_ascii=False)))
                    subtitle_columns = adapter.get("subtitle_columns") or adapter.get("identity_columns", [])
                    subtitle = " · ".join(dict.fromkeys(_field_text(fields_by_column.get(column), row.get(column, {}))
                                                       for column in subtitle_columns
                                                       if _field_text(fields_by_column.get(column), row.get(column, {}))))
                    image = _register_image(row.get(adapter.get("image_column")), known_images, asset_records, package, connection)
                    try:
                        connection.execute("INSERT INTO wiki_entries VALUES(?,?,?,?,?,?,?)", (
                            entry_id, category_id, title, subtitle, image, row_number, title + " " + subtitle,
                        ))
                    except sqlite3.IntegrityError as error:
                        raise ContentDatabaseError(f"分类身份冲突，需指定明确变体字段：{category_id}/{title}") from error
                    values = [entry_id]
                    search_values = [title, subtitle]
                    for field in adapter["fields"]:
                        if field.get("source", "column") == "section":
                            value = current_section
                            field_image = None
                        else:
                            field_row = row_number + field.get("row_offset", 0)
                            cell = rows.get(field_row, {}).get(field["column"], {})
                            value = _strip_prefixes(_field_text(field, cell), field.get("strip_prefixes", []))
                            field_image = None if field.get("blank_if_merged") and cell.get("merged_fill") else _register_image(
                                cell, known_images, asset_records, package, connection,
                            )
                        if value in {"--", "×"}:
                            value = ""
                        values.extend([value or None, field_image])
                        search_values.extend([field["label"], value])
                        if field.get("numeric"):
                            numeric = float(value.rstrip("%")) if NUMBER.fullmatch(value) else None
                            values.append(numeric)
                    connection.execute(insert_statement, values)
                    connection.execute("UPDATE wiki_entries SET search_text=? WHERE id=?", (" ".join(search_values), entry_id))
                    entry_count += 1
                if used_title_rows != set(row_titles):
                    raise ContentDatabaseError(f"标题映射未全部命中来源条目：{adapter['id']}")
                if entry_count == 0:
                    raise ContentDatabaseError(f"分类没有业务条目：{category_id}")
                category_counts[category_id] = entry_count
            connection.execute("CREATE VIEW wiki_fields AS " + " UNION ALL ".join(field_views))
            relation_gaps = _build_relations(connection, adapters)
            character_learning = _build_character_learning(
                connection, package, sheets, source["revision"], adapters.get("character_learning"),
            )
            gift_preferences = _build_gift_preferences(
                connection, package, sheets, source["revision"], adapters.get("gift_preferences"),
            )
            dining = _build_dining(connection, package, sheets, source["revision"], adapters.get("dining"))
            bird_dialogues = _build_bird_dialogues(
                connection, package, sheets, source["revision"], adapters.get("bird_dialogues"),
            )
            restoration = _build_restoration(
                connection, package, sheets, source, adapters.get("restoration"),
            )
            theater = _build_theater(connection, package, sheets, source, adapters.get("theater"))
            character_routes = _build_character_route_order(
                connection, package, sheets, source["revision"],
            )
            planting_source_path = record_sources.get("planting")
            if planting_source_path is None:
                planting = _build_planting(connection, None)
            else:
                planting_source_bytes = planting_source_path.read_bytes()
                planting_source_data = json.loads(planting_source_bytes)
                planting = _build_planting(connection, planting_source_data)
                planting_digest = _sha256(planting_source_bytes)
                external_source_digests["planting"] = planting_digest
                external_source_records["planting"] = {
                    "uri": planting["source_uri"],
                    "revision": planting["source_revision"],
                    "sha256": planting_digest,
                    "records": planting["locations"] + planting["plants"] + planting["fertilizers"],
                }
            supports_source_path = record_sources.get("supports")
            if supports_source_path is None:
                supports = _build_supports(connection, None, package, sheets, source["revision"])
            else:
                supports_source_bytes = supports_source_path.read_bytes()
                supports_source_data = json.loads(supports_source_bytes)
                supports = _build_supports(
                    connection, supports_source_data, package, sheets, source["revision"],
                )
                supports_digest = _sha256(supports_source_bytes)
                external_source_digests["supports"] = supports_digest
                external_source_records["supports"] = {
                    "uri": supports["source_uri"],
                    "revision": supports["source_revision"],
                    "sha256": supports_digest,
                    "records": supports["pairs"] + supports["dialogue_conditions"],
                }
            mechanics_source_path = record_sources.get("mechanics")
            if mechanics_source_path is None:
                mechanics = _build_mechanics(connection, None)
            else:
                mechanics_source_bytes = mechanics_source_path.read_bytes()
                mechanics_source_data = json.loads(mechanics_source_bytes)
                mechanics = _build_mechanics(connection, mechanics_source_data)
                mechanics_digest = _sha256(mechanics_source_bytes)
                external_source_digests["mechanics"] = mechanics_digest
                external_source_records["mechanics"] = {
                    "uri": mechanics["archive_source_uri"],
                    "revision": mechanics["source_revision"],
                    "sha256": mechanics_digest,
                    "records": mechanics["entries"] + mechanics["growth_sections"],
                }
            gs_source_path = record_sources.get("growth-simulator")
            if gs_source_path is None:
                growth_simulator = _build_growth_simulator(connection, None)
            else:
                gs_bytes = gs_source_path.read_bytes()
                gs_data = json.loads(gs_bytes)
                growth_simulator = _build_growth_simulator(connection, gs_data)
                gs_digest = _sha256(gs_bytes)
                external_source_digests["growth-simulator"] = gs_digest
                external_source_records["growth-simulator"] = {
                    "uri": growth_simulator["source_uri"],
                    "revision": growth_simulator["source_revision"],
                    "sha256": gs_digest,
                    "records": growth_simulator["characters"] + growth_simulator["classes"] + growth_simulator["mounts"],
                }
            version_inputs = ((package / "manifest.json").read_bytes() + adapters_path.read_bytes()
                              + (package / "image-assets-manifest.json").read_bytes()
                              + json.dumps(external_source_digests, sort_keys=True).encode("utf-8"))
            content_version = _sha256(version_inputs)
            metadata_records = {
                "schema_version": str(SCHEMA_VERSION), "content_version": content_version,
                "source_revision": str(source["revision"]), "source_uri": source.get("uri", ""),
                "source_title": source.get("title", "火纹资料库"), "category_count": str(len(category_counts)),
                "entry_count": str(sum(category_counts.values())), "asset_count": str(len(asset_records)),
            }
            if bird_dialogues["source_note"]:
                metadata_records["bird_dialogues_source_note"] = bird_dialogues["source_note"]
            connection.executemany("INSERT INTO content_metadata VALUES(?,?)", metadata_records.items())
            connection.execute(f"PRAGMA user_version={SCHEMA_VERSION}")
            connection.execute(f"PRAGMA application_id={APPLICATION_ID}")
        if connection.execute("PRAGMA foreign_key_check").fetchall() or connection.execute("PRAGMA quick_check").fetchone() != ("ok",):
            raise ContentDatabaseError("资料库完整性检查失败")
        connection.close()
        os.replace(temporary, output)
    finally:
        connection.close()
        temporary.unlink(missing_ok=True)
    digest = _sha256(output.read_bytes())
    _atomic_write(output.with_suffix(".sha256"), (digest + "\n").encode("ascii"))
    adapted_sheets = {adapter["sheet"] for adapter in adapters["categories"] if "sheet" in adapter}
    if adapters.get("character_learning"):
        adapted_sheets.add(adapters["character_learning"]["sheet"])
    if adapters.get("gift_preferences"):
        adapted_sheets.add(adapters["gift_preferences"]["sheet"])
    if adapters.get("dining"):
        adapted_sheets.add(adapters["dining"]["sheet"])
    if adapters.get("bird_dialogues"):
        adapted_sheets.add(adapters["bird_dialogues"]["sheet"])
    if adapters.get("restoration"):
        adapted_sheets.add(adapters["restoration"]["sheet"])
    if adapters.get("theater"):
        adapted_sheets.add(adapters["theater"]["sheet"])
    report = {
        "schema_version": SCHEMA_VERSION, "content_version": content_version, "database_sha256": digest,
        "categories": category_counts, "entries": sum(category_counts.values()), "assets": len(asset_records),
        "unadapted_sheets": sorted(set(sheets) - adapted_sheets),
        "unavailable_sheets": manifest.get("unavailable_sheets", []), "bytes": output.stat().st_size,
        "external_sources": external_source_records,
        "relation_gaps": relation_gaps,
        "character_learning": character_learning,
        "gift_preferences": gift_preferences,
        "dining": dining,
        "bird_dialogues": bird_dialogues,
        "planting": planting,
        "supports": supports,
        "mechanics": mechanics,
        "growth_simulator": growth_simulator,
        "restoration": restoration,
        "theater": theater,
        "relations": relation_count(output),
    }
    _atomic_write(output.with_suffix(".report.json"), (json.dumps(report, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))
    return report


def _build_restoration(connection: sqlite3.Connection, package: Path, sheets: dict[str, Any],
                       source: dict[str, Any], adapter: dict[str, Any] | None) -> dict[str, Any]:
    report = {"locations": 0, "requirements": 0, "materials": 0, "linked_materials": 0,
              "gaps": [], "summary_differences": []}
    if adapter is None:
        return report
    sheet_id = adapter["sheet"]
    sheet = sheets.get(sheet_id)
    if sheet is None or sheet.get("status") != "available" or sheet.get("output_file") != f"tables/{sheet_id}.json":
        raise ContentDatabaseError("复兴来源表不可用或路径不合法")
    payload = (package / sheet["output_file"]).read_bytes()
    if _sha256(payload) != sheet["output_sha256"]:
        raise ContentDatabaseError("复兴来源表校验失败")
    table = json.loads(payload)
    metadata = table["metadata"]
    if (table.get("schema_version") != 1 or metadata["sheet_id"] != sheet_id
            or metadata["name"] != sheet["name"] or metadata["revision"] != source["revision"]
            or len(table["cells"]) != sheet["stored_cell_count"]):
        raise ContentDatabaseError("复兴来源表身份或计数不匹配")
    rows = _project_cells(table)
    headers = {2: "复兴地点名称", 3: "世界地图所在地", 4: "复兴所需材料", 6: "复兴所需材料", 7: "总数"}
    for column, expected in headers.items():
        if _header_line(rows, 2, column) != expected:
            raise ContentDatabaseError(f"复兴表头不匹配：{expected}")
    titles = _support_wiki_title_index(connection, "items")
    totals: dict[str, int] = {}
    summaries: dict[str, int] = {}
    location_ids: set[str] = set()
    material_ids: dict[str, str] = {}
    for row_number, row in sorted(rows.items()):
        if row_number < 3:
            continue
        text = lambda column: row.get(column, {}).get("text", "").strip()
        summary_name = _normalize_content_title(text(6))
        if summary_name:
            if summary_name in summaries or not re.fullmatch(r"[1-9][0-9]*", text(7)):
                raise ContentDatabaseError(f"复兴材料总表重复或数量无效：第{row_number}行")
            summaries[summary_name] = int(text(7))
        title, region, evidence = text(2), text(3), text(4)
        if not title:
            if region or evidence:
                raise ContentDatabaseError(f"复兴地点名称缺失：第{row_number}行")
            continue
        if not region or not evidence:
            raise ContentDatabaseError(f"复兴地点资料不完整：第{row_number}行")
        identity = json.dumps([_normalize_content_title(region), _normalize_content_title(title)], ensure_ascii=False)
        location_id = "restoration-location:" + str(uuid.uuid5(uuid.NAMESPACE_URL, identity))
        if location_id in location_ids:
            raise ContentDatabaseError(f"复兴地点重复：{title}")
        location_ids.add(location_id)
        connection.execute("INSERT INTO restoration_locations VALUES(?,?,?,?,?)",
                           (location_id, title, region, row_number, evidence))
        used_materials: set[str] = set()
        for position, token in enumerate(evidence.split("、")):
            match = re.fullmatch(r"\s*(.+?)\s*×\s*([1-9][0-9]*)\s*", token)
            if match is None:
                raise ContentDatabaseError(f"复兴材料格式无效：第{row_number}行 {token}")
            name = _normalize_content_title(match[1])
            quantity = int(match[2])
            if quantity > 1_000_000 or name in used_materials:
                raise ContentDatabaseError(f"复兴材料数量越界或单地点重复：第{row_number}行 {name}")
            used_materials.add(name)
            if name not in material_ids:
                material_id = "restoration-material:" + str(uuid.uuid5(uuid.NAMESPACE_URL, name))
                material_ids[name] = material_id
                matches = titles.get(name, [])
                status = "matched" if len(matches) == 1 else "ambiguous" if matches else "missing"
                item_id = matches[0] if status == "matched" else None
                connection.execute("INSERT INTO restoration_materials VALUES(?,?,?,?)", (material_id, name, item_id, status))
                if item_id:
                    report["linked_materials"] += 1
                else:
                    report["gaps"].append({"material": name, "reason": status, "source_row": row_number})
            connection.execute("INSERT INTO restoration_requirements VALUES(?,?,?,?,?)",
                               (location_id, material_ids[name], quantity, position, token.strip()))
            totals[name] = totals.get(name, 0) + quantity
            report["requirements"] += 1
    if not location_ids:
        raise ContentDatabaseError("复兴来源没有地点")
    for name in sorted(totals.keys() | summaries.keys()):
        if totals.get(name) != summaries.get(name):
            report["summary_differences"].append({"material": name, "computed": totals.get(name), "source": summaries.get(name)})
    connection.executemany("INSERT INTO restoration_metadata VALUES(?,?)", {
        "source_sheet_id": sheet_id, "source_sheet_name": sheet["name"],
        "source_revision": str(source["revision"]), "source_uri": source.get("uri", ""),
        "source_sha256": _sha256(payload), "access_note": rows.get(16, {}).get(9, {}).get("text", ""),
    }.items())
    report.update(locations=len(location_ids), materials=len(material_ids), total_units=sum(totals.values()),
                  source_sheet_id=sheet_id, source_revision=source["revision"])
    return report


def _build_theater(connection: sqlite3.Connection, package: Path, sheets: dict[str, Any],
                   source: dict[str, Any], adapter: dict[str, Any] | None) -> dict[str, Any]:
    report = {"plays": 0, "cues": 0, "recorded_rewards": 0, "preferences": 0, "gaps": []}
    if adapter is None:
        return report

    def load(sheet_id):
        sheet = sheets.get(sheet_id)
        if (sheet is None or sheet.get("status") != "available"
                or sheet.get("output_file") != f"tables/{sheet_id}.json"):
            raise ContentDatabaseError("剧场来源表不可用或路径不合法")
        payload = (package / sheet["output_file"]).read_bytes()
        if _sha256(payload) != sheet["output_sha256"]:
            raise ContentDatabaseError("剧场来源表校验失败")
        table = json.loads(payload)
        metadata = table["metadata"]
        if (table.get("schema_version") != 1 or metadata["sheet_id"] != sheet_id
                or metadata["name"] != sheet["name"] or metadata["revision"] != source["revision"]
                or len(table["cells"]) != sheet["stored_cell_count"]):
            raise ContentDatabaseError("剧场来源表身份或计数不匹配")
        return sheet, payload, _project_cells(table)

    sheet_id = adapter["sheet"]
    sheet, payload, rows = load(sheet_id)
    ratings = {7: "PERFECT", 8: "GREAT", 9: "GOOD", 10: "BAD"}
    for row, rating in ratings.items():
        if _header_line(rows, row, 1) != rating:
            raise ContentDatabaseError(f"剧场评价表头不匹配：{rating}")
    for row, columns in rows.items():
        if any(cell["image"] for cell in columns.values()):
            raise ContentDatabaseError("剧场出现未适配图片，请核查来源")
        if row not in range(1, 11) and any(cell["text"] or cell["image"] for cell in columns.values()):
            raise ContentDatabaseError(f"剧场出现未适配行：{row}")
        if row == 5 and columns.get(1, {}).get("text"):
            raise ContentDatabaseError("剧场出现未适配说明：A5")
    for position, row in enumerate((2, 3, 4, 6)):
        text = rows.get(row, {}).get(1, {}).get("text", "")
        if text:
            connection.execute("INSERT INTO theater_notes VALUES(?,?,?,?,?)",
                               (position, row, f"A{row}", "reward" if row == 6 else "tip", text))
    titles = set()
    columns = sorted({column for values in rows.values() for column in values if column > 1})
    for column in columns:
        title = rows.get(1, {}).get(column, {}).get("text", "").strip()
        has_data = any(values.get(column, {}).get("text") or values.get(column, {}).get("image") for values in rows.values())
        if not title:
            if has_data:
                raise ContentDatabaseError(f"剧场剧目标题缺失：第{column}列")
            continue
        normalized = _normalize_content_title(title)
        if normalized in titles:
            raise ContentDatabaseError(f"剧场剧目重复：{title}")
        titles.add(normalized)
        play_id = "theater-play:" + str(uuid.uuid5(uuid.NAMESPACE_URL, normalized))
        connection.execute("INSERT INTO theater_plays VALUES(?,?,?,?)", (play_id, title, column, report["plays"]))
        report["plays"] += 1
        column_label = ""
        quotient = column
        while quotient:
            quotient, remainder = divmod(quotient - 1, 26)
            column_label = chr(65 + remainder) + column_label
        missing_rows = []
        for row in range(2, 7):
            text = rows.get(row, {}).get(column, {}).get("text", "").strip()
            address = f"{column_label}{row}"
            if text:
                options = [line.strip() for line in text.splitlines() if line.strip()]
                connection.execute("INSERT INTO theater_play_cues VALUES(?,?,?,?,?,?)",
                                   (play_id, row - 2, row, address, text, json.dumps(options, ensure_ascii=False)))
                report["cues"] += 1
                if row > 4:
                    report["gaps"].append({"play": title, "cell": address, "reason": "unverified_cue_position"})
            else:
                missing_rows.append(row)
        if missing_rows:
            report["gaps"].append({"play": title, "source_rows": missing_rows, "reason": "blank_cue_rows"})
        for position, (row, rating) in enumerate(ratings.items()):
            reward = rows.get(row, {}).get(column, {}).get("text", "").strip() or None
            address = f"{column_label}{row}"
            connection.execute("INSERT INTO theater_play_rewards VALUES(?,?,?,?,?,?)",
                               (play_id, rating, position, row, address, reward))
            if reward is None:
                report["gaps"].append({"play": title, "cell": address, "rating": rating, "reason": "missing_reward"})
            else:
                report["recorded_rewards"] += 1
    if not titles:
        raise ContentDatabaseError("剧场来源没有剧目")
    activity_id = adapter["activity_sheet"]
    activity_sheet, activity_payload, activity_rows = load(activity_id)
    if _header_line(activity_rows, 1, 35) != "每周可做活动（每周日重置）":
        raise ContentDatabaseError("剧场每周活动表头不匹配")
    for column, expected in {35: "内容", 36: "人数", 37: "效果", 38: "备注"}.items():
        if _header_line(activity_rows, 2, column) != expected:
            raise ContentDatabaseError(f"剧场活动表头不匹配：{expected}")
    for row, identity, label, count in [(7, "watch", "观剧", 2), (8, "perform", "出演", 3)]:
        text = lambda column: activity_rows.get(row, {}).get(column, {}).get("text", "").strip()
        if text(35).split("\n", 1)[0] != label or text(36) != str(count) or not text(37) or not text(38):
            raise ContentDatabaseError(f"剧场活动资料不完整：第{row}行")
        connection.execute("INSERT INTO theater_activities VALUES(?,?,?,?,?,?,?,?)",
                           (identity, text(35), count, text(37), text(38), row, f"AI{row}:AL{row}", activity_id))
    quest_title = adapter["related_sidequest_title"]
    matches = _support_wiki_title_index(connection, "side-quests").get(_normalize_content_title(quest_title), [])
    if len(matches) != 1:
        report["gaps"].append({"title": quest_title, "reason": "related_quest_missing_or_ambiguous"})
    connection.executemany("INSERT INTO theater_metadata VALUES(?,?)", {
        "source_sheet_id": sheet_id, "source_sheet_name": sheet["name"],
        "source_revision": str(source["revision"]), "source_uri": source.get("uri", ""),
        "source_sha256": _sha256(payload), "context_note": rows.get(1, {}).get(1, {}).get("text", ""),
        "preference_status": "unrecorded_in_source", "related_sidequest_title": quest_title,
        "related_sidequest_id": matches[0] if len(matches) == 1 else "",
        "activity_sheet_id": activity_id, "activity_sheet_name": activity_sheet["name"],
        "weekly_reset_note": "每周日重置",
        "activity_sha256": _sha256(activity_payload),
    }.items())
    report["gaps"].append({"reason": "character_preferences_unrecorded_in_source"})
    report.update(source_sheet_id=sheet_id, source_revision=source["revision"], activity_sheet_id=activity_id)
    return report


def relation_count(database: Path) -> int:
    with closing(sqlite3.connect(database)) as connection:
        return connection.execute("SELECT COUNT(*) FROM wiki_relations").fetchone()[0]


def _build_relations(connection: sqlite3.Connection, adapters: dict[str, Any]) -> list[dict[str, Any]]:
    gaps = []
    titles: dict[tuple[str, str], list[str]] = {}
    for entry_id, category, title in connection.execute("SELECT id,category_id,title FROM wiki_entries"):
        titles.setdefault((category, title.strip()), []).append(entry_id)
    for adapter in adapters["categories"]:
        table = adapter["id"].replace("-", "_") + "_details"
        for field in adapter["fields"]:
            targets = field.get("references", [])
            if not targets:
                continue
            for entry_id, value in connection.execute(f"SELECT entry_id,{field['key']} FROM {table} WHERE {field['key']} IS NOT NULL"):
                for token in _reference_tokens(value, field.get("reference_syntax", "list")):
                    name = token.strip()
                    if field.get("quantity_reference"):
                        name = re.sub(r"\s*[×*]\s*\d+$", "", name).strip()
                    if not name:
                        continue
                    candidates = [target for category in targets for target in titles.get((category, name), [])]
                    if len(candidates) == 1:
                        if candidates[0] != entry_id:
                            connection.execute("INSERT OR IGNORE INTO wiki_relations VALUES(?,?,?,?)",
                                               (entry_id, candidates[0], field["label"], token.strip()))
                    else:
                        gaps.append({"source_id": entry_id, "field": field["key"], "text": name,
                                     "reason": "ambiguous" if candidates else "not_found"})
    return gaps


def _build_character_learning(connection: sqlite3.Connection, package: Path, sheets: dict[str, Any],
                              source_revision: int, adapter: dict[str, Any] | None) -> dict[str, Any]:
    if adapter is None:
        return {"sheet_id": None, "characters_with_source": 0, "characters_without_source": [],
                "records": 0, "linked_records": 0, "gaps": []}
    _validate_character_learning_adapter(adapter)
    sheet_id = adapter["sheet"]
    sheet = sheets.get(sheet_id)
    if sheet is None or sheet.get("status") != "available":
        raise ContentDatabaseError(f"角色技能来源不可用：{sheet_id}")
    if sheet.get("output_file") != f"tables/{sheet_id}.json":
        raise ContentDatabaseError(f"角色技能来源路径不合法：{sheet_id}")
    table_bytes = (package / sheet["output_file"]).read_bytes()
    if _sha256(table_bytes) != sheet["output_sha256"]:
        raise ContentDatabaseError(f"角色技能来源表校验失败：{sheet_id}")
    table = json.loads(table_bytes)
    metadata = table["metadata"]
    if (table.get("schema_version") != 1 or metadata["sheet_id"] != sheet_id
            or metadata["revision"] != source_revision or metadata["name"] != sheet["name"]
            or len(table["cells"]) != sheet["stored_cell_count"]):
        raise ContentDatabaseError(f"角色技能来源表身份或计数不匹配：{sheet_id}")
    rows = _project_cells(table)
    name_header = rows.get(adapter["header_row"], {}).get(adapter["name_column"], {}).get("text", "")
    if not name_header.startswith(adapter["header"]):
        raise ContentDatabaseError(f"角色技能来源表头不匹配：{name_header}")
    for group in adapter["groups"]:
        header = rows.get(adapter["group_header_row"], {}).get(group["column"], {}).get("text", "")
        if header.strip() != group["label"]:
            raise ContentDatabaseError(f"角色技能分类表头不匹配：{group['label']} ({header})")

    characters_by_title: dict[str, list[str]] = {}
    for entry_id, title in connection.execute("SELECT id,title FROM wiki_entries WHERE category_id='characters'"):
        normalized = unicodedata.normalize("NFC", " ".join(title.split()))
        characters_by_title.setdefault(normalized, []).append(entry_id)
    skills_by_title: dict[str, list[str]] = {}
    for entry_id, title in connection.execute(
            "SELECT id,title FROM wiki_entries WHERE category_id IN ('skills','combat-arts','magic')"):
        normalized = unicodedata.normalize("NFC", " ".join(title.split()))
        skills_by_title.setdefault(normalized, []).append(entry_id)

    source_characters: set[str] = set()
    gaps = []
    record_count = 0
    linked_count = 0
    connection.execute("DELETE FROM character_skill_learnings")
    connection.execute("DELETE FROM character_learning_sources")
    for row_number, row in sorted(rows.items()):
        if row_number < adapter["first_row"]:
            continue
        character_name = row.get(adapter["name_column"], {}).get("text", "").strip()
        if not character_name:
            continue
        normalized_name = unicodedata.normalize("NFC", " ".join(character_name.split()))
        character_ids = characters_by_title.get(normalized_name, [])
        if len(character_ids) != 1:
            gaps.append({"source_row": row_number, "character": character_name,
                         "reason": "ambiguous_character" if character_ids else "character_not_found"})
            continue
        character_id = character_ids[0]
        if character_id in source_characters:
            gaps.append({"source_row": row_number, "character": character_name, "reason": "duplicate_source_row"})
            continue
        source_characters.add(character_id)
        connection.execute("INSERT INTO character_learning_sources VALUES(?,?,?,?)",
                           (character_id, sheet_id, sheet["name"], row_number))
        for group_position, group in enumerate(adapter["groups"]):
            value = row.get(group["column"], {}).get("text", "")
            for position, evidence in enumerate(_learning_tokens(value)):
                skill_name, requirement = _parse_learning_token(evidence)
                normalized_skill = unicodedata.normalize("NFC", " ".join(skill_name.split()))
                candidates = skills_by_title.get(normalized_skill, [])
                skill_entry_id = candidates[0] if len(candidates) == 1 else None
                connection.execute("INSERT INTO character_skill_learnings VALUES(?,?,?,?,?,?,?,?,?,?,?)", (
                    character_id, skill_entry_id, skill_name, group["label"], group_position, requirement,
                    evidence, sheet_id, sheet["name"], row_number, position,
                ))
                record_count += 1
                if skill_entry_id is not None:
                    existing_relation = connection.execute(
                        "SELECT 1 FROM wiki_relations WHERE source_id=? AND target_id=? LIMIT 1",
                        (character_id, skill_entry_id),
                    ).fetchone()
                    if existing_relation is None:
                        connection.execute("INSERT OR IGNORE INTO wiki_relations VALUES(?,?,?,?)", (
                            character_id, skill_entry_id, "可学技能 · " + group["label"], evidence,
                        ))
                    linked_count += 1
                else:
                    gaps.append({"source_row": row_number, "character": character_name,
                                 "group": group["label"], "skill": skill_name,
                                 "reason": "ambiguous_skill" if candidates else "skill_not_found"})

    characters_without_source = [title for entry_id, title in
                                 connection.execute("SELECT id,title FROM wiki_entries WHERE category_id='characters'")
                                 if entry_id not in source_characters]
    return {"sheet_id": sheet_id, "characters_with_source": len(source_characters),
            "characters_without_source": characters_without_source, "records": record_count,
            "linked_records": linked_count, "gaps": gaps}


def _learning_tokens(value: str) -> list[str]:
    return [token.strip() for token in re.split(r"(?:\r?\n|[ \t\u3000]{2,})+", value)
            if token.strip() and token.strip() not in {"--", "×"}]


def _parse_learning_token(value: str) -> tuple[str, str]:
    match = re.fullmatch(r"(.+?)\s*[（(]([^（）()]*)[）)]", value.strip())
    if match is None:
        return value.strip(), ""
    return match.group(1).strip(), match.group(2).strip()


def _validate_character_learning_adapter(adapter: dict[str, Any]) -> None:
    required = ("sheet", "header_row", "header", "name_column", "group_header_row", "first_row", "groups")
    if any(key not in adapter for key in required) or not re.fullmatch(r"[a-zA-Z0-9]+", adapter["sheet"]):
        raise ContentDatabaseError("角色技能适配器缺少必要字段")
    if not adapter["groups"]:
        raise ContentDatabaseError("角色技能适配器没有分类")
    columns = [group.get("column") for group in adapter["groups"]]
    labels = [group.get("label") for group in adapter["groups"]]
    if (any(not isinstance(column, int) or column < 1 for column in columns)
            or len(set(columns)) != len(columns) or any(not isinstance(label, str) or not label for label in labels)
            or len(set(labels)) != len(labels)):
        raise ContentDatabaseError("角色技能分类列或名称不合法")


def _build_gift_preferences(connection: sqlite3.Connection, package: Path, sheets: dict[str, Any],
                            source_revision: int, adapter: dict[str, Any] | None) -> dict[str, Any]:
    if adapter is None:
        return {"sheet_id": None, "characters_with_source": 0, "characters_without_source": [],
                "records": 0, "linked_records": 0, "gaps": []}
    _validate_gift_preferences_adapter(adapter)
    sheet_id = adapter["sheet"]
    sheet = sheets.get(sheet_id)
    if sheet is None or sheet.get("status") != "available":
        raise ContentDatabaseError(f"礼物偏好来源不可用：{sheet_id}")
    if sheet.get("output_file") != f"tables/{sheet_id}.json":
        raise ContentDatabaseError(f"礼物偏好来源路径不合法：{sheet_id}")
    table_bytes = (package / sheet["output_file"]).read_bytes()
    if _sha256(table_bytes) != sheet["output_sha256"]:
        raise ContentDatabaseError(f"礼物偏好来源表校验失败：{sheet_id}")
    table = json.loads(table_bytes)
    metadata = table["metadata"]
    if (table.get("schema_version") != 1 or metadata["sheet_id"] != sheet_id
            or metadata["revision"] != source_revision or metadata["name"] != sheet["name"]
            or len(table["cells"]) != sheet["stored_cell_count"]):
        raise ContentDatabaseError(f"礼物偏好来源表身份或计数不匹配：{sheet_id}")
    rows = _project_cells(table)
    name_header = rows.get(adapter["header_row"], {}).get(adapter["name_column"], {}).get("text", "")
    if not name_header.startswith(adapter["header"]):
        raise ContentDatabaseError(f"礼物偏好表头不匹配：{name_header}")
    for tier in adapter["tiers"]:
        header = rows.get(adapter["tier_header_row"], {}).get(tier["column"], {}).get("text", "")
        if header.splitlines()[0].strip() != tier["header"]:
            raise ContentDatabaseError(f"礼物偏好等级表头不匹配：{tier['header']} ({header})")

    characters_by_title: dict[str, list[str]] = {}
    for entry_id, title in connection.execute("SELECT id,title FROM wiki_entries WHERE category_id='characters'"):
        normalized = unicodedata.normalize("NFC", " ".join(title.split()))
        characters_by_title.setdefault(normalized, []).append(entry_id)
    gifts_by_title: dict[str, list[str]] = {}
    for entry_id, title in connection.execute("SELECT id,title FROM wiki_entries WHERE category_id='gifts'"):
        normalized = unicodedata.normalize("NFC", " ".join(title.split()))
        gifts_by_title.setdefault(normalized, []).append(entry_id)

    source_characters: set[str] = set()
    preference_pairs: dict[tuple[str, str], dict[str, Any]] = {}
    conflicted_pairs: set[tuple[str, str]] = set()
    gaps = []
    record_count = 0
    linked_count = 0
    connection.execute("DELETE FROM character_gift_preferences")
    connection.execute("DELETE FROM character_gift_sources")
    for row_number, row in sorted(rows.items()):
        if row_number < adapter["first_row"]:
            continue
        character_name = row.get(adapter["name_column"], {}).get("text", "").strip()
        if not character_name:
            continue
        normalized_name = unicodedata.normalize("NFC", " ".join(character_name.split()))
        character_ids = characters_by_title.get(normalized_name, [])
        if len(character_ids) != 1:
            gaps.append({"source_row": row_number, "character": character_name,
                         "reason": "ambiguous_character" if character_ids else "character_not_found"})
            continue
        character_id = character_ids[0]
        if character_id in source_characters:
            gaps.append({"source_row": row_number, "character": character_name, "reason": "duplicate_source_row"})
            continue
        source_characters.add(character_id)
        category_text = row.get(adapter["category_column"], {}).get("text", "").strip()
        connection.execute("INSERT INTO character_gift_sources VALUES(?,?,?,?,?)",
                           (character_id, sheet_id, sheet["name"], row_number, category_text or None))
        for tier in adapter["tiers"]:
            cell_text = row.get(tier["column"], {}).get("text", "")
            for evidence in _gift_tokens(cell_text):
                record_count += 1
                normalized_gift = unicodedata.normalize("NFC", " ".join(evidence.split()))
                gift_ids = gifts_by_title.get(normalized_gift, [])
                if len(gift_ids) != 1:
                    gaps.append({"source_row": row_number, "source_column": tier["column"],
                                 "character": character_name, "gift": evidence,
                                 "reason": "ambiguous_gift" if gift_ids else "gift_not_found"})
                    continue
                gift_id = gift_ids[0]
                pair = (character_id, gift_id)
                if pair in conflicted_pairs:
                    continue
                previous = preference_pairs.get(pair)
                if previous is not None:
                    if previous["level"] == tier["level"]:
                        gaps.append({"source_row": row_number, "source_column": tier["column"],
                                     "character": character_name, "gift": evidence,
                                     "previous_source_row": previous["source_row"],
                                     "previous_source_column": previous["source_column"],
                                     "reason": "duplicate_preference_entry"})
                        continue
                    connection.execute(
                        "DELETE FROM character_gift_preferences WHERE character_id=? AND gift_entry_id=?",
                        pair,
                    )
                    preference_pairs.pop(pair)
                    conflicted_pairs.add(pair)
                    linked_count -= 1
                    gaps.append({"source_row": row_number, "source_column": tier["column"],
                                 "character": character_name, "gift": evidence,
                                 "previous_source_row": previous["source_row"],
                                 "previous_source_column": previous["source_column"],
                                 "previous_level": previous["level"], "level": tier["level"],
                                 "reason": "conflicting_preference_tiers"})
                    continue
                connection.execute("INSERT INTO character_gift_preferences VALUES(?,?,?,?,?,?,?,?)", (
                    character_id, gift_id, tier["level"], evidence, sheet_id, sheet["name"], row_number,
                    tier["column"],
                ))
                preference_pairs[pair] = {
                    "level": tier["level"], "source_row": row_number, "source_column": tier["column"],
                }
                linked_count += 1

    characters_without_source = [title for entry_id, title in
                                 connection.execute("SELECT id,title FROM wiki_entries WHERE category_id='characters'")
                                 if entry_id not in source_characters]
    return {"sheet_id": sheet_id, "characters_with_source": len(source_characters),
            "characters_without_source": characters_without_source, "records": record_count,
            "linked_records": linked_count, "gaps": gaps}


def _gift_tokens(value: str) -> list[str]:
    return [token.strip() for token in re.split(r"[,，、;；\r\n\t]+", value)
            if token.strip() and token.strip() not in {"--", "×"}]


def _validate_gift_preferences_adapter(adapter: dict[str, Any]) -> None:
    required = ("sheet", "header_row", "header", "name_column", "category_column", "tier_header_row", "first_row", "tiers")
    if any(key not in adapter for key in required) or not re.fullmatch(r"[a-zA-Z0-9]+", adapter["sheet"]):
        raise ContentDatabaseError("礼物偏好适配器缺少必要字段")
    tiers = adapter["tiers"]
    columns = [tier.get("column") for tier in tiers]
    levels = [tier.get("level") for tier in tiers]
    headers = [tier.get("header") for tier in tiers]
    if (len(tiers) != 4 or columns != sorted(set(columns)) or any(not isinstance(column, int) or column < 1 for column in columns)
            or levels != [1, 2, 3, 4] or any(not isinstance(header, str) or not header for header in headers)):
        raise ContentDatabaseError("礼物偏好等级列配置不合法")


def _build_dining(connection: sqlite3.Connection, package: Path, sheets: dict[str, Any],
                  source_revision: int, adapter: dict[str, Any] | None) -> dict[str, Any]:
    if adapter is None:
        return {"sheet_id": None, "characters_with_source": 0, "characters_without_source": [],
                "preference_records": 0, "linked_preferences": 0, "preference_gaps": [],
                "trio_records": 0, "linked_trios": 0, "trio_gaps": []}
    _validate_dining_adapter(adapter)
    sheet_id = adapter["sheet"]
    sheet = sheets.get(sheet_id)
    if sheet is None or sheet.get("status") != "available":
        raise ContentDatabaseError(f"用餐偏好来源不可用：{sheet_id}")
    if sheet.get("output_file") != f"tables/{sheet_id}.json":
        raise ContentDatabaseError(f"用餐偏好来源路径不合法：{sheet_id}")
    table_bytes = (package / sheet["output_file"]).read_bytes()
    if _sha256(table_bytes) != sheet["output_sha256"]:
        raise ContentDatabaseError(f"用餐偏好来源表校验失败：{sheet_id}")
    table = json.loads(table_bytes)
    metadata = table["metadata"]
    if (table.get("schema_version") != 1 or metadata["sheet_id"] != sheet_id
            or metadata["revision"] != source_revision or metadata["name"] != sheet["name"]
            or len(table["cells"]) != sheet["stored_cell_count"]):
        raise ContentDatabaseError(f"用餐偏好来源表身份或计数不匹配：{sheet_id}")
    rows = _project_cells(table)
    character_header = _header_line(rows, adapter["character_header_row"], adapter["name_column"])
    if character_header != adapter["character_header"]:
        raise ContentDatabaseError(f"用餐角色表头不匹配：{character_header}")
    for tier in adapter["tiers"]:
        header = _header_line(rows, adapter["tier_header_row"], tier["column"])
        if header != tier["header"]:
            raise ContentDatabaseError(f"用餐偏好等级表头不匹配：{tier['header']} ({header})")
    for member in adapter["trio_members"]:
        header = _header_line(rows, adapter["trio_header_row"], member["header_column"])
        if header != member["header"]:
            raise ContentDatabaseError(f"三人组合角色表头不匹配：{member['header']} ({header})")
    for relation in adapter["trio_relations"]:
        header = _header_line(rows, adapter["trio_header_row"], relation["column"])
        if header != relation["header"]:
            raise ContentDatabaseError(f"三人组合关系表头不匹配：{relation['header']} ({header})")
    minimum_header = _header_line(rows, adapter["trio_header_row"], adapter["trio_minimum"]["column"])
    if minimum_header != adapter["trio_minimum"]["header"]:
        raise ContentDatabaseError(f"三人组合最低等级表头不匹配：{minimum_header}")

    characters_by_title: dict[str, list[str]] = {}
    for entry_id, title in connection.execute("SELECT id,title FROM wiki_entries WHERE category_id='characters'"):
        normalized = unicodedata.normalize("NFC", " ".join(title.split()))
        characters_by_title.setdefault(normalized, []).append(entry_id)
    meals_by_title: dict[str, list[str]] = {}
    for entry_id, title in connection.execute("SELECT id,title FROM wiki_entries WHERE category_id='meals'"):
        normalized = unicodedata.normalize("NFC", " ".join(title.split()))
        meals_by_title.setdefault(normalized, []).append(entry_id)
    if not meals_by_title:
        raise ContentDatabaseError("料理目录尚未生成")

    source_characters: set[str] = set()
    preference_pairs: dict[tuple[str, str], dict[str, Any]] = {}
    conflicted_preferences: set[tuple[str, str]] = set()
    preference_gaps = []
    preference_records = 0
    linked_preferences = 0
    connection.execute("DELETE FROM character_meal_preferences")
    connection.execute("DELETE FROM character_meal_sources")
    for row_number, row in sorted(rows.items()):
        if row_number < adapter["first_row"]:
            continue
        character_name = row.get(adapter["name_column"], {}).get("text", "").strip()
        if not character_name:
            continue
        normalized_name = unicodedata.normalize("NFC", " ".join(character_name.split()))
        character_ids = characters_by_title.get(normalized_name, [])
        if len(character_ids) != 1:
            preference_gaps.append({"source_row": row_number, "character": character_name,
                                    "reason": "ambiguous_character" if character_ids else "character_not_found"})
            continue
        character_id = character_ids[0]
        if character_id in source_characters:
            preference_gaps.append({"source_row": row_number, "character": character_name,
                                    "reason": "duplicate_source_row"})
            continue
        source_characters.add(character_id)
        connection.execute("INSERT INTO character_meal_sources VALUES(?,?,?,?)",
                           (character_id, sheet_id, sheet["name"], row_number))
        for tier in adapter["tiers"]:
            cell_text = row.get(tier["column"], {}).get("text", "")
            for evidence in _gift_tokens(cell_text):
                preference_records += 1
                normalized_meal = unicodedata.normalize("NFC", " ".join(evidence.split()))
                meal_ids = meals_by_title.get(normalized_meal, [])
                if len(meal_ids) != 1:
                    preference_gaps.append({"source_row": row_number, "source_column": tier["column"],
                                            "character": character_name, "meal": evidence,
                                            "reason": "ambiguous_meal" if meal_ids else "meal_not_found"})
                    continue
                meal_id = meal_ids[0]
                pair = (character_id, meal_id)
                if pair in conflicted_preferences:
                    continue
                previous = preference_pairs.get(pair)
                if previous is not None:
                    if previous["level"] == tier["level"]:
                        preference_gaps.append({"source_row": row_number, "source_column": tier["column"],
                                                "character": character_name, "meal": evidence,
                                                "previous_source_row": previous["source_row"],
                                                "previous_source_column": previous["source_column"],
                                                "reason": "duplicate_preference_entry"})
                        continue
                    connection.execute(
                        "DELETE FROM character_meal_preferences WHERE character_id=? AND meal_entry_id=?", pair,
                    )
                    preference_pairs.pop(pair)
                    conflicted_preferences.add(pair)
                    linked_preferences -= 1
                    preference_gaps.append({"source_row": row_number, "source_column": tier["column"],
                                            "character": character_name, "meal": evidence,
                                            "previous_source_row": previous["source_row"],
                                            "previous_source_column": previous["source_column"],
                                            "previous_level": previous["level"], "level": tier["level"],
                                            "reason": "conflicting_preference_tiers"})
                    continue
                connection.execute("INSERT INTO character_meal_preferences VALUES(?,?,?,?,?,?,?,?)", (
                    character_id, meal_id, tier["level"], evidence, sheet_id, sheet["name"], row_number,
                    tier["column"],
                ))
                preference_pairs[pair] = {
                    "level": tier["level"], "source_row": row_number, "source_column": tier["column"],
                }
                linked_preferences += 1

    trio_pairs: dict[tuple[str, ...], dict[str, Any]] = {}
    conflicted_trios: set[tuple[str, ...]] = set()
    trio_gaps = []
    trio_records = 0
    linked_trios = 0
    connection.execute("DELETE FROM dining_trios")
    for row_number, row in sorted(rows.items()):
        if row_number < adapter["trio_first_row"]:
            continue
        names = [row.get(member["column"], {}).get("text", "").strip() for member in adapter["trio_members"]]
        if not any(names):
            continue
        trio_records += 1
        if not all(names):
            trio_gaps.append({"source_row": row_number, "characters": names, "reason": "incomplete_trio"})
            continue
        member_ids = []
        unresolved = []
        for name in names:
            normalized_name = unicodedata.normalize("NFC", " ".join(name.split()))
            candidates = characters_by_title.get(normalized_name, [])
            if len(candidates) != 1:
                unresolved.append({"character": name, "reason": "ambiguous_character" if candidates
                                  else "character_not_found"})
            else:
                member_ids.append(candidates[0])
        if unresolved:
            trio_gaps.append({"source_row": row_number, "characters": names,
                              "reason": "unresolved_trio_character", "details": unresolved})
            continue
        if len(set(member_ids)) != 3:
            trio_gaps.append({"source_row": row_number, "characters": names,
                              "reason": "duplicate_character_in_trio"})
            continue
        relation_values = [row.get(relation["column"], {}).get("text", "").strip() or None
                           for relation in adapter["trio_relations"]]
        minimum = row.get(adapter["trio_minimum"]["column"], {}).get("text", "").strip() or None
        pair_levels = tuple(sorted(
            (tuple(sorted((member_ids[first], member_ids[second]))), relation_values[index])
            for index, (first, second) in enumerate(((0, 1), (0, 2), (1, 2)))
        ))
        trio_key = tuple(sorted(member_ids))
        if trio_key in conflicted_trios:
            continue
        signature = (pair_levels, minimum)
        previous = trio_pairs.get(trio_key)
        if previous is not None:
            if previous["signature"] == signature:
                trio_gaps.append({"source_row": row_number, "characters": names,
                                  "previous_source_row": previous["source_row"], "reason": "duplicate_trio"})
                continue
            connection.execute("DELETE FROM dining_trios WHERE trio_id=?", (previous["trio_id"],))
            trio_pairs.pop(trio_key)
            conflicted_trios.add(trio_key)
            linked_trios -= 1
            trio_gaps.append({"source_row": row_number, "characters": names,
                              "previous_source_row": previous["source_row"],
                              "reason": "conflicting_trio_levels"})
            continue
        trio_id = "dining-trio:" + str(uuid.uuid5(
            uuid.NAMESPACE_URL, json.dumps(["dining-trio", *trio_key], ensure_ascii=False),
        ))
        evidence = "；".join([*(f"角色{index + 1}={name}" for index, name in enumerate(names)),
                             *(f"{relation['header']}={value or '未记录'}"
                               for relation, value in zip(adapter["trio_relations"], relation_values)),
                             f"{adapter['trio_minimum']['header']}={minimum or '未记录'}"])
        connection.execute("INSERT INTO dining_trios VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", (
            trio_id, *member_ids, *relation_values, minimum, evidence, sheet_id, sheet["name"], row_number,
        ))
        trio_pairs[trio_key] = {"trio_id": trio_id, "signature": signature, "source_row": row_number}
        linked_trios += 1

    characters_without_source = [title for entry_id, title in
                                 connection.execute("SELECT id,title FROM wiki_entries WHERE category_id='characters'")
                                 if entry_id not in source_characters]
    return {
        "sheet_id": sheet_id,
        "characters_with_source": len(source_characters),
        "characters_without_source": characters_without_source,
        "meal_count": len(meals_by_title),
        "preference_records": preference_records,
        "linked_preferences": linked_preferences,
        "preference_gaps": preference_gaps,
        "trio_records": trio_records,
        "linked_trios": linked_trios,
        "trio_gaps": trio_gaps,
    }


def _validate_dining_adapter(adapter: dict[str, Any]) -> None:
    required = ("sheet", "character_header_row", "character_header", "name_column", "first_row",
                "tier_header_row", "tiers",
                "trio_first_row", "trio_header_row", "trio_members", "trio_relations", "trio_minimum")
    if any(key not in adapter for key in required) or not re.fullmatch(r"[a-zA-Z0-9]+", adapter["sheet"]):
        raise ContentDatabaseError("用餐数据适配器缺少必要字段")
    positive_rows = (adapter["character_header_row"], adapter["first_row"], adapter["tier_header_row"],
                     adapter["trio_first_row"], adapter["trio_header_row"])
    if any(not isinstance(value, int) or isinstance(value, bool) or value < 1 for value in positive_rows):
        raise ContentDatabaseError("用餐数据行配置不合法")
    positive_columns = [adapter["name_column"], *(tier.get("column") for tier in adapter["tiers"]),
                        *(member.get("column") for member in adapter["trio_members"]),
                        *(member.get("header_column") for member in adapter["trio_members"]),
                        *(relation.get("column") for relation in adapter["trio_relations"]),
                        adapter["trio_minimum"].get("column")]
    if any(not isinstance(value, int) or isinstance(value, bool) or value < 1 for value in positive_columns):
        raise ContentDatabaseError("用餐数据列配置不合法")
    tiers = adapter["tiers"]
    if (len(tiers) != 4 or [tier.get("level") for tier in tiers] != [1, 2, 3, 4]
            or len({tier["column"] for tier in tiers}) != 4
            or any(not isinstance(tier.get("header"), str) or not tier["header"] for tier in tiers)):
        raise ContentDatabaseError("用餐偏好等级配置不合法")
    if (len(adapter["trio_members"]) != 3 or len(adapter["trio_relations"]) != 3
            or len({member["column"] for member in adapter["trio_members"]}) != 3
            or len({relation["column"] for relation in adapter["trio_relations"]}) != 3
            or any(not isinstance(value, str) or not value for value in
                   [adapter["character_header"], *(tier["header"] for tier in tiers),
                    *(member["header"] for member in adapter["trio_members"]),
                    *(relation["header"] for relation in adapter["trio_relations"]),
                    adapter["trio_minimum"].get("header")])):
        raise ContentDatabaseError("三人组合关系列配置不合法")


def _build_bird_dialogues(connection: sqlite3.Connection, package: Path, sheets: dict[str, Any],
                          source_revision: int, adapter: dict[str, Any] | None) -> dict[str, Any]:
    if adapter is None:
        return {"sheet_id": None, "records": 0, "marked_corrected": 0, "unmarked": 0,
                "linked_characters": 0, "character_groups": {}, "source_note": "", "gaps": []}
    _validate_bird_dialogues_adapter(adapter)
    sheet_id = adapter["sheet"]
    sheet = sheets.get(sheet_id)
    if sheet is None or sheet.get("status") != "available":
        raise ContentDatabaseError(f"逗鸟对话来源不可用：{sheet_id}")
    if sheet.get("output_file") != f"tables/{sheet_id}.json":
        raise ContentDatabaseError(f"逗鸟对话来源路径不合法：{sheet_id}")
    table_bytes = (package / sheet["output_file"]).read_bytes()
    if _sha256(table_bytes) != sheet["output_sha256"]:
        raise ContentDatabaseError(f"逗鸟对话来源表校验失败：{sheet_id}")
    table = json.loads(table_bytes)
    metadata = table["metadata"]
    if (table.get("schema_version") != 1 or metadata["sheet_id"] != sheet_id
            or metadata["revision"] != source_revision or metadata["name"] != sheet["name"]
            or len(table["cells"]) != sheet["stored_cell_count"]):
        raise ContentDatabaseError(f"逗鸟对话来源表身份或计数不匹配：{sheet_id}")
    rows = _project_cells(table)
    for header in adapter["headers"]:
        actual = _header_line(rows, adapter["header_row"], header["column"])
        if actual != header["value"]:
            raise ContentDatabaseError(f"逗鸟对话表头不匹配：{header['value']} ({actual})")
    source_note = rows.get(adapter["source_note_row"], {}).get(adapter["source_note_column"], {}).get("text", "").strip()
    if not source_note:
        raise ContentDatabaseError("逗鸟对话来源说明缺失")

    category_id = adapter["category_id"]
    category_source = connection.execute(
        "SELECT source_sheet_id FROM wiki_categories WHERE id=?", (category_id,),
    ).fetchone()
    if category_source is None or category_source[0] != sheet_id:
        raise ContentDatabaseError("逗鸟对话分类与来源表不匹配")
    characters_by_title: dict[str, list[str]] = {}
    for entry_id, title in connection.execute("SELECT id,title FROM wiki_entries WHERE category_id='characters'"):
        normalized = unicodedata.normalize("NFC", " ".join(title.split()))
        characters_by_title.setdefault(normalized, []).append(entry_id)

    entries_by_source_row = dict(connection.execute(
        "SELECT source_row,id FROM wiki_entries WHERE category_id=?", (category_id,),
    ))
    columns = adapter["columns"]
    connection.execute("DELETE FROM bird_dialogues")
    record_count = 0
    marked_corrected = 0
    linked_characters = 0
    groups: dict[str, int] = {}
    markers: dict[str, int] = {}
    gaps = []
    for row_number, row in sorted(rows.items()):
        if row_number < adapter["first_row"]:
            continue
        values = {name: row.get(column, {}).get("text", "").strip() for name, column in columns.items()}
        content_values = [value for name, value in values.items() if name != "correction_marker"]
        if not any(content_values):
            continue
        if not values["translated_prompt"]:
            has_dialogue_content = any(values[name] for name in (
                "character_group", "character_name_english", "original_prompt", "original_reaction",
                "translated_reaction",
            ))
            gaps.append({"source_row": row_number, "character": values["character_name"] or None,
                         "reason": "missing_translated_prompt" if has_dialogue_content else "source_row_placeholder"})
            continue
        required_values = ("character_name", "translated_prompt", "translated_reaction")
        if any(not values[name] for name in required_values):
            raise ContentDatabaseError(f"逗鸟对话必填字段缺失：第{row_number}行")
        entry_id = entries_by_source_row.get(row_number)
        if entry_id is None:
            raise ContentDatabaseError(f"逗鸟对话未生成语义条目：第{row_number}行")
        character_name = values["character_name"]
        normalized_name = unicodedata.normalize("NFC", " ".join(character_name.split()))
        candidates = characters_by_title.get(normalized_name, [])
        character_entry_id = candidates[0] if len(candidates) == 1 else None
        if character_entry_id:
            linked_characters += 1
        else:
            gaps.append({"source_row": row_number, "character": character_name,
                         "reason": "ambiguous_character" if candidates else "character_not_found"})
        marker = values["correction_marker"]
        markers[marker or "<blank>"] = markers.get(marker or "<blank>", 0) + 1
        is_marked_corrected = adapter["corrected_marker"] in marker
        if marker and not is_marked_corrected:
            gaps.append({"source_row": row_number, "correction_marker": marker,
                         "reason": "unknown_correction_marker"})
        marked_corrected += int(is_marked_corrected)
        source_group = values["character_group"]
        group = source_group if source_group in adapter["character_groups"] else None
        if group is None:
            gaps.append({"source_row": row_number, "character_group": source_group or None,
                         "reason": "missing_character_group" if not source_group else "unknown_character_group"})
        group_key = group or "<unclassified>"
        groups[group_key] = groups.get(group_key, 0) + 1
        for field_name in ("character_name_english", "original_prompt", "original_reaction"):
            if not values[field_name]:
                gaps.append({"source_row": row_number, "field": field_name, "reason": "missing_optional_source_text"})
        connection.execute("INSERT INTO bird_dialogues VALUES(?,?,?,?,?,?,?,?,?,?)", (
            entry_id, character_entry_id, group, character_name, values["character_name_english"] or None,
            values["original_prompt"] or None, values["translated_prompt"], values["original_reaction"] or None,
            values["translated_reaction"], int(is_marked_corrected),
        ))
        record_count += 1

    return {
        "sheet_id": sheet_id,
        "records": record_count,
        "marked_corrected": marked_corrected,
        "unmarked": record_count - marked_corrected,
        "linked_characters": linked_characters,
        "character_groups": dict(sorted(groups.items())),
        "correction_markers": dict(sorted(markers.items())),
        "source_note": source_note,
        "gaps": gaps,
    }


def _build_planting(connection: sqlite3.Connection, source_data: dict[str, Any] | None) -> dict[str, Any]:
    if source_data is None:
        return {
            "available": False, "source_uri": None, "source_revision": None, "locations": 0,
            "plants": 0, "fertilizers": 0, "map_markers": 0, "location_map_markers": 0,
            "fertilizer_map_markers": 0, "matched_plants": 0, "matched_fertilizers": 0, "gaps": [],
        }
    if not isinstance(source_data, dict):
        raise ContentDatabaseError("种植资料投影格式无效")
    if source_data.get("schema_version") != 1:
        raise ContentDatabaseError("种植资料投影版本不受支持")
    source = source_data.get("source")
    if not isinstance(source, dict) or not isinstance(source.get("uri"), str) or not source["uri"].startswith("https://"):
        raise ContentDatabaseError("种植资料来源身份不合法")
    page_sha256 = source.get("page_sha256")
    if not isinstance(page_sha256, str) or not re.fullmatch(r"[0-9a-f]{64}", page_sha256):
        raise ContentDatabaseError("种植页面来源校验值不合法")
    if source.get("revision") != page_sha256:
        raise ContentDatabaseError("种植资料修订号与页面校验值不匹配")
    locations = source_data.get("locations")
    plants = source_data.get("plants")
    fertilizers = source_data.get("fertilizers")
    harvest = source_data.get("harvest_parameters")
    notes = source_data.get("source_notes")
    if not all(isinstance(records, list) and records for records in (locations, plants, fertilizers)):
        raise ContentDatabaseError("种植地点、植物或肥料来源列表为空")
    if not isinstance(harvest, dict) or not isinstance(notes, dict):
        raise ContentDatabaseError("种植参数或说明缺失")
    required_notes = ("usage_note", "fertilizer_note")
    if any(not isinstance(notes.get(key), str) or not notes[key].strip() for key in required_notes):
        raise ContentDatabaseError("种植来源说明不完整")
    source_uri = source["uri"]
    source_revision = source["revision"]
    item_candidates: dict[str, list[tuple[str, str | None]]] = {}
    for entry_id, title, image_asset in connection.execute(
            "SELECT id,title,image_asset FROM wiki_entries WHERE category_id='items'"):
        normalized = _normalize_planting_name(title)
        item_candidates.setdefault(normalized, []).append((entry_id, image_asset))

    gaps = []
    plant_ids: dict[str, str] = {}
    plant_matches = {}
    seen_plant_names: set[str] = set()
    first_plant_rows: dict[str, int] = {}
    for location in locations:
        name = _required_planting_text(location, "plant_name", "种植地点")
        first_plant_rows.setdefault(_normalize_planting_name(name), _required_planting_row(location))
    for plant in plants:
        name = _required_planting_text(plant, "name", "稀有植物")
        normalized = _normalize_planting_name(name)
        if normalized in seen_plant_names:
            raise ContentDatabaseError(f"稀有植物名称重复：{name}")
        seen_plant_names.add(normalized)
        plant_id = _planting_id("plant", name)
        candidates = item_candidates.get(normalized, [])
        matched = candidates[0] if len(candidates) == 1 else None
        match_status = "matched" if matched else "ambiguous" if candidates else "missing"
        if matched is None:
            gaps.append({"kind": "plant", "title": name, "reason": match_status})
        plant_ids[normalized] = plant_id
        plant_matches[normalized] = (matched[0], matched[1], match_status) if matched else (None, None, match_status)
        connection.execute(
            "INSERT INTO planting_plants VALUES(?,?,?,?,?,?)",
            (plant_id, name, plant_matches[normalized][0], plant_matches[normalized][1], match_status,
             first_plant_rows.get(normalized, 1)),
        )

    fertilizer_ids: dict[str, str] = {}
    fertilizer_matches: dict[str, tuple[str | None, str | None, str]] = {}
    seen_fertilizer_names: set[str] = set()
    fertilizer_marker_count = 0
    used_map_ids: set[str] = set()
    route_ids = {"カイ": "kai", "救世編": "salvation"}
    for fertilizer in fertilizers:
        name = _required_planting_text(fertilizer, "name", "肥料")
        normalized = _normalize_planting_name(name)
        if normalized in seen_fertilizer_names:
            raise ContentDatabaseError(f"肥料名称重复：{name}")
        seen_fertilizer_names.add(normalized)
        fertilizer_id = _planting_id("fertilizer", name)
        candidates = item_candidates.get(normalized, [])
        matched = candidates[0] if len(candidates) == 1 else None
        match_status = "matched" if matched else "ambiguous" if candidates else "missing"
        if matched is None:
            gaps.append({"kind": "fertilizer", "title": name, "reason": match_status})
        fertilizer_ids[normalized] = fertilizer_id
        fertilizer_matches[normalized] = (matched[0], matched[1], match_status) if matched else (None, None, match_status)
        parameter_text = _required_planting_text(fertilizer, "parameter_text", f"肥料：{name}")
        parameter_percent = _planting_percent(fertilizer.get("parameter_percent"), f"肥料：{name}")
        description = fertilizer.get("description", "")
        acquisition_hint = fertilizer.get("acquisition_hint", "")
        if not isinstance(description, str) or not isinstance(acquisition_hint, str):
            raise ContentDatabaseError(f"肥料说明格式无效：{name}")
        connection.execute(
            "INSERT INTO planting_fertilizers VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            (fertilizer_id, name, fertilizer_matches[normalized][0], fertilizer_matches[normalized][1],
             match_status, parameter_text, parameter_percent, description, acquisition_hint,
             _required_planting_row(fertilizer),
             source_uri),
        )
        marker_count = _insert_planting_markers(
            connection, fertilizer.get("map_markers"), None, fertilizer_id, route_ids, used_map_ids,
        )
        fertilizer_marker_count += marker_count

    location_marker_count = 0
    seen_location_names: set[str] = set()
    for location in locations:
        name = _required_planting_text(location, "name", "种植地点")
        normalized_location = _normalize_planting_name(name)
        if normalized_location in seen_location_names:
            raise ContentDatabaseError(f"种植地点名称重复：{name}")
        seen_location_names.add(normalized_location)
        plant_name = _required_planting_text(location, "plant_name", f"种植地点：{name}")
        plant_key = _normalize_planting_name(plant_name)
        plant_id = plant_ids.get(plant_key)
        if plant_id is None:
            raise ContentDatabaseError(f"种植地点引用了未列入植物目录的条目：{name}/{plant_name}")
        location_id = _planting_id("location", name)
        probability_text = _required_planting_text(location, "plant_probability_text", f"种植地点：{name}")
        probability_percent = _planting_percent(location.get("plant_probability_percent"), f"种植地点：{name}")
        description = _required_planting_text(location, "plant_description", f"种植地点：{name}")
        source_row = _required_planting_row(location)
        connection.execute(
            "INSERT INTO planting_locations VALUES(?,?,?,?,?,?,?)",
            (location_id, name, source_row, plant_id, probability_text, probability_percent, description),
        )
        location_marker_count += _insert_planting_markers(
            connection, location.get("map_markers"), location_id, None, route_ids, used_map_ids,
        )

    cycle_turns = harvest.get("cycle_turns")
    yield_count = harvest.get("yield_count")
    if not isinstance(cycle_turns, int) or isinstance(cycle_turns, bool) or cycle_turns < 1:
        raise ContentDatabaseError("收获周期参数无效")
    if not isinstance(yield_count, int) or isinstance(yield_count, bool) or yield_count < 0:
        raise ContentDatabaseError("收获数量参数无效")
    for key, value, status in (
        ("cycle_turns", cycle_turns, "source_recorded"),
        ("yield_count", yield_count, harvest.get("yield_count_status")),
    ):
        evidence_key = "cycle_evidence" if key == "cycle_turns" else "yield_evidence"
        evidence = _required_planting_text(harvest, evidence_key, "收获参数")
        if status not in {"source_recorded", "unverified"}:
            raise ContentDatabaseError(f"收获参数状态无效：{key}")
        connection.execute(
            "INSERT INTO planting_harvest_parameters VALUES(?,?,?,?,?)",
            (key, str(value), float(value), status, evidence),
        )
    probabilities = harvest.get("probabilities")
    if not isinstance(probabilities, list) or not probabilities:
        raise ContentDatabaseError("收获次数概率表为空")
    seen_harvest_numbers: set[int] = set()
    for probability in probabilities:
        number = probability.get("harvest_number") if isinstance(probability, dict) else None
        if not isinstance(number, int) or isinstance(number, bool) or number < 1 or number in seen_harvest_numbers:
            raise ContentDatabaseError("收获次数编号无效或重复")
        seen_harvest_numbers.add(number)
        status = probability.get("verification_status")
        if status != "unverified":
            raise ContentDatabaseError("收获次数概率必须保留待核实状态")
        percent = _planting_percent(probability.get("probability_percent"), f"第{number}次收获概率")
        text = _required_planting_text(probability, "probability_text", "收获次数概率")
        connection.execute(
            "INSERT INTO planting_harvest_probabilities VALUES(?,?,?,?)",
            (number, text, percent, status),
        )
    probability_note = _required_planting_text(harvest, "probability_note", "收获概率说明")
    for key, value in (
        ("source_uri", source_uri), ("source_revision", source_revision), ("page_sha256", page_sha256),
        ("usage_note", notes["usage_note"]), ("fertilizer_note", notes["fertilizer_note"]),
        ("probability_note", probability_note),
    ):
        connection.execute("INSERT INTO planting_metadata VALUES(?,?)", (key, value))

    matched_plants = sum(match[2] == "matched" for match in plant_matches.values())
    matched_fertilizers = sum(match[2] == "matched" for match in fertilizer_matches.values())
    return {
        "available": True,
        "source_uri": source_uri,
        "source_revision": source_revision,
        "locations": len(locations),
        "plants": len(plants),
        "fertilizers": len(fertilizers),
        "map_markers": len(used_map_ids),
        "location_map_markers": location_marker_count,
        "fertilizer_map_markers": fertilizer_marker_count,
        "matched_plants": matched_plants,
        "matched_fertilizers": matched_fertilizers,
        "gaps": gaps,
    }


def _build_growth_simulator(connection: sqlite3.Connection, source_data: dict[str, Any] | None) -> dict[str, Any]:
    if source_data is None:
        return {
            "characters": 0,
            "classes": 0,
            "mounts": 0,
            "source_uri": "",
            "source_revision": "",
        }
    if source_data.get("schema_version") != 1:
        raise ContentDatabaseError("成长模拟器来源版本不受支持")
    source = source_data.get("source")
    characters = source_data.get("characters")
    classes = source_data.get("classes")
    mounts = source_data.get("mounts")
    if (not isinstance(source, dict) or not isinstance(characters, list) or not characters
            or not isinstance(classes, list) or not classes
            or not isinstance(mounts, list) or not mounts):
        raise ContentDatabaseError("成长模拟器数据不完整")
    source_uri = str(source.get("uri", "")).strip()
    revision = str(source.get("revision", "")).strip()
    if not source_uri or not revision:
        raise ContentDatabaseError("成长模拟器缺少来源信息")
    connection.executemany(
        "INSERT INTO growth_sim_metadata VALUES(?,?)",
        [("source_uri", source_uri), ("source_revision", revision)],
    )
    for position, ch in enumerate(characters):
        connection.execute(
            "INSERT INTO growth_sim_characters VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (
                str(ch["native_id"]).strip(),
                str(ch["code"]).strip(),
                int(ch["record_id"]),
                str(ch["name"]).strip(),
                str(ch["faction"]).strip(),
                str(ch["gender"]).strip(),
                str(ch["start_class"]).strip(),
                int(ch["start_level"]),
                json.dumps(ch["base"]),
                json.dumps(ch["growth"]),
                json.dumps(ch["caps"]),
                int(ch["guarantee_pre"]),
                int(ch["guarantee_part3"]),
                position,
            ),
        )
    for position, cls in enumerate(classes):
        connection.execute(
            "INSERT INTO growth_sim_classes VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (
                str(cls["id"]).strip(),
                int(cls["record_id"]),
                str(cls["name"]).strip(),
                str(cls["tier"]).strip(),
                json.dumps(cls["base"]),
                json.dumps(cls["growth_bonus"]),
                json.dumps(cls["base_growth"]),
                json.dumps(cls["modifier"]),
                json.dumps(cls["caps"]),
                str(cls["mount_type"]).strip(),
                int(cls["mount_multiplier"]),
                json.dumps(cls["allowed_records"]),
                json.dumps(cls["forbidden_people"], ensure_ascii=False),
                position,
            ),
        )
    for position, mount in enumerate(mounts):
        connection.execute(
            "INSERT INTO growth_sim_mounts VALUES(?,?,?,?,?,?,?,?)",
            (
                str(mount["id"]).strip(),
                str(mount["type"]).strip(),
                str(mount["name"]).strip(),
                json.dumps(mount["growth"]),
                json.dumps(mount["attr_bonus"]),
                str(mount["effect"]).strip(),
                str(mount["location"]).strip(),
                position,
            ),
        )
    return {
        "characters": len(characters),
        "classes": len(classes),
        "mounts": len(mounts),
        "source_uri": source_uri,
        "source_revision": revision,
    }


def _build_mechanics(connection: sqlite3.Connection, source_data: dict[str, Any] | None) -> dict[str, Any]:
    if source_data is None:
        return {
            "groups": 0,
            "entries": 0,
            "growth_sections": 0,
            "archive_source_uri": "",
            "growth_source_uri": "",
            "source_revision": "",
        }
    if source_data.get("schema_version") != 1:
        raise ContentDatabaseError("机制资料来源版本不受支持")
    source = source_data.get("source")
    groups = source_data.get("groups")
    entries = source_data.get("entries")
    growth_sections = source_data.get("growth_sections")
    if (not isinstance(source, dict) or not isinstance(groups, list) or not groups
            or not isinstance(entries, list) or not entries
            or not isinstance(growth_sections, list) or not growth_sections):
        raise ContentDatabaseError("机制资料来源结构不完整")
    archive_uri = str(source.get("archive_uri", "")).strip()
    growth_uri = str(source.get("growth_uri", "")).strip()
    revision = str(source.get("revision", "")).strip()
    if not archive_uri or not growth_uri or not revision:
        raise ContentDatabaseError("机制资料来源缺少来源说明")
    connection.executemany(
        "INSERT INTO mechanics_metadata VALUES(?,?)",
        [
            ("archive_source_uri", archive_uri),
            ("growth_source_uri", growth_uri),
            ("source_revision", revision),
        ],
    )
    group_ids: set[str] = set()
    for position, group in enumerate(groups):
        group_id = str(group["id"]).strip()
        group_ids.add(group_id)
        connection.execute(
            "INSERT INTO mechanics_groups VALUES(?,?,?,?,?,?,?)",
            (
                group_id,
                str(group["title"]).strip(),
                str(group["category"]).strip(),
                str(group["description"]).strip(),
                group.get("href"),
                int(group["count"]),
                position,
            ),
        )
    for position, entry in enumerate(entries):
        group_id = str(entry["group"]).strip()
        if group_id not in group_ids:
            raise ContentDatabaseError(f"机制条目引用未知分组：{group_id}")
        paragraphs = [str(p).strip() for p in entry["paragraphs"] if str(p).strip()]
        sources = [str(s).strip() for s in entry.get("sources", []) if str(s).strip()]
        title = str(entry["title"]).strip()
        kind = str(entry["kind"]).strip()
        search_text = " ".join([title, kind, *paragraphs])
        connection.execute(
            "INSERT INTO mechanics_entries VALUES(?,?,?,?,?,?,?,?)",
            (
                str(entry["id"]).strip(),
                title,
                group_id,
                kind,
                json.dumps(paragraphs, ensure_ascii=False),
                json.dumps(sources, ensure_ascii=False),
                position,
                search_text,
            ),
        )
    for position, section in enumerate(growth_sections):
        connection.execute(
            "INSERT INTO growth_mechanics_sections VALUES(?,?,?,?)",
            (
                str(section["id"]).strip(),
                str(section["title"]).strip(),
                position,
                json.dumps(section["blocks"], ensure_ascii=False),
            ),
        )
    return {
        "groups": len(groups),
        "entries": len(entries),
        "growth_sections": len(growth_sections),
        "archive_source_uri": archive_uri,
        "growth_source_uri": growth_uri,
        "source_revision": revision,
    }


def _build_supports(connection: sqlite3.Connection, source_data: dict[str, Any] | None,
                    package: Path, sheets: dict[str, Any], source_revision: int) -> dict[str, Any]:
    if source_data is None:
        return {
            "available": False, "source_uri": None, "source_revision": None,
            "people": 0, "linked_people": 0, "jobs": 0, "linked_jobs": 0,
            "pairs": 0, "pairs_with_bonus": 0, "pairs_with_dialogues": 0,
            "dialogue_conditions": 0, "support_types": 0, "gaps": [],
            "tencent_crosscheck": {"available": False, "reason": "support_source_missing"},
        }
    if not isinstance(source_data, dict) or source_data.get("schema_version") != 1:
        raise ContentDatabaseError("支援关系投影版本或格式无效")
    source = source_data.get("source")
    if not isinstance(source, dict):
        raise ContentDatabaseError("支援关系来源信息缺失")
    for key in ("page_sha256", "bundle_sha256"):
        if not isinstance(source.get(key), str) or not re.fullmatch(r"[0-9a-f]{64}", source[key]):
            raise ContentDatabaseError(f"支援关系来源校验值无效：{key}")
    expected_revision = _sha256((source["page_sha256"] + source["bundle_sha256"]).encode("ascii"))
    if source.get("revision") != expected_revision:
        raise ContentDatabaseError("支援关系修订号与页面/脚本校验值不匹配")
    if (not isinstance(source.get("uri"), str) or not source["uri"].startswith("https://")
            or not isinstance(source.get("bundle_uri"), str) or not source["bundle_uri"].startswith("https://")):
        raise ContentDatabaseError("支援关系来源地址无效")

    stat_names = source_data.get("stat_names")
    thresholds = source_data.get("thresholds")
    people = source_data.get("people")
    jobs = source_data.get("jobs")
    support_types = source_data.get("support_types")
    pairs = source_data.get("pairs")
    if stat_names != SUPPORT_STAT_NAMES:
        raise ContentDatabaseError("支援关系九项属性顺序与当前领域契约不一致")
    if (not isinstance(thresholds, list) or len(thresholds) != 5
            or any(not _support_integer(value) or value < 0 for value in thresholds)
            or thresholds[0] != 0 or any(first >= second for first, second in zip(thresholds, thresholds[1:]))):
        raise ContentDatabaseError("支援等级门槛无效")
    if not isinstance(people, list) or not people or not isinstance(jobs, list) or not jobs:
        raise ContentDatabaseError("支援人物或职业清单为空")
    if not isinstance(support_types, list) or not isinstance(pairs, list):
        raise ContentDatabaseError("支援兵种加成或角色关系清单格式无效")

    people_by_index: list[str] = []
    seen_people: set[str] = set()
    for person in people:
        if not isinstance(person, dict):
            raise ContentDatabaseError("支援人物记录格式无效")
        name = _required_support_text(person, "name", "支援人物")
        display_name = _required_support_text(person, "display_name", f"支援人物：{name}")
        normalized = _normalize_content_title(name)
        if normalized in seen_people:
            raise ContentDatabaseError(f"支援人物名称重复：{name}")
        seen_people.add(normalized)
        people_by_index.append(name)

    type_by_id: dict[str, dict[str, Any]] = {}
    if len(support_types) != len(SUPPORT_TYPE_LABELS):
        raise ContentDatabaseError("支援兵种类型数量不符合来源数据")
    for support_type in support_types:
        if not isinstance(support_type, dict):
            raise ContentDatabaseError("支援兵种类型格式无效")
        type_id = support_type.get("id")
        label = support_type.get("label")
        levels = support_type.get("levels")
        if (type_id not in SUPPORT_TYPE_LABELS or label != SUPPORT_TYPE_LABELS[type_id]
                or type_id in type_by_id or not isinstance(levels, list) or len(levels) != 5):
            raise ContentDatabaseError(f"支援兵种类型字段无效：{type_id}")
        if any(not isinstance(level, list) or len(level) != len(SUPPORT_STAT_NAMES)
               or any(not _support_integer(value) or not -100 <= value <= 100 for value in level)
               for level in levels):
            raise ContentDatabaseError(f"支援兵种等级加成无效：{type_id}")
        if type_id == "無し" and any(any(level) for level in levels):
            raise ContentDatabaseError("无支援类型的加成必须为零")
        type_by_id[type_id] = {"label": label, "levels": levels}
    if set(type_by_id) != set(SUPPORT_TYPE_LABELS):
        raise ContentDatabaseError("支援兵种类型不完整")

    gaps: list[dict[str, Any]] = []
    character_ids_by_name = _support_wiki_title_index(connection, "characters")
    job_ids_by_name = _support_wiki_title_index(connection, "professions")
    linked_people = 0
    for person in people:
        name = person["name"]
        candidates = character_ids_by_name.get(_normalize_content_title(name), [])
        character_id = candidates[0] if len(candidates) == 1 else None
        match_status = "matched" if character_id else "ambiguous" if candidates else "missing"
        if character_id:
            linked_people += 1
        else:
            gaps.append({"kind": "character", "name": name, "reason": match_status})
        connection.execute("INSERT INTO support_people VALUES(?,?,?,?)", (
            name, person["display_name"], character_id, match_status,
        ))

    linked_jobs = 0
    seen_job_ids: set[str] = set()
    seen_job_names: set[str] = set()
    for job in jobs:
        if not isinstance(job, dict):
            raise ContentDatabaseError("支援职业记录格式无效")
        source_job_id = _required_support_text(job, "source_id", "支援职业")
        name = _required_support_text(job, "name", f"支援职业：{source_job_id}")
        display_name = _required_support_text(job, "display_name", f"支援职业：{name}")
        type_id = job.get("support_type")
        type_label = job.get("support_type_label")
        tiers = job.get("tiers")
        if (source_job_id in seen_job_ids or _normalize_content_title(name) in seen_job_names
                or type_id not in type_by_id or type_label != type_by_id[type_id]["label"]
                or not isinstance(tiers, list) or any(not isinstance(tier, str) for tier in tiers)):
            raise ContentDatabaseError(f"支援职业字段无效或重复：{name}")
        seen_job_ids.add(source_job_id)
        seen_job_names.add(_normalize_content_title(name))
        candidates = job_ids_by_name.get(_normalize_content_title(name), [])
        job_entry_id = candidates[0] if len(candidates) == 1 else None
        match_status = "matched" if job_entry_id else "ambiguous" if candidates else "missing"
        if job_entry_id:
            linked_jobs += 1
            image_asset = connection.execute(
                "SELECT image_asset FROM wiki_entries WHERE id=?", (job_entry_id,),
            ).fetchone()[0]
        else:
            image_asset = None
            gaps.append({"kind": "job", "name": name, "source_job_id": source_job_id,
                         "reason": match_status})
        connection.execute("INSERT INTO support_jobs VALUES(?,?,?,?,?,?,?,?,?)", (
            source_job_id, job_entry_id, name, display_name, type_id, type_label,
            json.dumps(tiers, ensure_ascii=False, separators=(",", ":")), image_asset, match_status,
        ))

    connection.execute("INSERT INTO support_metadata VALUES(?,?)", ("source_uri", source["uri"]))
    connection.execute("INSERT INTO support_metadata VALUES(?,?)", ("bundle_uri", source["bundle_uri"]))
    connection.execute("INSERT INTO support_metadata VALUES(?,?)", ("page_sha256", source["page_sha256"]))
    connection.execute("INSERT INTO support_metadata VALUES(?,?)", ("bundle_sha256", source["bundle_sha256"]))
    connection.execute("INSERT INTO support_metadata VALUES(?,?)", ("source_revision", source["revision"]))
    connection.execute("INSERT INTO support_metadata VALUES(?,?)", (
        "thresholds", json.dumps(thresholds, separators=(",", ":")),
    ))
    connection.execute("INSERT INTO support_metadata VALUES(?,?)", (
        "stat_names", json.dumps(stat_names, ensure_ascii=False, separators=(",", ":")),
    ))
    for type_id, support_type in type_by_id.items():
        for level_number, values in enumerate(support_type["levels"], start=1):
            connection.execute("INSERT INTO support_type_bonuses VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", (
                type_id, support_type["label"], level_number, *values,
            ))

    seen_pairs: set[tuple[int, int]] = set()
    pair_ids: set[str] = set()
    pairs_with_bonus = 0
    pairs_with_dialogues = 0
    dialogue_conditions = 0
    for pair in pairs:
        if not isinstance(pair, dict):
            raise ContentDatabaseError("支援关系格式无效")
        first_index = pair.get("source_first_index")
        second_index = pair.get("source_second_index")
        if (not _support_integer(first_index) or not _support_integer(second_index)
                or first_index == second_index or first_index not in range(len(people_by_index))
                or second_index not in range(len(people_by_index))):
            raise ContentDatabaseError("支援关系角色索引无效")
        first_name = pair.get("first_name")
        second_name = pair.get("second_name")
        if first_name != people_by_index[first_index] or second_name != people_by_index[second_index]:
            raise ContentDatabaseError("支援关系名称与来源角色索引不一致")
        unordered_indexes = tuple(sorted((first_index, second_index)))
        if unordered_indexes in seen_pairs:
            raise ContentDatabaseError(f"支援关系重复：{first_name}/{second_name}")
        seen_pairs.add(unordered_indexes)
        affinity = pair.get("affinity")
        initial_level = pair.get("initial_level")
        bonuses = pair.get("combination_bonus")
        talks = pair.get("talks")
        if (not _support_integer(affinity) or affinity not in range(1, 5)
                or not _support_integer(initial_level) or initial_level not in range(0, 6)
                or not isinstance(bonuses, list) or len(bonuses) != len(SUPPORT_STAT_NAMES)
                or any(not _support_integer(value) or not -100 <= value <= 100 for value in bonuses)
                or not isinstance(talks, list)):
            raise ContentDatabaseError("支援关系等级、加成或对话列表无效")
        canonical_names = sorted((_normalize_content_title(first_name), _normalize_content_title(second_name)))
        pair_id = "support-pair:" + str(uuid.uuid5(
            uuid.NAMESPACE_URL, json.dumps(["support-pair", *canonical_names], ensure_ascii=False),
        ))
        if pair_id in pair_ids:
            raise ContentDatabaseError("支援关系规范化身份重复")
        pair_ids.add(pair_id)
        search_text = f"{first_name} {second_name} {pair.get('display_name', '')}"
        connection.execute("INSERT INTO support_pairs VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", (
            pair_id, first_name, second_name, first_index, second_index, affinity, initial_level,
            *bonuses, search_text,
        ))
        pairs_with_bonus += int(any(bonuses))
        if talks:
            pairs_with_dialogues += 1
        for talk in talks:
            if not isinstance(talk, dict):
                raise ContentDatabaseError("支援对话条件格式无效")
            rank = talk.get("rank")
            start = talk.get("start")
            end = talk.get("end")
            route = talk.get("route")
            any_joined = talk.get("any_joined")
            conditions = talk.get("conditions")
            pending = talk.get("pending")
            date_pending = talk.get("date_pending")
            if (not _support_integer(rank) or rank not in range(1, 5)
                    or not _support_chapter(start, allow_open_end=False)
                    or not _support_chapter(end, allow_open_end=True)
                    or not _support_integer(route) or route < 0
                    or not isinstance(any_joined, list) or any(not isinstance(name, str) for name in any_joined)
                    or not isinstance(conditions, list) or any(not isinstance(text, str) for text in conditions)
                    or not _support_integer(pending) or pending < 0 or not isinstance(date_pending, bool)):
                raise ContentDatabaseError("支援对话条件字段无效")
            dialogue_identity = json.dumps(
                [pair_id, rank, start, end, route, any_joined, conditions, pending, date_pending],
                ensure_ascii=False, sort_keys=True, separators=(",", ":"),
            )
            dialogue_id = "support-dialogue:" + str(uuid.uuid5(uuid.NAMESPACE_URL, dialogue_identity))
            connection.execute("INSERT INTO support_dialogues VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)", (
                dialogue_id, pair_id, rank, start[0], start[1], end[0], end[1], route,
                json.dumps(any_joined, ensure_ascii=False, separators=(",", ":")),
                json.dumps(conditions, ensure_ascii=False, separators=(",", ":")),
                pending, int(date_pending), source["revision"],
            ))
            dialogue_conditions += 1

    expected_pair_count = len(people_by_index) * (len(people_by_index) - 1) // 2
    if len(seen_pairs) != expected_pair_count:
        raise ContentDatabaseError(
            f"支援关系不完整：应有 {expected_pair_count} 组，实际有 {len(seen_pairs)} 组",
        )
    expected_pairs = {(first, second) for first in range(len(people_by_index))
                      for second in range(first + 1, len(people_by_index))}
    if seen_pairs != expected_pairs:
        raise ContentDatabaseError("支援关系角色组合存在缺失")

    tencent_crosscheck = _cross_check_support_table(
        package, sheets, source_revision, type_by_id, jobs,
    )
    return {
        "available": True,
        "source_uri": source["uri"],
        "source_revision": source["revision"],
        "people": len(people),
        "linked_people": linked_people,
        "jobs": len(jobs),
        "linked_jobs": linked_jobs,
        "pairs": len(pairs),
        "pairs_with_bonus": pairs_with_bonus,
        "pairs_with_dialogues": pairs_with_dialogues,
        "dialogue_conditions": dialogue_conditions,
        "support_types": len(type_by_id),
        "gaps": gaps,
        "tencent_crosscheck": tencent_crosscheck,
    }


def _cross_check_support_table(package: Path, sheets: dict[str, Any], source_revision: int,
                               support_types: dict[str, dict[str, Any]], jobs: list[dict[str, Any]]) -> dict[str, Any]:
    sheet_id = "tvt8ng"
    sheet = sheets.get(sheet_id)
    if sheet is None or sheet.get("status") != "available":
        return {"available": False, "reason": "sheet_unavailable", "sheet_id": sheet_id}
    if sheet.get("output_file") != f"tables/{sheet_id}.json":
        raise ContentDatabaseError("支援效果交叉核验表路径无效")
    table_path = package / sheet["output_file"]
    table_bytes = table_path.read_bytes()
    if _sha256(table_bytes) != sheet.get("output_sha256"):
        raise ContentDatabaseError("支援效果交叉核验表哈希不匹配")
    table = json.loads(table_bytes)
    metadata = table.get("metadata", {})
    if (table.get("schema_version") != 1 or metadata.get("sheet_id") != sheet_id
            or metadata.get("revision") != source_revision or metadata.get("name") != sheet.get("name")
            or len(table.get("cells", [])) != sheet.get("stored_cell_count")):
        raise ContentDatabaseError("支援效果交叉核验表身份或计数不匹配")
    rows = _project_cells(table)
    differences = []
    compared = 0
    unmatched_jobs = []
    source_jobs: dict[str, list[str]] = {}
    for job in jobs:
        source_jobs.setdefault(_normalize_content_title(job["name"]), []).append(job["name"])
    for group_name, type_id, first_row in SUPPORT_TENCENT_GROUPS:
        actual_group = rows.get(first_row, {}).get(14, {}).get("text", "").strip()
        if actual_group != group_name:
            raise ContentDatabaseError(f"支援效果交叉核验分组不匹配：{group_name} ({actual_group})")
        job_text = rows.get(first_row, {}).get(15, {}).get("text", "").strip()
        for job_name in (name.strip() for name in re.split(r"[、,，]", job_text) if name.strip()):
            candidates = source_jobs.get(_normalize_content_title(job_name), [])
            if len(candidates) != 1:
                unmatched_jobs.append({
                    "group": group_name,
                    "name": job_name,
                    "reason": "ambiguous" if candidates else "not_found",
                })
        for row_offset, expected_level in enumerate(range(2, 6)):
            row_number = first_row + row_offset
            level_text = rows.get(row_number, {}).get(16, {}).get("text", "").strip()
            if level_text != str(expected_level):
                raise ContentDatabaseError(f"支援效果交叉核验等级顺序无效：{group_name}/{row_number}")
            expected_values = support_types[type_id]["levels"][expected_level - 1]
            for stat_index, column in enumerate(range(17, 25)):
                raw_value = rows.get(row_number, {}).get(column, {}).get("text", "").strip()
                if not raw_value:
                    actual_value = 0
                elif re.fullmatch(r"[-+]?[0-9]+(?:\.0+)?", raw_value):
                    actual_value = int(float(raw_value))
                else:
                    raise ContentDatabaseError(
                        f"支援效果交叉核验数值无效：{group_name}/Lv{expected_level}/{column}",
                    )
                compared += 1
                if actual_value != expected_values[stat_index]:
                    differences.append({
                        "group": group_name,
                        "support_type": type_id,
                        "level": expected_level,
                        "stat": SUPPORT_STAT_NAMES[stat_index],
                        "support_page_value": expected_values[stat_index],
                        "tencent_sheet_value": actual_value,
                    })
    return {
        "available": True,
        "sheet_id": sheet_id,
        "source_revision": metadata["revision"],
        "groups_compared": len(SUPPORT_TENCENT_GROUPS),
        "values_compared": compared,
        "differences": differences,
        "unmatched_jobs": unmatched_jobs,
    }


def _support_wiki_title_index(connection: sqlite3.Connection, category_id: str) -> dict[str, list[str]]:
    result: dict[str, list[str]] = {}
    for entry_id, title in connection.execute(
            "SELECT id,title FROM wiki_entries WHERE category_id=?", (category_id,)):
        result.setdefault(_normalize_content_title(title), []).append(entry_id)
    return result


def _required_support_text(record: dict[str, Any], key: str, label: str) -> str:
    value = record.get(key)
    if not isinstance(value, str) or not value.strip():
        raise ContentDatabaseError(f"{label}缺少{key}")
    return value.strip()


def _support_integer(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def _support_chapter(value: Any, allow_open_end: bool) -> bool:
    if (not isinstance(value, list) or len(value) != 2
            or any(not _support_integer(part) or part < 0 for part in value)):
        return False
    if allow_open_end and value == [0, 0]:
        return True
    return value[0] > 0


def _normalize_content_title(value: str) -> str:
    return unicodedata.normalize("NFC", " ".join(value.split()))


def _insert_planting_markers(connection: sqlite3.Connection, markers: Any, location_id: str | None,
                             fertilizer_id: str | None, route_ids: dict[str, str],
                             used_map_ids: set[str]) -> int:
    if not isinstance(markers, list) or not markers:
        raise ContentDatabaseError("種植资料记录缺少地图来源链接")
    inserted = 0
    for marker in markers:
        if not isinstance(marker, dict):
            raise ContentDatabaseError("种植地图标记格式无效")
        source_map_id = _required_planting_text(marker, "source_map_id", "地图标记")
        source_token, separator, pin_id = source_map_id.partition("|")
        chapter_id = route_ids.get(source_token)
        if (not separator or not pin_id or chapter_id is None or marker.get("chapter_source_token") != source_token
                or marker.get("chapter_id") != chapter_id):
            raise ContentDatabaseError(f"种植地图标记篇章与完整 ID 不匹配：{source_map_id}")
        if source_map_id in used_map_ids:
            raise ContentDatabaseError(f"种植地图标记重复：{source_map_id}")
        map_uri = _required_planting_text(marker, "source_uri", "地图标记")
        map_parts = urlsplit(map_uri)
        if (map_parts.scheme != "https" or map_parts.netloc != "fireemblem.top" or not map_parts.path.endswith("/maps")
                or parse_qs(map_parts.query).get("pin") != [source_map_id]):
            raise ContentDatabaseError(f"种植地图来源地址无效：{source_map_id}")
        marker_row = _required_planting_row(marker)
        location_name = _required_planting_text(marker, "location_name", "地图标记")
        connection.execute(
            "INSERT INTO planting_map_markers VALUES(?,?,?,?,?,?,?,?,?)",
            (source_map_id, chapter_id, source_token, location_name, location_id, fertilizer_id, map_uri, marker_row,
             pin_id),
        )
        used_map_ids.add(source_map_id)
        inserted += 1
    return inserted


def _required_planting_text(record: Any, key: str, label: str) -> str:
    value = record.get(key) if isinstance(record, dict) else None
    if not isinstance(value, str) or not value.strip():
        raise ContentDatabaseError(f"{label}缺少{key}")
    return value.strip()


def _required_planting_row(record: Any) -> int:
    value = record.get("source_row") if isinstance(record, dict) else None
    if not isinstance(value, int) or isinstance(value, bool) or value < 1:
        raise ContentDatabaseError("种植来源记录序号无效")
    return value


def _planting_percent(value: Any, label: str) -> float:
    if not isinstance(value, (int, float)) or isinstance(value, bool) or not 0 <= value <= 100:
        raise ContentDatabaseError(f"{label}百分比无效")
    return float(value)


def _normalize_planting_name(value: str) -> str:
    return unicodedata.normalize("NFC", " ".join(value.split()))


def _planting_id(kind: str, title: str) -> str:
    identity = [f"planting-{kind}", _normalize_planting_name(title)]
    return f"planting-{kind}:" + str(uuid.uuid5(uuid.NAMESPACE_URL, json.dumps(identity, ensure_ascii=False)))


def _parse_chapter_num(chapter_text: str) -> int:
    text = (chapter_text or "").strip()
    if not text or "不可挖角" in text or "无缘加入" in text:
        return 999
    if "序幕" in text:
        m = re.search(r"第\s*(\d+)\s*章", text)
        return int(m.group(1)) if m else 1
    if "第2部" in text:
        m = re.search(r"第\s*(\d+)\s*章", text)
        return 100 + (int(m.group(1)) if m else 1)
    if "第3部" in text:
        m = re.search(r"第\s*(\d+)\s*区分", text)
        return 200 + (int(m.group(1)) if m else 1)
    m = re.search(r"第\s*(\d+)\s*章", text)
    return int(m.group(1)) if m else 999


def _parse_fw_poach_by_route(raw_poach: str) -> dict[str, str]:
    routes = [("kai", "凯伊"), ("dit", "迪托利希"), ("seo", "赛奥朵拉"), ("leda", "蕾达")]
    out: dict[str, str] = {}
    if not raw_poach or raw_poach.strip() in {"--", "×"}:
        return out
    current_route: str | None = None
    for line in (ln.strip() for ln in raw_poach.splitlines() if ln.strip()):
        matched = False
        for route_key, route_name in routes:
            if line.startswith(route_name + "：") or line.startswith(route_name + ":"):
                current_route = route_key
                val = line.split("：", 1)[1] if "：" in line else line.split(":", 1)[1]
                out[route_key] = val.strip()
                matched = True
                break
        if not matched:
            if line in {"--", "×"}:
                current_route = None
            elif current_route is not None:
                out[current_route] = (out[current_route] + " " + line).strip()
    return out


def _parse_fw_contact_by_route(raw_ch: str, raw_cond: str) -> tuple[dict[str, str], dict[str, str], set[str]]:
    routes = [("kai", "凯伊"), ("dit", "迪托利希"), ("seo", "赛奥朵拉"), ("leda", "蕾达")]
    ch_lines = [ln.strip() for ln in (raw_ch or "").splitlines() if ln.strip()]
    cd_lines = [ln.strip() for ln in (raw_cond or "").splitlines() if ln.strip()]
    route_pos: dict[str, tuple[int, str]] = {}
    rec_routes: set[str] = set()
    for idx, line in enumerate(ch_lines):
        for route_key, route_name in routes:
            if route_name in line and route_key not in route_pos:
                if "✅" in line:
                    rec_routes.add(route_key)
                route_pos[route_key] = (idx, line.replace("✅", "").strip())
    out_ch: dict[str, str] = {}
    out_cd: dict[str, str] = {}
    if len(route_pos) == 4 and len(cd_lines) == 4:
        for route_key, (idx, line) in route_pos.items():
            out_ch[route_key] = line
            out_cd[route_key] = "" if cd_lines[idx] in {"--", "×"} else cd_lines[idx]
    elif len(route_pos) == 1:
        route_key, (_, line) = next(iter(route_pos.items()))
        out_ch[route_key] = line
        joined = " / ".join(ln for ln in cd_lines if ln not in {"--", "×"})
        out_cd[route_key] = joined
    else:
        for route_key, (idx, line) in route_pos.items():
            out_ch[route_key] = line
            if idx < len(cd_lines):
                out_cd[route_key] = "" if cd_lines[idx] in {"--", "×"} else cd_lines[idx]
    return out_ch, out_cd, rec_routes


def _build_character_route_order(
    connection: sqlite3.Connection,
    package: Path,
    sheets: dict[str, Any],
    source_revision: int,
) -> dict[str, Any]:
    required_ids = ("sd3o0k", "c12ffz", "4bwh7j")
    if not all(sheets.get(sid, {}).get("status") == "available" for sid in required_ids):
        return {"available": False, "rows": 0}

    def load_verified_rows(sid: str) -> dict[int, dict[int, dict[str, Any]]]:
        sheet = sheets[sid]
        if sheet.get("output_file") != f"tables/{sid}.json":
            raise ContentDatabaseError(f"角色路线来源路径不合法：{sid}")
        payload = (package / sheet["output_file"]).read_bytes()
        if _sha256(payload) != sheet["output_sha256"]:
            raise ContentDatabaseError(f"角色路线来源表校验失败：{sid}")
        table = json.loads(payload)
        meta = table["metadata"]
        if (table.get("schema_version") != 1 or meta["sheet_id"] != sid
                or meta["name"] != sheet["name"] or meta["revision"] != source_revision
                or len(table["cells"]) != sheet["stored_cell_count"]):
            raise ContentDatabaseError(f"角色路线来源表身份或计数不匹配：{sid}")
        return _project_cells(table)

    char_ids = _support_wiki_title_index(connection, "characters")
    if not char_ids:
        return {"available": False, "rows": 0}

    sd_rows = load_verified_rows("sd3o0k")
    c12_rows = load_verified_rows("c12ffz")
    b4_rows = load_verified_rows("4bwh7j")

    def resolve_entry(name: str) -> str:
        matches = char_ids.get(_normalize_content_title(name), [])
        if len(matches) != 1:
            raise ContentDatabaseError(f"角色路线来源角色无法唯一匹配：{name}")
        return matches[0]

    route_defs = [
        ("kai", "凯伊篇", (3, 12), 3, 4),
        ("dit", "迪托利希篇", (13, 24), 5, 6),
        ("seo", "赛奥朵拉篇", (25, 34), 7, 8),
        ("leda", "蕾达篇", (35, 45), 9, 10),
    ]
    buckets = [
        (4, 0, "story", None),
        (6, 2, "free", None),
        (8, 3, "fame", 9),
        (11, 4, "fame", 12),
        (14, 5, "fame", 15),
        (17, 6, "fame", 18),
        (20, 7, "fame", 21),
        (23, 8, "fame", 24),
        (26, 9, "fame", 27),
        (29, 10, "fame", 30),
        (32, 999, "unrecruitable", None),
    ]

    earliest_map: dict[str, dict[str, tuple[int | None, str]]] = {}
    for r_num, row in sorted(b4_rows.items()):
        if r_num < 3:
            continue
        name = row.get(2, {}).get("text", "").strip()
        if not name:
            continue
        entry_id = resolve_entry(name)
        per_route: dict[str, tuple[int | None, str]] = {}
        for rk, _, _, lvl_col, ch_col in route_defs:
            lvl_raw = row.get(lvl_col, {}).get("text", "").strip()
            ch_raw = row.get(ch_col, {}).get("text", "").strip()
            lvl = int(lvl_raw) if lvl_raw.isdigit() else None
            per_route[rk] = (lvl, ch_raw)
        earliest_map[entry_id] = per_route

    sd_info_by_entry: dict[str, dict[str, Any]] = {}
    sd_order: list[str] = []
    for r_num, row in sorted(sd_rows.items()):
        if r_num < 4:
            continue
        name = row.get(4, {}).get("text", "").strip()
        if not name or name in {"--", "×"}:
            continue
        entry_id = resolve_entry(name)
        raw_ch = row.get(17, {}).get("text", "").strip()
        raw_cond = row.get(18, {}).get("text", "").strip()
        raw_poach = row.get(19, {}).get("text", "").strip()
        raw_lvl = row.get(30, {}).get("text", "").strip()
        ch_by_route, cond_by_route, rec_routes = _parse_fw_contact_by_route(raw_ch, raw_cond)
        poach_by_route = _parse_fw_poach_by_route(raw_poach)
        sd_info_by_entry[entry_id] = {
            "name": name,
            "row_number": r_num,
            "marker": row.get(1, {}).get("text", "").strip(),
            "raw_ch": raw_ch,
            "raw_cond": raw_cond,
            "raw_poach": raw_poach,
            "init_lvl": int(raw_lvl) if raw_lvl.isdigit() else None,
            "ch_by_route": ch_by_route,
            "cond_by_route": cond_by_route,
            "poach_by_route": poach_by_route,
            "rec_routes": rec_routes,
        }
        sd_order.append(entry_id)

    seen_route_entries: set[str] = set()
    rows_inserted = 0
    for rk, rlabel, (col_min, col_max), _, _ in route_defs:
        seq = 0
        for row_idx, fame, join_kind, sup_row in buckets:
            bucket_row = c12_rows.get(row_idx, {})
            for col_idx in sorted(bucket_row):
                if not (col_min <= col_idx <= col_max):
                    continue
                raw_val = bucket_row[col_idx].get("text", "").strip()
                if not raw_val:
                    continue
                rec = "✅" in raw_val
                name = raw_val.replace("✅", "").strip()
                entry_id = resolve_entry(name)
                seen_route_entries.add(entry_id)
                sup_req = c12_rows.get(sup_row, {}).get(col_idx, {}).get("text", "").strip() if sup_row else ""
                init_lvl, join_ch = earliest_map.get(entry_id, {}).get(rk, (None, ""))
                sd_meta = sd_info_by_entry.get(entry_id, {})
                if not join_ch:
                    join_ch = sd_meta.get("ch_by_route", {}).get(rk, "")
                rec = rec or (rk in sd_meta.get("rec_routes", ()))
                poach_cond = sd_meta.get("poach_by_route", {}).get(rk, "")
                contact_loc = sd_meta.get("cond_by_route", {}).get(rk, "")
                if join_kind == "unrecruitable":
                    join_ch = "不可挖角"
                    poach_cond = ""
                    contact_loc = contact_loc or "本路线不可挖角"
                ch_num = _parse_chapter_num(join_ch)
                connection.execute(
                    "INSERT INTO character_route_order VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    (
                        entry_id, name, rk, rlabel, fame, join_kind, join_ch, ch_num,
                        init_lvl, sup_req, poach_cond, contact_loc, 1 if rec else 0, seq,
                    ),
                )
                seq += 1
                rows_inserted += 1

    # Add non-Part-1 characters (Prologue, Part 2, Part 3, Temp/NPC) under 'other'
    for entry_id in sd_order:
        if entry_id in seen_route_entries:
            continue
        meta = sd_info_by_entry[entry_id]
        raw_ch = meta["raw_ch"]
        raw_cond = meta["raw_cond"]
        raw_poach = meta["raw_poach"]
        if "序幕" in raw_ch:
            fame, join_kind = 0, "prologue"
        elif "第2部" in raw_ch:
            fame, join_kind = 901, "part2"
        elif "第3部" in raw_ch:
            fame, join_kind = 902, "part3"
        else:
            fame, join_kind = 950, "temp"
        join_ch = " / ".join(ln.strip() for ln in raw_ch.splitlines() if ln.strip() and ln.strip() not in {"--", "×"})
        if not join_ch:
            join_ch = "临时加入" if "临时加入" in raw_cond else "剧情角色 / NPC"
        contact_loc = " / ".join(ln.strip() for ln in raw_cond.splitlines() if ln.strip() and ln.strip() not in {"--", "×"})
        poach_cond = "" if raw_poach in {"", "--", "×"} else " / ".join(ln.strip() for ln in raw_poach.splitlines() if ln.strip())
        ch_num = _parse_chapter_num(join_ch)
        connection.execute(
            "INSERT INTO character_route_order VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (
                entry_id, meta["name"], "other", "序幕 / 二·三部 / NPC", fame, join_kind,
                join_ch, ch_num, meta["init_lvl"], "", poach_cond, contact_loc, 0, meta["row_number"],
            ),
        )
        rows_inserted += 1

    return {"available": True, "rows": rows_inserted}


def _validate_bird_dialogues_adapter(adapter: dict[str, Any]) -> None:
    required = ("sheet", "category_id", "header_row", "first_row", "headers", "columns", "character_groups",
                "corrected_marker", "source_note_row", "source_note_column")
    if (any(key not in adapter for key in required)
            or not re.fullmatch(r"[a-zA-Z0-9]+", adapter["sheet"])
            or not re.fullmatch(r"[a-z][a-z0-9-]*", adapter["category_id"])):
        raise ContentDatabaseError("逗鸟对话适配器缺少必要字段")
    row_numbers = (adapter["header_row"], adapter["first_row"], adapter["source_note_row"])
    if any(not isinstance(value, int) or isinstance(value, bool) or value < 1 for value in row_numbers):
        raise ContentDatabaseError("逗鸟对话来源行配置不合法")
    columns = adapter["columns"]
    required_columns = ("character_group", "character_name", "character_name_english", "original_prompt",
                        "translated_prompt", "original_reaction", "translated_reaction", "correction_marker")
    if (not isinstance(columns, dict) or any(not isinstance(columns.get(key), int) or isinstance(columns.get(key), bool)
                                             or columns[key] < 1 for key in required_columns)
            or len({columns[key] for key in required_columns}) != len(required_columns)):
        raise ContentDatabaseError("逗鸟对话来源列配置不合法")
    headers = adapter["headers"]
    if (not isinstance(headers, list) or not headers
            or any(not isinstance(item, dict) or not isinstance(item.get("column"), int)
                   or isinstance(item.get("column"), bool) or item["column"] < 1
                   or not isinstance(item.get("value"), str) or not item["value"].strip() for item in headers)
            or len({item["column"] for item in headers}) != len(headers)):
        raise ContentDatabaseError("逗鸟对话表头配置不合法")
    if (not isinstance(adapter["corrected_marker"], str) or not adapter["corrected_marker"].strip()
            or not isinstance(adapter["source_note_column"], int) or isinstance(adapter["source_note_column"], bool)
            or adapter["source_note_column"] < 1):
        raise ContentDatabaseError("逗鸟对话校订或来源说明配置不合法")
    groups = adapter["character_groups"]
    if (not isinstance(groups, list) or not groups
            or any(not isinstance(group, str) or not group.strip() for group in groups)
            or len(set(groups)) != len(groups)):
        raise ContentDatabaseError("逗鸟对话角色范围配置不合法")


def _header_line(rows: dict[int, dict[int, dict[str, Any]]], row_number: int, column: int) -> str:
    value = rows.get(row_number, {}).get(column, {}).get("text", "")
    lines = value.splitlines()
    return lines[0].strip() if lines else ""


def _reference_tokens(value: str, syntax: str) -> list[str]:
    if syntax == "skills":
        return re.findall(r"【([^【】]+)】", value)
    if syntax == "materials":
        return re.findall(r"\[([^\[\]]+)\]\s*[×*]\s*\d+", value)
    if syntax == "forge":
        return re.findall(r"\[([^\[\]]+)\]\s*[×*]\s*\d+", value) + re.findall(r"由\s+(.+?)\s+锻造而成", value)
    if syntax != "list":
        raise ContentDatabaseError(f"不支持的关联格式：{syntax}")
    return re.split(r"[、，,\n]", value)


def _validate_adapter(adapter: dict[str, Any]) -> None:
    if not re.fullmatch(r"[a-z][a-z0-9-]*", adapter["id"]):
        raise ContentDatabaseError("分类ID不合法")
    record_source = adapter.get("record_source")
    if record_source:
        if (not re.fullmatch(r"[a-z][a-z0-9-]*", record_source)
                or not re.fullmatch(r"[a-z][a-z0-9-]*", adapter.get("source_id", ""))
                or not isinstance(adapter.get("source_name"), str) or not adapter["source_name"].strip()
                or not isinstance(adapter.get("source_uri"), str) or not adapter["source_uri"].startswith("https://")):
            raise ContentDatabaseError("外部记录来源配置不合法")
        if "sheet" in adapter:
            raise ContentDatabaseError("外部记录分类不能同时声明表格来源")
    elif not re.fullmatch(r"[a-zA-Z0-9]+", adapter["sheet"]):
        raise ContentDatabaseError("来源ID不合法")
    keys = [field["key"] for field in adapter["fields"]]
    if len(set(keys)) != len(keys) or any(not IDENTIFIER.fullmatch(key) for key in keys):
        raise ContentDatabaseError("语义字段名称不合法或重复")
    if any(field["column"] < 1 for field in adapter["fields"]):
        raise ContentDatabaseError("来源列必须从1开始")
    row_stride = adapter.get("row_stride", 1)
    if not isinstance(row_stride, int) or isinstance(row_stride, bool) or row_stride < 1:
        raise ContentDatabaseError("来源行步长必须为正整数")
    expected_revision = adapter.get("expected_source_revision")
    if expected_revision is not None and (not isinstance(expected_revision, int) or isinstance(expected_revision, bool)):
        raise ContentDatabaseError("分类来源版本配置不合法")
    row_titles = adapter.get("row_titles", {})
    if (not isinstance(row_titles, dict)
            or any(not isinstance(row, str) or not re.fullmatch(r"[1-9][0-9]*", row)
                   or not isinstance(title, str) or not title.strip() for row, title in row_titles.items())):
        raise ContentDatabaseError("来源行标题映射不合法")
    if any(int(row) < adapter["first_row"] or int(row) > adapter.get("last_row", int(row))
           for row in row_titles):
        raise ContentDatabaseError("来源行标题映射超出条目范围")
    _validate_prefixes(adapter.get("strip_title_prefixes", []), "标题")
    title_fallback_columns = adapter.get("title_fallback_columns", [])
    if (not isinstance(title_fallback_columns, list)
            or any(not isinstance(column, int) or column < 1 for column in title_fallback_columns)):
        raise ContentDatabaseError("标题备用列配置不合法")
    section_config = adapter.get("section_markers")
    if section_config is not None:
        values = section_config.get("values")
        if (not isinstance(section_config.get("column"), int) or section_config["column"] < 1
                or not isinstance(values, dict) or not values
                or any(not isinstance(marker, str) or not marker or not isinstance(section, str) or not section
                       for marker, section in values.items())
                or len(set(values.values())) != len(values)):
            raise ContentDatabaseError("分组标记配置不合法")
    for field in adapter["fields"]:
        row_offset = field.get("row_offset", 0)
        if not isinstance(row_offset, int) or isinstance(row_offset, bool) or row_offset < 0:
            raise ContentDatabaseError("语义字段行偏移必须为非负整数")
        _validate_prefixes(field.get("strip_prefixes", []), "语义字段")
        if field.get("source", "column") not in {"column", "section"}:
            raise ContentDatabaseError("语义字段来源不支持")
        if field.get("source") == "section" and section_config is None:
            raise ContentDatabaseError("分组字段缺少分组标记配置")
        if field.get("source") == "section" and row_offset:
            raise ContentDatabaseError("分组字段不能设置来源行偏移")
        if field.get("format") not in {None, "game-date"}:
            raise ContentDatabaseError("语义字段格式不支持")
        if "blank_if_merged" in field and not isinstance(field["blank_if_merged"], bool):
            raise ContentDatabaseError("合并字段处理配置不合法")
        if field.get("format") == "game-date" and field.get("source", "column") != "column":
            raise ContentDatabaseError("游戏日期格式只能用于来源列")
    generated = ["entry_id"]
    for field in adapter["fields"]:
        generated.extend([field["key"], field["key"] + "_image"])
        if field.get("numeric"):
            generated.append(field["key"] + "_numeric")
    if len(set(generated)) != len(generated):
        raise ContentDatabaseError("业务字段与图片或数值辅助列重名")


def _record_source_rows(data: dict[str, Any], adapter: dict[str, Any]) -> dict[int, dict[int, dict[str, Any]]]:
    if not isinstance(data, dict) or data.get("schema_version") != 1:
        raise ContentDatabaseError("外部记录来源版本不支持")
    source = data.get("source")
    records = data.get("records")
    if not isinstance(source, dict) or source.get("uri") != adapter["source_uri"] or not isinstance(records, list):
        raise ContentDatabaseError(f"外部记录来源身份不匹配：{adapter['id']}")
    page_hash = source.get("page_sha256", "")
    bundle_hash = source.get("bundle_sha256", "")
    revision = source.get("revision", "")
    if (not re.fullmatch(r"[0-9a-f]{64}", page_hash)
            or not re.fullmatch(r"[0-9a-f]{64}", bundle_hash)
            or not re.fullmatch(r"[0-9a-f]{64}", revision)
            or _sha256((page_hash + bundle_hash).encode("ascii")) != revision):
        raise ContentDatabaseError(f"外部记录来源校验信息无效：{adapter['id']}")
    if not records:
        raise ContentDatabaseError(f"外部记录来源没有条目：{adapter['id']}")

    field_keys = {field["key"] for field in adapter["fields"]}
    rows: dict[int, dict[int, dict[str, Any]]] = {}
    record_ids: set[str] = set()
    reserved_columns = {adapter["title_column"]}
    if adapter.get("image_column") is not None:
        reserved_columns.add(adapter["image_column"])
    reserved_columns.update(adapter.get("identity_columns", []))
    field_columns = {field["column"] for field in adapter["fields"]}
    if (len(field_columns) != len(adapter["fields"]) or field_columns & reserved_columns
            or len(reserved_columns) != len(adapter.get("identity_columns", []))
            + 1 + int(adapter.get("image_column") is not None)):
        raise ContentDatabaseError(f"外部记录列映射重复：{adapter['id']}")

    for record in records:
        if not isinstance(record, dict):
            raise ContentDatabaseError(f"外部记录格式无效：{adapter['id']}")
        row_number = record.get("source_row")
        record_id = record.get("source_id")
        title = record.get("title")
        values = record.get("fields")
        identities = record.get("identity_values")
        if (not isinstance(row_number, int) or isinstance(row_number, bool) or row_number < 1
                or row_number in rows or not isinstance(record_id, str) or not record_id.strip()
                or record_id in record_ids or not isinstance(title, str) or not title.strip()
                or not isinstance(values, dict) or set(values) - field_keys
                or not isinstance(identities, list) or len(identities) != len(adapter.get("identity_columns", []))
                or any(not isinstance(value, str) for value in identities)):
            raise ContentDatabaseError(f"外部记录字段缺失、重复或类型错误：{adapter['id']}")
        row: dict[int, dict[str, Any]] = {
            adapter["title_column"]: {"text": title.strip()},
        }
        for field in adapter["fields"]:
            value = values.get(field["key"], "")
            if not isinstance(value, str):
                raise ContentDatabaseError(f"外部语义字段必须为文本：{adapter['id']}/{field['key']}")
            row[field["column"]] = {"text": value.strip()}
        for column, value in zip(adapter.get("identity_columns", []), identities):
            row[column] = {"text": value.strip()}
        rows[row_number] = row
        record_ids.add(record_id)
    return rows


def _validate_prefixes(prefixes: Any, label: str) -> None:
    if (not isinstance(prefixes, list)
            or any(not isinstance(prefix, str) or not prefix for prefix in prefixes)
            or len(set(prefixes)) != len(prefixes)):
        raise ContentDatabaseError(f"{label}前缀清理配置不合法")


def _strip_prefixes(value: str, prefixes: list[str]) -> str:
    for prefix in prefixes:
        if value.startswith(prefix):
            return value[len(prefix):].lstrip()
    return value


def _project_cells(table: dict[str, Any]) -> dict[int, dict[int, dict[str, Any]]]:
    rows: dict[int, dict[int, dict[str, Any]]] = {}
    for cell in table["cells"]:
        value = cell.get("formula", {}).get("cached_value") if cell.get("type") == "formula" else cell.get("value")
        text = _display_text(value)
        if not text:
            runs = cell.get("rich_text_runs", [])
            if isinstance(runs, list):
                text = "".join(run.get("text", "") for run in runs if isinstance(run, dict)).strip()
        row, column = cell["row"], cell["column"]
        if not isinstance(row, int) or not isinstance(column, int) or row < 1 or column < 1:
            raise ContentDatabaseError("来源单元格坐标不合法")
        if column in rows.setdefault(row, {}):
            raise ContentDatabaseError("来源单元格坐标重复")
        rows[row][column] = {"text": text, "image": cell.get("image_reference"), "raw": value}
    for merge in table.get("metadata", {}).get("merges", []):
        rectangle = merge.get("gridRangeData", {})
        first_row, first_column = rectangle.get("startRowIndex", -1) + 1, rectangle.get("startColIndex", -1) + 1
        anchor = rows.get(first_row, {}).get(first_column)
        if anchor is None:
            continue
        for row in range(first_row, rectangle.get("endRowIndex", -1) + 2):
            for column in range(first_column, rectangle.get("endColIndex", -1) + 2):
                existing = rows.setdefault(row, {}).get(column)
                if existing is None or not existing.get("text") and not existing.get("image"):
                    rows[row][column] = {**anchor, "merged_fill": (row != first_row or column != first_column)}
    return rows


def _display_text(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, bool):
        return str(value).lower()
    if isinstance(value, (dict, list)):
        return json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    return str(value).strip()


def _field_text(field: dict[str, Any] | None, cell: dict[str, Any]) -> str:
    if field and field.get("blank_if_merged") and cell.get("merged_fill"):
        return ""
    value = cell.get("text", "")
    raw = cell.get("raw")
    if field and field.get("format") == "game-date" and isinstance(raw, (int, float)) and not isinstance(raw, bool):
        instant = datetime(1899, 12, 30) + timedelta(days=raw)
        value = f"{instant.month}月{instant.day}日"
        if instant.hour or instant.minute:
            value += f" {instant.hour:02d}:{instant.minute:02d}"
    return value


def _register_image(cell: dict[str, Any] | None, known: dict[str, Any], registered: dict[str, Any],
                    package: Path, connection: sqlite3.Connection) -> str | None:
    reference = cell.get("image") if cell else None
    if not isinstance(reference, dict):
        return None
    source_file = reference.get("local_file", "")
    if not re.fullmatch(r"assets/[a-zA-Z0-9_-]+\.(png|jpeg|jpg)", source_file):
        raise ContentDatabaseError(f"图片路径不合法：{source_file}")
    path = "assets/" + Path(source_file).stem + ".webp"
    if path in registered:
        if registered[path]["source_sha256"] != reference.get("source_asset_sha256"):
            raise ContentDatabaseError(f"图片来源哈希冲突：{path}")
        return path
    record = known.get(path)
    if record is None or record.get("source_sha256") != reference.get("source_asset_sha256"):
        raise ContentDatabaseError(f"图片清单缺失或来源哈希不符：{path}")
    file = package / path
    if not IMAGE_PATH.fullmatch(path) or not file.is_file() or _sha256(file.read_bytes()) != record["sha256"]:
        raise ContentDatabaseError(f"图片不存在或校验失败：{path}")
    width, height = record["dimensions"]
    connection.execute("INSERT INTO content_assets VALUES(?,?,?,?,?)", (path, record["sha256"], width, height, record["bytes"]))
    registered[path] = record
    return path


def _read_json(path: Path) -> dict[str, Any]:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise ContentDatabaseError(f"无法读取数据包文件：{path}") from error


def _sha256(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def _atomic_write(path: Path, payload: bytes) -> None:
    descriptor, temporary = tempfile.mkstemp(prefix=".publish-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        Path(temporary).unlink(missing_ok=True)


_SCHEMA = """
CREATE TABLE content_metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE content_assets(path TEXT PRIMARY KEY, sha256 TEXT NOT NULL,
 width INTEGER NOT NULL CHECK(width>0), height INTEGER NOT NULL CHECK(height>0), bytes INTEGER NOT NULL CHECK(bytes>0));
CREATE TABLE wiki_categories(id TEXT PRIMARY KEY, title TEXT NOT NULL, description TEXT NOT NULL,
 position INTEGER NOT NULL UNIQUE, source_sheet_id TEXT NOT NULL, source_sheet_name TEXT NOT NULL);
CREATE TABLE wiki_entries(id TEXT PRIMARY KEY, category_id TEXT NOT NULL REFERENCES wiki_categories(id),
 title TEXT NOT NULL, subtitle TEXT NOT NULL, image_asset TEXT REFERENCES content_assets(path),
 source_row INTEGER NOT NULL CHECK(source_row>0), search_text TEXT NOT NULL);
CREATE INDEX wiki_entries_category_order ON wiki_entries(category_id,title COLLATE NOCASE,id);
CREATE TABLE wiki_field_definitions(category_id TEXT NOT NULL REFERENCES wiki_categories(id), field_key TEXT NOT NULL,
 position INTEGER NOT NULL, label TEXT NOT NULL, is_numeric INTEGER NOT NULL CHECK(is_numeric IN (0,1)),
 PRIMARY KEY(category_id,field_key), UNIQUE(category_id,position));
CREATE TABLE wiki_relations(source_id TEXT NOT NULL REFERENCES wiki_entries(id),
 target_id TEXT NOT NULL REFERENCES wiki_entries(id),label TEXT NOT NULL,evidence TEXT NOT NULL,
 PRIMARY KEY(source_id,target_id,label,evidence));
CREATE INDEX wiki_relations_target ON wiki_relations(target_id);
CREATE TABLE character_learning_sources(character_id TEXT PRIMARY KEY REFERENCES wiki_entries(id),
 source_sheet_id TEXT NOT NULL,source_sheet_name TEXT NOT NULL,source_row INTEGER NOT NULL CHECK(source_row>0));
CREATE TABLE character_skill_learnings(character_id TEXT NOT NULL REFERENCES character_learning_sources(character_id),
 skill_entry_id TEXT REFERENCES wiki_entries(id),skill_name TEXT NOT NULL,group_label TEXT NOT NULL,
 group_position INTEGER NOT NULL CHECK(group_position>=0),requirement TEXT NOT NULL,evidence TEXT NOT NULL,
 source_sheet_id TEXT NOT NULL,source_sheet_name TEXT NOT NULL,source_row INTEGER NOT NULL CHECK(source_row>0),
 position INTEGER NOT NULL CHECK(position>=0),PRIMARY KEY(character_id,group_position,position));
CREATE INDEX character_skill_learnings_target ON character_skill_learnings(skill_entry_id);
CREATE TABLE character_gift_sources(character_id TEXT PRIMARY KEY REFERENCES wiki_entries(id),
 source_sheet_id TEXT NOT NULL,source_sheet_name TEXT NOT NULL,source_row INTEGER NOT NULL CHECK(source_row>0),
 category_preferences TEXT);
CREATE TABLE character_gift_preferences(character_id TEXT NOT NULL REFERENCES character_gift_sources(character_id),
 gift_entry_id TEXT NOT NULL REFERENCES wiki_entries(id),preference INTEGER NOT NULL CHECK(preference BETWEEN 1 AND 4),
 evidence TEXT NOT NULL,source_sheet_id TEXT NOT NULL,source_sheet_name TEXT NOT NULL,
 source_row INTEGER NOT NULL CHECK(source_row>0),source_column INTEGER NOT NULL CHECK(source_column BETWEEN 5 AND 8),
 PRIMARY KEY(character_id,gift_entry_id));
CREATE INDEX character_gift_preferences_gift ON character_gift_preferences(gift_entry_id,preference,character_id);
CREATE TABLE character_meal_sources(character_id TEXT PRIMARY KEY REFERENCES wiki_entries(id),
 source_sheet_id TEXT NOT NULL,source_sheet_name TEXT NOT NULL,source_row INTEGER NOT NULL CHECK(source_row>0));
CREATE TABLE character_meal_preferences(character_id TEXT NOT NULL REFERENCES character_meal_sources(character_id),
 meal_entry_id TEXT NOT NULL REFERENCES wiki_entries(id),preference INTEGER NOT NULL CHECK(preference BETWEEN 1 AND 4),
 evidence TEXT NOT NULL,source_sheet_id TEXT NOT NULL,source_sheet_name TEXT NOT NULL,
 source_row INTEGER NOT NULL CHECK(source_row>0),source_column INTEGER NOT NULL CHECK(source_column BETWEEN 3 AND 6),
 PRIMARY KEY(character_id,meal_entry_id));
CREATE INDEX character_meal_preferences_meal ON character_meal_preferences(meal_entry_id,preference,character_id);
CREATE TABLE dining_trios(trio_id TEXT PRIMARY KEY,
 character_1_id TEXT NOT NULL REFERENCES wiki_entries(id),character_2_id TEXT NOT NULL REFERENCES wiki_entries(id),
 character_3_id TEXT NOT NULL REFERENCES wiki_entries(id),relationship_12 TEXT,relationship_13 TEXT,
 relationship_23 TEXT,minimum_level TEXT,evidence TEXT NOT NULL,source_sheet_id TEXT NOT NULL,
 source_sheet_name TEXT NOT NULL,source_row INTEGER NOT NULL CHECK(source_row>0),
 CHECK(character_1_id<>character_2_id AND character_1_id<>character_3_id AND character_2_id<>character_3_id));
CREATE INDEX dining_trios_member_1 ON dining_trios(character_1_id);
CREATE INDEX dining_trios_member_2 ON dining_trios(character_2_id);
CREATE INDEX dining_trios_member_3 ON dining_trios(character_3_id);
CREATE TABLE bird_dialogues(entry_id TEXT PRIMARY KEY REFERENCES wiki_entries(id),
 character_entry_id TEXT REFERENCES wiki_entries(id),character_group TEXT,
 character_name TEXT NOT NULL,character_name_english TEXT,original_prompt TEXT,
 translated_prompt TEXT NOT NULL,original_reaction TEXT,translated_reaction TEXT NOT NULL,
 marked_corrected INTEGER NOT NULL CHECK(marked_corrected IN (0,1)));
CREATE INDEX bird_dialogues_character ON bird_dialogues(character_entry_id,marked_corrected,entry_id);
CREATE INDEX bird_dialogues_filter ON bird_dialogues(character_group,marked_corrected,entry_id);
CREATE TABLE planting_metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL);
CREATE TABLE planting_harvest_parameters(parameter_key TEXT PRIMARY KEY,value_text TEXT NOT NULL,
 value_numeric REAL NOT NULL,verification_status TEXT NOT NULL CHECK(verification_status IN ('source_recorded','unverified')),
 source_evidence TEXT NOT NULL);
CREATE TABLE planting_harvest_probabilities(harvest_number INTEGER PRIMARY KEY CHECK(harvest_number>0),
 probability_text TEXT NOT NULL,probability_percent REAL NOT NULL CHECK(probability_percent BETWEEN 0 AND 100),
 verification_status TEXT NOT NULL CHECK(verification_status='unverified'));
CREATE TABLE planting_plants(plant_id TEXT PRIMARY KEY,title TEXT NOT NULL UNIQUE,
 item_entry_id TEXT REFERENCES wiki_entries(id),image_asset TEXT REFERENCES content_assets(path),
 match_status TEXT NOT NULL CHECK(match_status IN ('matched','missing','ambiguous')),
 source_row INTEGER NOT NULL CHECK(source_row>0));
CREATE TABLE planting_locations(location_id TEXT PRIMARY KEY,title TEXT NOT NULL UNIQUE,
 source_row INTEGER NOT NULL CHECK(source_row>0),plant_id TEXT NOT NULL REFERENCES planting_plants(plant_id),
 plant_probability_text TEXT NOT NULL,plant_probability_percent REAL NOT NULL CHECK(plant_probability_percent BETWEEN 0 AND 100),
 plant_description TEXT NOT NULL);
CREATE INDEX planting_locations_plant ON planting_locations(plant_id,title COLLATE NOCASE,location_id);
CREATE TABLE planting_fertilizers(fertilizer_id TEXT PRIMARY KEY,title TEXT NOT NULL UNIQUE,
 item_entry_id TEXT REFERENCES wiki_entries(id),image_asset TEXT REFERENCES content_assets(path),
 match_status TEXT NOT NULL CHECK(match_status IN ('matched','missing','ambiguous')),
 parameter_text TEXT NOT NULL,parameter_percent REAL NOT NULL CHECK(parameter_percent BETWEEN 0 AND 100),
 description TEXT NOT NULL,acquisition_hint TEXT NOT NULL,source_row INTEGER NOT NULL CHECK(source_row>0),
 source_uri TEXT NOT NULL);
CREATE TABLE planting_map_markers(source_map_id TEXT PRIMARY KEY,
 chapter_id TEXT NOT NULL CHECK(chapter_id IN ('kai','salvation')),chapter_source_token TEXT NOT NULL,
 location_name TEXT NOT NULL,location_id TEXT REFERENCES planting_locations(location_id),
 fertilizer_id TEXT REFERENCES planting_fertilizers(fertilizer_id),source_uri TEXT NOT NULL,
 source_row INTEGER NOT NULL CHECK(source_row>0),map_pin_id TEXT NOT NULL,
 CHECK((location_id IS NOT NULL AND fertilizer_id IS NULL) OR (location_id IS NULL AND fertilizer_id IS NOT NULL)));
CREATE INDEX planting_map_location ON planting_map_markers(location_id,chapter_id,source_map_id);
CREATE INDEX planting_map_fertilizer ON planting_map_markers(fertilizer_id,chapter_id,location_name,source_map_id);
CREATE TABLE support_metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL);
CREATE TABLE support_people(source_name TEXT PRIMARY KEY,display_name TEXT NOT NULL,
 character_entry_id TEXT REFERENCES wiki_entries(id),match_status TEXT NOT NULL CHECK(match_status IN ('matched','missing','ambiguous')));
CREATE INDEX support_people_character ON support_people(character_entry_id);
CREATE TABLE support_jobs(source_job_id TEXT PRIMARY KEY,job_entry_id TEXT REFERENCES wiki_entries(id),name TEXT NOT NULL UNIQUE,
 display_name TEXT NOT NULL,support_type TEXT NOT NULL,support_type_label TEXT NOT NULL,tiers_json TEXT NOT NULL,
 image_asset TEXT REFERENCES content_assets(path),match_status TEXT NOT NULL CHECK(match_status IN ('matched','missing','ambiguous')));
CREATE TABLE support_type_bonuses(support_type TEXT NOT NULL,support_type_label TEXT NOT NULL,
 support_level INTEGER NOT NULL CHECK(support_level BETWEEN 1 AND 5),physical_attack INTEGER NOT NULL,
 magic_attack INTEGER NOT NULL,physical_defense INTEGER NOT NULL,magic_defense INTEGER NOT NULL,
 hit INTEGER NOT NULL,avoid INTEGER NOT NULL,critical INTEGER NOT NULL,critical_avoid INTEGER NOT NULL,
 attack_speed INTEGER NOT NULL,PRIMARY KEY(support_type,support_level));
CREATE TABLE support_pairs(pair_id TEXT PRIMARY KEY,first_person_name TEXT NOT NULL REFERENCES support_people(source_name),
 second_person_name TEXT NOT NULL REFERENCES support_people(source_name),first_source_index INTEGER NOT NULL CHECK(first_source_index>=0),
 second_source_index INTEGER NOT NULL CHECK(second_source_index>=0),affinity INTEGER NOT NULL CHECK(affinity BETWEEN 1 AND 4),
 initial_level INTEGER NOT NULL CHECK(initial_level BETWEEN 0 AND 5),physical_attack INTEGER NOT NULL,
 magic_attack INTEGER NOT NULL,physical_defense INTEGER NOT NULL,magic_defense INTEGER NOT NULL,
 hit INTEGER NOT NULL,avoid INTEGER NOT NULL,critical INTEGER NOT NULL,critical_avoid INTEGER NOT NULL,
 attack_speed INTEGER NOT NULL,search_text TEXT NOT NULL,CHECK(first_person_name<>second_person_name));
CREATE INDEX support_pairs_first ON support_pairs(first_person_name,second_person_name,pair_id);
CREATE INDEX support_pairs_second ON support_pairs(second_person_name,first_person_name,pair_id);
CREATE INDEX support_pairs_search ON support_pairs(search_text);
CREATE TABLE support_dialogues(dialogue_id TEXT PRIMARY KEY,pair_id TEXT NOT NULL REFERENCES support_pairs(pair_id),
 rank INTEGER NOT NULL CHECK(rank BETWEEN 1 AND 4),start_part INTEGER NOT NULL,start_chapter INTEGER NOT NULL,
 end_part INTEGER NOT NULL,end_chapter INTEGER NOT NULL,route_id INTEGER NOT NULL CHECK(route_id>=0),
 any_joined_json TEXT NOT NULL,conditions_json TEXT NOT NULL,pending INTEGER NOT NULL CHECK(pending>=0),
 date_pending INTEGER NOT NULL CHECK(date_pending IN (0,1)),source_revision TEXT NOT NULL,
 CHECK(start_part>0),CHECK((end_part=0 AND end_chapter=0) OR end_part>0));
CREATE INDEX support_dialogues_pair_rank ON support_dialogues(pair_id,rank,start_part,start_chapter,dialogue_id);
CREATE TABLE restoration_metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL);
CREATE TABLE restoration_materials(material_id TEXT PRIMARY KEY,title TEXT NOT NULL UNIQUE,
 item_entry_id TEXT REFERENCES wiki_entries(id),match_status TEXT NOT NULL CHECK(match_status IN ('matched','missing','ambiguous')));
CREATE TABLE restoration_locations(location_id TEXT PRIMARY KEY,title TEXT NOT NULL,region TEXT NOT NULL,
 source_row INTEGER NOT NULL CHECK(source_row>0),evidence TEXT NOT NULL);
CREATE TABLE restoration_requirements(location_id TEXT NOT NULL REFERENCES restoration_locations(location_id),
 material_id TEXT NOT NULL REFERENCES restoration_materials(material_id),quantity INTEGER NOT NULL CHECK(quantity>0),
 position INTEGER NOT NULL CHECK(position>=0),evidence TEXT NOT NULL,PRIMARY KEY(location_id,material_id));
CREATE TABLE theater_metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL);
CREATE TABLE theater_notes(position INTEGER PRIMARY KEY,source_row INTEGER NOT NULL,cell_address TEXT NOT NULL,
 kind TEXT NOT NULL,text TEXT NOT NULL);
CREATE TABLE theater_activities(activity_id TEXT PRIMARY KEY,label TEXT NOT NULL,participants INTEGER NOT NULL CHECK(participants>0),
 effect TEXT NOT NULL,note TEXT NOT NULL,source_row INTEGER NOT NULL,cell_address TEXT NOT NULL,source_sheet_id TEXT NOT NULL);
CREATE TABLE theater_plays(play_id TEXT PRIMARY KEY,title TEXT NOT NULL,source_column INTEGER NOT NULL,position INTEGER NOT NULL UNIQUE);
CREATE TABLE theater_play_cues(play_id TEXT NOT NULL REFERENCES theater_plays(play_id),position INTEGER NOT NULL,
 source_row INTEGER NOT NULL,cell_address TEXT NOT NULL,raw_text TEXT NOT NULL,options_json TEXT NOT NULL,PRIMARY KEY(play_id,position));
CREATE TABLE theater_play_rewards(play_id TEXT NOT NULL REFERENCES theater_plays(play_id),
 rating TEXT NOT NULL CHECK(rating IN ('PERFECT','GREAT','GOOD','BAD')),position INTEGER NOT NULL,
 source_row INTEGER NOT NULL,cell_address TEXT NOT NULL,reward_text TEXT,PRIMARY KEY(play_id,rating));
CREATE TABLE theater_character_preferences(character_entry_id TEXT NOT NULL REFERENCES wiki_entries(id),
 play_id TEXT NOT NULL REFERENCES theater_plays(play_id),preference_label TEXT NOT NULL,evidence TEXT NOT NULL,
 source_sheet_id TEXT NOT NULL,source_row INTEGER NOT NULL,PRIMARY KEY(character_entry_id,play_id));
CREATE TABLE mechanics_metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE mechanics_groups(id TEXT PRIMARY KEY, title TEXT NOT NULL, category TEXT NOT NULL,
 description TEXT NOT NULL, href TEXT, count INTEGER NOT NULL CHECK(count>0),
 position INTEGER NOT NULL UNIQUE CHECK(position>=0));
CREATE TABLE mechanics_entries(id TEXT PRIMARY KEY, title TEXT NOT NULL,
 group_id TEXT NOT NULL REFERENCES mechanics_groups(id), kind TEXT NOT NULL,
 paragraphs_json TEXT NOT NULL, sources_json TEXT NOT NULL,
 position INTEGER NOT NULL CHECK(position>=0), search_text TEXT NOT NULL);
CREATE INDEX mechanics_entries_group ON mechanics_entries(group_id, position, id);
CREATE TABLE growth_mechanics_sections(id TEXT PRIMARY KEY, title TEXT NOT NULL,
 position INTEGER NOT NULL UNIQUE CHECK(position>=0), blocks_json TEXT NOT NULL);
CREATE TABLE growth_sim_metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE growth_sim_characters(native_id TEXT PRIMARY KEY, code TEXT NOT NULL,
 record_id INTEGER NOT NULL, name TEXT NOT NULL, faction TEXT NOT NULL, gender TEXT NOT NULL,
 start_class TEXT NOT NULL, start_level INTEGER NOT NULL, base_json TEXT NOT NULL,
 growth_json TEXT NOT NULL, caps_json TEXT NOT NULL, guarantee_pre INTEGER NOT NULL,
 guarantee_part3 INTEGER NOT NULL, position INTEGER NOT NULL UNIQUE CHECK(position>=0));
CREATE TABLE growth_sim_classes(id TEXT PRIMARY KEY, record_id INTEGER NOT NULL,
 name TEXT NOT NULL, tier TEXT NOT NULL, base_json TEXT NOT NULL, growth_bonus_json TEXT NOT NULL,
 base_growth_json TEXT NOT NULL, modifier_json TEXT NOT NULL, caps_json TEXT NOT NULL,
 mount_type TEXT NOT NULL, mount_multiplier INTEGER NOT NULL, allowed_records_json TEXT NOT NULL,
 forbidden_people_json TEXT NOT NULL, position INTEGER NOT NULL UNIQUE CHECK(position>=0));
CREATE INDEX growth_sim_classes_tier ON growth_sim_classes(tier, position);
CREATE TABLE growth_sim_mounts(id TEXT PRIMARY KEY, mount_type TEXT NOT NULL,
 name TEXT NOT NULL, growth_json TEXT NOT NULL, attr_bonus_json TEXT NOT NULL,
 effect TEXT NOT NULL, location TEXT NOT NULL, position INTEGER NOT NULL UNIQUE CHECK(position>=0));
CREATE INDEX growth_sim_mounts_type ON growth_sim_mounts(mount_type, position);

CREATE TABLE character_route_order (
    character_entry_id TEXT NOT NULL REFERENCES wiki_entries(id) ON DELETE CASCADE,
    character_name TEXT NOT NULL,
    route_key TEXT NOT NULL,
    route_label TEXT NOT NULL,
    fame_level INTEGER NOT NULL,
    join_kind TEXT NOT NULL,
    join_chapter TEXT NOT NULL DEFAULT '',
    join_chapter_num INTEGER NOT NULL DEFAULT 999,
    initial_level INTEGER,
    support_req TEXT NOT NULL DEFAULT '',
    poach_condition TEXT NOT NULL DEFAULT '',
    contact_location TEXT NOT NULL DEFAULT '',
    recommended INTEGER NOT NULL DEFAULT 0,
    source_order INTEGER NOT NULL,
    PRIMARY KEY (character_entry_id, route_key)
);

CREATE INDEX character_route_order_lookup ON character_route_order(route_key, fame_level, join_chapter_num, source_order);
"""


def main() -> int:
    parser = argparse.ArgumentParser(description="开发侧生成App内置语义SQLite资料库，不在App中解释表格")
    parser.add_argument("--package", type=Path, default=Path("data/derived/tencent-sheet"))
    parser.add_argument("--output", type=Path, default=Path("data/derived/wiki/content.db"))
    parser.add_argument("--adapters", type=Path, default=Path(__file__).with_name("wiki-adapters.json"))
    parser.add_argument("--record-source", action="append", default=[], metavar="ID=PATH")
    args = parser.parse_args()
    try:
        record_sources = {}
        for value in args.record_source:
            source_id, separator, source_path = value.partition("=")
            if (not separator or not source_path or not re.fullmatch(r"[a-z][a-z0-9-]*", source_id)
                    or source_id in record_sources):
                raise ContentDatabaseError(f"外部记录来源参数无效：{value}")
            record_sources[source_id] = Path(source_path)
        result = build_content_database(args.package, args.output, args.adapters, record_sources)
    except (ContentDatabaseError, sqlite3.Error, OSError, ValueError, KeyError, TypeError) as error:
        parser.error(str(error))
    print(f"Built offline content database: {len(result['categories'])} categories, {result['entries']} entries, "
          f"{result['assets']} assets, {result['bytes']} bytes")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
