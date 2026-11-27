package com.fireemblem.companion.engine

/**
 * Deterministic multi-objective (minimum switches + optional exam skips vs. weighted stat gain)
 * exact 2D dynamic-programming Pareto planner over [BuddyRules] and [SeedEngine].
 *
 * Supports:
 * - Hard skip filters (`minTotalGain`, `minWeightedGain`, `requiredStats`, `maxExamProbability`)
 *   to skip unwanted low-roll level-ups via exam/advance.
 * - Weight-optimal exam skipping (`optimizeSkips = true`, `maxSkips > 0`) to automatically pick
 *   the highest-value RNG rolls across `currentLevel..targetLevel`.
 * - Exact replay parity with [SeedWorkbenchEngine.evaluate] (`SKIP` + `CLASS_CHANGE` + `UPGRADE`).
 */
data class ParetoGrowthPlanRequest(
    val seed: Long,
    val characterId: String,
    val line: BuddyCampaignLine = BuddyCampaignLine.KAI,
    val currentLevel: Int,
    val targetLevel: Int,
    val currentPart3: Boolean = false,
    val part3StartLevel: Int? = null,
    val initialProfession: String,
    val unlockedProfessions: Set<String> = setOf(initialProfession),
    val includeLockedPreview: Boolean = false,
    val weights: List<Int>,
    val currentStats: List<Int> = List(GrowthStat.entries.size) { 0 },
    val ride: BuddyRideSelection? = null,
    val maxSwitchBudget: Int = 6,
    /** Maximum number of exam/skip RNG advances allowed. */
    val maxSkips: Int = 0,
    /** When true (or when any skip filter is set), DP may insert exam SKIPs to maximize weighted score. */
    val optimizeSkips: Boolean = false,
    /** Hard filter: require each actual upgrade's total stat points >= this value (e.g., 3 skips <3). */
    val minTotalGain: Int? = null,
    /** Hard filter: require each actual upgrade's weighted score >= this value. */
    val minWeightedGain: Int? = null,
    /** Hard filter: require each actual upgrade to proc > 0 in all listed stats. */
    val requiredStats: Set<GrowthStat> = emptySet(),
    /** Optional constraint: only allow exam SKIP on rolls with exam minimum probability <= this %. */
    val maxExamProbability: Int? = null,
    /**
     * Explicit earliest level at which any NOT-YET-UNLOCKED profession may be entered.
     * When null and the character has only Tier 0 (平民/贵族) unlocked below Lv.10, defaults conservatively
     * to Lv.10 so early-game plans never suggest impossible promotions before certification exams open.
     */
    val minPromotionLevel: Int? = null,
    /** When true, Part-1 route-exclusive classes from other campaign lines are excluded unless already unlocked. */
    val enforceRouteAvailability: Boolean = true,
    /** Optional per-tier unlock level overrides (e.g. `{1: 10, 2: 20, 3: 35}`). */
    val tierUnlockLevelOverrides: Map<Int, Int> = emptyMap(),
) {
    val hasSkipFilters: Boolean
        get() = (minTotalGain != null && minTotalGain > 0) ||
            (minWeightedGain != null && minWeightedGain > 0) ||
            requiredStats.isNotEmpty()

    val effectiveMaxSkips: Int
        get() = when {
            maxSkips > 0 -> maxSkips.coerceAtMost(ParetoGrowthPlanner.MAX_ALLOWED_SKIPS)
            hasSkipFilters -> minOf(ParetoGrowthPlanner.MAX_ALLOWED_SKIPS, maxOf(15, targetLevel - currentLevel))
            optimizeSkips -> minOf(ParetoGrowthPlanner.MAX_ALLOWED_SKIPS, 10)
            else -> 0
        }
}

data class ParetoSwitchEvent(
    val atLevel: Int,
    val fromProfession: String,
    val toProfession: String,
    val gainsAtLevel: List<Int>,
    val baselineGainsAtLevel: List<Int>,
    val scoreDeltaAtLevel: Long,
    val requiresUnlockExam: Boolean = false,
    val targetTier: Int = 0,
    val unlockRequirement: String? = null,
)

