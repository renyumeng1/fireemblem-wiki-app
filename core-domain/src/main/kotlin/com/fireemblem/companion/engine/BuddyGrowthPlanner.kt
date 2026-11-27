package com.fireemblem.companion.engine

data class BuddyProfessionWindow(val profession: String, val fromLevel: Int, val throughLevel: Int)

data class BuddyGrowthPlanRequest(
    val seed: Long,
    val characterId: String,
    val line: BuddyCampaignLine,
    val currentLevel: Int,
    val targetLevel: Int,
    val part3StartLevel: Int?,
    val currentPart3: Boolean = false,
    val initialProfession: String,
    val unlockedProfessions: Set<String>,
    val professionWindows: List<BuddyProfessionWindow>,
    val weights: List<Int>,
    val currentStats: List<Int>,
    val caps: List<Int?>,
    val minimumStats: List<Int?> = List(GrowthStat.entries.size) { null },
    val maxSwitches: Int = 98,
    val maxStates: Int = 50_000,
    val ride: BuddyRideSelection? = null,
)

enum class BuddyGrowthPlanStatus { EXACT, BEST_FOUND, NO_PATH, CANCELLED }

data class BuddyGrowthPlanStep(
    val level: Int,
    val profession: String,
    val seed: Long,
    val nextSeed: Long,
    val gains: List<Int>,
    val switchFrom: String?,
    val ride: BuddyRideSelection? = null,
)

data class BuddyGrowthPlanResult(
    val status: BuddyGrowthPlanStatus,
    val complete: Boolean,
    val optimal: Boolean,
    val steps: List<BuddyGrowthPlanStep>,
    val terminalStats: List<Int>?,
    val weightedScore: Long?,
    val exploredStates: Int,
    val warnings: List<String>,
    val reason: String,
) {
    val hasPlan: Boolean
        get() = terminalStats != null && weightedScore != null
}

object BuddyGrowthPlanner {
    private const val MAX_STAT = 32_767

    private data class Option(val profession: String, val parameters: LevelParameters)
    private data class Candidate(
        val steps: List<BuddyGrowthPlanStep>,
        val stats: List<Int>,
        val score: Long,
        val switches: Int,
        val sequence: List<String>,
    )
    private data class SearchState(
        val level: Int,
        val seed: Long,
        val stats: List<Int>,
        val profession: String,
        val switches: Int,
        val score: Long,
    )

    private enum class Stop { LIMIT, CANCELLED }

