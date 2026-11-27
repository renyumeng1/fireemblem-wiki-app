package com.fireemblem.companion.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import com.fireemblem.companion.ui.theme.FireEmblemTheme
import com.fireemblem.companion.engine.BuddyCampaignLine
import com.fireemblem.companion.engine.BuddyGrowthInput
import com.fireemblem.companion.engine.BuddyRoute
import com.fireemblem.companion.engine.BuddyRules
import com.fireemblem.companion.engine.GrowthStat
import com.fireemblem.companion.engine.LevelJournal
import com.fireemblem.companion.engine.LevelObservation
import com.fireemblem.companion.importer.AtomicBuddyArchiveStore
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SeedSearchScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun backActionCancelsRunningSearchBeforeLeaving() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val rules = BuddyRules.bundled()
        val rows = rules.forecast(42, BuddyGrowthInput("c044", "平民", 1, false, BuddyRoute.KAI), count = 4).rows
        val journal = LevelJournal("反推取消测试", "global", BuddyCampaignLine.KAI, "c044", rows.map { row ->
            LevelObservation(level = row.fromLevel, profession = "平民", part3 = false,
                gains = GrowthStat.entries.map { row.gains.getValue(it) })
        })
        val history = journal.history(rules)
        assertTrue(history.ready)
        val backCalls = AtomicInteger()

        compose.setContent {
            MaterialTheme {
                SeedSearchScreen(
                    archiveStore = AtomicBuddyArchiveStore(target.cacheDir.toPath().resolve("seed-search-ui-test")),
                    archives = emptyList(),
                    onBack = { backCalls.incrementAndGet() },
                    onChooseBackup = {},
                    recordedHistory = history,
                )
            }
        }

        compose.onNodeWithText("用当前记录重新反推").performScrollTo().performClick()
        compose.waitUntil(15000) {
            compose.onAllNodesWithText("正在搜索候选种子…").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("已用", substring = true).assertIsDisplayed()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("取消搜索"))
        compose.onNodeWithText("取消搜索").performClick()
        compose.waitUntil(15000) {
            compose.onAllNodesWithText("搜索已取消，结果不完整").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(0, backCalls.get())

        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("返回升级记录"))
        compose.onNodeWithText("返回升级记录").assertIsDisplayed().performClick()
        assertEquals(1, backCalls.get())
    }

    @Test fun candidateLaunchesInteractiveLv99WorkbenchWithSkipCompareAndPlan() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val rules = BuddyRules.bundled()
        val rows = rules.forecast(42, BuddyGrowthInput("c044", "平民", 1, false, BuddyRoute.KAI), count = 4).rows
        val expectedCurrentSeed = rows.last().nextSeed
        val journal = LevelJournal("反推直达预览测试", "global", BuddyCampaignLine.KAI, "c044", rows.map { row ->
            LevelObservation(
                level = row.fromLevel,
                profession = "平民",
                part3 = false,
                gains = GrowthStat.entries.map { row.gains.getValue(it) },
            )
        })
        val history = journal.history(rules)
        assertTrue(history.ready)

        compose.setContent {
            FireEmblemTheme {
                var launchCtx by remember { mutableStateOf<SeedWorkbenchLaunchContext?>(null) }
                if (launchCtx != null) {
                    SeedForecastScreen(
                        onBack = { launchCtx = null },
                        initialContext = launchCtx,
                    )
                } else {
                    SeedSearchScreen(
                        archiveStore = AtomicBuddyArchiveStore(target.cacheDir.toPath().resolve("seed-search-preview-test")),
                        archives = emptyList(),
                        onBack = {},
                        onChooseBackup = {},
                        recordedHistory = history,
                        recordLabel = "凯伊篇 · 反推直达预览测试",
                        onPreviewCandidate = { ctx -> launchCtx = ctx },
                    )
                }
            }
        }

        // Narrow range to 0..100 for instantaneous deterministic hit of seed 42
        compose.onNodeWithTag("seed-search-list").performScrollToNode(hasText("结束种子（不含）"))
        compose.onNodeWithText("结束种子（不含）").performTextReplacement("100")
        compose.onNodeWithTag("seed-search-list").performScrollToNode(hasTestTag("seed-search-start"))
        compose.onNodeWithTag("seed-search-start").performClick()

        val previewBtnTag = "seed-candidate-preview:$expectedCurrentSeed"
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("当前范围内唯一匹配").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("seed-search-list").performScrollToNode(hasTestTag(previewBtnTag))
        compose.onNodeWithTag(previewBtnTag).assertIsDisplayed().performClick()

        // Immediately lands in unified 3-tab workbench with Lv.5 -> Lv.6 preview rendered!
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Lv.5 → Lv.6").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Lv.5 → Lv.6").assertIsDisplayed()
        compose.onNodeWithText("共 94 步至 Lv.99").assertIsDisplayed()

        // Toggle skip on row #1 -> changes Lv.5 -> Lv.6 to skip!
        compose.onNodeWithTag("seed-workbench-list").performScrollToNode(hasTestTag("preview-toggle-skip:1"))
        compose.onNodeWithTag("preview-toggle-skip:1").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Lv.5 · 跳过/考试").fetchSemanticsNodes().isNotEmpty()
        }

        // Open merged compare & pick class dialog on row #2
        compose.onNodeWithTag("seed-workbench-list").performScrollToNode(hasTestTag("preview-compare-classes:2"))
        compose.onNodeWithTag("preview-compare-classes:2").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("换职对比", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("关闭").performClick()

        // Commit steps 1..2 into the sequential plan and verify Plan tab updates!
        compose.onNodeWithTag("seed-workbench-list").performScrollToNode(hasTestTag("preview-commit:2"))
        compose.onNodeWithTag("preview-commit:2").performClick()
        compose.onNodeWithTag("seed-workbench-list").performScrollToNode(hasTestTag("workbench-tab:plan"))
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("培养计划 (2)").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("workbench-tab:plan").performClick()
        compose.onNodeWithTag("seed-workbench-list").performScrollToNode(hasTestTag("plan-step-complete"))
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("已完成 0 / 2").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("plan-step-complete").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("已完成 1 / 2").fetchSemanticsNodes().isNotEmpty()
        }

        // Verify the rebuilt Auto-Planner tab runs cleanly in 1 tap!
        compose.onNodeWithTag("seed-workbench-list").performScrollToNode(hasTestTag("workbench-tab:auto"))
        compose.onNodeWithTag("workbench-tab:auto").performClick()
        compose.onNodeWithTag("seed-workbench-list").performScrollToNode(hasTestTag("auto-plan-run"))
        compose.onNodeWithTag("auto-plan-run").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("套用到培养计划").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
