package com.fireemblem.companion.agent

import android.content.Context
import com.fireemblem.companion.content.CompanionGame
import com.fireemblem.companion.engine.BuddyCampaignLine
import com.fireemblem.companion.engine.BuddyRules
import com.fireemblem.companion.engine.EngageFixedGrowthPlanner
import com.fireemblem.companion.engine.EngageFixedGrowthRequest
import com.fireemblem.companion.engine.EngageStageSpec
import com.fireemblem.companion.engine.EngageStat
import com.fireemblem.companion.engine.GrowthStat
import com.fireemblem.companion.engine.LevelJournal
import com.fireemblem.companion.engine.ParetoGrowthPlanRequest
import com.fireemblem.companion.engine.ParetoGrowthPlanner
import com.fireemblem.companion.engine.SeedEngine
import com.fireemblem.companion.engine.SeedWorkbenchEngine
import com.fireemblem.companion.engine.SeedWorkbenchRecord
import com.fireemblem.companion.engine.SeedWorkbenchStart
import com.fireemblem.companion.engine.ThreeHousesCertDipSpec
import com.fireemblem.companion.engine.ThreeHousesGender
import com.fireemblem.companion.engine.ThreeHousesGrowthPlanner
import com.fireemblem.companion.engine.ThreeHousesPlanRequest
import com.fireemblem.companion.engine.ThreeHousesStageSpec
import com.fireemblem.companion.engine.ThreeHousesStat
import com.fireemblem.companion.personal.PersonalStore
import com.fireemblem.companion.personal.RouteMergeCharacter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Local deterministic tool executor backing [CompanionAgentOrchestrator].
 * Game data & personal saves are strictly read-only; only the user-visible [AgentMemoryStore]
 * can be updated via memory tools. Workbench imports always require explicit UI confirmation.
 */
