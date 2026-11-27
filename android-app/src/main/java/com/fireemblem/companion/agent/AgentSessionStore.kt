package com.fireemblem.companion.agent

import android.content.Context
import com.fireemblem.companion.engine.BuddyCampaignLine
import com.fireemblem.companion.engine.BuddyRules
import com.fireemblem.companion.engine.EngageFixedGrowthPlanner
import com.fireemblem.companion.engine.EngageFixedGrowthRequest
import com.fireemblem.companion.engine.EngageStageSpec
import com.fireemblem.companion.engine.EngageStat
import com.fireemblem.companion.engine.GrowthStat
import com.fireemblem.companion.engine.ParetoGrowthPlanRequest
import com.fireemblem.companion.engine.ParetoGrowthPlanner
import com.fireemblem.companion.engine.SeedWorkbenchEngine
import com.fireemblem.companion.engine.SeedWorkbenchRecord
import com.fireemblem.companion.engine.ThreeHousesCertDipSpec
import com.fireemblem.companion.engine.ThreeHousesGender
import com.fireemblem.companion.engine.ThreeHousesGrowthPlanner
import com.fireemblem.companion.engine.ThreeHousesPlanRequest
import com.fireemblem.companion.engine.ThreeHousesStageSpec
import com.fireemblem.companion.engine.ThreeHousesStat
import java.io.File
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class PersistedAgentBubble(
    val id: Int,
    val isUser: Boolean,
    val text: String,
    val toolTraces: List<AgentToolTrace> = emptyList(),
    val citations: List<AgentWikiCitation> = emptyList(),
    val proposalSpecs: List<PersistedProposalSpec> = emptyList(),
    val threeHousesSpecs: List<PersistedThreeHousesProposalSpec> = emptyList(),
    val engageSpecs: List<PersistedEngageProposalSpec> = emptyList(),
    val isError: Boolean = false,
)

/** Compact deterministic recipe to re-hydrate an exact [AgentPlanProposal] when reloading history. */
data class PersistedProposalSpec(
    val profileId: String,
    val characterId: String,
    val lineId: String,
    val seed: Long,
    val currentLevel: Int,
    val targetLevel: Int,
    val part3: Boolean,
    val initialProfession: String,
    val unlockedProfessions: Set<String>,
    val includeLockedPreview: Boolean,
    val weights: List<Int>,
    val maxSwitchBudget: Int,
    val maxSkips: Int,
    val optimizeSkips: Boolean,
    val minTotalGain: Int?,
    val minWeightedGain: Int?,
    val requiredStats: Set<GrowthStat>,
    val defaultTierIndex: Int,
    val minPromotionLevel: Int? = null,
) {
    fun rehydrate(rules: BuddyRules): AgentPlanProposal? = runCatching {
        val line = BuddyCampaignLine.entries.firstOrNull { it.id == lineId } ?: BuddyCampaignLine.KAI
        val report = ParetoGrowthPlanner.solve(
            ParetoGrowthPlanRequest(
                seed = seed,
                characterId = characterId,
                line = line,
                currentLevel = currentLevel,
                targetLevel = targetLevel,
                currentPart3 = part3,
                initialProfession = initialProfession,
                unlockedProfessions = unlockedProfessions,
                includeLockedPreview = includeLockedPreview,
                weights = weights,
                maxSwitchBudget = maxSwitchBudget,
                maxSkips = maxSkips,
                optimizeSkips = optimizeSkips,
                minTotalGain = minTotalGain,
                minWeightedGain = minWeightedGain,
                requiredStats = requiredStats,
                minPromotionLevel = minPromotionLevel,
            ),
            rules,
        )
        val best = report.paretoTiers.getOrElse(defaultTierIndex) { report.recommendedTier }
        AgentPlanProposal(
            report = report,
            recordTemplate = SeedWorkbenchRecord(
                profileId = profileId,
                characterId = characterId,
                line = line,
                part3 = report.part3,
                baseSeed = seed,
                inferredLevel = currentLevel,
                startLevel = currentLevel,
                extraAdvances = 0,
                profession = initialProfession,
                ride = null,
                unlockedProfessions = unlockedProfessions + initialProfession + best.steps.map { it.profession },
                weights = SeedWorkbenchEngine.normalizeWeights(weights),
                planSteps = best.workbenchSteps,
                previewOverrides = emptyMap(),
                completedSteps = 0,
            ),
            defaultTierIndex = defaultTierIndex.coerceIn(0, report.paretoTiers.lastIndex.coerceAtLeast(0)),
        )
    }.getOrNull()

    companion object {
        fun fromProposal(proposal: AgentPlanProposal): PersistedProposalSpec {
            val r = proposal.report
            return PersistedProposalSpec(
                profileId = proposal.recordTemplate.profileId,
                characterId = r.characterId,
                lineId = r.line.id,
                seed = r.seed,
                currentLevel = r.fromLevel,
                targetLevel = r.targetLevel,
                part3 = r.part3,
                initialProfession = r.initialProfession,
                unlockedProfessions = r.unlockedProfessionsInput.ifEmpty { setOf(r.initialProfession) },
                includeLockedPreview = true,
                weights = r.weights,
                maxSwitchBudget = r.paretoTiers.maxOfOrNull { it.switches }?.coerceAtLeast(3) ?: 5,
                maxSkips = r.maxSkipsAllowed,
                optimizeSkips = r.maxSkipsAllowed > 0,
                minTotalGain = r.minTotalGainFilter,
                minWeightedGain = r.minWeightedGainFilter,
                requiredStats = r.requiredStatsFilter,
                defaultTierIndex = proposal.defaultTierIndex,
                minPromotionLevel = r.effectiveMinPromotionLevel,
            )
        }
    }
}

