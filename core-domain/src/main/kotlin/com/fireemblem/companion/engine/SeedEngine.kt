package com.fireemblem.companion.engine

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val STAT_COUNT = 9
private const val MULTIPLIER = 1_812_433_253
private const val OUTPUT_MASK = 0x7fff_ffff
private const val MILLION = 1_000_000
// Exact Barrett constant for 31-bit non-negative integers: ceil(2^50 / 1_000_000) = 0x431BDE83L.
// For all r in 0..0x7fff_ffff: q = ((r.toLong() * MILLION_BARRETT_MAGIC) ushr 50).toInt() == r / 1_000_000.
private const val MILLION_BARRETT_MAGIC = 0x431BDE83L
private const val FAST_BLOCK_SIZE = 65_536L
private const val FAST_CHUNK_SIZE = 4_194_304L
private const val PROGRESS_INTERVAL_NANOS = 100_000_000L

data class LevelParameters(
    val growth: List<Int>,
    val room: List<Int>,
    val guarantee: Int,
) {
    init {
        require(growth.size == STAT_COUNT) { "growth must contain nine stats" }
        require(room.size == STAT_COUNT) { "room must contain nine stats" }
    }
}

data class ExamParameters(val probability: Double)

sealed interface ObservedSeedEvent {
    val advanceBefore: Int

    data class Level(
        val parameters: LevelParameters,
        val gains: List<Int>,
        override val advanceBefore: Int = 0,
        val knownMask: Int = ALL_STATS_MASK,
    ) : ObservedSeedEvent {
        init {
            require(gains.size == STAT_COUNT) { "gains must contain nine stats" }
            require(advanceBefore >= 0) { "advanceBefore must not be negative" }
            require(knownMask in 0..ALL_STATS_MASK) { "knownMask must only use the lowest nine bits" }
        }

        companion object {
            const val ALL_STATS_MASK: Int = (1 shl STAT_COUNT) - 1
        }
    }

    data class Exam(
        val parameters: ExamParameters,
        val passed: Boolean,
        override val advanceBefore: Int = 0,
    ) : ObservedSeedEvent {
        init {
            require(advanceBefore >= 0) { "advanceBefore must not be negative" }
        }
    }

    data class Advance(override val advanceBefore: Int = 0) : ObservedSeedEvent {
        init {
            require(advanceBefore >= 0) { "advanceBefore must not be negative" }
        }
    }

    data class Anchor(val seed: Long, override val advanceBefore: Int = 0) : ObservedSeedEvent {
        init {
            require(seed in 0 until SeedEngine.UINT32_LIMIT) { "seed must be an unsigned 32-bit value" }
            require(advanceBefore >= 0) { "advanceBefore must not be negative" }
        }
    }
}

data class LevelSimulationResult(
    val gains: List<Int>,
    val rounds: Int,
    val fills: Int,
    val nextSeed: Long,
)

data class ExamSimulationResult(
    val passed: Boolean,
    val nextSeed: Long,
    val minimumProbability: Int,
)

sealed interface EventMatchResult {
    data class Matched(val nextSeed: Long) : EventMatchResult

    object Mismatch : EventMatchResult
}

enum class SeedSearchStatus {
    CANCELLED,
    INCOMPLETE,
    IMPOSSIBLE,
    NO_MATCH,
    UNIQUE,
    AMBIGUOUS,
}

data class SeedSearchCandidate(
    val currentSeed: Long,
    val originSeeds: List<Long>,
)

data class SeedSearchResult(
    val status: SeedSearchStatus,
    val complete: Boolean,
    val candidateLimitReached: Boolean,
    val totalSeeds: Long,
    val processedSeeds: Long,
    val matches: Long,
    val prefixMatches: List<Long>,
    val candidates: List<SeedSearchCandidate>,
    val impossibleEventIndex: Int? = null,
) {
    val candidatesComplete: Boolean
        get() = complete && !candidateLimitReached

    val fullUint32SpaceScanned: Boolean
        get() = candidatesComplete && totalSeeds == SeedEngine.UINT32_LIMIT && processedSeeds == SeedEngine.UINT32_LIMIT
}

data class SeedSearchProgress(val processedSeeds: Long, val totalSeeds: Long)

object SeedEngine {
    const val SEED_MASK: Long = 0x7fff_ffffL
    const val UINT32_LIMIT: Long = 0x1_0000_0000L
    const val DEFAULT_MAX_TRACKED_CANDIDATES: Int = 100_000

    private data class PreparedEvent(
        val event: ObservedSeedEvent,
        val growth: IntArray? = null,
        val room: IntArray? = null,
        val expectedGains: IntArray? = null,
        val eligibleStats: IntArray = IntArray(0),
        val knownMask: Int = ObservedSeedEvent.Level.ALL_STATS_MASK,
    )

    fun advance(seed: Long): Long = RandomState(seed).draw().toLong()

    fun simulateLevel(seed: Long, parameters: LevelParameters): LevelSimulationResult {
        val state = RandomState(seed)
        val eligible = (0 until STAT_COUNT).filter { index ->
            parameters.growth[index] > 0 && parameters.room[index] > 0
        }
        val guarantee = minOf(parameters.guarantee, eligible.size)
        val target = maxOf(guarantee, 1)
        val points = IntArray(STAT_COUNT)
        var count: Int
        var rounds = 0

        do {
            count = 0
            rounds++
            for (index in eligible) {
                val growth = parameters.growth[index]
                val remainder = growth % 100
                points[index] = growth / 100 +
                    if (remainder > 0 && chance(state, remainder)) 1 else 0
                if (points[index] != 0) count++
            }
        } while (count < target && rounds < 4)

        var fills = 0
        while (count < guarantee) {
            val pool = eligible.filter { points[it] == 0 }
            val totalWeight = pool.sumOf { parameters.growth[it].toLong() * 100L }
            var pick = state.draw().toLong() % totalWeight
            for (index in pool) {
                pick -= parameters.growth[index].toLong() * 100L
                if (pick < 0) {
                    points[index] = 1
                    count++
                    fills++
                    break
                }
            }
        }

        return LevelSimulationResult(
            gains = (0 until STAT_COUNT).map { minOf(points[it], parameters.room[it]) },
            rounds = rounds,
            fills = fills,
            nextSeed = advance(seed),
        )
    }

    fun simulateExam(seed: Long, parameters: ExamParameters): ExamSimulationResult {
        val state = RandomState(seed)
        val nextSeed = state.draw().toLong()
        val roll = state.draw() % MILLION
        return ExamSimulationResult(
            passed = parameters.probability >= 1.0 && roll < parameters.probability * 10_000.0,
            nextSeed = nextSeed,
            minimumProbability = roll / 10_000 + 1,
        )
    }

