package com.fireemblem.companion.importer

import com.fireemblem.companion.engine.BuddyCampaignLine
import com.fireemblem.companion.engine.BuddyGrowthInput
import com.fireemblem.companion.engine.BuddyRoute
import com.fireemblem.companion.engine.BuddyRules
import com.fireemblem.companion.engine.LevelJournal
import com.fireemblem.companion.engine.LevelObservation
import com.fireemblem.companion.engine.JournalExam
import com.fireemblem.companion.engine.JournalSeedCheckpoint
import com.fireemblem.companion.engine.JournalSeedEvidence
import com.fireemblem.companion.engine.EventMatchResult
import com.fireemblem.companion.engine.levelsUp
import com.fireemblem.companion.engine.SeedEngine
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.json.*

data class FefwGrowthSeedImportPreview(
    val sourceSha256: String,
    val sourceArchive: FefwGrowthSeedSourceArchive,
    val entries: List<FefwGrowthSeedRecordPreview>,
)

data class FefwGrowthSeedRecordPreview(
    val index: Int,
    val characterId: String,
    val characterName: String,
    val profession: String,
    val part3: Boolean,
    val startLevel: Int,
    val currentLevel: Int?,
    val candidateSeed: Long?,
    val currentSeed: Long?,
    val fullSearch: Boolean?,
    val matchCount: Long?,
    val observations: List<LevelObservation>,
    val warnings: List<String>,
    val errors: List<String>,
    val candidateReplayVerified: Boolean,
    val currentStats: List<Int>?,
    val caps: List<Int?>,
) {
    val ready: Boolean get() = errors.isEmpty()

    fun toJournal(name: String, profile: String, line: BuddyCampaignLine): LevelJournal {
        require(ready) { "此记录尚不能安全导入：${errors.joinToString("；")}" }
        return LevelJournal(name, profile, line, characterId, observations).validated()
    }

    fun toJournal(name: String, profile: String, line: BuddyCampaignLine, sourceSha256: String, rules: BuddyRules): LevelJournal {
        val journal = toJournal(name, profile, line)
        val history = journal.history(rules)
        require(history.errors.isEmpty()) { "此记录与所选路线不一致：${history.errors.joinToString("；")}" }
        if (!candidateReplayVerified) return journal.copy(importSourceSha256 = sourceSha256,
            importSourceRecordIndex = index).validated()
        val origin = requireNotNull(candidateSeed) { "缺少已核对的起始种子" }
        val current = requireNotNull(currentSeed) { "缺少已核对的当前种子" }
        var replaySeed = origin
        history.events.forEachIndexed { index, event ->
            val match = SeedEngine.matchEvent(replaySeed, event)
            require(match is EventMatchResult.Matched) { "所选路线的第 ${index + 1} 条记录无法回放" }
            replaySeed = match.nextSeed
        }
        require(history.observedLevels > 0 && replaySeed == current) { "当前种子与所选路线回放结果不一致" }
        require(currentLevel == journal.currentLevel) { "当前等级与升级记录不一致" }
        val last = observations.last()
        val checkpoint = JournalSeedCheckpoint(seed = current, eventIndex = history.events.size,
            level = requireNotNull(currentLevel), profession = last.profession, part3 = last.part3,
            stats = null, caps = List(9) { null }, evidence = JournalSeedEvidence.IMPORTED_REPLAY,
            historyDigest = journal.historyDigest(), ruleRevision = journal.ruleRevision, sourceSha256 = sourceSha256,
            originSeed = origin, unlockedProfessions = journal.confirmedUnlockedProfessions()).validated()
        return journal.copy(seedCheckpoint = checkpoint, importSourceSha256 = sourceSha256,
            importSourceRecordIndex = index).validated()
    }
}

object FefwGrowthSeedImporter {
    const val MAX_BYTES = 10 * 1024 * 1024
    private const val MAX_RECORDS = 1000
    private const val MAX_OBSERVATIONS = 1000
    private const val MAX_DEPTH = 64
    private const val MAX_STRUCTURE_TOKENS = 1_000_000
    private val recordFields = setOf(
        "observations", "observationRecord", "passive", "currentStats", "caps", "pid", "job", "part",
        "mountId", "obsMounts", "minimum", "rates", "dead", "level", "batches", "candidate", "current",
        "currentLevel", "matchCount", "fullSearch", "routeType", "routes", "activeRoute", "prediction",
        "plan", "position", "positionSource", "recordPosition", "progress", "examChance", "savedAt",
        "historyId", "revisions",
    )

