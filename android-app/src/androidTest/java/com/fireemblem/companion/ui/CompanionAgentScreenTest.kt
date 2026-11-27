package com.fireemblem.companion.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import com.fireemblem.companion.agent.AgentLlmClient
import com.fireemblem.companion.agent.AgentLlmResponse
import com.fireemblem.companion.agent.AgentToolCall
import com.fireemblem.companion.agent.AgentToolDefinition
import com.fireemblem.companion.agent.AgentTurnMessage
import com.fireemblem.companion.agent.AgentWikiCitation
import com.fireemblem.companion.agent.CompanionAgentTools
import com.fireemblem.companion.content.CompanionGame
import com.fireemblem.companion.content.WikiDatabaseRepository
import com.fireemblem.companion.engine.SeedWorkbenchRecord
import com.fireemblem.companion.personal.PersonalStore
import com.fireemblem.companion.ui.theme.FireEmblemTheme
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CompanionAgentScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private fun isolatedContext(tempRoot: File): Context {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        return object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(tempRoot, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir(): File = File(tempRoot, "no-backup").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) =
                target.getSharedPreferences("${tempRoot.name}-$name", mode)
        }
    }

    @Test
    fun offlineShortcutSearchesCrossGameAndSolvesParetoWithConfirmedWorkbenchImport() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val tempRoot = Files.createTempDirectory(target.cacheDir.toPath(), "agent-ui-offline-").toFile()
        val testContext = isolatedContext(tempRoot)
        var openedCitation: AgentWikiCitation? = null
        var importedLaunch: SeedWorkbenchLaunchContext? = null
        try {
            WikiDatabaseRepository.open(target).use { fwRepo ->
                rule.setContent {
                    CompositionLocalProvider(LocalContext provides testContext) {
                        FireEmblemTheme {
                            CompanionAgentScreen(
                                activeGame = CompanionGame.FORTUNES_WEAVE,
                                fwRepository = fwRepo,
                                onBack = {},
                                onOpenWikiCitation = { openedCitation = it },
                                onOpenWorkbench = { importedLaunch = it },
                            )
                        }
                    }
                }

                // Zero-config defaults straight into offline shortcut mode under 战策参谋
                rule.waitUntil(10_000) {
                    rule.onAllNodesWithTag("agent-offline-search").fetchSemanticsNodes().isNotEmpty()
                }
                rule.onNodeWithText("战策参谋").assertIsDisplayed()

                // 1. Cross-game Wiki fuzzy search for Three Houses class "圣天马骑士"
                rule.onNodeWithTag("agent-offline-search").performTextReplacement("圣天马骑士")
                rule.waitUntil(10_000) {
                    rule.onAllNodesWithTag("fuzzy-search-dropdown").fetchSemanticsNodes().isNotEmpty()
                }
                rule.onNodeWithTag("agent-offline-search-go").performClick()
                val pegHit = com.fireemblem.companion.agent.CrossGameWikiRetriever(target, fwRepo).use { r ->
                    r.search("圣天马骑士", gameFilter = "three-houses", limit = 5).first()
                }
                rule.waitUntil(10_000) {
                    rule.onAllNodesWithTag("agent-offline-hit:${pegHit.entryId}").fetchSemanticsNodes().isNotEmpty()
                }
                rule.onNodeWithTag("agent-offline-list").performScrollToNode(hasTestTag("agent-offline-hit:${pegHit.entryId}"))
                rule.onNodeWithTag("agent-offline-hit:${pegHit.entryId}").performClick()
                assertNotNull("Clicking offline hit should invoke onOpenWikiCitation", openedCitation)
                assertEquals("three-houses", openedCitation!!.gameId)

                // 2. Solve Pareto minimum-switch + exam-skip growth plan and confirm Workbench import
                rule.onNodeWithTag("agent-offline-list").performScrollToNode(hasTestTag("agent-planner-seed"))
                rule.onNodeWithTag("agent-planner-seed").performTextReplacement("1090197388")
                rule.onNodeWithTag("agent-offline-list").performScrollToNode(hasTestTag("agent-min-gain:3"))
                rule.onNodeWithTag("agent-min-gain:3").performClick()
                rule.onNodeWithTag("agent-offline-list").performScrollToNode(hasTestTag("agent-solve-pareto"))
                rule.onNodeWithTag("agent-solve-pareto").performClick()

                rule.waitUntil(15_000) {
                    rule.onAllNodesWithTag("pareto-proposal-card").fetchSemanticsNodes().isNotEmpty()
                }
                rule.onNodeWithTag("agent-offline-list").performScrollToNode(hasTestTag("pareto-import-workbench"))
                rule.onNodeWithTag("pareto-import-workbench").performClick()

                rule.waitUntil(5_000) {
                    rule.onAllNodesWithTag("agent-confirm-import").fetchSemanticsNodes().isNotEmpty()
                }
                rule.onNodeWithTag("agent-confirm-import").performClick()

                rule.waitUntil(10_000) { importedLaunch != null }
                val launch = requireNotNull(importedLaunch)
                assertEquals("plan", launch.initialTab)
                assertTrue("Imported planSteps should be non-empty", launch.initialPlanSteps.isNotEmpty())
                assertTrue(
                    "Exam-skip filter minTotalGain=3 should include SKIP steps in workbench plan",
                    launch.initialPlanSteps.any { it.kind == com.fireemblem.companion.engine.SeedPlanActionKind.SKIP },
                )

                // Verify persisted into PersonalStore
                val saved = PersonalStore(testContext).snapshot().heads().values
                    .mapNotNull { it.singleOrNull()?.takeIf { r -> !r.deleted && r.kind == SeedWorkbenchRecord.KIND } }
                assertEquals(1, saved.size)
            }
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun chatModeExecutesRealLocalToolsAndRendersCitationsAndParetoCard() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val tempRoot = Files.createTempDirectory(target.cacheDir.toPath(), "agent-ui-chat-").toFile()
        val testContext = isolatedContext(tempRoot)
        var openedCitation: AgentWikiCitation? = null
        var turn = 0
        val fakeClient = object : AgentLlmClient {
            override suspend fun complete(
                systemPrompt: String,
                messages: List<AgentTurnMessage>,
                tools: List<AgentToolDefinition>,
                onTextDelta: (String) -> Unit,
            ): AgentLlmResponse {
                turn++
                return if (turn == 1) {
                    AgentLlmResponse(
                        text = "",
                        toolCalls = listOf(
                            AgentToolCall(
                                id = "c_wiki",
                                name = CompanionAgentTools.TOOL_SEARCH_WIKI,
                                arguments = buildJsonObject {
                                    put("query", "凯伊")
                                    put("game", "fortunes-weave")
                                },
                            ),
                            AgentToolCall(
                                id = "c_plan",
                                name = CompanionAgentTools.TOOL_SOLVE_PARETO_GROWTH_PLAN,
                                arguments = buildJsonObject {
                                    put("character", "c044")
                                    put("seed", 123456789L)
                                    put("current_level", 20)
                                    put("target_level", 26)
                                    put("include_locked_preview", true)
                                },
                            ),
                        ),
                        stopReason = "tool_use",
                    )
                } else if (systemPrompt.contains("记忆整理引擎")) {
                    // Automatic background memory extraction call!
                    AgentLlmResponse(
                        text = """[{"category":"preference","content":"凯伊篇自动提炼偏好：优先力量成长"}]""",
                        stopReason = "end_turn",
                    )
                } else {
                    val reply = """
已查到**凯伊资料**并完成 Lv.20→26 转职分档最优方案：

| 方案 | 力量成长 | 说明 |
|---|---|---|
| 基准方案 | +6 | 全程不转职 |
| 最优方案 | +9 | 含跳过低点 |

可直接点击下方卡片导入工作台。
                    """.trimIndent()
                    onTextDelta(reply)
                    AgentLlmResponse(text = reply, stopReason = "end_turn")
                }
            }
        }
        try {
            val sharedSession = CompanionAgentSessionHolder()
            var showingDetail by androidx.compose.runtime.mutableStateOf(false)
            WikiDatabaseRepository.open(target).use { fwRepo ->
                rule.setContent {
                    CompositionLocalProvider(LocalContext provides testContext) {
                        FireEmblemTheme {
                            if (showingDetail) {
                                androidx.compose.material3.Button(
                                    onClick = { showingDetail = false },
                                    modifier = androidx.compose.ui.Modifier.testTag("mock-citation-back"),
                                ) {
                                    androidx.compose.material3.Text("返回战策参谋")
                                }
                            } else {
                                CompanionAgentScreen(
                                    activeGame = CompanionGame.FORTUNES_WEAVE,
                                    fwRepository = fwRepo,
                                    session = sharedSession,
                                    onBack = {},
                                    onOpenWikiCitation = {
                                        openedCitation = it
                                        showingDetail = true
                                    },
                                    onOpenWorkbench = {},
                                    llmClientOverride = fakeClient,
                                    initialModeOverride = "chat",
                                )
                            }
                        }
                    }
                }

                rule.waitUntil(10_000) {
                    rule.onAllNodesWithTag("agent-chat-input").fetchSemanticsNodes().isNotEmpty()
                }
                rule.onNodeWithTag("agent-chat-input").performTextReplacement("查凯伊并规划 20 到 26 级转职")
                rule.onNodeWithTag("agent-chat-send").performClick()

                rule.waitUntil(15_000) {
                    rule.onAllNodesWithText("已查到凯伊资料", substring = true).fetchSemanticsNodes().isNotEmpty()
                }
                // Verify automatic background memory distillation saved the preference without manual tap!
                rule.waitUntil(10_000) {
                    com.fireemblem.companion.agent.AgentMemoryStore(testContext).list()
                        .any { it.content.contains("凯伊篇自动提炼偏好") }
                }

                // Verify native Markdown table & bold rendered cleanly (no raw ** or |---|---|)
                rule.onNodeWithText("基准方案").assertIsDisplayed()
                assertTrue(
                    "Raw markdown table separator must not appear literally",
                    rule.onAllNodesWithText("|---|---|", substring = true).fetchSemanticsNodes().isEmpty(),
                )
                assertTrue(
                    "Raw bold markers must not appear literally",
                    rule.onAllNodesWithText("**凯伊资料**", substring = true).fetchSemanticsNodes().isEmpty(),
                )

                // Expand level-by-level growth preview table inside Pareto card
                rule.onNodeWithTag("agent-chat-list").performScrollToNode(hasTestTag("pareto-toggle-steps"))
                rule.onNodeWithTag("pareto-toggle-steps").performClick()
                rule.waitUntil(5_000) {
                    rule.onAllNodesWithTag("pareto-steps-table").fetchSemanticsNodes().isNotEmpty()
                }
                rule.onNodeWithTag("agent-chat-list").performScrollToNode(hasTestTag("pareto-steps-table"))
                rule.onNodeWithText("Lv.20→21").assertIsDisplayed()

                // Click citation chip -> navigate away -> return -> verify exact scroll & expanded table preserved!
                val kaiHit = com.fireemblem.companion.agent.CrossGameWikiRetriever(target, fwRepo).use { r ->
                    r.search("凯伊", gameFilter = "fortunes-weave", limit = 5).first { it.title == "凯伊" }
                }
                rule.onNodeWithTag("agent-chat-list").performScrollToNode(hasTestTag("agent-citation:${kaiHit.entryId}"))
                rule.waitForIdle()
                val idxBefore = sharedSession.chatListIndex
                val offBefore = sharedSession.chatListOffset
                rule.onNodeWithTag("agent-citation:${kaiHit.entryId}").performClick()
                assertNotNull("Clicking citation chip should invoke callback", openedCitation)
                assertEquals(kaiHit.entryId, openedCitation!!.entryId)

                rule.waitUntil(5_000) {
                    rule.onAllNodesWithTag("mock-citation-back").fetchSemanticsNodes().isNotEmpty()
                }
                rule.onNodeWithTag("mock-citation-back").performClick()
                rule.waitUntil(5_000) {
                    rule.onAllNodesWithTag("agent-chat-list").fetchSemanticsNodes().isNotEmpty()
                }
                rule.waitForIdle()
                assertEquals("Chat list index must be preserved on return", idxBefore, sharedSession.chatListIndex)
                assertEquals("Chat list pixel offset must be preserved on return", offBefore, sharedSession.chatListOffset)
                assertTrue(
                    "Expanded level-by-level preview must remain expanded after return",
                    sharedSession.expandedProposalSteps.values.any { it },
                )

                // Verify New Chat creates a fresh session while History retains the previous one
                rule.onNodeWithTag("agent-new-chat").performClick()
                rule.waitUntil(5_000) {
                    rule.onAllNodesWithTag("agent-starter-prompt").fetchSemanticsNodes().isNotEmpty()
                }
                rule.onNodeWithTag("agent-history-button").performClick()
                rule.waitUntil(5_000) {
                    rule.onAllNodesWithText("历史对话", substring = true).fetchSemanticsNodes().isNotEmpty()
                }
                rule.onNodeWithText("查凯伊并规划", substring = true).performClick()
                rule.waitUntil(5_000) {
                    rule.onAllNodesWithText("基准方案").fetchSemanticsNodes().isNotEmpty()
                }

                // Verify Memory Book dialog shows auto-extracted memory + switches + manual add
                rule.onNodeWithTag("agent-memory-button").performClick()
                rule.waitUntil(5_000) {
                    rule.onAllNodesWithTag("agent-memory-input").fetchSemanticsNodes().isNotEmpty()
                }
                rule.onNodeWithTag("agent-auto-memory-switch").assertIsDisplayed()
                rule.onNodeWithTag("agent-auto-compact-switch").assertIsDisplayed()
                rule.onNodeWithText("凯伊篇自动提炼偏好：优先力量成长").assertIsDisplayed()
                rule.onNodeWithTag("agent-memory-input").performTextReplacement("凯伊篇固定跳过成长低于3")
                rule.onNodeWithTag("agent-memory-add").performClick()
                rule.waitUntil(5_000) {
                    rule.onAllNodesWithText("凯伊篇固定跳过成长低于3").fetchSemanticsNodes().isNotEmpty()
                }
            }
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun threeHousesAndEngagePlannersAndFwPromotionGatingWorkEndToEnd() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val tempRoot = Files.createTempDirectory(target.cacheDir.toPath(), "agent-3games-").toFile()
        val testContext = isolatedContext(tempRoot)
        val shotDir = File(target.getExternalFilesDir(null), "agent_3games_accept").apply {
            deleteRecursively()
            mkdirs()
        }
        fun capture(name: String) {
            rule.waitForIdle()
            Thread.sleep(400)
            val bmp = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            if (bmp != null) {
                File(shotDir, "$name.png").outputStream().use { out ->
                    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                }
                bmp.recycle()
            }
        }

        try {
            // 1. Direct deterministic tool verification against REAL bundled SQLite packs (FW + 3H + Engage)
            WikiDatabaseRepository.open(target).use { fwRepo ->
                com.fireemblem.companion.agent.CrossGameWikiRetriever(target, fwRepo).use { retriever ->
                    val executor = com.fireemblem.companion.agent.CompanionToolExecutor(testContext, retriever)
                    kotlinx.coroutines.runBlocking {
                        // A) Fortune's Weave: Pietro Lv.5 平民 with ONLY 平民 unlocked must NOT switch at Lv.5!
                        val fwOut = executor.execute(
                            AgentToolCall(
                                id = "c-fw",
                                name = CompanionAgentTools.TOOL_SOLVE_PARETO_GROWTH_PLAN,
                                arguments = buildJsonObject {
                                    put("character", "皮特鲁")
                                    put("seed", 1_090_197_388L)
                                    put("line", "kai")
                                    put("current_level", 5)
                                    put("target_level", 25)
                                    put("initial_profession", "平民")
                                    put("include_locked_preview", true)
                                    put("weights", buildJsonObject { put("力量", 5); put("速度", 3) })
                                    put("max_switch_budget", 4)
                                },
                            ),
                        )
                        assertTrue("FW plan must succeed: ${fwOut.summary}", !fwOut.isError)
                        assertTrue("Summary must use 转职分档方案 not 帕累托", fwOut.summary.contains("转职分档方案"))
                        val fwReport = fwOut.planProposal!!.report
                        assertEquals("Early Lv.5 平民 must conservatively gate locked promotion to Lv.10", 10, fwReport.effectiveMinPromotionLevel)
                        fwReport.paretoTiers.forEach { tier ->
                            assertTrue(
                                "Must NEVER suggest switching to 斗士 at Lv.5 when promotion isn't unlocked: ${tier.switchEvents}",
                                tier.switchEvents.all { it.atLevel >= 10 },
                            )
                        }

                        // B) Three Houses: Ingrid Lv.1->40 with Lv.10 Armored Knight cert dip on REAL pack.db
                        val thOut = executor.execute(
                            AgentToolCall(
                                id = "c-3h",
                                name = CompanionAgentTools.TOOL_PLAN_THREE_HOUSES_GROWTH,
                                arguments = buildJsonObject {
                                    put("character", "英谷莉特")
                                    put("target_level", 40)
                                    put("weights", buildJsonObject { put("力量", 5); put("速度", 4); put("防守", 2) })
                                    put(
                                        "cert_dips",
                                        kotlinx.serialization.json.buildJsonArray {
                                            add(buildJsonObject { put("at_level", 10); put("class_name", "重甲骑士") })
                                        },
                                    )
                                },
                            ),
                        )
                        assertTrue("3H plan must succeed: ${thOut.summary}", !thOut.isError)
                        val thReport = thOut.threeHousesProposal!!.report
                        assertEquals(39, thReport.levelRows.size)
                        assertTrue("Ingrid must use 2-stat reroll guarantee", thReport.usedTwoStatReroll)
                        assertTrue("3H plan must have >= 3 progression stages", thReport.stages.size >= 3)
                        assertTrue(
                            "No NPC/enemy classes like Lord (Judith) or Ruffian may appear: ${thReport.stages.map { it.className }}",
                            thReport.stages.none { it.className.contains("茱迪特") || it.className == "暴徒" || it.className == "叛逆者" },
                        )
                        assertTrue(
                            "Armored Knight Lv.10 cert dip must trigger Def floor bump",
                            thReport.certEvents.any { it.isCertDipOnly && it.className == "重骑士" && it.totalFloorGain > 2.0 },
                        )

                        // C) Engage: Alear Fixed Growth + Jean Expertise 2x on REAL pack.db
                        val egOut = executor.execute(
                            AgentToolCall(
                                id = "c-eg",
                                name = CompanionAgentTools.TOOL_PLAN_ENGAGE_FIXED_GROWTH,
                                arguments = buildJsonObject {
                                    put("character", "琉尔")
                                    put("total_level_ups", 25)
                                    put("starsphere", true)
                                    put(
                                        "stages",
                                        kotlinx.serialization.json.buildJsonArray {
                                            add(buildJsonObject { put("class_name", "神龙之子"); put("levels", 9); put("starsphere", true) })
                                            add(buildJsonObject { put("class_name", "神龙之王"); put("levels", 16); put("starsphere", true) })
                                        },
                                    )
                                },
                            ),
                        )
                        assertTrue("Engage plan must succeed: ${egOut.summary}", !egOut.isError)
                        val egReport = egOut.engageProposal!!.report
                        assertEquals(25, egReport.levelSteps.size)
                        assertEquals("神龙之王", egReport.finalClassName)
                        assertTrue("Starsphere must be enabled", egReport.usedStarsphere)
                        assertEquals(1, egReport.classChanges.size)
                    }
                }

                // 2. UI verification: Fake LLM invokes FW + 3H + Engage tools -> cards + expandable tables render cleanly
                var round = 0
                val fakeClient = object : AgentLlmClient {
                    override suspend fun complete(
                        systemPrompt: String,
                        messages: List<AgentTurnMessage>,
                        tools: List<AgentToolDefinition>,
                        onTextDelta: (String) -> Unit,
                    ): AgentLlmResponse {
                        round++
                        return if (round == 1) {
                            AgentLlmResponse(
                                text = "",
                                toolCalls = listOf(
                                    AgentToolCall(
                                        id = "tc-fw",
                                        name = CompanionAgentTools.TOOL_SOLVE_PARETO_GROWTH_PLAN,
                                        arguments = buildJsonObject {
                                            put("character", "皮特鲁")
                                            put("seed", 1_090_197_388L)
                                            put("line", "kai")
                                            put("current_level", 5)
                                            put("target_level", 25)
                                            put("initial_profession", "平民")
                                            put("weights", buildJsonObject { put("力量", 5); put("速度", 3) })
                                        },
                                    ),
                                    AgentToolCall(
                                        id = "tc-3h",
                                        name = CompanionAgentTools.TOOL_PLAN_THREE_HOUSES_GROWTH,
                                        arguments = buildJsonObject {
                                            put("character", "英谷莉特")
                                            put("target_level", 40)
                                            put("weights", buildJsonObject { put("力量", 5); put("速度", 4); put("防守", 2) })
                                            put(
                                                "cert_dips",
                                                kotlinx.serialization.json.buildJsonArray {
                                                    add(buildJsonObject { put("at_level", 10); put("class_name", "重甲骑士") })
                                                },
                                            )
                                        },
                                    ),
                                    AgentToolCall(
                                        id = "tc-eg",
                                        name = CompanionAgentTools.TOOL_PLAN_ENGAGE_FIXED_GROWTH,
                                        arguments = buildJsonObject {
                                            put("character", "琉尔")
                                            put("starsphere", true)
                                            put(
                                                "stages",
                                                kotlinx.serialization.json.buildJsonArray {
                                                    add(buildJsonObject { put("class_name", "神龙之子"); put("levels", 9); put("starsphere", true) })
                                                    add(buildJsonObject { put("class_name", "神龙之王"); put("levels", 16); put("starsphere", true) })
                                                },
                                            )
                                        },
                                    ),
                                ),
                                stopReason = "tool_use",
                            )
                        } else {
                            val reply = "已完成三部作品规划：\n- 《万缕千丝》皮特鲁已生成**转职分档方案**（未解锁初级职业默认 Lv.10 起，标注考试条件）。\n- 《风花雪月》英谷莉特 Lv.1→40 期望转职路线已生成。\n- 《Engage》琉尔固定成长 25 级精确面板已生成。"
                            onTextDelta(reply)
                            AgentLlmResponse(text = reply)
                        }
                    }
                }

                rule.setContent {
                    CompositionLocalProvider(LocalContext provides testContext) {
                        FireEmblemTheme {
                            CompanionAgentScreen(
                                activeGame = CompanionGame.THREE_HOUSES,
                                fwRepository = fwRepo,
                                onBack = {},
                                onOpenWikiCitation = {},
                                onOpenWorkbench = {},
                                llmClientOverride = fakeClient,
                                initialModeOverride = "chat",
                            )
                        }
                    }
                }

                rule.waitUntil(10_000) {
                    rule.onAllNodesWithTag("agent-chat-input").fetchSemanticsNodes().isNotEmpty()
                }
                rule.onNodeWithTag("agent-chat-input").performTextReplacement("同时帮我规划万缕千丝皮特鲁、风花雪月英谷莉特和Engage琉尔")
                rule.onNodeWithTag("agent-chat-send").performClick()

                rule.waitUntil(25_000) {
                    rule.onAllNodesWithTag("engage-proposal-card").fetchSemanticsNodes().isNotEmpty()
                }

                // Verify ZERO "帕累托" jargon on screen!
                assertTrue(
                    "UI must never show 帕累托 jargon",
                    rule.onAllNodesWithText("帕累托", substring = true).fetchSemanticsNodes().isEmpty(),
                )

                // Capture FW Tiered Plan card (no Lv.5 switch, shows 转职分档方案 + 需考试解锁)
                rule.onNodeWithTag("agent-chat-list").performScrollToNode(hasTestTag("pareto-proposal-card"))
                assertTrue(
                    "Tiered plan heading 转职分档方案 must be rendered",
                    rule.onAllNodesWithText("转职分档方案", substring = true).fetchSemanticsNodes().isNotEmpty(),
                )
                capture("01_fw_tiered_no_lv5_switch")

                // Scroll to Three Houses card & expand level-by-level preview table!
                rule.onNodeWithTag("agent-chat-list").performScrollToNode(hasTestTag("th-toggle-steps"))
                rule.onNodeWithTag("th-toggle-steps").performClick()
                rule.waitUntil(5_000) {
                    rule.onAllNodesWithTag("th-steps-table").fetchSemanticsNodes().isNotEmpty()
                }
                rule.onNodeWithTag("agent-chat-list").performScrollToNode(hasTestTag("th-steps-table"))
                capture("02_three_houses_card_expanded")

                // Scroll to Engage Fixed Growth card & expand level-by-level table!
                rule.onNodeWithTag("agent-chat-list").performScrollToNode(hasTestTag("engage-toggle-steps"))
                rule.onNodeWithTag("engage-toggle-steps").performClick()
                rule.waitUntil(5_000) {
                    rule.onAllNodesWithTag("engage-steps-table").fetchSemanticsNodes().isNotEmpty()
                }
                rule.onNodeWithTag("agent-chat-list").performScrollToNode(hasTestTag("engage-steps-table"))
                capture("03_engage_fixed_card_expanded")

                // Switch to Offline mode -> test 3H & Engage offline planners directly!
                rule.onNodeWithTag("agent-mode:offline").performClick()
                rule.waitUntil(5_000) {
                    rule.onAllNodesWithTag("agent-offline-list").fetchSemanticsNodes().isNotEmpty()
                }
                rule.onNodeWithTag("agent-offline-list").performScrollToNode(hasTestTag("agent-offline-planner-tab:three-houses"))
                rule.onNodeWithTag("agent-offline-planner-tab:three-houses").performClick()
                rule.waitUntil(10_000) {
                    runCatching {
                        rule.onNodeWithTag("agent-offline-list").performScrollToNode(hasTestTag("th-planner-solve"))
                        true
                    }.getOrDefault(false)
                }
                rule.onNodeWithTag("th-planner-solve").performClick()
                rule.waitUntil(10_000) {
                    runCatching {
                        rule.onNodeWithTag("agent-offline-list").performScrollToNode(hasTestTag("th-proposal-card"))
                        true
                    }.getOrDefault(false)
                }
                capture("04_offline_three_houses_planner")

                rule.onNodeWithTag("agent-offline-list").performScrollToNode(hasTestTag("agent-offline-planner-tab:engage"))
                rule.onNodeWithTag("agent-offline-planner-tab:engage").performClick()
                rule.waitUntil(10_000) {
                    runCatching {
                        rule.onNodeWithTag("agent-offline-list").performScrollToNode(hasTestTag("engage-planner-solve"))
                        true
                    }.getOrDefault(false)
                }
                rule.onNodeWithTag("engage-planner-solve").performClick()
                rule.waitUntil(10_000) {
                    runCatching {
                        rule.onNodeWithTag("agent-offline-list").performScrollToNode(hasTestTag("engage-proposal-card"))
                        true
                    }.getOrDefault(false)
                }
                capture("05_offline_engage_planner")
            }
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    /**
     * Opt-in live E2E acceptance test driven via instrumentation args (`-e live_base_url ... -e live_api_key ... -e live_model ...`).
     * Automatically skipped in regular test runs when no live_api_key argument is passed.
     */
    @Test
    fun liveLanAgentEndToEndAcceptance() {
        val args = InstrumentationRegistry.getArguments()
        val baseUrl = args.getString("live_base_url").orEmpty().trim()
        val apiKey = args.getString("live_api_key").orEmpty().trim()
        val modelId = args.getString("live_model").orEmpty().trim()
        org.junit.Assume.assumeTrue("Skipping live LAN E2E test (no live_api_key arg provided)", baseUrl.isNotEmpty() && modelId.isNotEmpty())

        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val tempRoot = Files.createTempDirectory(target.cacheDir.toPath(), "agent-live-e2e-").toFile()
        val testContext = isolatedContext(tempRoot)
        val shotDir = File(target.getExternalFilesDir(null), "agent_v2_accept").apply {
            deleteRecursively()
            mkdirs()
        }
        fun capture(name: String) {
            rule.waitForIdle()
            Thread.sleep(500)
            val bmp = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            if (bmp != null) {
                File(shotDir, "$name.png").outputStream().use { out ->
                    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                }
                bmp.recycle()
            }
        }

        try {
            // Seed realistic SeedWorkbenchRecord matching user's tablet character (皮特鲁 Lv.5, 平民, seed 1090197388)
            // plus a 2-route RouteMergeCharacter so dynamic starter prompts showcase personalized save-grounded recommendations!
            val wb = SeedWorkbenchRecord(
                profileId = "global",
                characterId = "c006", // 皮特鲁
                line = com.fireemblem.companion.engine.BuddyCampaignLine.KAI,
                part3 = false,
                baseSeed = 1_090_197_388L,
                inferredLevel = 5,
                startLevel = 5,
                extraAdvances = 0,
                profession = "平民",
                ride = null,
                unlockedProfessions = setOf("平民", "斗士", "土匪", "勇士"),
                weights = listOf(1, 5, 0, 2, 3, 1, 1, 0, 1),
                planSteps = emptyList(),
                previewOverrides = emptyMap(),
                completedSteps = 0,
            )
            PersonalStore(testContext).edit(
                entityId = SeedWorkbenchRecord.entityId("global", wb.characterId, wb.line),
                kind = SeedWorkbenchRecord.KIND,
                payload = wb.encode(),
                deleted = false,
                expectedParents = emptySet(),
            )
            val rm = com.fireemblem.companion.personal.RouteMergeCharacter(
                profileId = "global",
                characterId = "characters:demo_nine",
                characterName = "妮涅",
            ).withSnapshot(
                com.fireemblem.companion.personal.RouteMergeSnapshot(
                    com.fireemblem.companion.engine.BuddyCampaignLine.KAI, 42, "勇士",
                    listOf(52, 36, 18, 29, 24, 25, 28, 22, 20),
                ),
            ).withSnapshot(
                com.fireemblem.companion.personal.RouteMergeSnapshot(
                    com.fireemblem.companion.engine.BuddyCampaignLine.SEO, 46, "卫士",
                    listOf(56, 33, 20, 28, 16, 28, 29, 32, 22),
                ),
            )
            PersonalStore(testContext).edit(
                entityId = com.fireemblem.companion.personal.RouteMergeCharacter.entityId("global", rm.characterId),
                kind = com.fireemblem.companion.personal.RouteMergeCharacter.KIND,
                payload = rm.payload(),
                deleted = false,
                expectedParents = emptySet(),
            )

            // Configure 2 role-routed profiles (Main Chat + Memory/Compaction) pointing at user's LAN gateway
            val chatProfile = com.fireemblem.companion.agent.AgentModelProfile(
                id = "lan-main",
                name = "主力参谋 · luna",
                provider = com.fireemblem.companion.agent.AgentProviderKind.OPENAI_COMPAT,
                presetId = com.fireemblem.companion.agent.AgentProviderConfig.PRESET_CUSTOM.id,
                baseUrl = baseUrl,
                apiKey = apiKey,
                modelId = modelId,
            )
            val memProfile = com.fireemblem.companion.agent.AgentModelProfile(
                id = "lan-mem",
                name = "记忆整理 · luna",
                provider = com.fireemblem.companion.agent.AgentProviderKind.OPENAI_COMPAT,
                presetId = com.fireemblem.companion.agent.AgentProviderConfig.PRESET_CUSTOM.id,
                baseUrl = baseUrl,
                apiKey = apiKey,
                modelId = modelId,
            )
            com.fireemblem.companion.agent.SecureAgentSettings(testContext).saveRegistry(
                com.fireemblem.companion.agent.AgentModelRegistry(
                    profiles = listOf(chatProfile, memProfile),
                    chatProfileId = chatProfile.id,
                    memoryProfileId = memProfile.id,
                ),
            )

            WikiDatabaseRepository.open(target).use { fwRepo ->
                rule.setContent {
                    CompositionLocalProvider(LocalContext provides testContext) {
                        FireEmblemTheme {
                            CompanionAgentScreen(
                                activeGame = CompanionGame.FORTUNES_WEAVE,
                                fwRepository = fwRepo,
                                onBack = {},
                                onOpenWikiCitation = {},
                                onOpenWorkbench = {},
                            )
                        }
                    }
                }

                // 1. Dynamic data-driven starter prompts grounded in user's皮特鲁 checkpoint
                rule.waitUntil(15_000) {
                    rule.onAllNodesWithText("皮特鲁", substring = true).fetchSemanticsNodes().isNotEmpty()
                }
                capture("01_dynamic_starters")

                // 2. Real LAN LLM call with exam-skip (min_total_gain=3) + Markdown table response
                val livePrompt = "帮我查看存档里皮特鲁的种子，按力量优先且跳过总成长小于3的加点，规划到30级的最少转职方案，并用Markdown表格对比关键方案"
                rule.onNodeWithTag("agent-chat-input").performTextReplacement(livePrompt)
                rule.onNodeWithTag("agent-chat-send").performClick()

                // Wait for real model tool calls + final reply + Pareto card
                rule.waitUntil(90_000) {
                    rule.onAllNodesWithTag("agent-chat-send").fetchSemanticsNodes().isNotEmpty() &&
                        rule.onAllNodesWithTag("agent-fork:2").fetchSemanticsNodes().isNotEmpty()
                }
                assertTrue(
                    "Raw markdown separator must not be displayed verbatim",
                    rule.onAllNodesWithText("|---|---|", substring = true).fetchSemanticsNodes().isEmpty(),
                )
                capture("02_live_chat_markdown_skip_plan")

                // 3. Scroll down to full Pareto skip card & expand level-by-level preview table!
                val hasToggle = runCatching {
                    rule.onNodeWithTag("agent-chat-list").performScrollToNode(hasTestTag("pareto-import-workbench"))
                    rule.onNodeWithTag("agent-chat-list").performScrollToNode(hasTestTag("pareto-toggle-steps"))
                    true
                }.getOrDefault(false)
                if (hasToggle) {
                    rule.onNodeWithTag("pareto-toggle-steps").performClick()
                    rule.waitUntil(5_000) {
                        rule.onAllNodesWithTag("pareto-steps-table").fetchSemanticsNodes().isNotEmpty()
                    }
                    rule.onNodeWithTag("agent-chat-list").performScrollToNode(hasTestTag("pareto-steps-table"))
                    capture("08_pareto_level_by_level_preview")
                }

                // 4. Wait for automatic background Memory Model distillation & capture Memory Book dialog!
                rule.waitUntil(45_000) {
                    com.fireemblem.companion.agent.AgentMemoryStore(testContext).list().isNotEmpty()
                }
                rule.onNodeWithTag("agent-memory-button").performClick()
                rule.waitUntil(5_000) {
                    rule.onAllNodesWithText("参谋记忆本", substring = true).fetchSemanticsNodes().isNotEmpty()
                }
                capture("09_auto_memory_and_compact_settings")
                rule.onNodeWithText("完成").performClick()

                // 5. New Chat + History Session switcher
                rule.onNodeWithTag("agent-new-chat").performClick()
                rule.waitUntil(5_000) {
                    rule.onAllNodesWithTag("agent-starter-prompt").fetchSemanticsNodes().isNotEmpty()
                }
                rule.onNodeWithTag("agent-history-button").performClick()
                rule.waitUntil(5_000) {
                    rule.onAllNodesWithText("历史对话", substring = true).fetchSemanticsNodes().isNotEmpty()
                }
                capture("05_live_history_sessions")
                rule.onNodeWithText("关闭").performClick()

                // 6. Multi-Model Registry dialog
                rule.onNodeWithTag("agent-config-button").performClick()
                rule.waitUntil(5_000) {
                    rule.onAllNodesWithText("多模型配置", substring = true).fetchSemanticsNodes().isNotEmpty()
                }
                capture("06_live_multi_model_config")
                rule.onNodeWithText("完成").performClick()

                // 7. Offline Exam-Skip Planner
                rule.onNodeWithTag("agent-mode:offline").performClick()
                rule.waitUntil(5_000) {
                    rule.onAllNodesWithTag("agent-offline-list").fetchSemanticsNodes().isNotEmpty()
                }
                rule.onNodeWithTag("agent-offline-list").performScrollToNode(hasTestTag("agent-min-gain:3"))
                rule.onNodeWithTag("agent-min-gain:3").performClick()
                rule.onNodeWithTag("agent-offline-list").performScrollToNode(hasTestTag("agent-solve-pareto"))
                rule.onNodeWithTag("agent-solve-pareto").performClick()
                rule.waitUntil(15_000) {
                    rule.onAllNodesWithTag("pareto-proposal-card").fetchSemanticsNodes().isNotEmpty()
                }
                rule.onNodeWithTag("agent-offline-list").performScrollToNode(hasTestTag("pareto-import-workbench"))
                capture("07_offline_skip_planner")
            }
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