    fun matchEvent(seed: Long, event: ObservedSeedEvent): EventMatchResult =
        matchEvent(seed, event, applyAdvanceBefore = true)

    fun searchRange(
        events: List<ObservedSeedEvent>,
        beginInclusive: Long,
        endExclusive: Long,
        maxCandidates: Int = DEFAULT_MAX_TRACKED_CANDIDATES,
        isCancelled: () -> Boolean = { false },
        onProgress: (SeedSearchProgress) -> Unit = {},
    ): SeedSearchResult {
        require(beginInclusive in 0L..UINT32_LIMIT) { "beginInclusive must be a uint32 range boundary" }
        require(endExclusive in beginInclusive..UINT32_LIMIT) { "endExclusive must not precede beginInclusive" }
        require(maxCandidates >= 0) { "maxCandidates must not be negative" }

        val totalSeeds = endExclusive - beginInclusive
        val impossibleEventIndex = findImpossibleEventIndex(events)
        if (impossibleEventIndex != null) {
            return SeedSearchResult(
                status = SeedSearchStatus.IMPOSSIBLE,
                complete = true,
                candidateLimitReached = false,
                totalSeeds = totalSeeds,
                processedSeeds = 0,
                matches = 0,
                prefixMatches = List(events.size) { 0L },
                candidates = emptyList(),
                impossibleEventIndex = impossibleEventIndex,
            )
        }

        if (events.isNotEmpty() && totalSeeds > 4_096L) {
            return searchRangeFastParallel(
                events = events,
                beginInclusive = beginInclusive,
                endExclusive = endExclusive,
                maxCandidates = maxCandidates,
                parallelism = 1,
                isCancelled = isCancelled,
                onProgress = onProgress,
            )
        }

        val preparedEvents = events.map { event ->
            if (event is ObservedSeedEvent.Level) {
                val growth = event.parameters.growth.toIntArray()
                val room = event.parameters.room.toIntArray()
                PreparedEvent(event, growth, room, event.gains.toIntArray(),
                    (0 until STAT_COUNT).filter { growth[it] > 0 && room[it] > 0 }.toIntArray(),
                    event.knownMask)
            } else PreparedEvent(event)
        }
        val prefixMatches = LongArray(events.size)
        val candidates = linkedMapOf<Long, MutableList<Long>>()
        val random = RandomState()
        val points = IntArray(STAT_COUNT)
        var candidateLimitReached = false
        var processedSeeds = 0L
        var matches = 0L
        var origin = beginInclusive

        while (origin < endExclusive) {
            if (isCancelled()) break

            var currentSeed = origin
            var matched = true
            for (eventIndex in preparedEvents.indices) {
                val nextSeed = matchPreparedEvent(currentSeed, preparedEvents[eventIndex], eventIndex > 0, random, points)
                if (nextSeed < 0) {
                    matched = false
                    break
                }
                currentSeed = nextSeed
                prefixMatches[eventIndex]++
            }

            if (matched) {
                matches++
                val origins = candidates[currentSeed]
                if (origins != null) {
                    origins.add(origin)
                } else if (candidates.size < maxCandidates) {
                    candidates[currentSeed] = mutableListOf(origin)
                } else {
                    candidateLimitReached = true
                }
            }

            processedSeeds++
            origin++
            if (processedSeeds % 65_536L == 0L) onProgress(SeedSearchProgress(processedSeeds, totalSeeds))
        }
        onProgress(SeedSearchProgress(processedSeeds, totalSeeds))

        val complete = processedSeeds == totalSeeds
        val status = when {
            !complete -> SeedSearchStatus.CANCELLED
            candidateLimitReached -> SeedSearchStatus.INCOMPLETE
            matches == 0L -> SeedSearchStatus.NO_MATCH
            matches == 1L -> SeedSearchStatus.UNIQUE
            else -> SeedSearchStatus.AMBIGUOUS
        }

        return SeedSearchResult(
            status = status,
            complete = complete,
            candidateLimitReached = candidateLimitReached,
            totalSeeds = totalSeeds,
            processedSeeds = processedSeeds,
            matches = matches,
            prefixMatches = prefixMatches.toList(),
            candidates = candidates.map { (current, origins) ->
                SeedSearchCandidate(currentSeed = current, originSeeds = origins.toList())
            },
        )
    }

    fun searchRangeParallel(
        events: List<ObservedSeedEvent>,
        beginInclusive: Long,
        endExclusive: Long,
        maxCandidates: Int = DEFAULT_MAX_TRACKED_CANDIDATES,
        parallelism: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
        isCancelled: () -> Boolean = { false },
        onProgress: (SeedSearchProgress) -> Unit = {},
    ): SeedSearchResult {
        require(beginInclusive in 0L..UINT32_LIMIT) { "beginInclusive must be a uint32 range boundary" }
        require(endExclusive in beginInclusive..UINT32_LIMIT) { "endExclusive must not precede beginInclusive" }
        require(maxCandidates >= 0) { "maxCandidates must not be negative" }
        require(parallelism > 0) { "parallelism must be positive" }
        val totalSeeds = endExclusive - beginInclusive
        if (events.isEmpty() || totalSeeds <= 4_096L || findImpossibleEventIndex(events) != null) {
            return searchRange(events, beginInclusive, endExclusive, maxCandidates, isCancelled, onProgress)
        }
        return searchRangeFastParallel(
            events = events,
            beginInclusive = beginInclusive,
            endExclusive = endExclusive,
            maxCandidates = maxCandidates,
            parallelism = parallelism,
            isCancelled = isCancelled,
            onProgress = onProgress,
        )
    }

    private sealed class PreparedFastEvent(val advanceBefore: Int) {
        class Level(
            advanceBefore: Int,
            val knownMask: Int,
            val eligLen: Int,
            val eligBase: IntArray,
            val eligThresh: IntArray,
            val eligWeight: LongArray,
            val eligRoom: IntArray,
            val eligExpected: IntArray,
            val eligKnown: BooleanArray,
            val lowOk: BooleanArray,
            val highOk: BooleanArray,
            val baseNz: BooleanArray,
            val guarantee: Int,
            val target: Int,
        ) : PreparedFastEvent(advanceBefore) {
            val isFast9: Boolean = eligLen == STAT_COUNT && eligThresh.all { it > 0 }
            val isFast9Base0: Boolean = isFast9 &&
                knownMask == ObservedSeedEvent.Level.ALL_STATS_MASK &&
                eligBase.all { it == 0 } &&
                eligRoom.all { it >= 1 } &&
                eligExpected.all { it == 0 || it == 1 }
        }

        class Exam(
            advanceBefore: Int,
            val canPass: Boolean,
            val threshold: Double,
            val passed: Boolean,
        ) : PreparedFastEvent(advanceBefore)