    fun inspect(bytes: ByteArray, rules: BuddyRules): FefwGrowthSeedImportPreview {
        require(bytes.size <= MAX_BYTES) { "成长种子文件不能超过 10 MiB" }
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (error: CharacterCodingException) {
            throw IllegalArgumentException("成长种子文件必须是有效的 UTF-8", error)
        }
        checkJsonBounds(text)
        val root = try {
            Json.parseToJsonElement(text) as? JsonObject
                ?: throw IllegalArgumentException("成长种子文件根节点必须是对象")
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("成长种子 JSON 格式不正确：${error.message}", error)
        }
        require(string(root["format"], "format") == "fefw-growth-seed") { "不是 fefw-growth-seed 文件" }
        require(integer(root["version"], "version") == 1L) { "仅支持 fefw-growth-seed v1" }
        val records = array(root["records"], "records")
        require(records.size <= MAX_RECORDS) { "文件最多包含 $MAX_RECORDS 组角色记录" }
        val sourceArchive = FefwGrowthSeedSourceArchive.fromBytes(bytes)
        return FefwGrowthSeedImportPreview(sourceArchive.sourceSha256, sourceArchive,
            records.mapIndexed { index, record -> inspectRecord(index, record, rules) })
    }

    private fun inspectRecord(index: Int, element: JsonElement, rules: BuddyRules): FefwGrowthSeedRecordPreview {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf("请在导入时选择实际游玩的路线；不会用来源的推演名称判断路线。")
        val record = capture(errors, JsonObject(emptyMap())) {
            element as? JsonObject ?: throw IllegalArgumentException("records[$index] 必须是对象")
        }
        val unknownFields = record.keys - recordFields
        if (unknownFields.isNotEmpty()) errors += "存在尚未核实的记录字段：${unknownFields.take(10).joinToString()}"
        val pid = capture(errors, "") { string(record["pid"], "pid") }
        val character = rules.characters.singleOrNull { it.personId == pid }
        if (character == null) errors += "pid=$pid 未找到唯一、已验证的角色映射"
        val part3 = capture(errors, false) {
            val part = record["part"] as? JsonPrimitive
            require(part != null && part.content in setOf("0", "1")) { "part 仅支持 0（前篇）或 1（第三部）" }
            part.content == "1"
        }
        if (character?.part3Only == true && !part3) errors += "此角色只在第三部可用"
        val jobId = capture(errors, "") { string(record["job"], "job") }
        fun professionFor(job: String): String = capture(errors, job) {
            require(character != null) { "无法在角色未确定时验证职业 $job" }
            rules.professionForJobId(job, character.id, part3)?.name
                ?: throw IllegalArgumentException("job=$job 没有适用于此角色和篇章的唯一职业映射")
        }
        val profession = professionFor(jobId)
        val startLevel = capture(errors, 0) { boundedInt(record["level"], "level", 1..99) }
        val currentLevel = capture<Int?>(errors, null) {
            record["currentLevel"]?.takeUnless { it is JsonNull }?.let { boundedInt(it, "currentLevel", 1..99) }
        }
        val candidate = capture<Long?>(errors, null) { seed(record["candidate"], "candidate") }
        val current = capture<Long?>(errors, null) { seed(record["current"], "current") }
        val fullSearch = capture<Boolean?>(warnings, null) {
            record["fullSearch"]?.takeUnless { it is JsonNull }?.let { boolean(it, "fullSearch") }
        }
        val matchCount = capture<Long?>(warnings, null) {
            record["matchCount"]?.takeUnless { it is JsonNull }?.let {
                integer(it, "matchCount").also { count -> require(count >= 0) { "matchCount 不能为负数" } }
            }
        }
        val currentStats = capture<List<Int>?>(errors, null) {
            record["currentStats"]?.takeUnless { it is JsonNull }?.let { vector(it, "currentStats", 0..32767) }
        }
        val caps = capture(errors, List<Int?>(9) { null }) {
            record["caps"]?.takeUnless { it is JsonNull }?.let { value ->
                val values = array(value, "caps")
                require(values.size == 9) { "caps 必须包含九项上限" }
                values.mapIndexed { stat, cap -> if (cap is JsonNull) null else boundedInt(cap, "caps[$stat]", 0..32767) }
            } ?: List(9) { null }
        }
        if (caps.any { it != null }) errors += "存在属性上限，但各次升级前的剩余空间尚未核实，不能按未封顶记录导入"
        if (currentStats != null) warnings += "属性数值保留来源 currentStats 原值；未把它推断为全部升级后的属性。"
        else warnings += "没有可用的九项属性数值，不能据此建立属性起点。"
        if (record["caps"] == null || record["caps"] is JsonNull) warnings += "来源未提供属性上限；没有据此推断游戏中的上限。"
        val sourceRows = capture(errors, JsonArray(emptyList())) { array(record["observations"], "observations") }
        require(sourceRows.size <= MAX_OBSERVATIONS) { "records[$index].observations 最多允许 $MAX_OBSERVATIONS 项" }
        if (startLevel + sourceRows.size > 99) errors += "升级记录超出 99 级"
        val batchEndLevels = capture(errors, List<Int?>(sourceRows.size) { null }) {
            batchGrowthLevels(record["batches"], startLevel, sourceRows.size)
        }
        if (currentLevel != null && currentLevel != startLevel + sourceRows.size) errors += "currentLevel 与实际升级次数不一致"
        val eventRecord = capture<ObservationRecord?>(errors, null) {
            validateObservationRecord(record["observationRecord"], sourceRows.size)
        }
        val choices = eventRecord?.choices ?: JsonArray(emptyList())
        capture(errors, Unit) { validateUnmodeledInputs(record, sourceRows.size) }
        val upgrades = sourceRows.mapIndexedNotNull { rowIndex, row ->
            val gains = capture<List<Int>?>(errors, null) { vector(row, "observations[$rowIndex]", 0..9) }
            val choiceJob = capture<String?>(errors, null) {
                val choice = choices.getOrNull(rowIndex)
                if (choice == null || choice is JsonNull) null else {
                    val fields = choice as? JsonObject ?: throw IllegalArgumentException("choices[$rowIndex] 必须是对象")
                    require((fields.keys - setOf("job", "mountId")).isEmpty()) { "choices[$rowIndex] 含未核实的职业、坐骑或友好度字段" }
                    fields["mountId"]?.let {
                        require(string(it, "choices[$rowIndex].mountId").isEmpty()) { "choices[$rowIndex] 坐骑缺少可验证的友好度，不能导入" }
                    }
                    fields["job"]?.takeUnless { it is JsonNull }?.let { string(it, "choices[$rowIndex].job").takeIf(String::isNotEmpty) }
                }
            }
            val rowProfession = choiceJob?.let(::professionFor) ?: profession
            gains?.let {
                val level = startLevel + rowIndex
                LevelObservation(level = level, profession = rowProfession, part3 = part3, gains = it,
                    growthLevel = batchEndLevels.getOrNull(rowIndex))
            }
        }
        if (upgrades.size == sourceRows.size && eventRecord != null) {
            capture(errors, Unit) {
                for (rowIndex in 1 until upgrades.size) {
                    val batchEnd = batchEndLevels[rowIndex]
                    if (batchEnd != null && batchEnd == batchEndLevels[rowIndex - 1]) {
                        require(upgrades[rowIndex].profession == upgrades[rowIndex - 1].profession) {
                            "batches 同次结算不能更换职业"
                        }
                        require(upgrades[rowIndex].ride == upgrades[rowIndex - 1].ride) {
                            "batches 同次结算不能更换坐骑"
                        }
                        require(eventRecord.before[rowIndex].isEmpty()) { "batches 同次结算不能参加考试" }
                    }
                }
            }
        }
        val observations = buildList {
            var activeProfession = profession
            fun appendExams(exams: List<SourceExam>, level: Int) {
                exams.forEach { exam ->
                    val target = exam.jobId?.let(::professionFor).orEmpty()
                    add(LevelObservation(level = level, profession = activeProfession, part3 = part3,
                        exam = JournalExam(target, null, exam.passed, null)))
                }
            }
            upgrades.forEachIndexed { rowIndex, upgrade ->
                appendExams(eventRecord?.before?.getOrNull(rowIndex).orEmpty(), upgrade.level)
                add(upgrade)
                activeProfession = upgrade.profession
            }
            appendExams(eventRecord?.trailing.orEmpty(), startLevel + sourceRows.size)
        }
        if (observations.any { it.exam != null }) {
            warnings += "已保留考试成败与顺序；来源未提供逐次成功率和消耗，反推时每次考试仅推进一次种子。"
        }
        if (upgrades.any { it.growthLevel != null }) {
            warnings += "已按来源批次末级重建每次升级使用的成长等级；记录仍按逐级顺序保存。"
        }
        if (upgrades.size < 5) warnings += "实测升级少于五次；仍可保存记录，后续需继续补充。"
        if (listOf("routes", "prediction", "plan", "position", "recordPosition", "progress", "revisions").any(record::containsKey)) {
            warnings += "预测、规划、执行位置和历史修订未作为实际升级导入。"
        }
        if (fullSearch != null || matchCount != null) warnings += "fullSearch 和 matchCount 仅保留为来源信息；全空间搜索和全局唯一性未在本地复核。"
        var replayVerified = false
        if (errors.isEmpty() && character != null) {
            capture(errors, Unit) {
                val base = rules.growth(BuddyGrowthInput(character.id, profession, startLevel, part3, BuddyRoute.KAI)).parameters
                record["rates"]?.let { require(vector(it, "rates", 0..32767) == base.growth) { "rates 与已安装的成长规则不一致" } }
                record["minimum"]?.let { require(integer(it, "minimum") == base.guarantee.toLong()) { "minimum 与已安装的保底规则不一致" } }
                var replaySeed = candidate
                observations.forEachIndexed { rowIndex, observation ->
                    if (observation.exam != null) {
                        replaySeed = replaySeed?.let(SeedEngine::advance)
                        return@forEachIndexed
                    }
                    val parameters = BuddyRoute.entries.map { route ->
                        rules.growth(BuddyGrowthInput(character.id, observation.profession,
                            observation.growthLevel ?: observation.level, part3, route)).parameters
                    }.distinct()
                    require(parameters.size == 1) { "第 ${rowIndex + 1} 次升级依赖尚未选择的路线，不能预先验证" }
                    replaySeed?.let { seed ->
                        val result = SeedEngine.simulateLevel(seed, parameters.single())
                        require(result.gains == observation.gains) { "candidate 在第 ${rowIndex + 1} 条记录与实际提升不一致" }
                        replaySeed = result.nextSeed
                    }
                }
                if (candidate != null && current != null) {
                    require(replaySeed == current) { "candidate 回放后的种子与 current 不一致" }
                    replayVerified = observations.any { it.levelsUp }
                }
            }
        }
        if (!replayVerified && errors.isEmpty()) warnings += "缺少完整的起始种子、末尾种子或升级记录，尚未完成候选回放验证。"
        return FefwGrowthSeedRecordPreview(index, character?.id ?: pid, character?.name ?: pid, profession,
            part3, startLevel, currentLevel, candidate, current, fullSearch, matchCount, observations.toList(),
            warnings.toList(), errors.toList(), replayVerified && errors.isEmpty(), currentStats, caps)
    }

