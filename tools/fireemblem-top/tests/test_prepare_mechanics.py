import json
import sys
import unittest
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from prepare_mechanics import EXPECTED_GROWTH_SECTION_IDS, extract_mechanics


class PrepareMechanicsTests(unittest.TestCase):
    def _sample_inputs(self):
        groups = [
            {
                "id": "combat",
                "title": "战斗基础",
                "category": "战斗与养成",
                "description": "攻击与命中机制。",
                "href": "/database/skills",
                "count": 2,
            }
        ]
        entries = [
            {
                "id": "M1",
                "title": "连携攻击",
                "group": "combat",
                "kind": "系统说明",
                "paragraphs": ["第一段。", "第二段。"],
                "sources": [],
            },
            {
                "id": "M2",
                "title": "追击速度",
                "group": "combat",
                "kind": "游戏提示",
                "paragraphs": ["攻速高出 4 点可追击。"],
                "sources": [],
            },
        ]
        stream = json.dumps({"groups": groups, "entries": entries}, ensure_ascii=False)
        archive_html = f"<script>self.__next_f.push([1,{json.dumps(stream, ensure_ascii=False)}])</script>"
        sections_html = "\n".join(
            f'<section id="{sec_id}"><h2>{sec_id} 标题</h2>'
            f'<h3>小节</h3><p>{sec_id} 正文段落</p>'
            f'<table><tr><th>列A</th><th>列B</th></tr><tr><td>值1</td><td>值2</td></tr></table>'
            f'</section>'
            for sec_id in EXPECTED_GROWTH_SECTION_IDS
        )
        return archive_html, f"<html><body>{sections_html}</body></html>"

    def test_extracts_synthetic_mechanics_and_growth_sections(self):
        archive_html, growth_html = self._sample_inputs()
        result = extract_mechanics(archive_html, growth_html)
        self.assertEqual(1, len(result["groups"]))
        self.assertEqual(2, len(result["entries"]))
        self.assertEqual(7, len(result["growth_sections"]))
        self.assertEqual("fixed", result["growth_sections"][3]["id"])

    def test_extracts_real_audited_pages(self):
        root = Path(__file__).resolve().parents[3] / "data" / "research" / "site-plan-audit"
        archive_path = root / "archive-mechanics.html"
        growth_path = root / "topics-growth-mechanics.html"
        if not archive_path.is_file() or not growth_path.is_file():
            self.skipTest("Audited mechanics HTML fixtures not present")
        result = extract_mechanics(
            archive_path.read_text(encoding="utf-8"),
            growth_path.read_text(encoding="utf-8"),
        )
        self.assertEqual(19, len(result["groups"]))
        self.assertEqual(463, len(result["entries"]))
        self.assertEqual(list(EXPECTED_GROWTH_SECTION_IDS), [s["id"] for s in result["growth_sections"]])
        self.assertIn("45%", json.dumps(result["growth_sections"][3], ensure_ascii=False))


if __name__ == "__main__":
    unittest.main()
