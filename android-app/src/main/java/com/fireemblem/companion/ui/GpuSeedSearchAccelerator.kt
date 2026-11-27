package com.fireemblem.companion.ui

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES31
import com.fireemblem.companion.engine.EventMatchResult
import com.fireemblem.companion.engine.LevelParameters
import com.fireemblem.companion.engine.ObservedSeedEvent
import com.fireemblem.companion.engine.SeedEngine
import com.fireemblem.companion.engine.SeedSearchCandidate
import com.fireemblem.companion.engine.SeedSearchProgress
import com.fireemblem.companion.engine.SeedSearchResult
import com.fireemblem.companion.engine.SeedSearchStatus
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference

internal enum class SeedSearchBackend(val label: String) {
    GPU_COMPUTE("GPU 计算着色器极速"),
    CPU_MULTI_CORE("CPU 多核位运算"),
}

internal data class AcceleratedSeedSearchOutcome(
    val result: SeedSearchResult,
    val backend: SeedSearchBackend,
    val elapsedMillis: Long,
)

/**
 * Hybrid OpenGL ES 3.1 Compute Shader + Exact CPU Verification seed search engine.
 *
 * - Runs zero-NDK GLSL ES 3.10 compute shaders on mobile GPUs (Adreno / Mali / Immortalis)
 *   using exact 64-bit Barrett magic multiplication (`umulExtended` with 0x431BDE83u — zero
 *   integer division) and workgroup shared-memory reduction (128x fewer global atomics).
 * - Supports ALL uncapped growth profiles including 0% and >=100% stat growths across Events 0..2,
 *   reducing 4.29B seeds to a tiny handful of candidate origins verified bit-for-bit on CPU.
 * - Performs a one-time deterministic self-test against [SeedEngine.searchRange] before trusting
 *   a device GPU; any unsupported driver, overflow chunk, or self-test discrepancy falls back
 *   transparently to multi-core Barrett CPU [SeedEngine.searchRangeParallel].
 */
internal object GpuSeedSearchAccelerator {
    private const val EGL_OPENGL_ES3_BIT_KHR = 0x00000040
    private const val EGL_CONTEXT_MAJOR_VERSION_KHR = 0x3098
    private const val EGL_CONTEXT_MINOR_VERSION_KHR = 0x30FB
    private const val LOCAL_SIZE = 128
    private const val GROUPS_X = 2048
    private const val GROUPS_Y_SINGLE = 32   // 8,388,608 seeds/dispatch (safer for 1-event hit density)
    private const val GROUPS_Y_MULTI = 64    // 16,777,216 seeds/dispatch (2x fewer GPU syncs for 2+ events)
    private const val MIN_SEEDS_FOR_GPU = 131_072L
    private const val MAX_GPU_OUT = 65_536
    private const val HEADER_UINTS = 4 // [0]=outCount, [1]=overflow, [2]=pass0, [3]=pass1

    private val selfTestVerified = AtomicReference<Boolean?>(null)
    @Volatile
    var lastProbeDiagnostics: String = "unprobed"
        private set

    fun isGpuAvailableHint(): Boolean {
        selfTestVerified.get()?.let { return it }
        return runCatching { probeAndSelfTest() }.getOrElse { err ->
            lastProbeDiagnostics = "exception: ${err::class.java.simpleName}: ${err.message}"
            selfTestVerified.set(false)
            false
        }
    }

