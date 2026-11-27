package com.fireemblem.companion.importer

import com.fireemblem.companion.engine.BuddyCampaignLine
import com.fireemblem.companion.engine.BuddyRules
import com.fireemblem.companion.engine.ImportedGrowthActionKind
import com.fireemblem.companion.engine.JournalImportedGrowthAction
import com.fireemblem.companion.engine.JournalImportedGrowthPlan
import com.fireemblem.companion.engine.JournalSeedCheckpoint
import com.fireemblem.companion.engine.JournalSeedEvidence
import com.fireemblem.companion.engine.LevelJournal
import com.fireemblem.companion.engine.SeedEngine
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.json.*

data class FefwGrowthChaptersImportPreview(
    val sourceSha256: String,
    val sourceArchive: FefwGrowthSeedSourceArchive,
    val entries: List<FefwGrowthChaptersEntryPreview>,
) {
    fun toJournal(
        entryIndex: Int,
        routeIndex: Int,
        name: String,
        profile: String,
        line: BuddyCampaignLine,
        rules: BuddyRules,
    ): LevelJournal {
        require(name.isNotBlank() && profile.isNotBlank()) { "请填写记录册名称和存档" }
        val entry = entries.singleOrNull { it.index == entryIndex } ?: error("所选角色记录已不存在")
        require(entry.ready) { "此角色记录尚不能安全导入：${entry.errors.joinToString("；")}" }
        val route = entry.routes.singleOrNull { it.index == routeIndex } ?: error("所选路线分支已不存在")
        require(route.ready) { "此路线尚不能安全导入：${route.errors.joinToString("；")}" }
        val seed = requireNotNull(route.initialSeed)
        val level = requireNotNull(route.initialLevel)
        val stats = requireNotNull(route.initialStats)
        val profession = entry.initialProfession
        require(rules.professionForJobId(entry.initialProfessionId, entry.characterId, entry.part3)?.name == profession) {
            "起始职业映射与当前规则不一致"
        }
        route.actions.forEach { action ->
            require(rules.professionForJobId(action.jobId, entry.characterId, entry.part3)?.name == action.profession) {
                "路线职业映射与当前规则不一致"
            }
        }
        val base = LevelJournal(name, profile, line, entry.characterId)
        val digest = base.historyDigest()
        val unlocked = setOf(profession)
        val checkpoint = JournalSeedCheckpoint(
            seed = seed,
            eventIndex = 0,
            level = level,
            profession = profession,
            part3 = entry.part3,
            stats = stats,
            caps = route.caps,
            evidence = JournalSeedEvidence.USER_CONFIRMED_IMPORT,
            historyDigest = digest,
            ruleRevision = base.ruleRevision,
            sourceSha256 = sourceSha256,
            originSeed = seed,
            unlockedProfessions = unlocked,
        ).validated()
        val plan = JournalImportedGrowthPlan(
            sourceSha256 = sourceSha256,
            entryIndex = entry.index,
            routeIndex = route.index,
            entryId = entry.entryId,
            chapter = entry.chapter,
            round = entry.round,
            routeName = route.name,
            initialSeed = seed,
            initialLevel = level,
            initialProfessionId = entry.initialProfessionId,
            initialProfession = profession,
            part3 = entry.part3,
            initialStats = stats,
            caps = route.caps,
            initialUnlockedProfessions = unlocked,
            actions = route.actions,
            historyDigest = digest,
            ruleRevision = base.ruleRevision,
        ).validated()
        return base.copy(
            seedCheckpoint = checkpoint,
            unlockedProfessions = unlocked,
            importSourceSha256 = sourceSha256,
            importSourceRecordIndex = entry.index,
            importedGrowthPlan = plan,
        ).validated()
    }
}

data class FefwGrowthChaptersEntryPreview(
    val index: Int,
    val entryId: String,
    val chapter: String,
    val round: Int,
    val characterId: String,
    val characterName: String,
    val part3: Boolean,
    val initialProfessionId: String,
    val initialProfession: String,
    val routes: List<FefwGrowthChaptersRoutePreview>,
    val errors: List<String>,
) {
    val ready: Boolean get() = errors.isEmpty() && initialProfessionId.isNotBlank() &&
        initialProfession.isNotBlank() && routes.isNotEmpty()
}