    private data class SourceExam(val jobId: String?, val passed: Boolean)

    private data class ObservationRecord(
        val choices: JsonArray,
        val before: List<List<SourceExam>>,
        val trailing: List<SourceExam>,
    )

    private fun validateObservationRecord(element: JsonElement?, count: Int): ObservationRecord {
        val record = element as? JsonObject ?: throw IllegalArgumentException("observationRecord 必须是对象")
        require((record.keys - setOf("version", "choices", "counts", "details", "trailing")).isEmpty()) {
            "observationRecord 含尚未支持的事件字段"
        }
        require(integer(record["version"], "observationRecord.version") == 1L) { "不支持此 observationRecord 版本" }
        val choices = array(record["choices"], "observationRecord.choices")
        require(choices.isEmpty() || choices.size == count) { "choices 数量必须与 observations 一致，或为空以继承原职业" }
        val counts = array(record["counts"], "observationRecord.counts")
        require(counts.size == count) { "observationRecord.counts 数量与 observations 不一致" }
        val details = array(record["details"], "observationRecord.details")
        require(details.size == count) { "observationRecord.details 数量与 observations 不一致" }
        val trailing = array(record["trailing"], "observationRecord.trailing")
        val before = details.mapIndexed { index, detail ->
            val exams = array(detail, "observationRecord.details[$index]")
            require(exams.size == boundedInt(counts[index], "observationRecord.counts[$index]", 0..MAX_OBSERVATIONS)) {
                "observationRecord.details[$index] 与考试次数不一致"
            }
            exams
        }
        require(count.toLong() + before.sumOf { it.size.toLong() } + trailing.size <= MAX_OBSERVATIONS) {
            "升级和考试合计最多允许 $MAX_OBSERVATIONS 条记录"
        }
        fun exam(element: JsonElement, path: String): SourceExam {
            val fields = element as? JsonObject ?: throw IllegalArgumentException("$path 必须是考试对象")
            require((fields.keys - setOf("family", "success", "skippedUpgrade")).isEmpty()) { "$path 含未核实的考试字段" }
            val job = fields["family"]?.takeUnless { it is JsonNull }?.let {
                string(it, "$path.family").takeIf(String::isNotEmpty)
            }
            return SourceExam(job, boolean(fields["success"], "$path.success"))
        }
        return ObservationRecord(choices,
            before.mapIndexed { index, exams -> exams.mapIndexed { position, value ->
                exam(value, "observationRecord.details[$index][$position]")
            } },
            trailing.mapIndexed { index, value -> exam(value, "observationRecord.trailing[$index]") })
    }