data class PersistedThreeHousesProposalSpec(
    val characterName: String,
    val startLevel: Int,
    val targetLevel: Int,
    val weights: List<Int>,
    val customStages: List<ThreeHousesStageSpec> = emptyList(),
    val extraCertDips: List<ThreeHousesCertDipSpec> = emptyList(),
    val genderOverride: String? = null,
    val includeDlcClasses: Boolean = true,
) {
    fun rehydrate(retriever: CrossGameWikiRetriever?): AgentThreeHousesProposal? = runCatching {
        val (chars, classes) = retriever?.threeHousesPlannerData() ?: return null
        val gender = genderOverride?.let { g -> ThreeHousesGender.entries.firstOrNull { it.name == g } }
        val report = ThreeHousesGrowthPlanner.plan(
            ThreeHousesPlanRequest(
                characterName = characterName,
                startLevel = startLevel,
                targetLevel = targetLevel,
                genderOverride = gender,
                weights = weights,
                customStages = customStages,
                extraCertDips = extraCertDips,
                includeDlcClasses = includeDlcClasses,
            ),
            characters = chars,
            classes = classes,
        )
        AgentThreeHousesProposal(report, customStages, extraCertDips, gender, includeDlcClasses)
    }.getOrNull()

    companion object {
        fun fromProposal(p: AgentThreeHousesProposal): PersistedThreeHousesProposalSpec = PersistedThreeHousesProposalSpec(
            characterName = p.report.characterName,
            startLevel = p.report.fromLevel,
            targetLevel = p.report.targetLevel,
            weights = p.report.weights,
            customStages = p.customStages,
            extraCertDips = p.extraCertDips,
            genderOverride = p.genderOverride?.name,
            includeDlcClasses = p.includeDlcClasses,
        )
    }
}

data class PersistedEngageProposalSpec(
    val characterName: String,
    val stages: List<EngageStageSpec> = emptyList(),
    val totalLevelUps: Int,
    val starsphere: Boolean,
    val weights: List<Int>,
) {
    fun rehydrate(retriever: CrossGameWikiRetriever?): AgentEngageProposal? = runCatching {
        val (chars, classes) = retriever?.engagePlannerData() ?: return null
        val report = EngageFixedGrowthPlanner.solve(
            EngageFixedGrowthRequest(
                characterName = characterName,
                stages = stages,
                totalLevelUps = totalLevelUps,
                starsphere = starsphere,
                weights = weights,
            ),
            characters = chars,
            classes = classes,
        )
        AgentEngageProposal(report, stages, totalLevelUps, starsphere)
    }.getOrNull()

    companion object {
        fun fromProposal(p: AgentEngageProposal): PersistedEngageProposalSpec = PersistedEngageProposalSpec(
            characterName = p.report.characterName,
            stages = p.requestedStages,
            totalLevelUps = p.requestedTotalLevels,
            starsphere = p.starsphere,
            weights = p.report.weights,
        )
    }
}