    fun search(
        events: List<ObservedSeedEvent>,
        beginInclusive: Long,
        endExclusive: Long,
        maxCandidates: Int = SeedEngine.DEFAULT_MAX_TRACKED_CANDIDATES,
        isCancelled: () -> Boolean = { false },
        onProgress: (SeedSearchProgress) -> Unit = {},
    ): AcceleratedSeedSearchOutcome {
        val startedAt = System.nanoTime()
        val totalSeeds = endExclusive - beginInclusive
        if (events.isEmpty() || totalSeeds < MIN_SEEDS_FOR_GPU || !canAccelerateOnGpu(events)) {
            val cpuResult = SeedEngine.searchRangeParallel(
                events = events,
                beginInclusive = beginInclusive,
                endExclusive = endExclusive,
                maxCandidates = maxCandidates,
                isCancelled = isCancelled,
                onProgress = onProgress,
            )
            return AcceleratedSeedSearchOutcome(
                result = cpuResult,
                backend = SeedSearchBackend.CPU_MULTI_CORE,
                elapsedMillis = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(1L),
            )
        }

        val gpuOutcome = runCatching {
            if (!isGpuAvailableHint()) return@runCatching null
            searchOnGpu(events, beginInclusive, endExclusive, maxCandidates, isCancelled, onProgress)
        }.getOrNull()

        if (gpuOutcome != null) {
            return AcceleratedSeedSearchOutcome(
                result = gpuOutcome,
                backend = SeedSearchBackend.GPU_COMPUTE,
                elapsedMillis = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(1L),
            )
        }

        val fallback = SeedEngine.searchRangeParallel(
            events = events,
            beginInclusive = beginInclusive,
            endExclusive = endExclusive,
            maxCandidates = maxCandidates,
            isCancelled = isCancelled,
            onProgress = onProgress,
        )
        return AcceleratedSeedSearchOutcome(
            result = fallback,
            backend = SeedSearchBackend.CPU_MULTI_CORE,
            elapsedMillis = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(1L),
        )
    }

    /**
     * Eligible for GPU filtering when all 9 stats are known and none are capped below the max
     * possible single-level gain (supports 0%, 1..99%, and >=100% growths identically to SeedEngine).
     */
    private fun isGpuLevelEvent(event: ObservedSeedEvent): ObservedSeedEvent.Level? {
        val lv = event as? ObservedSeedEvent.Level ?: return null
        if (lv.knownMask != ObservedSeedEvent.Level.ALL_STATS_MASK) return null
        for (i in 0 until 9) {
            val g = lv.parameters.growth[i]
            val r = lv.parameters.room[i]
            val e = lv.gains[i]
            if (g < 0 || e < 0) return null
            val maxPossibleGain = (g / 100) + if (g % 100 > 0) 1 else 0
            if (g > 0 && r < maxOf(maxPossibleGain, 1)) return null
        }
        return lv
    }

    private fun canAccelerateOnGpu(events: List<ObservedSeedEvent>): Boolean {
        if (events.isEmpty()) return false
        return isGpuLevelEvent(events[0]) != null
    }

    private fun probeAndSelfTest(): Boolean {
        // Test standard 1..99% growth AND mixed 0% / 100% growth on real hardware!
        val stdParams = LevelParameters(
            growth = listOf(45, 35, 25, 50, 40, 30, 20, 15, 35),
            room = List(9) { 32767 },
            guarantee = 2,
        )
        val edgeParams = LevelParameters(
            growth = listOf(100, 75, 0, 30, 20, 35, 55, 20, 25),
            room = List(9) { 32767 },
            guarantee = 2,
        )
        val target = 2_026L
        val s1 = SeedEngine.simulateLevel(target, stdParams)
        val s2 = SeedEngine.simulateLevel(s1.nextSeed, edgeParams)
        val events = listOf(
            ObservedSeedEvent.Level(stdParams, s1.gains),
            ObservedSeedEvent.Level(edgeParams, s2.gains),
        )
        val expected = SeedEngine.searchRange(events, 0L, 4_096L)
        val actual = searchOnGpu(events, 0L, 4_096L, SeedEngine.DEFAULT_MAX_TRACKED_CANDIDATES, { false }, {})
        val ok = actual != null &&
            actual.status == expected.status &&
            actual.matches == expected.matches &&
            actual.candidates == expected.candidates
        lastProbeDiagnostics = if (ok) "ok" else "mismatch: actual=${actual?.status}/${actual?.matches}/${actual?.candidates} expected=${expected.status}/${expected.matches}/${expected.candidates}"
        selfTestVerified.set(ok)
        return ok
    }

    private class GpuSession : AutoCloseable {
        var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var context: EGLContext = EGL14.EGL_NO_CONTEXT
        var surface: EGLSurface = EGL14.EGL_NO_SURFACE
        var program: Int = 0
        var ssbo: Int = 0

