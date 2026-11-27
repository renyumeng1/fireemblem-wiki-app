package com.fireemblem.companion.engine

data class FixedGrowthState(
    val accumulators: List<Int>,
    val stats: List<Int>,
) {
    init {
        require(accumulators.size == GrowthStat.entries.size) { "固定成长累积值长度无效" }
        require(stats.size == GrowthStat.entries.size) { "属性数组长度无效" }
    }
}

data class FixedGrowthStep(
    val levelFrom: Int,
    val levelTo: Int,
    val effectiveRates: List<Int>,
    val naturalGains: List<Int>,
    val totalGains: List<Int>,
    val guaranteedIndices: List<Int>,
    val hasTieBreakCaveat: Boolean,
    val stateAfter: FixedGrowthState,
)

object FixedGrowthEngine {
    fun initialAccumulators(personalGrowth: List<Int>): List<Int> {
        require(personalGrowth.size == GrowthStat.entries.size) { "个人成长率长度无效" }
        return personalGrowth.map { rate ->
            val mod = (3 * rate) % 100
            if (mod < 0) mod + 100 else mod
        }
    }

    fun initialState(personalGrowth: List<Int>, startStats: List<Int>): FixedGrowthState =
        FixedGrowthState(
            accumulators = initialAccumulators(personalGrowth),
            stats = startStats.toList(),
        )

    fun step(
        levelFrom: Int,
        state: FixedGrowthState,
        effectiveRates: List<Int>,
        caps: List<Int>,
        guaranteePoints: Int,
    ): FixedGrowthStep {
        val count = GrowthStat.entries.size
        require(effectiveRates.size == count) { "有效成长率长度无效" }
        require(caps.size == count) { "属性上限长度无效" }
        require(guaranteePoints >= 0) { "保底点数不能为负" }

        val naturalGains = MutableList(count) { 0 }
        val remainders = MutableList(count) { state.accumulators[it] }

        for (index in 0 until count) {
            val rate = effectiveRates[index]
            val room = (caps[index] - state.stats[index]).coerceAtLeast(0)
            if (rate > 0 && room > 0) {
                val sum = state.accumulators[index] + rate
                val rawGain = sum / 100
                naturalGains[index] = minOf(rawGain, room)
                remainders[index] = if (rawGain >= room) 0 else sum % 100
            }
        }

        val totalGains = naturalGains.toMutableList()
        val nextAcc = remainders.toMutableList()
        val guaranteedIndices = mutableListOf<Int>()
        var hasTieBreakCaveat = false

        val naturalSum = naturalGains.sum()
        if (guaranteePoints > 0 && naturalSum < guaranteePoints) {
            val need = guaranteePoints - naturalSum
            val candidates = (0 until count).filter { index ->
                effectiveRates[index] > 0 &&
                    state.stats[index] < caps[index] &&
                    naturalGains[index] == 0
            }.sortedByDescending { remainders[it] }

            if (candidates.size > need) {
                val cutoffRem = remainders[candidates[need - 1]]
                if (remainders[candidates[need]] == cutoffRem) {
                    hasTieBreakCaveat = true
                }
            }
            for (index in candidates.take(need)) {
                totalGains[index] = 1
                // Confirmed rule: guaranteed stat's accumulator zeroes out cleanly instead of keeping negative remainder.
                nextAcc[index] = 0
                guaranteedIndices += index
            }
        }

        val nextStats = List(count) { index ->
            (state.stats[index] + totalGains[index]).coerceAtMost(caps[index])
        }

        return FixedGrowthStep(
            levelFrom = levelFrom,
            levelTo = levelFrom + 1,
            effectiveRates = effectiveRates.toList(),
            naturalGains = naturalGains,
            totalGains = totalGains,
            guaranteedIndices = guaranteedIndices,
            hasTieBreakCaveat = hasTieBreakCaveat,
            stateAfter = FixedGrowthState(nextAcc, nextStats),
        )
    }

    fun simulate(
        startLevel: Int,
        personalGrowth: List<Int>,
        startStats: List<Int>,
        ratesPerLevel: List<List<Int>>,
        capsPerLevel: List<List<Int>>,
        guaranteePoints: Int,
    ): List<FixedGrowthStep> {
        require(ratesPerLevel.size == capsPerLevel.size) { "逐级成长率与上限步数不匹配" }
        var state = initialState(personalGrowth, startStats)
        val steps = ArrayList<FixedGrowthStep>(ratesPerLevel.size)
        ratesPerLevel.forEachIndexed { offset, rates ->
            val step = step(
                levelFrom = startLevel + offset,
                state = state,
                effectiveRates = rates,
                caps = capsPerLevel[offset],
                guaranteePoints = guaranteePoints,
            )
            steps += step
            state = step.stateAfter
        }
        return steps
    }
}