    fun plan(
        request: BuddyGrowthPlanRequest,
        rules: BuddyRules,
        isCancelled: () -> Boolean = { false },
    ): BuddyGrowthPlanResult {
        validate(request, rules)
        val known = rules.professions.mapTo(mutableSetOf()) { it.name }
        val windowNames = request.professionWindows.mapTo(mutableSetOf()) { it.profession }
        val warnings = mutableListOf(
            "仅规划已显式解锁且处于指定等级范围内的职业；未模拟考试解锁、武器适用关系或资源消耗。",
            "职业切换不额外推进种子；每次升级只按成长引擎推进一次。",
            "坐骑设置固定用于整个方案；不支持逐级更换坐骑。",
        )
        val unknown = (request.unlockedProfessions + windowNames).filterNot(known::contains).sorted()
        if (unknown.isNotEmpty()) warnings += "未识别的职业已排除：${unknown.joinToString("、")}。"
        val locked = windowNames.filter { it in known && it !in request.unlockedProfessions }.sorted()
        if (locked.isNotEmpty()) warnings += "以下职业未标记为已解锁，因此不会进入规划：${locked.joinToString("、")}。"
        val withoutWindow = request.unlockedProfessions.filter { it in known && it !in windowNames }.sorted()
        if (withoutWindow.isNotEmpty()) warnings += "以下已解锁职业没有等级范围，因此不会进入规划：${withoutWindow.joinToString("、")}。"

        val character = rules.characters.single { it.id == request.characterId }
        val eligible = request.unlockedProfessions.filter { it in known && it in windowNames }.sorted()
        val optionsByLevel = (request.currentLevel until request.targetLevel).associateWith { level ->
            val part3 = request.currentPart3 || (request.part3StartLevel?.let { level >= it } ?: false)
            eligible.mapNotNull { profession ->
                if (request.professionWindows.none {
                        it.profession == profession && level in it.fromLevel..it.throughLevel
                    }
                ) return@mapNotNull null
                if (character.part3Only && !part3) return@mapNotNull null
                if (rules.professionIsPart3Only(profession) && !part3) return@mapNotNull null
                if (!rules.classAllowed(request.characterId, profession, part3)) return@mapNotNull null
                val growth = rules.growth(
                    BuddyGrowthInput(
                        characterId = request.characterId,
                        profession = profession,
                        level = level,
                        part3 = part3,
                        route = request.line.rulesRoute,
                        ride = request.ride,
                    ),
                )
                if (!growth.classAllowed || growth.effectiveRide != request.ride) return@mapNotNull null
                Option(profession, growth.parameters)
            }
        }

        // Exact polynomial-time DP fast path when no caps or minimum-stat constraints are active:
        // Because nextSeed = advance(seed) is independent of profession, every (level, profession)
        // has a fixed gain & score, so exact DP finds the provably global optimum (including switch
        // count & lexicographic tie-breaks) across Lv.1..99 and all 64 classes in a few milliseconds!
        val canUseExactDp = request.caps.all { it == null } &&
            request.minimumStats.all { it == null } &&
            request.currentStats.all { it <= MAX_STAT - 500 } &&
            (request.targetLevel - request.currentLevel) >= 2 &&
            request.maxStates >= 1_000

        if (canUseExactDp) {
            return planWithExactDp(request, optionsByLevel, warnings, isCancelled)
        }

        var explored = 0
        var stop: Stop? = null
        var best: Candidate? = null
        val visited = mutableSetOf<SearchState>()

        fun visit(
            level: Int,
            seed: Long,
            stats: List<Int>,
            previousProfession: String,
            switches: Int,
            score: Long,
            steps: List<BuddyGrowthPlanStep>,
            sequence: List<String>,
        ) {
            if (stop != null) return
            if (isCancelled()) {
                stop = Stop.CANCELLED
                return
            }
            val state = SearchState(level, seed, stats, previousProfession, switches, score)
            if (state in visited) return
            if (explored >= request.maxStates) {
                stop = Stop.LIMIT
                return
            }
            visited += state
            explored++
            if (level == request.targetLevel) {
                val meetsTargets = request.minimumStats.indices.all { index ->
                    request.minimumStats[index]?.let { stats[index] >= it } ?: true
                }
                if (!meetsTargets) return
                val candidate = Candidate(steps, stats, score, switches, sequence)
                if (isBetter(candidate, best)) best = candidate
                return
            }

            for (option in optionsByLevel[level].orEmpty()) {
                val switched = option.profession != previousProfession
                val nextSwitches = switches + if (switched) 1 else 0
                if (nextSwitches > request.maxSwitches) continue
                val room = stats.indices.map { index ->
                    ((request.caps[index] ?: MAX_STAT) - stats[index]).coerceAtLeast(0)
                }
                val simulation = SeedEngine.simulateLevel(seed, option.parameters.copy(room = room))
                val nextStats = stats.indices.map { index -> stats[index] + simulation.gains[index] }
                val nextScore = Math.addExact(score, scoreFor(request.weights, simulation.gains))
                val step = BuddyGrowthPlanStep(
                    level = level,
                    profession = option.profession,
                    seed = seed,
                    nextSeed = simulation.nextSeed,
                    gains = simulation.gains,
                    switchFrom = if (switched) previousProfession else null,
                    ride = request.ride,
                )
                visit(
                    level = level + 1,
                    seed = simulation.nextSeed,
                    stats = nextStats,
                    previousProfession = option.profession,
                    switches = nextSwitches,
                    score = nextScore,
                    steps = steps + step,
                    sequence = sequence + option.profession,
                )
                if (stop != null) return
            }
        }

        visit(
            level = request.currentLevel,
            seed = request.seed,
            stats = request.currentStats,
            previousProfession = request.initialProfession,
            switches = 0,
            score = 0L,
            steps = emptyList(),
            sequence = emptyList(),
        )

        val status = when (stop) {
            Stop.CANCELLED -> BuddyGrowthPlanStatus.CANCELLED
            Stop.LIMIT -> BuddyGrowthPlanStatus.BEST_FOUND
            null -> if (best == null) BuddyGrowthPlanStatus.NO_PATH else BuddyGrowthPlanStatus.EXACT
        }
        val reason = when (status) {
            BuddyGrowthPlanStatus.EXACT -> "已穷举所有符合条件的路径，找到当前约束下的最优方案。"
            BuddyGrowthPlanStatus.NO_PATH -> "已穷举所有符合条件的路径，没有方案满足最低属性要求。"
            BuddyGrowthPlanStatus.BEST_FOUND -> if (best == null) {
                "已达到搜索状态上限，尚未找到满足条件的方案；当前结果不能判定为无解。"
            } else {
                "已达到搜索状态上限，保留当前最佳方案；搜索不完整，不能保证全局最优。"
            }
            BuddyGrowthPlanStatus.CANCELLED -> if (best == null) {
                "规划已取消，尚未找到可行方案。"
            } else {
                "规划已取消，保留取消前找到的最佳方案；不能保证全局最优。"
            }
        }
        return BuddyGrowthPlanResult(
            status = status,
            complete = stop == null,
            optimal = stop == null && best != null,
            steps = best?.steps.orEmpty(),
            terminalStats = best?.stats,
            weightedScore = best?.score,
            exploredStates = explored,
            warnings = warnings,
            reason = reason,
        )
    }

