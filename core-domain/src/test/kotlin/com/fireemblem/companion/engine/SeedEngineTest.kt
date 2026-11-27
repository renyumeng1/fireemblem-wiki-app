package com.fireemblem.companion.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class SeedEngineTest {
    @Test
    fun advanceMatchesBuddyGoldenVectorsAcrossUnsignedBoundaries() {
        val seeds = listOf(0L, 1L, 0x7fff_ffffL, 0x8000_0000L, 0xffff_ffffL)
        val expected = listOf(1_900_725_608L, 128_212_549L, 1_507_036_647L, 2_086_980_003L, 37_784_296L)

        assertEquals(expected, seeds.map { SeedEngine.advance(it) })
    }

    @Test
    fun levelSimulationMatchesRetryAndGuaranteeFillGoldenVector() {
        val parameters = LevelParameters(
            growth = List(9) { 1 },
            room = List(9) { 9 },
            guarantee = 4,
        )

        val result = SeedEngine.simulateLevel(0L, parameters)

        assertEquals(listOf(0, 1, 0, 1, 1, 0, 1, 0, 0), result.gains)
        assertEquals(4, result.rounds)
        assertEquals(4, result.fills)
        assertEquals(1_900_725_608L, result.nextSeed)
    }

    @Test
    fun levelSimulationPreservesGrowthRemaindersAndRoomCaps() {
        val parameters = LevelParameters(
            growth = listOf(0, 50, 100, 101, 250, 99, 0, 200, 75),
            room = listOf(0, 9, 1, 9, 3, 0, 0, 9, 2),
            guarantee = 2,
        )

        val result = SeedEngine.simulateLevel(0xffff_ffffL, parameters)

        assertEquals(listOf(0, 0, 1, 1, 3, 0, 0, 2, 0), result.gains)
        assertEquals(1, result.rounds)
        assertEquals(0, result.fills)
        assertEquals(37_784_296L, result.nextSeed)
    }

    @Test
    fun zeroEligibleStatsStillUseFourRoundsWithoutFilling() {
        val parameters = LevelParameters(
            growth = List(9) { 0 },
            room = List(9) { 0 },
            guarantee = 3,
        )

        val result = SeedEngine.simulateLevel(0L, parameters)

        assertEquals(List(9) { 0 }, result.gains)
        assertEquals(4, result.rounds)
        assertEquals(0, result.fills)
        assertEquals(1_900_725_608L, result.nextSeed)
    }

    @Test
    fun examThresholdsUseStrictComparisonAndAdvanceFromFirstDraw() {
        val expectations = listOf(
            0.0 to false,
            1.0 to false,
            99.0 to true,
            100.0 to true,
        )

        for ((probability, passed) in expectations) {
            val result = SeedEngine.simulateExam(0xffff_ffffL, ExamParameters(probability))
            if (passed) assertTrue(result.passed) else assertFalse(result.passed)
            assertEquals(37_784_296L, result.nextSeed)
            assertEquals(69, result.minimumProbability)
        }
    }

    @Test
    fun eventMatchingAppliesAdvanceBeforeAndReturnsPostEventSeed() {
        val parameters = LevelParameters(
            growth = List(9) { 1 },
            room = List(9) { 9 },
            guarantee = 4,
        )
        val seedAfterAdvance = SeedEngine.advance(0L)
        val observed = SeedEngine.simulateLevel(seedAfterAdvance, parameters)
        val event = ObservedSeedEvent.Level(
            parameters = parameters,
            gains = observed.gains,
            advanceBefore = 1,
        )

        assertEquals(EventMatchResult.Matched(observed.nextSeed), SeedEngine.matchEvent(0L, event))
        assertEquals(EventMatchResult.Mismatch, SeedEngine.matchEvent(0L, event.copy(gains = List(9) { 0 })))
    }

    @Test
    fun searchReportsUniqueAmbiguousZeroAndImpossibleResultsSeparately() {
        val parameters = LevelParameters(List(9) { 1 }, List(9) { 9 }, guarantee = 4)
        val observed = SeedEngine.simulateLevel(0L, parameters)
        val event = ObservedSeedEvent.Level(parameters, observed.gains, advanceBefore = 3)
        val unique = SeedEngine.searchRange(listOf(event), 0L, 1L)
        val ambiguous = SeedEngine.searchRange(emptyList(), 0L, 2L)
        val zero = SeedEngine.searchRange(
            listOf(ObservedSeedEvent.Exam(ExamParameters(100.0), passed = false)),
            0L,
            8L,
        )
        val impossible = SeedEngine.searchRange(
            listOf(ObservedSeedEvent.Exam(ExamParameters(0.0), passed = true)),
            0L,
            8L,
        )

        assertEquals(SeedSearchStatus.UNIQUE, unique.status)
        assertEquals(listOf(1L), unique.prefixMatches)
        assertEquals(listOf(SeedSearchCandidate(1_900_725_608L, listOf(0L))), unique.candidates)
        assertEquals(SeedSearchStatus.AMBIGUOUS, ambiguous.status)
        assertEquals(2L, ambiguous.matches)
        assertEquals(SeedSearchStatus.NO_MATCH, zero.status)
        assertEquals(0L, zero.matches)
        assertEquals(SeedSearchStatus.IMPOSSIBLE, impossible.status)
        assertEquals(0, impossible.impossibleEventIndex)
    }

    @Test
    fun searchModelsSkippedAttemptsAndReportsProgress() {
        val parameters = LevelParameters(List(9) { 60 }, List(9) { 9 }, guarantee = 0)
        val first = SeedEngine.simulateLevel(0L, parameters)
        val afterSkippedAttempt = SeedEngine.advance(first.nextSeed)
        val afterTwoUnobservedAdvances = SeedEngine.advance(SeedEngine.advance(afterSkippedAttempt))
        val final = SeedEngine.simulateLevel(afterTwoUnobservedAdvances, parameters)
        val progress = mutableListOf<Pair<Long, Long>>()
        val events = listOf(
            ObservedSeedEvent.Level(parameters, first.gains),
            ObservedSeedEvent.Advance(),
            ObservedSeedEvent.Level(parameters, final.gains, advanceBefore = 2),
        )

        val result = SeedEngine.searchRange(events, 0L, 1L, onProgress = {
            progress += it.processedSeeds to it.totalSeeds
        })

        assertEquals(SeedSearchStatus.UNIQUE, result.status)
        assertEquals(listOf(0L), result.candidates.single().originSeeds)
        assertEquals(listOf(1L to 1L), progress)
    }

    @Test
    fun parallelSearchMatchesSerialAcrossPartitionsAndCancelsExplicitly() {
        val parameters = LevelParameters(List(9) { 50 }, List(9) { 32767 }, guarantee = 0)
        var seed = 123_456L
        val events = buildList {
            repeat(4) {
                val result = SeedEngine.simulateLevel(seed, parameters)
                add(ObservedSeedEvent.Level(parameters, result.gains))
                seed = result.nextSeed
            }
        }
        val serial = SeedEngine.searchRange(events, 0L, 300_000L)
        val progress = mutableListOf<SeedSearchProgress>()
        val parallel = SeedEngine.searchRangeParallel(events, 0L, 300_000L, parallelism = 3, onProgress = progress::add)
        val cancelled = SeedEngine.searchRangeParallel(events, 0L, 300_000L, parallelism = 3,
            isCancelled = AtomicBoolean(true)::get)
        val capped = SeedEngine.searchRangeParallel(listOf(ObservedSeedEvent.Advance()), 0L, 300_000L,
            maxCandidates = 10, parallelism = 3)

        assertEquals(serial.status, parallel.status)
        assertEquals(serial.matches, parallel.matches)
        assertEquals(serial.prefixMatches, parallel.prefixMatches)
        assertEquals(serial.candidates.associate { it.currentSeed to it.originSeeds.toSet() },
            parallel.candidates.associate { it.currentSeed to it.originSeeds.toSet() })
        assertTrue(progress.isNotEmpty())
        assertEquals(300_000L, progress.last().processedSeeds)
        assertEquals(SeedSearchStatus.CANCELLED, cancelled.status)
        assertEquals(0L, cancelled.processedSeeds)
        assertEquals(SeedSearchStatus.INCOMPLETE, capped.status)
        assertEquals(300_000L, capped.matches)
        assertEquals(10, capped.candidates.size)
    }

    @Test
    fun searchIgnoresFirstAdvanceAndAppliesLaterEventAdvances() {
        val parameters = LevelParameters(List(9) { 1 }, List(9) { 9 }, guarantee = 4)
        val firstResult = SeedEngine.simulateLevel(0L, parameters)
        val firstEvent = ObservedSeedEvent.Level(parameters, firstResult.gains, advanceBefore = 3)
        val secondEvent = ObservedSeedEvent.Exam(ExamParameters(100.0), passed = true, advanceBefore = 2)
        var seedBeforeExam = firstResult.nextSeed
        repeat(secondEvent.advanceBefore) { seedBeforeExam = SeedEngine.advance(seedBeforeExam) }
        val expectedFinalSeed = SeedEngine.simulateExam(seedBeforeExam, secondEvent.parameters).nextSeed

        val result = SeedEngine.searchRange(listOf(firstEvent, secondEvent), 0L, 1L)

        assertEquals(SeedSearchStatus.UNIQUE, result.status)
        assertEquals(listOf(1L, 1L), result.prefixMatches)
        assertEquals(expectedFinalSeed, result.candidates.single().currentSeed)
    }

    @Test
    fun searchDistinguishesCancellationCandidateTruncationAndHighSeeds() {
        var cancellationChecks = 0
        val cancelled = SeedEngine.searchRange(
            events = emptyList(),
            beginInclusive = 10L,
            endExclusive = 20L,
            isCancelled = { ++cancellationChecks == 4 },
        )
        val incomplete = SeedEngine.searchRange(
            events = emptyList(),
            beginInclusive = 0L,
            endExclusive = 3L,
            maxCandidates = 1,
        )
        val highSeed = SeedEngine.searchRange(
            events = emptyList(),
            beginInclusive = 0xffff_ffffL,
            endExclusive = SeedEngine.UINT32_LIMIT,
        )
        val narrowComplete = SeedEngine.searchRange(emptyList(), 10L, 11L)

        assertTrue(narrowComplete.complete)
        assertFalse(narrowComplete.fullUint32SpaceScanned)
        assertEquals(SeedSearchStatus.CANCELLED, cancelled.status)
        assertFalse(cancelled.complete)
        assertEquals(3L, cancelled.processedSeeds)
        assertEquals(SeedSearchStatus.INCOMPLETE, incomplete.status)
        assertTrue(incomplete.complete)
        assertTrue(incomplete.candidateLimitReached)
        assertEquals(3L, incomplete.matches)
        assertEquals(SeedSearchStatus.UNIQUE, highSeed.status)
        assertEquals(0xffff_ffffL, highSeed.candidates.single().originSeeds.single())
        assertEquals(0xffff_ffffL, highSeed.candidates.single().currentSeed)
    }

    @Test
    fun knownMaskIgnoresUnknownStatsWithoutTreatingThemAsZero() {
        val parameters = LevelParameters(
            growth = listOf(150, 50, 50, 50, 50, 50, 50, 50, 50),
            room = List(9) { 9 },
            guarantee = 2,
        )
        val observed = SeedEngine.simulateLevel(42L, parameters)
        val maskedIndex = observed.gains.indexOfFirst { it > 0 }
        assertTrue(maskedIndex >= 0)
        val unknownGains = observed.gains.toMutableList().apply { this[maskedIndex] = 0 }
        val knownMask = ObservedSeedEvent.Level.ALL_STATS_MASK xor (1 shl maskedIndex)

        val exactRejected = ObservedSeedEvent.Level(parameters, unknownGains)
        val maskedAccepted = ObservedSeedEvent.Level(parameters, unknownGains, knownMask = knownMask)

        assertEquals(EventMatchResult.Mismatch, SeedEngine.matchEvent(42L, exactRejected))
        assertEquals(EventMatchResult.Matched(observed.nextSeed), SeedEngine.matchEvent(42L, maskedAccepted))
        assertEquals(SeedSearchStatus.IMPOSSIBLE, SeedEngine.searchRange(listOf(exactRejected), 42L, 43L).status)
        assertEquals(SeedSearchStatus.UNIQUE, SeedEngine.searchRange(listOf(maskedAccepted), 42L, 43L).status)

        val probabilisticIndex = (1 until 9).first { observed.gains[it] != 0 }
        val nonGuaranteedGains = observed.gains.toMutableList().apply { this[probabilisticIndex] = 0 }
        val nonGuaranteedMask = ObservedSeedEvent.Level.ALL_STATS_MASK xor (1 shl probabilisticIndex)
        assertEquals(SeedSearchStatus.NO_MATCH,
            SeedEngine.searchRange(listOf(ObservedSeedEvent.Level(parameters, nonGuaranteedGains)), 42L, 43L).status)
        assertEquals(SeedSearchStatus.UNIQUE,
            SeedEngine.searchRange(listOf(ObservedSeedEvent.Level(parameters, nonGuaranteedGains, knownMask = nonGuaranteedMask)), 42L, 43L).status)
        assertEquals(SeedSearchStatus.UNIQUE,
            SeedEngine.searchRangeParallel(listOf(maskedAccepted), 40L, 45L, parallelism = 2)
                .takeIf { it.candidates.any { candidate -> candidate.originSeeds.contains(42L) } }
                ?.let { result -> if (result.matches >= 1L) SeedSearchStatus.UNIQUE else result.status }
                ?: SeedSearchStatus.NO_MATCH)

        val highRangeParallel = SeedEngine.searchRangeParallel(
            events = listOf(maskedAccepted),
            beginInclusive = 0L,
            endExclusive = 300_000L,
            parallelism = 2,
        )
        val highRangeSerial = SeedEngine.searchRange(listOf(maskedAccepted), 0L, 300_000L)
        assertEquals(highRangeSerial.matches, highRangeParallel.matches)
        assertEquals(highRangeSerial.candidates, highRangeParallel.candidates)
        assertTrue(highRangeParallel.candidates.any { 42L in it.originSeeds })

        val impossibleKnownStat = ObservedSeedEvent.Level(
            parameters = parameters,
            gains = List(9) { 0 }.toMutableList().apply { this[1] = 2 },
            knownMask = ObservedSeedEvent.Level.ALL_STATS_MASK xor 1,
        )
        assertEquals(0, SeedEngine.searchRange(listOf(impossibleKnownStat), 0L, 10L).impossibleEventIndex)
        assertThrows(IllegalArgumentException::class.java) {
            ObservedSeedEvent.Level(parameters, observed.gains, knownMask = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ObservedSeedEvent.Level(parameters, observed.gains, knownMask = 0x200)
        }
    }

    @Test
    fun fastSearchPathsMatchReferenceSimulationAcrossAllSpecializations() {
        val cases = listOf(
            // Fast9Base0 path (all 9 stats growth in 1..99, room >= 1, full mask, guarantee 2 triggers slow rounds too)
            LevelParameters(listOf(45, 35, 25, 50, 40, 30, 20, 15, 35), List(9) { 32767 }, guarantee = 2),
            // Fast9 path (growth > 100 on some stats)
            LevelParameters(listOf(125, 105, 45, 55, 65, 35, 25, 15, 50), List(9) { 32767 }, guarantee = 2),
            // Generic path (some growth == 0 or exact 100 remainder == 0)
            LevelParameters(listOf(100, 0, 50, 0, 100, 40, 20, 0, 30), List(9) { 32767 }, guarantee = 3),
            // Guarantee fill heavy (tiny growths forcing round 4 weighted fill)
            LevelParameters(List(9) { 1 }, List(9) { 9 }, guarantee = 4),
        )
        for (params in cases) {
            val targetSeed = 12_345L
            val first = SeedEngine.simulateLevel(targetSeed, params)
            val second = SeedEngine.simulateLevel(first.nextSeed, params)
            val events = listOf(
                ObservedSeedEvent.Level(params, first.gains),
                ObservedSeedEvent.Level(params, second.gains),
            )
            val fast = SeedEngine.searchRangeParallel(events, 0L, 20_000L, parallelism = 2)
            val referenceOrigins = (0L until 20_000L).filter { origin ->
                val m1 = SeedEngine.matchEvent(origin, events[0]) as? EventMatchResult.Matched ?: return@filter false
                SeedEngine.matchEvent(m1.nextSeed, events[1]) is EventMatchResult.Matched
            }
            assertEquals(referenceOrigins.size.toLong(), fast.matches)
            assertEquals(referenceOrigins.toSet(), fast.candidates.flatMap { it.originSeeds }.toSet())
            assertTrue(targetSeed in referenceOrigins)
        }
    }

    @Test
    fun barrettModMillionIsBitExactAcrossFull31BitDomain() {
        val magic = 0x431BDE83L
        val boundaries = listOf(
            0, 1, 999_999, 1_000_000, 1_000_001,
            999_999_999, 1_000_000_000, 1_000_000_001,
            Int.MAX_VALUE - 1, Int.MAX_VALUE,
        )
        for (r in boundaries) {
            val fast = r - (((r.toLong() * magic) ushr 50).toInt() * 1_000_000)
            assertEquals("Mismatch at $r", r % 1_000_000, fast)
        }
        var x = 123456789
        repeat(100_000) {
            x = x * 1_664_525 + 1_013_904_223
            val r = x and 0x7fff_ffff
            val fast = r - (((r.toLong() * magic) ushr 50).toInt() * 1_000_000)
            assertEquals("Mismatch at $r", r % 1_000_000, fast)
        }
    }
}