        class Advance(advanceBefore: Int) : PreparedFastEvent(advanceBefore)

        class Anchor(advanceBefore: Int, val seed: Long) : PreparedFastEvent(advanceBefore)
    }

    private class FastChunkResult(
        val from: Long,
        val processedSeeds: Long,
        val matches: Long,
        val prefixMatches: LongArray,
        val candidates: LinkedHashMap<Long, MutableList<Long>>,
        val candidateLimitReached: Boolean,
    )

    private fun prepareFast(event: ObservedSeedEvent): PreparedFastEvent = when (event) {
        is ObservedSeedEvent.Level -> {
            val growth = event.parameters.growth
            val room = event.parameters.room
            val expected = event.gains
            val eligible = (0 until STAT_COUNT).filter { growth[it] > 0 && room[it] > 0 }
            val len = eligible.size
            val eligBase = IntArray(len)
            val eligThresh = IntArray(len)
            val eligWeight = LongArray(len)
            val eligRoom = IntArray(len)
            val eligExpected = IntArray(len)
            val eligKnown = BooleanArray(len)
            val lowOk = BooleanArray(len)
            val highOk = BooleanArray(len)
            val baseNz = BooleanArray(len)
            for (k in 0 until len) {
                val idx = eligible[k]
                val g = growth[idx]
                val b = g / 100
                val r = room[idx]
                val exp = expected[idx]
                val known = (event.knownMask and (1 shl idx)) != 0
                eligBase[k] = b
                eligThresh[k] = (g % 100) * 10_000
                eligWeight[k] = g.toLong() * 100L
                eligRoom[k] = r
                eligExpected[k] = exp
                eligKnown[k] = known
                lowOk[k] = !known || minOf(b, r) == exp
                highOk[k] = !known || minOf(b + 1, r) == exp
                baseNz[k] = b != 0
            }
            val guarantee = minOf(event.parameters.guarantee, len)
            val target = maxOf(guarantee, 1)
            PreparedFastEvent.Level(
                advanceBefore = event.advanceBefore,
                knownMask = event.knownMask,
                eligLen = len,
                eligBase = eligBase,
                eligThresh = eligThresh,
                eligWeight = eligWeight,
                eligRoom = eligRoom,
                eligExpected = eligExpected,
                eligKnown = eligKnown,
                lowOk = lowOk,
                highOk = highOk,
                baseNz = baseNz,
                guarantee = guarantee,
                target = target,
            )
        }
        is ObservedSeedEvent.Exam -> PreparedFastEvent.Exam(
            advanceBefore = event.advanceBefore,
            canPass = event.parameters.probability >= 1.0,
            threshold = event.parameters.probability * 10_000.0,
            passed = event.passed,
        )
        is ObservedSeedEvent.Advance -> PreparedFastEvent.Advance(event.advanceBefore)
        is ObservedSeedEvent.Anchor -> PreparedFastEvent.Anchor(event.advanceBefore, event.seed)
    }

    private fun searchRangeFastParallel(
        events: List<ObservedSeedEvent>,
        beginInclusive: Long,
        endExclusive: Long,
        maxCandidates: Int,
        parallelism: Int,
        isCancelled: () -> Boolean,
        onProgress: (SeedSearchProgress) -> Unit,
    ): SeedSearchResult {
        val totalSeeds = endExclusive - beginInclusive
        val prepared = Array(events.size) { index -> prepareFast(events[index]) }
        val chunkSize = minOf(FAST_CHUNK_SIZE, maxOf(FAST_BLOCK_SIZE, (totalSeeds + parallelism - 1L) / parallelism))
        val workers = if (totalSeeds <= 250_000L || parallelism == 1) {
            1
        } else {
            minOf(parallelism.toLong(), (totalSeeds + chunkSize - 1L) / chunkSize).toInt().coerceAtLeast(1)
        }

        val nextCursor = AtomicLong(beginInclusive)
        val processedTotal = AtomicLong(0L)
        val lastProgressNanos = AtomicLong(System.nanoTime())
        val cancelFlag = AtomicBoolean(false)

        fun checkCancelled(): Boolean {
            if (cancelFlag.get()) return true
            if (isCancelled()) {
                cancelFlag.set(true)
                return true
            }
            return false
        }

        fun reportBlock(delta: Long) {
            val completed = processedTotal.addAndGet(delta)
            val now = System.nanoTime()
            val prev = lastProgressNanos.get()
            if (now - prev >= PROGRESS_INTERVAL_NANOS && lastProgressNanos.compareAndSet(prev, now)) {
                onProgress(SeedSearchProgress(completed, totalSeeds))
            }
        }

        val chunkResults = if (workers == 1) {
            listOf(
                scanFastChunk(
                    prepared = prepared,
                    from = beginInclusive,
                    until = endExclusive,
                    maxCandidates = maxCandidates,
                    checkCancelled = ::checkCancelled,
                    onBlock = ::reportBlock,
                    points = IntArray(STAT_COUNT),
                ),
            )
        } else {
            val executor = Executors.newFixedThreadPool(workers)
            try {
                val tasks = (0 until workers).map {
                    Callable<List<FastChunkResult>> {
                        val localResults = mutableListOf<FastChunkResult>()
                        val points = IntArray(STAT_COUNT)
                        while (!checkCancelled()) {
                            val from = nextCursor.getAndAdd(chunkSize)
                            if (from >= endExclusive) break
                            val until = minOf(from + chunkSize, endExclusive)
                            localResults.add(
                                scanFastChunk(
                                    prepared = prepared,
                                    from = from,
                                    until = until,
                                    maxCandidates = maxCandidates,
                                    checkCancelled = ::checkCancelled,
                                    onBlock = ::reportBlock,
                                    points = points,
                                ),
                            )
                        }
                        localResults
                    }
                }
                executor.invokeAll(tasks).flatMap { it.get() }
            } finally {
                executor.shutdownNow()
            }
        }

        val sortedChunks = chunkResults.sortedBy { it.from }
        val prefixMatches = LongArray(events.size)
        val candidates = linkedMapOf<Long, MutableList<Long>>()
        var matches = 0L
        var processedSeeds = 0L
        var candidateLimitReached = false

        for (chunk in sortedChunks) {
            processedSeeds += chunk.processedSeeds
            matches += chunk.matches
            candidateLimitReached = candidateLimitReached || chunk.candidateLimitReached
            for (i in prefixMatches.indices) {
                prefixMatches[i] += chunk.prefixMatches[i]
            }
            for ((currentSeed, origins) in chunk.candidates) {
                val existing = candidates[currentSeed]
                if (existing != null) {
                    existing.addAll(origins)
                } else if (candidates.size < maxCandidates) {
                    candidates[currentSeed] = origins.toMutableList()
                } else {
                    candidateLimitReached = true
                }
            }
        }

        onProgress(SeedSearchProgress(processedSeeds, totalSeeds))

        val complete = processedSeeds == totalSeeds
        val status = when {
            !complete -> SeedSearchStatus.CANCELLED
            candidateLimitReached -> SeedSearchStatus.INCOMPLETE
            matches == 0L -> SeedSearchStatus.NO_MATCH
            matches == 1L -> SeedSearchStatus.UNIQUE
            else -> SeedSearchStatus.AMBIGUOUS
        }

        return SeedSearchResult(
            status = status,
            complete = complete,
            candidateLimitReached = candidateLimitReached,
            totalSeeds = totalSeeds,
            processedSeeds = processedSeeds,
            matches = matches,
            prefixMatches = prefixMatches.toList(),
            candidates = candidates.map { (current, origins) ->
                SeedSearchCandidate(currentSeed = current, originSeeds = origins.toList())
            },
        )
    }

