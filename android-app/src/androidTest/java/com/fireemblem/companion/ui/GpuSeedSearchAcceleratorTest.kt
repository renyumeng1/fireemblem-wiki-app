package com.fireemblem.companion.ui

import com.fireemblem.companion.engine.BuddyGrowthInput
import com.fireemblem.companion.engine.BuddyRoute
import com.fireemblem.companion.engine.BuddyRules
import com.fireemblem.companion.engine.GrowthStat
import com.fireemblem.companion.engine.ObservedSeedEvent
import com.fireemblem.companion.engine.SeedEngine
import com.fireemblem.companion.engine.SeedSearchStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuSeedSearchAcceleratorTest {
    @Test
    fun acceleratedSearchMatchesReferenceEngineBitForBit() {
        val rules = BuddyRules.bundled()
        val originSeed = 123_456L
        val forecast = rules.forecast(originSeed, BuddyGrowthInput("c044", "平民", 1, false, BuddyRoute.KAI), count = 4)
        val events = forecast.rows.map { row ->
            val params = rules.growth(BuddyGrowthInput("c044", "平民", row.fromLevel, false, BuddyRoute.KAI)).parameters
            ObservedSeedEvent.Level(params, GrowthStat.entries.map { row.gains.getValue(it) })
        }

        val reference = SeedEngine.searchRangeParallel(events, 0L, 200_000L)
        val accelerated = GpuSeedSearchAccelerator.search(events, 0L, 200_000L)

        assertEquals(SeedSearchStatus.UNIQUE, reference.status)
        assertEquals(reference.status, accelerated.result.status)
        assertEquals(reference.matches, accelerated.result.matches)
        assertEquals(reference.candidates, accelerated.result.candidates)
        assertEquals(forecast.rows.last().nextSeed, accelerated.result.candidates.single().currentSeed)
        assertTrue(accelerated.elapsedMillis >= 1L)
        android.util.Log.i(
            "GpuSeedTest",
            "std backend=${accelerated.backend} elapsedMs=${accelerated.elapsedMillis} seeds=${accelerated.result.processedSeeds} gpuHint=${GpuSeedSearchAccelerator.isGpuAvailableHint()} diag=${GpuSeedSearchAccelerator.lastProbeDiagnostics}",
        )
    }

    @Test
    fun acceleratedSearchSupportsZeroAndHundredPercentGrowthAndHighUint32Seeds() {
        val rules = BuddyRules.bundled()
        // Character with 0% growth (哥莱亚斯/土匪 -> 0% MAG) and 100% growth (赛奥朵拉/永霸神将 -> 100% HP)
        val zeroChar = rules.characters.first { it.name == "哥莱亚斯" }
        val overChar = rules.characters.first { it.name == "赛奥朵拉" }
        val highOrigin = 0xFFFF_1234L
        val highBegin = 0xFFFF_0000L - 150_000L
        val highEnd = 0x1_0000_0000L

        // Verify 0% MAG growth character on high uint32 seeds
        val zeroForecast = rules.forecast(highOrigin, BuddyGrowthInput(zeroChar.id, "土匪", 1, false, BuddyRoute.KAI), count = 4)
        val zeroEvents = zeroForecast.rows.map { row ->
            val params = rules.growth(BuddyGrowthInput(zeroChar.id, "土匪", row.fromLevel, false, BuddyRoute.KAI)).parameters
            ObservedSeedEvent.Level(params, GrowthStat.entries.map { row.gains.getValue(it) })
        }
        val zeroRef = SeedEngine.searchRangeParallel(zeroEvents, highBegin, highEnd)
        val zeroAcc = GpuSeedSearchAccelerator.search(zeroEvents, highBegin, highEnd)
        assertEquals(zeroRef.status, zeroAcc.result.status)
        assertEquals(zeroRef.matches, zeroAcc.result.matches)
        assertEquals(zeroRef.candidates, zeroAcc.result.candidates)

        // Verify 100% HP growth character on high uint32 seeds
        val overForecast = rules.forecast(highOrigin, BuddyGrowthInput(overChar.id, "永霸神将", 1, true, BuddyRoute.OTHER), count = 4)
        val overEvents = overForecast.rows.map { row ->
            val params = rules.growth(BuddyGrowthInput(overChar.id, "永霸神将", row.fromLevel, true, BuddyRoute.OTHER)).parameters
            ObservedSeedEvent.Level(params, GrowthStat.entries.map { row.gains.getValue(it) })
        }
        val overRef = SeedEngine.searchRangeParallel(overEvents, highBegin, highEnd)
        val overAcc = GpuSeedSearchAccelerator.search(overEvents, highBegin, highEnd)
        assertEquals(overRef.status, overAcc.result.status)
        assertEquals(overRef.matches, overAcc.result.matches)
        assertEquals(overRef.candidates, overAcc.result.candidates)

        android.util.Log.i(
            "GpuSeedTest",
            "edge zeroBackend=${zeroAcc.backend} overBackend=${overAcc.backend} highMatch=${overAcc.result.candidates.single().originSeeds}",
        )
    }
}