data class AgentSessionMeta(
    val id: String,
    val title: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val parentSessionId: String? = null,
    val messageCount: Int = 0,
    val preview: String = "",
)

data class AgentSessionDocument(
    val id: String,
    val title: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val parentSessionId: String? = null,
    val compactedSummary: String? = null,
    val bubbles: List<PersistedAgentBubble> = emptyList(),
    val llmHistory: List<AgentTurnMessage> = emptyList(),
)

/**
 * Pi-inspired persistent multi-session store supporting New Chat, History switching,
 * Rename, Delete, Compaction summary persistence, and Fork-from-any-turn branching.
 */
class AgentSessionStore(private val sessionsDir: File) {
    constructor(context: Context) : this(File(context.filesDir, "agent-sessions"))

    private val lock = Any()
    private val indexFile: File get() = File(sessionsDir, "index.json")

    fun listSessions(): List<AgentSessionMeta> = synchronized(lock) {
        if (!indexFile.isFile) return emptyList()
        runCatching {
            val root = Json.parseToJsonElement(indexFile.readText(Charsets.UTF_8)).jsonObject
            (root["sessions"] as? JsonArray).orEmpty().mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                AgentSessionMeta(
                    id = id,
                    title = obj["title"]?.jsonPrimitive?.content ?: "未命名对话",
                    createdAtMillis = obj["createdAtMillis"]?.jsonPrimitive?.longOrNull ?: 0L,
                    updatedAtMillis = obj["updatedAtMillis"]?.jsonPrimitive?.longOrNull ?: 0L,
                    parentSessionId = obj["parentSessionId"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
                    messageCount = obj["messageCount"]?.jsonPrimitive?.intOrNull ?: 0,
                    preview = obj["preview"]?.jsonPrimitive?.content.orEmpty(),
                )
            }.sortedByDescending { it.updatedAtMillis }
        }.getOrDefault(emptyList())
    }

    fun createNewSession(title: String = "新对话", parentSessionId: String? = null): AgentSessionDocument = synchronized(lock) {
        val now = System.currentTimeMillis()
        val doc = AgentSessionDocument(
            id = UUID.randomUUID().toString(),
            title = title.trim().ifBlank { "新对话" },
            createdAtMillis = now,
            updatedAtMillis = now,
            parentSessionId = parentSessionId,
        )
        saveSession(doc)
        doc
    }

    fun loadSession(id: String): AgentSessionDocument? = synchronized(lock) {
        val file = File(sessionsDir, "$id.json")
        if (!file.isFile) return null
        runCatching { decodeSession(Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject) }.getOrNull()
    }

    fun saveSession(doc: AgentSessionDocument) = synchronized(lock) {
        if (!sessionsDir.exists()) sessionsDir.mkdirs()
        val now = System.currentTimeMillis()
        val autoTitle = if (doc.title == "新对话" || doc.title.isBlank()) {
            doc.bubbles.firstOrNull { it.isUser }?.text?.trim()?.take(18)?.ifBlank { "新对话" } ?: "新对话"
        } else doc.title.trim().take(36)
        val stamped = doc.copy(title = autoTitle, updatedAtMillis = now)

        val file = File(sessionsDir, "${stamped.id}.json")
        val tmp = File(sessionsDir, "${stamped.id}.json.tmp")
        tmp.writeText(encodeSession(stamped).toString(), Charsets.UTF_8)
        if (file.exists()) file.delete()
        check(tmp.renameTo(file)) { "无法保存对话历史" }

        val preview = stamped.bubbles.lastOrNull()?.text
            ?.replace(Regex("[#*_`|>]+"), " ")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(48)
            .orEmpty()
        val meta = AgentSessionMeta(
            id = stamped.id,
            title = stamped.title,
            createdAtMillis = stamped.createdAtMillis,
            updatedAtMillis = stamped.updatedAtMillis,
            parentSessionId = stamped.parentSessionId,
            messageCount = stamped.bubbles.size,
            preview = preview,
        )
        val updatedIndex = listSessions().filterNot { it.id == stamped.id }.toMutableList().apply { add(0, meta) }
        writeIndex(updatedIndex.take(80))
    }

