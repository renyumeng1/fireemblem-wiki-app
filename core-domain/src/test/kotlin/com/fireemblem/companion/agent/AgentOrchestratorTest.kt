package com.fireemblem.companion.agent

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentOrchestratorTest {
    private fun <T> runSuspend(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(result: Result<T>) {
                    outcome = result
                }
            },
        )
        return checkNotNull(outcome) { "Suspend block did not complete synchronously" }.getOrThrow()
    }

    @Test
    fun executesMultiRoundToolCallsAndCollectsDeduplicatedCitations() = runSuspend {
        var round = 0
        val fakeClient = object : AgentLlmClient {
            override suspend fun complete(
                systemPrompt: String,
                messages: List<AgentTurnMessage>,
                tools: List<AgentToolDefinition>,
                onTextDelta: (String) -> Unit,
            ): AgentLlmResponse {
                round++
                return when (round) {
                    1 -> AgentLlmResponse(
                        text = "",
                        toolCalls = listOf(
                            AgentToolCall(
                                id = "tc_1",
                                name = CompanionAgentTools.TOOL_SEARCH_WIKI,
                                arguments = buildJsonObject { put("query", "圣天马骑士") },
                            ),
                        ),
                        stopReason = "tool_use",
                    )
                    2 -> {
                        val answer = "已查到风花雪月「圣天马骑士」的转职条件与成长加成。"
                        onTextDelta(answer)
                        AgentLlmResponse(text = answer, stopReason = "end_turn")
                    }
                    else -> error("Unexpected round $round")
                }
            }
        }
        val fakeExecutor = object : AgentToolExecutor {
            override suspend fun execute(call: AgentToolCall): AgentToolExecutionOutcome {
                val hit = AgentWikiCitation(
                    gameId = "three-houses",
                    gameTitle = "风花雪月",
                    categoryId = "classes",
                    entryId = "classes:falcon-knight",
                    title = "圣天马骑士",
                    subtitle = "最高级",
                )
                return AgentToolExecutionOutcome(
                    outputJson = buildJsonObject { put("count", 1) },
                    summary = "检索《风花雪月》命中 1 条",
                    citations = listOf(hit, hit), // duplicate should be deduped
                )
            }
        }

        val streamed = StringBuilder()
        val result = CompanionAgentOrchestrator(fakeClient, fakeExecutor).runTurn(
            systemPrompt = "sys",
            priorHistory = emptyList(),
            userPrompt = "查圣天马骑士",
            onTextDelta = { streamed.append(it) },
        )

        assertFalse(result.stoppedByBudget)
        assertEquals(1, result.toolTraces.size)
        assertEquals(1, result.citations.size)
        assertEquals("圣天马骑士", result.citations.single().title)
        assertEquals(result.assistantText, streamed.toString())
        assertTrue(result.updatedHistory.size == 4)
    }

    @Test
    fun enforcesToolRoundBudgetAndSynthesizesFinalReply() = runSuspend {
        var calls = 0
        val loopingClient = object : AgentLlmClient {
            override suspend fun complete(
                systemPrompt: String,
                messages: List<AgentTurnMessage>,
                tools: List<AgentToolDefinition>,
                onTextDelta: (String) -> Unit,
            ): AgentLlmResponse {
                calls++
                return if (tools.isEmpty()) {
                    AgentLlmResponse(text = "已达最大工具轮次，整理当前结果。", stopReason = "end_turn")
                } else {
                    AgentLlmResponse(
                        text = "",
                        toolCalls = listOf(
                            AgentToolCall("tc_$calls", CompanionAgentTools.TOOL_SEARCH_WIKI, buildJsonObject { put("query", "凯伊") }),
                        ),
                        stopReason = "tool_use",
                    )
                }
            }
        }
        val failingOnceExecutor = object : AgentToolExecutor {
            override suspend fun execute(call: AgentToolCall): AgentToolExecutionOutcome =
                if (call.id == "tc_1") throw IllegalArgumentException("bad arg")
                else AgentToolExecutionOutcome(buildJsonObject { put("ok", JsonPrimitive(true)) }, "ok")
        }

        val result = CompanionAgentOrchestrator(loopingClient, failingOnceExecutor, maxToolRounds = 2).runTurn(
            systemPrompt = "sys",
            priorHistory = emptyList(),
            userPrompt = "loop",
        )

        assertTrue(result.stoppedByBudget)
        assertEquals(2, result.toolTraces.size)
        assertTrue(result.toolTraces.first().isError)
        assertEquals("已达最大工具轮次，整理当前结果。", result.assistantText)
    }
}