    private fun scanFastChunk(
        prepared: Array<PreparedFastEvent>,
        from: Long,
        until: Long,
        maxCandidates: Int,
        checkCancelled: () -> Boolean,
        onBlock: (Long) -> Unit,
        points: IntArray,
    ): FastChunkResult {
        if (prepared.isNotEmpty()) {
            val first = prepared[0]
            if (first is PreparedFastEvent.Level && first.isFast9Base0) {
                return scanFastChunk9Base0(prepared, first, from, until, maxCandidates, checkCancelled, onBlock, points)
            }
            if (first is PreparedFastEvent.Level && first.isFast9) {
                return scanFastChunk9(prepared, first, from, until, maxCandidates, checkCancelled, onBlock, points)
            }
        }
        return scanFastChunkGeneric(prepared, from, until, maxCandidates, checkCancelled, onBlock, points)
    }

    private fun scanFastChunk9Base0(
        prepared: Array<PreparedFastEvent>,
        ev0: PreparedFastEvent.Level,
        from: Long,
        until: Long,
        maxCandidates: Int,
        checkCancelled: () -> Boolean,
        onBlock: (Long) -> Unit,
        points: IntArray,
    ): FastChunkResult {
        val eventCount = prepared.size
        val prefixMatches = LongArray(eventCount)
        val candidates = linkedMapOf<Long, MutableList<Long>>()
        var matches = 0L
        var processed = 0L
        var candidateLimitReached = false

        val t0 = ev0.eligThresh[0]; val t1 = ev0.eligThresh[1]; val t2 = ev0.eligThresh[2]
        val t3 = ev0.eligThresh[3]; val t4 = ev0.eligThresh[4]; val t5 = ev0.eligThresh[5]
        val t6 = ev0.eligThresh[6]; val t7 = ev0.eligThresh[7]; val t8 = ev0.eligThresh[8]

        val e0 = ev0.eligExpected[0]; val e1 = ev0.eligExpected[1]; val e2 = ev0.eligExpected[2]
        val e3 = ev0.eligExpected[3]; val e4 = ev0.eligExpected[4]; val e5 = ev0.eligExpected[5]
        val e6 = ev0.eligExpected[6]; val e7 = ev0.eligExpected[7]; val e8 = ev0.eligExpected[8]

        val target0 = ev0.target

        var blockStart = from
        while (blockStart < until) {
            if (checkCancelled()) break
            val blockEnd = minOf(blockStart + FAST_BLOCK_SIZE, until)
            val blockLen = (blockEnd - blockStart).toInt()
            val baseSeed = blockStart.toInt()
            var offset = 0
            while (offset < blockLen) {
                val seedBits = baseSeed + offset
                offset++

                var x = MULTIPLIER * (seedBits xor (seedBits ushr 30))
                var y = MULTIPLIER * (x xor (x ushr 30)) + 1
                var z = MULTIPLIER * (y xor (y ushr 30)) + 2
                var w = MULTIPLIER * (z xor (z ushr 30)) + 3

                // Branchless stats 0..3 with Barrett mod 1_000_000 and progressive early reject
                var tmp = x xor (x shl 11)
                x = y; y = z; z = w
                w = w xor (w ushr 19) xor tmp xor (tmp ushr 8)
                val firstDraw = w and OUTPUT_MASK
                val r0 = firstDraw - (((firstDraw.toLong() * MILLION_BARRETT_MAGIC) ushr 50).toInt() * MILLION)
                val b0 = (r0 - t0) ushr 31
                var count = b0
                var diff = b0 xor e0
                if (count >= target0 && diff != 0) continue

                tmp = x xor (x shl 11)
                x = y; y = z; z = w
                w = w xor (w ushr 19) xor tmp xor (tmp ushr 8)
                val d1 = w and OUTPUT_MASK
                val r1 = d1 - (((d1.toLong() * MILLION_BARRETT_MAGIC) ushr 50).toInt() * MILLION)
                val b1 = (r1 - t1) ushr 31
                count += b1
                diff = diff or (b1 xor e1)
                if (count >= target0 && diff != 0) continue

                tmp = x xor (x shl 11)
                x = y; y = z; z = w
                w = w xor (w ushr 19) xor tmp xor (tmp ushr 8)
                val d2 = w and OUTPUT_MASK
                val r2 = d2 - (((d2.toLong() * MILLION_BARRETT_MAGIC) ushr 50).toInt() * MILLION)
                val b2 = (r2 - t2) ushr 31
                count += b2
                diff = diff or (b2 xor e2)
                if (count >= target0 && diff != 0) continue

                tmp = x xor (x shl 11)
                x = y; y = z; z = w
                w = w xor (w ushr 19) xor tmp xor (tmp ushr 8)
                val d3 = w and OUTPUT_MASK
                val r3 = d3 - (((d3.toLong() * MILLION_BARRETT_MAGIC) ushr 50).toInt() * MILLION)
                val b3 = (r3 - t3) ushr 31
                count += b3
                diff = diff or (b3 xor e3)
                if (count >= target0 && diff != 0) continue

                // Branchless stats 4..8 with Barrett mod 1_000_000
                tmp = x xor (x shl 11)
                x = y; y = z; z = w
                w = w xor (w ushr 19) xor tmp xor (tmp ushr 8)
                val d4 = w and OUTPUT_MASK
                val r4 = d4 - (((d4.toLong() * MILLION_BARRETT_MAGIC) ushr 50).toInt() * MILLION)
                val b4 = (r4 - t4) ushr 31

                tmp = x xor (x shl 11)
                x = y; y = z; z = w
                w = w xor (w ushr 19) xor tmp xor (tmp ushr 8)
                val d5 = w and OUTPUT_MASK
                val r5 = d5 - (((d5.toLong() * MILLION_BARRETT_MAGIC) ushr 50).toInt() * MILLION)
                val b5 = (r5 - t5) ushr 31

                tmp = x xor (x shl 11)
                x = y; y = z; z = w
                w = w xor (w ushr 19) xor tmp xor (tmp ushr 8)
                val d6 = w and OUTPUT_MASK
                val r6 = d6 - (((d6.toLong() * MILLION_BARRETT_MAGIC) ushr 50).toInt() * MILLION)
                val b6 = (r6 - t6) ushr 31

                tmp = x xor (x shl 11)
                x = y; y = z; z = w
                w = w xor (w ushr 19) xor tmp xor (tmp ushr 8)
                val d7 = w and OUTPUT_MASK
                val r7 = d7 - (((d7.toLong() * MILLION_BARRETT_MAGIC) ushr 50).toInt() * MILLION)
                val b7 = (r7 - t7) ushr 31

                tmp = x xor (x shl 11)
                x = y; y = z; z = w
                w = w xor (w ushr 19) xor tmp xor (tmp ushr 8)
                val d8 = w and OUTPUT_MASK
                val r8 = d8 - (((d8.toLong() * MILLION_BARRETT_MAGIC) ushr 50).toInt() * MILLION)
                val b8 = (r8 - t8) ushr 31

                count += b4 + b5 + b6 + b7 + b8
                diff = diff or (b4 xor e4) or (b5 xor e5) or (b6 xor e6) or (b7 xor e7) or (b8 xor e8)

                val nextAfterFirst = if (count >= target0) {
                    if (diff != 0) continue
                    firstDraw.toLong()
                } else {
                    finishFastLevelSlowRounds(x, y, z, w, firstDraw, ev0, points)
                }
                if (nextAfterFirst < 0L) continue

                prefixMatches[0]++
                var currentSeed = nextAfterFirst
                var matched = true
                var eventIndex = 1
                while (eventIndex < eventCount) {
                    val nextSeed = matchPreparedFast(currentSeed, prepared[eventIndex], applyAdvanceBefore = true, points = points)
                    if (nextSeed < 0L) {
                        matched = false
                        break
                    }
                    currentSeed = nextSeed
                    prefixMatches[eventIndex]++
                    eventIndex++
                }

                if (matched) {
                    val origin = seedBits.toLong() and 0xffff_ffffL
                    matches++
                    val origins = candidates[currentSeed]
                    if (origins != null) {
                        origins.add(origin)
                    } else if (candidates.size < maxCandidates) {
                        candidates[currentSeed] = mutableListOf(origin)
                    } else {
                        candidateLimitReached = true
                    }
                }
            }
            processed += blockLen
            onBlock(blockLen.toLong())
            blockStart = blockEnd
        }

        return FastChunkResult(from, processed, matches, prefixMatches, candidates, candidateLimitReached)
    }