    fun forkSession(source: AgentSessionDocument, upToBubbleId: Int): AgentSessionDocument = synchronized(lock) {
        val cutIdx = source.bubbles.indexOfFirst { it.id == upToBubbleId }
        val keptBubbles = if (cutIdx >= 0) source.bubbles.take(cutIdx + 1) else source.bubbles
        // Rebuild clean linear LLM history from kept bubbles so forked branch has exact valid tool-free context
        val rebuiltHistory = keptBubbles.filterNot { it.isError }.map { b ->
            if (b.isUser) AgentTurnMessage.User(b.text) else AgentTurnMessage.Assistant(b.text)
        }
        val now = System.currentTimeMillis()
        val forked = AgentSessionDocument(
            id = UUID.randomUUID().toString(),
            title = "${source.title.take(14)} · 分支",
            createdAtMillis = now,
            updatedAtMillis = now,
            parentSessionId = source.id,
            compactedSummary = source.compactedSummary,
            bubbles = keptBubbles,
            llmHistory = rebuiltHistory,
        )
        saveSession(forked)
        forked
    }

    fun renameSession(id: String, newTitle: String) {
        synchronized(lock) {
            val clean = newTitle.trim().take(36)
            if (clean.isEmpty()) return
            val doc = loadSession(id) ?: return
            saveSession(doc.copy(title = clean))
        }
    }

    fun deleteSession(id: String) = synchronized(lock) {
        val file = File(sessionsDir, "$id.json")
        if (file.exists()) file.delete()
        val next = listSessions().filterNot { it.id == id }
        writeIndex(next)
    }

    private fun writeIndex(metas: List<AgentSessionMeta>) {
        if (!sessionsDir.exists()) sessionsDir.mkdirs()
        val json = buildJsonObject {
            put("version", 1)
            put("sessions", buildJsonArray {
                metas.forEach { m ->
                    add(buildJsonObject {
                        put("id", m.id)
                        put("title", m.title)
                        put("createdAtMillis", m.createdAtMillis)
                        put("updatedAtMillis", m.updatedAtMillis)
                        m.parentSessionId?.let { put("parentSessionId", it) }
                        put("messageCount", m.messageCount)
                        put("preview", m.preview)
                    })
                }
            })
        }.toString()
        val tmp = File(sessionsDir, "index.json.tmp")
        tmp.writeText(json, Charsets.UTF_8)
        if (indexFile.exists()) indexFile.delete()
        check(tmp.renameTo(indexFile)) { "无法更新对话索引" }
    }