class CompanionToolExecutor(
    private val context: Context,
    private val retriever: CrossGameWikiRetriever,
    private val memoryStore: AgentMemoryStore = AgentMemoryStore(context),
    private val rulesProvider: () -> BuddyRules = { BuddyRules.bundled() },
) : AgentToolExecutor {
    private val rules: BuddyRules by lazy(rulesProvider)

    override suspend fun execute(call: AgentToolCall): AgentToolExecutionOutcome = withContext(Dispatchers.IO) {
        when (call.name) {
            CompanionAgentTools.TOOL_SEARCH_WIKI -> execSearchWiki(call.arguments)
            CompanionAgentTools.TOOL_GET_WIKI_ENTRY -> execGetWikiEntry(call.arguments)
            CompanionAgentTools.TOOL_LIST_SAVED_CHARACTERS -> execListSavedCharacters(call.arguments)
            CompanionAgentTools.TOOL_SOLVE_PARETO_GROWTH_PLAN -> execSolvePareto(call.arguments)
            CompanionAgentTools.TOOL_COMPARE_CLASSES_AT_LEVEL -> execCompareClasses(call.arguments)
            CompanionAgentTools.TOOL_GET_ROUTE_MERGE_SUMMARY -> execRouteMergeSummary(call.arguments)
            CompanionAgentTools.TOOL_PLAN_THREE_HOUSES_GROWTH -> execPlanThreeHouses(call.arguments)
            CompanionAgentTools.TOOL_PLAN_ENGAGE_FIXED_GROWTH -> execPlanEngageFixed(call.arguments)
            CompanionAgentTools.TOOL_READ_MEMORIES -> execReadMemories()
            CompanionAgentTools.TOOL_SAVE_MEMORY -> execSaveMemory(call.arguments)
            CompanionAgentTools.TOOL_FORGET_MEMORY -> execForgetMemory(call.arguments)
            else -> errorOutcome("未知工具：${call.name}")
        }
    }

    private fun execReadMemories(): AgentToolExecutionOutcome {
        val list = memoryStore.list()
        val json = buildJsonObject {
            put("count", list.size)
            put("memories", buildJsonArray {
                list.forEach { m ->
                    add(buildJsonObject {
                        put("id", m.id)
                        put("category", m.category)
                        put("content", m.content)
                    })
                }
            })
        }
        return AgentToolExecutionOutcome(
            outputJson = json,
            summary = "已读取参谋记忆本（${list.size} 条）",
        )
    }

    private fun execSaveMemory(args: JsonObject): AgentToolExecutionOutcome {
        val content = args.str("content")?.trim().orEmpty()
        if (content.isEmpty()) return errorOutcome("缺少必需参数 content")
        val cat = args.str("category") ?: "note"
        val saved = memoryStore.add(cat, content)
        return AgentToolExecutionOutcome(
            outputJson = buildJsonObject {
                put("saved", true)
                put("id", saved.id)
                put("category", saved.category)
                put("content", saved.content)
            },
            summary = "已记入参谋记忆本：${saved.content.take(28)}",
        )
    }

    private fun execForgetMemory(args: JsonObject): AgentToolExecutionOutcome {
        val id = args.str("memory_id")?.trim().orEmpty()
        if (id.isEmpty()) return errorOutcome("缺少必需参数 memory_id")
        val ok = memoryStore.remove(id)
        return AgentToolExecutionOutcome(
            outputJson = buildJsonObject { put("deleted", ok); put("id", id) },
            summary = if (ok) "已删除过期记忆（$id）" else "未找到记忆 $id",
        )
    }

    private fun execSearchWiki(args: JsonObject): AgentToolExecutionOutcome {
        val query = args.str("query")?.trim().orEmpty()
        if (query.isEmpty()) return errorOutcome("缺少必需参数 query")
        val game = args.str("game") ?: "all"
        val category = args.str("category")
        val limit = (args.int("limit") ?: 8).coerceIn(1, 12)
        val hits = retriever.search(query, gameFilter = game, categoryFilter = category, limit = limit)
        val json = buildJsonObject {
            put("query", query)
            put("count", hits.size)
            put("hits", buildJsonArray {
                hits.forEach { h ->
                    add(buildJsonObject {
                        put("game", h.gameId)
                        put("game_title", h.gameTitle)
                        put("category_id", h.categoryId)
                        put("category_title", h.categoryTitle)
                        put("entry_id", h.entryId)
                        put("title", h.title)
                        put("subtitle", h.subtitle)
                        put("snippet", h.snippet)
                    })
                }
            })
        }
        return AgentToolExecutionOutcome(
            outputJson = json,
            summary = "检索「$query」命中 ${hits.size} 条资料",
            citations = hits.take(5).map { it.toCitation() },
        )
    }

    private fun execGetWikiEntry(args: JsonObject): AgentToolExecutionOutcome {
        val game = args.str("game")?.trim().orEmpty()
        val entryId = args.str("entry_id")?.trim().orEmpty()
        if (game.isEmpty() || entryId.isEmpty()) return errorOutcome("缺少必需参数 game 或 entry_id")
        val detail = retriever.getEntryDetail(game, entryId)
            ?: return errorOutcome("未找到词条：$game / $entryId")
        val json = buildJsonObject {
            put("game", detail.citation.gameId)
            put("entry_id", detail.citation.entryId)
            put("title", detail.citation.title)
            put("content", detail.summaryText)
        }
        return AgentToolExecutionOutcome(
            outputJson = json,
            summary = "查阅《${detail.citation.gameTitle}》${detail.citation.title}",
            citations = listOf(detail.citation),
        )
    }

    private fun execListSavedCharacters(args: JsonObject): AgentToolExecutionOutcome {
        val filter = args.str("character_query")?.trim().orEmpty()
        val profileId = activeProfileId()
        val snap = PersonalStore(context).snapshot()
        val heads = snap.heads().values.mapNotNull { it.singleOrNull() }.filter { !it.deleted }

        val workbenches = heads.filter { it.kind == SeedWorkbenchRecord.KIND }
            .mapNotNull { runCatching { SeedWorkbenchRecord.decode(it.payload) }.getOrNull() }
            .filter { it.profileId == profileId }
        val journals = heads.filter { it.kind == LevelJournal.KIND }
            .mapNotNull { runCatching { LevelJournal.decode(it.payload) }.getOrNull() }
            .filter { it.profile == profileId }
        val mergeRecords = RouteMergeCharacter.readProfile(snap, profileId).records

        fun matches(charId: String, name: String): Boolean =
            filter.isEmpty() || name.contains(filter, ignoreCase = true) || charId.equals(filter, ignoreCase = true)

        val json = buildJsonObject {
            put("profile_id", profileId)
            put("seed_workbenches", buildJsonArray {
                workbenches.forEach { w ->
                    val name = rules.characters.firstOrNull { it.id == w.characterId }?.name ?: w.characterId
                    if (!matches(w.characterId, name)) return@forEach
                    add(buildJsonObject {
                        put("character_id", w.characterId)
                        put("character_name", name)
                        put("line", w.line.id)
                        put("part3", w.part3)
                        put("base_seed", w.baseSeed)
                        put("start_level", w.startLevel)
                        put("profession", w.profession)
                        put("unlocked_professions", JsonArray(w.unlockedProfessions.sorted().map(::JsonPrimitive)))
                        put("plan_step_count", w.planSteps.size)
                    })
                }
            })
            put("journal_checkpoints", buildJsonArray {
                journals.forEach { j ->
                    val name = rules.characters.firstOrNull { it.id == j.characterId }?.name ?: j.characterId
                    if (!matches(j.characterId, name)) return@forEach
                    val cp = j.takeIf { it.hasCurrentSeedCheckpoint() }?.seedCheckpoint
                    add(buildJsonObject {
                        put("character_id", j.characterId)
                        put("character_name", name)
                        put("journal_name", j.name)
                        put("line", j.line.id)
                        put("current_level", j.currentLevel ?: 1)
                        if (cp != null) {
                            put("seed", cp.seed)
                            put("checkpoint_level", cp.level)
                            put("profession", cp.profession)
                            put("part3", cp.part3)
                            put("unlocked_professions", JsonArray((j.confirmedUnlockedProfessions() + cp.profession).sorted().map(::JsonPrimitive)))
                        }
                    })
                }
            })
            put("route_merge_count", mergeRecords.size)
        }
        return AgentToolExecutionOutcome(
            outputJson = json,
            summary = "已读取本地档案：工作台 ${workbenches.size} · 升级记录 ${journals.size} · 多线融合 ${mergeRecords.size}",
        )
    }

    private fun execSolvePareto(args: JsonObject): AgentToolExecutionOutcome {
        val charQuery = args.str("character")?.trim().orEmpty()
        if (charQuery.isEmpty()) return errorOutcome("缺少必需参数 character")
        val buddyChar = resolveBuddyCharacter(charQuery)
            ?: return errorOutcome("在《万缕千丝》乱数引擎中未找到角色「$charQuery」")

        val profileId = activeProfileId()
        val saved = findSavedSeedDefaults(profileId, buddyChar.id)
        val line = args.str("line")?.let { parseLine(it) } ?: saved?.line ?: BuddyCampaignLine.KAI
        val seed = args.long("seed") ?: saved?.seed
            ?: return errorOutcome("未提供角色「${buddyChar.name}」的乱数种子，且当前存档中暂无该角色的种子检查点；请先在「升级记录 / 反推」获取种子或直接提供种子。")
        val currentLevel = (args.int("current_level") ?: saved?.level ?: 1).coerceIn(1, 98)
        val targetLevel = (args.int("target_level") ?: (currentLevel + 15).coerceAtMost(99)).coerceIn(currentLevel + 1, 99)
        val part3 = args.bool("part3") ?: saved?.part3 ?: buddyChar.part3Only
        val legalClasses = rules.professions.filter { rules.classAllowed(buddyChar.id, it.name, part3) }.map { it.name }
        val initialProf = args.str("initial_profession")?.takeIf { it in legalClasses }
            ?: saved?.profession?.takeIf { it in legalClasses }
            ?: legalClasses.firstOrNull()
            ?: return errorOutcome("角色「${buddyChar.name}」在当前篇章无合法职业")

        val argUnlocked = (args["unlocked_professions"] as? JsonArray)
            ?.mapNotNull { runCatching { it.jsonPrimitive.content.trim() }.getOrNull() }
            ?.filter { it in legalClasses }
            ?.toSet()
            .orEmpty()
        val savedUnlocked = saved?.unlockedProfessions?.filter { it in legalClasses }?.toSet().orEmpty()
        val includeLockedPreview = args.bool("include_locked_preview") ?: (argUnlocked.isEmpty() && savedUnlocked.size <= 1)
        val unlocked = (argUnlocked.ifEmpty { savedUnlocked } + initialProf).toSet()

        val weights = parseWeights(args["weights"] as? JsonObject)
        val budget = (args.int("max_switch_budget") ?: 5).coerceIn(0, 10)
        val maxSkips = (args.int("max_skips") ?: 0).coerceIn(0, ParetoGrowthPlanner.MAX_ALLOWED_SKIPS)
        val optimizeSkips = args.bool("optimize_skips") ?: (maxSkips > 0)
        val minTotalGain = args.int("min_total_gain")?.takeIf { it > 0 }
        val minWeightedGain = args.int("min_weighted_gain")?.takeIf { it > 0 }
        val maxExamProb = args.int("max_exam_probability")?.coerceIn(1, 100)
        val requiredStats = parseStatSet(args["required_stats"] as? JsonArray)
        val minPromotionLevel = args.int("earliest_promotion_level")?.coerceIn(1, 99)
        val enforceRoute = args.bool("enforce_route_availability") ?: true

        val report = ParetoGrowthPlanner.solve(
            ParetoGrowthPlanRequest(
                seed = seed,
                characterId = buddyChar.id,
                line = line,
                currentLevel = currentLevel,
                targetLevel = targetLevel,
                currentPart3 = part3,
                initialProfession = initialProf,
                unlockedProfessions = unlocked,
                includeLockedPreview = includeLockedPreview,
                weights = weights,
                currentStats = saved?.stats ?: List(GrowthStat.entries.size) { 0 },
                ride = saved?.ride,
                maxSwitchBudget = budget,
                maxSkips = maxSkips,
                optimizeSkips = optimizeSkips,
                minTotalGain = minTotalGain,
                minWeightedGain = minWeightedGain,
                requiredStats = requiredStats,
                maxExamProbability = maxExamProb,
                minPromotionLevel = minPromotionLevel,
                enforceRouteAvailability = enforceRoute,
            ),
            rules,
        )

        val bestTier = report.recommendedTier
        val recordTemplate = SeedWorkbenchRecord(
            profileId = profileId,
            characterId = buddyChar.id,
            line = line,
            part3 = report.part3,
            baseSeed = seed,
            inferredLevel = currentLevel,
            startLevel = currentLevel,
            extraAdvances = 0,
            profession = initialProf,
            ride = report.ride,
            unlockedProfessions = unlocked + bestTier.steps.map { it.profession },
            weights = SeedWorkbenchEngine.normalizeWeights(weights),
            planSteps = bestTier.workbenchSteps,
            previewOverrides = emptyMap(),
            completedSteps = 0,
        )

        val json = buildJsonObject {
            put("character_id", report.characterId)
            put("character_name", report.characterName)
            put("line", report.line.label)
            put("seed", report.seed)
            put("from_level", report.fromLevel)
            put("target_level", report.targetLevel)
            put("initial_profession", report.initialProfession)
            put("unlocked_professions", JsonArray(report.unlockedProfessionsInput.sorted().map(::JsonPrimitive)))
            report.effectiveMinPromotionLevel?.let { put("effective_min_promotion_level", it) }
            put("max_skips_allowed", report.maxSkipsAllowed)
            report.minTotalGainFilter?.let { put("min_total_gain_filter", it) }
            report.minWeightedGainFilter?.let { put("min_weighted_gain_filter", it) }
            put("global_best_min_switches", report.globalBestMinSwitches)
            put("global_best_score", report.globalBestScore)
            put("tiers", buildJsonArray {
                report.paretoTiers.forEach { t ->
                    add(buildJsonObject {
                        put("label", t.label)
                        put("switches", t.switches)
                        put("skips", t.skips)
                        put("weighted_score", t.weightedScore)
                        put("score_delta_vs_baseline", t.scoreDeltaVsBaseline)
                        put("total_stat_gain", t.totalStatGain)
                        putJsonObject("stat_gains") {
                            GrowthStat.entries.forEach { st -> put(st.label, t.statGains[st.ordinal]) }
                        }
                        putJsonObject("stat_delta_vs_baseline") {
                            GrowthStat.entries.forEach { st ->
                                val d = t.statGainsDeltaVsBaseline[st.ordinal]
                                if (d != 0) put(st.label, d)
                            }
                        }
                        put("switch_events", buildJsonArray {
                            t.switchEvents.forEach { ev ->
                                add(buildJsonObject {
                                    put("at_level", ev.atLevel)
                                    put("from", ev.fromProfession)
                                    put("to", ev.toProfession)
                                    put("score_delta_at_level", ev.scoreDeltaAtLevel)
                                    put("requires_unlock_exam", ev.requiresUnlockExam)
                                    put("target_tier", BuddyRules.tierLabel(ev.targetTier))
                                    ev.unlockRequirement?.let { put("unlock_requirement", it) }
                                })
                            }
                        })
                        if (t.skipEvents.isNotEmpty()) {
                            put("skip_events", buildJsonArray {
                                t.skipEvents.take(20).forEach { sk ->
                                    add(buildJsonObject {
                                        put("at_level", sk.atLevel)
                                        put("rng_step", sk.rngStepIndex)
                                        put("skipped_total", sk.skippedTotal)
                                        put("exam_min_prob", sk.examMinimumProbability)
                                        put("reason", sk.reason)
                                    })
                                }
                            })
                        }
                    })
                }
            })
            if (report.warnings.isNotEmpty()) {
                put("warnings", JsonArray(report.warnings.map(::JsonPrimitive)))
            }
        }

        val skipHint = if (bestTier.skips > 0) " · 考试跳过 ${bestTier.skips} 次" else ""
        return AgentToolExecutionOutcome(
            outputJson = json,
            summary = "已计算 ${report.characterName} Lv.${report.fromLevel}→${report.targetLevel} 转职分档方案（${report.paretoTiers.size} 档$skipHint）",
            planProposal = AgentPlanProposal(
                report = report,
                recordTemplate = recordTemplate,
                defaultTierIndex = report.paretoTiers.lastIndex.coerceAtLeast(0),
            ),
        )
    }

    private fun execPlanThreeHouses(args: JsonObject): AgentToolExecutionOutcome {
        val charQuery = args.str("character")?.trim().orEmpty()
        if (charQuery.isEmpty()) return errorOutcome("缺少必需参数 character")
        val data = retriever.threeHousesPlannerData()
            ?: return errorOutcome("无法读取《风花雪月》离线资料包，请稍后重试。")
        val (characters, classes) = data

        val genderOverride = when (args.str("gender")?.trim()?.lowercase(Locale.ROOT)) {
            "male", "m", "男" -> ThreeHousesGender.MALE
            "female", "f", "女" -> ThreeHousesGender.FEMALE
            else -> null
        }
        val weights = parseThreeHousesWeights(args["weights"] as? JsonObject)
        val customStages = (args["stages"] as? JsonArray).orEmpty().mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val clsName = obj.str("class_name")?.trim().orEmpty()
            val fromLv = obj.int("from_level") ?: return@mapNotNull null
            val toLv = obj.int("to_level") ?: return@mapNotNull null
            if (clsName.isEmpty() || toLv <= fromLv) null else ThreeHousesStageSpec(clsName, fromLv, toLv)
        }
        val certDips = (args["cert_dips"] as? JsonArray).orEmpty().mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val atLv = obj.int("at_level") ?: return@mapNotNull null
            val clsName = obj.str("class_name")?.trim().orEmpty()
            if (clsName.isEmpty()) null else ThreeHousesCertDipSpec(atLv, clsName)
        }
        val includeDlc = args.bool("include_dlc") ?: true

        val report = ThreeHousesGrowthPlanner.plan(
            request = ThreeHousesPlanRequest(
                characterName = charQuery,
                startLevel = args.int("start_level"),
                targetLevel = (args.int("target_level") ?: 40).coerceIn(2, 99),
                genderOverride = genderOverride,
                weights = weights,
                customStages = customStages,
                extraCertDips = certDips,
                includeDlcClasses = includeDlc,
            ),
            characters = characters,
            classes = classes,
        )

        val json = buildJsonObject {
            put("game", "three-houses")
            put("character_name", report.characterName)
            put("affiliation", report.affiliation)
            put("gender", report.gender.label)
            put("from_level", report.fromLevel)
            put("target_level", report.targetLevel)
            put("two_stat_reroll_guaranteed", report.usedTwoStatReroll)
            putJsonObject("final_displayed_stats") {
                ThreeHousesStat.entries.forEach { st ->
                    put(st.label, String.format(Locale.US, "%.1f", report.finalDisplayedStats[st.ordinal]))
                }
            }
            putJsonObject("total_expected_gains") {
                ThreeHousesStat.entries.forEach { st ->
                    put(st.label, String.format(Locale.US, "%.1f", report.totalExpectedGains[st.ordinal]))
                }
            }
            putJsonObject("final_class_bonuses") {
                ThreeHousesStat.entries.forEach { st ->
                    val b = report.finalClassBonuses[st.ordinal]
                    if (b != 0) put(st.label, b)
                }
            }
            put("stages", buildJsonArray {
                report.stages.forEach { st ->
                    add(buildJsonObject {
                        put("class_name", st.className)
                        put("tier", st.tier)
                        put("from_level", st.fromLevel)
                        put("to_level", st.toLevel)
                        put("requirement", st.requirement)
                        if (st.classSkill.isNotBlank()) put("class_skill", st.classSkill)
                    })
                }
            })
            if (report.certEvents.isNotEmpty()) {
                put("certification_events", buildJsonArray {
                    report.certEvents.forEach { ev ->
                        add(buildJsonObject {
                            put("at_level", ev.atLevel)
                            put("class_name", ev.className)
                            put("cert_dip_only", ev.isCertDipOnly)
                            put("requirement", ev.requirement)
                            if (ev.masterySkill.isNotBlank()) put("mastery_skill", ev.masterySkill)
                            putJsonObject("stat_floor_bumps") {
                                ThreeHousesStat.entries.forEach { st ->
                                    val bump = ev.floorGains[st.ordinal]
                                    if (bump >= 0.05) put(st.label, String.format(Locale.US, "+%.1f", bump))
                                }
                            }
                        })
                    }
                })
            }
            if (report.alternativeFinalClasses.isNotEmpty()) {
                put("alternative_final_classes", JsonArray(report.alternativeFinalClasses.map { JsonPrimitive(it.first) }))
            }
            if (report.warnings.isNotEmpty()) {
                put("warnings", JsonArray(report.warnings.map(::JsonPrimitive)))
            }
        }
        val pathPreview = report.stages.joinToString("→") { it.className }
        return AgentToolExecutionOutcome(
            outputJson = json,
            summary = "已规划《风花雪月》${report.characterName} Lv.${report.fromLevel}→${report.targetLevel}（$pathPreview）",
            threeHousesProposal = AgentThreeHousesProposal(
                report = report,
                customStages = customStages,
                extraCertDips = certDips,
                genderOverride = genderOverride,
                includeDlcClasses = includeDlc,
            ),
        )
    }

    private fun execPlanEngageFixed(args: JsonObject): AgentToolExecutionOutcome {
        val charQuery = args.str("character")?.trim().orEmpty()
        if (charQuery.isEmpty()) return errorOutcome("缺少必需参数 character")
        val data = retriever.engagePlannerData()
            ?: return errorOutcome("无法读取《Engage》离线资料包，请稍后重试。")
        val (characters, classes) = data

        val starsphere = args.bool("starsphere") ?: false
        val totalLevels = (args.int("total_level_ups") ?: 25).coerceIn(1, 60)
        val weights = parseEngageWeights(args["weights"] as? JsonObject)
        val stages = (args["stages"] as? JsonArray).orEmpty().mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val clsName = obj.str("class_name")?.trim().orEmpty()
            val lvs = obj.int("levels") ?: obj.int("levels_to_gain") ?: return@mapNotNull null
            val st = obj.bool("starsphere") ?: starsphere
            if (clsName.isEmpty() || lvs < 0) null else EngageStageSpec(clsName, lvs, st)
        }

        val report = EngageFixedGrowthPlanner.solve(
            request = EngageFixedGrowthRequest(
                characterName = charQuery,
                stages = stages,
                totalLevelUps = totalLevels,
                starsphere = starsphere,
                weights = weights,
            ),
            characters = characters,
            classes = classes,
        )

        val json = buildJsonObject {
            put("game", "engage")
            put("mode", "fixed_growth_deterministic")
            put("character_name", report.characterName)
            put("affiliation", report.affiliation)
            put("initial_class", report.initialClassName)
            put("initial_level", report.initialLevel)
            put("initial_internal_level", report.initialInternalLevel)
            put("has_jean_expertise_2x", report.hasExpertise)
            put("used_starsphere_plus15", report.usedStarsphere)
            put("total_level_ups", report.totalLevelUps)
            put("final_class", report.finalClassName)
            put("final_total_internal_level", report.finalTotalInternalLevel)
            putJsonObject("start_stats") {
                EngageStat.entries.forEach { st -> put(st.label, report.startStats[st.ordinal]) }
            }
            putJsonObject("final_stats") {
                EngageStat.entries.forEach { st -> put(st.label, report.finalStats[st.ordinal]) }
            }
            putJsonObject("total_net_gains") {
                EngageStat.entries.forEach { st -> put(st.label, report.totalNetGains[st.ordinal]) }
            }
            putJsonObject("final_accumulators_mod100") {
                EngageStat.entries.forEach { st -> put(st.label, report.finalAccumulators[st.ordinal]) }
            }
            putJsonObject("final_caps") {
                EngageStat.entries.forEach { st -> put(st.label, report.finalCaps[st.ordinal]) }
            }
            put("stages", buildJsonArray {
                report.stages.forEach { st ->
                    add(buildJsonObject {
                        put("class_name", st.className)
                        put("tier", st.tier)
                        put("levels_gained", st.levelsGained)
                        put("level_range_in_class", "Lv.${st.levelFromInClass}→Lv.${st.levelToInClass}")
                        put("starsphere", st.starsphere)
                        if (st.weaponRequirement.isNotBlank()) put("weapon_requirement", st.weaponRequirement)
                        if (st.classSkill.isNotBlank()) put("class_skill", st.classSkill)
                    })
                }
            })
            if (report.classChanges.isNotEmpty()) {
                put("class_changes", buildJsonArray {
                    report.classChanges.forEach { cc ->
                        add(buildJsonObject {
                            put("after_level_ups", cc.afterLevelUps)
                            put("from", cc.fromClass)
                            put("to", cc.toClass)
                            put("seal", cc.sealLabel)
                            putJsonObject("stat_diffs") {
                                EngageStat.entries.forEach { st ->
                                    val d = cc.statDiffs[st.ordinal]
                                    if (d != 0) put(st.label, d)
                                }
                            }
                        })
                    }
                })
            }
            if (report.alternativeAdvancedClasses.isNotEmpty()) {
                put("alternative_advanced_classes", JsonArray(report.alternativeAdvancedClasses.map { JsonPrimitive(it.first) }))
            }
            if (report.warnings.isNotEmpty()) {
                put("warnings", JsonArray(report.warnings.map(::JsonPrimitive)))
            }
        }
        val starTag = if (report.usedStarsphere) " · 星玉" else ""
        return AgentToolExecutionOutcome(
            outputJson = json,
            summary = "已推算《Engage》${report.characterName} 固定成长（+${report.totalLevelUps} 级 · ${report.finalClassName}$starTag）",
            engageProposal = AgentEngageProposal(
                report = report,
                requestedStages = stages,
                requestedTotalLevels = totalLevels,
                starsphere = starsphere,
            ),
        )
    }

    private fun execCompareClasses(args: JsonObject): AgentToolExecutionOutcome {
        val charQuery = args.str("character")?.trim().orEmpty()
        val level = args.int("level") ?: return errorOutcome("缺少必需参数 level")
        if (level !in 1..98) return errorOutcome("等级需在 1..98 之间")
        val buddyChar = resolveBuddyCharacter(charQuery)
            ?: return errorOutcome("未找到角色「$charQuery」")
        val profileId = activeProfileId()
        val saved = findSavedSeedDefaults(profileId, buddyChar.id)
        val line = args.str("line")?.let { parseLine(it) } ?: saved?.line ?: BuddyCampaignLine.KAI
        val part3 = args.bool("part3") ?: saved?.part3 ?: buddyChar.part3Only

        val rowSeed: Long = args.long("seed") ?: run {
            val s = saved ?: return errorOutcome("未提供该级乱数种子且本地无检查点，无法计算跳点。")
            if (level < s.level) return errorOutcome("本地检查点为 Lv.${s.level}，无法自动回推 Lv.$level 种子，请直接指定该级种子。")
            var cur = s.seed
            repeat(level - s.level) { cur = SeedEngine.advance(cur) }
            cur
        }

        val legalClasses = rules.professions.filter { rules.classAllowed(buddyChar.id, it.name, part3) }.map { it.name }
        val currentProf = args.str("current_profession")?.takeIf { it in legalClasses }
            ?: saved?.profession?.takeIf { it in legalClasses }
            ?: legalClasses.first()
        val unlocked = (saved?.unlockedProfessions.orEmpty() + currentProf).filterTo(mutableSetOf()) { it in legalClasses }
        val weights = parseWeights(args["weights"] as? JsonObject)

        val options = SeedWorkbenchEngine.compareProfessionsAtRow(
            rules = rules,
            start = SeedWorkbenchStart(
                characterId = buddyChar.id,
                line = line,
                part3 = part3,
                baseSeed = rowSeed,
                inferredLevel = level,
                startLevel = level,
                profession = currentProf,
                ride = saved?.ride,
                unlockedProfessions = unlocked,
            ),
            rowSeed = rowSeed,
            rowLevel = level,
            currentProfession = currentProf,
            unlockedProfessions = unlocked,
            weights = weights,
        )
        val currentOpt = options.firstOrNull { it.isCurrent }
        val json = buildJsonObject {
            put("character_name", buddyChar.name)
            put("level", level)
            put("seed", rowSeed)
            put("current_profession", currentProf)
            currentOpt?.let { cur ->
                putJsonObject("current_gains") {
                    GrowthStat.entries.forEach { st -> put(st.label, cur.gains[st.ordinal]) }
                }
            }
            put("top_options", buildJsonArray {
                options.take(12).forEach { opt ->
                    add(buildJsonObject {
                        put("profession", opt.profession)
                        put("unlocked", opt.unlocked)
                        put("is_current", opt.isCurrent)
                        put("weighted_score", opt.weightedScore)
                        put("total_gain", opt.totalGain)
                        putJsonObject("gains") {
                            GrowthStat.entries.forEach { st ->
                                if (opt.gains[st.ordinal] > 0) put(st.label, opt.gains[st.ordinal])
                            }
                        }
                    })
                }
            })
        }
        return AgentToolExecutionOutcome(
            outputJson = json,
            summary = "已对比 ${buddyChar.name} Lv.$level 全部 ${options.size} 个合法职业跳点",
        )
    }

    private fun execRouteMergeSummary(args: JsonObject): AgentToolExecutionOutcome {
        val filter = args.str("character")?.trim().orEmpty()
        val profileId = activeProfileId()
        val collection = RouteMergeCharacter.readProfile(PersonalStore(context).snapshot(), profileId)
        val matched = collection.records.filter { rec ->
            filter.isEmpty() || rec.character.characterName.contains(filter, ignoreCase = true)
        }
        val json = buildJsonObject {
            put("profile_id", profileId)
            put("count", matched.size)
            put("characters", buildJsonArray {
                matched.take(20).forEach { rec ->
                    val ch = rec.character
                    val comp = ch.compare()
                    add(buildJsonObject {
                        put("character_name", ch.characterName)
                        put("recorded_lines", JsonArray(ch.recordedLines.map { JsonPrimitive(it.label) }))
                        put("selected_lines", JsonArray(comp.selectedLines.map { JsonPrimitive(it.label) }))
                        put("selected_total", comp.mergedMaxTotal)
                        put("full_total", comp.fullMergedMaxTotal)
                        put("delta_vs_full", comp.totalDelta)
                        putJsonObject("merged_stats") {
                            GrowthStat.entries.forEach { st -> put(st.label, comp.mergedMaxStats[st.ordinal]) }
                        }
                    })
                }
            })
        }
        return AgentToolExecutionOutcome(
            outputJson = json,
            summary = "已读取多线融合记录（${matched.size} 名角色）",
        )
    }

    private data class SavedSeedDefault(
        val line: BuddyCampaignLine,
        val part3: Boolean,
        val seed: Long,
        val level: Int,
        val profession: String,
        val unlockedProfessions: Set<String>,
        val stats: List<Int>,
        val ride: com.fireemblem.companion.engine.BuddyRideSelection?,
    )

    private fun findSavedSeedDefaults(profileId: String, buddyCharId: String): SavedSeedDefault? {
        val snap = PersonalStore(context).snapshot()
        val heads = snap.heads().values.mapNotNull { it.singleOrNull() }.filter { !it.deleted }
        val wb = heads.filter { it.kind == SeedWorkbenchRecord.KIND }
            .mapNotNull { runCatching { SeedWorkbenchRecord.decode(it.payload) }.getOrNull() }
            .firstOrNull { it.profileId == profileId && it.characterId == buddyCharId }
        if (wb != null) {
            return SavedSeedDefault(
                line = wb.line,
                part3 = wb.part3,
                seed = wb.baseSeed,
                level = wb.startLevel,
                profession = wb.profession,
                unlockedProfessions = wb.unlockedProfessions,
                stats = List(GrowthStat.entries.size) { 0 },
                ride = wb.ride,
            )
        }
        val j = heads.filter { it.kind == LevelJournal.KIND }
            .mapNotNull { runCatching { LevelJournal.decode(it.payload) }.getOrNull() }
            .firstOrNull { it.profile == profileId && it.characterId == buddyCharId && it.hasCurrentSeedCheckpoint() }
        val cp = j?.seedCheckpoint
        if (j != null && cp != null) {
            return SavedSeedDefault(
                line = j.line,
                part3 = cp.part3,
                seed = cp.seed,
                level = cp.level,
                profession = cp.profession,
                unlockedProfessions = j.confirmedUnlockedProfessions() + cp.profession,
                stats = cp.stats ?: List(GrowthStat.entries.size) { 0 },
                ride = j.observations.lastOrNull()?.ride,
            )
        }
        return null
    }

    private fun resolveBuddyCharacter(query: String): com.fireemblem.companion.engine.BuddyCharacter? {
        val q = query.trim()
        return rules.characters.firstOrNull { it.id.equals(q, ignoreCase = true) }
            ?: rules.characters.firstOrNull { it.name == q }
            ?: rules.characters.firstOrNull { it.name.contains(q, ignoreCase = true) || q.contains(it.name, ignoreCase = true) }
    }

    private fun parseLine(raw: String): BuddyCampaignLine = when (raw.trim().lowercase(Locale.ROOT)) {
        "kai", "凯伊", "凯伊篇", "凯伊线" -> BuddyCampaignLine.KAI
        "dit", "迪托", "迪托利希", "迪托利希篇", "迪托线" -> BuddyCampaignLine.DIT
        "seo", "赛奥", "赛奥朵拉", "赛奥朵拉篇", "赛奥线" -> BuddyCampaignLine.SEO
        "leda", "蕾达", "蕾达篇", "蕾达线" -> BuddyCampaignLine.LEDA
        else -> BuddyCampaignLine.KAI
    }

    private fun parseStatAlias(raw: String): GrowthStat? = when (raw.trim().lowercase(Locale.ROOT)) {
        "hp", "生命" -> GrowthStat.HP
        "力量", "力", "str", "strength" -> GrowthStat.STRENGTH
        "魔力", "魔", "mag", "magic" -> GrowthStat.MAGIC
        "技巧", "技", "dex", "dexterity", "skill" -> GrowthStat.DEXTERITY
        "速度", "速", "spd", "speed" -> GrowthStat.SPEED
        "幸运", "幸", "lck", "luck" -> GrowthStat.LUCK
        "防守", "防御", "防", "def", "defense" -> GrowthStat.DEFENSE
        "魔防", "res", "resistance" -> GrowthStat.RESISTANCE
        "魅力", "魅", "chm", "charm" -> GrowthStat.CHARM
        else -> null
    }

    private fun parseStatSet(arr: JsonArray?): Set<GrowthStat> =
        arr.orEmpty().mapNotNull { el ->
            runCatching { el.jsonPrimitive.content }.getOrNull()?.let(::parseStatAlias)
        }.toSet()

    private fun parseWeights(obj: JsonObject?): List<Int> {
        if (obj == null || obj.isEmpty()) return List(GrowthStat.entries.size) { 1 }
        val out = IntArray(GrowthStat.entries.size) { 0 }
        obj.forEach { (k, v) ->
            val stat = parseStatAlias(k) ?: return@forEach
            val w = runCatching { v.jsonPrimitive.intOrNull }.getOrNull() ?: return@forEach
            out[stat.ordinal] = w.coerceIn(0, 100)
        }
        return if (out.all { it == 0 }) List(GrowthStat.entries.size) { 1 } else out.toList()
    }

    private fun parseThreeHousesWeights(obj: JsonObject?): List<Int> {
        if (obj == null || obj.isEmpty()) return List(ThreeHousesStat.entries.size) { 1 }
        val out = IntArray(ThreeHousesStat.entries.size) { 0 }
        obj.forEach { (k, v) ->
            val st = parseStatAlias(k) ?: return@forEach
            val w = runCatching { v.jsonPrimitive.intOrNull }.getOrNull() ?: return@forEach
            out[st.ordinal] = w.coerceIn(0, 100)
        }
        return if (out.all { it == 0 }) List(ThreeHousesStat.entries.size) { 1 } else out.toList()
    }

    private fun parseEngageWeights(obj: JsonObject?): List<Int> {
        if (obj == null || obj.isEmpty()) return List(EngageStat.entries.size) { 1 }
        val out = IntArray(EngageStat.entries.size) { 0 }
        obj.forEach { (k, v) ->
            val w = runCatching { v.jsonPrimitive.intOrNull }.getOrNull() ?: return@forEach
            val idx = when (k.trim().lowercase(Locale.ROOT)) {
                "hp", "生命" -> EngageStat.HP.ordinal
                "力量", "力", "str", "strength" -> EngageStat.STRENGTH.ordinal
                "魔力", "魔", "mag", "magic" -> EngageStat.MAGIC.ordinal
                "技巧", "技", "dex", "dexterity", "skill" -> EngageStat.DEXTERITY.ordinal
                "速度", "速", "spd", "speed" -> EngageStat.SPEED.ordinal
                "幸运", "幸", "lck", "luck" -> EngageStat.LUCK.ordinal
                "防守", "防御", "防", "def", "defense" -> EngageStat.DEFENSE.ordinal
                "魔防", "res", "resistance" -> EngageStat.RESISTANCE.ordinal
                "体格", "体", "bld", "build", "con" -> EngageStat.BUILD.ordinal
                else -> -1
            }
            if (idx >= 0) out[idx] = w.coerceIn(0, 100)
        }
        return if (out.all { it == 0 }) List(EngageStat.entries.size) { 1 } else out.toList()
    }

    private fun activeProfileId(): String =
        context.getSharedPreferences("personal-view", 0).getString("profile", "global") ?: "global"

    private fun errorOutcome(msg: String) = AgentToolExecutionOutcome(
        outputJson = buildJsonObject { put("error", msg) },
        summary = msg,
        isError = true,
    )

    private fun JsonObject.str(key: String): String? = runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()
    private fun JsonObject.int(key: String): Int? = runCatching { this[key]?.jsonPrimitive?.intOrNull }.getOrNull()
    private fun JsonObject.long(key: String): Long? = runCatching { this[key]?.jsonPrimitive?.longOrNull }.getOrNull()
    private fun JsonObject.bool(key: String): Boolean? = runCatching { this[key]?.jsonPrimitive?.booleanOrNull }.getOrNull()

    companion object {
        /**
         * Builds personalized starter prompts dynamically from the player's actual local save
         * (LevelJournal checkpoints, SeedWorkbench records, RouteMerge characters, Memory Book)
         * and current active game view.
         */
        fun buildDynamicStarterPrompts(
            context: Context,
            activeGame: CompanionGame,
            rules: BuddyRules?,
            memories: List<AgentMemoryEntry>,
        ): List<String> = runCatching {
            val prompts = mutableListOf<String>()
            val profileId = context.getSharedPreferences("personal-view", 0).getString("profile", "global") ?: "global"
            val snap = PersonalStore(context).snapshot()
            val heads = snap.heads().values.mapNotNull { it.singleOrNull() }.filter { !it.deleted }

            // 1. Personal seed checkpoint / workbench prompt
            val journals = heads.filter { it.kind == LevelJournal.KIND }
                .mapNotNull { runCatching { LevelJournal.decode(it.payload) }.getOrNull() }
                .filter { it.profile == profileId && it.hasCurrentSeedCheckpoint() }
            val workbenches = heads.filter { it.kind == SeedWorkbenchRecord.KIND }
                .mapNotNull { runCatching { SeedWorkbenchRecord.decode(it.payload) }.getOrNull() }
                .filter { it.profileId == profileId }

            val topJournal = journals.firstOrNull()
            val topWb = workbenches.firstOrNull()
            if (topJournal != null) {
                val cp = topJournal.seedCheckpoint!!
                val charName = rules?.characters?.firstOrNull { it.id == topJournal.characterId }?.name ?: topJournal.characterId
                val nextTarget = (cp.level + 20).coerceAtMost(99)
                prompts += "按我存档里 $charName（${topJournal.line.label} Lv.${cp.level}）的种子，跳过总成长 <3 并按力量优先规划到 Lv.$nextTarget 的最少转职方案"
                prompts += "帮我查 $charName 在《万缕千丝》的推荐转职路线、个人成长与可习得技能"
            } else if (topWb != null) {
                val charName = rules?.characters?.firstOrNull { it.id == topWb.characterId }?.name ?: topWb.characterId
                prompts += "基于工作台里 $charName（Lv.${topWb.startLevel}）的种子，允许最多考试跳过 15 次重新规划最优方案"
            }

            // 2. Personal RouteMerge prompt
            val mergeRecords = RouteMergeCharacter.readProfile(snap, profileId).records
            val multiMerge = mergeRecords.firstOrNull { it.character.recordedLines.size >= 2 }
            if (multiMerge != null) {
                val ch = multiMerge.character
                val lines = ch.recordedLines.joinToString("/") { it.label.removeSuffix("篇") }
                prompts += "分析我多线融合里「${ch.characterName}」在 $lines ${ch.recordedLines.size} 条线的融合收益与自选路线取舍"
            } else if (mergeRecords.isNotEmpty()) {
                prompts += "汇总我当前多线融合面板中已录入的 ${mergeRecords.size} 名角色并给出后续补线建议"
            }

            // 3. Memory-grounded prompt if user saved build preferences
            memories.firstOrNull { it.category in setOf("build", "preference") }?.let { mem ->
                prompts += "结合我之前记录的偏好（${mem.content.take(24)}），给我下一步培养建议"
            }

            // 4. Active-game contextual fallback fillers (only to reach 4 prompts cleanly)
            val fallbacks = when (activeGame) {
                CompanionGame.THREE_HOUSES -> listOf(
                    "帮我规划《风花雪月》英谷莉特 Lv.1→40 的飞兵期望成长与考试转职路线（含 Lv.10 重甲保底）",
                    "规划《风花雪月》菲力克斯力量速度优先到 Lv.40 的最优职业路线与期望面板",
                    "查《风花雪月》圣天马骑士与飞龙将领的转职条件与成长率对比",
                )
                CompanionGame.ENGAGE -> listOf(
                    "用固定成长推算《Engage》琉尔神龙之子 9 级转神龙之王再升 20 级的逐级精确面板",
                    "对比《Engage》安娜转贤者与魔导骑士开启星玉加护后的 25 级固定成长面板",
                    "查《Engage》琉尔与主要纹章士的结合特技搭配推荐",
                )
                CompanionGame.FORTUNES_WEAVE -> listOf(
                    "查《万缕千丝》四条路线前期最值得按声望优先招募的角色",
                    "帮我规划《风花雪月》英谷莉特 Lv.1→40 的期望成长与转职路线",
                    "用固定成长推算《Engage》琉尔转神龙之王的 25 级精确面板",
                )
            }
            for (f in fallbacks) {
                if (prompts.size >= 4) break
                if (f !in prompts) prompts += f
            }
            prompts.take(4)
        }.getOrElse {
            listOf(
                "查《万缕千丝》四条路线推荐优先招募的角色",
                "查《风花雪月》圣天马骑士的转职条件与成长加成",
                "查看我存档中的角色种子并规划最优转职与跳过方案",
            )
        }
    }
}