    private fun scanFastChunk9(
        prepared: Array<PreparedFastEvent>,
        ev0: PreparedFastEvent.Level,
        from: Long,
        until: Long,
        maxCandidates: Int,
        checkCancelled: () -> Boolean,
        onBlock: (Long) -> Unit,
        points: IntArray,
    ): FastChunkResult {
        val eventCount = prepared.size
        val prefixMatches = LongArray(eventCount)
        val candidates = linkedMapOf<Long, MutableList<Long>>()
        var matches = 0L
        var processed = 0L
        var candidateLimitReached = false

        val t0 = ev0.eligThresh[0]; val t1 = ev0.eligThresh[1]; val t2 = ev0.eligThresh[2]
        val t3 = ev0.eligThresh[3]; val t4 = ev0.eligThresh[4]; val t5 = ev0.eligThresh[5]
        val t6 = ev0.eligThresh[6]; val t7 = ev0.eligThresh[7]; val t8 = ev0.eligThresh[8]

        val hi0 = ev0.highOk[0]; val hi1 = ev0.highOk[1]; val hi2 = ev0.highOk[2]
        val hi3 = ev0.highOk[3]; val hi4 = ev0.highOk[4]; val hi5 = ev0.highOk[5]
        val hi6 = ev0.highOk[6]; val hi7 = ev0.highOk[7]; val hi8 = ev0.highOk[8]

        val lo0 = ev0.lowOk[0]; val lo1 = ev0.lowOk[1]; val lo2 = ev0.lowOk[2]
        val lo3 = ev0.lowOk[3]; val lo4 = ev0.lowOk[4]; val lo5 = ev0.lowOk[5]
        val lo6 = ev0.lowOk[6]; val lo7 = ev0.lowOk[7]; val lo8 = ev0.lowOk[8]

        val bz0 = ev0.baseNz[0]; val bz1 = ev0.baseNz[1]; val bz2 = ev0.baseNz[2]
        val bz3 = ev0.baseNz[3]; val bz4 = ev0.baseNz[4]; val bz5 = ev0.baseNz[5]
        val bz6 = ev0.baseNz[6]; val bz7 = ev0.baseNz[7]; val bz8 = ev0.baseNz[8]

        val target0 = ev0.target

        var blockStart = from
        while (blockStart < until) {
            if (checkCancelled()) break
            val blockEnd = minOf(blockStart + FAST_BLOCK_SIZE, until)
            val blockLen = (blockEnd - blockStart).toInt()
            val baseSeed = blockStart.toInt()
            var offset = 0
            while (offset < blockLen) {
                val seedBits = baseSeed + offset
                offset++

                var x = MULTIPLIER * (seedBits xor (seedBits ushr 30))
                var y = MULTIPLIER * (x xor (x ushr 30)) + 1
                var z = MULTIPLIER * (y xor (y ushr 30)) + 2
                var w = MULTIPLIER * (z xor (z ushr 30)) + 3

                var count = 0
                var mismatch = false

                var tmp = x xor (x shl 11)
                x = y; y = z; z = w
                w = w xor (w ushr 19) xor tmp xor (tmp ushr 8)
                val firstDraw = w and OUTPUT_MASK
                if (firstDraw - (((firstDraw.toLong() * MILLION_BARRETT_MAGIC) ushr 50).toInt() * MILLION) < t0) { count++; if (!hi0) mismatch = true } else { if (bz0) count++; if (!lo0) mismatch = true }
                if (count >= target0 && mismatch) continue

                tmp = x xor (x shl 11); x = y; y = z; z = w; w = w xor (w ushr 19) xor tmp xor (tmp ushr 8)
                var d = w and OUTPUT_MASK
                if (d - (((d.toLong() * MILLION_BARRETT_MAGIC) ushr 50).toInt() * MILLION) < t1) { count++; if (!hi1) mismatch = true } else { if (bz1) count++; if (!lo1) mismatch = true }
                if (count >= target0 && mismatch) continue

                tmp = x xor (x shl 11); x = y; y = z; z = w; w = w xor (w ushr 19) xor tmp xor (tmp ushr 8)
                d = w and OUTPUT_MASK
                if (d - (((d.toLong() * MILLION_BARRETT_MAGIC) ushr 50).toInt() * MILLION) < t2) { count++; if (!hi2) mismatch = true } else { if (bz2) count++; if (!lo2) mismatch = true }
                if (count >= target0 && mismatch) continue

                tmp = x xor (x shl 11); x = y; y = z; z = w; w = w xor (w ushr 19) xor tmp xor (tmp ushr 8)
                d = w and OUTPUT_MASK
                if (d - (((d.toLong() * MILLION_BARRETT_MAGIC) ushr 50).toInt() * MILLION) < t3) { count++; if (!hi3) mismatch = true } else { if (bz3) count++; if (!lo3) mismatch = true }
                if (count >= target0 && mismatch) continue

                tmp = x xor (x shl 11); x = y; y = z; z = w; w = w xor (w ushr 19) xor tmp xor (tmp ushr 8)
                d = w and OUTPUT_MASK
                if (d - (((d.toLong() * MILLION_BARRETT_MAGIC) ushr 50).toInt() * MILLION) < t4) { count++; if (!hi4) mismatch = true } else { if (bz4) count++; if (!lo4) mismatch = true }
                if (count >= target0 && mismatch) continue

                tmp = x xor (x shl 11); x = y; y = z; z = w; w = w xor (w ushr 19) xor tmp xor (tmp ushr 8)
                d = w and OUTPUT_MASK
                if (d - (((d.toLong() * MILLION_BARRETT_MAGIC) ushr 50).toInt() * MILLION) < t5) { count++; if (!hi5) mismatch = true } else { if (bz5) count++; if (!lo5) mismatch = true }
                if (count >= target0 && mismatch) continue

                tmp = x xor (x shl 11); x = y; y = z; z = w; w = w xor (w ushr 19) xor tmp xor (tmp ushr 8)
                d = w and OUTPUT_MASK
                if (d - (((d.toLong() * MILLION_BARRETT_MAGIC) ushr 50).toInt() * MILLION) < t6) { count++; if (!hi6) mismatch = true } else { if (bz6) count++; if (!lo6) mismatch = true }
                if (count >= target0 && mismatch) continue

                tmp = x xor (x shl 11); x = y; y = z; z = w; w = w xor (w ushr 19) xor tmp xor (tmp ushr 8)
                d = w and OUTPUT_MASK
                if (d - (((d.toLong() * MILLION_BARRETT_MAGIC) ushr 50).toInt() * MILLION) < t7) { count++; if (!hi7) mismatch = true } else { if (bz7) count++; if (!lo7) mismatch = true }
                if (count >= target0 && mismatch) continue

                tmp = x xor (x shl 11); x = y; y = z; z = w; w = w xor (w ushr 19) xor tmp xor (tmp ushr 8)
                d = w and OUTPUT_MASK
                if (d - (((d.toLong() * MILLION_BARRETT_MAGIC) ushr 50).toInt() * MILLION) < t8) { count++; if (!hi8) mismatch = true } else { if (bz8) count++; if (!lo8) mismatch = true }
                if (count >= target0 && mismatch) continue

                val nextAfterFirst = if (count >= target0) {
                    firstDraw.toLong()
                } else {
                    finishFastLevelSlowRounds(x, y, z, w, firstDraw, ev0, points)
                }
                if (nextAfterFirst < 0L) continue

                prefixMatches[0]++
                var currentSeed = nextAfterFirst
                var matched = true
                var eventIndex = 1
                while (eventIndex < eventCount) {
                    val nextSeed = matchPreparedFast(currentSeed, prepared[eventIndex], applyAdvanceBefore = true, points = points)
                    if (nextSeed < 0L) {
                        matched = false
                        break
                    }
                    currentSeed = nextSeed
                    prefixMatches[eventIndex]++
                    eventIndex++
                }

                if (matched) {
                    val origin = seedBits.toLong() and 0xffff_ffffL
                    matches++
                    val origins = candidates[currentSeed]
                    if (origins != null) {
                        origins.add(origin)
                    } else if (candidates.size < maxCandidates) {
                        candidates[currentSeed] = mutableListOf(origin)
                    } else {
                        candidateLimitReached = true
                    }
                }
            }
            processed += blockLen
            onBlock(blockLen.toLong())
            blockStart = blockEnd
        }

        return FastChunkResult(from, processed, matches, prefixMatches, candidates, candidateLimitReached)
    }