data class ParetoSkipEvent(
    val atLevel: Int,
    val rngStepIndex: Int, // 1-based RNG step from start
    val seed: Long,
    val professionAtSkip: String,
    val skippedGains: List<Int>,
    val skippedTotal: Int,
    val skippedWeightedScore: Long,
    val examMinimumProbability: Int,
    val reason: String,
)

data class ParetoPlanTier(
    val switches: Int,
    val skips: Int = 0,
    val weightedScore: Long,
    val scoreDeltaVsBaseline: Long,
    val totalStatGain: Int,
    val statGains: List<Int>,
    val statGainsDeltaVsBaseline: List<Int>,
    val terminalStats: List<Int>,
    val switchEvents: List<ParetoSwitchEvent>,
    val skipEvents: List<ParetoSkipEvent> = emptyList(),
    val steps: List<BuddyGrowthPlanStep>,
    val workbenchSteps: List<SeedWorkbenchPlanStep>,
    val label: String,
)

data class ParetoGrowthPlanReport(
    val characterId: String,
    val characterName: String,
    val line: BuddyCampaignLine,
    val seed: Long,
    val fromLevel: Int,
    val targetLevel: Int,
    val part3: Boolean,
    val initialProfession: String,
    val candidateProfessions: Set<String>,
    val unlockedProfessionsInput: Set<String> = setOf(initialProfession),
    val effectiveMinPromotionLevel: Int? = null,
    val weights: List<Int>,
    val ride: BuddyRideSelection?,
    val maxSkipsAllowed: Int = 0,
    val minTotalGainFilter: Int? = null,
    val minWeightedGainFilter: Int? = null,
    val requiredStatsFilter: Set<GrowthStat> = emptySet(),
    val baseline: ParetoPlanTier,
    val paretoTiers: List<ParetoPlanTier>,
    val globalBestMinSwitches: Int,
    val globalBestScore: Long,
    val warnings: List<String>,
) {
    /** Recommended tier: fewest switches achieving the best score within budget (or global best). */
    val recommendedTier: ParetoPlanTier
        get() = paretoTiers.lastOrNull() ?: baseline
}

object ParetoGrowthPlanner {
    private const val MAX_ALLOWED_BUDGET = 10
    const val MAX_ALLOWED_SKIPS = 45
    const val EARLY_LOCKED_PROMOTION_DEFAULT_LEVEL = 10
    private const val NEG_INF = Long.MIN_VALUE / 4

    // Backpointer encoding into Short:
    // -1 = root/none; -2 = SKIP from (u, k-1, p, s); >= 0 = UPGRADE from prevProfIndex (same s if prevP==p else s-1)
    private const val BACK_NONE: Short = -1
    private const val BACK_SKIP: Short = -2

