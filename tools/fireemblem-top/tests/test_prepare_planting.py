import hashlib
import sys
import unittest
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from prepare_planting import extract_planting


class PreparePlantingTests(unittest.TestCase):
    def test_extracts_source_records_parameters_and_exact_map_markers(self):
        page = _page_html()

        output = extract_planting(page)

        self.assertEqual(1, output["schema_version"])
        self.assertEqual(hashlib.sha256(page.encode("utf-8")).hexdigest(), output["source"]["page_sha256"])
        self.assertEqual([{"name": "稀有植物"}], output["plants"])
        self.assertEqual("unverified", output["harvest_parameters"]["yield_count_status"])
        self.assertEqual(10, output["harvest_parameters"]["yield_count"])
        self.assertEqual([100.0, 70.0], [
            item["probability_percent"] for item in output["harvest_parameters"]["probabilities"]
        ])
        location = output["locations"][0]
        self.assertEqual(("常雾平原", "稀有植物", 30.0),
                         (location["name"], location["plant_name"], location["plant_probability_percent"]))
        self.assertEqual(["カイ|11003", "救世編|11029"], [
            marker["source_map_id"] for marker in location["map_markers"]
        ])
        self.assertEqual(["kai", "salvation"], [
            marker["chapter_id"] for marker in location["map_markers"]
        ])
        self.assertEqual("对田地施肥。", output["source_notes"]["usage_note"])
        fertilizer = output["fertilizers"][0]
        self.assertEqual(("测试肥料", 50.0, "改善土质。", "可在样本山地捡到。"), (
            fertilizer["name"], fertilizer["parameter_percent"], fertilizer["description"],
            fertilizer["acquisition_hint"],
        ))
        self.assertEqual("埃利萨岭", fertilizer["map_markers"][0]["location_name"])
        self.assertIn("不能直接相加", output["source_notes"]["fertilizer_note"])

    def test_rejects_unknown_campaign_tokens_and_changed_location_headers(self):
        with self.assertRaisesRegex(ValueError, "篇章未配置"):
            extract_planting(_page_html().replace("%E3%82%AB%E3%82%A4%7C11003", "unknown%7C11003"))
        with self.assertRaisesRegex(ValueError, "地点表"):
            extract_planting(_page_html().replace("稀有植物概率", "出现率"))


def _page_html() -> str:
    return """<!doctype html><html><body><main>
    <section><h2>收获参数怎么理解</h2>
      <h3>收获周期</h3><p>收获周期为 6 回合。</p>
      <h3>收获个数</h3><p>收获数量：10（待确认）。</p>
      <h3>收获次数与概率</h3><p>各次收获的对应概率如下。</p>
      <table><tr><th>收获次数</th><th>1</th><th>2</th></tr>
      <tr><th>对应概率</th><td>100%</td><td>70%</td></tr></table>
      <p>待确认：判定时点尚未验证。</p>
    </section>
    <section><h2>稀有植物获取地点</h2>
      <table><thead><tr><th>地点</th><th>稀有植物</th><th>稀有植物概率</th><th>植物说明</th></tr></thead>
      <tbody><tr><td><span>常雾平原</span>
        <a href="/maps?pin=%E3%82%AB%E3%82%A4%7C11003">凯伊篇（互动地图）</a>
        <a href="/maps?pin=%E6%95%91%E4%B8%96%E7%B7%A8%7C11029">救世篇（互动地图）</a></td>
        <td><span>稀有植物</span></td><td>30%</td><td>适合田地的样本植物。</td></tr></tbody>
      </table>
    </section>
    <section><h2>肥料与获取地点</h2><article>
      <h3>测试肥料 <span>50%</span></h3>
      <div><p>改善土质。</p><p>可在样本山地捡到。</p></div>
      <details><summary>查看关联来源</summary><div>
        <span><span>埃利萨岭</span><a href="/maps?pin=%E3%82%AB%E3%82%A4%7C13001">凯伊篇（互动地图）</a></span>
      </div></details>
    </article></section>
      <p><strong>如何使用：</strong>对田地施肥。</p>
      <p><strong>百分比怎么看：</strong>参数关系待验证，不能直接相加。</p>
    </main></body></html>"""


if __name__ == "__main__":
    unittest.main()