data class FefwGrowthChaptersRoutePreview(
    val index: Int,
    val name: String,
    val initialSeed: Long?,
    val initialLevel: Int?,
    val initialStats: List<Int>?,
    val caps: List<Int?>,
    val actions: List<JournalImportedGrowthAction>,
    val errors: List<String>,
) {
    val ready: Boolean get() = name.isNotBlank() && initialSeed != null && initialLevel != null &&
        initialStats != null && actions.isNotEmpty() && errors.isEmpty()
}

object FefwGrowthChaptersImporter {
    const val MAX_BYTES = FefwGrowthSeedImporter.MAX_BYTES
    private const val MAX_ENTRIES = 1000
    private const val MAX_ROUTES_PER_ENTRY = 100
    private const val MAX_ACTIONS = 1000
    private const val MAX_DEPTH = 64
    private const val MAX_STRUCTURE_TOKENS = 1_000_000
    private val routeFields = setOf("name", "forecast", "plan", "position", "positionSource", "recordPosition", "progress")
    private val actionFields = setOf("job", "mountId", "exams", "kind")

    fun isGrowthChaptersFile(bytes: ByteArray): Boolean {
        require(bytes.size in 1..MAX_BYTES) { "成长方案文件必须在 10 MiB 以内" }
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (error: CharacterCodingException) {
            throw IllegalArgumentException("成长方案文件必须是有效的 UTF-8", error)
        }
        checkJsonBounds(text)
        val root = Json.parseToJsonElement(text) as? JsonObject ?: return false
        return (root["format"] as? JsonPrimitive)?.takeIf { it.isString }?.content == "fefw-growth-chapters"
    }

    fun inspect(bytes: ByteArray, rules: BuddyRules): FefwGrowthChaptersImportPreview {
        require(bytes.size in 1..MAX_BYTES) { "成长方案文件必须在 10 MiB 以内" }
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (error: CharacterCodingException) {
            throw IllegalArgumentException("成长方案文件必须是有效的 UTF-8", error)
        }
        checkJsonBounds(text)
        val root = try {
            Json.parseToJsonElement(text) as? JsonObject
                ?: throw IllegalArgumentException("成长方案根节点必须是对象")
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("成长方案 JSON 格式不正确：${error.message}", error)
        }
        require(string(root["format"], "format") == "fefw-growth-chapters") { "不是 fefw-growth-chapters 文件" }
        require(integer(root["version"], "version") == 1L) { "仅支持 fefw-growth-chapters v1" }
        val sourceEntries = array(root["entries"], "entries")
        require(sourceEntries.size in 1..MAX_ENTRIES) { "角色记录数量应在 1 至 $MAX_ENTRIES 之间" }
        var actionTotal = 0
        val entries = sourceEntries.mapIndexed { index, element ->
            inspectEntry(index, element, rules).also { entry ->
                actionTotal += entry.routes.sumOf { it.actions.size }
                require(actionTotal <= MAX_ACTIONS) { "成长方案最多包含 $MAX_ACTIONS 个路线步骤" }
            }
        }
        val archive = FefwGrowthSeedSourceArchive.fromBytes(bytes)
        return FefwGrowthChaptersImportPreview(archive.sourceSha256, archive, entries)
    }