    fun solve(
        request: ParetoGrowthPlanRequest,
        rules: BuddyRules,
        isCancelled: () -> Boolean = { false },
    ): ParetoGrowthPlanReport {
        require(request.seed in 0 until SeedEngine.UINT32_LIMIT) { "种子应为 0 至 4,294,967,295 的整数" }
        require(request.currentLevel in 1..98) { "起始等级应为 1 至 98" }
        require(request.targetLevel in (request.currentLevel + 1)..99) {
            "目标等级应高于起始等级（Lv.${request.currentLevel}）且不超过 99"
        }
        require(request.weights.size == GrowthStat.entries.size) { "权重必须包含 9 项基础属性" }
        require(request.currentStats.size == GrowthStat.entries.size) { "当前属性必须包含 9 项基础属性" }

        val character = requireNotNull(rules.characters.firstOrNull { it.id == request.characterId }) {
            "角色「${request.characterId}」暂不支持乱数成长规划"
        }
        val effectivePart3 = request.currentPart3 || character.part3Only
        val allowPart3Future = effectivePart3 || (request.part3StartLevel != null && request.part3StartLevel < request.targetLevel)
        require(rules.classAllowed(request.characterId, request.initialProfession, effectivePart3)) {
            "角色「${character.name}」在当前篇章无法处于初始职业「${request.initialProfession}」"
        }

        val normalizedWeights = request.weights.map { it.coerceIn(0, 100) }
        val knownNames = rules.professions.mapTo(mutableSetOf()) { it.name }
        val explicitlyUnlocked = (request.unlockedProfessions + request.initialProfession)
            .filterTo(linkedSetOf()) { it in knownNames && rules.classAllowed(request.characterId, it, allowPart3Future) }

        val candidateList = if (request.includeLockedPreview) {
            rules.professions
                .filter { prof ->
                    rules.classAllowed(request.characterId, prof.name, allowPart3Future) &&
                        (prof.name in explicitlyUnlocked || !request.enforceRouteAvailability ||
                            rules.isClassObtainableInRoute(prof.name, request.line, allowPart3Future))
                }
                .map { it.name }
                .sorted()
        } else {
            explicitlyUnlocked.sorted()
        }
        val initialProfIdx = candidateList.indexOf(request.initialProfession)
        require(initialProfIdx >= 0) { "初始职业「${request.initialProfession}」不在合法候选中" }

        val levelSpan = request.targetLevel - request.currentLevel
        val budget = request.maxSwitchBudget.coerceIn(0, minOf(MAX_ALLOWED_BUDGET, levelSpan))
        val maxSkips = request.effectiveMaxSkips
        val profCount = candidateList.size

        // Compute realistic per-profession earliest allowed level:
        // - Already unlocked classes can be entered at currentLevel.
        // - Locked classes honor tier unlock levels AND earliest promotion-open level.
        // - If character currently only has Tier 0 (平民/贵族) unlocked below Lv.10 and caller did not
        //   explicitly set minPromotionLevel, conservatively gate locked Tier 1+ preview to Lv.10+
        //   because early-game chapters haven't unlocked the Temple certification system yet.
        val hasAnyPromotedUnlocked = explicitlyUnlocked.any { rules.professionTier(it) >= 1 }
        val usedEarlyPromotionDefault = request.includeLockedPreview &&
            request.minPromotionLevel == null &&
            !hasAnyPromotedUnlocked &&
            request.currentLevel < EARLY_LOCKED_PROMOTION_DEFAULT_LEVEL
        val effectiveMinPromoLevel: Int? = when {
            request.minPromotionLevel != null -> request.minPromotionLevel.coerceIn(request.currentLevel, 99)
            usedEarlyPromotionDefault -> EARLY_LOCKED_PROMOTION_DEFAULT_LEVEL
            else -> null
        }

        val profMinLevel = IntArray(profCount) { idx ->
            val name = candidateList[idx]
            if (name in explicitlyUnlocked) {
                request.currentLevel
            } else {
                val tier = rules.professionTier(name)
                val tierUnlock = request.tierUnlockLevelOverrides[tier]
                    ?: BuddyRules.defaultUnlockLevelForTier(tier)
                maxOf(request.currentLevel, tierUnlock, effectiveMinPromoLevel ?: 1)
            }
        }
        val profPart3Only = BooleanArray(profCount) { idx -> rules.professionIsPart3Only(candidateList[idx]) }

        // Precompute exact RNG seeds & exam minimum probabilities across L + maxSkips rolls
        val totalRolls = levelSpan + maxSkips
        val seeds = LongArray(totalRolls + 1)
        seeds[0] = request.seed
        for (t in 0 until totalRolls) {
            seeds[t + 1] = SeedEngine.advance(seeds[t])
        }
        val examMinProb = IntArray(totalRolls) { t ->
            SeedEngine.simulateExam(seeds[t], ExamParameters(100.0)).minimumProbability
        }

        // Precompute LevelParameters for (levelOffset 0 until levelSpan, profIdx 0 until profCount)
        val levelParams = Array(levelSpan) { u ->
            val lv = request.currentLevel + u
            val isPart3AtLv = effectivePart3 || (request.part3StartLevel?.let { lv >= it } ?: false)
            Array<LevelParameters?>(profCount) { p ->
                val profName = candidateList[p]
                if (lv < profMinLevel[p]) return@Array null
                if (character.part3Only && !isPart3AtLv) return@Array null
                if (profPart3Only[p] && !isPart3AtLv) return@Array null
                if (!rules.classAllowed(request.characterId, profName, isPart3AtLv)) return@Array null
                if (request.enforceRouteAvailability && profName !in explicitlyUnlocked &&
                    !rules.isClassObtainableInRoute(profName, request.line, isPart3AtLv)
                ) return@Array null
                val g = rules.growth(
                    BuddyGrowthInput(
                        characterId = request.characterId,
                        profession = profName,
                        level = lv,
                        part3 = isPart3AtLv,
                        route = request.line.rulesRoute,
                        ride = request.ride,
                    ),
                )
                if (!g.classAllowed || g.effectiveRide != request.ride) null else g.parameters
            }
        }

        // Compute untouched baseline (0 switches, 0 skips, no filters)
        val baselineSteps = buildList {
            for (u in 0 until levelSpan) {
                val params = levelParams[u][initialProfIdx]
                    ?: throw IllegalArgumentException("无法生成不转职基准方案：Lv.${request.currentLevel + u} 无法保持「${request.initialProfession}」")
                val sim = SeedEngine.simulateLevel(seeds[u], params)
                add(
                    BuddyGrowthPlanStep(
                        level = request.currentLevel + u,
                        profession = request.initialProfession,
                        seed = seeds[u],
                        nextSeed = sim.nextSeed,
                        gains = sim.gains,
                        switchFrom = null,
                        ride = request.ride,
                    ),
                )
            }
        }
        val baselineGains = sumGains(baselineSteps)
        val baselineScore = weightedScore(baselineGains, normalizedWeights)
        val baselineTerminalStats = request.currentStats.indices.map { i -> request.currentStats[i] + baselineGains[i] }

        // Run exact 2D DP across (u = 0..levelSpan, k = 0..maxSkips, p = 0..profCount-1, s = 0..budget)
        // Also run a lightweight unconstrained-switch DP (up to levelSpan switches) ONLY when we want
        // to inform the user if extra switches beyond budget could yield even higher score.
        val solveSwitchLimit = budget
        val strideS = solveSwitchLimit + 1
        val strideP = profCount * strideS
        val layerSize = (maxSkips + 1) * strideP

        var prevScore = LongArray(layerSize) { NEG_INF }
        // Keep backpointers for all u = 0..levelSpan to reconstruct any (s, k) tier cleanly
        val backPtrs = Array(levelSpan + 1) { ShortArray(layerSize) { BACK_NONE } }

        // Initial state at u = 0, k = 0, p = initialProfIdx, s = 0
        prevScore[initialProfIdx * strideS] = 0L

        // Helper: propagate SKIP edges across k = 1..maxSkips within a fixed u layer
        fun propagateSkipsInLayer(u: Int, scores: LongArray, back: ShortArray) {
            if (maxSkips <= 0) return
            for (k in 1..maxSkips) {
                val skippedRoll = u + k - 1
                if (skippedRoll >= totalRolls) break
                if (request.maxExamProbability != null && examMinProb[skippedRoll] > request.maxExamProbability) continue
                val basePrev = (k - 1) * strideP
                val baseCur = k * strideP
                for (i in 0 until strideP) {
                    val cand = scores[basePrev + i]
                    if (cand > NEG_INF / 2 && cand > scores[baseCur + i]) {
                        // Strict `>` guarantees tie-break prefers fewer skips!
                        scores[baseCur + i] = cand
                        back[baseCur + i] = BACK_SKIP
                    }
                }
            }
        }

        // Allow skipping before the very first upgrade (at u = 0)
        propagateSkipsInLayer(0, prevScore, backPtrs[0])

        val minTotal = request.minTotalGain?.takeIf { it > 0 }
        val minWeighted = request.minWeightedGain?.takeIf { it > 0 }
        val reqStats = request.requiredStats

        for (u in 1..levelSpan) {
            if (isCancelled()) break
            val curScore = LongArray(layerSize) { NEG_INF }
            val curBack = backPtrs[u]
            val paramsAtLv = levelParams[u - 1]

            for (k in 0..maxSkips) {
                val rollIdx = (u - 1) + k
                if (rollIdx >= totalRolls) break
                val rollSeed = seeds[rollIdx]
                val prevKOffset = k * strideP
                val curKOffset = k * strideP

                // Precompute Top-2 best professions in prevScore[k, *, sPrev] for O(P) switch transitions!
                val top1Score = LongArray(strideS) { NEG_INF }
                val top1Prof = IntArray(strideS) { -1 }
                val top2Score = LongArray(strideS) { NEG_INF }
                val top2Prof = IntArray(strideS) { -1 }
                for (sPrev in 0 until solveSwitchLimit) {
                    for (pPrev in 0 until profCount) {
                        val sc = prevScore[prevKOffset + pPrev * strideS + sPrev]
                        if (sc <= NEG_INF / 2) continue
                        if (sc > top1Score[sPrev]) {
                            top2Score[sPrev] = top1Score[sPrev]
                            top2Prof[sPrev] = top1Prof[sPrev]
                            top1Score[sPrev] = sc
                            top1Prof[sPrev] = pPrev
                        } else if (sc > top2Score[sPrev]) {
                            top2Score[sPrev] = sc
                            top2Prof[sPrev] = pPrev
                        }
                    }
                }

                for (p in 0 until profCount) {
                    val params = paramsAtLv[p] ?: continue
                    val sim = SeedEngine.simulateLevel(rollSeed, params)
                    val gains = sim.gains
                    val gainTotal = gains.sum()
                    if (minTotal != null && gainTotal < minTotal) continue
                    val stepWScore = weightedScore(gains, normalizedWeights)
                    if (minWeighted != null && stepWScore < minWeighted) continue
                    if (reqStats.isNotEmpty() && reqStats.any { gains[it.ordinal] <= 0 }) continue

                    val pCurBase = curKOffset + p * strideS
                    val pPrevBase = prevKOffset + p * strideS

                    for (s in 0..solveSwitchLimit) {
                        var bestVal = NEG_INF
                        var bestPrevP = -1

                        // Option A: stay in profession p (0 additional switches)
                        val stayPrev = prevScore[pPrevBase + s]
                        if (stayPrev > NEG_INF / 2) {
                            bestVal = stayPrev + stepWScore
                            bestPrevP = p
                        }

                        // Option B: switch from best other profession at s - 1
                        if (s > 0) {
                            val sPrev = s - 1
                            val bestOtherP = if (top1Prof[sPrev] != p) top1Prof[sPrev] else top2Prof[sPrev]
                            val bestOtherSc = if (top1Prof[sPrev] != p) top1Score[sPrev] else top2Score[sPrev]
                            if (bestOtherP >= 0 && bestOtherSc > NEG_INF / 2) {
                                val cand = bestOtherSc + stepWScore
                                if (cand > bestVal) {
                                    bestVal = cand
                                    bestPrevP = bestOtherP
                                }
                            }
                        }

                        if (bestPrevP >= 0 && bestVal > curScore[pCurBase + s]) {
                            curScore[pCurBase + s] = bestVal
                            curBack[pCurBase + s] = bestPrevP.toShort()
                        }
                    }
                }
            }

            // Allow exam skips after finishing u upgrades (for u < levelSpan)
            if (u < levelSpan) {
                propagateSkipsInLayer(u, curScore, curBack)
            }
            prevScore = curScore
        }

        // Extract best path for each switch budget s = 0..solveSwitchLimit (preferring fewest skips on tie!)
        data class SolvedEndpoint(
            val switches: Int,
            val skips: Int,
            val endProf: Int,
            val score: Long,
        )

        fun bestEndpointForSwitch(s: Int): SolvedEndpoint? {
            var best: SolvedEndpoint? = null
            for (k in 0..maxSkips) {
                val kOff = k * strideP
                for (p in 0 until profCount) {
                    val sc = prevScore[kOff + p * strideS + s]
                    if (sc <= NEG_INF / 2) continue
                    val cand = SolvedEndpoint(switches = s, skips = k, endProf = p, score = sc)
                    if (best == null || cand.score > best.score || (cand.score == best.score && cand.skips < best.skips)) {
                        best = cand
                    }
                }
            }
            return best
        }

        fun reconstruct(ep: SolvedEndpoint): Pair<List<BuddyGrowthPlanStep>, List<ParetoSkipEvent>> {
            val stepsRev = mutableListOf<BuddyGrowthPlanStep>()
            val skipsRev = mutableListOf<ParetoSkipEvent>()
            var u = levelSpan
            var k = ep.skips
            var p = ep.endProf
            var s = ep.switches

            while (u > 0 || k > 0) {
                val ptr = backPtrs[u][k * strideP + p * strideS + s]
                check(ptr != BACK_NONE) { "DP 回溯路径断裂 (u=$u, k=$k, p=$p, s=$s)" }
                if (ptr == BACK_SKIP) {
                    val skippedRoll = u + k - 1
                    val lvAtSkip = (request.currentLevel + u).coerceAtMost(98)
                    val paramsForInfo = levelParams[u.coerceAtMost(levelSpan - 1)][p]
                        ?: rules.growth(
                            BuddyGrowthInput(
                                request.characterId,
                                candidateList[p],
                                lvAtSkip,
                                effectivePart3,
                                request.line.rulesRoute,
                                request.ride,
                            ),
                        ).parameters
                    val sim = SeedEngine.simulateLevel(seeds[skippedRoll], paramsForInfo)
                    val skippedTotal = sim.gains.sum()
                    val skippedW = weightedScore(sim.gains, normalizedWeights)
                    val reason = buildString {
                        when {
                            minTotal != null && skippedTotal < minTotal -> append("总成长 +$skippedTotal < $minTotal，考试跳过")
                            minWeighted != null && skippedW < minWeighted -> append("加权分 $skippedW < $minWeighted，考试跳过")
                            reqStats.isNotEmpty() && reqStats.any { sim.gains[it.ordinal] <= 0 } -> {
                                val miss = reqStats.filter { sim.gains[it.ordinal] <= 0 }.joinToString("/") { it.label }
                                append("缺少 $miss 跳点，考试跳过")
                            }
                            else -> append("按权重考试跳过（原 +$skippedTotal / ${skippedW}分）")
                        }
                    }
                    skipsRev += ParetoSkipEvent(
                        atLevel = request.currentLevel + u,
                        rngStepIndex = skippedRoll + 1,
                        seed = seeds[skippedRoll],
                        professionAtSkip = candidateList[p],
                        skippedGains = sim.gains,
                        skippedTotal = skippedTotal,
                        skippedWeightedScore = skippedW,
                        examMinimumProbability = examMinProb[skippedRoll],
                        reason = reason,
                    )
                    k -= 1
                } else {
                    val prevP = ptr.toInt()
                    val rollIdx = (u - 1) + k
                    val params = requireNotNull(levelParams[u - 1][p])
                    val sim = SeedEngine.simulateLevel(seeds[rollIdx], params)
                    stepsRev += BuddyGrowthPlanStep(
                        level = request.currentLevel + (u - 1),
                        profession = candidateList[p],
                        seed = seeds[rollIdx],
                        nextSeed = sim.nextSeed,
                        gains = sim.gains,
                        switchFrom = if (prevP != p) candidateList[prevP] else null,
                        ride = request.ride,
                    )
                    if (prevP != p) s -= 1
                    p = prevP
                    u -= 1
                }
            }
            return stepsRev.asReversed() to skipsRev.asReversed()
        }

        val endpointsBySwitch = (0..solveSwitchLimit).mapNotNull { s -> bestEndpointForSwitch(s) }
        if (endpointsBySwitch.isEmpty()) {
            val filterDesc = buildList {
                if (minTotal != null) add("总成长 ≥ $minTotal")
                if (minWeighted != null) add("加权分 ≥ $minWeighted")
                if (reqStats.isNotEmpty()) add("必加 ${reqStats.joinToString("/") { it.label }}")
                if (request.maxExamProbability != null) add("跳过考试通过率 ≤ ${request.maxExamProbability}%")
            }.joinToString("、")
            throw IllegalArgumentException(
                "在最多考试跳过 $maxSkips 次的预算内，无法找到满足「$filterDesc」的可行方案；请适当提高跳过次数上限或放宽过滤条件。",
            )
        }

        // Build untouched baseline tier (0 switches, 0 skips)
        val baselineTier = ParetoPlanTier(
            switches = 0,
            skips = 0,
            weightedScore = baselineScore,
            scoreDeltaVsBaseline = 0L,
            totalStatGain = baselineGains.sum(),
            statGains = baselineGains,
            statGainsDeltaVsBaseline = List(GrowthStat.entries.size) { 0 },
            terminalStats = baselineTerminalStats,
            switchEvents = emptyList(),
            skipEvents = emptyList(),
            steps = baselineSteps,
            workbenchSteps = toWorkbenchSteps(baselineSteps, emptyList(), request.currentLevel),
            label = "不转职不跳过基准",
        )

        // Assemble strictly improving Pareto tiers
        val keptTiers = mutableListOf<ParetoPlanTier>()
        var lastScore = Long.MIN_VALUE

        // If no skip filters are active, include untouched baseline first
        if (!request.hasSkipFilters) {
            keptTiers += baselineTier
            lastScore = baselineScore
        }

        val topScore = endpointsBySwitch.maxOf { it.score }
        val topMinSwitch = endpointsBySwitch.first { it.score == topScore }.switches

        for (ep in endpointsBySwitch) {
            if (ep.score <= lastScore && keptTiers.isNotEmpty()) continue
            val (steps, skips) = reconstruct(ep)
            val actualSwitches = steps.count { it.switchFrom != null }
            val actualSkips = skips.size
            // Skip duplicate of baseline when actualSwitches==0 and actualSkips==0
            if (keptTiers.isNotEmpty() && actualSwitches == 0 && actualSkips == 0) continue

            val gains = sumGains(steps)
            val deltaGains = gains.indices.map { i -> gains[i] - baselineGains[i] }
            val termStats = request.currentStats.indices.map { i -> request.currentStats[i] + gains[i] }
            val switchEvents = steps.mapIndexedNotNull { idx, step ->
                val from = step.switchFrom ?: return@mapIndexedNotNull null
                val baseG = baselineSteps.getOrNull(idx)?.gains ?: List(GrowthStat.entries.size) { 0 }
                val needsExam = step.profession !in explicitlyUnlocked
                ParetoSwitchEvent(
                    atLevel = step.level,
                    fromProfession = from,
                    toProfession = step.profession,
                    gainsAtLevel = step.gains,
                    baselineGainsAtLevel = baseG,
                    scoreDeltaAtLevel = weightedScore(step.gains, normalizedWeights) - weightedScore(baseG, normalizedWeights),
                    requiresUnlockExam = needsExam,
                    targetTier = rules.professionTier(step.profession),
                    unlockRequirement = if (needsExam) rules.examRequirementSummary(step.profession) else null,
                )
            }

            val isBest = ep.score >= topScore
            val skipTag = if (actualSkips > 0) "·跳${actualSkips}次" else ""
            val label = when {
                actualSwitches == 0 && actualSkips > 0 -> "不转职·跳${actualSkips}次" + if (isBest) "·最优" else ""
                actualSwitches == 0 -> "不转职即最优"
                isBest -> "转 $actualSwitches 次$skipTag · 最优"
                else -> "转 $actualSwitches 次$skipTag"
            }

            keptTiers += ParetoPlanTier(
                switches = actualSwitches,
                skips = actualSkips,
                weightedScore = ep.score,
                scoreDeltaVsBaseline = ep.score - baselineScore,
                totalStatGain = gains.sum(),
                statGains = gains,
                statGainsDeltaVsBaseline = deltaGains,
                terminalStats = termStats,
                switchEvents = switchEvents,
                skipEvents = skips,
                steps = steps,
                workbenchSteps = toWorkbenchSteps(steps, skips, request.currentLevel),
                label = label,
            )
            lastScore = ep.score
        }

        // Clean label for baseline when it is already the sole optimal tier
        val finalTiers = if (keptTiers.size == 1 && keptTiers.first().switches == 0 && keptTiers.first().skips == 0) {
            listOf(keptTiers.first().copy(label = "不转职即最优"))
        } else {
            keptTiers
        }

        val warnings = buildList {
            if (usedEarlyPromotionDefault) {
                add("当前仅解锁基础职业（${explicitlyUnlocked.joinToString("/")}），默认限制未解锁职业从 Lv.$EARLY_LOCKED_PROMOTION_DEFAULT_LEVEL 起预演转职（避免前期转职未开时给出不可行建议；若进度已开放初级考试且武器达 D，可手动调低「最早转职等级」）。")
            } else if (request.minPromotionLevel != null && request.includeLockedPreview) {
                add("已按设定限制未解锁职业最早从 Lv.${request.minPromotionLevel} 起转职。")
            }
            if (request.includeLockedPreview) {
                val routeHint = if (request.enforceRouteAvailability && !effectivePart3) "，已过滤非「${request.line.label}」路线限定职业" else ""
                add("已启用合法职业预演（含未解锁目标$routeHint）；标注「需考试解锁」的节点需先达标并通过资格考试。")
            }
            if (request.hasSkipFilters || maxSkips > 0) {
                val activeRules = buildList {
                    if (minTotal != null) add("过滤总成长 < $minTotal")
                    if (minWeighted != null) add("过滤加权分 < $minWeighted")
                    if (reqStats.isNotEmpty()) add("必加 ${reqStats.joinToString("/") { it.label }}")
                    if (request.optimizeSkips) add("按权重自动优选跳过")
                    add("跳过上限 $maxSkips 次")
                }
                add("已启用考试跳过规划（${activeRules.joinToString(" · ")}）；工作台中已自动插入「跳过 / 考试」步骤。")
            }
            if (normalizedWeights.all { it == 0 }) {
                add("所有属性权重均为 0，建议至少为目标属性设置正权重（例如力量 = 5）。")
            }
        }

        return ParetoGrowthPlanReport(
            characterId = request.characterId,
            characterName = character.name,
            line = request.line,
            seed = request.seed,
            fromLevel = request.currentLevel,
            targetLevel = request.targetLevel,
            part3 = effectivePart3,
            initialProfession = request.initialProfession,
            candidateProfessions = candidateList.toSet(),
            unlockedProfessionsInput = explicitlyUnlocked.toSet(),
            effectiveMinPromotionLevel = effectiveMinPromoLevel,
            weights = normalizedWeights,
            ride = request.ride,
            maxSkipsAllowed = maxSkips,
            minTotalGainFilter = minTotal,
            minWeightedGainFilter = minWeighted,
            requiredStatsFilter = reqStats,
            baseline = baselineTier,
            paretoTiers = finalTiers,
            globalBestMinSwitches = topMinSwitch,
            globalBestScore = topScore,
            warnings = warnings,
        )
    }

