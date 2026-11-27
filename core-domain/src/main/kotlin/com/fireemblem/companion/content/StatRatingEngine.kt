package com.fireemblem.companion.content

import kotlin.math.ceil
import kotlin.math.floor

enum class StatRatingTier(
    val shortLabel: String,
    val label: String,
) {
    LOW("差", "偏低"),
    MEDIUM("中", "中等"),
    HIGH("好", "优秀"),
}

enum class StatMetricKind {
    BASE_STAT,
    GROWTH_RATE,
    GROWTH_TOTAL,
    CAP_MODIFIER,
    CLASS_BASE_STAT,
    CLASS_GROWTH_MODIFIER,
}

data class StatDistribution(
    val sampleSize: Int,
    val min: Double,
    val lowCutoff: Double,
    val median: Double,
    val highCutoff: Double,
    val max: Double,
) {
    fun progressOf(value: Double): Float {
        if (max <= min) return 0.55f
        val ratio = ((value - min) / (max - min)).toFloat()
        return ratio.coerceIn(0.12f, 1.0f)
    }
}

data class StatRatingResult(
    val tier: StatRatingTier,
    val progress: Float,
    val distribution: StatDistribution?,
)

data class GameStatBenchmarks(
    val gameId: String,
    val rosterSize: Int,
    val classSize: Int,
    val characterBaseStats: Map<String, StatDistribution> = emptyMap(),
    val characterGrowths: Map<String, StatDistribution> = emptyMap(),
    val characterGrowthTotal: StatDistribution? = null,
    val characterCaps: Map<String, StatDistribution> = emptyMap(),
    val classBaseStats: Map<String, StatDistribution> = emptyMap(),
    val classGrowths: Map<String, StatDistribution> = emptyMap(),
) {
    fun evaluate(kind: StatMetricKind, statKey: String, rawValue: String): StatRatingResult? {
        val numeric = parseStatNumeric(rawValue) ?: return null
        val normalizedKey = normalizeStatKey(statKey)
        return when (kind) {
            StatMetricKind.BASE_STAT -> {
                val dist = characterBaseStats[normalizedKey] ?: return null
                StatRatingEngine.classifyByDistribution(numeric, dist)
            }
            StatMetricKind.GROWTH_RATE -> {
                val dist = characterGrowths[normalizedKey] ?: return null
                StatRatingEngine.classifyByDistribution(numeric, dist)
            }
            StatMetricKind.GROWTH_TOTAL -> {
                val dist = characterGrowthTotal ?: return null
                StatRatingEngine.classifyByDistribution(numeric, dist)
            }
            StatMetricKind.CAP_MODIFIER -> {
                val dist = characterCaps[normalizedKey]
                StatRatingEngine.classifySignedModifier(numeric, dist)
            }
            StatMetricKind.CLASS_BASE_STAT -> {
                val dist = classBaseStats[normalizedKey] ?: return null
                StatRatingEngine.classifyByDistribution(numeric, dist)
            }
            StatMetricKind.CLASS_GROWTH_MODIFIER -> {
                val dist = classGrowths[normalizedKey]
                StatRatingEngine.classifyClassGrowthModifier(numeric, dist)
            }
        }
    }

    fun legendSummary(kind: StatMetricKind, gameTitle: String): String = when (kind) {
        StatMetricKind.BASE_STAT ->
            "算法依据：基于《$gameTitle》$rosterSize 名角色各属性实测分位（后 30% 差 · 中间 40% 中等 · 前 30% 好）；高初始等级角色面板天然偏高。"
        StatMetricKind.GROWTH_RATE ->
            "算法依据：基于《$gameTitle》$rosterSize 名角色分项成长率分位（各属性独立计算，后 30% 差 · 中间 40% 中等 · 前 30% 好）。"
        StatMetricKind.GROWTH_TOTAL ->
            "算法依据：基于《$gameTitle》$rosterSize 名角色总成长率分位（后 30% 差 · 中间 40% 中等 · 前 30% 好）。"
        StatMetricKind.CAP_MODIFIER ->
            "算法依据：负值修正为差，0 为中等，正值修正为好（基于《$gameTitle》角色能力上限修正）。"
        StatMetricKind.CLASS_BASE_STAT ->
            "算法依据：基于《$gameTitle》$classSize 个职业各基础面板分位（后 30% 差 · 中间 40% 中等 · 前 30% 好）。"
        StatMetricKind.CLASS_GROWTH_MODIFIER ->
            "算法依据：基于《$gameTitle》$classSize 个职业成长补正分位（负值/偏低为差 · 0% 或中间为中等 · 突出加成为好）。"
    }
}

