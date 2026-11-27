package com.fireemblem.companion.campaign

import com.fireemblem.companion.content.WikiEntry
import com.fireemblem.companion.content.WikiField
import com.fireemblem.companion.engine.BuddyCampaignLine
import com.fireemblem.companion.personal.PersonalRevision
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CampaignGuideTest {
    @Test fun sideQuestsUseRouteMarkersWithoutShowingThemAsChapters() {
        val entry = WikiEntry("side-quests:one", "河边委托", "", null, listOf(
            WikiField("凯篇", "〇", null),
            WikiField("迪篇", "", null),
            WikiField("触发时期与地点", "第 3 章\n营地", null),
            WikiField("报酬奖励", "药草", null),
        ), "任务表", 4)

        val kai = CampaignGuideProjector.project(BuddyCampaignLine.KAI, listOf(entry)).single()
        assertEquals(CampaignTaskKind.SIDE_QUEST, kai.kind)
        assertEquals("第 3 章\n营地", kai.timing)
        assertEquals("药草", kai.rewards)
        assertTrue(kai.availableOnLine)
        assertTrue(CampaignGuideProjector.project(BuddyCampaignLine.DIT, listOf(entry)).isEmpty())
    }

    @Test fun sideQuestTimingIsRouteScopedOnlyWhenEveryAvailableRouteIsExplicit() {
        val routeTiming = listOf(
            "第1部 凯伊篇 第2章 2月3日 市区",
            "第1部 迪托利希篇 第4章 3月27日 市区",
            "第1部 赛奥朵拉篇 第4章 3月1日 市区",
            "第1部 蕾达篇 第4章 3月21日 上参道",
            "共同补充信息",
        ).joinToString("\n")
        val fields = listOf(
            WikiField("凯篇", "〇", null),
            WikiField("迪篇", "〇", null),
            WikiField("赛篇", "〇", null),
            WikiField("蕾篇", "〇", null),
            WikiField("触发时期与地点", routeTiming, null),
        )
        val entry = WikiEntry("side-quests:route-timing", "道具店委托", "", null, fields, "任务表", 7)

        val kai = CampaignGuideProjector.project(BuddyCampaignLine.KAI, listOf(entry)).single()
        val leda = CampaignGuideProjector.project(BuddyCampaignLine.LEDA, listOf(entry)).single()

        assertEquals("第1部 凯伊篇 第2章 2月3日 市区\n共同补充信息", kai.timing)
        assertEquals("第1部 蕾达篇 第4章 3月21日 上参道\n共同补充信息", leda.timing)

        val incompleteTiming = routeTiming.lines().filterNot { it.contains("蕾达篇") }.joinToString("\n")
        val incomplete = entry.copy(fields = fields.dropLast(1) + WikiField("触发时期与地点", incompleteTiming, null))
        assertEquals(incompleteTiming, CampaignGuideProjector.project(BuddyCampaignLine.KAI, listOf(incomplete)).single().timing)
    }

    @Test fun paraloguesKeepIndependentRouteWindowsAndSkipUnlistedLines() {
        val entry = WikiEntry("paralogues:one", "骑士石之谜", "", null, listOf(
            WikiField("凯篇接取期限", "第 9 章", null),
            WikiField("凯篇截止期限", "9 月 20 日", null),
            WikiField("赛篇接取期限", "", null),
            WikiField("赛篇截止期限", "", null),
            WikiField("触发条件与特殊奖励", "完成前置委托", null),
        ), "外传表", 5)

        val kai = CampaignGuideProjector.project(BuddyCampaignLine.KAI, listOf(entry)).single()
        assertEquals("第 9 章", kai.availability)
        assertEquals("9 月 20 日", kai.deadline)
        assertEquals("完成前置委托", kai.conditions)
        assertTrue(CampaignGuideProjector.project(BuddyCampaignLine.SEO, listOf(entry)).isEmpty())
    }

    @Test fun paralogueDeadlinesRemainIndependentAcrossAllFourRoutes() {
        val entry = WikiEntry("paralogues:four-lines", "四线限时外传", "", null, listOf(
            WikiField("凯篇接取期限", "凯篇第8章", null),
            WikiField("凯篇截止期限", "凯篇10月12日", null),
            WikiField("迪篇接取期限", "迪篇第9章", null),
            WikiField("迪篇截止期限", "迪篇10月21日", null),
            WikiField("赛篇接取期限", "赛篇第7章", null),
            WikiField("赛篇截止期限", "赛篇9月25日", null),
            WikiField("蕾篇接取期限", "蕾篇第10章", null),
            WikiField("蕾篇截止期限", "蕾篇11月2日", null),
        ), "外传表", 8)

        val tasks = BuddyCampaignLine.entries.associate { line ->
            line to CampaignGuideProjector.project(line, listOf(entry)).single()
        }

        assertEquals("凯篇第8章", tasks.getValue(BuddyCampaignLine.KAI).availability)
        assertEquals("凯篇10月12日", tasks.getValue(BuddyCampaignLine.KAI).deadline)
        assertEquals("迪篇第9章", tasks.getValue(BuddyCampaignLine.DIT).availability)
        assertEquals("迪篇10月21日", tasks.getValue(BuddyCampaignLine.DIT).deadline)
        assertEquals("赛篇第7章", tasks.getValue(BuddyCampaignLine.SEO).availability)
        assertEquals("赛篇9月25日", tasks.getValue(BuddyCampaignLine.SEO).deadline)
        assertEquals("蕾篇第10章", tasks.getValue(BuddyCampaignLine.LEDA).availability)
        assertEquals("蕾篇11月2日", tasks.getValue(BuddyCampaignLine.LEDA).deadline)
    }

    @Test fun recruitmentConditionsFollowExplicitRouteRowsAndUnlistedRoutesStayOut() {
        val entry = WikiEntry("characters:one", "角色甲", "", null, listOf(
            WikiField("最早加入章节", "第1部 凯伊篇 第4章✅\n第1部 迪托利希篇 第7章\n第1部 赛奥朵拉篇 不可挖角\n第1部 蕾达篇 第8章", null),
            WikiField("加入条件", "凯伊的条件\n迪托利希的条件\n不可挖角\n蕾达的条件", null),
        ), "角色表", 6)

        val kai = CampaignGuideProjector.project(BuddyCampaignLine.KAI, listOf(entry)).single()
        val leda = CampaignGuideProjector.project(BuddyCampaignLine.LEDA, listOf(entry)).single()
        assertEquals(CampaignTaskKind.RECRUITMENT, kai.kind)
        assertEquals("第1部 凯伊篇 第4章✅", kai.availability)
        assertEquals("凯伊的条件", kai.timing)
        assertEquals("蕾达的条件", leda.timing)
        assertTrue(CampaignGuideProjector.project(BuddyCampaignLine.SEO, listOf(entry)).isEmpty())

        val sharedChapter = entry.copy(fields = listOf(
            WikiField("最早加入章节", "第3部 第4区分", null),
            WikiField("加入条件", "完成前置任务", null),
        ))
        assertTrue(CampaignGuideProjector.project(BuddyCampaignLine.KAI, listOf(sharedChapter)).isEmpty())
    }

    @Test fun timelineUsesExplicitRoutesAndIncludesCommonChaptersWithoutInventingAPath() {
        val kaiEvent = WikiEntry("campaign-timeline:kai-event", "对话", "", null, listOf(
            WikiField("路线范围", "kai", null),
            WikiField("事件类别", "事件", null),
            WikiField("事件名称", "对话", null),
            WikiField("章节", "第4章", null),
            WikiField("开始时间", "3月11日", null),
            WikiField("截止时间", "当天", null),
            WikiField("触发方式", "贫民窟发光点", null),
            WikiField("备注", "谭利穆恩和迪托利希遇袭", null),
        ), "时间轴", 4)
        val sharedEvent = WikiEntry("campaign-timeline:shared-event", "齐利科入队", "", null, listOf(
            WikiField("路线范围", "common-war", null),
            WikiField("章节", "第2章", null),
            WikiField("触发方式", "击败齐利科", null),
            WikiField("奖励", "齐利科入队", null),
        ), "时间轴", 80)
        val otherLineEvent = WikiEntry("campaign-timeline:other-line", "不应显示", "", null, listOf(
            WikiField("路线范围", "seo", null),
            WikiField("事件类别", "事件", null),
        ), "时间轴", 40)

        val kaiTasks = CampaignGuideProjector.project(BuddyCampaignLine.KAI, listOf(otherLineEvent, sharedEvent, kaiEvent))
        val ditTasks = CampaignGuideProjector.project(BuddyCampaignLine.DIT, listOf(sharedEvent, kaiEvent))

        assertEquals(listOf("谭利穆恩和迪托利希遇袭", "齐利科入队"), kaiTasks.map { it.title })
        assertEquals("第4章", kaiTasks.first().chapter)
        assertEquals("当天", kaiTasks.first().deadline)
        assertTrue(kaiTasks.last().isCommon)
        assertEquals(listOf("齐利科入队"), ditTasks.map { it.title })
    }

    @Test fun challengeBookIsSequencedAndAvailableOnlyOnTheDietrichLine() {
        val later = WikiEntry("challenge-book:later", "寻求挑战者！斩鹰阿基雷", "", null, listOf(
            WikiField("对应战技", "秘传·落燕", null),
            WikiField("接取条件", "名声Lv3，并完成Lv2挑战任务", null),
            WikiField("任务地点", "鲁贝斯之森", null),
            WikiField("任务奖励", "金币×56000G、名声值×150", null),
            WikiField("威力", "0", null),
            WikiField("命中", "0（20）", null),
        ), "挑战书", 4)
        val earlier = later.copy(id = "challenge-book:first", title = "寻求挑战者！吸血鬼波尔赫斯", sourceRowNumber = 3)

        val ditTasks = CampaignGuideProjector.project(BuddyCampaignLine.DIT, listOf(later, earlier))

        assertEquals(listOf(earlier.id, later.id), ditTasks.map { it.entryId })
        assertEquals("名声Lv3，并完成Lv2挑战任务", ditTasks.last().availability)
        assertEquals("秘传·落燕", ditTasks.last().timing)
        assertTrue(ditTasks.last().conditions.orEmpty().contains("威力 0"))
        assertTrue(CampaignGuideProjector.project(BuddyCampaignLine.KAI, listOf(later)).isEmpty())
    }

    @Test fun taskFacetsOfferExactSourceValuesAndFilterWithoutMixingUnavailableFields() {
        val sideQuest = CampaignGuideTask(
            entryId = "side-quests:one",
            title = "城内委托",
            kind = CampaignTaskKind.SIDE_QUEST,
            imageAsset = null,
            availableOnLine = true,
            availability = null,
            deadline = null,
            timing = "第 5 章 · 帝都",
            characters = null,
            location = null,
            completion = "与守卫对话 / 5月20日",
            conditions = null,
            rewards = "护身符",
            hints = null,
            victoryCondition = null,
        )
        val paralogue = sideQuest.copy(
            entryId = "paralogues:one",
            title = "遗迹调查",
            kind = CampaignTaskKind.PARALOGUE,
            availability = "第 9 章",
            deadline = "10月12日",
            timing = null,
            completion = null,
            conditions = "完成前置委托",
            rewards = "稀有武器",
        )
        val timeline = sideQuest.copy(
            entryId = "campaign-timeline:one",
            title = "夜间事件",
            kind = CampaignTaskKind.TIMELINE,
            timing = "3月15日",
            chapter = "第 5 章",
            deadline = "当天",
            location = "城中广场",
            completion = null,
            rewards = "角色加入",
        )

        assertEquals("第 5 章 · 帝都", CampaignTaskFacet.CHAPTER.value(sideQuest))
        assertEquals("10月12日", CampaignTaskFacet.DEADLINE.value(paralogue))
        assertEquals("完成前置委托", CampaignTaskFacet.CONDITION.value(paralogue))
        assertEquals("第 5 章", CampaignTaskFacet.CHAPTER.value(timeline))
        assertEquals(listOf("10月12日", "当天"), CampaignTaskFacet.DEADLINE.options(listOf(paralogue, timeline)))
        assertTrue(CampaignTaskFacet.REWARD.matches(paralogue, "稀有武器"))
        assertFalse(CampaignTaskFacet.REWARD.matches(paralogue, "护身符"))
        assertNull(CampaignTaskFacet.CHAPTER.value(sideQuest.copy(kind = CampaignTaskKind.CHALLENGE, timing = null)))
    }

    @Test fun progressIsProfileAndRouteScopedAndRoundTripsFromPersonalRevision() {
        val taskId = "side-quests:one"
        val completed = CampaignTaskProgress("profile-a", "kai", taskId, CampaignTaskStatus.COMPLETED)
        val otherLine = CampaignTaskProgress("profile-a", "dit", taskId, CampaignTaskStatus.COMPLETED)
        val otherProfile = CampaignTaskProgress("profile-b", "kai", taskId, CampaignTaskStatus.COMPLETED)
        val recruit = CampaignTaskProgress("profile-a", "kai", "characters:one", CampaignTaskStatus.TODO)
        val timeline = CampaignTaskProgress("profile-a", "kai", "campaign-timeline:event", CampaignTaskStatus.TODO)
        val challenge = CampaignTaskProgress("profile-a", "dit", "challenge-book:one", CampaignTaskStatus.TODO)

        assertNotEquals(completed.entityId, otherLine.entityId)
        assertNotEquals(completed.entityId, otherProfile.entityId)
        assertNotEquals(completed.entityId, recruit.entityId)
        assertNotEquals(timeline.entityId, challenge.entityId)
        val revision = PersonalRevision(UUID.randomUUID().toString(), completed.entityId, "progress",
            UUID.randomUUID().toString(), emptyList(), false, 1L, completed.payload())
        assertEquals(completed, CampaignTaskProgress.fromRevision(revision))
        assertNull(CampaignTaskProgress.fromRevision(revision.copy(deleted = true)))
        assertFalse(CampaignTaskProgress.fromRevision(revision.copy(entityId = UUID.randomUUID().toString())) == completed)
    }
}