    private fun scanFastChunkGeneric(
        prepared: Array<PreparedFastEvent>,
        from: Long,
        until: Long,
        maxCandidates: Int,
        checkCancelled: () -> Boolean,
        onBlock: (Long) -> Unit,
        points: IntArray,
    ): FastChunkResult {
        val eventCount = prepared.size
        val prefixMatches = LongArray(eventCount)
        val candidates = linkedMapOf<Long, MutableList<Long>>()
        var matches = 0L
        var processed = 0L
        var candidateLimitReached = false

        var blockStart = from
        while (blockStart < until) {
            if (checkCancelled()) break
            val blockEnd = minOf(blockStart + FAST_BLOCK_SIZE, until)
            val blockLen = (blockEnd - blockStart).toInt()
            val baseSeed = blockStart.toInt()
            var offset = 0
            while (offset < blockLen) {
                val origin = (baseSeed + offset).toLong() and 0xffff_ffffL
                offset++

                var currentSeed = origin
                var matched = true
                var eventIndex = 0
                while (eventIndex < eventCount) {
                    val nextSeed = matchPreparedFast(currentSeed, prepared[eventIndex], eventIndex > 0, points)
                    if (nextSeed < 0L) {
                        matched = false
                        break
                    }
                    currentSeed = nextSeed
                    prefixMatches[eventIndex]++
                    eventIndex++
                }

                if (matched) {
                    matches++
                    val origins = candidates[currentSeed]
                    if (origins != null) {
                        origins.add(origin)
                    } else if (candidates.size < maxCandidates) {
                        candidates[currentSeed] = mutableListOf(origin)
                    } else {
                        candidateLimitReached = true
                    }
                }
            }
            processed += blockLen
            onBlock(blockLen.toLong())
            blockStart = blockEnd
        }

        return FastChunkResult(from, processed, matches, prefixMatches, candidates, candidateLimitReached)
    }

