import json
import sys
import unittest
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from prepare_growth_simulator import BUDDY_STAT_KEYS, STAT_PERMUTATION, extract_growth_simulator


class PrepareGrowthSimulatorTests(unittest.TestCase):
    def test_applies_exact_stat_permutation_on_synthetic_data(self):
        # Web vector order: [HP, 力量, 魔力, 速度, 技巧, 守备, 魔防, 幸运, 魅力]
        web_growth = [10, 20, 30, 40, 50, 60, 70, 80, 90]
        stream = json.dumps({
            "characters": [{
                "code": "0", "nativeId": "PID_TEST", "name": "测试角色", "recordId": 1,
                "faction": "测试", "gender": "男", "startClass": "平民", "level": 1,
                "base": web_growth, "growth": web_growth, "caps": [99] * 9, "minimum": [2, 0],
            }],
            "classes": [{
                "recordId": 1, "id": "JID_TEST", "name": "平民", "tier": "基础",
                "base": [20] * 9, "growth": [0] * 9, "baseGrowth": web_growth,
                "modifier": [0] * 9, "caps": [99] * 9, "mountType": "", "mountMultiplier": 1,
                "allowedRecords": [], "forbiddenPeople": [],
            }],
            "mounts": [{
                "id": "RID_TEST", "type": "马", "name": "战马",
                "growth": web_growth,
                "attributes": json.dumps({k: idx for idx, k in enumerate(BUDDY_STAT_KEYS)}, ensure_ascii=False),
                "effect": "", "location": "",
            }],
        }, ensure_ascii=False)
        html = f"<script>self.__next_f.push([1,{json.dumps(stream, ensure_ascii=False)}])</script>"
        result = extract_growth_simulator(html)
        expected_buddy = [web_growth[i] for i in STAT_PERMUTATION]
        self.assertEqual(expected_buddy, result["characters"][0]["growth"])
        self.assertEqual(expected_buddy, result["classes"][0]["base_growth"])
        self.assertEqual(expected_buddy, result["mounts"][0]["growth"])
        self.assertEqual(list(range(9)), result["mounts"][0]["attr_bonus"])

    def test_real_page_aligns_with_buddy_rules_v1(self):
        root = Path(__file__).resolve().parents[3]
        page_path = root / "data" / "research" / "site-plan-audit" / "growth-simulator.html"
        rules_path = root / "core-domain" / "src" / "main" / "resources" / "buddy" / "rules-v1.json"
        if not page_path.is_file() or not rules_path.is_file():
            self.skipTest("Audited growth-simulator.html or rules-v1.json missing")
        result = extract_growth_simulator(page_path.read_text(encoding="utf-8"))
        rules = json.loads(rules_path.read_text(encoding="utf-8"))

        self.assertEqual(64, len(result["characters"]))
        self.assertEqual(85, len(result["classes"]))
        self.assertEqual(135, len(result["mounts"]))

        for cls in result["classes"]:
            rj = rules["jobs"].get(cls["id"])
            if rj:
                self.assertEqual(rj["growthBonus"], cls["growth_bonus"], msg=cls["id"])
                self.assertEqual(rj["baseGrowth"], cls["base_growth"], msg=cls["id"])

        mismatched_chars = []
        for ch in result["characters"]:
            rp = rules["persons"].get(ch["native_id"])
            if rp and rp["growth"] != ch["growth"]:
                mismatched_chars.append(ch["native_id"])
        self.assertEqual(["PID_ムウ"], mismatched_chars)


if __name__ == "__main__":
    unittest.main()