    private fun planWithExactDp(
        request: BuddyGrowthPlanRequest,
        optionsByLevel: Map<Int, List<Option>>,
        warnings: List<String>,
        isCancelled: () -> Boolean,
    ): BuddyGrowthPlanResult {
        val levelCount = request.targetLevel - request.currentLevel
        val maxSw = minOf(request.maxSwitches, levelCount)
        val uncappedRoom = List(GrowthStat.entries.size) { MAX_STAT }

        // Precompute exact deterministic seed for each level
        val seeds = LongArray(levelCount + 1)
        seeds[0] = request.seed
        for (i in 0 until levelCount) {
            seeds[i + 1] = SeedEngine.advance(seeds[i])
        }

        data class DpNode(
            val score: Long,
            val switches: Int,
            val profession: String,
            val sequence: List<String>,
            val step: BuddyGrowthPlanStep?,
            val prev: DpNode?,
        )

        fun isBetterDp(cand: DpNode, inc: DpNode?): Boolean {
            if (inc == null) return true
            if (cand.score != inc.score) return cand.score > inc.score
            if (cand.switches != inc.switches) return cand.switches < inc.switches
            return compareSequences(cand.sequence, inc.sequence) < 0
        }

        var explored = 1
        // Map (profession, switches) -> best DpNode ending at previous level
        var layer = mutableMapOf<Pair<String, Int>, DpNode>(
            (request.initialProfession to 0) to DpNode(
                score = 0L,
                switches = 0,
                profession = request.initialProfession,
                sequence = emptyList(),
                step = null,
                prev = null,
            ),
        )

        for (stepIdx in 0 until levelCount) {
            if (isCancelled()) {
                return BuddyGrowthPlanResult(
                    status = BuddyGrowthPlanStatus.CANCELLED,
                    complete = false,
                    optimal = false,
                    steps = emptyList(),
                    terminalStats = null,
                    weightedScore = null,
                    exploredStates = explored,
                    warnings = warnings,
                    reason = "规划已取消，尚未找到可行方案。",
                )
            }
            val level = request.currentLevel + stepIdx
            val seed = seeds[stepIdx]
            val options = optionsByLevel[level].orEmpty()
            if (options.isEmpty() || layer.isEmpty()) {
                layer = mutableMapOf()
                break
            }

            // Precompute top-2 distinct-profession best nodes per switch count s for O(1) switch transitions!
            val top1BySw = arrayOfNulls<DpNode>(maxSw + 1)
            val top2BySw = arrayOfNulls<DpNode>(maxSw + 1)
            for ((_, node) in layer) {
                val s = node.switches
                val t1 = top1BySw[s]
                if (t1 == null || isBetterDp(node, t1)) {
                    top2BySw[s] = t1
                    top1BySw[s] = node
                } else {
                    val t2 = top2BySw[s]
                    if (t2 == null || isBetterDp(node, t2)) {
                        top2BySw[s] = node
                    }
                }
            }

            val nextLayer = mutableMapOf<Pair<String, Int>, DpNode>()
            for (option in options) {
                val sim = SeedEngine.simulateLevel(seed, option.parameters.copy(room = uncappedRoom))
                val gainScore = scoreFor(request.weights, sim.gains)

                for (s in 0..maxSw) {
                    // Option A: stay in the same profession (0 additional switches)
                    val stayPrev = layer[option.profession to s]
                    // Option B: switch from best different profession at s - 1
                    val switchPrev = if (s > 0) {
                        val t1 = top1BySw[s - 1]
                        if (t1 != null && t1.profession != option.profession) t1
                        else top2BySw[s - 1]?.takeIf { it.profession != option.profession }
                    } else null

                    var chosenPrev: DpNode? = null
                    if (stayPrev != null) chosenPrev = stayPrev
                    if (switchPrev != null) {
                        val stayCand = stayPrev?.let {
                            DpNode(
                                score = Math.addExact(it.score, gainScore),
                                switches = s,
                                profession = option.profession,
                                sequence = it.sequence + option.profession,
                                step = null,
                                prev = it,
                            )
                        }
                        val swCand = DpNode(
                            score = Math.addExact(switchPrev.score, gainScore),
                            switches = s,
                            profession = option.profession,
                            sequence = switchPrev.sequence + option.profession,
                            step = null,
                            prev = switchPrev,
                        )
                        if (stayCand == null || isBetterDp(swCand, stayCand)) {
                            chosenPrev = switchPrev
                        }
                    }

                    if (chosenPrev != null) {
                        val switched = chosenPrev.profession != option.profession
                        val step = BuddyGrowthPlanStep(
                            level = level,
                            profession = option.profession,
                            seed = seed,
                            nextSeed = sim.nextSeed,
                            gains = sim.gains,
                            switchFrom = if (switched) chosenPrev.profession else null,
                            ride = request.ride,
                        )
                        val node = DpNode(
                            score = Math.addExact(chosenPrev.score, gainScore),
                            switches = s,
                            profession = option.profession,
                            sequence = chosenPrev.sequence + option.profession,
                            step = step,
                            prev = chosenPrev,
                        )
                        val key = option.profession to s
                        val cur = nextLayer[key]
                        if (cur == null || isBetterDp(node, cur)) {
                            nextLayer[key] = node
                        }
                        explored++
                    }
                }
            }
            layer = nextLayer
        }

        var bestNode: DpNode? = null
        for ((_, node) in layer) {
            if (isBetterDp(node, bestNode)) bestNode = node
        }
        if (bestNode == null) {
            return BuddyGrowthPlanResult(
                status = BuddyGrowthPlanStatus.NO_PATH,
                complete = true,
                optimal = false,
                steps = emptyList(),
                terminalStats = null,
                weightedScore = null,
                exploredStates = explored,
                warnings = warnings,
                reason = "当前等级段内没有可用的已解锁职业，请检查职业等级门槛或解锁列表。",
            )
        }

        val steps = mutableListOf<BuddyGrowthPlanStep>()
        var cursor: DpNode? = bestNode
        while (cursor?.step != null) {
            steps += cursor.step
            cursor = cursor.prev
        }
        steps.reverse()
        val terminalStats = request.currentStats.indices.map { i ->
            request.currentStats[i] + steps.sumOf { it.gains[i] }
        }
        return BuddyGrowthPlanResult(
            status = BuddyGrowthPlanStatus.EXACT,
            complete = true,
            optimal = true,
            steps = steps,
            terminalStats = terminalStats,
            weightedScore = bestNode.score,
            exploredStates = explored,
            warnings = warnings,
            reason = "已求得当前等级门槛与职业约束下的全局最优方案。",
        )
    }

