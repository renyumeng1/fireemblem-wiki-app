package com.fireemblem.companion.engine

import com.fireemblem.companion.content.GrowthSimCharacter
import com.fireemblem.companion.content.GrowthSimClass
import com.fireemblem.companion.content.GrowthSimMount
import com.fireemblem.companion.content.GrowthSimulatorCatalog
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

enum class GrowthSimulationMode(val id: String, val label: String, val note: String) {
    FIXED(
        "fixed",
        "固定成长",
        "使用初始累积值 (3 × 个人成长)%100 与确定性保底补点（触发清零）。",
    ),
    RANDOM_EXPECTED(
        "random",
        "随机期望",
        "按随机成长 + 最多三次重抽 + 补点规则进行 200 组确定性样本统计均值与 P10–P90 区间。",
    ),
    LINEAR_EXPECTED(
        "linear",
        "线性期望",
        "仅按纯成长率百分比累加期望（不含重抽、保底与取整累积效应）。",
    );

    companion object {
        fun fromId(id: String?): GrowthSimulationMode = entries.firstOrNull { it.id == id } ?: FIXED
    }
}

data class GrowthPlanStage(
    val classId: String,
    val mountId: String? = null,
    val levels: Int,
) {
    init {
        require(classId.isNotBlank()) { "请选择阶段兵种" }
        require(levels in 1..98) { "阶段升级次数应为 1 至 98" }
    }
}

data class GrowthSimulatorPlan(
    val profileId: String,
    val name: String,
    val characterId: String,
    val startLevel: Int,
    val part3: Boolean,
    val stages: List<GrowthPlanStage>,
) {
    init {
        require(profileId.isNotBlank() && profileId.length <= 128) { "请选择个人存档" }
        require(name.trim().isNotEmpty() && name.trim().length <= 80) { "规划名称应为 1 至 80 字" }
        require(characterId.isNotBlank()) { "请选择角色" }
        require(startLevel in 1..98) { "起始等级应为 1 至 98" }
        require(stages.isNotEmpty() && stages.size <= 16) { "阶段数应为 1 至 16" }
    }

    fun payload(): JsonObject = buildJsonObject {
        put("recordType", RECORD_TYPE)
        put("version", VERSION)
        put("profileId", profileId)
        put("name", name.trim())
        put("characterId", characterId)
        put("startLevel", startLevel)
        put("part3", part3)
        put("stages", JsonArray(stages.map { stage ->
            buildJsonObject {
                put("classId", stage.classId)
                stage.mountId?.let { put("mountId", it) }
                put("levels", stage.levels)
            }
        }))
    }

    fun encodeExport(): ByteArray {
        val bytes = buildJsonObject {
            put("format", EXPORT_FORMAT)
            put("version", VERSION)
            put("name", name.trim())
            put("characterId", characterId)
            put("startLevel", startLevel)
            put("part3", part3)
            put("stages", JsonArray(stages.map { stage ->
                buildJsonObject {
                    put("classId", stage.classId)
                    stage.mountId?.let { put("mountId", it) }
                    put("levels", stage.levels)
                }
            }))
        }.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_EXPORT_BYTES) { "规划导出文件超过 512 KiB" }
        return bytes
    }

    companion object {
        const val KIND = "growth-plan"
        const val RECORD_TYPE = "multi-stage-growth-simulator-plan"
        const val EXPORT_FORMAT = "fireemblem-growth-simulator-plan"
        const val VERSION = 1
        const val MAX_EXPORT_BYTES = 512 * 1024

        fun entityId(profileId: String, slotName: String = "default"): String =
            UUID.nameUUIDFromBytes("growth-simulator-plan\u0000$profileId\u0000$slotName".toByteArray(Charsets.UTF_8))
                .toString()

        fun decode(payload: JsonObject): GrowthSimulatorPlan {
            require(payload["recordType"]?.jsonPrimitive?.content == RECORD_TYPE) { "不是成长模拟器规划记录" }
            val profileId = payload.getValue("profileId").jsonPrimitive.content
            val name = payload.getValue("name").jsonPrimitive.content
            val characterId = payload.getValue("characterId").jsonPrimitive.content
            val startLevel = payload.getValue("startLevel").jsonPrimitive.int
            val part3 = payload.getValue("part3").jsonPrimitive.boolean
            val stages = payload.getValue("stages").jsonArray.map { el ->
                val obj = el.jsonObject
                GrowthPlanStage(
                    classId = obj.getValue("classId").jsonPrimitive.content,
                    mountId = obj["mountId"]?.takeIf { it is JsonPrimitive && it.isString }?.jsonPrimitive?.content,
                    levels = obj.getValue("levels").jsonPrimitive.int,
                )
            }
            return GrowthSimulatorPlan(profileId, name, characterId, startLevel, part3, stages)
        }

        fun decodeExport(bytes: ByteArray, targetProfileId: String): GrowthSimulatorPlan {
            require(bytes.size <= MAX_EXPORT_BYTES) { "规划文件超过 512 KiB" }
            val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            require(root["format"]?.jsonPrimitive?.content == EXPORT_FORMAT) { "不是成长模拟器规划导出文件" }
            val name = root.getValue("name").jsonPrimitive.content
            val characterId = root.getValue("characterId").jsonPrimitive.content
            val startLevel = root.getValue("startLevel").jsonPrimitive.int
            val part3 = root.getValue("part3").jsonPrimitive.boolean
            val stages = root.getValue("stages").jsonArray.map { el ->
                val obj = el.jsonObject
                GrowthPlanStage(
                    classId = obj.getValue("classId").jsonPrimitive.content,
                    mountId = obj["mountId"]?.takeIf { it is JsonPrimitive && it.isString }?.jsonPrimitive?.content,
                    levels = obj.getValue("levels").jsonPrimitive.int,
                )
            }
            return GrowthSimulatorPlan(targetProfileId, name, characterId, startLevel, part3, stages)
        }
    }
}