object StatRatingEngine {
    private const val LOW_QUANTILE = 0.30
    private const val HIGH_QUANTILE = 0.70

    fun buildDistribution(samples: List<Double>): StatDistribution? {
        val valid = samples.filter { it.isFinite() }.sorted()
        if (valid.isEmpty()) return null
        return StatDistribution(
            sampleSize = valid.size,
            min = valid.first(),
            lowCutoff = quantile(valid, LOW_QUANTILE),
            median = quantile(valid, 0.50),
            highCutoff = quantile(valid, HIGH_QUANTILE),
            max = valid.last(),
        )
    }

    fun buildBenchmarks(
        gameId: String,
        characterBaseSamples: Map<String, List<Double>>,
        characterGrowthSamples: Map<String, List<Double>>,
        characterGrowthTotals: List<Double> = emptyList(),
        characterCapSamples: Map<String, List<Double>> = emptyMap(),
        classBaseSamples: Map<String, List<Double>> = emptyMap(),
        classGrowthSamples: Map<String, List<Double>> = emptyMap(),
        rosterSize: Int = characterGrowthSamples.values.maxOfOrNull { it.size }
            ?: characterBaseSamples.values.maxOfOrNull { it.size } ?: 0,
        classSize: Int = classGrowthSamples.values.maxOfOrNull { it.size }
            ?: classBaseSamples.values.maxOfOrNull { it.size } ?: 0,
    ): GameStatBenchmarks = GameStatBenchmarks(
        gameId = gameId,
        rosterSize = rosterSize,
        classSize = classSize,
        characterBaseStats = characterBaseSamples.mapNotNull { (k, v) ->
            buildDistribution(v)?.let { normalizeStatKey(k) to it }
        }.toMap(),
        characterGrowths = characterGrowthSamples.mapNotNull { (k, v) ->
            buildDistribution(v)?.let { normalizeStatKey(k) to it }
        }.toMap(),
        characterGrowthTotal = buildDistribution(characterGrowthTotals),
        characterCaps = characterCapSamples.mapNotNull { (k, v) ->
            buildDistribution(v)?.let { normalizeStatKey(k) to it }
        }.toMap(),
        classBaseStats = classBaseSamples.mapNotNull { (k, v) ->
            buildDistribution(v)?.let { normalizeStatKey(k) to it }
        }.toMap(),
        classGrowths = classGrowthSamples.mapNotNull { (k, v) ->
            buildDistribution(v)?.let { normalizeStatKey(k) to it }
        }.toMap(),
    )

    internal fun classifyByDistribution(value: Double, dist: StatDistribution): StatRatingResult {
        val isLow = if (dist.lowCutoff < dist.median) value <= dist.lowCutoff else value < dist.lowCutoff
        val isHigh = if (dist.highCutoff > dist.median) value >= dist.highCutoff else value > dist.highCutoff
        val tier = when {
            dist.max <= dist.min -> StatRatingTier.MEDIUM
            isLow -> StatRatingTier.LOW
            isHigh -> StatRatingTier.HIGH
            else -> StatRatingTier.MEDIUM
        }
        return StatRatingResult(tier = tier, progress = dist.progressOf(value), distribution = dist)
    }

    internal fun classifySignedModifier(value: Double, dist: StatDistribution?): StatRatingResult {
        val tier = when {
            value < 0.0 -> StatRatingTier.LOW
            value > 0.0 -> StatRatingTier.HIGH
            else -> StatRatingTier.MEDIUM
        }
        val progress = dist?.progressOf(value) ?: ((value + 3.0) / 6.0).toFloat().coerceIn(0.12f, 1.0f)
        return StatRatingResult(tier = tier, progress = progress, distribution = dist)
    }

    internal fun classifyClassGrowthModifier(value: Double, dist: StatDistribution?): StatRatingResult {
        if (dist != null && dist.min >= 0.0) {
            // Engage-style non-negative class growth bonuses (e.g. 0%..30%)
            return classifyByDistribution(value, dist)
        }
        // Three Houses / Fortunes Weave signed class growth modifiers (-10%..+30%)
        val highThreshold = dist?.highCutoff?.coerceAtLeast(10.0) ?: 10.0
        val tier = when {
            value < 0.0 -> StatRatingTier.LOW
            value >= highThreshold -> StatRatingTier.HIGH
            else -> StatRatingTier.MEDIUM
        }
        val progress = dist?.progressOf(value) ?: ((value + 10.0) / 30.0).toFloat().coerceIn(0.12f, 1.0f)
        return StatRatingResult(tier = tier, progress = progress, distribution = dist)
    }