        init {
            try {
                display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                require(display != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
                val version = IntArray(2)
                require(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }

                val configAttribs = intArrayOf(
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
                    EGL14.EGL_NONE,
                )
                val configs = arrayOfNulls<EGLConfig>(1)
                val numConfigs = IntArray(1)
                require(EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0) && numConfigs[0] > 0) {
                    "No ES3 EGL config"
                }
                val config = requireNotNull(configs[0])

                val es31Attribs = intArrayOf(
                    EGL_CONTEXT_MAJOR_VERSION_KHR, 3,
                    EGL_CONTEXT_MINOR_VERSION_KHR, 1,
                    EGL14.EGL_NONE,
                )
                val es3FallbackAttribs = intArrayOf(
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
                    EGL14.EGL_NONE,
                )
                val createdContext = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, es31Attribs, 0)
                    .takeIf { it != EGL14.EGL_NO_CONTEXT }
                    ?: EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, es3FallbackAttribs, 0)
                context = createdContext
                require(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

                val surfaceAttribs = intArrayOf(
                    EGL14.EGL_WIDTH, 1,
                    EGL14.EGL_HEIGHT, 1,
                    EGL14.EGL_NONE,
                )
                surface = EGL14.eglCreatePbufferSurface(display, config, surfaceAttribs, 0)
                require(surface != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed" }
                require(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed" }

                val maj = IntArray(1)
                val min = IntArray(1)
                GLES31.glGetIntegerv(GLES31.GL_MAJOR_VERSION, maj, 0)
                GLES31.glGetIntegerv(GLES31.GL_MINOR_VERSION, min, 0)
                require(maj[0] > 3 || (maj[0] == 3 && min[0] >= 1)) {
                    "GLES ${maj[0]}.${min[0]} does not support compute shaders"
                }

                val shader = GLES31.glCreateShader(GLES31.GL_COMPUTE_SHADER)
                GLES31.glShaderSource(shader, COMPUTE_SHADER_SRC)
                GLES31.glCompileShader(shader)
                val compiled = IntArray(1)
                GLES31.glGetShaderiv(shader, GLES31.GL_COMPILE_STATUS, compiled, 0)
                if (compiled[0] == 0) {
                    val log = GLES31.glGetShaderInfoLog(shader)
                    GLES31.glDeleteShader(shader)
                    throw IllegalStateException("Compute shader compile error: $log")
                }

                program = GLES31.glCreateProgram()
                GLES31.glAttachShader(program, shader)
                GLES31.glLinkProgram(program)
                GLES31.glDeleteShader(shader)
                val linked = IntArray(1)
                GLES31.glGetProgramiv(program, GLES31.GL_LINK_STATUS, linked, 0)
                if (linked[0] == 0) {
                    val log = GLES31.glGetProgramInfoLog(program)
                    throw IllegalStateException("Compute program link error: $log")
                }

                val buffers = IntArray(1)
                GLES31.glGenBuffers(1, buffers, 0)
                ssbo = buffers[0]
                GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, ssbo)
                val byteSize = (HEADER_UINTS + MAX_GPU_OUT) * 4
                GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, byteSize, null, GLES31.GL_DYNAMIC_READ)
                GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, ssbo)
            } catch (t: Throwable) {
                close()
                throw t
            }
        }

        override fun close() {
            runCatching {
                if (ssbo != 0) GLES31.glDeleteBuffers(1, intArrayOf(ssbo), 0)
                if (program != 0) GLES31.glDeleteProgram(program)
            }
            runCatching {
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                    EGL14.eglTerminate(display)
                }
            }
            ssbo = 0; program = 0; surface = EGL14.EGL_NO_SURFACE; context = EGL14.EGL_NO_CONTEXT; display = EGL14.EGL_NO_DISPLAY
        }
    }

    private fun searchOnGpu(
        events: List<ObservedSeedEvent>,
        beginInclusive: Long,
        endExclusive: Long,
        maxCandidates: Int,
        isCancelled: () -> Boolean,
        onProgress: (SeedSearchProgress) -> Unit,
    ): SeedSearchResult? {
        // Quick impossibility check via SeedEngine on 0-length window
        val impossibleCheck = SeedEngine.searchRange(events, beginInclusive, beginInclusive)
        if (impossibleCheck.status == SeedSearchStatus.IMPOSSIBLE) {
            return impossibleCheck.copy(totalSeeds = endExclusive - beginInclusive)
        }

        val ev0 = isGpuLevelEvent(events[0]) ?: return null
        val ev1 = events.getOrNull(1)?.takeIf { it.advanceBefore == 0 }?.let(::isGpuLevelEvent)
        val ev2 = if (ev1 != null) events.getOrNull(2)?.takeIf { it.advanceBefore == 0 }?.let(::isGpuLevelEvent) else null

        val groupsYMax = if (ev1 != null) GROUPS_Y_MULTI else GROUPS_Y_SINGLE
        val chunkSize = GROUPS_X.toLong() * groupsYMax.toLong() * LOCAL_SIZE.toLong()

        val totalSeeds = endExclusive - beginInclusive
        val prefixMatches = LongArray(events.size)
        val candidates = linkedMapOf<Long, MutableList<Long>>()
        var processedSeeds = 0L
        var matches = 0L
        var candidateLimitReached = false

        GpuSession().use { session ->
            GLES31.glUseProgram(session.program)

            fun uniformArrayLoc(name: String): Int =
                GLES31.glGetUniformLocation(session.program, name).takeIf { it >= 0 }
                    ?: GLES31.glGetUniformLocation(session.program, "$name[0]")

            fun bindLevelUniforms(prefix: String, lv: ObservedSeedEvent.Level) {
                val base = IntArray(9) { lv.parameters.growth[it] / 100 }
                val thresh = IntArray(9) { (lv.parameters.growth[it] % 100) * 10_000 }
                val exp = IntArray(9) { lv.gains[it] }
                val eligibleCount = lv.parameters.growth.count { it > 0 }
                val guarantee = minOf(lv.parameters.guarantee, eligibleCount)
                val target = maxOf(guarantee, 1)
                val isSimple = lv.parameters.growth.all { it in 1..99 }
                GLES31.glUniform1uiv(uniformArrayLoc("uB$prefix"), 9, base, 0)
                GLES31.glUniform1uiv(uniformArrayLoc("uT$prefix"), 9, thresh, 0)
                GLES31.glUniform1uiv(uniformArrayLoc("uE$prefix"), 9, exp, 0)
                GLES31.glUniform1ui(GLES31.glGetUniformLocation(session.program, "uTarget$prefix"), target)
                GLES31.glUniform1ui(GLES31.glGetUniformLocation(session.program, "uSimple$prefix"), if (isSimple) 1 else 0)
            }

            bindLevelUniforms("0", ev0)
            GLES31.glUniform1ui(GLES31.glGetUniformLocation(session.program, "uHasEv1"), if (ev1 != null) 1 else 0)
            if (ev1 != null) bindLevelUniforms("1", ev1)
            GLES31.glUniform1ui(GLES31.glGetUniformLocation(session.program, "uHasEv2"), if (ev2 != null) 1 else 0)
            if (ev2 != null) bindLevelUniforms("2", ev2)
            GLES31.glUniform1ui(GLES31.glGetUniformLocation(session.program, "uMaxOut"), MAX_GPU_OUT)

            val locBase = GLES31.glGetUniformLocation(session.program, "uBaseSeed")
            val locCount = GLES31.glGetUniformLocation(session.program, "uCount")
            val zeroHeader = ByteBuffer.allocateDirect(HEADER_UINTS * 4).order(ByteOrder.nativeOrder())
                .apply { repeat(HEADER_UINTS) { putInt(0) }; flip() }

            var cursor = beginInclusive
            while (cursor < endExclusive) {
                if (isCancelled()) break
                val chunkEnd = minOf(cursor + chunkSize, endExclusive)
                val chunkLen = (chunkEnd - cursor).toInt()
                val totalGroups = (chunkLen + LOCAL_SIZE - 1) / LOCAL_SIZE
                val groupsX = minOf(totalGroups, GROUPS_X)
                val groupsY = (totalGroups + groupsX - 1) / groupsX

                // Reset SSBO header
                GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, session.ssbo)
                zeroHeader.rewind()
                GLES31.glBufferSubData(GLES31.GL_SHADER_STORAGE_BUFFER, 0, HEADER_UINTS * 4, zeroHeader)

                GLES31.glUniform1ui(locBase, cursor.toInt())
                GLES31.glUniform1ui(locCount, chunkLen)
                GLES31.glDispatchCompute(groupsX, groupsY, 1)
                GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT or GLES31.GL_BUFFER_UPDATE_BARRIER_BIT)
                GLES31.glFinish()

                // Read header first to know how many candidates survived
                val mappedHeader = GLES31.glMapBufferRange(
                    GLES31.GL_SHADER_STORAGE_BUFFER,
                    0,
                    HEADER_UINTS * 4,
                    GLES31.GL_MAP_READ_BIT,
                ) as? ByteBuffer ?: return null
                mappedHeader.order(ByteOrder.nativeOrder())
                val outCount = mappedHeader.getInt(0)
                val overflow = mappedHeader.getInt(4)
                val gpuPass0 = mappedHeader.getInt(8).toLong() and 0xffff_ffffL
                val gpuPass1 = mappedHeader.getInt(12).toLong() and 0xffff_ffffL
                GLES31.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)

                if (overflow != 0 || outCount < 0 || outCount > MAX_GPU_OUT) {
                    val cpuChunk = SeedEngine.searchRangeParallel(events, cursor, chunkEnd, maxCandidates, isCancelled = isCancelled)
                    processedSeeds += cpuChunk.processedSeeds
                    matches += cpuChunk.matches
                    candidateLimitReached = candidateLimitReached || cpuChunk.candidateLimitReached
                    cpuChunk.prefixMatches.forEachIndexed { i, c -> prefixMatches[i] += c }
                    for (cand in cpuChunk.candidates) {
                        val existing = candidates[cand.currentSeed]
                        if (existing != null) existing.addAll(cand.originSeeds)
                        else if (candidates.size < maxCandidates) candidates[cand.currentSeed] = cand.originSeeds.toMutableList()
                        else candidateLimitReached = true
                    }
                    onProgress(SeedSearchProgress(processedSeeds, totalSeeds))
                    cursor = chunkEnd
                    continue
                }

                prefixMatches[0] += gpuPass0
                if (ev1 != null && prefixMatches.size > 1) prefixMatches[1] += gpuPass1

                if (outCount > 0) {
                    val mappedOrigins = GLES31.glMapBufferRange(
                        GLES31.GL_SHADER_STORAGE_BUFFER,
                        HEADER_UINTS * 4,
                        outCount * 4,
                        GLES31.GL_MAP_READ_BIT,
                    ) as? ByteBuffer ?: return null
                    mappedOrigins.order(ByteOrder.nativeOrder())
                    val surviving = LongArray(outCount) { idx ->
                        mappedOrigins.getInt(idx * 4).toLong() and 0xffff_ffffL
                    }
                    GLES31.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
                    surviving.sort()

                    for (origin in surviving) {
                        var currentSeed = origin
                        var matchedAll = true
                        for (ei in events.indices) {
                            val res = if (ei == 0) {
                                when (val firstEv = events[0]) {
                                    is ObservedSeedEvent.Level -> SeedEngine.matchEvent(currentSeed, firstEv.copy(advanceBefore = 0))
                                    is ObservedSeedEvent.Exam -> SeedEngine.matchEvent(currentSeed, firstEv.copy(advanceBefore = 0))
                                    is ObservedSeedEvent.Advance -> SeedEngine.matchEvent(currentSeed, firstEv.copy(advanceBefore = 0))
                                    is ObservedSeedEvent.Anchor -> SeedEngine.matchEvent(currentSeed, firstEv.copy(advanceBefore = 0))
                                }
                            } else {
                                SeedEngine.matchEvent(currentSeed, events[ei])
                            }
                            if (res is EventMatchResult.Matched) {
                                currentSeed = res.nextSeed
                                if (ei >= 2 || (ei == 1 && ev1 == null)) {
                                    prefixMatches[ei]++
                                }
                            } else {
                                matchedAll = false
                                break
                            }
                        }
                        if (matchedAll) {
                            matches++
                            val existing = candidates[currentSeed]
                            if (existing != null) {
                                existing.add(origin)
                            } else if (candidates.size < maxCandidates) {
                                candidates[currentSeed] = mutableListOf(origin)
                            } else {
                                candidateLimitReached = true
                            }
                        }
                    }
                }

                processedSeeds += chunkLen
                onProgress(SeedSearchProgress(processedSeeds, totalSeeds))
                cursor = chunkEnd
            }
        }

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
            candidates = candidates.map { (cur, origins) -> SeedSearchCandidate(cur, origins) },
        )
    }

    private const val COMPUTE_SHADER_SRC = """#version 310 es
precision highp int;
layout(local_size_x = 128, local_size_y = 1, local_size_z = 1) in;

const uint MUL = 1812433253u;
const uint MASK = 0x7fffffffu;
const uint MILLION = 1000000u;
const uint BARRETT_MAGIC = 0x431BDE83u;

shared uint sPass0;
shared uint sPass1;

layout(std430, binding = 0) buffer OutputBuffer {
    uint outCount;
    uint overflow;
    uint pass0;
    uint pass1;
    uint origins[];
};

uniform uint uBaseSeed;
uniform uint uCount;
uniform uint uMaxOut;

uniform uint uB0[9];
uniform uint uT0[9];
uniform uint uE0[9];
uniform uint uTarget0;
uniform uint uSimple0;

uniform uint uHasEv1;
uniform uint uB1[9];
uniform uint uT1[9];
uniform uint uE1[9];
uniform uint uTarget1;
uniform uint uSimple1;

uniform uint uHasEv2;
uniform uint uB2[9];
uniform uint uT2[9];
uniform uint uE2[9];
uniform uint uTarget2;
uniform uint uSimple2;

uint nextDraw(inout uint x, inout uint y, inout uint z, inout uint w) {
    uint t = x ^ (x << 11u);
    x = y;
    y = z;
    z = w;
    w = w ^ (w >> 19u) ^ t ^ (t >> 8u);
    return w & MASK;
}

// Exact Barrett modulo 1,000,000 for all 31-bit unsigned draws [0, 0x7fffffff]: zero division!
uint modMillion(uint v) {
    uint hi;
    uint lo;
    umulExtended(v, BARRETT_MAGIC, hi, lo);
    return v - (hi >> 18u) * MILLION;
}

int evalLevelSimple(uint seed, uint thresh[9], uint expGains[9], uint target, out uint firstDraw) {
    uint x = MUL * (seed ^ (seed >> 30u));
    uint y = MUL * (x ^ (x >> 30u)) + 1u;
    uint z = MUL * (y ^ (y >> 30u)) + 2u;
    uint w = MUL * (z ^ (z >> 30u)) + 3u;

    firstDraw = nextDraw(x, y, z, w);
    uint b0 = (modMillion(firstDraw) < thresh[0]) ? 1u : 0u;
    uint cnt = b0;
    uint diff = b0 ^ expGains[0];
    if (cnt >= target && diff != 0u) return 0;

    uint r1 = nextDraw(x, y, z, w);
    uint b1 = (modMillion(r1) < thresh[1]) ? 1u : 0u;
    cnt += b1;
    diff |= (b1 ^ expGains[1]);
    if (cnt >= target && diff != 0u) return 0;

    uint r2 = nextDraw(x, y, z, w);
    uint b2 = (modMillion(r2) < thresh[2]) ? 1u : 0u;
    cnt += b2;
    diff |= (b2 ^ expGains[2]);
    if (cnt >= target && diff != 0u) return 0;

    uint r3 = nextDraw(x, y, z, w);
    uint b3 = (modMillion(r3) < thresh[3]) ? 1u : 0u;
    cnt += b3;
    diff |= (b3 ^ expGains[3]);
    if (cnt >= target && diff != 0u) return 0;

    for (int k = 4; k < 9; k++) {
        uint rk = nextDraw(x, y, z, w);
        uint bk = (modMillion(rk) < thresh[k]) ? 1u : 0u;
        cnt += bk;
        diff |= (bk ^ expGains[k]);
        if (cnt >= target && diff != 0u) return 0;
    }
    if (cnt >= target) {
        return (diff == 0u) ? 1 : 0;
    }

    for (int round = 2; round <= 3; round++) {
        uint rcnt = 0u;
        uint rdiff = 0u;
        for (int k = 0; k < 9; k++) {
            uint rk = nextDraw(x, y, z, w);
            uint bk = (modMillion(rk) < thresh[k]) ? 1u : 0u;
            rcnt += bk;
            rdiff |= (bk ^ expGains[k]);
        }
        if (rcnt >= target) {
            return (rdiff == 0u) ? 1 : 0;
        }
    }
    return 2;
}

int evalLevelGeneral(uint seed, uint baseGains[9], uint thresh[9], uint expGains[9], uint target, out uint firstDraw) {
    uint x = MUL * (seed ^ (seed >> 30u));
    uint y = MUL * (x ^ (x >> 30u)) + 1u;
    uint z = MUL * (y ^ (y >> 30u)) + 2u;
    uint w = MUL * (z ^ (z >> 30u)) + 3u;

    uint firstVal = nextDraw(x, y, z, w);
    firstDraw = firstVal;
    bool usedFirst = false;

    for (int round = 1; round <= 3; round++) {
        uint rcnt = 0u;
        uint rdiff = 0u;
        for (int k = 0; k < 9; k++) {
            uint gk = baseGains[k];
            if (thresh[k] > 0u) {
                uint rv;
                if (!usedFirst) {
                    rv = firstVal;
                    usedFirst = true;
                } else {
                    rv = nextDraw(x, y, z, w);
                }
                if (modMillion(rv) < thresh[k]) {
                    gk += 1u;
                }
            }
            if (gk > 0u) rcnt += 1u;
            rdiff |= (gk ^ expGains[k]);
        }
        if (rcnt >= target) {
            return (rdiff == 0u) ? 1 : 0;
        }
    }
    return 2;
}

int evalLevel(uint seed, uint baseGains[9], uint thresh[9], uint expGains[9], uint target, uint simple, out uint firstDraw) {
    if (simple != 0u) {
        return evalLevelSimple(seed, thresh, expGains, target, firstDraw);
    }
    return evalLevelGeneral(seed, baseGains, thresh, expGains, target, firstDraw);
}

void main() {
    if (gl_LocalInvocationIndex == 0u) {
        sPass0 = 0u;
        sPass1 = 0u;
    }
    barrier();

    uint gid = gl_GlobalInvocationID.y * (gl_NumWorkGroups.x * 128u) + gl_GlobalInvocationID.x;
    if (gid < uCount) {
        uint origin = uBaseSeed + gid;
        uint next0 = 0u;
        int r0 = evalLevel(origin, uB0, uT0, uE0, uTarget0, uSimple0, next0);
        if (r0 != 0) {
            if (r0 == 1) {
                atomicAdd(sPass0, 1u);
            }
            bool emit = true;
            if (r0 == 1 && uHasEv1 != 0u) {
                uint next1 = 0u;
                int r1 = evalLevel(next0, uB1, uT1, uE1, uTarget1, uSimple1, next1);
                if (r1 == 0) {
                    emit = false;
                } else {
                    if (r1 == 1) {
                        atomicAdd(sPass1, 1u);
                    }
                    if (r1 == 1 && uHasEv2 != 0u) {
                        uint next2 = 0u;
                        int r2 = evalLevel(next1, uB2, uT2, uE2, uTarget2, uSimple2, next2);
                        if (r2 == 0) emit = false;
                    }
                }
            }
            if (emit) {
                uint idx = atomicAdd(outCount, 1u);
                if (idx < uMaxOut) {
                    origins[idx] = origin;
                } else {
                    atomicExchange(overflow, 1u);
                }
            }
        }
    }

    barrier();
    if (gl_LocalInvocationIndex == 0u) {
        if (sPass0 != 0u) atomicAdd(pass0, sPass0);
        if (sPass1 != 0u) atomicAdd(pass1, sPass1);
    }
}
"""
}