    private fun validate(request: BuddyGrowthPlanRequest, rules: BuddyRules) {
        require(request.seed in 0 until SeedEngine.UINT32_LIMIT) { "种子应为 0 至 4294967295 的整数" }
        require(rules.characters.any { it.id == request.characterId }) { "该角色暂不支持成长计算" }
        require(rules.professions.any { it.name == request.initialProfession }) { "无法识别当前职业" }
        require(request.currentLevel in 1..98) { "当前等级应为 1 至 98" }
        require(request.targetLevel in (request.currentLevel + 1)..99) { "目标等级应高于当前等级且不超过 99" }
        require(request.part3StartLevel == null || request.part3StartLevel in 1..99) {
            "第三部起始等级应为 1 至 99"
        }
        require(request.unlockedProfessions.all { it.isNotBlank() }) { "已解锁职业名称不能为空" }
        require(request.professionWindows.all { it.profession.isNotBlank() }) { "职业名称不能为空" }
        require(request.professionWindows.all {
            it.fromLevel in 1..99 && it.throughLevel in 1..99 && it.fromLevel <= it.throughLevel
        }) { "职业等级范围应为 1 至 99，且起始等级不能高于结束等级" }
        require(request.weights.size == GrowthStat.entries.size && request.weights.all { it >= 0 }) {
            "九项属性权重必须为非负整数"
        }
        require(request.currentStats.size == GrowthStat.entries.size && request.currentStats.all { it in 0..MAX_STAT }) {
            "当前九项属性必须为 0 至 32767 的整数"
        }
        require(request.caps.size == GrowthStat.entries.size && request.caps.all { it == null || it in 0..MAX_STAT }) {
            "九项属性上限必须为空或为 0 至 32767 的整数"
        }
        require(request.caps.indices.all { request.caps[it] == null || request.caps[it]!! >= request.currentStats[it] }) {
            "属性上限不能低于当前实测属性"
        }
        require(request.minimumStats.size == GrowthStat.entries.size &&
            request.minimumStats.all { it == null || it in 0..MAX_STAT }
        ) { "九项最低属性目标必须为空或为 0 至 32767 的整数" }
        require(request.maxSwitches in 0..98) { "职业切换次数必须为 0 至 98" }
        require(request.maxStates > 0) { "搜索状态上限必须大于 0" }
        require(request.ride == null || request.ride.id.isNotBlank() && request.ride.level in 1..5) { "坐骑选择无效" }
        require(request.ride == null || rules.isKnownRide(request.ride.id)) { "无法识别所选坐骑" }
    }

    private fun scoreFor(weights: List<Int>, gains: List<Int>): Long =
        weights.indices.fold(0L) { total, index ->
            Math.addExact(total, Math.multiplyExact(weights[index].toLong(), gains[index].toLong()))
        }

    private fun isBetter(candidate: Candidate, incumbent: Candidate?): Boolean {
        if (incumbent == null) return true
        if (candidate.score != incumbent.score) return candidate.score > incumbent.score
        if (candidate.switches != incumbent.switches) return candidate.switches < incumbent.switches
        return compareSequences(candidate.sequence, incumbent.sequence) < 0
    }

    private fun compareSequences(left: List<String>, right: List<String>): Int {
        for (index in 0 until minOf(left.size, right.size)) {
            val comparison = left[index].compareTo(right[index])
            if (comparison != 0) return comparison
        }
        return left.size.compareTo(right.size)
    }
}