    private fun encodeSession(doc: AgentSessionDocument): JsonObject = buildJsonObject {
        put("version", 1)
        put("id", doc.id)
        put("title", doc.title)
        put("createdAtMillis", doc.createdAtMillis)
        put("updatedAtMillis", doc.updatedAtMillis)
        doc.parentSessionId?.let { put("parentSessionId", it) }
        doc.compactedSummary?.let { put("compactedSummary", it) }
        put("bubbles", buildJsonArray {
            doc.bubbles.forEach { b ->
                add(buildJsonObject {
                    put("id", b.id)
                    put("isUser", b.isUser)
                    put("text", b.text)
                    put("isError", b.isError)
                    put("toolTraces", buildJsonArray {
                        b.toolTraces.forEach { t ->
                            add(buildJsonObject {
                                put("toolName", t.toolName)
                                put("summary", t.summary)
                                put("isError", t.isError)
                            })
                        }
                    })
                    put("citations", buildJsonArray {
                        b.citations.forEach { c ->
                            add(buildJsonObject {
                                put("gameId", c.gameId)
                                put("gameTitle", c.gameTitle)
                                put("categoryId", c.categoryId)
                                put("entryId", c.entryId)
                                put("title", c.title)
                                put("subtitle", c.subtitle)
                            })
                        }
                    })
                    put("proposals", buildJsonArray {
                        b.proposalSpecs.forEach { p ->
                            add(buildJsonObject {
                                put("profileId", p.profileId)
                                put("characterId", p.characterId)
                                put("lineId", p.lineId)
                                put("seed", p.seed)
                                put("currentLevel", p.currentLevel)
                                put("targetLevel", p.targetLevel)
                                put("part3", p.part3)
                                put("initialProfession", p.initialProfession)
                                put("unlockedProfessions", JsonArray(p.unlockedProfessions.sorted().map(::JsonPrimitive)))
                                put("includeLockedPreview", p.includeLockedPreview)
                                put("weights", JsonArray(p.weights.map(::JsonPrimitive)))
                                put("maxSwitchBudget", p.maxSwitchBudget)
                                put("maxSkips", p.maxSkips)
                                put("optimizeSkips", p.optimizeSkips)
                                p.minTotalGain?.let { put("minTotalGain", it) }
                                p.minWeightedGain?.let { put("minWeightedGain", it) }
                                put("requiredStats", JsonArray(p.requiredStats.map { JsonPrimitive(it.name) }))
                                put("defaultTierIndex", p.defaultTierIndex)
                                p.minPromotionLevel?.let { put("minPromotionLevel", it) }
                            })
                        }
                    })
                    if (b.threeHousesSpecs.isNotEmpty()) {
                        put("threeHousesProposals", buildJsonArray {
                            b.threeHousesSpecs.forEach { th ->
                                add(buildJsonObject {
                                    put("characterName", th.characterName)
                                    put("startLevel", th.startLevel)
                                    put("targetLevel", th.targetLevel)
                                    put("weights", JsonArray(th.weights.map(::JsonPrimitive)))
                                    th.genderOverride?.let { put("genderOverride", it) }
                                    put("includeDlcClasses", th.includeDlcClasses)
                                    put("customStages", buildJsonArray {
                                        th.customStages.forEach { st ->
                                            add(buildJsonObject {
                                                put("className", st.className)
                                                put("fromLevel", st.fromLevel)
                                                put("toLevel", st.toLevel)
                                            })
                                        }
                                    })
                                    put("extraCertDips", buildJsonArray {
                                        th.extraCertDips.forEach { dp ->
                                            add(buildJsonObject {
                                                put("atLevel", dp.atLevel)
                                                put("className", dp.className)
                                            })
                                        }
                                    })
                                })
                            }
                        })
                    }
                    if (b.engageSpecs.isNotEmpty()) {
                        put("engageProposals", buildJsonArray {
                            b.engageSpecs.forEach { eg ->
                                add(buildJsonObject {
                                    put("characterName", eg.characterName)
                                    put("totalLevelUps", eg.totalLevelUps)
                                    put("starsphere", eg.starsphere)
                                    put("weights", JsonArray(eg.weights.map(::JsonPrimitive)))
                                    put("stages", buildJsonArray {
                                        eg.stages.forEach { st ->
                                            add(buildJsonObject {
                                                put("className", st.className)
                                                put("levelsToGain", st.levelsToGain)
                                                put("starsphere", st.starsphere)
                                            })
                                        }
                                    })
                                })
                            }
                        })
                    }
                })
            }
        })
        put("llmHistory", buildJsonArray {
            // Persist user & final assistant turns cleanly (dropping raw multi-KB intermediate tool blobs across restarts)
            doc.llmHistory.forEach { m ->
                when (m) {
                    is AgentTurnMessage.User -> add(buildJsonObject {
                        put("role", "user")
                        put("text", m.text)
                    })
                    is AgentTurnMessage.Assistant -> if (m.toolCalls.isEmpty() && m.text.isNotBlank()) {
                        add(buildJsonObject {
                            put("role", "assistant")
                            put("text", m.text)
                        })
                    }
                    is AgentTurnMessage.ToolResult -> Unit
                }
            }
        })
    }

