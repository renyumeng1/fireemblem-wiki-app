package com.fireemblem.companion.campaign

import com.fireemblem.companion.content.WikiEntry
import com.fireemblem.companion.content.WikiField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BeginnerGuideTest {
    @Test fun projectsPairedQuestionsInSourceOrderAndKeepsMissingAnswersExplicit() {
        val questions = BeginnerGuideProjector.project(
            listOf(
                entry("beginner-guide:later", "Q;问题二", "方法0\n第二段", 6),
                entry("side-quests:unrelated", "不要显示", "其他资料", 3),
                entry("beginner-guide:missing", "Q：问题三", "", 7),
                entry("beginner-guide:first", "Q:问题一", "A：第一段\n第二段", 4),
            ),
        )

        assertEquals(listOf("问题一", "问题二", "问题三"), questions.map(BeginnerGuideQuestion::question))
        assertEquals("第一段\n第二段", questions[0].answer)
        assertEquals("方法0\n第二段", questions[1].answer)
        assertNull(questions[2].answer)
        assertEquals(listOf(4, 6, 7), questions.map(BeginnerGuideQuestion::sourceRowNumber))
    }

    @Test fun routeSummariesPreserveTheirArtworkAndSubjectiveRecommendationLabel() {
        val routes = BeginnerGuideProjector.projectRoutes(
            listOf(
                WikiEntry(
                    id = "beginner-routes:seo", title = "赛奥朵拉篇", subtitle = "", imageAsset = "assets/seo.webp",
                    fields = listOf(
                        WikiField("原资料主观推荐", "B"),
                        WikiField("可挖角人数", "44"),
                        WikiField("路线特色", "国家复兴"),
                        WikiField("游玩注意", "资源管理"),
                    ),
                    sourceSheetName = "新手初次游玩查看", sourceRowNumber = 4,
                ),
            ),
        )

        assertEquals(1, routes.size)
        assertEquals("B", routes.single().recommendation)
        assertEquals(44, routes.single().recruitableCount)
        assertEquals("assets/seo.webp", routes.single().imageAsset)
        assertEquals("资源管理", routes.single().considerations)
    }

    @Test fun mapAccessRulesKeepEverySourceLineAndUnparsedText() {
        val entry = WikiEntry(
            id = "beginner-map-access:overview", title = "世界地图名声门槛", subtitle = "", imageAsset = null,
            fields = listOf(WikiField("开放条件", "初始：阿玛尔忒娅州\n名声LV1：艾雷克特拉州\n额外提示")),
            sourceSheetName = "新手初次游玩查看", sourceRowNumber = 2,
        )

        val rules = BeginnerGuideProjector.projectMapAccess(listOf(entry))

        assertEquals(listOf("初始", "名声LV1", "开放区域"), rules.map(BeginnerMapAccessRule::requirement))
        assertEquals("艾雷克特拉州", rules[1].locations)
        assertEquals("额外提示", rules[2].locations)
    }

    private fun entry(id: String, title: String, answer: String, row: Int) = WikiEntry(
        id = id,
        title = title,
        subtitle = "",
        imageAsset = null,
        fields = listOf(WikiField("解答", answer)),
        sourceSheetName = "新手初次游玩查看",
        sourceRowNumber = row,
    )
}