    private fun quantile(sorted: List<Double>, p: Double): Double {
        if (sorted.size == 1) return sorted.first()
        val pos = p * (sorted.size - 1)
        val lo = floor(pos).toInt().coerceIn(0, sorted.lastIndex)
        val hi = ceil(pos).toInt().coerceIn(0, sorted.lastIndex)
        if (lo == hi) return sorted[lo]
        val frac = pos - lo
        return sorted[lo] + (sorted[hi] - sorted[lo]) * frac
    }

    /**
     * Verified Fortunes Weave (69 characters initial stats / 68 characters growths)
     * per-stat distribution benchmarks from the bundled wiki database.
     */
    val FORTUNES_WEAVE_BENCHMARKS: GameStatBenchmarks = GameStatBenchmarks(
        gameId = "fortunes-weave",
        rosterSize = 69,
        classSize = 63,
        characterBaseStats = mapOf(
            "HP" to StatDistribution(69, 22.0, 29.0, 31.0, 37.0, 64.0),
            "力量" to StatDistribution(69, 5.0, 11.0, 12.0, 18.0, 39.0),
            "魔力" to StatDistribution(69, 1.0, 6.0, 7.0, 10.0, 34.0),
            "技巧" to StatDistribution(69, 5.0, 10.0, 12.0, 19.0, 47.0),
            "速度" to StatDistribution(69, 5.0, 9.0, 12.0, 17.0, 64.0),
            "幸运" to StatDistribution(69, 4.0, 8.0, 10.0, 13.0, 36.0),
            "防守" to StatDistribution(69, 4.0, 7.0, 10.0, 14.0, 47.0),
            "魔防" to StatDistribution(69, 3.0, 6.0, 8.0, 11.0, 43.0),
            "魅力" to StatDistribution(69, 3.0, 8.0, 9.0, 15.0, 66.0),
        ),
        characterGrowths = mapOf(
            "HP" to StatDistribution(68, 30.0, 40.0, 45.0, 50.0, 70.0),
            "力量" to StatDistribution(68, 25.0, 35.0, 40.0, 45.0, 60.0),
            "魔力" to StatDistribution(68, 5.0, 20.0, 30.0, 35.0, 55.0),
            "技巧" to StatDistribution(68, 20.0, 40.0, 45.0, 50.0, 60.0),
            "速度" to StatDistribution(68, 10.0, 35.0, 40.0, 45.0, 65.0),
            "幸运" to StatDistribution(68, 20.0, 30.0, 35.0, 40.0, 60.0),
            "防守" to StatDistribution(68, 20.0, 30.0, 35.0, 40.0, 60.0),
            "魔防" to StatDistribution(68, 10.0, 25.0, 30.0, 35.0, 60.0),
            "魅力" to StatDistribution(68, 5.0, 30.0, 35.0, 40.0, 60.0),
        ),
        classBaseStats = mapOf(
            "HP" to StatDistribution(63, 0.0, 1.0, 3.0, 5.0, 13.0),
            "力量" to StatDistribution(63, 0.0, 0.0, 1.0, 2.0, 6.0),
            "魔力" to StatDistribution(63, 0.0, 0.0, 0.0, 0.0, 9.0),
            "技巧" to StatDistribution(63, 0.0, 0.0, 2.0, 3.0, 9.0),
            "速度" to StatDistribution(63, -6.0, 1.0, 2.0, 3.0, 9.0),
            "幸运" to StatDistribution(63, 0.0, 0.0, 0.0, 0.0, 5.0),
            "防守" to StatDistribution(63, -1.0, 0.0, 1.0, 2.4, 13.0),
            "魔防" to StatDistribution(63, -2.0, 0.0, 0.0, 1.0, 9.0),
            "魅力" to StatDistribution(63, -2.0, 0.0, 0.0, 1.0, 9.0),
        ),
    )
}

fun parseStatNumeric(raw: String): Double? {
    val cleaned = raw.trim().removeSuffix("%").trim().removePrefix("+")
    return cleaned.toDoubleOrNull()?.takeIf { it.isFinite() }
}

fun normalizeStatKey(raw: String): String = when (raw.trim().lowercase()) {
    "hp" -> "HP"
    "str", "strength", "力量" -> "力量"
    "mag", "magic", "魔力" -> "魔力"
    "dex", "skl", "skill", "技巧" -> "技巧"
    "spd", "speed", "速度" -> "速度"
    "lck", "luck", "幸运" -> "幸运"
    "def", "defense", "防守" -> "防守"
    "res", "resistance", "魔防" -> "魔防"
    "cha", "charm", "魅力" -> "魅力"
    "bld", "build", "体格" -> "体格"
    "mov", "move", "移动", "移动力" -> "移动力"
    else -> raw.trim()
}