    private fun advanceInt(seedBits: Int): Int {
        val x = MULTIPLIER * (seedBits xor (seedBits ushr 30))
        val y = MULTIPLIER * (x xor (x ushr 30)) + 1
        val z = MULTIPLIER * (y xor (y ushr 30)) + 2
        val w = MULTIPLIER * (z xor (z ushr 30)) + 3
        val t = x xor (x shl 11)
        val w1 = w xor (w ushr 19) xor t xor (t ushr 8)
        return w1 and OUTPUT_MASK
    }

    private fun matchPreparedFast(
        seed: Long,
        event: PreparedFastEvent,
        applyAdvanceBefore: Boolean,
        points: IntArray,
    ): Long {
        var seedBits = seed.toInt()
        if (applyAdvanceBefore) {
            var remaining = event.advanceBefore
            while (remaining > 0) {
                seedBits = advanceInt(seedBits)
                remaining--
            }
        }
        return when (event) {
            is PreparedFastEvent.Advance -> advanceInt(seedBits).toLong()
            is PreparedFastEvent.Anchor -> {
                val current = seedBits.toLong() and 0xffff_ffffL
                if (current == event.seed) current else -1L
            }
            is PreparedFastEvent.Exam -> {
                var x = MULTIPLIER * (seedBits xor (seedBits ushr 30))
                var y = MULTIPLIER * (x xor (x ushr 30)) + 1
                var z = MULTIPLIER * (y xor (y ushr 30)) + 2
                var w = MULTIPLIER * (z xor (z ushr 30)) + 3
                var t = x xor (x shl 11)
                x = y; y = z; z = w
                w = w xor (w ushr 19) xor t xor (t ushr 8)
                val nextSeed = w and OUTPUT_MASK
                t = x xor (x shl 11)
                w = w xor (w ushr 19) xor t xor (t ushr 8)
                val roll = (w and OUTPUT_MASK) % MILLION
                val passed = event.canPass && roll < event.threshold
                if (passed == event.passed) nextSeed.toLong() else -1L
            }
            is PreparedFastEvent.Level -> matchFastLevel(seedBits, event, points)
        }
    }

    private fun matchFastLevel(
        seedBits: Int,
        ev: PreparedFastEvent.Level,
        points: IntArray,
    ): Long {
        var x = MULTIPLIER * (seedBits xor (seedBits ushr 30))
        var y = MULTIPLIER * (x xor (x ushr 30)) + 1
        var z = MULTIPLIER * (y xor (y ushr 30)) + 2
        var w = MULTIPLIER * (z xor (z ushr 30)) + 3

        val eligLen = ev.eligLen
        if (eligLen == 0) {
            val t = x xor (x shl 11)
            val w1 = w xor (w ushr 19) xor t xor (t ushr 8)
            return (w1 and OUTPUT_MASK).toLong()
        }

        val eligThresh = ev.eligThresh
        val highOk = ev.highOk
        val lowOk = ev.lowOk
        val baseNz = ev.baseNz
        val target = ev.target

        var firstDraw = -1
        var count = 0
        var mismatch = false
        var k = 0
        while (k < eligLen) {
            val thresh = eligThresh[k]
            if (thresh > 0) {
                val t = x xor (x shl 11)
                x = y; y = z; z = w
                w = w xor (w ushr 19) xor t xor (t ushr 8)
                val r = w and OUTPUT_MASK
                if (firstDraw < 0) firstDraw = r
                if (r % MILLION < thresh) {
                    count++
                    if (!highOk[k]) mismatch = true
                } else {
                    if (baseNz[k]) count++
                    if (!lowOk[k]) mismatch = true
                }
            } else {
                if (baseNz[k]) count++
                if (!lowOk[k]) mismatch = true
            }
            if (count >= target && mismatch) return -1L
            k++
        }
        if (count >= target) {
            if (firstDraw < 0) {
                val t = x xor (x shl 11)
                val w1 = w xor (w ushr 19) xor t xor (t ushr 8)
                firstDraw = w1 and OUTPUT_MASK
            }
            return firstDraw.toLong()
        }
        val resolvedFirstDraw = if (firstDraw >= 0) firstDraw else advanceInt(seedBits)
        return finishFastLevelSlowRounds(x, y, z, w, resolvedFirstDraw, ev, points)
    }

    private fun finishFastLevelSlowRounds(
        xIn: Int,
        yIn: Int,
        zIn: Int,
        wIn: Int,
        firstDraw: Int,
        ev: PreparedFastEvent.Level,
        points: IntArray,
    ): Long {
        var x = xIn
        var y = yIn
        var z = zIn
        var w = wIn

        val eligLen = ev.eligLen
        val eligBase = ev.eligBase
        val eligThresh = ev.eligThresh
        val highOk = ev.highOk
        val lowOk = ev.lowOk
        val baseNz = ev.baseNz
        val target = ev.target
        val guarantee = ev.guarantee

        var round = 2
        while (round <= 3) {
            var count = 0
            var mismatch = false
            var k = 0
            while (k < eligLen) {
                val thresh = eligThresh[k]
                if (thresh > 0) {
                    val t = x xor (x shl 11)
                    x = y; y = z; z = w
                    w = w xor (w ushr 19) xor t xor (t ushr 8)
                    val r = w and OUTPUT_MASK
                    if (r % MILLION < thresh) {
                        count++
                        if (!highOk[k]) mismatch = true
                    } else {
                        if (baseNz[k]) count++
                        if (!lowOk[k]) mismatch = true
                    }
                } else {
                    if (baseNz[k]) count++
                    if (!lowOk[k]) mismatch = true
                }
                if (count >= target && mismatch) return -1L
                k++
            }
            if (count >= target) return firstDraw.toLong()
            round++
        }

        var count = 0
        var k = 0
        while (k < eligLen) {
            val thresh = eligThresh[k]
            val p = if (thresh > 0) {
                val t = x xor (x shl 11)
                x = y; y = z; z = w
                w = w xor (w ushr 19) xor t xor (t ushr 8)
                val r = w and OUTPUT_MASK
                eligBase[k] + if (r % MILLION < thresh) 1 else 0
            } else {
                eligBase[k]
            }
            points[k] = p
            if (p != 0) count++
            k++
        }

        val eligWeight = ev.eligWeight
        while (count < guarantee) {
            var totalWeight = 0L
            k = 0
            while (k < eligLen) {
                if (points[k] == 0) totalWeight += eligWeight[k]
                k++
            }
            val t = x xor (x shl 11)
            x = y; y = z; z = w
            w = w xor (w ushr 19) xor t xor (t ushr 8)
            var pick = (w and OUTPUT_MASK).toLong() % totalWeight
            var selected = -1
            k = 0
            while (k < eligLen) {
                if (points[k] == 0) {
                    pick -= eligWeight[k]
                    if (pick < 0) {
                        selected = k
                        break
                    }
                }
                k++
            }
            check(selected >= 0)
            points[selected] = 1
            count++
        }

        val eligRoom = ev.eligRoom
        val eligExpected = ev.eligExpected
        val eligKnown = ev.eligKnown
        k = 0
        while (k < eligLen) {
            if (eligKnown[k]) {
                val actual = if (points[k] < eligRoom[k]) points[k] else eligRoom[k]
                if (actual != eligExpected[k]) return -1L
            }
            k++
        }
        return firstDraw.toLong()
    }

