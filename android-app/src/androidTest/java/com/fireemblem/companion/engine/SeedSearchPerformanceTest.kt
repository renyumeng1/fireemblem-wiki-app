package com.fireemblem.companion.engine

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SeedSearchPerformanceTest {
    @Test
    fun fullUint32SearchReportsThroughputAndStopsOnCancellation() {
        val parameters = LevelParameters(
            growth = listOf(80, 55, 30, 45, 35, 30, 50, 30, 50),
            room = List(9) { 32_767 },
            guarantee = 2,
        )
        var seed = 2_141_258_584L
        val events = buildList {
            repeat(5) {
                val result = SeedEngine.simulateLevel(seed, parameters)
                add(ObservedSeedEvent.Level(parameters, result.gains))
                seed = result.nextSeed
            }
        }
        val cancellation = AtomicBoolean(false)
        val cancelAfter = Thread {
            Thread.sleep(2_000)
            cancellation.set(true)
        }.apply { start() }
        val startedAt = SystemClock.elapsedRealtimeNanos()
        val result = SeedEngine.searchRangeParallel(
            events = events,
            beginInclusive = 0,
            endExclusive = SeedEngine.UINT32_LIMIT,
            isCancelled = cancellation::get,
        )
        val elapsedSeconds = (SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000_000_000.0
        cancelAfter.join()

        assertEquals(SeedSearchStatus.CANCELLED, result.status)
        assertTrue(result.processedSeeds > 0)
        assertTrue(result.processedSeeds < SeedEngine.UINT32_LIMIT)
        val seedsPerSecond = result.processedSeeds / elapsedSeconds
        val estimatedFullScanHours = SeedEngine.UINT32_LIMIT / seedsPerSecond / 3_600.0
        Log.i("SeedSearchPerformance", "processed=${result.processedSeeds}, seconds=$elapsedSeconds, " +
            "seedsPerSecond=$seedsPerSecond, estimatedFullScanHours=$estimatedFullScanHours")
    }

    @Test(timeout = 900_000)
    fun fullUint32SearchConfirmsTheodoraArchiveCandidateWhenRequested() {
        assumeTrue(androidx.test.platform.app.InstrumentationRegistry.getArguments()
            .getString("runFullUint32Scan") == "true")
        val rules = BuddyRules.bundled()
        val gains = listOf(
            listOf(1, 0, 1, 0, 0, 0, 0, 1, 0),
            listOf(1, 1, 1, 0, 0, 0, 0, 0, 1),
            listOf(1, 0, 1, 0, 0, 0, 1, 1, 1),
            listOf(1, 1, 0, 0, 1, 1, 1, 0, 0),
            listOf(1, 0, 1, 0, 1, 0, 1, 1, 0),
        )
        val events = gains.mapIndexed { index, observed ->
            val parameters = rules.growth(BuddyGrowthInput("c012", "士兵", 10 + index, false, BuddyRoute.KAI))
                .parameters.copy(room = List(9) { 32_767 })
            ObservedSeedEvent.Level(parameters, observed)
        }
        val startedAt = SystemClock.elapsedRealtimeNanos()
        val result = SeedEngine.searchRangeParallel(events, 0, SeedEngine.UINT32_LIMIT)
        val elapsedSeconds = (SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000_000_000.0
        val candidate = result.candidates.singleOrNull { 2_141_258_584L in it.originSeeds }

        assertTrue("Full uint32 scan was not complete: $result", result.fullUint32SpaceScanned)
        assertEquals(SeedSearchStatus.UNIQUE, result.status)
        assertEquals(1L, result.matches)
        assertTrue("Known archive origin was not found: $result", candidate != null)
        assertEquals(759_314_053L, candidate?.currentSeed)
        Log.i("SeedSearchPerformance", "theodoraStatus=${result.status}, matches=${result.matches}, " +
            "candidates=${result.candidates.size}, elapsedSeconds=$elapsedSeconds")
    }
}
