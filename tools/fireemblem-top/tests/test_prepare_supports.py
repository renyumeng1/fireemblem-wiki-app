import hashlib
import json
import sys
import unittest
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from prepare_supports import _STAT_NAMES, extract_supports


class PrepareSupportsTests(unittest.TestCase):
    def setUp(self):
        self.people = [
            {"name": "角色甲", "displayName": "角色甲", "href": "/database/characters/a"},
            {"name": "角色乙", "displayName": "$undefined", "href": "/database/characters/b"},
            {"name": "角色丙", "displayName": "角色丙", "href": "/database/characters/c"},
        ]
        self.jobs = [{
            "id": "JID_测试职业",
            "name": "测试职业",
            "displayName": "$undefined",
            "type": "連携",
            "tiers": ["中级"],
        }]
        self.payload = {
            "hg": [{"name": person["name"]} for person in self.people],
            "AS": [
                {
                    "a": first,
                    "b": second,
                    "affinity": 1,
                    "initial": 0,
                    "bonus": [first - second] * 9,
                    "talks": ([{
                        "rank": rank,
                        "start": [1, rank],
                        "end": [2, rank],
                        "route": 4,
                        "anyJoined": ["角色甲"],
                        "conditions": ["角色甲已加入"],
                        "pending": 0,
                        "datePending": False,
                    } for rank in range(1, 5)] if (first, second) == (0, 1) else []),
                }
                for first in range(3)
                for second in range(first + 1, 3)
            ],
            "gK": {
                type_id: [[level] * 9 for level in range(1, 6)]
                for type_id in ("援護", "攻勢", "連携", "防衛", "魔道", "騎兵", "飛兵")
            },
            "M1": list(_STAT_NAMES),
            "pw": [0, 400, 1400, 2400, 3200],
        }

    def _inputs(self):
        page_stream = json.dumps({"people": self.people, "jobs": self.jobs}, ensure_ascii=False,
                                 separators=(",", ":"))
        page = (
            '<script src="/_next/static/chunks/5657-test.js"></script>'
            f"<script>self.__next_f.push([1,{json.dumps(page_stream, ensure_ascii=False)}])</script>"
        )
        payload_json = json.dumps(self.payload, ensure_ascii=False, separators=(",", ":"))
        bundle = f"const supportData=JSON.parse({json.dumps(payload_json, ensure_ascii=False)});"
        return page, bundle

    def _extract(self):
        page, bundle = self._inputs()
        return extract_supports(page, bundle)

    def test_extracts_complete_pairs_jobs_bonuses_talks_and_provenance(self):
        page, bundle = self._inputs()
        output = extract_supports(page, bundle)

        self.assertEqual(3, len(output["people"]))
        self.assertEqual("角色乙", output["people"][1]["display_name"])
        self.assertEqual("测试职业", output["jobs"][0]["display_name"])
        self.assertEqual(3, len(output["pairs"]))
        self.assertEqual([1, 2, 3, 4], [talk["rank"] for talk in output["pairs"][0]["talks"]])
        self.assertEqual([0, 400, 1400, 2400, 3200], output["thresholds"])
        self.assertEqual(["物攻", "魔攻", "物防", "魔防", "命中", "回避", "必杀", "必杀回避", "攻速"],
                         output["stat_names"])
        self.assertEqual([[0] * 9] * 5, output["support_types"][-1]["levels"])
        page_hash = hashlib.sha256(page.encode("utf-8")).hexdigest()
        bundle_hash = hashlib.sha256(bundle.encode("utf-8")).hexdigest()
        self.assertEqual(page_hash, output["source"]["page_sha256"])
        self.assertEqual(bundle_hash, output["source"]["bundle_sha256"])
        self.assertEqual(hashlib.sha256((page_hash + bundle_hash).encode("ascii")).hexdigest(),
                         output["source"]["revision"])

    def test_rejects_missing_and_duplicate_unordered_pairs(self):
        self.payload["AS"].pop()
        with self.assertRaisesRegex(ValueError, "不完整"):
            self._extract()

        self.payload["AS"].append(dict(self.payload["AS"][0], a=1, b=0))
        with self.assertRaisesRegex(ValueError, "重复"):
            self._extract()

    def test_rejects_invalid_role_indexes_and_page_roster_mismatch(self):
        self.payload["AS"][0]["b"] = 8
        with self.assertRaisesRegex(ValueError, "索引无效"):
            self._extract()

        self.payload["AS"][0]["b"] = 1
        self.people[0]["name"] = "网页缺失角色"
        with self.assertRaisesRegex(ValueError, "角色清单与支援数据角色不一致"):
            self._extract()

    def test_rejects_changed_stat_order_or_malformed_bonus_dimensions(self):
        self.payload["M1"][0] = "魔攻"
        with self.assertRaisesRegex(ValueError, "属性顺序"):
            self._extract()

        self.payload["M1"] = list(_STAT_NAMES)
        self.payload["gK"]["連携"][2].pop()
        with self.assertRaisesRegex(ValueError, "加成格式无效"):
            self._extract()

        self.payload["gK"]["連携"][2] = [0] * 9
        self.payload["AS"][0]["bonus"][0] = 101
        with self.assertRaisesRegex(ValueError, "关系属性、等级或对话字段"):
            self._extract()

    def test_rejects_invalid_thresholds_dialogue_bounds_and_unknown_job_type(self):
        self.payload["pw"] = [0, 400, 400, 2400, 3200]
        with self.assertRaisesRegex(ValueError, "累计门槛无效"):
            self._extract()

        self.payload["pw"] = [0, 400, 1400, 2400, 3200]
        self.payload["AS"][0]["talks"][0]["rank"] = 5
        with self.assertRaisesRegex(ValueError, "对话条件字段缺失或越界"):
            self._extract()

        self.payload["AS"][0]["talks"][0]["rank"] = 1
        self.jobs[0]["type"] = "未知类型"
        with self.assertRaisesRegex(ValueError, "jobs"):
            self._extract()


if __name__ == "__main__":
    unittest.main()
