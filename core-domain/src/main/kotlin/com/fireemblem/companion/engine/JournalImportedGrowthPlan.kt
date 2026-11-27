package com.fireemblem.companion.engine

import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

enum class ImportedGrowthActionKind(val id: String) {
    UPGRADE("level"),
    EXAM("exam"),
}

data class JournalImportedGrowthAction(
    val jobId: String,
    val profession: String,
    val mountId: String,
    val kind: ImportedGrowthActionKind,
    val exams: Int,
)

data class JournalImportedGrowthConfirmation(
    val actionIndex: Int,
    val observationIds: List<String>,
    val seedAfter: Long?,
    val statsAfter: List<Int>,
    val levelAfter: Int,
    val professionAfter: String,
    val unlockedProfessionsAfter: Set<String>,
)

data class JournalImportedGrowthPlan(
    val sourceSha256: String,
    val entryIndex: Int,
    val routeIndex: Int,
    val entryId: String,
    val chapter: String,
    val round: Int,
    val routeName: String,
    val initialSeed: Long,
    val initialLevel: Int,
    val initialProfessionId: String,
    val initialProfession: String,
    val part3: Boolean,
    val initialStats: List<Int>,
    val caps: List<Int?>,
    val initialUnlockedProfessions: Set<String>,
    val actions: List<JournalImportedGrowthAction>,
    val nextStepIndex: Int = 0,
    val confirmations: List<JournalImportedGrowthConfirmation> = emptyList(),
    val historyDigest: String,
    val ruleRevision: String = BuddyRules.REVISION,
) {
    fun validated(): JournalImportedGrowthPlan {
        require(SHA256_PATTERN.matches(sourceSha256) && SHA256_PATTERN.matches(historyDigest)) {
            "来源方案摘要无效"
        }
        require(entryIndex >= 0 && routeIndex >= 0 && entryId.isNotBlank() && chapter.isNotBlank() &&
            round >= 0 && routeName.isNotBlank()) { "来源方案身份信息无效" }
        require(initialSeed in 0 until SeedEngine.UINT32_LIMIT && initialLevel in 1..98 &&
            initialProfessionId.isNotBlank() && initialProfession.isNotBlank()) { "来源方案起点无效" }
        require(initialStats.size == GrowthStat.entries.size && initialStats.all { it in 0..32767 } &&
            caps.size == GrowthStat.entries.size && caps.indices.all { caps[it] == null || caps[it]!! in initialStats[it]..32767 }) {
            "来源方案属性或上限无效"
        }
        require(initialUnlockedProfessions.all(String::isNotBlank) && initialProfession in initialUnlockedProfessions) {
            "请确认起始职业已解锁"
        }
        require(ruleRevision.isNotBlank() && actions.isNotEmpty() && actions.size <= 1000)
        require(actions.count { it.kind == ImportedGrowthActionKind.UPGRADE } <= 99 - initialLevel) {
            "来源方案升级步骤超过 99 级"
        }
        actions.forEach { action ->
            require(action.jobId.isNotBlank() && action.profession.isNotBlank() && action.mountId.isEmpty() && action.exams == 0) {
                "来源方案包含尚未支持的坐骑或考试次数"
            }
        }
        require(nextStepIndex in 0..actions.size && confirmations.size == nextStepIndex) { "来源方案执行位置无效" }
        var expectedLevel = initialLevel
        var expectedProfession = initialProfession
        val allObservationIds = mutableSetOf<String>()
        confirmations.forEachIndexed { index, confirmation ->
            val action = actions[index]
            require(confirmation.actionIndex == index && confirmation.observationIds.isNotEmpty()) { "来源方案确认顺序无效" }
            require(confirmation.observationIds.all { id ->
                runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false) && allObservationIds.add(id)
            }) { "来源方案实测记录编号无效" }
            if (action.kind == ImportedGrowthActionKind.UPGRADE) {
                expectedLevel++
                require(confirmation.observationIds.size in 1..2) { "升级步骤记录数量无效" }
            } else {
                require(confirmation.observationIds.size == 1) { "考试步骤记录数量无效" }
                require(confirmation.professionAfter == expectedProfession) { "考试步骤不能改变当前职业" }
            }
            require(confirmation.professionAfter.isNotBlank()) { "来源方案实测职业无效" }
            if (action.kind == ImportedGrowthActionKind.UPGRADE) expectedProfession = confirmation.professionAfter
            require(confirmation.seedAfter == null || confirmation.seedAfter in 0 until SeedEngine.UINT32_LIMIT)
            require(confirmation.statsAfter.size == GrowthStat.entries.size && confirmation.statsAfter.all { it in 0..32767 } &&
                confirmation.levelAfter == expectedLevel && confirmation.professionAfter == expectedProfession &&
                confirmation.unlockedProfessionsAfter.all(String::isNotBlank)) { "来源方案实测状态无效" }
        }
        return this
    }

    fun currentSeed(): Long? = if (confirmations.isEmpty()) initialSeed else confirmations.last().seedAfter
    fun currentStats(): List<Int> = confirmations.lastOrNull()?.statsAfter ?: initialStats
    fun currentLevel(): Int = confirmations.lastOrNull()?.levelAfter ?: initialLevel
    fun currentProfession(): String = confirmations.lastOrNull()?.professionAfter ?: initialProfession
    fun currentUnlockedProfessions(): Set<String> = confirmations.lastOrNull()?.unlockedProfessionsAfter
        ?: initialUnlockedProfessions

    fun matches(journal: LevelJournal): Boolean = historyDigest == journal.historyDigest() &&
        confirmations.flatMap(JournalImportedGrowthConfirmation::observationIds) == journal.observations.map(LevelObservation::id)

    fun encode(): JsonObject = buildJsonObject {
        validated()
        put("sourceSha256", sourceSha256); put("entryIndex", entryIndex); put("routeIndex", routeIndex)
        put("entryId", entryId); put("chapter", chapter); put("round", round); put("routeName", routeName)
        put("initialSeed", initialSeed); put("initialLevel", initialLevel); put("initialProfessionId", initialProfessionId)
        put("initialProfession", initialProfession); put("part3", part3)
        put("initialStats", JsonArray(initialStats.map(::JsonPrimitive)))
        put("caps", JsonArray(caps.map { it?.let(::JsonPrimitive) ?: JsonNull }))
        put("initialUnlockedProfessions", JsonArray(initialUnlockedProfessions.sorted().map(::JsonPrimitive)))
        put("actions", JsonArray(actions.map { action -> buildJsonObject {
            put("jobId", action.jobId); put("profession", action.profession); put("mountId", action.mountId)
            put("kind", action.kind.id); put("exams", action.exams)
        } }))
        put("nextStepIndex", nextStepIndex)
        put("confirmations", JsonArray(confirmations.map { confirmation -> buildJsonObject {
            put("actionIndex", confirmation.actionIndex)
            put("observationIds", JsonArray(confirmation.observationIds.map(::JsonPrimitive)))
            put("seedAfter", confirmation.seedAfter?.let(::JsonPrimitive) ?: JsonNull)
            put("statsAfter", JsonArray(confirmation.statsAfter.map(::JsonPrimitive)))
            put("levelAfter", confirmation.levelAfter); put("professionAfter", confirmation.professionAfter)
            put("unlockedProfessionsAfter", JsonArray(confirmation.unlockedProfessionsAfter.sorted().map(::JsonPrimitive)))
        } }))
        put("historyDigest", historyDigest); put("ruleRevision", ruleRevision)
    }

    companion object {
        private val SHA256_PATTERN = Regex("[0-9a-f]{64}")

        fun decode(value: JsonObject): JournalImportedGrowthPlan = JournalImportedGrowthPlan(
            sourceSha256 = value.getValue("sourceSha256").jsonPrimitive.content,
            entryIndex = value.getValue("entryIndex").jsonPrimitive.int,
            routeIndex = value.getValue("routeIndex").jsonPrimitive.int,
            entryId = value.getValue("entryId").jsonPrimitive.content,
            chapter = value.getValue("chapter").jsonPrimitive.content,
            round = value.getValue("round").jsonPrimitive.int,
            routeName = value.getValue("routeName").jsonPrimitive.content,
            initialSeed = value.getValue("initialSeed").jsonPrimitive.long,
            initialLevel = value.getValue("initialLevel").jsonPrimitive.int,
            initialProfessionId = value.getValue("initialProfessionId").jsonPrimitive.content,
            initialProfession = value.getValue("initialProfession").jsonPrimitive.content,
            part3 = value.getValue("part3").jsonPrimitive.content.toBooleanStrict(),
            initialStats = value.getValue("initialStats").jsonArray.map { it.jsonPrimitive.int },
            caps = value.getValue("caps").jsonArray.map { it.takeUnless { cap -> cap is JsonNull }?.jsonPrimitive?.int },
            initialUnlockedProfessions = value.getValue("initialUnlockedProfessions").jsonArray.map { it.jsonPrimitive.content }.toSet(),
            actions = value.getValue("actions").jsonArray.map { element ->
                val item = element.jsonObject
                JournalImportedGrowthAction(item.getValue("jobId").jsonPrimitive.content,
                    item.getValue("profession").jsonPrimitive.content, item.getValue("mountId").jsonPrimitive.content,
                    requireNotNull(ImportedGrowthActionKind.entries.find { it.id == item.getValue("kind").jsonPrimitive.content }),
                    item.getValue("exams").jsonPrimitive.int)
            },
            nextStepIndex = value.getValue("nextStepIndex").jsonPrimitive.int,
            confirmations = value.getValue("confirmations").jsonArray.map { element ->
                val item = element.jsonObject
                JournalImportedGrowthConfirmation(item.getValue("actionIndex").jsonPrimitive.int,
                    item.getValue("observationIds").jsonArray.map { it.jsonPrimitive.content },
                    item["seedAfter"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.long,
                    item.getValue("statsAfter").jsonArray.map { it.jsonPrimitive.int },
                    item.getValue("levelAfter").jsonPrimitive.int,
                    item.getValue("professionAfter").jsonPrimitive.content,
                    item.getValue("unlockedProfessionsAfter").jsonArray.map { it.jsonPrimitive.content }.toSet())
            },
            historyDigest = value.getValue("historyDigest").jsonPrimitive.content,
            ruleRevision = value.getValue("ruleRevision").jsonPrimitive.content,
        ).validated()
    }
}