    fun toWorkbenchSteps(
        steps: List<BuddyGrowthPlanStep>,
        skipEvents: List<ParetoSkipEvent> = emptyList(),
        startLevel: Int = steps.firstOrNull()?.level ?: 1,
    ): List<SeedWorkbenchPlanStep> = buildList {
        // Group skip events by the level at which they occur (BEFORE upgrading out of that level)
        val skipsByLevel = skipEvents.groupBy { it.atLevel }
        // Handle any skips occurring at startLevel before the first upgrade
        var curLv = startLevel
        for (step in steps) {
            while (curLv <= step.level) {
                skipsByLevel[curLv].orEmpty().forEach {
                    add(SeedWorkbenchPlanStep(SeedPlanActionKind.SKIP))
                }
                curLv++
            }
            if (step.switchFrom != null) {
                add(SeedWorkbenchPlanStep(SeedPlanActionKind.CLASS_CHANGE, targetProfession = step.profession))
            }
            add(SeedWorkbenchPlanStep(SeedPlanActionKind.UPGRADE))
        }
    }

    private fun sumGains(steps: List<BuddyGrowthPlanStep>): List<Int> {
        val totals = IntArray(GrowthStat.entries.size)
        for (step in steps) {
            for (i in totals.indices) totals[i] += step.gains[i]
        }
        return totals.toList()
    }

    private fun weightedScore(gains: List<Int>, weights: List<Int>): Long {
        var total = 0L
        for (i in gains.indices) total += gains[i].toLong() * weights[i].toLong()
        return total
    }
}