data class GrowthStageSummary(
    val stageIndex: Int,
    val classId: String,
    val className: String,
    val mountName: String?,
    val levelFrom: Int,
    val levelTo: Int,
    val effectiveRates: List<Int>,
)

data class StatBand(
    val p10: Double,
    val median: Double,
    val p90: Double,
)

data class GrowthSimulationResult(
    val mode: GrowthSimulationMode,
    val characterName: String,
    val startStats: List<Int>,
    val finalStats: List<Double>,
    val gains: List<Double>,
    val stageSummaries: List<GrowthStageSummary>,
    val tieBreakStepsCount: Int = 0,
    val statBands: List<StatBand>? = null,
)

object GrowthSimulatorEngine {
    private const val RANDOM_SAMPLES = 200

    fun initialStatsFor(character: GrowthSimCharacter, firstClass: GrowthSimClass?): List<Int> =
        List(GrowthStat.entries.size) { index ->
            val personalBase = character.base[index]
            val classBase = firstClass?.base?.get(index) ?: 0
            maxOf(personalBase, classBase)
        }

    fun effectiveRates(
        character: GrowthSimCharacter,
        simClass: GrowthSimClass,
        mount: GrowthSimMount?,
    ): List<Int> = List(GrowthStat.entries.size) { index ->
        val charOrClassBase = character.growth[index].takeIf { it != 0 } ?: simClass.baseGrowth[index]
        val mountBonus = if (mount != null && simClass.mountType.isNotEmpty() && mount.type == simClass.mountType) {
            mount.growth[index] * simClass.mountMultiplier
        } else 0
        (charOrClassBase + simClass.growthBonus[index] + mountBonus).coerceAtLeast(0)
    }

    fun effectiveCaps(character: GrowthSimCharacter, simClass: GrowthSimClass): List<Int> =
        List(GrowthStat.entries.size) { index ->
            minOf(character.caps[index], simClass.caps[index]).coerceAtLeast(1)
        }

