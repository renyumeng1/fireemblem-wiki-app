package com.fireemblem.companion.agent

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.fireemblem.companion.engine.BuddyCampaignLine
import com.fireemblem.companion.engine.BuddyRules
import com.fireemblem.companion.personal.PersonalStore
import com.fireemblem.companion.personal.RouteMergeCharacter
import com.fireemblem.companion.personal.RouteMergeSnapshot
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentClientAndSettingsTest {
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
    fun keystoreRoundTripEncryptsCredentialsAndNeverStoresPlaintext() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val tempRoot = Files.createTempDirectory(target.cacheDir.toPath(), "agent-sec-").toFile()
        val ctx = isolatedContext(tempRoot)
        try {
            val settings = SecureAgentSettings(ctx)
            assertNull(settings.load())

            val secretKey = "unit-test-anthropic-key-999999999999"
            val cfg = AgentProviderConfig(
                provider = AgentProviderKind.ANTHROPIC,
                presetId = AgentProviderConfig.PRESET_CLAUDE.id,
                baseUrl = "https://api.anthropic.com",
                apiKey = secretKey,
                modelId = "claude-opus-5-5",
                thinkingEffort = "high",
            )
            settings.save(cfg)

            // Verify raw SharedPreferences does NOT contain plaintext API key
            val rawPref = ctx.getSharedPreferences("agent-credentials", Context.MODE_PRIVATE)
                .getString("encrypted", "")
                .orEmpty()
            assertTrue(rawPref.isNotEmpty())
            assertFalse("Plaintext key must never appear in SharedPreferences", rawPref.contains(secretKey))

            val loaded = settings.load()
            assertNotNull(loaded)
            assertEquals(AgentProviderKind.ANTHROPIC, loaded!!.provider)
            assertEquals(secretKey, loaded.apiKey)
            assertEquals("claude-opus-5-5", loaded.modelId)
            assertEquals("high", loaded.thinkingEffort)

            settings.clear()
            assertNull(settings.load())
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun crossGameWikiRetrieverSearchesAndFetchesAcrossAllThreeGames() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        CrossGameWikiRetriever(target).use { retriever ->
            val fwHits = retriever.search("凯伊", gameFilter = "fortunes-weave", limit = 5)
            assertTrue("Expected Fortunes Weave hits for 凯伊", fwHits.isNotEmpty())
            val fwDetail = retriever.getEntryDetail("fortunes-weave", fwHits.first().entryId)
            assertNotNull(fwDetail)
            assertTrue(fwDetail!!.summaryText.contains("凯伊"))

            val thHits = retriever.search("圣天马骑士", gameFilter = "three-houses", limit = 5)
            assertTrue("Expected Three Houses hits for 圣天马骑士", thHits.isNotEmpty())
            val thDetail = retriever.getEntryDetail("three-houses", thHits.first().entryId)
            assertNotNull(thDetail)
            assertTrue(thDetail!!.summaryText.contains("圣天马骑士"))

            val engageHits = retriever.search("琉尔", gameFilter = "engage", limit = 5)
            assertTrue("Expected Engage hits for 琉尔", engageHits.isNotEmpty())

            val allHits = retriever.search("剑", gameFilter = "all", limit = 12)
            assertTrue("All-game search should return hits", allHits.isNotEmpty())
        }
    }

    @Test
    fun openAiCompatClientStreamsMultiRoundToolLoopAgainstHttpsMockServer() = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val cert = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverCerts = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientCerts = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        val server = MockWebServer().apply { useHttps(serverCerts.sslSocketFactory(), false) }
        val httpClient = OkHttpClient.Builder()
            .sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager)
            .build()

        val roundCounter = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val r = roundCounter.incrementAndGet()
                return if (r == 1) {
                    val sse1 = listOf(
                        """data: {"choices":[{"index":0,"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"call_wiki_1","type":"function","function":{"name":"search_wiki","arguments":"{\"query\":\"圣天马骑士\","}}]},"finish_reason":null}]}""",
                        """data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"game\":\"three-houses\"}"}}]},"finish_reason":"tool_calls"}]}""",
                        "data: [DONE]",
                    ).joinToString("\n\n", postfix = "\n\n")
                    MockResponse().setResponseCode(200).addHeader("Content-Type", "text/event-stream").setBody(sse1)
                } else {
                    val sse2 = listOf(
                        """data: {"choices":[{"index":0,"delta":{"content":"已从离线图鉴查到《风花雪月》"},"finish_reason":null}]}""",
                        """data: {"choices":[{"index":0,"delta":{"content":"圣天马骑士资料。"},"finish_reason":"stop"}]}""",
                        "data: [DONE]",
                    ).joinToString("\n\n", postfix = "\n\n")
                    MockResponse().setResponseCode(200).addHeader("Content-Type", "text/event-stream").setBody(sse2)
                }
            }
        }
        server.start()
        try {
            val cfg = AgentProviderConfig(
                provider = AgentProviderKind.OPENAI_COMPAT,
                presetId = AgentProviderConfig.PRESET_DEEPSEEK.id,
                baseUrl = server.url("/v1").toString(),
                apiKey = "sk-test",
                modelId = "deepseek-chat",
            )
            val client = OpenAiCompatAgentClient(cfg, httpClient)
            CrossGameWikiRetriever(target).use { retriever ->
                val executor = CompanionToolExecutor(target, retriever)
                val orchestrator = CompanionAgentOrchestrator(client, executor)
                val streamed = StringBuilder()
                val result = orchestrator.runTurn(
                    systemPrompt = CompanionAgentTools.buildSystemPrompt("three-houses", "风花雪月"),
                    priorHistory = emptyList(),
                    userPrompt = "查圣天马骑士",
                    onTextDelta = { streamed.append(it) },
                )
                assertEquals(2, roundCounter.get())
                assertEquals("已从离线图鉴查到《风花雪月》圣天马骑士资料。", result.assistantText)
                assertEquals(result.assistantText, streamed.toString())
                assertTrue("Real offline SQLite citation expected", result.citations.any { it.title.contains("圣天马骑士") })
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun toolExecutorSolvesParetoPlanComparesClassesAndReadsRouteMerge() = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val tempRoot = Files.createTempDirectory(target.cacheDir.toPath(), "agent-tools-").toFile()
        val ctx = isolatedContext(tempRoot)
        try {
            val rules = BuddyRules.bundled()
            val character = rules.characters.first { !it.part3Only }
            val legal = rules.professions.filter { rules.classAllowed(character.id, it.name, false) }.map { it.name }

            // Seed a RouteMergeCharacter in isolated store
            val store = PersonalStore(ctx)
            val rm = RouteMergeCharacter(
                profileId = "global",
                characterId = "characters:demo",
                characterName = "妮涅",
            ).withSnapshot(
                RouteMergeSnapshot(BuddyCampaignLine.SEO, 46, "卫士", listOf(56, 33, 20, 28, 16, 28, 29, 32, 22)),
            )
            store.edit(RouteMergeCharacter.entityId("global", rm.characterId), RouteMergeCharacter.KIND, rm.payload(), false, emptySet())

            CrossGameWikiRetriever(target).use { retriever ->
                val executor = CompanionToolExecutor(ctx, retriever)

                val paretoOut = executor.execute(
                    AgentToolCall(
                        id = "p1",
                        name = CompanionAgentTools.TOOL_SOLVE_PARETO_GROWTH_PLAN,
                        arguments = buildJsonObject {
                            put("character", character.id)
                            put("seed", 123456789L)
                            put("current_level", 20)
                            put("target_level", 28)
                            put("initial_profession", legal.first())
                            put("include_locked_preview", true)
                            put("max_switch_budget", 3)
                            putJsonObject("weights") {
                                put("力量", 5)
                                put("速度", 3)
                            }
                        },
                    ),
                )
                assertFalse("Pareto tool failed: ${paretoOut.outputJson}", paretoOut.isError)
                assertNotNull("Expected attached AgentPlanProposal", paretoOut.planProposal)
                assertTrue(paretoOut.outputJson["tiers"]!!.jsonArray.isNotEmpty())

                val cmpOut = executor.execute(
                    AgentToolCall(
                        id = "c1",
                        name = CompanionAgentTools.TOOL_COMPARE_CLASSES_AT_LEVEL,
                        arguments = buildJsonObject {
                            put("character", character.id)
                            put("seed", 123456789L)
                            put("level", 23)
                            put("current_profession", legal.first())
                        },
                    ),
                )
                assertFalse(cmpOut.isError)
                assertTrue(cmpOut.outputJson["top_options"]!!.jsonArray.isNotEmpty())

                val mergeOut = executor.execute(
                    AgentToolCall(
                        id = "m1",
                        name = CompanionAgentTools.TOOL_GET_ROUTE_MERGE_SUMMARY,
                        arguments = buildJsonObject { put("character", "妮涅") },
                    ),
                )
                assertFalse(mergeOut.isError)
                assertEquals(1, mergeOut.outputJson["count"]!!.jsonPrimitive.int)

                // Verify exam-skip Pareto plan via tool executor emits SKIP steps
                val skipOut = executor.execute(
                    AgentToolCall(
                        id = "p_skip",
                        name = CompanionAgentTools.TOOL_SOLVE_PARETO_GROWTH_PLAN,
                        arguments = buildJsonObject {
                            put("character", "c006") // 皮特鲁
                            put("seed", 1_090_197_388L)
                            put("current_level", 5)
                            put("target_level", 15)
                            put("initial_profession", "平民")
                            put("include_locked_preview", true)
                            put("min_total_gain", 3)
                            put("max_skips", 15)
                            putJsonObject("weights") { put("力量", 5) }
                        },
                    ),
                )
                assertFalse("Skip Pareto tool failed: ${skipOut.outputJson}", skipOut.isError)
                val skipTier = skipOut.planProposal!!.report.recommendedTier
                assertTrue("Expected exam skip steps when min_total_gain=3", skipTier.skips > 0)
                assertTrue(skipTier.steps.all { it.gains.sum() >= 3 })

                // Verify Memory Book tools round-trip
                val saveMem = executor.execute(
                    AgentToolCall(
                        id = "mem1",
                        name = CompanionAgentTools.TOOL_SAVE_MEMORY,
                        arguments = buildJsonObject {
                            put("category", "preference")
                            put("content", "皮特鲁固定力量优先且跳过总成长小于3的加点")
                        },
                    ),
                )
                assertFalse(saveMem.isError)
                val savedId = saveMem.outputJson["id"]!!.jsonPrimitive.content
                val readMem = executor.execute(AgentToolCall("mem2", CompanionAgentTools.TOOL_READ_MEMORIES, buildJsonObject {}))
                assertEquals(1, readMem.outputJson["count"]!!.jsonPrimitive.int)
                val delMem = executor.execute(
                    AgentToolCall("mem3", CompanionAgentTools.TOOL_FORGET_MEMORY, buildJsonObject { put("memory_id", savedId) }),
                )
                assertFalse(delMem.isError)
            }
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun multiModelRegistrySupportsLanHttpRoleRoutingAndSessionForkAndCompaction() = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val tempRoot = Files.createTempDirectory(target.cacheDir.toPath(), "agent-v2-").toFile()
        val ctx = isolatedContext(tempRoot)
        try {
            val settings = SecureAgentSettings(ctx)
            // Public plaintext HTTP must be rejected
            var rejected = false
            try {
                settings.saveRegistry(
                    AgentModelRegistry(
                        profiles = listOf(
                            AgentModelProfile(
                                id = "bad",
                                name = "Bad Public HTTP",
                                baseUrl = "http://api.example.com/v1",
                                apiKey = "sk-x",
                                modelId = "test",
                            ),
                        ),
                    ),
                )
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue("Public http:// must be rejected", rejected)

            // Private LAN HTTP + HTTPS Memory Model must succeed with independent role routing
            val chatProf = AgentModelProfile(
                id = "lan-chat",
                name = "局域网主力",
                baseUrl = "http://192.168.1.100:8080/v1",
                apiKey = "sk-local-dummy",
                modelId = "gpt-test",
            )
            val memProf = AgentModelProfile(
                id = "mem-model",
                name = "记忆整理模型",
                baseUrl = "https://api.deepseek.com/v1",
                apiKey = "sk-mem-dummy",
                modelId = "deepseek-chat",
            )
            settings.saveRegistry(
                AgentModelRegistry(
                    profiles = listOf(chatProf, memProf),
                    chatProfileId = chatProf.id,
                    memoryProfileId = memProf.id,
                ),
            )
            val loaded = settings.loadRegistry()
            assertEquals(2, loaded.profiles.size)
            assertEquals("lan-chat", loaded.activeChatProfile?.id)
            assertEquals("mem-model", loaded.activeMemoryProfile?.id)

            // Session persistence + fork
            val sessionStore = AgentSessionStore(ctx)
            val s1 = sessionStore.createNewSession("新对话").copy(
                bubbles = listOf(
                    PersistedAgentBubble(1, true, "帮我规划皮特鲁路线"),
                    PersistedAgentBubble(2, false, "推荐走斗士→土匪→勇士"),
                    PersistedAgentBubble(3, true, "如果转重甲呢？"),
                    PersistedAgentBubble(4, false, "重甲力量略低但防守更高"),
                ),
                llmHistory = listOf(
                    AgentTurnMessage.User("帮我规划皮特鲁路线"),
                    AgentTurnMessage.Assistant("推荐走斗士→土匪→勇士"),
                    AgentTurnMessage.User("如果转重甲呢？"),
                    AgentTurnMessage.Assistant("重甲力量略低但防守更高"),
                ),
            )
            sessionStore.saveSession(s1)
            val reloaded = sessionStore.loadSession(s1.id)
            assertNotNull(reloaded)
            assertEquals("帮我规划皮特鲁路线", reloaded!!.title)
            assertEquals(4, reloaded.bubbles.size)

            val forked = sessionStore.forkSession(reloaded, upToBubbleId = 2)
            assertEquals(s1.id, forked.parentSessionId)
            assertEquals(2, forked.bubbles.size)
            assertEquals(2, sessionStore.listSessions().size)

            // Deterministic compaction fallback preserves recent window & summarizes older
            val shortHistory = listOf(AgentTurnMessage.User("你好"), AgentTurnMessage.Assistant("你好"))
            assertFalse("Short history must not auto-compact", AgentMemoryStore.shouldAutoCompact(null, shortHistory))
            val longHistory = (1..10).flatMap { i ->
                listOf(AgentTurnMessage.User("问题 $i"), AgentTurnMessage.Assistant("回答 $i"))
            }
            assertTrue("History >= 12 messages must trigger auto-compact", AgentMemoryStore.shouldAutoCompact(null, longHistory))
            val (summary, recent) = AgentMemoryStore.compactHistory(null, null, longHistory, keepRecentMessages = 6)
            assertTrue(summary.contains("问题 1"))
            assertEquals(6, recent.size)

            // Verify auto-memory signal detection
            assertTrue(
                "Preference keyword or plan proposal should trigger auto memory extraction",
                AgentMemoryStore.shouldAutoExtractMemories(2, 0, "帮我跳过成长小于3并力量优先规划", true),
            )
            assertFalse(
                "Already extracted at same bubble count must not re-trigger",
                AgentMemoryStore.shouldAutoExtractMemories(2, 2, "偏好力量", false),
            )
            assertTrue(
                "Every 2 exchanges (4 bubbles) should trigger periodic extraction",
                AgentMemoryStore.shouldAutoExtractMemories(6, 2, "继续看看下一级", false),
            )
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
