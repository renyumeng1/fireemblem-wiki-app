import json
import sys
import unittest
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from prepare_endings import extract_endings


class PrepareEndingsTests(unittest.TestCase):
    def test_extracts_pair_and_single_stories_with_source_identity(self):
        paired = [{
            "id": 1,
            "characters": [{"name": "角色甲", "title": "剑士"}, {"name": "角色乙", "title": "歌者"}],
            "paragraphs": ["双人正文第一段。", "双人正文第二段。"],
        }]
        singles = [{
            "id": 100,
            "characters": [{"name": "角色甲", "title": "剑士"}],
            "label": "存活结局",
            "condition": "存活且未进入双人结局。",
            "paragraphs": ["单人正文。"],
        }]
        stream = json.dumps({"singleEndings": singles}, ensure_ascii=False, separators=(",", ":"))
        page = (
            '<script src="/_next/static/chunks/app/topics/page-test.js"></script>'
            f"<script>self.__next_f.push([1,{json.dumps(stream, ensure_ascii=False)}])</script>"
        )
        pair_json = json.dumps(paired, ensure_ascii=False, separators=(",", ":"))
        bundle = f"const pairData=JSON.parse({json.dumps(pair_json, ensure_ascii=False)});"

        output = extract_endings(page, bundle)

        self.assertEqual(1, output["schema_version"])
        self.assertTrue(output["source"]["revision"])
        self.assertEqual(2, len(output["records"]))
        self.assertEqual("角色甲 × 角色乙", output["records"][0]["title"])
        self.assertEqual("后日谈 01", output["records"][0]["fields"]["ending_label"])
        self.assertEqual("双人正文第一段。\n\n双人正文第二段。", output["records"][0]["fields"]["story"])
        self.assertEqual("角色甲", output["records"][1]["title"])
        self.assertEqual("存活结局", output["records"][1]["fields"]["ending_label"])
        self.assertEqual("存活且未进入双人结局。", output["records"][1]["fields"]["condition"])

    def test_rejects_malformed_or_incomplete_source(self):
        page = '<script src="/_next/static/chunks/app/topics/page-test.js"></script>'
        stream = json.dumps({"singleEndings": [{
            "id": 100,
            "characters": [{"name": "角色甲"}],
            "paragraphs": [],
        }]}, ensure_ascii=False)
        page += f"<script>self.__next_f.push([1,{json.dumps(stream, ensure_ascii=False)}])</script>"
        pair_json = json.dumps([{
            "id": 1,
            "characters": [{"name": "角色甲"}, {"name": "角色乙"}],
            "paragraphs": ["双人正文。"],
        }], ensure_ascii=False)
        bundle = f"const pairData=JSON.parse({json.dumps(pair_json, ensure_ascii=False)});"

        with self.assertRaisesRegex(ValueError, "正文缺失"):
            extract_endings(page, bundle)


if __name__ == "__main__":
    unittest.main()