    fun simulate(
        catalog: GrowthSimulatorCatalog,
        plan: GrowthSimulatorPlan,
        mode: GrowthSimulationMode,
    ): GrowthSimulationResult {
        val character = catalog.characters.firstOrNull { it.nativeId == plan.characterId }
            ?: throw IllegalArgumentException("找不到角色：${plan.characterId}")
        val classesById = catalog.classes.associateBy(GrowthSimClass::id)
        val mountsById = catalog.mounts.associateBy(GrowthSimMount::id)

        val firstClass = classesById[plan.stages.first().classId]
            ?: throw IllegalArgumentException("找不到兵种：${plan.stages.first().classId}")
        val startStats = initialStatsFor(character, firstClass)
        val guarantee = if (plan.part3) character.guaranteePart3 else character.guaranteePre

        val stageSummaries = mutableListOf<GrowthStageSummary>()
        val perLevelRates = mutableListOf<List<Int>>()
        val perLevelCaps = mutableListOf<List<Int>>()

        var currentLevel = plan.startLevel
        plan.stages.forEachIndexed { idx, stage ->
            val cls = classesById[stage.classId] ?: throw IllegalArgumentException("找不到兵种：${stage.classId}")
            val mount = stage.mountId?.let(mountsById::get)
            val rates = effectiveRates(character, cls, mount)
            val caps = effectiveCaps(character, cls)
            val actualLevels = stage.levels.coerceAtMost((99 - currentLevel).coerceAtLeast(0))
            if (actualLevels > 0) {
                stageSummaries += GrowthStageSummary(
                    stageIndex = idx,
                    classId = cls.id,
                    className = cls.name,
                    mountName = mount?.name,
                    levelFrom = currentLevel,
                    levelTo = currentLevel + actualLevels,
                    effectiveRates = rates,
                )
                repeat(actualLevels) {
                    perLevelRates += rates
                    perLevelCaps += caps
                }
                currentLevel += actualLevels
            }
        }

        val count = GrowthStat.entries.size
        return when (mode) {
            GrowthSimulationMode.FIXED -> {
                val fixedSteps = FixedGrowthEngine.simulate(
                    startLevel = plan.startLevel,
                    personalGrowth = character.growth,
                    startStats = startStats,
                    ratesPerLevel = perLevelRates,
                    capsPerLevel = perLevelCaps,
                    guaranteePoints = guarantee,
                )
                val endStats = (fixedSteps.lastOrNull()?.stateAfter?.stats ?: startStats).map { it.toDouble() }
                GrowthSimulationResult(
                    mode = mode,
                    characterName = character.name,
                    startStats = startStats,
                    finalStats = endStats,
                    gains = List(count) { i -> endStats[i] - startStats[i] },
                    stageSummaries = stageSummaries,
                    tieBreakStepsCount = fixedSteps.count { it.hasTieBreakCaveat },
                )
            }

            GrowthSimulationMode.LINEAR_EXPECTED -> {
                val endStats = MutableList(count) { startStats[it].toDouble() }
                for (stepIdx in perLevelRates.indices) {
                    val rates = perLevelRates[stepIdx]
                    val caps = perLevelCaps[stepIdx]
                    for (i in 0 until count) {
                        endStats[i] = (endStats[i] + rates[i] / 100.0).coerceAtMost(caps[i].toDouble())
                    }
                }
                GrowthSimulationResult(
                    mode = mode,
                    characterName = character.name,
                    startStats = startStats,
                    finalStats = endStats,
                    gains = List(count) { i -> endStats[i] - startStats[i] },
                    stageSummaries = stageSummaries,
                )
            }

            GrowthSimulationMode.RANDOM_EXPECTED -> {
                val samplesByStat = Array(count) { DoubleArray(RANDOM_SAMPLES) }
                for (sample in 0 until RANDOM_SAMPLES) {
                    var seed = ((sample + 1).toLong() * 21_474_836L) and 0xffff_ffffL
                    val current = startStats.toMutableList()
                    for (stepIdx in perLevelRates.indices) {
                        val rates = perLevelRates[stepIdx]
                        val caps = perLevelCaps[stepIdx]
                        val room = List(count) { i -> (caps[i] - current[i]).coerceAtLeast(0) }
                        val res = SeedEngine.simulateLevel(seed, LevelParameters(rates, room, guarantee))
                        seed = res.nextSeed
                        for (i in 0 until count) {
                            current[i] = (current[i] + res.gains[i]).coerceAtMost(caps[i])
                        }
                    }
                    for (i in 0 until count) {
                        samplesByStat[i][sample] = current[i].toDouble()
                    }
                }
                val means = List(count) { i -> samplesByStat[i].average() }
                val bands = List(count) { i ->
                    val sorted = samplesByStat[i].sortedArray()
                    StatBand(
                        p10 = sorted[(RANDOM_SAMPLES * 0.10).roundToInt().coerceIn(0, RANDOM_SAMPLES - 1)],
                        median = sorted[RANDOM_SAMPLES / 2],
                        p90 = sorted[(RANDOM_SAMPLES * 0.90).roundToInt().coerceIn(0, RANDOM_SAMPLES - 1)],
                    )
                }
                GrowthSimulationResult(
                    mode = mode,
                    characterName = character.name,
                    startStats = startStats,
                    finalStats = means,
                    gains = List(count) { i -> means[i] - startStats[i] },
                    stageSummaries = stageSummaries,
                    statBands = bands,
                )
            }
        }
    }

    fun compare(baseline: GrowthSimulationResult, candidate: GrowthSimulationResult): List<Double> =
        List(GrowthStat.entries.size) { idx -> candidate.finalStats[idx] - baseline.finalStats[idx] }
}