    private fun matchEvent(
        seed: Long,
        event: ObservedSeedEvent,
        applyAdvanceBefore: Boolean,
    ): EventMatchResult {
        var currentSeed = validateSeed(seed)
        if (applyAdvanceBefore) {
            var index = 0
            while (index < event.advanceBefore) {
                currentSeed = advance(currentSeed)
                index++
            }
        }

        return when (event) {
            is ObservedSeedEvent.Level -> {
                val result = simulateLevel(currentSeed, event.parameters)
                val matched = (0 until STAT_COUNT).all { index ->
                    (event.knownMask and (1 shl index)) == 0 || result.gains[index] == event.gains[index]
                }
                if (matched) EventMatchResult.Matched(result.nextSeed)
                else EventMatchResult.Mismatch
            }
            is ObservedSeedEvent.Exam -> {
                val result = simulateExam(currentSeed, event.parameters)
                if (result.passed == event.passed) EventMatchResult.Matched(result.nextSeed)
                else EventMatchResult.Mismatch
            }
            is ObservedSeedEvent.Advance -> EventMatchResult.Matched(advance(currentSeed))
            is ObservedSeedEvent.Anchor -> if (currentSeed == event.seed) EventMatchResult.Matched(currentSeed) else EventMatchResult.Mismatch
        }
    }

    private fun matchPreparedEvent(
        seed: Long,
        prepared: PreparedEvent,
        applyAdvanceBefore: Boolean,
        random: RandomState,
        points: IntArray,
    ): Long {
        var currentSeed = seed
        if (applyAdvanceBefore) repeat(prepared.event.advanceBefore) {
            random.reset(currentSeed)
            currentSeed = random.draw().toLong()
        }
        random.reset(currentSeed)
        val nextSeed = random.draw().toLong()
        return when (val event = prepared.event) {
            is ObservedSeedEvent.Advance -> nextSeed
            is ObservedSeedEvent.Anchor -> if (currentSeed == event.seed) currentSeed else -1L
            is ObservedSeedEvent.Exam -> {
                val roll = random.draw() % MILLION
                val passed = event.parameters.probability >= 1.0 && roll < event.parameters.probability * 10_000.0
                if (passed == event.passed) nextSeed else -1L
            }
            is ObservedSeedEvent.Level -> {
                random.reset(currentSeed)
                val growth = requireNotNull(prepared.growth)
                val room = requireNotNull(prepared.room)
                val expected = requireNotNull(prepared.expectedGains)
                points.fill(0)
                val eligible = prepared.eligibleStats
                val guarantee = minOf(event.parameters.guarantee, eligible.size)
                val target = maxOf(guarantee, 1)
                var count: Int
                var rounds = 0
                do {
                    count = 0
                    rounds++
                    for (index in eligible) {
                        val value = growth[index]
                        val remainder = value % 100
                        points[index] = value / 100 + if (remainder > 0 && random.draw() % MILLION < remainder * 10_000) 1 else 0
                        if (points[index] != 0) count++
                    }
                } while (count < target && rounds < 4)
                while (count < guarantee) {
                    var totalWeight = 0L
                    for (index in eligible) if (points[index] == 0) totalWeight += growth[index].toLong() * 100L
                    var pick = random.draw().toLong() % totalWeight
                    var selected = -1
                    for (index in eligible) {
                        if (points[index] != 0) continue
                        pick -= growth[index].toLong() * 100L
                        if (pick < 0) { selected = index; break }
                    }
                    check(selected >= 0)
                    points[selected] = 1
                    count++
                }
                val mask = prepared.knownMask
                val matched = if (mask == ObservedSeedEvent.Level.ALL_STATS_MASK) {
                    (0 until STAT_COUNT).all { minOf(points[it], room[it]) == expected[it] }
                } else {
                    (0 until STAT_COUNT).all { (mask and (1 shl it)) == 0 || minOf(points[it], room[it]) == expected[it] }
                }
                if (matched) nextSeed else -1L
            }
        }
    }

    private fun findImpossibleEventIndex(events: List<ObservedSeedEvent>): Int? {
        for ((eventIndex, event) in events.withIndex()) {
            val impossible = when (event) {
                is ObservedSeedEvent.Exam -> event.passed && event.parameters.probability < 1.0
                is ObservedSeedEvent.Level -> {
                    val parameters = event.parameters
                    var found = false
                    for (index in 0 until STAT_COUNT) {
                        if ((event.knownMask and (1 shl index)) == 0) continue
                        val growth = parameters.growth[index]
                        val room = parameters.room[index]
                        val gain = event.gains[index]
                        val eligible = growth > 0 && room > 0
                        if (!eligible) {
                            if (gain != 0) found = true
                        } else {
                            val base = growth / 100
                            val low = minOf(base, room)
                            val high = if (growth % 100 > 0) minOf(base + 1, room) else low
                            if (gain != low && gain != high) found = true
                        }
                        if (found) break
                    }
                    found
                }
                is ObservedSeedEvent.Advance -> false
                is ObservedSeedEvent.Anchor -> false
            }
            if (impossible) return eventIndex
        }
        return null
    }

    private fun chance(state: RandomState, remainder: Int): Boolean =
        state.draw() % MILLION < remainder * 10_000

    private fun validateSeed(seed: Long): Long {
        require(seed in 0 until UINT32_LIMIT) { "seed must be an unsigned 32-bit value" }
        return seed
    }

    private class RandomState(seed: Long = 0L) {
        private var x: Int = 0
        private var y: Int = 0
        private var z: Int = 0
        private var w: Int = 0

        init { reset(seed) }

        fun reset(seed: Long) {
            val seedBits = validateSeed(seed).toInt()
            x = MULTIPLIER * (seedBits xor (seedBits ushr 30))
            y = MULTIPLIER * (x xor (x ushr 30)) + 1
            z = MULTIPLIER * (y xor (y ushr 30)) + 2
            w = MULTIPLIER * (z xor (z ushr 30)) + 3
        }

        fun draw(): Int {
            val t = x xor (x shl 11)
            x = y
            y = z
            z = w
            w = w xor (w ushr 19) xor t xor (t ushr 8)
            return w and OUTPUT_MASK
        }
    }
}
