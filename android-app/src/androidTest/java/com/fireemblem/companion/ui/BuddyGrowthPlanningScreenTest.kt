package com.fireemblem.companion.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import com.fireemblem.companion.engine.BuddyCampaignLine
import com.fireemblem.companion.engine.BuddyRules
import com.fireemblem.companion.engine.GrowthStat
import com.fireemblem.companion.engine.JournalSeedCheckpoint
import com.fireemblem.companion.engine.JournalSeedEvidence
import com.fireemblem.companion.engine.LevelJournal
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class BuddyGrowthPlanningScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun growthStatColorsRemainDistinctInLightAndDarkThemes() {
        val lightColors = GrowthStat.entries.map { growthStatTone(it, darkTheme = false).container }
        val darkColors = GrowthStat.entries.map { growthStatTone(it, darkTheme = true).container }

        assertEquals(GrowthStat.entries.size, lightColors.toSet().size)
        assertEquals(GrowthStat.entries.size, darkColors.toSet().size)
    }

    @Test fun plannerRunsDirectlyWithoutMandatoryStatsAndShowsSaveState() {
        val source = LevelJournal("规划测试", "global", BuddyCampaignLine.KAI, "c044")
        val checkpoint = JournalSeedCheckpoint(
            seed = 42,
            eventIndex = 0,
            level = 1,
            profession = "平民",
            part3 = false,
            stats = null,
            caps = null,
            evidence = JournalSeedEvidence.MANUAL,
            historyDigest = source.historyDigest(),
            unlockedProfessions = setOf("平民"),
        )
        val journal = source.copy(seedCheckpoint = checkpoint, unlockedProfessions = setOf("平民"))
        val saving = mutableStateOf(false)
        val saveError = mutableStateOf<String?>(null)

        compose.setContent {
            MaterialTheme {
                BuddyGrowthPlanningScreen(
                    journal = journal,
                    checkpoint = checkpoint,
                    rules = BuddyRules.bundled(),
                    saving = saving.value,
                    saveError = saveError.value,
                    onBack = {},
                    onSave = { _, _, _, _ -> saving.value = true },
                )
            }
        }

        // Expanding & collapsing advanced constraints works smoothly
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("高级约束", substring = true))
        compose.onNodeWithText("高级约束", substring = true).performClick()
        compose.onNodeWithContentDescription("${GrowthStat.STRENGTH.label}当前实测").performScrollTo().assertIsDisplayed()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("收起高级约束"))
        compose.onNodeWithText("收起高级约束").performClick()

        // Generate works IMMEDIATELY with zero mandatory 9-stat typing!
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("生成培养方案"))
        compose.onNodeWithText("生成培养方案").performClick()
        compose.waitUntil(30000) {
            compose.onAllNodesWithText("已探索", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("保存并关联到记录册"))
        compose.onNodeWithText("保存并关联到记录册").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("保存并关联到记录册").performClick()
        compose.onNodeWithText("正在保存…").assertIsDisplayed()

        saving.value = false
        saveError.value = "本地保存失败"
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("本地保存失败"))
        compose.onNodeWithText("本地保存失败").fetchSemanticsNode()
    }

    @Test fun invalidTargetLevelShowsClearFeedback() {
        val source = LevelJournal("无效等级", "global", BuddyCampaignLine.KAI, "c044")
        val checkpoint = JournalSeedCheckpoint(
            seed = 42,
            eventIndex = 0,
            level = 5,
            profession = "平民",
            part3 = false,
            stats = null,
            caps = null,
            evidence = JournalSeedEvidence.MANUAL,
            historyDigest = source.historyDigest(),
            unlockedProfessions = setOf("平民"),
        )
        val journal = source.copy(seedCheckpoint = checkpoint, unlockedProfessions = setOf("平民"))

        compose.setContent {
            MaterialTheme {
                BuddyGrowthPlanningScreen(
                    journal = journal,
                    checkpoint = checkpoint,
                    rules = BuddyRules.bundled(),
                    saving = false,
                    saveError = null,
                    onBack = {},
                    onSave = { _, _, _, _ -> },
                )
            }
        }

        compose.onNodeWithContentDescription("目标等级").performScrollTo().performTextReplacement("3")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("生成培养方案"))
        compose.onNodeWithText("生成培养方案").performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("目标等级应在 6 至 99 之间"))
        compose.onNodeWithText("目标等级应在 6 至 99 之间").assertIsDisplayed()
    }

    @Test fun uncappedAndSkippedObservationsUseUnboundedRoom() {
        val enteredRoom = List(GrowthStat.entries.size) { "0" }

        assertEquals(List(GrowthStat.entries.size) { 32767 }, resolveObservationRoom(false, false, enteredRoom))
        assertEquals(List(GrowthStat.entries.size) { 32767 }, resolveObservationRoom(true, true, enteredRoom))
        assertEquals(List(GrowthStat.entries.size) { 0 }, resolveObservationRoom(true, false, enteredRoom))
    }
}