    private fun inspectEntry(index: Int, element: JsonElement, rules: BuddyRules): FefwGrowthChaptersEntryPreview {
        val errors = mutableListOf<String>()
        val entry = capture(errors, JsonObject(emptyMap())) {
            element as? JsonObject ?: throw IllegalArgumentException("entries[$index] 必须是对象")
        }
        val entryId = capture(errors, "") { string(entry["id"], "entries[$index].id") }
        val chapter = capture(errors, "") { string(entry["chapter"], "entries[$index].chapter") }
        val round = capture(errors, 0) { boundedInt(entry["round"], "entries[$index].round", 0..999) }
        val snapshot = capture(errors, JsonObject(emptyMap())) { obj(entry["snapshot"], "entries[$index].snapshot") }
        val inputs = capture(errors, JsonObject(emptyMap())) { obj(snapshot["inputs"], "entries[$index].snapshot.inputs") }
        val pid = capture(errors, "") { string(inputs["pid"], "entries[$index].snapshot.inputs.pid") }
        val character = rules.characters.singleOrNull { it.personId == pid }
        if (character == null) errors += "personId=$pid 未找到唯一、已验证的角色映射"
        val part3 = capture(errors, false) {
            val value = string(inputs["part"], "entries[$index].snapshot.inputs.part")
            require(value == "0" || value == "1") { "篇章只能是 0（前篇）或 1（第三部）" }
            value == "1"
        }
        if (character?.part3Only == true && !part3) errors += "此角色只在第三部可用"
        val initialProfessionId = capture(errors, "") {
            string(inputs["job"], "entries[$index].snapshot.inputs.job")
        }
        val initialProfession = character?.let {
            rules.professionForJobId(initialProfessionId, it.id, part3)?.name
        }
        if (initialProfession == null) errors += "job=$initialProfessionId 没有唯一、已验证的起始职业映射"
        val name = capture(errors, "") { string(entry["name"], "entries[$index].name") }
        if (name.isBlank()) errors += "路线记录名称不能为空"
        val routes = capture(errors, JsonArray(emptyList())) {
            array(snapshot["routes"], "entries[$index].snapshot.routes")
        }
        if (routes.size !in 1..MAX_ROUTES_PER_ENTRY) errors += "每条角色记录应包含 1 至 $MAX_ROUTES_PER_ENTRY 条路线分支"
        val snapshotCursorErrors = buildList {
            listOf("position", "recordPosition").forEach { field ->
                snapshot[field]?.let { cursor ->
                    val value = capture(errors, -1L) { integer(cursor, "entries[$index].snapshot.$field") }
                    if (value != 0L) add("snapshot.$field=$value，已有执行进度无法还原为实测记录")
                }
            }
            snapshot["activeRoute"]?.let { active ->
                val value = capture(errors, -1) { boundedInt(active, "entries[$index].snapshot.activeRoute", 0..MAX_ROUTES_PER_ENTRY) }
                if (value !in routes.indices) add("snapshot.activeRoute 不对应现有路线分支")
            }
        }
        val execution = capture(errors, JsonObject(emptyMap())) { obj(entry["execution"], "entries[$index].execution") }
        val executionCursor = capture(errors, -1L) { integer(execution["cursor"], "entries[$index].execution.cursor") }
        if (executionCursor != 0L) errors += "execution.cursor=$executionCursor，已有执行进度无法还原为实测记录"
        errors += snapshotCursorErrors
        val routePreviews = routes.mapIndexed { routeIndex, routeElement ->
            val routeErrors = errors.toMutableList()
            val route = capture(routeErrors, JsonObject(emptyMap())) {
                obj(routeElement, "entries[$index].snapshot.routes[$routeIndex]")
            }
            val routeName = capture(routeErrors, "") { string(route["name"], "routes[$routeIndex].name") }
            if ((route.keys - routeFields).isNotEmpty()) routeErrors += "路线分支含尚未核实字段：${(route.keys - routeFields).sorted().take(8).joinToString()}"
            listOf("position", "recordPosition").forEach { field ->
                route[field]?.let { cursor ->
                    val value = capture(routeErrors, -1L) { integer(cursor, "routes[$routeIndex].$field") }
                    if (value != 0L) routeErrors += "路线已有执行游标 $field=$value，不能导入为未执行方案"
                }
            }
            val forecast = capture(routeErrors, JsonObject(emptyMap())) {
                obj(route["forecast"], "routes[$routeIndex].forecast")
            }
            val seed = capture<Long?>(routeErrors, null) { parseSeed(forecast["seed"], "routes[$routeIndex].forecast.seed") }
            val level = capture<Int?>(routeErrors, null) {
                boundedInt(forecast["level"], "routes[$routeIndex].forecast.level", 1..98)
            }
            val stats = capture<List<Int>?>(routeErrors, null) {
                vector(forecast["currentStats"], "routes[$routeIndex].forecast.currentStats", 0..32767)
            }
            val caps = capture(routeErrors, List<Int?>(9) { null }) {
                parseCaps(forecast["caps"], "routes[$routeIndex].forecast.caps", stats)
            }
            val actions = capture(routeErrors, JsonArray(emptyList())) {
                array(route["plan"], "routes[$routeIndex].plan")
            }
            if (actions.isEmpty()) routeErrors += "路线中没有可继续执行的步骤"
            val mappedActions = actions.mapIndexedNotNull { actionIndex, actionElement ->
                capture<JournalImportedGrowthAction?>(routeErrors, null) {
                    val action = obj(actionElement, "routes[$routeIndex].plan[$actionIndex]")
                    val unknown = action.keys - actionFields
                    require(unknown.isEmpty()) { "plan[$actionIndex] 含尚未核实字段：${unknown.sorted().joinToString()}" }
                    val jobId = string(action["job"], "plan[$actionIndex].job")
                    val profession = character?.let { rules.professionForJobId(jobId, it.id, part3)?.name }
                        ?: throw IllegalArgumentException("job=$jobId 没有唯一、适用于该角色和篇章的职业映射")
                    val mountId = string(action["mountId"], "plan[$actionIndex].mountId")
                    require(mountId.isEmpty()) { "plan[$actionIndex] 坐骑和友好度尚未核实，不能导入" }
                    val exams = boundedInt(action["exams"], "plan[$actionIndex].exams", 0..MAX_ACTIONS)
                    require(exams == 0) { "plan[$actionIndex] 包含额外考试次数，暂不支持自动执行" }
                    val kindId = string(action["kind"], "plan[$actionIndex].kind")
                    val kind = ImportedGrowthActionKind.entries.singleOrNull { it.id == kindId }
                        ?: throw IllegalArgumentException("plan[$actionIndex].kind=$kindId 尚未支持")
                    JournalImportedGrowthAction(jobId, profession, mountId, kind, exams)
                }
            }
            if (actions.size > MAX_ACTIONS) routeErrors += "单条路线超过 $MAX_ACTIONS 个步骤"
            val upgrades = mappedActions.count { it.kind == ImportedGrowthActionKind.UPGRADE }
            if (level != null && upgrades > 99 - level) routeErrors += "路线升级步骤超过 99 级"
            FefwGrowthChaptersRoutePreview(routeIndex, routeName, seed, level, stats, caps, mappedActions,
                routeErrors.distinct())
        }
        if (initialProfession != null && initialProfession !in rules.professions.map { it.name }) {
            errors += "起始职业无法用于当前规则版本"
        }
        return FefwGrowthChaptersEntryPreview(index, entryId, chapter, round, character?.id ?: pid,
            character?.name ?: pid, part3, initialProfessionId, initialProfession.orEmpty(), routePreviews, errors.distinct())
    }

    private fun parseCaps(element: JsonElement?, path: String, stats: List<Int>?): List<Int?> {
        if (element == null || element is JsonNull) return List(9) { null }
        val caps = array(element, path)
        require(caps.size == 9) { "$path 必须包含九项" }
        return caps.mapIndexed { index, cap ->
            if (cap is JsonNull) null else boundedInt(cap, "$path[$index]", 0..32767).also { value ->
                require(stats == null || value >= stats[index]) { "$path[$index] 低于当前属性" }
            }
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
        errors += error.message ?: "方案字段无效"
        fallback
    }

    private fun obj(element: JsonElement?, path: String): JsonObject =
        element as? JsonObject ?: throw IllegalArgumentException("$path 必须是对象")

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

    private fun vector(element: JsonElement?, path: String, range: IntRange): List<Int> {
        val values = array(element, path)
        require(values.size == 9) { "$path 必须包含九个整数" }
        return values.mapIndexed { index, value -> boundedInt(value, "$path[$index]", range) }
    }

    private fun parseSeed(element: JsonElement?, path: String): Long = integer(element, path).also {
        require(it in 0 until SeedEngine.UINT32_LIMIT) { "$path 必须是 0 至 4294967295 的 uint32 种子" }
    }
}
