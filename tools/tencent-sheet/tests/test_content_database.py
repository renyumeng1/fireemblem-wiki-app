from contextlib import closing
import hashlib
import json
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

from PIL import Image


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from build_content_db import (
    APPLICATION_ID,
    SCHEMA_VERSION,
    ContentDatabaseError,
    SUPPORT_TENCENT_GROUPS,
    build_content_database,
    _gift_tokens,
    _learning_tokens,
    _parse_learning_token,
    _reference_tokens,
)


class ContentDatabaseTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.package = self.root / "snapshot"
        (self.package / "tables").mkdir(parents=True)
        (self.package / "assets").mkdir()
        image_path = self.package / "assets/icon.webp"
        Image.new("RGB", (2, 3), "#a05032").save(image_path, format="WEBP")
        self._write_json(self.package / "image-assets-manifest.json", {
            "schema_version": 1,
            "assets": [{"file": "assets/icon.webp", "sha256": self._hash(image_path),
                        "source_sha256": "a" * 64, "dimensions": [2, 3], "bytes": image_path.stat().st_size}],
        })
        self.config = {
            "schema_version": 1,
            "categories": [{
                "id": "characters", "title": "角色", "description": "测试资料", "sheet": "demo1",
                "header_row": 2, "header": "角色名称", "first_row": 4, "title_column": 3,
                "image_column": 2, "subtitle_columns": [4], "identity_columns": [],
                "fields": [{"key": "affiliation", "label": "所属势力", "column": 4},
                           {"key": "growth_hp", "label": "HP成长率", "column": 5, "numeric": True},
                           {"key": "effect", "label": "效果", "column": 6},
                           {"key": "initial_level", "label": "等级", "column": 7, "numeric": True}],
            }],
        }
        self.adapters = self.root / "adapters.json"
        self._write_json(self.adapters, self.config)
        self.table = {
            "schema_version": 1,
            "metadata": {"sheet_id": "demo1", "name": "测试角色", "revision": 9, "merges": []},
            "cells": [
                {"row": 2, "column": 3, "type": "string", "value": "角色名称"},
                {"row": 4, "column": 2, "type": "string", "value": " ",
                 "image_reference": {"local_file": "assets/icon.jpeg", "source_asset_sha256": "a" * 64}},
                {"row": 4, "column": 3, "type": "string", "value": "测试勇者"},
                {"row": 4, "column": 4, "type": "string", "value": "测试军团"},
                {"row": 4, "column": 5, "type": "string", "value": "45%"},
                {"row": 4, "column": 6, "type": "rich_text", "value": "",
                 "rich_text_runs": [{"text": "首段"}, {"text": "续段"}]},
                {"row": 4, "column": 7, "type": "formula", "formula": {"cached_value": 0}},
                {"row": 5, "column": 3, "type": "string", "value": "另一名角色"},
                {"row": 5, "column": 5, "type": "string", "value": "尚未核实"},
                {"row": 5, "column": 6, "type": "string", "value": "%_ 字符也可查"},
            ],
        }
        self.output = self.root / "generated/content.db"
        self._save_table()

    def tearDown(self):
        self.temporary.cleanup()

    def _build(self):
        return build_content_database(self.package, self.output, self.adapters)

    def _save_table(self):
        table_path = self.package / "tables/demo1.json"
        self._write_json(table_path, self.table)
        self._write_json(self.package / "manifest.json", {
            "generator": "fireemblem/tools/tencent-sheet", "schema_version": 1,
            "source": {"revision": 9, "title": "测试图鉴", "uri": "https://example.invalid"},
            "sheets": [{"sheet_id": "demo1", "name": "测试角色", "status": "available",
                        "output_file": "tables/demo1.json", "output_sha256": self._hash(table_path),
                        "stored_cell_count": len(self.table["cells"])}],
            "unavailable_sheets": [{"sheet_id": "missing", "name": "未取得", "status": "unavailable"}],
        })

    def _save_restoration_fixture(self, demand="测试勇者×5、缺失材料×2", first_row=3, total=10, header="复兴地点名称"):
        self.config["categories"][0]["id"] = "items"
        self.config["restoration"] = {"sheet": "restoration"}
        self._write_json(self.adapters, self.config)
        rows = {
            2: {2: header, 3: "世界地图所在地", 4: "复兴所需材料", 6: "复兴所需材料", 7: "总数"},
            first_row: {2: "东站", 3: "州", 4: demand, 6: "测试勇者", 7: total},
            first_row + 1: {2: "西站", 3: "州", 4: "测试勇者×5", 6: "缺失材料", 7: 2},
        }
        cells = [{"row": row, "column": column, "type": "string", "value": value}
                 for row, columns in rows.items() for column, value in columns.items()]
        table_path = self.package / "tables/restoration.json"
        self._write_json(table_path, {"schema_version": 1, "metadata": {
            "sheet_id": "restoration", "name": "测试复兴", "revision": 9, "merges": [],
        }, "cells": cells})
        manifest = self._read_json(self.package / "manifest.json")
        manifest["sheets"] = [sheet for sheet in manifest["sheets"] if sheet["sheet_id"] != "restoration"] + [{
            "sheet_id": "restoration", "name": "测试复兴", "status": "available",
            "output_file": "tables/restoration.json", "output_sha256": self._hash(table_path), "stored_cell_count": len(cells),
        }]
        self._write_json(self.package / "manifest.json", manifest)

    def test_restoration_projects_exact_links_and_retains_missing_materials(self):
        self._save_restoration_fixture()
        report = self._build()["restoration"]
        self.assertEqual((2, 3, 2, 1, 12), tuple(report[key] for key in
                         ["locations", "requirements", "materials", "linked_materials", "total_units"]))
        self.assertEqual([], report["summary_differences"])
        self.assertEqual("缺失材料", report["gaps"][0]["material"])
        with closing(sqlite3.connect(self.output)) as database:
            self.assertEqual((None, "missing"), database.execute(
                "SELECT item_entry_id,match_status FROM restoration_materials WHERE title='缺失材料'"
            ).fetchone())
            self.assertEqual([], database.execute("PRAGMA foreign_key_check").fetchall())
            self.assertNotIn("restoration", self._read_json(self.output.with_suffix(".report.json"))["unadapted_sheets"])

    def test_restoration_row_moves_preserve_ids_and_total_conflicts_do_not_replace_requirements(self):
        self._save_restoration_fixture()
        self._build()
        with closing(sqlite3.connect(self.output)) as database:
            original = database.execute("SELECT location_id FROM restoration_locations ORDER BY title").fetchall()
        self._save_restoration_fixture(first_row=20, total=99)
        report = self._build()["restoration"]
        self.assertEqual([{"material": "测试勇者", "computed": 10, "source": 99}], report["summary_differences"])
        with closing(sqlite3.connect(self.output)) as database:
            self.assertEqual(original, database.execute("SELECT location_id FROM restoration_locations ORDER BY title").fetchall())
            self.assertEqual(12, database.execute("SELECT SUM(quantity) FROM restoration_requirements").fetchone()[0])

    def test_restoration_bad_quantity_duplicate_or_header_preserves_old_database(self):
        self._save_restoration_fixture()
        self._build()
        previous = self.output.read_bytes()
        for demand in ["测试勇者×0", "测试勇者×-1", "测试勇者×1000001", "测试勇者×五", "测试勇者×5、测试勇者×2"]:
            with self.subTest(demand=demand):
                self._save_restoration_fixture(demand=demand)
                with self.assertRaises(ContentDatabaseError):
                    self._build()
                self.assertEqual(previous, self.output.read_bytes())
        self._save_restoration_fixture(header="改变的表头")
        with self.assertRaisesRegex(ContentDatabaseError, "表头"):
            self._build()
        self.assertEqual(previous, self.output.read_bytes())

    def test_restoration_ambiguous_material_keeps_unlinked_requirement(self):
        self.config["categories"][0]["identity_columns"] = [4]
        self.table["cells"].extend([
            {"row": 6, "column": 3, "type": "string", "value": "测试勇者"},
            {"row": 6, "column": 4, "type": "string", "value": "另一版本"},
        ])
        self._save_table()
        self._save_restoration_fixture()
        report = self._build()["restoration"]
        self.assertEqual(0, report["linked_materials"])
        self.assertTrue(any(gap["material"] == "测试勇者" and gap["reason"] == "ambiguous" for gap in report["gaps"]))
        self.assertEqual(3, report["requirements"])

    def _save_theater_fixture(self, first_column=2, duplicate=False, stray=False, rating="PERFECT", untitled=False):
        self.config["theater"] = {"sheet": "theater", "activity_sheet": "weekly", "related_sidequest_title": "剧场支线"}
        self._write_json(self.adapters, self.config)
        rows = {1: {1: "蕾达线第一部11章（名声11）", first_column: "第一部剧", first_column + 1: "第一部剧" if duplicate else "第二部剧"},
                2: {1: "※看语义", first_column: "非常开心地\n满怀喜悦地"},
                4: {1: "※PERFECT不消耗干劲"}, 6: {1: "固定奖励10名声", first_column + 1: "愤恨一般地"},
                7: {1: rating, first_column: "1000酬金，随机礼物"},
                8: {1: "GREAT", first_column: "1000酬金"}, 9: {1: "GOOD", first_column: "500酬金"},
                10: {1: "BAD", first_column: "300酬金"}}
        if stray:
            rows[11] = {first_column: "未适配"}
        if untitled:
            rows[2][20] = "标题缺失的选项"
        weekly = {1: {35: "每周可做活动（每周日重置）"}, 2: {35: "内容", 36: "人数", 37: "效果", 38: "备注"},
                  7: {35: "观剧\n（观看费500G）", 36: 2, 37: "提高支援", 38: "每周二选一"},
                  8: {33: "绝不串读的加护条件", 35: "出演", 36: 3, 37: "消耗1点干劲"}}
        manifest = self._read_json(self.package / "manifest.json")
        for sheet_id, content, merges in [("theater", rows, []), ("weekly", weekly, [{"gridRangeData": {
                "startRowIndex": 6, "endRowIndex": 7, "startColIndex": 37, "endColIndex": 37}}])]:
            cells = [{"row": row, "column": column, "type": "string", "value": value}
                     for row, columns in content.items() for column, value in columns.items()]
            path = self.package / f"tables/{sheet_id}.json"
            self._write_json(path, {"schema_version": 1, "metadata": {
                "sheet_id": sheet_id, "name": sheet_id, "revision": 9, "merges": merges}, "cells": cells})
            manifest["sheets"] = [sheet for sheet in manifest["sheets"] if sheet["sheet_id"] != sheet_id] + [{
                "sheet_id": sheet_id, "name": sheet_id, "status": "available", "output_file": f"tables/{sheet_id}.json",
                "output_sha256": self._hash(path), "stored_cell_count": len(cells)}]
        self._write_json(self.package / "manifest.json", manifest)

    def test_theater_retains_variants_null_rewards_and_merged_activity_without_guessing_preferences(self):
        self._save_theater_fixture()
        report = self._build()
        self.assertEqual((2, 2, 4, 0), tuple(report["theater"][key] for key in ["plays", "cues", "recorded_rewards", "preferences"]))
        with closing(sqlite3.connect(self.output)) as database:
            self.assertEqual(8, database.execute("SELECT COUNT(*) FROM theater_play_rewards").fetchone()[0])
            self.assertEqual(4, database.execute("SELECT COUNT(*) FROM theater_play_rewards WHERE reward_text IS NULL").fetchone()[0])
            self.assertEqual(["非常开心地", "满怀喜悦地"], json.loads(database.execute(
                "SELECT options_json FROM theater_play_cues WHERE source_row=2").fetchone()[0]))
            self.assertEqual((6, "C6"), database.execute("SELECT source_row,cell_address FROM theater_play_cues WHERE source_row=6").fetchone())
            self.assertEqual(("每周二选一",), database.execute("SELECT note FROM theater_activities WHERE activity_id='perform'").fetchone())
            self.assertEqual(("unrecorded_in_source",), database.execute("SELECT value FROM theater_metadata WHERE key='preference_status'").fetchone())
            self.assertEqual([], database.execute("PRAGMA foreign_key_check").fetchall())
        self.assertNotIn("theater", report["unadapted_sheets"])
        self.assertIn("weekly", report["unadapted_sheets"])
        self.assertTrue(any(gap["reason"] == "unverified_cue_position" for gap in report["theater"]["gaps"]))

    def test_theater_column_moves_preserve_identity(self):
        self._save_theater_fixture()
        self._build()
        with closing(sqlite3.connect(self.output)) as database:
            original = database.execute("SELECT play_id FROM theater_plays ORDER BY title").fetchall()
        self._save_theater_fixture(first_column=5)
        self._build()
        with closing(sqlite3.connect(self.output)) as database:
            self.assertEqual(original, database.execute("SELECT play_id FROM theater_plays ORDER BY title").fetchall())

    def test_theater_invalid_header_duplicate_or_unadapted_row_preserves_previous_database(self):
        self._save_theater_fixture()
        self._build()
        previous = self.output.read_bytes()
        for options in [{"duplicate": True}, {"stray": True}, {"rating": "完美"}, {"untitled": True}]:
            with self.subTest(options=options):
                self._save_theater_fixture(**options)
                with self.assertRaises(ContentDatabaseError):
                    self._build()
                self.assertEqual(previous, self.output.read_bytes())

    def test_theater_manifest_mismatch_is_rejected(self):
        self._save_theater_fixture()
        manifest = self._read_json(self.package / "manifest.json")
        manifest["sheets"][-1]["stored_cell_count"] += 1
        self._write_json(self.package / "manifest.json", manifest)
        with self.assertRaisesRegex(ContentDatabaseError, "计数"):
            self._build()

    def test_theater_sources_reject_unsafe_paths_tampering_and_changed_revision(self):
        for field, value, message in [("output_file", "../theater.json", "路径"), ("output_sha256", "a" * 64, "校验")]:
            with self.subTest(field=field):
                self._save_theater_fixture()
                manifest = self._read_json(self.package / "manifest.json")
                manifest["sheets"][-1][field] = value
                self._write_json(self.package / "manifest.json", manifest)
                with self.assertRaisesRegex(ContentDatabaseError, message):
                    self._build()
        self._save_theater_fixture()
        path = self.package / "tables/weekly.json"
        table = self._read_json(path)
        table["metadata"]["revision"] = 10
        self._write_json(path, table)
        manifest = self._read_json(self.package / "manifest.json")
        manifest["sheets"][-1]["output_sha256"] = self._hash(path)
        self._write_json(self.package / "manifest.json", manifest)
        with self.assertRaisesRegex(ContentDatabaseError, "身份"):
            self._build()

    def test_business_tables_view_numeric_values_and_provenance(self):
        report = self._build()
        with closing(sqlite3.connect(self.output)) as database:
            self.assertEqual(APPLICATION_ID, database.execute("PRAGMA application_id").fetchone()[0])
            self.assertEqual(("45%", 45.0, "0", 0.0), database.execute(
                "SELECT growth_hp,growth_hp_numeric,initial_level,initial_level_numeric "
                "FROM characters_details WHERE growth_hp='45%'"
            ).fetchone())
            self.assertEqual(("尚未核实", None), database.execute(
                "SELECT growth_hp,growth_hp_numeric FROM characters_details WHERE growth_hp='尚未核实'"
            ).fetchone())
            self.assertEqual("首段续段", database.execute("SELECT value FROM wiki_fields WHERE label='效果' AND value LIKE '首段%'").fetchone()[0])
            self.assertEqual((4, "assets/icon.webp"), database.execute(
                "SELECT source_row,image_asset FROM wiki_entries WHERE title='测试勇者'"
            ).fetchone())
            self.assertEqual([], database.execute("PRAGMA foreign_key_check").fetchall())
        self.assertEqual({"characters": 2}, report["categories"])
        self.assertEqual(1, report["assets"])
        self.assertEqual("missing", report["unavailable_sheets"][0]["sheet_id"])

    def test_relations_require_unique_exact_targets_and_keep_unresolved_evidence(self):
        self.config["categories"][0]["fields"][2]["references"] = ["characters"]
        self._write_json(self.adapters, self.config)
        for cell in self.table["cells"]:
            if cell["row"] == 4 and cell["column"] == 6:
                cell["value"] = "另一名角色、未核实角色"
        self._save_table()
        report = self._build()
        with closing(sqlite3.connect(self.output)) as database:
            self.assertEqual(SCHEMA_VERSION, database.execute("PRAGMA user_version").fetchone()[0])
            self.assertEqual(1, database.execute("SELECT COUNT(*) FROM wiki_relations").fetchone()[0])
            self.assertEqual("另一名角色", database.execute("SELECT evidence FROM wiki_relations").fetchone()[0])
        self.assertTrue(any(gap["text"] == "未核实角色" for gap in report["relation_gaps"]))
        self.config["categories"][0]["identity_columns"] = [4]
        self._write_json(self.adapters, self.config)
        self.table["cells"].extend([
            {"row": 6, "column": 3, "type": "string", "value": "另一名角色"},
            {"row": 6, "column": 4, "type": "string", "value": "不同版本"},
        ])
        self._save_table()
        report = self._build()
        self.assertEqual(0, report["relations"])
        self.assertTrue(any(gap["reason"] == "ambiguous" for gap in report["relation_gaps"]))

    def test_external_record_sources_join_semantic_database_and_hash_into_content_version(self):
        page_hash = "a" * 64
        bundle_hash = "b" * 64
        revision = hashlib.sha256((page_hash + bundle_hash).encode("ascii")).hexdigest()
        source_data = {
            "schema_version": 1,
            "source": {
                "uri": "https://fireemblem.top/topics/endings",
                "page_sha256": page_hash,
                "bundle_sha256": bundle_hash,
                "revision": revision,
            },
            "records": [{
                "source_id": "pair-1",
                "source_row": 1,
                "identity_values": ["pair-1"],
                "title": "测试勇者 × 另一名角色",
                "fields": {
                    "ending_kind": "双人结局",
                    "characters": "测试勇者、另一名角色",
                    "character_titles": "勇者、同伴",
                    "ending_label": "后日谈 01",
                    "condition": "条件未确认",
                    "story": "这是需要保护的正文。",
                },
            }],
        }
        records_path = self.root / "endings.json"
        self._write_json(records_path, source_data)
        self.config["categories"].append({
            "id": "endings", "title": "结局", "description": "后日谈", "record_source": "endings",
            "source_id": "fireemblem-top-endings", "source_name": "fireemblem.top · 结局一览",
            "source_uri": "https://fireemblem.top/topics/endings", "first_row": 1,
            "title_column": 1, "image_column": None, "subtitle_columns": [2, 5], "identity_columns": [8],
            "fields": [
                {"key": "ending_kind", "label": "结局类型", "column": 2},
                {"key": "characters", "label": "相关角色", "column": 3,
                 "references": ["characters"], "reference_syntax": "list"},
                {"key": "character_titles", "label": "角色称号", "column": 4},
                {"key": "ending_label", "label": "结局标签", "column": 5},
                {"key": "condition", "label": "触发条件", "column": 6},
                {"key": "story", "label": "后日谈正文", "column": 7},
            ],
        })
        self._write_json(self.adapters, self.config)

        report = build_content_database(self.package, self.output, self.adapters, {"endings": records_path})

        with closing(sqlite3.connect(self.output)) as database:
            entry_id, title, source_name, source_row = database.execute(
                "SELECT entry.id,entry.title,category.source_sheet_name,entry.source_row "
                "FROM wiki_entries entry JOIN wiki_categories category ON category.id=entry.category_id "
                "WHERE entry.category_id='endings'"
            ).fetchone()
            self.assertEqual(("测试勇者 × 另一名角色", "fireemblem.top · 结局一览", 1),
                             (title, source_name, source_row))
            self.assertEqual("这是需要保护的正文。", database.execute(
                "SELECT story FROM endings_details WHERE entry_id=?", (entry_id,)
            ).fetchone()[0])
            self.assertEqual(2, database.execute(
                "SELECT COUNT(*) FROM wiki_relations WHERE source_id=?", (entry_id,)
            ).fetchone()[0])
            self.assertEqual("fireemblem.top · 结局一览", database.execute(
                "SELECT source_sheet_name FROM wiki_categories WHERE id='endings'"
            ).fetchone()[0])
        self.assertEqual(1, report["external_sources"]["endings"]["records"])
        self.assertEqual(3, report["entries"])

        source_data["records"][0]["fields"]["story"] = "更新后的正文。"
        self._write_json(records_path, source_data)
        updated = build_content_database(self.package, self.output, self.adapters, {"endings": records_path})
        self.assertNotEqual(report["content_version"], updated["content_version"])

    def test_external_category_requires_record_source_and_rejects_wrong_origin(self):
        category = {
            "id": "endings", "title": "结局", "description": "后日谈", "record_source": "endings",
            "source_id": "fireemblem-top-endings", "source_name": "fireemblem.top · 结局一览",
            "source_uri": "https://fireemblem.top/topics/endings", "first_row": 1,
            "title_column": 1, "image_column": None, "subtitle_columns": [], "identity_columns": [3],
            "fields": [{"key": "story", "label": "正文", "column": 2}],
        }
        self.config["categories"].append(category)
        self._write_json(self.adapters, self.config)
        with self.assertRaisesRegex(ContentDatabaseError, "缺少分类记录来源"):
            self._build()

        page_hash = "a" * 64
        bundle_hash = "b" * 64
        source_data = {
            "schema_version": 1,
            "source": {
                "uri": "https://example.invalid/wrong", "page_sha256": page_hash,
                "bundle_sha256": bundle_hash,
                "revision": hashlib.sha256((page_hash + bundle_hash).encode("ascii")).hexdigest(),
            },
            "records": [{
                "source_id": "one", "source_row": 1, "identity_values": ["one"], "title": "一条",
                "fields": {"story": "正文"},
            }],
        }
        records_path = self.root / "endings.json"
        self._write_json(records_path, source_data)
        with self.assertRaisesRegex(ContentDatabaseError, "身份不匹配"):
            build_content_database(self.package, self.output, self.adapters, {"endings": records_path})

    def test_reference_grammars_do_not_infer_names_from_prose(self):
        self.assertEqual(["乘降术", "战斗的基本"], _reference_tokens("【乘降术】【战斗的基本】", "skills"))
        self.assertEqual(["锻造石", "金币"], _reference_tokens("【修理】[锻造石]×1+[金币]×10", "materials"))
        self.assertEqual(["锻造石", "木制剑+1"], _reference_tokens("【锻造】消耗 [锻造石]×2 由 木制剑+1 锻造而成", "forge"))
        self.assertEqual([], _reference_tokens("请查看木制剑的介绍", "forge"))

    def test_planting_projects_exact_wiki_items_source_notes_and_map_markers(self):
        self.table["cells"] = [
            {**cell, "value": "稀有植物"} if cell.get("row") == 4 and cell.get("column") == 3 else
            {**cell, "value": "测试肥料"} if cell.get("row") == 5 and cell.get("column") == 3 else cell
            for cell in self.table["cells"]
        ]
        image_reference = next(cell["image_reference"] for cell in self.table["cells"] if "image_reference" in cell)
        self.table["cells"].append(
            {"row": 5, "column": 2, "type": "string", "value": " ", "image_reference": image_reference}
        )
        self._save_table()
        self.config["categories"].append({
            "id": "items", "title": "道具与材料", "description": "测试图片关联", "sheet": "demo1",
            "header_row": 2, "header": "角色名称", "first_row": 4, "title_column": 3,
            "image_column": 2, "subtitle_columns": [], "identity_columns": [], "fields": [],
        })
        self._write_json(self.adapters, self.config)
        source_data = {
            "schema_version": 1,
            "source": {"uri": "https://fireemblem.top/database/planting", "page_sha256": "a" * 64,
                       "revision": "a" * 64},
            "harvest_parameters": {
                "cycle_turns": 6, "cycle_evidence": "收获周期为 6 回合。", "yield_count": 10,
                "yield_count_status": "unverified", "yield_evidence": "收获数量：10（待确认）。",
                "probabilities": [{"harvest_number": 1, "probability_text": "100%",
                                    "probability_percent": 100, "verification_status": "unverified"}],
                "probability_note": "判定时点尚未验证。",
            },
            "source_notes": {"usage_note": "对田地施肥。", "fertilizer_note": "概率计算关系待验证。"},
            "plants": [{"name": "稀有植物"}],
            "locations": [{
                "source_row": 4, "name": "常雾平原", "plant_name": "稀有植物",
                "plant_probability_text": "30%", "plant_probability_percent": 30,
                "plant_description": "适合田地的样本植物。",
                "map_markers": [{
                    "source_map_id": "カイ|11003", "chapter_source_token": "カイ", "chapter_id": "kai",
                    "location_name": "常雾平原",
                    "source_uri": "https://fireemblem.top/maps?pin=%E3%82%AB%E3%82%A4%7C11003",
                    "source_row": 4,
                }],
            }],
            "fertilizers": [{
                "source_row": 1, "name": "测试肥料", "parameter_text": "50%", "parameter_percent": 50,
                "description": "改善土质。", "acquisition_hint": "可在样本山地捡到。",
                "map_markers": [{
                    "source_map_id": "救世編|13007", "chapter_source_token": "救世編", "chapter_id": "salvation",
                    "location_name": "样本山地",
                    "source_uri": "https://fireemblem.top/maps?pin=%E6%95%91%E4%B8%96%E7%B7%A8%7C13007",
                    "source_row": 1,
                }],
            }],
        }
        planting_path = self.root / "planting.json"
        self._write_json(planting_path, source_data)

        report = build_content_database(self.package, self.output, self.adapters, {"planting": planting_path})

        with closing(sqlite3.connect(self.output)) as database:
            self.assertEqual(SCHEMA_VERSION, database.execute("PRAGMA user_version").fetchone()[0])
            self.assertEqual((1, 1, 1), database.execute(
                "SELECT (SELECT COUNT(*) FROM planting_locations),(SELECT COUNT(*) FROM planting_plants),"
                "(SELECT COUNT(*) FROM planting_fertilizers)"
            ).fetchone())
            self.assertEqual(("matched", "assets/icon.webp"), database.execute(
                "SELECT match_status,image_asset FROM planting_plants"
            ).fetchone())
            self.assertEqual(("matched", "assets/icon.webp", "改善土质。", "可在样本山地捡到。"), database.execute(
                "SELECT match_status,image_asset,description,acquisition_hint FROM planting_fertilizers"
            ).fetchone())
            self.assertEqual(("カイ|11003", "kai", "常雾平原"), database.execute(
                "SELECT source_map_id,chapter_id,location_name FROM planting_map_markers WHERE location_id IS NOT NULL"
            ).fetchone())
            self.assertEqual("unverified", database.execute(
                "SELECT verification_status FROM planting_harvest_parameters WHERE parameter_key='yield_count'"
            ).fetchone()[0])
            self.assertEqual("判定时点尚未验证。", database.execute(
                "SELECT value FROM planting_metadata WHERE key='probability_note'"
            ).fetchone()[0])
        self.assertEqual({"locations": 1, "plants": 1, "fertilizers": 1, "map_markers": 2,
                          "location_map_markers": 1, "fertilizer_map_markers": 1,
                          "matched_plants": 1, "matched_fertilizers": 1, "gaps": [], "available": True,
                          "source_uri": "https://fireemblem.top/database/planting", "source_revision": "a" * 64},
                         report["planting"])
        self.assertEqual(3, report["external_sources"]["planting"]["records"])

    def test_character_learning_parser_handles_packed_items_and_mixed_parentheses(self):
        self.assertEqual(["单挑（C）", "魔法强打（B）", "铠甲跳跃+（B)"],
                         _learning_tokens("单挑（C）                魔法强打（B）\n铠甲跳跃+（B)"))
        self.assertEqual(("铠甲跳跃+", "B"), _parse_learning_token("铠甲跳跃+（B)"))

    def test_gift_preferences_use_exact_bidirectional_links_and_keep_source_gaps(self):
        self.assertEqual(["苹果", "蜂蜜奶", "鱼酱"], _gift_tokens("苹果、蜂蜜奶，鱼酱\n"))
        gifts_table = {
            "schema_version": 1,
            "metadata": {"sheet_id": "demo2", "name": "礼物资料", "revision": 9, "merges": []},
            "cells": [
                {"row": 1, "column": 2, "type": "string", "value": "礼物名称"},
                {"row": 2, "column": 2, "type": "string", "value": "苹果"},
                {"row": 2, "column": 3, "type": "string", "value": " ",
                 "image_reference": {"local_file": "assets/icon.jpeg", "source_asset_sha256": "a" * 64}},
                {"row": 2, "column": 4, "type": "string", "value": "水果礼物"},
                {"row": 3, "column": 2, "type": "string", "value": "梨"},
                {"row": 3, "column": 4, "type": "string", "value": "另一种水果礼物"},
            ],
        }
        preferences_table = {
            "schema_version": 1,
            "metadata": {"sheet_id": "demo3", "name": "角色礼物偏好", "revision": 9, "merges": []},
            "cells": [
                {"row": 1, "column": 2, "type": "string", "value": "角色名称"},
                {"row": 2, "column": 5, "type": "string", "value": "1-非常喜欢"},
                {"row": 2, "column": 6, "type": "string", "value": "2-很喜欢"},
                {"row": 2, "column": 7, "type": "string", "value": "3-还算喜欢"},
                {"row": 2, "column": 8, "type": "string", "value": "4-不喜欢\n赠送不提升支援"},
                {"row": 3, "column": 2, "type": "string", "value": "测试勇者"},
                {"row": 3, "column": 4, "type": "string", "value": "偏好水果类"},
                {"row": 3, "column": 5, "type": "string", "value": "苹果、梨、未收录苹果"},
                {"row": 3, "column": 7, "type": "string", "value": "梨"},
                {"row": 4, "column": 2, "type": "string", "value": "另一名角色"},
                {"row": 4, "column": 8, "type": "string", "value": "苹果"},
                {"row": 5, "column": 2, "type": "string", "value": "不在图鉴的角色"},
                {"row": 5, "column": 5, "type": "string", "value": "苹果"},
            ],
        }
        self.config["categories"].append({
            "id": "gifts", "title": "礼物", "description": "测试礼物", "sheet": "demo2",
            "header_row": 1, "header": "礼物名称", "first_row": 2, "title_column": 2,
            "image_column": 3, "subtitle_columns": [], "identity_columns": [],
            "fields": [{"key": "description", "label": "简介", "column": 4}],
        })
        self.config["gift_preferences"] = {
            "sheet": "demo3", "header_row": 1, "header": "角色名称", "name_column": 2,
            "category_column": 4, "tier_header_row": 2, "first_row": 3,
            "tiers": [
                {"column": 5, "level": 1, "header": "1-非常喜欢"},
                {"column": 6, "level": 2, "header": "2-很喜欢"},
                {"column": 7, "level": 3, "header": "3-还算喜欢"},
                {"column": 8, "level": 4, "header": "4-不喜欢"},
            ],
        }
        self._write_json(self.adapters, self.config)
        source_sheets = self._read_json(self.package / "manifest.json")["sheets"]
        for table in (gifts_table, preferences_table):
            sheet_id = table["metadata"]["sheet_id"]
            table_path = self.package / f"tables/{sheet_id}.json"
            self._write_json(table_path, table)
            source_sheets.append({
                "sheet_id": sheet_id, "name": table["metadata"]["name"], "status": "available",
                "output_file": f"tables/{sheet_id}.json", "output_sha256": self._hash(table_path),
                "stored_cell_count": len(table["cells"]),
            })
        manifest = self._read_json(self.package / "manifest.json")
        manifest["sheets"] = source_sheets
        self._write_json(self.package / "manifest.json", manifest)

        report = self._build()

        with closing(sqlite3.connect(self.output)) as database:
            rows = database.execute(
                "SELECT character.title,gift.title,preference.preference,preference.evidence,"
                "preference.source_row,preference.source_column "
                "FROM character_gift_preferences preference "
                "JOIN wiki_entries character ON character.id=preference.character_id "
                "JOIN wiki_entries gift ON gift.id=preference.gift_entry_id "
                "ORDER BY character.title,preference.preference"
            ).fetchall()
            self.assertEqual([
                ("另一名角色", "苹果", 4, "苹果", 4, 8),
                ("测试勇者", "苹果", 1, "苹果", 3, 5),
            ], rows)
            category_text = database.execute(
                "SELECT category_preferences FROM character_gift_sources "
                "JOIN wiki_entries character ON character.id=character_gift_sources.character_id "
                "WHERE character.title='测试勇者'"
            ).fetchone()[0]
            self.assertEqual("偏好水果类", category_text)
            self.assertEqual([], database.execute("PRAGMA foreign_key_check").fetchall())
        self.assertEqual(5, report["gift_preferences"]["records"])
        self.assertEqual(2, report["gift_preferences"]["linked_records"])
        self.assertTrue(any(gap.get("gift") == "未收录苹果" and gap["reason"] == "gift_not_found"
                            for gap in report["gift_preferences"]["gaps"]))
        self.assertTrue(any(gap.get("gift") == "梨" and gap["reason"] == "conflicting_preference_tiers"
                            for gap in report["gift_preferences"]["gaps"]))
        self.assertTrue(any(gap.get("character") == "不在图鉴的角色"
                            and gap["reason"] == "character_not_found"
                            for gap in report["gift_preferences"]["gaps"]))

    def test_dining_builds_exact_preferences_meal_images_and_unique_trios(self):
        self.table["cells"].extend([
            {"row": 6, "column": 3, "type": "string", "value": "第三名角色"},
        ])
        self._save_table()
        meal_table = {
            "schema_version": 1,
            "metadata": {"sheet_id": "demo2", "name": "料理目录", "revision": 9, "merges": []},
            "cells": [
                {"row": 2, "column": 8, "type": "string", "value": "菜品"},
                {"row": 3, "column": 8, "type": "string", "value": " ",
                 "image_reference": {"local_file": "assets/icon.png", "source_asset_sha256": "a" * 64}},
                {"row": 3, "column": 9, "type": "string", "value": "苹果炖肉"},
                {"row": 5, "column": 8, "type": "string", "value": " ",
                 "image_reference": {"local_file": "assets/icon.png", "source_asset_sha256": "a" * 64}},
                {"row": 5, "column": 9, "type": "string", "value": "蜂蜜汤"},
            ],
        }
        dining_table = {
            "schema_version": 1,
            "metadata": {"sheet_id": "demo3", "name": "角色用餐偏好", "revision": 9, "merges": []},
            "cells": [
                {"row": 1, "column": 2, "type": "string", "value": "角色名称"},
                {"row": 2, "column": 3, "type": "string", "value": "特别喜欢"},
                {"row": 2, "column": 4, "type": "string", "value": "喜欢"},
                {"row": 2, "column": 5, "type": "string", "value": "普通"},
                {"row": 2, "column": 6, "type": "string", "value": "不喜欢"},
                {"row": 2, "column": 38, "type": "string", "value": "角色1"},
                {"row": 2, "column": 40, "type": "string", "value": "角色2"},
                {"row": 2, "column": 42, "type": "string", "value": "角色3"},
                {"row": 2, "column": 44, "type": "string", "value": "关系1-2"},
                {"row": 2, "column": 45, "type": "string", "value": "关系1-3"},
                {"row": 2, "column": 46, "type": "string", "value": "关系2-3"},
                {"row": 2, "column": 47, "type": "string", "value": "组合最低等级"},
                {"row": 3, "column": 2, "type": "string", "value": "测试勇者"},
                {"row": 3, "column": 3, "type": "string", "value": "苹果炖肉、未收录菜品"},
                {"row": 3, "column": 4, "type": "string", "value": "蜂蜜汤"},
                {"row": 3, "column": 5, "type": "string", "value": "蜂蜜汤"},
                {"row": 3, "column": 39, "type": "string", "value": "测试勇者"},
                {"row": 3, "column": 41, "type": "string", "value": "另一名角色"},
                {"row": 3, "column": 43, "type": "string", "value": "第三名角色"},
                {"row": 3, "column": 44, "type": "string", "value": "A"},
                {"row": 3, "column": 45, "type": "string", "value": "B"},
                {"row": 3, "column": 46, "type": "string", "value": "C"},
                {"row": 3, "column": 47, "type": "string", "value": "B"},
                {"row": 4, "column": 2, "type": "string", "value": "另一名角色"},
                {"row": 4, "column": 5, "type": "string", "value": "苹果炖肉"},
                {"row": 4, "column": 39, "type": "string", "value": "第三名角色"},
                {"row": 4, "column": 41, "type": "string", "value": "另一名角色"},
                {"row": 4, "column": 43, "type": "string", "value": "测试勇者"},
                {"row": 4, "column": 44, "type": "string", "value": "C"},
                {"row": 4, "column": 45, "type": "string", "value": "B"},
                {"row": 4, "column": 46, "type": "string", "value": "A"},
                {"row": 4, "column": 47, "type": "string", "value": "B"},
                {"row": 5, "column": 2, "type": "string", "value": "第三名角色"},
                {"row": 5, "column": 6, "type": "string", "value": "蜂蜜汤"},
                {"row": 5, "column": 39, "type": "string", "value": "测试勇者"},
                {"row": 5, "column": 41, "type": "string", "value": "另一名角色"},
                {"row": 6, "column": 2, "type": "string", "value": "不在图鉴的角色"},
                {"row": 6, "column": 3, "type": "string", "value": "苹果炖肉"},
            ],
        }
        self.config["categories"].append({
            "id": "meals", "title": "料理", "description": "测试料理", "sheet": "demo2",
            "header_row": 2, "header_column": 8, "header": "菜品", "first_row": 3, "last_row": 5,
            "row_stride": 2, "title_column": 9, "image_column": 8, "subtitle_columns": [],
            "identity_columns": [], "fields": [],
        })
        self.config["dining"] = {
            "sheet": "demo3", "character_header_row": 1, "character_header": "角色名称",
            "name_column": 2, "first_row": 3, "tier_header_row": 2,
            "tiers": [
                {"column": 3, "level": 1, "header": "特别喜欢"},
                {"column": 4, "level": 2, "header": "喜欢"},
                {"column": 5, "level": 3, "header": "普通"},
                {"column": 6, "level": 4, "header": "不喜欢"},
            ],
            "trio_first_row": 3, "trio_header_row": 2,
            "trio_members": [
                {"header_column": 38, "column": 39, "header": "角色1"},
                {"header_column": 40, "column": 41, "header": "角色2"},
                {"header_column": 42, "column": 43, "header": "角色3"},
            ],
            "trio_relations": [
                {"column": 44, "header": "关系1-2"},
                {"column": 45, "header": "关系1-3"},
                {"column": 46, "header": "关系2-3"},
            ],
            "trio_minimum": {"column": 47, "header": "组合最低等级"},
        }
        self._write_json(self.adapters, self.config)
        source_sheets = self._read_json(self.package / "manifest.json")["sheets"]
        for table in (meal_table, dining_table):
            sheet_id = table["metadata"]["sheet_id"]
            table_path = self.package / f"tables/{sheet_id}.json"
            self._write_json(table_path, table)
            source_sheets.append({
                "sheet_id": sheet_id, "name": table["metadata"]["name"], "status": "available",
                "output_file": f"tables/{sheet_id}.json", "output_sha256": self._hash(table_path),
                "stored_cell_count": len(table["cells"]),
            })
        manifest = self._read_json(self.package / "manifest.json")
        manifest["sheets"] = source_sheets
        self._write_json(self.package / "manifest.json", manifest)

        report = self._build()

        with closing(sqlite3.connect(self.output)) as database:
            self.assertEqual(SCHEMA_VERSION, database.execute("PRAGMA user_version").fetchone()[0])
            self.assertEqual(2, database.execute("SELECT COUNT(*) FROM wiki_entries WHERE category_id='meals'").fetchone()[0])
            self.assertEqual(("assets/icon.webp",), database.execute(
                "SELECT image_asset FROM wiki_entries WHERE title='苹果炖肉'"
            ).fetchone())
            self.assertEqual(0, database.execute(
                "SELECT COUNT(*) FROM wiki_fields WHERE entry_id=(SELECT id FROM wiki_entries WHERE title='苹果炖肉')"
            ).fetchone()[0])
            self.assertEqual([
                ("另一名角色", "苹果炖肉", 3),
                ("测试勇者", "苹果炖肉", 1),
                ("第三名角色", "蜂蜜汤", 4),
            ], database.execute(
                "SELECT character.title,meal.title,preference.preference FROM character_meal_preferences preference "
                "JOIN wiki_entries character ON character.id=preference.character_id "
                "JOIN wiki_entries meal ON meal.id=preference.meal_entry_id "
                "ORDER BY character.title,meal.title"
            ).fetchall())
            self.assertEqual(("测试勇者", "另一名角色", "第三名角色", "A", "B", "C", "B", 3), database.execute(
                "SELECT first.title,second.title,third.title,relationship_12,relationship_13,relationship_23,"
                "minimum_level,dining_trios.source_row FROM dining_trios "
                "JOIN wiki_entries first ON first.id=character_1_id "
                "JOIN wiki_entries second ON second.id=character_2_id "
                "JOIN wiki_entries third ON third.id=character_3_id"
            ).fetchone())
            self.assertEqual([], database.execute("PRAGMA foreign_key_check").fetchall())
        self.assertEqual(6, report["dining"]["preference_records"])
        self.assertEqual(3, report["dining"]["linked_preferences"])
        self.assertEqual(3, report["dining"]["trio_records"])
        self.assertEqual(1, report["dining"]["linked_trios"])
        self.assertTrue(any(gap.get("meal") == "未收录菜品" and gap["reason"] == "meal_not_found"
                            for gap in report["dining"]["preference_gaps"]))
        self.assertTrue(any(gap["reason"] == "conflicting_preference_tiers"
                            for gap in report["dining"]["preference_gaps"]))
        self.assertTrue(any(gap.get("character") == "不在图鉴的角色"
                            and gap["reason"] == "character_not_found"
                            for gap in report["dining"]["preference_gaps"]))
        self.assertTrue(any(gap["reason"] == "duplicate_trio" for gap in report["dining"]["trio_gaps"]))
        self.assertTrue(any(gap["reason"] == "incomplete_trio" for gap in report["dining"]["trio_gaps"]))

        dining_table["cells"].extend([
            {"row": 7, "column": 39, "type": "string", "value": "测试勇者"},
            {"row": 7, "column": 41, "type": "string", "value": "测试勇者"},
            {"row": 7, "column": 43, "type": "string", "value": "第三名角色"},
            {"row": 8, "column": 39, "type": "string", "value": "另一名角色"},
            {"row": 8, "column": 41, "type": "string", "value": "测试勇者"},
            {"row": 8, "column": 43, "type": "string", "value": "第三名角色"},
            {"row": 8, "column": 44, "type": "string", "value": "A"},
            {"row": 8, "column": 45, "type": "string", "value": "B"},
            {"row": 8, "column": 46, "type": "string", "value": "C"},
            {"row": 8, "column": 47, "type": "string", "value": "A"},
        ])
        dining_path = self.package / "tables/demo3.json"
        self._write_json(dining_path, dining_table)
        manifest = self._read_json(self.package / "manifest.json")
        for source_sheet in manifest["sheets"]:
            if source_sheet["sheet_id"] == "demo3":
                source_sheet["output_sha256"] = self._hash(dining_path)
                source_sheet["stored_cell_count"] = len(dining_table["cells"])
        self._write_json(self.package / "manifest.json", manifest)
        conflict_report = self._build()
        with closing(sqlite3.connect(self.output)) as database:
            self.assertEqual(0, database.execute("SELECT COUNT(*) FROM dining_trios").fetchone()[0])
        self.assertEqual(0, conflict_report["dining"]["linked_trios"])
        self.assertTrue(any(gap["reason"] == "duplicate_character_in_trio"
                            for gap in conflict_report["dining"]["trio_gaps"]))
        self.assertTrue(any(gap["reason"] == "conflicting_trio_levels"
                            for gap in conflict_report["dining"]["trio_gaps"]))

        previous_database = self.output.read_bytes()
        dining_table["cells"] = [
            cell for cell in dining_table["cells"]
            if not (cell["row"] == 2 and cell["column"] == 3)
        ]
        self._write_json(dining_path, dining_table)
        manifest = self._read_json(self.package / "manifest.json")
        for source_sheet in manifest["sheets"]:
            if source_sheet["sheet_id"] == "demo3":
                source_sheet["output_sha256"] = self._hash(dining_path)
                source_sheet["stored_cell_count"] = len(dining_table["cells"])
        self._write_json(self.package / "manifest.json", manifest)
        with self.assertRaisesRegex(ContentDatabaseError, "用餐偏好等级表头不匹配"):
            self._build()
        self.assertEqual(previous_database, self.output.read_bytes())

    def test_character_learning_keeps_conditions_links_and_unmatched_source_text(self):
        skills_table = {
            "schema_version": 1,
            "metadata": {"sheet_id": "demo2", "name": "测试特技", "revision": 9, "merges": []},
            "cells": [
                {"row": 2, "column": 3, "type": "string", "value": "特技名称"},
                {"row": 4, "column": 3, "type": "string", "value": "神速"},
                {"row": 4, "column": 4, "type": "string", "value": "效果"},
            ],
        }
        learning_table = {
            "schema_version": 1,
            "metadata": {"sheet_id": "demo3", "name": "角色可学技能", "revision": 9, "merges": []},
            "cells": [
                {"row": 1, "column": 1, "type": "string", "value": "中文名"},
                {"row": 2, "column": 3, "type": "string", "value": "剑术"},
                {"row": 4, "column": 1, "type": "string", "value": "测试勇者"},
                {"row": 4, "column": 3, "type": "string", "value": "神速（B）\n未收录（A）"},
                {"row": 5, "column": 1, "type": "string", "value": "另一名角色"},
                {"row": 5, "column": 3, "type": "string", "value": "神速（D）"},
            ],
        }
        self.config["categories"].append({
            "id": "skills", "title": "特技", "description": "效果", "sheet": "demo2",
            "header_row": 2, "header": "特技名称", "first_row": 4, "title_column": 3,
            "image_column": None, "subtitle_columns": [],
            "fields": [{"key": "effect", "label": "效果", "column": 4}], "identity_columns": [],
        })
        self.config["character_learning"] = {
            "sheet": "demo3", "header_row": 1, "header": "中文名", "name_column": 1,
            "group_header_row": 2, "first_row": 4,
            "groups": [{"column": 3, "label": "剑术"}],
        }
        self._write_json(self.adapters, self.config)
        source_sheets = self._read_json(self.package / "manifest.json")["sheets"]
        for table in (skills_table, learning_table):
            sheet_id = table["metadata"]["sheet_id"]
            table_path = self.package / f"tables/{sheet_id}.json"
            self._write_json(table_path, table)
            source_sheets.append({
                "sheet_id": sheet_id, "name": table["metadata"]["name"], "status": "available",
                "output_file": f"tables/{sheet_id}.json", "output_sha256": self._hash(table_path),
                "stored_cell_count": len(table["cells"]),
            })
        manifest = self._read_json(self.package / "manifest.json")
        manifest["sheets"] = source_sheets
        self._write_json(self.package / "manifest.json", manifest)

        report = self._build()

        with closing(sqlite3.connect(self.output)) as database:
            learning = database.execute(
                "SELECT learning.skill_name,learning.requirement,learning.group_label,learning.skill_entry_id "
                "FROM character_skill_learnings learning JOIN wiki_entries character "
                "ON character.id=learning.character_id WHERE character.title='测试勇者' "
                "ORDER BY learning.position"
            ).fetchall()
            self.assertEqual([("神速", "B", "剑术", learning[0][3]), ("未收录", "A", "剑术", None)], learning)
            self.assertTrue(learning[0][3])
            skill_id = learning[0][3]
            self.assertEqual(("可学技能 · 剑术", "神速（B）"), database.execute(
                "SELECT label,evidence FROM wiki_relations WHERE source_id=? AND target_id=?",
                (self._entry_id(database, "characters", "测试勇者"), skill_id),
            ).fetchone())
            self.assertEqual(("demo3", "角色可学技能", 4), database.execute(
                "SELECT source.source_sheet_id,source.source_sheet_name,source.source_row "
                "FROM character_learning_sources source JOIN wiki_entries character "
                "ON character.id=source.character_id WHERE character.title='测试勇者'"
            ).fetchone())
        self.assertEqual(3, report["character_learning"]["records"])
        self.assertEqual(2, report["character_learning"]["linked_records"])
        self.assertTrue(any(gap["skill"] == "未收录" and gap["reason"] == "skill_not_found"
                            for gap in report["character_learning"]["gaps"]))

    def test_auxiliary_column_name_collisions_fail_with_a_diagnostic(self):
        self.config["categories"][0]["fields"].append({"key": "affiliation_image", "label": "图标", "column": 8})
        self._write_json(self.adapters, self.config)
        with self.assertRaisesRegex(ContentDatabaseError, "重名"):
            self._build()

    def test_weapon_and_accessory_sections_keep_their_own_rows_and_fields(self):
        source_adapters = json.loads((Path(__file__).resolve().parents[1] / "wiki-adapters.json").read_text(encoding="utf-8"))
        categories = [category for category in source_adapters["categories"]
                      if category["id"] in {"weapons", "accessories"}]
        self.assertEqual(["weapons", "accessories"], [category["id"] for category in categories])
        self._write_json(self.adapters, {"schema_version": 1, "categories": categories})

        table = {
            "schema_version": 1,
            "metadata": {"sheet_id": "twr89r", "name": "测试装备", "revision": 9, "merges": []},
            "cells": [
                {"row": 2, "column": 4, "type": "string", "value": "武器名称"},
                {"row": 4, "column": 2, "type": "string", "value": "剑"},
                {"row": 4, "column": 4, "type": "string", "value": "测试长剑"},
                {"row": 4, "column": 6, "type": "string", "value": "A"},
                {"row": 4, "column": 15, "type": "string", "value": "38"},
                {"row": 487, "column": 2, "type": "string", "value": "枪"},
                {"row": 487, "column": 4, "type": "string", "value": "末行武器"},
                {"row": 487, "column": 6, "type": "string", "value": "S"},
                {"row": 487, "column": 15, "type": "string", "value": "25"},
                {"row": 488, "column": 4, "type": "string", "value": "装备品名称"},
                {"row": 490, "column": 2, "type": "string", "value": "盾"},
                {"row": 490, "column": 4, "type": "string", "value": "测试盾牌"},
                {"row": 490, "column": 5, "type": "string", "value": "测试盾牌说明"},
                {"row": 490, "column": 6, "type": "string", "value": "防御+5"},
                {"row": 490, "column": 15, "type": "string", "value": "3"},
                {"row": 490, "column": 16, "type": "string", "value": "测试宝箱"},
            ],
        }
        table_path = self.package / "tables/twr89r.json"
        self._write_json(table_path, table)
        self._write_json(self.package / "manifest.json", {
            "generator": "fireemblem/tools/tencent-sheet", "schema_version": 1,
            "source": {"revision": 9, "title": "测试图鉴", "uri": "https://example.invalid"},
            "sheets": [{"sheet_id": "twr89r", "name": "测试装备", "status": "available",
                        "output_file": "tables/twr89r.json", "output_sha256": self._hash(table_path),
                        "stored_cell_count": len(table["cells"])}],
            "unavailable_sheets": [],
        })

        report = self._build()
        with closing(sqlite3.connect(self.output)) as database:
            self.assertEqual([("测试长剑", "A", "38"), ("末行武器", "S", "25")], database.execute(
                "SELECT entry.title,details.rank,details.durability FROM weapons_details details "
                "JOIN wiki_entries entry ON entry.id=details.entry_id ORDER BY entry.source_row"
            ).fetchall())
            self.assertEqual(("盾", "防御+5", "3", 3.0, "测试宝箱"), database.execute(
                "SELECT details.equipment_type,details.additional_effects,details.weight,"
                "details.weight_numeric,details.acquisition FROM accessories_details details "
                "JOIN wiki_entries entry ON entry.id=details.entry_id WHERE entry.title='测试盾牌'"
            ).fetchone())
            accessory_columns = {row[1] for row in database.execute("PRAGMA table_info(accessories_details)")}
            self.assertNotIn("rank", accessory_columns)
            self.assertNotIn("durability", accessory_columns)
            self.assertEqual([("accessories", 1), ("weapons", 2)], database.execute(
                "SELECT category_id,COUNT(*) FROM wiki_entries GROUP BY category_id ORDER BY category_id"
            ).fetchall())
        self.assertEqual({"weapons": 2, "accessories": 1}, report["categories"])

    def test_section_markers_game_dates_and_title_fallbacks_are_projected_semantically(self):
        category = self.config["categories"][0]
        category.update({
            "first_row": 3,
            "title_fallback_columns": [4],
            "section_markers": {"column": 1, "values": {"凯伊篇": "kai", "共通篇": "common"}},
        })
        category["fields"].extend([
            {"key": "route", "label": "路线范围", "column": 1, "source": "section"},
            {"key": "game_date", "label": "游戏日期", "column": 8, "format": "game-date"},
            {"key": "deadline", "label": "截止时间", "column": 9, "format": "game-date", "blank_if_merged": True},
        ])
        self.table["metadata"]["merges"] = [{"gridRangeData": {
            "startRowIndex": 3, "endRowIndex": 4, "startColIndex": 7, "endColIndex": 8,
        }}]
        self.table["cells"] = [
            {"row": 2, "column": 3, "type": "string", "value": "角色名称"},
            {"row": 3, "column": 1, "type": "string", "value": "凯伊篇"},
            {"row": 4, "column": 3, "type": "string", "value": "相同标题"},
            {"row": 4, "column": 8, "type": "number", "value": 46056},
            {"row": 5, "column": 1, "type": "string", "value": "共通篇"},
            {"row": 6, "column": 3, "type": "string", "value": "相同标题"},
            {"row": 6, "column": 8, "type": "number", "value": 46149},
            {"row": 7, "column": 4, "type": "string", "value": "第3章"},
            {"row": 7, "column": 8, "type": "number", "value": 46150},
        ]
        self._write_json(self.adapters, self.config)
        self._save_table()

        report = self._build()
        with closing(sqlite3.connect(self.output)) as database:
            records = database.execute(
                "SELECT entry.title,details.route,details.game_date,details.deadline,entry.id "
                "FROM characters_details details JOIN wiki_entries entry ON entry.id=details.entry_id "
                "ORDER BY entry.source_row"
            ).fetchall()
        self.assertEqual([("相同标题", "kai", "2月3日", None), ("相同标题", "common", "5月7日", None),
                          ("第3章", "common", "5月8日", None)], [record[:4] for record in records])
        self.assertNotEqual(records[0][4], records[1][4])
        self.assertEqual({"characters": 3}, report["categories"])

    def test_alternating_question_rows_pair_the_next_row_as_the_answer(self):
        category = self.config["categories"][0]
        category.update({
            "first_row": 4,
            "last_row": 7,
            "row_stride": 2,
            "image_column": None,
            "strip_title_prefixes": ["Q:", "Q："],
        })
        category["fields"] = [{
            "key": "answer", "label": "解答", "column": 3, "row_offset": 1, "strip_prefixes": ["A:", "A："],
        }]
        self.table["cells"] = [
            {"row": 2, "column": 3, "type": "string", "value": "角色名称"},
            {"row": 4, "column": 3, "type": "string", "value": "Q:问题一"},
            {"row": 5, "column": 3, "type": "string", "value": "A:答案一"},
            {"row": 6, "column": 3, "type": "string", "value": "Q：问题二"},
            {"row": 7, "column": 3, "type": "string", "value": "A：答案二"},
        ]
        self._write_json(self.adapters, self.config)
        self._save_table()

        report = self._build()
        with closing(sqlite3.connect(self.output)) as database:
            records = database.execute(
                "SELECT entry.title,details.answer,entry.source_row "
                "FROM characters_details details JOIN wiki_entries entry ON entry.id=details.entry_id "
                "ORDER BY entry.source_row"
            ).fetchall()

        self.assertEqual([("问题一", "答案一", 4), ("问题二", "答案二", 6)], records)
        self.assertEqual({"characters": 2}, report["categories"])

    def test_source_row_title_mapping_is_explicit_and_revision_locked(self):
        category = self.config["categories"][0]
        category["row_titles"] = {"4": "指定条目名称"}
        category["expected_source_revision"] = 9
        self._write_json(self.adapters, self.config)

        self._build()
        previous = self.output.read_bytes()
        with closing(sqlite3.connect(self.output)) as database:
            self.assertEqual(("指定条目名称", 4), database.execute(
                "SELECT title,source_row FROM wiki_entries WHERE source_row=4"
            ).fetchone())

        category["expected_source_revision"] = 10
        self._write_json(self.adapters, self.config)
        with self.assertRaisesRegex(ContentDatabaseError, "来源版本不匹配"):
            self._build()
        self.assertEqual(previous, self.output.read_bytes())

    def test_unknown_section_marker_rejects_the_database_build(self):
        category = self.config["categories"][0]
        category.update({
            "first_row": 3,
            "section_markers": {"column": 1, "values": {"凯伊篇": "kai"}},
        })
        category["fields"].append({"key": "route", "label": "路线范围", "column": 1, "source": "section"})
        self.table["cells"] = [
            {"row": 2, "column": 3, "type": "string", "value": "角色名称"},
            {"row": 3, "column": 1, "type": "string", "value": "未知篇"},
            {"row": 4, "column": 3, "type": "string", "value": "测试条目"},
        ]
        self._write_json(self.adapters, self.config)
        self._save_table()

        with self.assertRaisesRegex(ContentDatabaseError, "分组标记未配置"):
            self._build()

    def test_byte_repeatable_database_and_checksum(self):
        self._build()
        previous = self.output.read_bytes()
        previous_report = self.output.with_suffix(".report.json").read_bytes()
        self._build()
        self.assertEqual(previous, self.output.read_bytes())
        self.assertEqual(previous_report, self.output.with_suffix(".report.json").read_bytes())
        self.assertEqual(self._hash(self.output), self.output.with_suffix(".sha256").read_text().strip())

    def test_ids_do_not_depend_on_source_row_and_no_ambiguous_name_fallback(self):
        self._build()
        with closing(sqlite3.connect(self.output)) as database:
            identities = dict(database.execute("SELECT title,id FROM wiki_entries"))
        for cell in self.table["cells"]:
            if cell["row"] >= 4:
                cell["row"] += 8
        self._save_table()
        self._build()
        with closing(sqlite3.connect(self.output)) as database:
            self.assertEqual(identities, dict(database.execute("SELECT title,id FROM wiki_entries")))
        self.table["cells"].append({"row": 20, "column": 3, "type": "string", "value": "测试勇者"})
        self._save_table()
        with self.assertRaisesRegex(ContentDatabaseError, "身份冲突"):
            self._build()

    def test_merged_group_labels_are_filled_without_overwriting_real_values(self):
        self.table["metadata"]["merges"] = [{"gridRangeData": {
            "startRowIndex": 3, "endRowIndex": 4, "startColIndex": 3, "endColIndex": 3,
        }}]
        self._save_table()
        self._build()
        with closing(sqlite3.connect(self.output)) as database:
            self.assertEqual([("测试军团",), ("测试军团",)], database.execute(
                "SELECT affiliation FROM characters_details ORDER BY entry_id"
            ).fetchall())

    def test_changed_header_does_not_publish_or_destroy_previous_database(self):
        self._build()
        previous = self.output.read_bytes()
        self.table["cells"][0]["value"] = "不可识别的新表头"
        self._save_table()
        with self.assertRaisesRegex(ContentDatabaseError, "表头"):
            self._build()
        self.assertEqual(previous, self.output.read_bytes())
        self.assertEqual([], list(self.output.parent.glob(".content-*")))

    def test_corrupt_image_and_source_table_are_rejected(self):
        image_path = self.package / "assets/icon.webp"
        image_bytes = image_path.read_bytes()
        image_path.write_bytes(b"corrupt")
        with self.assertRaisesRegex(ContentDatabaseError, "图片"):
            self._build()
        image_path.write_bytes(image_bytes)
        (self.package / "tables/demo1.json").write_bytes(b"corrupt")
        with self.assertRaisesRegex(ContentDatabaseError, "来源表校验"):
            self._build()

    def test_image_path_traversal_is_rejected(self):
        self.table["cells"][1]["image_reference"]["local_file"] = "../private.png"
        self._save_table()
        with self.assertRaisesRegex(ContentDatabaseError, "路径"):
            self._build()

    def test_output_cannot_be_inside_source_or_replace_unowned_database(self):
        with self.assertRaises(ContentDatabaseError):
            build_content_database(self.package, self.package / "content.db", self.adapters)
        self.output.parent.mkdir()
        with closing(sqlite3.connect(self.output)) as database:
            database.execute("CREATE TABLE personal_record(id TEXT)")
        with self.assertRaisesRegex(ContentDatabaseError, "拒绝替换"):
            self._build()
        with closing(sqlite3.connect(self.output)) as database:
            self.assertEqual("personal_record", database.execute("SELECT name FROM sqlite_master").fetchone()[0])

    def test_sql_query_uses_literal_substrings_and_full_pagination(self):
        self._build()
        with closing(sqlite3.connect(self.output)) as database:
            self.assertEqual(1, database.execute(
                "SELECT COUNT(*) FROM wiki_entries WHERE instr(lower(search_text),lower(?))>0", ("%_",)
            ).fetchone()[0])
            first = database.execute("SELECT id FROM wiki_entries ORDER BY title,id LIMIT ? OFFSET ?", (1, 0)).fetchall()
            last = database.execute("SELECT id FROM wiki_entries ORDER BY title,id LIMIT ? OFFSET ?", (1, 1)).fetchall()
            self.assertEqual(2, len({*first, *last}))
            self.assertEqual([], database.execute(
                "SELECT id FROM wiki_entries WHERE instr(search_text,?)>0", ("' OR 1=1 --",)
            ).fetchall())

    def test_bird_dialogues_keep_exact_character_links_review_state_and_row_independent_ids(self):
        headers = ["分类", "角色中文名", "角色英文名", "台词（Prompt）", "中文台词",
                   "正确反应（Reaction）", "反应", "备注"]
        bird_cells = [
            {"row": 1, "column": column, "type": "string", "value": value}
            for column, value in enumerate(headers, start=1)
        ]
        bird_cells.extend([
            {"row": 2, "column": 1, "type": "string", "value": "说明：来源资料经交叉核对"},
            {"row": 2, "column": 8, "type": "string", "value": "校订打✅"},
            {"row": 3, "column": 1, "type": "string", "value": "主角"},
            {"row": 3, "column": 2, "type": "string", "value": "测试勇者"},
            {"row": 3, "column": 3, "type": "string", "value": "Test Hero"},
            {"row": 3, "column": 4, "type": "string", "value": "How are you?"},
            {"row": 3, "column": 5, "type": "string", "value": "你好吗？"},
            {"row": 3, "column": 6, "type": "string", "value": "Nod"},
            {"row": 3, "column": 7, "type": "string", "value": "点头"},
            {"row": 3, "column": 8, "type": "string", "value": "✅☑️"},
            {"row": 4, "column": 2, "type": "string", "value": "未收录角色"},
            {"row": 4, "column": 5, "type": "string", "value": "你觉得呢？"},
            {"row": 4, "column": 7, "type": "string", "value": "凝视"},
            {"row": 5, "column": 2, "type": "string", "value": "盖茨（待补充）"},
        ])
        table_path = self.package / "tables/birds.json"
        bird_table = {"schema_version": 1, "metadata": {
            "sheet_id": "birds", "name": "测试逗鸟表", "revision": 9, "merges": [],
        }, "cells": bird_cells}
        self._write_json(table_path, bird_table)
        manifest = self._read_json(self.package / "manifest.json")
        manifest["sheets"].append({
            "sheet_id": "birds", "name": "测试逗鸟表", "status": "available",
            "output_file": "tables/birds.json", "output_sha256": self._hash(table_path),
            "stored_cell_count": len(bird_cells),
        })
        self._write_json(self.package / "manifest.json", manifest)
        self.config["categories"].append({
            "id": "bird-dialogues", "title": "逗鸟问答", "description": "角色对话",
            "sheet": "birds", "header_row": 1, "header": "中文台词", "first_row": 3,
            "title_column": 5, "image_column": None, "subtitle_columns": [2, 7],
            "identity_columns": [2, 4],
            "fields": [
                {"key": "character_group", "label": "角色范围", "column": 1},
                {"key": "character_name", "label": "角色", "column": 2, "references": ["characters"]},
                {"key": "character_name_english", "label": "角色英文名", "column": 3},
                {"key": "original_prompt", "label": "原文对话", "column": 4},
                {"key": "translated_prompt", "label": "中文对话", "column": 5},
                {"key": "original_reaction", "label": "原文反应", "column": 6},
                {"key": "translated_reaction", "label": "推荐反应", "column": 7},
                {"key": "correction_marker", "label": "校订标记", "column": 8},
            ],
        })
        self.config["bird_dialogues"] = {
            "sheet": "birds", "category_id": "bird-dialogues", "header_row": 1, "first_row": 3,
            "character_groups": ["主角", "可招募角色"],
            "headers": [{"column": column, "value": value} for column, value in enumerate(headers, start=1)],
            "columns": {
                "character_group": 1, "character_name": 2, "character_name_english": 3,
                "original_prompt": 4, "translated_prompt": 5, "original_reaction": 6,
                "translated_reaction": 7, "correction_marker": 8,
            },
            "corrected_marker": "✅", "source_note_row": 2, "source_note_column": 1,
        }
        self._write_json(self.adapters, self.config)

        report = self._build()
        with closing(sqlite3.connect(self.output)) as database:
            self.assertEqual(SCHEMA_VERSION, database.execute("PRAGMA user_version").fetchone()[0])
            marked, unmarked = database.execute(
                "SELECT SUM(marked_corrected),COUNT(*)-SUM(marked_corrected) FROM bird_dialogues"
            ).fetchone()
            self.assertEqual((1, 1), (marked, unmarked))
            entry_id = database.execute(
                "SELECT entry_id FROM bird_dialogues WHERE translated_prompt='你好吗？'"
            ).fetchone()[0]
            linked_character = database.execute(
                "SELECT character_entry_id,marked_corrected FROM bird_dialogues WHERE entry_id=?", (entry_id,),
            ).fetchone()
            self.assertIsNotNone(linked_character[0])
            self.assertEqual(1, linked_character[1])
            optional_fields = database.execute(
                "SELECT character_group,character_name_english,original_prompt,original_reaction "
                "FROM bird_dialogues WHERE translated_prompt='你觉得呢？'"
            ).fetchone()
            self.assertEqual((None, None, None, None), optional_fields)
            self.assertEqual(1, database.execute(
                "SELECT COUNT(*) FROM wiki_relations WHERE source_id=? AND label='角色'", (entry_id,),
            ).fetchone()[0])
            self.assertEqual("说明：来源资料经交叉核对", database.execute(
                "SELECT value FROM content_metadata WHERE key='bird_dialogues_source_note'"
            ).fetchone()[0])

        self.assertEqual(2, report["bird_dialogues"]["records"])
        self.assertEqual(1, report["bird_dialogues"]["marked_corrected"])
        self.assertEqual(1, report["bird_dialogues"]["linked_characters"])
        self.assertTrue(any(gap["character"] == "未收录角色" and gap["reason"] == "character_not_found"
                            for gap in report["bird_dialogues"]["gaps"]))
        self.assertTrue(any(gap["source_row"] == 4 and gap["reason"] == "missing_character_group"
                            for gap in report["bird_dialogues"]["gaps"]))
        self.assertTrue(any(gap["source_row"] == 4 and gap.get("field") == "original_prompt"
                            and gap["reason"] == "missing_optional_source_text"
                            for gap in report["bird_dialogues"]["gaps"]))
        self.assertTrue(any(gap["source_row"] == 5 and gap["reason"] == "source_row_placeholder"
                            for gap in report["bird_dialogues"]["gaps"]))

        with closing(sqlite3.connect(self.output)) as database:
            original_ids = dict(database.execute(
                "SELECT title,id FROM wiki_entries WHERE category_id='bird-dialogues'"
            ).fetchall())
        bird_table["cells"] = [
            {**cell, "row": cell["row"] + 10} if cell["row"] >= 3 else cell for cell in bird_cells
        ]
        self._write_json(table_path, bird_table)
        manifest = self._read_json(self.package / "manifest.json")
        for source_sheet in manifest["sheets"]:
            if source_sheet["sheet_id"] == "birds":
                source_sheet["output_sha256"] = self._hash(table_path)
                source_sheet["stored_cell_count"] = len(bird_table["cells"])
        self._write_json(self.package / "manifest.json", manifest)
        self._build()
        with closing(sqlite3.connect(self.output)) as database:
            self.assertEqual(original_ids, dict(database.execute(
                "SELECT title,id FROM wiki_entries WHERE category_id='bird-dialogues'"
            ).fetchall()))
            self.assertEqual(13, database.execute(
                "SELECT source_row FROM wiki_entries WHERE category_id='bird-dialogues' AND title='你好吗？'"
            ).fetchone()[0])

        previous_database = self.output.read_bytes()
        bird_table["cells"] = [
            {**cell, "value": "意外表头"}
            if cell["row"] == 1 and cell["column"] == 4 else cell
            for cell in bird_table["cells"]
        ]
        self._write_json(table_path, bird_table)
        manifest = self._read_json(self.package / "manifest.json")
        for source_sheet in manifest["sheets"]:
            if source_sheet["sheet_id"] == "birds":
                source_sheet["output_sha256"] = self._hash(table_path)
        self._write_json(self.package / "manifest.json", manifest)
        with self.assertRaisesRegex(ContentDatabaseError, "逗鸟对话表头不匹配"):
            self._build()
        self.assertEqual(previous_database, self.output.read_bytes())

    def test_supports_project_pairs_dialogues_types_and_exact_links(self):
        source_data = self._support_source()
        support_path = self.root / "supports.json"
        self._write_json(support_path, source_data)

        report = build_content_database(
            self.package, self.output, self.adapters, {"supports": support_path},
        )

        with closing(sqlite3.connect(self.output)) as database:
            self.assertEqual(SCHEMA_VERSION, database.execute("PRAGMA user_version").fetchone()[0])
            people = database.execute(
                "SELECT source_name,character_entry_id,match_status FROM support_people ORDER BY source_name",
            ).fetchall()
            self.assertEqual(2, len(people))
            self.assertTrue(all(row[1] and row[2] == "matched" for row in people))
            self.assertEqual((1, 9), database.execute(
                "SELECT initial_level,attack_speed FROM support_pairs",
            ).fetchone())
            self.assertEqual((1, 1, 2, "角色甲已加入"), database.execute(
                "SELECT rank,start_part,route_id,json_extract(conditions_json,'$[0]') FROM support_dialogues",
            ).fetchone())
            self.assertEqual((0, 0, 0, 0, 0, 0, 0, 0, 0), database.execute(
                "SELECT physical_attack,magic_attack,physical_defense,magic_defense,hit,avoid,critical,"
                "critical_avoid,attack_speed FROM support_type_bonuses WHERE support_type='無し' AND support_level=5",
            ).fetchone())
            self.assertEqual("missing", database.execute(
                "SELECT match_status FROM support_jobs WHERE name='测试职业'",
            ).fetchone()[0])
            self.assertEqual(5, database.execute(
                "SELECT COUNT(*) FROM support_type_bonuses WHERE support_type='連携'",
            ).fetchone()[0])
            self.assertEqual([], database.execute("PRAGMA foreign_key_check").fetchall())

        self.assertEqual({
            "available": True,
            "source_uri": source_data["source"]["uri"],
            "source_revision": source_data["source"]["revision"],
            "people": 2,
            "linked_people": 2,
            "jobs": 1,
            "linked_jobs": 0,
            "pairs": 1,
            "pairs_with_bonus": 1,
            "pairs_with_dialogues": 1,
            "dialogue_conditions": 1,
            "support_types": 8,
            "gaps": [{"kind": "job", "name": "测试职业", "source_job_id": "JID_测试职业", "reason": "missing"}],
            "tencent_crosscheck": {"available": False, "reason": "sheet_unavailable", "sheet_id": "tvt8ng"},
        }, report["supports"])
        self.assertEqual(2, report["external_sources"]["supports"]["records"])

    def test_incomplete_support_pairs_do_not_replace_the_previous_database(self):
        source_data = self._support_source()
        support_path = self.root / "supports.json"
        self._write_json(support_path, source_data)
        build_content_database(self.package, self.output, self.adapters, {"supports": support_path})
        previous_database = self.output.read_bytes()

        source_data["pairs"] = []
        self._write_json(support_path, source_data)
        with self.assertRaisesRegex(ContentDatabaseError, "不完整"):
            build_content_database(self.package, self.output, self.adapters, {"supports": support_path})
        self.assertEqual(previous_database, self.output.read_bytes())

    def test_support_pair_ids_are_independent_of_source_order_and_indexes(self):
        first_source = self._support_source()
        first_path = self.root / "supports-first.json"
        self._write_json(first_path, first_source)
        build_content_database(self.package, self.output, self.adapters, {"supports": first_path})
        with closing(sqlite3.connect(self.output)) as database:
            first_pair_id = database.execute("SELECT pair_id FROM support_pairs").fetchone()[0]

        second_source = json.loads(json.dumps(first_source, ensure_ascii=False))
        second_source["people"].reverse()
        pair = second_source["pairs"][0]
        pair["source_first_index"], pair["source_second_index"] = 1, 0
        second_path = self.root / "supports-second.json"
        self._write_json(second_path, second_source)
        second_output = self.root / "generated/second.db"
        build_content_database(self.package, second_output, self.adapters, {"supports": second_path})
        with closing(sqlite3.connect(second_output)) as database:
            second_pair_id = database.execute("SELECT pair_id FROM support_pairs").fetchone()[0]
        self.assertEqual(first_pair_id, second_pair_id)

    def test_support_character_links_keep_missing_and_ambiguous_gaps(self):
        self.config["categories"][0]["identity_columns"] = [4]
        self._write_json(self.adapters, self.config)
        self.table["cells"].extend([
            {"row": 6, "column": 3, "type": "string", "value": "另一名角色"},
            {"row": 6, "column": 4, "type": "string", "value": "第二种"},
        ])
        self._save_table()
        source_data = self._support_source()
        source_data["people"][0]["name"] = "未匹配角色"
        source_data["pairs"][0]["first_name"] = "未匹配角色"
        support_path = self.root / "supports.json"
        self._write_json(support_path, source_data)

        report = build_content_database(self.package, self.output, self.adapters, {"supports": support_path})

        with closing(sqlite3.connect(self.output)) as database:
            people = dict(database.execute("SELECT source_name,match_status FROM support_people"))
            self.assertEqual({"另一名角色": "ambiguous", "未匹配角色": "missing"}, people)
            self.assertEqual(2, database.execute(
                "SELECT COUNT(*) FROM support_people WHERE character_entry_id IS NULL",
            ).fetchone()[0])
        character_gaps = {
            (gap["name"], gap["reason"])
            for gap in report["supports"]["gaps"]
            if gap["kind"] == "character"
        }
        self.assertEqual({("另一名角色", "ambiguous"), ("未匹配角色", "missing")}, character_gaps)

    def test_support_crosscheck_compares_tvt8ng_and_reports_differences(self):
        source_data = self._support_source()
        support_path = self.root / "supports.json"
        self._write_json(support_path, source_data)
        support_types = {item["id"]: item for item in source_data["support_types"]}
        cells = []
        for group_index, (group_name, type_id, first_row) in enumerate(SUPPORT_TENCENT_GROUPS):
            cells.extend([
                {"row": first_row, "column": 14, "type": "string", "value": group_name},
                {"row": first_row, "column": 15, "type": "string",
                 "value": "不存在的职业" if group_index == 0 else ""},
            ])
            for row_offset, level in enumerate(range(2, 6)):
                row = first_row + row_offset
                cells.append({"row": row, "column": 16, "type": "string", "value": str(level)})
                for stat_index, column in enumerate(range(17, 25)):
                    value = support_types[type_id]["levels"][level - 1][stat_index]
                    if group_index == 0 and level == 2 and stat_index == 0:
                        value += 5
                    cells.append({"row": row, "column": column, "type": "string", "value": str(value)})

        table_path = self.package / "tables/tvt8ng.json"
        self._write_json(table_path, {
            "schema_version": 1,
            "metadata": {"sheet_id": "tvt8ng", "name": "角色、兵种支援效果", "revision": 9},
            "cells": cells,
        })
        manifest = self._read_json(self.package / "manifest.json")
        manifest["sheets"].append({
            "sheet_id": "tvt8ng",
            "name": "角色、兵种支援效果",
            "status": "available",
            "output_file": "tables/tvt8ng.json",
            "output_sha256": self._hash(table_path),
            "stored_cell_count": len(cells),
        })
        self._write_json(self.package / "manifest.json", manifest)

        report = build_content_database(self.package, self.output, self.adapters, {"supports": support_path})

        crosscheck = report["supports"]["tencent_crosscheck"]
        self.assertTrue(crosscheck["available"])
        self.assertEqual(7, crosscheck["groups_compared"])
        self.assertEqual(224, crosscheck["values_compared"])
        self.assertEqual(1, len(crosscheck["differences"]))
        self.assertEqual((2, 7), (
            crosscheck["differences"][0]["support_page_value"],
            crosscheck["differences"][0]["tencent_sheet_value"],
        ))
        self.assertEqual([{
            "group": SUPPORT_TENCENT_GROUPS[0][0],
            "name": "不存在的职业",
            "reason": "not_found",
        }], crosscheck["unmatched_jobs"])

    def _support_source(self):
        page_hash = "a" * 64
        bundle_hash = "b" * 64
        revision = hashlib.sha256((page_hash + bundle_hash).encode("ascii")).hexdigest()
        types = ["援護", "攻勢", "連携", "防衛", "魔道", "騎兵", "飛兵"]
        support_types = [{
            "id": type_id,
            "label": label,
            "levels": [[level] * 9 for level in range(1, 6)],
        } for type_id, label in zip(types, ["援护", "攻势", "连携", "防卫", "魔道", "骑兵", "飞兵"])]
        support_types.append({"id": "無し", "label": "无", "levels": [[0] * 9 for _ in range(5)]})
        return {
            "schema_version": 1,
            "source": {
                "uri": "https://fireemblem.top/archive/supports",
                "bundle_uri": "https://fireemblem.top/_next/static/chunks/5657-test.js",
                "page_sha256": page_hash,
                "bundle_sha256": bundle_hash,
                "revision": revision,
            },
            "stat_names": ["物攻", "魔攻", "物防", "魔防", "命中", "回避", "必杀", "必杀回避", "攻速"],
            "thresholds": [0, 400, 1400, 2400, 3200],
            "people": [
                {"name": "测试勇者", "display_name": "测试勇者"},
                {"name": "另一名角色", "display_name": "另一名角色"},
            ],
            "jobs": [{
                "source_id": "JID_测试职业", "name": "测试职业", "display_name": "测试职业",
                "support_type": "連携", "support_type_label": "连携", "tiers": ["初级"],
            }],
            "support_types": support_types,
            "pairs": [{
                "first_name": "测试勇者", "second_name": "另一名角色",
                "source_first_index": 0, "source_second_index": 1,
                "affinity": 1, "initial_level": 1, "combination_bonus": list(range(1, 10)),
                "talks": [{
                    "rank": 1, "start": [1, 1], "end": [0, 0], "route": 2,
                    "any_joined": ["测试勇者"], "conditions": ["角色甲已加入"],
                    "pending": 0, "date_pending": False,
                }],
            }],
        }

    def test_mechanics_project_groups_entries_and_growth_sections(self):
        mechanics_data = {
            "schema_version": 1,
            "source": {
                "archive_uri": "https://fireemblem.top/archive/mechanics",
                "growth_uri": "https://fireemblem.top/topics/growth-mechanics",
                "archive_sha256": "a" * 64,
                "growth_sha256": "b" * 64,
                "revision": "c" * 64,
            },
            "groups": [{
                "id": "combat", "title": "战斗基础", "category": "战斗与养成",
                "description": "攻击与命中。", "href": "/database/skills", "count": 1,
            }],
            "entries": [{
                "id": "M_TEST", "title": "测试机制", "group": "combat",
                "kind": "系统说明", "paragraphs": ["测试正文"], "sources": [],
            }],
            "growth_sections": [{
                "id": "fixed", "title": "04固定成长",
                "blocks": [{"kind": "paragraph", "text": "初始值来自 3 倍个人成长取余。"}],
            }],
        }
        mechanics_path = self.root / "mechanics.json"
        self._write_json(mechanics_path, mechanics_data)

        report = build_content_database(
            self.package, self.output, self.adapters, {"mechanics": mechanics_path},
        )
        with closing(sqlite3.connect(self.output)) as database:
            self.assertEqual(SCHEMA_VERSION, database.execute("PRAGMA user_version").fetchone()[0])
            self.assertEqual(1, database.execute("SELECT COUNT(*) FROM mechanics_groups").fetchone()[0])
            self.assertEqual(1, database.execute("SELECT COUNT(*) FROM mechanics_entries").fetchone()[0])
            self.assertEqual(1, database.execute("SELECT COUNT(*) FROM growth_mechanics_sections").fetchone()[0])
            self.assertEqual([], database.execute("PRAGMA foreign_key_check").fetchall())
        self.assertEqual(1, report["mechanics"]["groups"])
        self.assertEqual(1, report["mechanics"]["entries"])
        self.assertEqual(1, report["mechanics"]["growth_sections"])

    def test_growth_simulator_projects_characters_classes_and_mounts(self):
        gs_data = {
            "schema_version": 1,
            "source": {
                "uri": "https://fireemblem.top/tools/growth-simulator",
                "page_sha256": "d" * 64,
                "revision": "d" * 64,
            },
            "stat_names": ["HP", "力量", "魔力", "技巧", "速度", "幸运", "守备", "魔防", "魅力"],
            "characters": [{
                "native_id": "PID_カイ", "code": "0", "record_id": 1, "name": "凯伊",
                "faction": "测试", "gender": "男", "start_class": "平民", "start_level": 1,
                "base": [27, 10, 7, 8, 8, 6, 5, 6, 5],
                "growth": [45, 45, 40, 45, 45, 40, 35, 35, 40],
                "caps": [99] * 9, "guarantee_pre": 2, "guarantee_part3": 0,
            }],
            "classes": [{
                "id": "JID_無職", "record_id": 1, "name": "平民", "tier": "基础",
                "base": [20] * 9, "growth_bonus": [0] * 9, "base_growth": [40] * 9,
                "modifier": [0] * 9, "caps": [99] * 9, "mount_type": "", "mount_multiplier": 1,
                "allowed_records": [], "forbidden_people": [],
            }],
            "mounts": [{
                "id": "RID_TEST", "type": "马", "name": "测试马",
                "growth": [5, 0, 0, 0, 0, 0, 0, 0, 0], "attr_bonus": [0] * 9,
                "effect": "", "location": "本地",
            }],
        }
        gs_path = self.root / "growth-simulator.json"
        self._write_json(gs_path, gs_data)

        report = build_content_database(
            self.package, self.output, self.adapters, {"growth-simulator": gs_path},
        )
        with closing(sqlite3.connect(self.output)) as database:
            self.assertEqual(SCHEMA_VERSION, database.execute("PRAGMA user_version").fetchone()[0])
            self.assertEqual(1, database.execute("SELECT COUNT(*) FROM growth_sim_characters").fetchone()[0])
            self.assertEqual(1, database.execute("SELECT COUNT(*) FROM growth_sim_classes").fetchone()[0])
            self.assertEqual(1, database.execute("SELECT COUNT(*) FROM growth_sim_mounts").fetchone()[0])
            self.assertEqual([], database.execute("PRAGMA foreign_key_check").fetchall())
        self.assertEqual(1, report["growth_simulator"]["characters"])
        self.assertEqual(1, report["growth_simulator"]["classes"])
        self.assertEqual(1, report["growth_simulator"]["mounts"])

    def test_character_affiliations_forward_fill_only_numbered_entries(self):
        self.config["categories"] = [{
            "id": "characters",
            "title": "角色",
            "description": "测试势力继承",
            "sheet": "demo1",
            "header_row": 2,
            "header": "角色名称",
            "first_row": 4,
            "title_column": 4,
            "image_column": None,
            "subtitle_columns": [2],
            "identity_columns": [],
            "fields": [{"key": "affiliation", "label": "所属势力", "column": 2, "numeric": False}],
        }]
        self._write_json(self.adapters, self.config)
        self.table["cells"] = [
            {"row": 2, "column": 2, "type": "string", "value": "所属势力"},
            {"row": 2, "column": 4, "type": "string", "value": "角色名称"},
            {"row": 4, "column": 1, "type": "string", "value": "#01"},
            {"row": 4, "column": 2, "type": "string", "value": "美盖拉的\n灯火"},
            {"row": 4, "column": 4, "type": "string", "value": "赛奥朵拉"},
            {"row": 5, "column": 1, "type": "string", "value": "#02"},
            {"row": 5, "column": 4, "type": "string", "value": "托比亚斯"},
            {"row": 6, "column": 4, "type": "string", "value": "剧情NPC"},
        ]
        self._save_table()

        build_content_database(self.package, self.output, self.adapters)
        with closing(sqlite3.connect(self.output)) as database:
            rows = database.execute(
                "SELECT e.title, cd.affiliation FROM wiki_entries e "
                "JOIN characters_details cd ON cd.entry_id=e.id ORDER BY e.source_row"
            ).fetchall()
            self.assertEqual(
                [("赛奥朵拉", "美盖拉的灯火"), ("托比亚斯", "美盖拉的灯火"), ("剧情NPC", None)],
                rows,
            )

    @staticmethod
    def _write_json(path, value):
        path.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")

    @staticmethod
    def _read_json(path):
        return json.loads(path.read_text(encoding="utf-8"))

    @staticmethod
    def _entry_id(database, category, title):
        return database.execute("SELECT id FROM wiki_entries WHERE category_id=? AND title=?", (category, title)).fetchone()[0]

    @staticmethod
    def _hash(path):
        return hashlib.sha256(path.read_bytes()).hexdigest()


if __name__ == "__main__":
    unittest.main()
