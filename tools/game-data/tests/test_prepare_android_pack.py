from contextlib import closing
from pathlib import Path
import sqlite3
import sys
import tempfile
import unittest


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from prepare_android_pack import APPLICATION_ID, SCHEMA_VERSION, prepare_single_game_pack


class PrepareAndroidPackTests(unittest.TestCase):
    def test_strips_workbook_cells_creates_indexes_and_passes_checks(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            src_db = root / "src.db"
            with closing(sqlite3.connect(src_db)) as conn:
                conn.executescript(f"""
                PRAGMA application_id={APPLICATION_ID};
                PRAGMA user_version={SCHEMA_VERSION};
                CREATE TABLE content_metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL);
                CREATE TABLE categories(id TEXT PRIMARY KEY, title TEXT NOT NULL, description TEXT NOT NULL,
                 position INTEGER NOT NULL, applicable INTEGER NOT NULL, coverage_status TEXT NOT NULL,
                 source_count INTEGER NOT NULL, record_count INTEGER NOT NULL);
                CREATE TABLE content_entries(id TEXT PRIMARY KEY, category_id TEXT NOT NULL, stable_key TEXT NOT NULL,
                 title TEXT NOT NULL, title_ja TEXT, title_en TEXT, subtitle TEXT NOT NULL, description TEXT NOT NULL,
                 search_text TEXT NOT NULL, verification_status TEXT NOT NULL);
                CREATE TABLE content_relations(source_entry_id TEXT NOT NULL, target_entry_id TEXT NOT NULL,
                 relation_type TEXT NOT NULL, evidence TEXT NOT NULL, verification_status TEXT NOT NULL,
                 source_id TEXT NOT NULL, locator TEXT NOT NULL);
                CREATE TABLE content_assets(asset_id TEXT PRIMARY KEY, app_file TEXT);
                CREATE TABLE entry_assets(entry_id TEXT NOT NULL, asset_id TEXT NOT NULL, role TEXT NOT NULL, locator TEXT);
                CREATE TABLE entry_sources(entry_id TEXT NOT NULL, source_id TEXT NOT NULL);
                CREATE TABLE workbook_sheets(sheet_id TEXT PRIMARY KEY);
                CREATE TABLE workbook_cells(cell_id TEXT PRIMARY KEY);
                CREATE TABLE workbook_image_placements(id TEXT PRIMARY KEY);
                CREATE TABLE image_ocr_results(asset_id TEXT PRIMARY KEY);
                INSERT INTO categories VALUES('characters', '角色', '角色列表', 0, 1, 'partial', 1, 1);
                INSERT INTO content_entries VALUES('characters:1', 'characters', '1', '琉尔', NULL, 'Alear', '神龙', '{{}}', '琉尔 神龙', 'source_only');
                INSERT INTO workbook_sheets VALUES('sheet1');
                INSERT INTO workbook_cells VALUES('cell1');
                """)
            out_dir = root / "out" / "engage"
            summary = prepare_single_game_pack("engage", src_db, None, out_dir)
            self.assertEqual(1, summary["entries"])
            with closing(sqlite3.connect(out_dir / "pack.db")) as conn:
                self.assertEqual(0, conn.execute("SELECT COUNT(*) FROM workbook_cells").fetchone()[0])
                self.assertEqual(0, conn.execute("SELECT COUNT(*) FROM workbook_sheets").fetchone()[0])
                self.assertEqual(("ok",), conn.execute("PRAGMA quick_check").fetchone())

    def test_real_game_packs_strip_cells_and_compress_cleanly(self):
        repo_root = Path(__file__).resolve().parents[3]
        games_root = repo_root / "data" / "games"
        if not (games_root / "engage" / "database" / "content.db").is_file():
            self.skipTest("Local engage database not present")
        with tempfile.TemporaryDirectory() as tmp:
            out_dir = Path(tmp) / "games" / "three-houses"
            summary = prepare_single_game_pack(
                "three-houses",
                games_root / "three-houses" / "database" / "content.db",
                games_root / "three-houses",
                out_dir,
            )
            self.assertGreater(summary["entries"], 1000)
            self.assertGreater(summary["relations"], 1000)
            self.assertGreater(summary["assets_count"], 80)
            with closing(sqlite3.connect(out_dir / "pack.db")) as conn:
                self.assertEqual(0, conn.execute("SELECT COUNT(*) FROM workbook_cells").fetchone()[0])
                char_total = conn.execute("SELECT COUNT(*) FROM content_entries WHERE category_id='characters'").fetchone()[0]
                char_with_img = conn.execute(
                    "SELECT COUNT(DISTINCT e.id) FROM content_entries e JOIN entry_assets ea ON ea.entry_id=e.id WHERE e.category_id='characters'"
                ).fetchone()[0]
                self.assertEqual(char_total, char_with_img)
                self.assertEqual(0, conn.execute(
                    "SELECT COUNT(*) FROM content_entries WHERE category_id='characters' AND title IN ('Anna', 'Flayn')"
                ).fetchone()[0])
                self.assertEqual(char_total, conn.execute(
                    "SELECT COUNT(*) FROM character_details cd JOIN content_entries e ON e.id=cd.entry_id "
                    "WHERE e.category_id='characters' AND cd.base_stats_json IS NOT NULL AND cd.growths_json IS NOT NULL"
                ).fetchone()[0])
                self.assertEqual(char_total, conn.execute(
                    "SELECT COUNT(*) FROM content_entries WHERE category_id='characters' AND description LIKE '%初始武器与道具%'"
                ).fetchone()[0])
                self.assertEqual(0, conn.execute(
                    "SELECT COUNT(*) FROM content_entries WHERE category_id='characters' AND description LIKE '%PersonalLink:%'"
                ).fetchone()[0])
                self.assertEqual(char_total, conn.execute(
                    "SELECT COUNT(*) FROM content_entries WHERE category_id='characters' AND description LIKE '%\"skill_tracks\"%'"
                ).fetchone()[0])
                houses = {
                    r[0] for r in conn.execute(
                        "SELECT DISTINCT cd.affiliation FROM character_details cd JOIN content_entries e ON e.id=cd.entry_id WHERE e.category_id='characters'"
                    ).fetchall()
                }
                self.assertTrue({"黑鹫学级", "青狮子学级", "金鹿学级", "灰狼学级", "赛罗司圣教会", "主角与独立"}.issubset(houses))
                self.assertEqual(0, conn.execute(
                    "SELECT COUNT(*) FROM content_entries WHERE category_id='classes' AND title NOT GLOB '*[一-龥]*'"
                ).fetchone()[0])
                self.assertEqual(0, conn.execute(
                    "SELECT COUNT(*) FROM content_entries WHERE category_id='chapters' AND title NOT GLOB '*[一-龥]*'"
                ).fetchone()[0])
                recruit_with_img = conn.execute(
                    "SELECT COUNT(DISTINCT e.id) FROM content_entries e JOIN entry_assets ea ON ea.entry_id=e.id WHERE e.category_id='recruitment'"
                ).fetchone()[0]
                self.assertEqual(char_total, recruit_with_img)
                cls_with_img = conn.execute(
                    "SELECT COUNT(DISTINCT e.id) FROM content_entries e JOIN entry_assets ea ON ea.entry_id=e.id WHERE e.category_id='classes'"
                ).fetchone()[0]
                self.assertGreaterEqual(cls_with_img, 60)
                # Ensure Three Houses skill_details has zero raw arrays/source_row dumps
                raw_skill_arrays = conn.execute(
                    "SELECT COUNT(*) FROM skill_details WHERE effect_json LIKE '%source_row%' OR effect_json LIKE '%source_sections%' OR effect_json LIKE '%[%'"
                ).fetchone()[0]
                self.assertEqual(0, raw_skill_arrays)
                # Ensure Three Houses activity_details & mechanic_details are cleanly localized (no snake_case types or English boilerplate)
                raw_act = conn.execute(
                    "SELECT COUNT(*) FROM activity_details WHERE instr(activity_type, '_') > 0 OR options_json LIKE '%source_reward_cells%' OR effects_json LIKE '%liked_by%'"
                ).fetchone()[0]
                self.assertEqual(0, raw_act)
                raw_mech = conn.execute(
                    "SELECT COUNT(*) FROM mechanic_details WHERE instr(mechanic_type, '_') > 0 OR rule_description LIKE 'Source-listed%' OR rule_description LIKE 'A named battalion%'"
                ).fetchone()[0]
                self.assertEqual(0, raw_mech)
                # Ensure redundant growth & battles categories are removed completely and zero boilerplate remains
                self.assertEqual(0, conn.execute(
                    "SELECT COUNT(*) FROM categories WHERE id IN ('growth', 'battles')"
                ).fetchone()[0])
                self.assertEqual(0, conn.execute(
                    "SELECT COUNT(*) FROM categories WHERE description LIKE '%收录%' OR description LIKE '%尚未覆盖%'"
                ).fetchone()[0])
                self.assertEqual(char_total, conn.execute(
                    "SELECT COUNT(*) FROM content_entries WHERE category_id='recruitment'"
                ).fetchone()[0])
                self.assertEqual(0, conn.execute(
                    "SELECT COUNT(*) FROM mechanic_details md JOIN content_entries e ON e.id=md.entry_id "
                    "WHERE e.category_id='recruitment' AND md.rule_description LIKE '%Exact join-condition%'"
                ).fetchone()[0])
                self.assertEqual(92, conn.execute(
                    "SELECT COUNT(*) FROM content_entries WHERE category_id='classes'"
                ).fetchone()[0])
                self.assertGreaterEqual(conn.execute(
                    "SELECT COUNT(*) FROM content_entries WHERE category_id='battalions'"
                ).fetchone()[0], 180)
                self.assertEqual(304, conn.execute(
                    "SELECT COUNT(*) FROM support_details WHERE dialogue_summary LIKE '%\"lines\"%'"
                ).fetchone()[0])

    def test_engage_propagates_bond_ring_portraits_across_ranks(self):
        repo_root = Path(__file__).resolve().parents[3]
        games_root = repo_root / "data" / "games"
        if not (games_root / "engage" / "database" / "content.db").is_file():
            self.skipTest("Local engage database not present")
        with tempfile.TemporaryDirectory() as tmp:
            out_dir = Path(tmp) / "games" / "engage"
            summary = prepare_single_game_pack(
                "engage",
                games_root / "engage" / "database" / "content.db",
                games_root / "engage",
                out_dir,
            )
            self.assertGreater(summary["assets_count"], 800)
            with closing(sqlite3.connect(out_dir / "pack.db")) as conn:
                bond_with_img = conn.execute(
                    "SELECT COUNT(DISTINCT e.id) FROM content_entries e JOIN entry_assets ea ON ea.entry_id=e.id WHERE e.category_id='bond-rings'"
                ).fetchone()[0]
                self.assertGreaterEqual(bond_with_img, 480)
                char_total = conn.execute("SELECT COUNT(*) FROM content_entries WHERE category_id='characters'").fetchone()[0]
                self.assertLessEqual(char_total, 45)
                regions = {
                    r[0] for r in conn.execute(
                        "SELECT DISTINCT cd.affiliation FROM character_details cd JOIN content_entries e ON e.id=cd.entry_id WHERE e.category_id='characters'"
                    ).fetchall()
                }
                self.assertTrue({"里特司", "费列聂", "布罗帝亚", "伊尔席翁", "索尔姆", "邪龙之章·其他"}.issubset(regions))
                with_wiki = conn.execute(
                    "SELECT COUNT(*) FROM content_entries WHERE category_id='characters' AND description LIKE '%\"skill_tracks\"%' AND description LIKE '%\"support_partners\"%'"
                ).fetchone()[0]
                self.assertEqual(char_total, with_wiki)
                self.assertEqual(0, conn.execute(
                    "SELECT COUNT(*) FROM content_entries WHERE category_id IN ('chapters','paralogues','dlc-chapters') AND title NOT GLOB '*[一-龥]*'"
                ).fetchone()[0])
                # Emblems consolidated to 20 illustrated entries with plain-text profile (no raw JSON dict!)
                emblem_total = conn.execute("SELECT COUNT(*) FROM content_entries WHERE category_id='emblems'").fetchone()[0]
                emblem_with_img = conn.execute(
                    "SELECT COUNT(DISTINCT e.id) FROM content_entries e JOIN entry_assets ea ON ea.entry_id=e.id WHERE e.category_id='emblems'"
                ).fetchone()[0]
                self.assertEqual(20, emblem_total)
                self.assertEqual(20, emblem_with_img)
                self.assertEqual(0, conn.execute(
                    "SELECT COUNT(*) FROM emblem_details WHERE profile LIKE '{%'"
                ).fetchone()[0])
                # Classes consolidated from 224 -> <= 75 unified classes
                class_total = conn.execute("SELECT COUNT(*) FROM content_entries WHERE category_id='classes'").fetchone()[0]
                self.assertLessEqual(class_total, 75)
                self.assertGreaterEqual(class_total, 55)
                # Country investment consolidated to 4 nations with ZERO workbook_image Switch screenshots
                nation_total = conn.execute("SELECT COUNT(*) FROM content_entries WHERE category_id='country-investment'").fetchone()[0]
                self.assertEqual(4, nation_total)
                self.assertEqual(0, conn.execute(
                    "SELECT COUNT(*) FROM entry_assets WHERE role IN ('workbook_image','community_skill_rating_chart','support_matrix_visual_candidate')"
                ).fetchone()[0])
                # Growth mechanics replaced with 3 readable Chinese guides (zero 列K / 100.0 junk)
                growth_total = conn.execute("SELECT COUNT(*) FROM content_entries WHERE category_id='growth-mechanics'").fetchone()[0]
                self.assertEqual(3, growth_total)
                self.assertEqual(0, conn.execute(
                    "SELECT COUNT(*) FROM content_entries WHERE category_id='growth-mechanics' AND (title LIKE '%.0' OR description LIKE '%列K%')"
                ).fetchone()[0])
                # Weapon proficiency consolidated to 8 entries & Paralogues to 21 entries
                self.assertEqual(8, conn.execute("SELECT COUNT(*) FROM content_entries WHERE category_id='weapon-proficiency'").fetchone()[0])
                self.assertEqual(21, conn.execute("SELECT COUNT(*) FROM content_entries WHERE category_id='paralogues'").fetchone()[0])
                # Zero Excel hyperlink JSON in chapter_details or raw JSON dicts in mechanic_details
                self.assertEqual(0, conn.execute("SELECT COUNT(*) FROM chapter_details WHERE objective LIKE '{%'").fetchone()[0])
                self.assertEqual(0, conn.execute("SELECT COUNT(*) FROM mechanic_details WHERE rule_description LIKE '{%' OR formula_text LIKE '{%'").fetchone()[0])
                self.assertEqual(0, conn.execute("SELECT COUNT(*) FROM content_entries WHERE description LIKE '%\"source_sheet\"%'").fetchone()[0])


if __name__ == "__main__":
    unittest.main()
