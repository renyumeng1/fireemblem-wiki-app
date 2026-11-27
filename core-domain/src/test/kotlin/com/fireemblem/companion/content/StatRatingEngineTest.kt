package com.fireemblem.companion.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatRatingEngineTest {
    @Test
    fun perGameDistributionRatesEngageBuildAndThreeHousesSpeedDifferently() {
        val engageBenchmarks = StatRatingEngine.buildBenchmarks(
            gameId = "engage",
            characterBaseSamples = mapOf(
                "HP" to listOf(18.0, 24.0, 30.0, 33.0, 36.0, 42.0, 51.0),
                "力量" to listOf(4.0, 8.0, 10.0, 12.0, 15.0, 18.0, 25.0),
            ),
            characterGrowthSamples = mapOf(
                "HP" to listOf(40.0, 55.0, 55.0, 60.0, 65.0, 65.0, 85.0),
                "体格" to listOf(0.0, 5.0, 5.0, 10.0, 10.0, 10.0, 20.0),
            ),
            characterGrowthTotals = listOf(225.0, 265.0, 285.0, 300.0, 345.0),
        )
        // In Engage, 15% Build growth is HIGH (top tier), while 45% HP growth is LOW!
        val buildHigh = requireNotNull(engageBenchmarks.evaluate(StatMetricKind.GROWTH_RATE, "体格", "15%"))
        assertEquals(StatRatingTier.HIGH, buildHigh.tier)
        val buildLow = requireNotNull(engageBenchmarks.evaluate(StatMetricKind.GROWTH_RATE, "体格", "5%"))
        assertEquals(StatRatingTier.LOW, buildLow.tier)
        val buildMed = requireNotNull(engageBenchmarks.evaluate(StatMetricKind.GROWTH_RATE, "体格", "10%"))
        assertEquals(StatRatingTier.MEDIUM, buildMed.tier)

        val hpLow = requireNotNull(engageBenchmarks.evaluate(StatMetricKind.GROWTH_RATE, "HP", "45%"))
        assertEquals(StatRatingTier.LOW, hpLow.tier)
        val hpHigh = requireNotNull(engageBenchmarks.evaluate(StatMetricKind.GROWTH_RATE, "HP", "70%"))
        assertEquals(StatRatingTier.HIGH, hpHigh.tier)
        assertTrue(hpHigh.progress > hpLow.progress)

        // Growth total rating
        val totalHigh = requireNotNull(engageBenchmarks.evaluate(StatMetricKind.GROWTH_TOTAL, "成长合计", "325%"))
        assertEquals(StatRatingTier.HIGH, totalHigh.tier)
        val totalLow = requireNotNull(engageBenchmarks.evaluate(StatMetricKind.GROWTH_TOTAL, "成长合计", "240%"))
        assertEquals(StatRatingTier.LOW, totalLow.tier)
    }

    @Test
    fun signedCapModifiersAndClassGrowthsRespectSignAndScale() {
        val benchmarks = StatRatingEngine.buildBenchmarks(
            gameId = "engage",
            characterBaseSamples = emptyMap(),
            characterGrowthSamples = emptyMap(),
            characterCapSamples = mapOf("力量" to listOf(-3.0, -1.0, 0.0, 1.0, 3.0)),
            classGrowthSamples = mapOf("力量" to listOf(0.0, 5.0, 10.0, 15.0, 20.0)),
        )
        assertEquals(StatRatingTier.LOW, benchmarks.evaluate(StatMetricKind.CAP_MODIFIER, "力量", "-2")?.tier)
        assertEquals(StatRatingTier.MEDIUM, benchmarks.evaluate(StatMetricKind.CAP_MODIFIER, "力量", "0")?.tier)
        assertEquals(StatRatingTier.HIGH, benchmarks.evaluate(StatMetricKind.CAP_MODIFIER, "力量", "+2")?.tier)
        assertEquals(StatRatingTier.LOW, benchmarks.evaluate(StatMetricKind.CLASS_GROWTH_MODIFIER, "力量", "0%")?.tier)
        assertEquals(StatRatingTier.HIGH, benchmarks.evaluate(StatMetricKind.CLASS_GROWTH_MODIFIER, "力量", "20%")?.tier)

        val signedClassBenchmarks = StatRatingEngine.buildBenchmarks(
            gameId = "three-houses",
            characterBaseSamples = emptyMap(),
            characterGrowthSamples = emptyMap(),
            classGrowthSamples = mapOf("速度" to listOf(-10.0, 0.0, 0.0, 5.0, 10.0, 20.0)),
        )
        assertEquals(StatRatingTier.LOW, signedClassBenchmarks.evaluate(StatMetricKind.CLASS_GROWTH_MODIFIER, "速度", "-5%")?.tier)
        assertEquals(StatRatingTier.MEDIUM, signedClassBenchmarks.evaluate(StatMetricKind.CLASS_GROWTH_MODIFIER, "速度", "0%")?.tier)
        assertEquals(StatRatingTier.HIGH, signedClassBenchmarks.evaluate(StatMetricKind.CLASS_GROWTH_MODIFIER, "速度", "10%")?.tier)
    }

    @Test
    fun fortunesWeavePrecomputedBenchmarksGradeBaseStatsCleanly() {
        val bench = StatRatingEngine.FORTUNES_WEAVE_BENCHMARKS
        assertNotNull(bench.evaluate(StatMetricKind.BASE_STAT, "HP", "25"))
        assertEquals(StatRatingTier.LOW, bench.evaluate(StatMetricKind.BASE_STAT, "HP", "25")?.tier)
        assertEquals(StatRatingTier.MEDIUM, bench.evaluate(StatMetricKind.BASE_STAT, "HP", "31")?.tier)
        assertEquals(StatRatingTier.HIGH, bench.evaluate(StatMetricKind.BASE_STAT, "HP", "42")?.tier)
        assertNull(bench.evaluate(StatMetricKind.BASE_STAT, "HP", "--"))
    }
}