    private fun validateUnmodeledInputs(record: JsonObject, count: Int) {
        record["mountId"]?.let { require(string(it, "mountId").isEmpty()) { "mountId 的坐骑和友好度尚不能验证" } }
        record["obsMounts"]?.let { element ->
            val mounts = array(element, "obsMounts")
            require(mounts.size == count && mounts.all { string(it, "obsMounts") == "" }) { "obsMounts 含未核实的坐骑或数量不一致" }
        }
        record["passive"]?.let { require(boolean(it, "passive")) { "尚未核实 passive=false 的成长语义" } }
        record["dead"]?.let { element ->
            val flags = array(element, "dead")
            require(flags.size == 9 && flags.all { !boolean(it, "dead") }) { "dead 含未支持的属性停增标记" }
        }
    }

    private fun batchGrowthLevels(element: JsonElement?, startLevel: Int, count: Int): List<Int?> {
        val raw = element?.takeUnless { it is JsonNull }?.let { string(it, "batches") }.orEmpty().trim()
        val batchSizes = if (raw.isEmpty()) List(count) { 1 } else raw.split(Regex("[,，\\s]+")).mapIndexed { index, token ->
            val size = token.toIntOrNull() ?: throw IllegalArgumentException("batches[$index] 必须是正整数")
            require(size > 0 && size <= count) { "batches[$index] 必须在 1 至 $count 之间" }
            size
        }
        require(batchSizes.isNotEmpty() == (count > 0) && batchSizes.sumOf(Int::toLong) == count.toLong()) {
            "batches 每批数量之和必须等于 observations 数量 $count"
        }
        var completed = 0
        return batchSizes.flatMap { batchSize ->
            val batchEndLevel = (startLevel + completed + batchSize - 1).coerceIn(1, 99)
            completed += batchSize
            List(batchSize) { if (batchSize > 1) batchEndLevel else null }
        }
    }