    private fun decodeSession(root: JsonObject): AgentSessionDocument {
        val bubbles = (root["bubbles"] as? JsonArray).orEmpty().mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val traces = (obj["toolTraces"] as? JsonArray).orEmpty().mapNotNull { te ->
                val to = te as? JsonObject ?: return@mapNotNull null
                AgentToolTrace(
                    toolName = to["toolName"]?.jsonPrimitive?.content.orEmpty(),
                    summary = to["summary"]?.jsonPrimitive?.content.orEmpty(),
                    isError = to["isError"]?.jsonPrimitive?.booleanOrNull == true,
                )
            }
            val citations = (obj["citations"] as? JsonArray).orEmpty().mapNotNull { ce ->
                val co = ce as? JsonObject ?: return@mapNotNull null
                AgentWikiCitation(
                    gameId = co["gameId"]?.jsonPrimitive?.content.orEmpty(),
                    gameTitle = co["gameTitle"]?.jsonPrimitive?.content.orEmpty(),
                    categoryId = co["categoryId"]?.jsonPrimitive?.content.orEmpty(),
                    entryId = co["entryId"]?.jsonPrimitive?.content.orEmpty(),
                    title = co["title"]?.jsonPrimitive?.content.orEmpty(),
                    subtitle = co["subtitle"]?.jsonPrimitive?.content.orEmpty(),
                )
            }
            val proposals = (obj["proposals"] as? JsonArray).orEmpty().mapNotNull { pe ->
                val po = pe as? JsonObject ?: return@mapNotNull null
                PersistedProposalSpec(
                    profileId = po["profileId"]?.jsonPrimitive?.content ?: "global",
                    characterId = po["characterId"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                    lineId = po["lineId"]?.jsonPrimitive?.content ?: BuddyCampaignLine.KAI.id,
                    seed = po["seed"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null,
                    currentLevel = po["currentLevel"]?.jsonPrimitive?.intOrNull ?: 1,
                    targetLevel = po["targetLevel"]?.jsonPrimitive?.intOrNull ?: 10,
                    part3 = po["part3"]?.jsonPrimitive?.booleanOrNull == true,
                    initialProfession = po["initialProfession"]?.jsonPrimitive?.content.orEmpty(),
                    unlockedProfessions = (po["unlockedProfessions"] as? JsonArray).orEmpty().mapNotNull {
                        runCatching { it.jsonPrimitive.content }.getOrNull()
                    }.toSet(),
                    includeLockedPreview = po["includeLockedPreview"]?.jsonPrimitive?.booleanOrNull != false,
                    weights = (po["weights"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.intOrNull }
                        .takeIf { it.size == GrowthStat.entries.size } ?: List(GrowthStat.entries.size) { 1 },
                    maxSwitchBudget = po["maxSwitchBudget"]?.jsonPrimitive?.intOrNull ?: 5,
                    maxSkips = po["maxSkips"]?.jsonPrimitive?.intOrNull ?: 0,
                    optimizeSkips = po["optimizeSkips"]?.jsonPrimitive?.booleanOrNull == true,
                    minTotalGain = po["minTotalGain"]?.jsonPrimitive?.intOrNull,
                    minWeightedGain = po["minWeightedGain"]?.jsonPrimitive?.intOrNull,
                    requiredStats = (po["requiredStats"] as? JsonArray).orEmpty().mapNotNull { elSt ->
                        val name = runCatching { elSt.jsonPrimitive.content }.getOrNull()
                        GrowthStat.entries.firstOrNull { it.name == name }
                    }.toSet(),
                    defaultTierIndex = po["defaultTierIndex"]?.jsonPrimitive?.intOrNull ?: 0,
                    minPromotionLevel = po["minPromotionLevel"]?.jsonPrimitive?.intOrNull,
                )
            }
            val thSpecs = (obj["threeHousesProposals"] as? JsonArray).orEmpty().mapNotNull { te ->
                val to = te as? JsonObject ?: return@mapNotNull null
                val name = to["characterName"]?.jsonPrimitive?.content ?: return@mapNotNull null
                PersistedThreeHousesProposalSpec(
                    characterName = name,
                    startLevel = to["startLevel"]?.jsonPrimitive?.intOrNull ?: 1,
                    targetLevel = to["targetLevel"]?.jsonPrimitive?.intOrNull ?: 40,
                    weights = (to["weights"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.intOrNull }
                        .takeIf { it.size == ThreeHousesStat.entries.size } ?: List(ThreeHousesStat.entries.size) { 1 },
                    customStages = (to["customStages"] as? JsonArray).orEmpty().mapNotNull { se ->
                        val so = se as? JsonObject ?: return@mapNotNull null
                        val cn = so["className"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val f = so["fromLevel"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                        val t = so["toLevel"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                        ThreeHousesStageSpec(cn, f, t)
                    },
                    extraCertDips = (to["extraCertDips"] as? JsonArray).orEmpty().mapNotNull { de ->
                        val dObj = de as? JsonObject ?: return@mapNotNull null
                        val atLv = dObj["atLevel"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                        val cn = dObj["className"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        ThreeHousesCertDipSpec(atLv, cn)
                    },
                    genderOverride = to["genderOverride"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
                    includeDlcClasses = to["includeDlcClasses"]?.jsonPrimitive?.booleanOrNull != false,
                )
            }
            val egSpecs = (obj["engageProposals"] as? JsonArray).orEmpty().mapNotNull { ee ->
                val eo = ee as? JsonObject ?: return@mapNotNull null
                val name = eo["characterName"]?.jsonPrimitive?.content ?: return@mapNotNull null
                PersistedEngageProposalSpec(
                    characterName = name,
                    stages = (eo["stages"] as? JsonArray).orEmpty().mapNotNull { se ->
                        val so = se as? JsonObject ?: return@mapNotNull null
                        val cn = so["className"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val lv = so["levelsToGain"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                        val sp = so["starsphere"]?.jsonPrimitive?.booleanOrNull == true
                        EngageStageSpec(cn, lv, sp)
                    },
                    totalLevelUps = eo["totalLevelUps"]?.jsonPrimitive?.intOrNull ?: 25,
                    starsphere = eo["starsphere"]?.jsonPrimitive?.booleanOrNull == true,
                    weights = (eo["weights"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.intOrNull }
                        .takeIf { it.size == EngageStat.entries.size } ?: List(EngageStat.entries.size) { 1 },
                )
            }
            PersistedAgentBubble(
                id = obj["id"]?.jsonPrimitive?.intOrNull ?: 0,
                isUser = obj["isUser"]?.jsonPrimitive?.booleanOrNull == true,
                text = obj["text"]?.jsonPrimitive?.content.orEmpty(),
                toolTraces = traces,
                citations = citations,
                proposalSpecs = proposals,
                threeHousesSpecs = thSpecs,
                engageSpecs = egSpecs,
                isError = obj["isError"]?.jsonPrimitive?.booleanOrNull == true,
            )
        }
        val history = (root["llmHistory"] as? JsonArray).orEmpty().mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val role = obj["role"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val txt = obj["text"]?.jsonPrimitive?.content.orEmpty()
            when (role) {
                "user" -> AgentTurnMessage.User(txt)
                "assistant" -> AgentTurnMessage.Assistant(txt)
                else -> null
            }
        }
        return AgentSessionDocument(
            id = root["id"]?.jsonPrimitive?.content.orEmpty(),
            title = root["title"]?.jsonPrimitive?.content ?: "历史对话",
            createdAtMillis = root["createdAtMillis"]?.jsonPrimitive?.longOrNull ?: 0L,
            updatedAtMillis = root["updatedAtMillis"]?.jsonPrimitive?.longOrNull ?: 0L,
            parentSessionId = root["parentSessionId"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
            compactedSummary = root["compactedSummary"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
            bubbles = bubbles,
            llmHistory = history,
        )
    }
}