    private fun checkJsonBounds(text: String) {
        var inString = false
        var escaped = false
        var depth = 0
        var tokens = 0
        for (character in text) {
            if (inString) {
                if (escaped) escaped = false
                else if (character == '\\') escaped = true
                else if (character == '"') inString = false
            } else when (character) {
                '"' -> inString = true
                '{', '[' -> {
                    depth++
                    tokens++
                    require(depth <= MAX_DEPTH) { "JSON 嵌套超过 $MAX_DEPTH 层" }
                }
                '}', ']' -> { depth--; require(depth >= 0) { "JSON 括号不匹配" } }
                ',', ':' -> tokens++
            }
            require(tokens <= MAX_STRUCTURE_TOKENS) { "JSON 结构过于复杂" }
        }
        require(!inString && depth == 0) { "JSON 未完整结束" }
    }

    private inline fun <T> capture(errors: MutableList<String>, fallback: T, block: () -> T): T = try {
        block()
    } catch (error: IllegalArgumentException) {
        errors += error.message ?: "记录格式无效"
        fallback
    }

    private fun array(element: JsonElement?, path: String): JsonArray =
        element as? JsonArray ?: throw IllegalArgumentException("$path 必须是列表")

    private fun string(element: JsonElement?, path: String): String =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw IllegalArgumentException("$path 必须是文本")

    private fun integer(element: JsonElement?, path: String): Long =
        (element as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
            ?: throw IllegalArgumentException("$path 必须是整数")

    private fun boundedInt(element: JsonElement?, path: String, range: IntRange): Int {
        val value = integer(element, path)
        require(value in range.first.toLong()..range.last.toLong()) { "$path 必须在 ${range.first} 至 ${range.last} 之间" }
        return value.toInt()
    }

    private fun boolean(element: JsonElement?, path: String): Boolean =
        (element as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
            ?: throw IllegalArgumentException("$path 必须是布尔值")

    private fun vector(element: JsonElement?, path: String, range: IntRange): List<Int> {
        val values = array(element, path)
        require(values.size == 9) { "$path 必须包含九个整数" }
        return values.mapIndexed { index, value -> boundedInt(value, "$path[$index]", range) }
    }

    private fun seed(element: JsonElement?, path: String): Long? {
        if (element == null || element is JsonNull) return null
        return integer(element, path).also {
            require(it in 0 until SeedEngine.UINT32_LIMIT) { "$path 必须是 0 至 4294967295 的 uint32 种子" }
        }
    }
}
