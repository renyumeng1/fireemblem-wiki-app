package com.fireemblem.companion.importer

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class BuddyBackupPreview(
    val sourceVersion: Int,
    val characterCount: Int,
    val activeCharacterCount: Int,
    val deletedCharacterCount: Int,
    val recordCount: Int,
    val skippedRecordCount: Int,
    val upgradedRecordCount: Int,
    val seedCount: Int,
    val highSeedCount: Int,
    val unknownFieldCount: Int,
    val unknownFieldPaths: List<String>,
    val sourceSha256: String,
    val canonicalJson: String,
)

class BuddyBackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

object BuddyBackupInspector {
    const val MAX_FILE_BYTES = 10 * 1024 * 1024
    const val MAX_RECORDS = 20_000

    private const val MAX_RULE_DEPTH = 16
    private const val MAX_RULE_NODES = 256
    private const val MAX_UNKNOWN_FIELD_SAMPLES = 20

    private class UnknownFieldReport {
        var count: Int = 0
            private set
        private val samples = linkedSetOf<String>()

        fun record(path: String) {
            count++
            if (samples.size < MAX_UNKNOWN_FIELD_SAMPLES) samples += path
        }

        fun paths(): List<String> = samples.toList()
    }

    private val stats = setOf("HP", "力量", "魔力", "速度", "技巧", "防守", "魔防", "幸运", "魅力")
    private val characterIds = setOf(
        "c001", "c002", "c003", "c004", "c005", "c006", "c007", "c008", "c009", "c010",
        "c011", "c012", "c013", "c014", "c015", "c016", "c017", "c018", "c019", "c020",
        "c021", "c022", "c023", "c024", "c025", "c026", "c027", "c028", "c029", "c030",
        "c031", "c032", "c033", "c034", "c035", "c036", "c037", "c038", "c039", "c040",
        "c041", "c042", "c043", "c044", "c045", "c046", "c047", "c048", "c049", "c050",
        "c051", "c052", "c053", "c054", "c055", "c056", "c057", "c058", "c059", "c060",
        "c061", "c062", "c063", "c064", "c065", "c066", "c067", "c068", "c069", "c070",
        "c071", "c072", "c073", "c074", "c075", "c076", "c077", "c078",
    )
    private val professions = setOf(
        "平民", "贵族", "斗士", "猎兵", "士兵", "飞鸵兵", "诅咒师", "剑客", "土匪", "斗拳手", "弓箭手", "恶棍",
        "重装步兵", "轻骑兵", "战车兵", "骑甲鸵兵", "天翼兵", "萨满巫师", "牧师", "侍道", "舞者", "勇士", "锻造师", "狙击手",
        "森林骑士", "游侠", "卫士", "巨型重甲兵", "荣光骑士", "神鸵兵", "铁甲骑兵", "战象兵", "驭龙兵", "游唱诗人", "先知", "主教",
        "刀剑将领", "高阶墓志铭", "战斗将领", "武僧", "宗师射手", "弓骑士", "探影者", "欺诈师", "圣枪师", "要塞", "奥利哈铁骑",
        "英勇骑士", "系尔", "巴伊波·卡哈", "圣天翼兵", "飞龙将领", "德鲁伊", "贤士", "觉者", "瓦尔基里姆", "刃辉神将", "神乐神将",
        "永霸神将", "破拳神将", "绝祸神将", "岚枪神将", "烈骏神将", "圣转神将",
    )
    private val part3OnlyProfessions = setOf(
        "战象兵", "刀剑将领", "高阶墓志铭", "战斗将领", "武僧", "宗师射手", "弓骑士", "探影者", "欺诈师", "圣枪师", "要塞",
        "奥利哈铁骑", "英勇骑士", "系尔", "巴伊波·卡哈", "圣天翼兵", "飞龙将领", "德鲁伊", "贤士", "瓦尔基里姆", "刃辉神将",
        "神乐神将", "永霸神将", "破拳神将", "绝祸神将", "岚枪神将", "烈骏神将", "圣转神将",
    )
    private val rideKinds = mapOf(
        "RID_馬" to "horse", "RID_汗血馬" to "horse", "RID_黒馬" to "horse", "RID_ユニコーン" to "horse", "RID_ロシナン" to "horse",
        "RID_オルニウス" to "ornius", "RID_白羽のオルニウス" to "ornius", "RID_メガニウス" to "ornius",
        "RID_赤羽のメガニウス" to "ornius", "RID_マジニウス" to "ornius", "RID_黒羽のマジニウス" to "ornius",
        "RID_ペガサス" to "pegasus", "RID_ダークペガサス" to "pegasus", "RID_ファルコン" to "pegasus",
        "RID_レッドファルコン" to "pegasus", "RID_ブーケパラス" to "pegasus",
        "RID_バウ" to "bau", "RID_レッドバウ" to "bau", "RID_ブラックバウ" to "bau", "RID_ダグザンゾウ" to "elephant",
    )
    private val mergedRides = mapOf("RID_ブーケパラスの仔" to "RID_ブーケパラス", "RID_ロシナンの仔" to "RID_ロシナン")
    private val classAliases = mapOf("骑甲驼兵" to "骑甲鸵兵")
    private val rootFields = setOf(
        "version", "roster", "selection", "initialLevels", "part3", "route", "recommendTier", "recommendScope",
        "summaryScroll", "deletionCounter", "draftProfessions", "draftStoryClasses", "draftRides", "draftExpectations", "rideMemory",
    )
    private val rosterFields = setOf(
        "id", "initialLevel", "records", "createdAt", "updatedAt", "deletedAt", "deletionBatch", "deletionOrder",
        "initialProfession", "pendingProfession", "unlockedClasses", "examRange", "expectations", "seed", "deleted",
    )
    private val recordFields = setOf(
        "stats", "happy", "skipped", "upgraded", "deleted", "part3", "calculated", "corrected", "anchor", "status", "plus2",
        "profession", "ride", "seq", "id", "level", "ratingSource", "ratingPending", "seedInput", "createdAt", "updatedAt",
        "deletedAt", "deletionBatch", "deletionOrder",
    )
    private val seedFields = setOf("value", "rows")
    private val seedRowFields = setOf("skipped", "upgraded", "part3", "happy", "status", "stats", "plus2", "profession", "ride")
    private val rideFields = setOf("id", "level")
    private val seedInputFields = setOf("profession", "ride", "stats", "plus2", "calculated")
    private val ruleFields = mapOf(
        "stat" to setOf("type", "stat"),
        "total" to setOf("type", "op", "value"),
        "not" to setOf("type", "child"),
        "all" to setOf("type", "children"),
        "any" to setOf("type", "children"),
    )

    fun inspect(bytes: ByteArray): BuddyBackupPreview {
        if (bytes.size > MAX_FILE_BYTES) throw BuddyBackupException("备份超过 10 MiB 上限")
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (error: Exception) {
            throw BuddyBackupException("文件不是有效的 UTF-8 JSON", error)
        }
        val input = try {
            Json.parseToJsonElement(text).jsonObject
        } catch (error: Exception) {
            throw BuddyBackupException("备份不是有效的 JSON 对象", error)
        }
        val sourceVersion = if (input["version"] == null || input["version"] is JsonNull) 1 else input.intValue("version", path = "version")
        if (sourceVersion !in 1..2) throw BuddyBackupException("不支持备份版本 $sourceVersion；原文件未改动")
        validateSourceEnvelope(input)

        val unknown = UnknownFieldReport()
        collectUnknown(input, rootFields, "$", unknown)
        val migrated = migrate(input, sourceVersion)
        val roster = migrated.requireArray("roster")
        var recordCount = 0
        var skippedCount = 0
        var upgradedCount = 0
        var seedCount = 0
        var highSeedCount = 0
        val itemIds = mutableSetOf<String>()
        val recordIds = mutableSetOf<String>()

        roster.forEachIndexed { itemIndex, element ->
            val path = "roster[$itemIndex]"
            val item = element.objectAt(path)
            collectUnknown(item, rosterFields, path, unknown)
            val id = item.stringValue("id", "$path.id")
            if (id !in characterIds) throw BuddyBackupException("$path.id 不属于已知角色，原始数据不会被覆盖")
            if (!itemIds.add(id)) throw BuddyBackupException("角色 $id 重复出现")
            validateMetadata(item, path)
            if (!item.containsKey("initialLevel")) throw BuddyBackupException("$path.initialLevel 缺失")
            item.optionalInt("initialLevel", path, 1..99, allowNull = true)
            item.optionalBoolean("deleted", path)
            item.optionalProfession("initialProfession", path)
            item.optionalProfession("pendingProfession", path)
            validateUnlockedClasses(item, path)
            validateExamRange(item, path)
            validateExpectations(item["expectations"], "$path.expectations", unknown)
            if (item.containsKey("seed") && item["seed"] is JsonNull) throw BuddyBackupException("$path.seed 不正确")
            validateSeed(item["seed"], "$path.seed", unknown).also { seedValue ->
                if (seedValue != null) {
                    seedCount++
                    if (seedValue >= 0x80000000L) highSeedCount++
                }
            }
            val records = item.requireArray("records", path)
            recordCount += records.size
            if (recordCount > MAX_RECORDS) throw BuddyBackupException("备份包含超过 20,000 条记录")
            var lastSequence = 0L
            records.forEachIndexed { recordIndex, recordElement ->
                val recordPath = "$path.records[$recordIndex]"
                val record = recordElement.objectAt(recordPath)
                collectUnknown(record, recordFields, recordPath, unknown)
                val legacySkipped = record.booleanValue("skipped", recordPath).orFalse()
                val legacyUpgraded = record.booleanValue("upgraded", recordPath).orFalse()
                if (legacySkipped && legacyUpgraded) throw BuddyBackupException("$recordPath 状态冲突")
                val legacyStatus = if (legacySkipped) "skipped" else if (legacyUpgraded) "upgraded" else null
                val recordStatus = record.stringValue("status", recordPath, default = "normal")
                if (legacyStatus != null && recordStatus != "normal" && legacyStatus != recordStatus) {
                    throw BuddyBackupException("$recordPath 状态冲突")
                }
                when (recordStatus) {
                    "normal" -> if (legacyStatus == "skipped") skippedCount++ else if (legacyStatus == "upgraded") upgradedCount++
                    "skipped" -> skippedCount++
                    "upgraded" -> upgradedCount++
                    else -> throw BuddyBackupException("$recordPath.status 状态不正确")
                }
                val statsValue = record.arrayValue("stats", recordPath)
                validateStats(statsValue, "$recordPath.stats")
                if (record.booleanValue("happy", recordPath) == null) throw BuddyBackupException("$recordPath.happy 必须是布尔值")
                validateMetadata(record, recordPath)
                record.optionalBoolean("deleted", recordPath)
                record.optionalBoolean("part3", recordPath)
                record.optionalBoolean("calculated", recordPath)
                record.optionalBoolean("corrected", recordPath)
                record.optionalBoolean("anchor", recordPath)
                record.optionalProfession("profession", recordPath)
                validateRide(record["ride"], "$recordPath.ride", unknown)
                record.optionalArray("plus2", recordPath)?.let { validatePlus2(it, statsValue, "$recordPath.plus2") }
                record.optionalString("ratingSource", recordPath, setOf("auto", "manual", "legacy"))
                record.optionalBoolean("ratingPending", recordPath)
                if (record["seedInput"] != null && record.booleanValue("corrected", recordPath) != true) {
                    throw BuddyBackupException("$recordPath.seedInput 只能用于已修正记录")
                }
                validateSeedInput(record["seedInput"], "$recordPath.seedInput", unknown)
                val sequence = if (sourceVersion == 1) recordIndex + 1L else record.longValue("seq", recordPath)
                if (sequence <= lastSequence || sequence > 9_007_199_254_740_990L) {
                    throw BuddyBackupException("$recordPath.seq 序号不正确")
                }
                lastSequence = sequence
                val recordId = record.stringValue("id", "$recordPath.id")
                if (recordId.isBlank() || recordId.length > 128 || !recordIds.add(recordId)) {
                    throw BuddyBackupException("$recordPath.id 记录 ID 不正确或重复")
                }
            }
        }

        validateTopLevel(migrated, unknown)
        return BuddyBackupPreview(
            sourceVersion = sourceVersion,
            characterCount = roster.size,
            activeCharacterCount = roster.count { !it.jsonObject.booleanValue("deleted", "roster.deleted").orFalse() },
            deletedCharacterCount = roster.count { it.jsonObject.booleanValue("deleted", "roster.deleted").orFalse() },
            recordCount = recordCount,
            skippedRecordCount = skippedCount,
            upgradedRecordCount = upgradedCount,
            seedCount = seedCount,
            highSeedCount = highSeedCount,
            unknownFieldCount = unknown.count,
            unknownFieldPaths = unknown.paths(),
            sourceSha256 = sha256(bytes),
            canonicalJson = migrated.toString(),
        )
    }

    private fun migrate(input: JsonObject, sourceVersion: Int): JsonObject {
        val root = input.toMutableMap()
        root["version"] = JsonPrimitive(2)
        root.putIfAbsent("selection", JsonArray(emptyList()))
        root.putIfAbsent("initialLevels", JsonObject(emptyMap()))
        root["part3"] = JsonPrimitive(input.booleanValue("part3", "part3").orFalse())
        root["route"] = JsonPrimitive(input.stringValue("route", "route", default = "kai").also {
            if (it !in setOf("kai", "other")) throw BuddyBackupException("路线不正确")
        })
        val migratedRoster = input.requireArray("roster").mapIndexed { index, element ->
            val path = "roster[$index]"
            val item = element.objectAt(path).toMutableMap()
            val initialLevel = item["initialLevel"].let { if (it == null || it is JsonNull) 0 else intValue(it, "$path.initialLevel") }
            val records = (item["records"] ?: throw BuddyBackupException("$path.records 缺失")).arrayAt("$path.records")
            var expectedLegacyLevel = initialLevel + 1
            val migratedRecords = records.mapIndexed { recordIndex, recordElement ->
                val recordPath = "$path.records[$recordIndex]"
                val record = recordElement.objectAt(recordPath).toMutableMap()
                val legacySkipped = record.booleanValue("skipped", recordPath).orFalse()
                val legacyUpgraded = record.booleanValue("upgraded", recordPath).orFalse()
                if (legacySkipped && legacyUpgraded) throw BuddyBackupException("$recordPath 状态冲突")
                val legacyStatus = if (legacySkipped) "skipped" else if (legacyUpgraded) "upgraded" else null
                val savedStatus = record.stringValue("status", recordPath, default = "")
                if (legacyStatus != null && savedStatus.isNotEmpty() && legacyStatus != savedStatus) {
                    throw BuddyBackupException("$recordPath 状态冲突")
                }
                val status = savedStatus.ifEmpty { legacyStatus ?: "normal" }
                if (status !in setOf("normal", "skipped", "upgraded")) throw BuddyBackupException("$recordPath.status 状态不正确")
                if (sourceVersion == 1) {
                    val oldLevel = record.intValue("level", path = "$recordPath.level")
                    if (oldLevel != expectedLegacyLevel) throw BuddyBackupException("$recordPath.level 旧记录等级不正确")
                    record["seq"] = JsonPrimitive(recordIndex + 1)
                    record["id"] = JsonPrimitive(UUID.randomUUID().toString())
                    record["ratingSource"] = JsonPrimitive("legacy")
                    if (status != "skipped") expectedLegacyLevel++
                }
                record.remove("level")
                record.remove("skipped")
                record.remove("upgraded")
                record["status"] = JsonPrimitive(status)
                record["profession"] = normalizeProfession(record["profession"]) ?: JsonNull
                record["ride"] = normalizeRide(record["ride"], "$recordPath.ride") ?: JsonNull
                record.putIfAbsent("profession", JsonNull)
                record.putIfAbsent("ride", JsonNull)
                val profession = record["profession"]?.stringOrNull()
                record["part3"] = JsonPrimitive(record["part3"]?.asBooleanOrNull() == true || profession in part3OnlyProfessions)
                if (record["ratingSource"] == null) record["ratingSource"] = JsonPrimitive("legacy")
                if (record["ratingPending"]?.asBooleanOrNull() == true) record["happy"] = JsonPrimitive(true)
                record.remove("ratingPending")
                record["seedInput"]?.let { seedInputElement ->
                    val seedInput = seedInputElement.objectAt("$recordPath.seedInput").toMutableMap()
                    if (seedInput.containsKey("profession")) {
                        seedInput["profession"] = normalizeProfession(seedInput["profession"]) ?: JsonNull
                    }
                    if (seedInput.containsKey("ride")) {
                        seedInput["ride"] = normalizeRide(seedInput["ride"], "$recordPath.seedInput.ride") ?: JsonNull
                    }
                    record["seedInput"] = JsonObject(seedInput)
                }
                JsonObject(record)
            }
            item["records"] = JsonArray(migratedRecords)
            item["deleted"] = JsonPrimitive(item["deleted"]?.asBooleanOrNull() == true)
            item["initialProfession"] = normalizeProfession(item["initialProfession"]) ?: JsonNull
            if (item.containsKey("pendingProfession")) item["pendingProfession"] = normalizeProfession(item["pendingProfession"]) ?: JsonNull
            item["expectations"] = item["expectations"] ?: JsonArray(emptyList())
            item["unlockedClasses"]?.let { element ->
                item["unlockedClasses"] = JsonArray(element.arrayAt("$path.unlockedClasses").map { normalizeProfession(it) ?: JsonNull })
            }
            if (item.containsKey("seed")) item["seed"] = normalizeSeed(item["seed"], "$path.seed") ?: JsonNull
            JsonObject(item)
        }
        root["roster"] = JsonArray(migratedRoster)
        for (key in listOf("draftProfessions", "draftStoryClasses")) {
            root[key]?.let { element ->
                root[key] = JsonObject(element.objectAt(key).mapValues { normalizeProfession(it.value) ?: JsonNull })
            }
        }
        for (key in listOf("draftRides", "rideMemory")) {
            root[key]?.let { element ->
                root[key] = JsonObject(element.objectAt(key).mapValues { (id, rideMap) ->
                    JsonObject(rideMap.objectAt("$key.$id").mapValues { (kind, ride) ->
                        normalizeRide(ride, "$key.$id.$kind") ?: JsonNull
                    })
                })
            }
        }
        return JsonObject(root)
    }

    private fun normalizeSeed(element: JsonElement?, path: String): JsonElement? {
        if (element == null || element is JsonNull) return element
        val seed = element.objectAt(path).toMutableMap()
        val rows = seed["rows"]?.let { rowsElement ->
            val objectRows = rowsElement.objectAt("$path.rows")
            JsonObject(objectRows.mapValues { (index, value) ->
                val rowPath = "$path.rows.$index"
                val row = value.objectAt(rowPath).toMutableMap()
                listOf("skipped", "upgraded", "part3", "happy").forEach { row.optionalBoolean(it, rowPath) }
                val skipped = row.booleanValue("skipped", rowPath).orFalse()
                val upgraded = row.booleanValue("upgraded", rowPath).orFalse()
                if (skipped && upgraded) throw BuddyBackupException("$rowPath 状态冲突")
                val legacyStatus = if (skipped) "skipped" else if (upgraded) "upgraded" else null
                val existingStatus = row.stringValue("status", rowPath, default = legacyStatus ?: "")
                if (existingStatus.isNotEmpty() && existingStatus !in setOf("skipped", "upgraded")) throw BuddyBackupException("$rowPath.status 不正确")
                if (legacyStatus != null && existingStatus.isNotEmpty() && legacyStatus != existingStatus) throw BuddyBackupException("$rowPath.status 状态冲突")
                if (existingStatus.isNotEmpty()) row["status"] = JsonPrimitive(existingStatus)
                row.remove("skipped")
                row.remove("upgraded")
                row["profession"] = normalizeProfession(row["profession"]) ?: JsonNull
                row["ride"] = normalizeRide(row["ride"], "$rowPath.ride") ?: JsonNull
                JsonObject(row)
            })
        }
        if (rows != null) seed["rows"] = rows
        return JsonObject(seed)
    }

    private fun normalizeProfession(element: JsonElement?): JsonElement? {
        if (element == null || element is JsonNull) return element
        val name = element.stringOrNull() ?: return element
        return JsonPrimitive(classAliases[name] ?: name)
    }

    private fun normalizeRide(element: JsonElement?, path: String): JsonElement? {
        if (element == null || element is JsonNull) return element
        val ride = element.objectAt(path).toMutableMap()
        val id = ride["id"]?.stringOrNull() ?: throw BuddyBackupException("$path.id 不正确")
        ride["id"] = JsonPrimitive(mergedRides[id] ?: id)
        return JsonObject(ride)
    }

    private fun validateTopLevel(root: JsonObject, unknown: UnknownFieldReport) {
        root["selection"]?.let { element ->
            val selection = element.arrayAt("selection").mapIndexed { index, item -> item.stringValue("selection[$index]") }
            if (selection.size != selection.toSet().size || selection.any { id ->
                    id !in characterIds || root.requireArray("roster").any { entry ->
                        val item = entry.jsonObject
                        item["id"]?.stringOrNull() == id && item.booleanValue("deleted", "roster.$id.deleted").orFalse()
                    }
                }) throw BuddyBackupException("人物选择不正确")
        }
        root["initialLevels"]?.let { element ->
            val levels = element.objectAt("initialLevels")
            levels.forEach { (id, value) ->
                if (id !in characterIds) throw BuddyBackupException("初始等级包含未知角色")
                if (value is JsonPrimitive && value.isString) {
                    if (value.content.length > 80) throw BuddyBackupException("初始等级草稿过长")
                } else if (intValue(value, "initialLevels.$id") !in 1..99) throw BuddyBackupException("初始等级不正确")
            }
        }
        root["part3"]?.let { if (it.asBooleanOrNull() == null) throw BuddyBackupException("第 3 部开关不正确") }
        root["recommendScope"]?.let { if (it.stringOrNull() != "unlocked") throw BuddyBackupException("兵种范围不正确") }
        root["recommendTier"]?.let { element ->
            val tiers = element.objectAt("recommendTier")
            tiers.forEach { (key, value) ->
                val cap = intValue(value, "recommendTier.$key")
                val max = when (key) { "pre" -> 3; "part3" -> 5; else -> throw BuddyBackupException("推荐范围不正确") }
                if (cap !in 0 until max) throw BuddyBackupException("推荐范围不正确")
            }
        }
        root["summaryScroll"]?.let {
            val scroll = runCatching { it.jsonPrimitive.doubleOrNull }.getOrNull()
            if (scroll == null || !scroll.isFinite() || scroll < 0) throw BuddyBackupException("页面位置不正确")
        }
        root["deletionCounter"]?.let {
            val counter = longValue(it, "deletionCounter")
            if (counter < 0 || counter > 9_007_199_254_740_991L) throw BuddyBackupException("删除顺序不正确")
        }
        for (key in listOf("draftProfessions", "draftStoryClasses")) {
            root[key]?.let { element ->
                element.objectAt(key).forEach { (id, value) ->
                    if (id !in characterIds) throw BuddyBackupException("$key 包含未知角色")
                    validateProfession(value, "$key.$id", nullable = true)
                }
            }
        }
        for (key in listOf("draftRides", "rideMemory")) {
            root[key]?.let { element ->
                val map = element.objectAt(key)
                map.forEach { (id, rides) ->
                    if (id !in characterIds) throw BuddyBackupException("$key 包含未知角色")
                    rides.objectAt("$key.$id").forEach { (kind, ride) ->
                        if (kind !in setOf("horse", "ornius", "pegasus", "bau", "elephant")) throw BuddyBackupException("$key.$id 配属类型不正确")
                        validateRide(ride, "$key.$id.$kind", unknown, kind)
                    }
                }
            }
        }
        root["draftExpectations"]?.let { element ->
            element.objectAt("draftExpectations").forEach { (id, rule) ->
                if (id !in characterIds) throw BuddyBackupException("待设置期望包含未知角色")
                validateRule(rule, "draftExpectations.$id", unknown)
            }
        }
    }

    private fun validateMetadata(value: JsonObject, path: String) {
        listOf("createdAt", "updatedAt", "deletedAt", "deletionBatch").forEach { key ->
            value[key]?.let { element ->
                val text = element.stringOrNull() ?: throw BuddyBackupException("$path.$key 信息不正确")
                if (text.length > 128) throw BuddyBackupException("$path.$key 信息过长")
            }
        }
        value["deletionOrder"]?.let {
            val order = longValue(it, "$path.deletionOrder")
            if (order < 0 || order > 9_007_199_254_740_991L) throw BuddyBackupException("$path.deletionOrder 不正确")
        }
    }

    private fun validateUnlockedClasses(item: JsonObject, path: String) {
        item["unlockedClasses"]?.let { element ->
            val classes = element.arrayAt("$path.unlockedClasses").mapIndexed { index, item ->
                item.stringValue("$path.unlockedClasses[$index]").let { classAliases[it] ?: it }
            }
            if (classes.size != classes.toSet().size || classes.any { it !in professions }) throw BuddyBackupException("$path.unlockedClasses 兵种不正确")
        }
    }

    private fun validateExamRange(item: JsonObject, path: String) {
        item["examRange"]?.let { element ->
            if (element is JsonNull) throw BuddyBackupException("$path.examRange 格式不正确")
            val range = element.objectAt("$path.examRange")
            if (range.keys.any { it !in setOf("from", "to") }) throw BuddyBackupException("$path.examRange 格式不正确")
            val from = range["from"]?.let { intValue(it, "$path.examRange.from") }
            val to = range["to"]?.let { intValue(it, "$path.examRange.to") }
            if ((from != null && from !in 1..99) || (to != null && to !in 1..99) || (from != null && to != null && from > to)) {
                throw BuddyBackupException("$path.examRange 等级范围不正确")
            }
        }
    }

    private fun validateExpectations(element: JsonElement?, path: String, unknown: UnknownFieldReport) {
        if (element == null) return
        if (element is JsonNull) throw BuddyBackupException("$path 格式不正确")
        var previous = 0L
        element.arrayAt(path).forEachIndexed { index, item ->
            val rangePath = "$path[$index]"
            val range = item.objectAt(rangePath)
            val from = range.longValue("from", rangePath)
            if (from <= previous || from > 9_007_199_254_740_991L) throw BuddyBackupException("$rangePath.from 必须递增")
            previous = from
            validateRule(range["rule"], "$rangePath.rule", unknown)
            collectUnknown(range, setOf("from", "rule"), rangePath, unknown)
        }
    }

    private fun validateRule(element: JsonElement?, path: String, unknown: UnknownFieldReport) {
        if (element == null || element is JsonNull) return
        var nodes = 0
        fun visit(nodeElement: JsonElement?, nodePath: String, depth: Int) {
            val node = nodeElement?.objectAt(nodePath) ?: throw BuddyBackupException("$nodePath 条件不正确")
            nodes++
            if (nodes > MAX_RULE_NODES || depth > MAX_RULE_DEPTH) throw BuddyBackupException("条件过多或层级过深")
            val type = node.stringValue("type", nodePath)
            val fields = ruleFields[type] ?: throw BuddyBackupException("$nodePath 条件类型不支持")
            when (type) {
                "stat" -> if (node.stringValue("stat", nodePath) !in stats) throw BuddyBackupException("$nodePath 属性未知")
                "total" -> {
                    val operation = node.stringValue("op", nodePath)
                    val value = node.intValue("value", path = nodePath)
                    val valid = when (operation) {
                        "gt" -> value in 0..8
                        "gte" -> value in 0..9
                        "lt" -> value in 1..9
                        "lte" -> value in 0..9
                        else -> false
                    }
                    if (!valid) throw BuddyBackupException("$nodePath 总点数条件不正确")
                }
                "not" -> visit(node["child"], "$nodePath.child", depth + 1)
                "all", "any" -> {
                    val children = node["children"]?.arrayAt("$nodePath.children") ?: throw BuddyBackupException("$nodePath.children 不能为空")
                    if (children.isEmpty()) throw BuddyBackupException("$nodePath.children 不能为空")
                    children.forEachIndexed { index, child -> visit(child, "$nodePath.children[$index]", depth + 1) }
                }
            }
            collectUnknown(node, fields, nodePath, unknown)
        }
        visit(element, path, 0)
    }

    private fun validateSeed(element: JsonElement?, path: String, unknown: UnknownFieldReport): Long? {
        if (element == null || element is JsonNull) return null
        val seed = element.objectAt(path)
        collectUnknown(seed, seedFields, path, unknown)
        val value = seed.longValue("value", path)
        if (value !in 0 until 0x1_0000_0000L) throw BuddyBackupException("$path.value 不是有效的 uint32 种子")
        seed["rows"]?.let { rowsElement ->
            rowsElement.objectAt("$path.rows").forEach { (index, rowElement) ->
                if (!index.matches(Regex("(?:0|[1-9]\\d{0,4})"))) throw BuddyBackupException("$path.rows 索引不正确")
                val rowPath = "$path.rows.$index"
                val row = rowElement.objectAt(rowPath)
                collectUnknown(row, seedRowFields, rowPath, unknown)
                row.optionalBoolean("skipped", rowPath)
                row.optionalBoolean("upgraded", rowPath)
                row.optionalBoolean("part3", rowPath)
                row.optionalBoolean("happy", rowPath)
                row.optionalProfession("profession", rowPath)
                row["stats"]?.let { validateStats(it.arrayAt("$rowPath.stats"), "$rowPath.stats") }
                row["plus2"]?.let { validatePlus2(it.arrayAt("$rowPath.plus2"), row["stats"]?.arrayAt("$rowPath.stats") ?: JsonArray(emptyList()), "$rowPath.plus2") }
                validateRide(row["ride"], "$rowPath.ride", unknown)
            }
        }
        return value
    }

    private fun validateRide(element: JsonElement?, path: String, unknown: UnknownFieldReport, expectedKind: String? = null) {
        if (element == null || element is JsonNull) return
        val ride = element.objectAt(path)
        collectUnknown(ride, rideFields, path, unknown)
        val id = ride.stringValue("id", path).let { mergedRides[it] ?: it }
        val kind = rideKinds[id] ?: throw BuddyBackupException("$path.id 配属不正确")
        if (expectedKind != null && kind != expectedKind) throw BuddyBackupException("$path.id 配属类型不正确")
        val level = ride.intValue("level", path = path)
        if (level !in 1..5) throw BuddyBackupException("$path.level 配属等级不正确")
    }

    private fun validateSeedInput(element: JsonElement?, path: String, unknown: UnknownFieldReport) {
        if (element == null) return
        if (element is JsonNull) throw BuddyBackupException("$path 格式不正确")
        val input = element.objectAt(path)
        collectUnknown(input, seedInputFields, path, unknown)
        validateProfession(input["profession"] ?: throw BuddyBackupException("$path.profession 缺失"), "$path.profession", nullable = true)
        val statsValue = input["stats"]?.arrayAt("$path.stats") ?: throw BuddyBackupException("$path.stats 缺失")
        validateStats(statsValue, "$path.stats")
        val plus2 = input["plus2"]?.arrayAt("$path.plus2") ?: throw BuddyBackupException("$path.plus2 缺失")
        validatePlus2(plus2, statsValue, "$path.plus2")
        if (!input.containsKey("ride")) throw BuddyBackupException("$path.ride 缺失")
        if (input["ride"] !is JsonNull) validateRide(input["ride"], "$path.ride", unknown)
        input.optionalBoolean("calculated", path)
        if (input.containsKey("calculated") && input["calculated"]?.asBooleanOrNull() != true) {
            throw BuddyBackupException("$path.calculated 必须为 true")
        }
    }

    private fun validateSourceEnvelope(input: JsonObject) {
        input["part3"]?.let {
            if (it.asBooleanOrNull() == null) throw BuddyBackupException("第 3 部开关不正确")
        }
        input["route"]?.let {
            if (it.stringOrNull() !in setOf("kai", "other")) throw BuddyBackupException("路线不正确")
        }
        input.requireArray("roster").forEachIndexed { itemIndex, element ->
            val path = "roster[$itemIndex]"
            val item = element.objectAt(path)
            if (!item.containsKey("initialLevel")) throw BuddyBackupException("$path.initialLevel 缺失")
            item.optionalInt("initialLevel", path, 1..99, allowNull = true)
            item.optionalBoolean("deleted", path)
            item.requireArray("records", path).forEachIndexed { recordIndex, recordElement ->
                val recordPath = "$path.records[$recordIndex]"
                val record = recordElement.objectAt(recordPath)
                listOf("skipped", "upgraded", "deleted", "part3", "calculated", "corrected", "anchor", "ratingPending")
                    .forEach { record.optionalBoolean(it, recordPath) }
                if (record.booleanValue("happy", recordPath) == null) throw BuddyBackupException("$recordPath.happy 必须是布尔值")
            }
        }
    }

    private fun validateProfession(element: JsonElement?, path: String, nullable: Boolean) {
        if (element == null || element is JsonNull) {
            if (!nullable) throw BuddyBackupException("$path 兵种缺失")
            return
        }
        val name = element.stringValue(path).let { classAliases[it] ?: it }
        if (name !in professions) throw BuddyBackupException("$path 兵种未知")
    }

    private fun validateStats(value: JsonArray, path: String) {
        val values = value.mapIndexed { index, item -> item.stringValue("$path[$index]") }
        if (values.size != values.toSet().size || values.any { it !in stats }) throw BuddyBackupException("$path 属性列表不正确")
    }

    private fun validatePlus2(value: JsonArray, raised: JsonArray, path: String) {
        val values = value.mapIndexed { index, item -> item.stringValue("$path[$index]") }
        val raisedStats = raised.mapNotNull { it.stringOrNull() }.toSet()
        if (values.size != values.toSet().size || values.any { it !in raisedStats }) throw BuddyBackupException("$path +2 列表不正确")
    }

    private fun collectUnknown(value: JsonObject, allowed: Set<String>, path: String, target: UnknownFieldReport) {
        value.keys.filterNot(allowed::contains).forEach { key -> target.record("$path.$key") }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun Map<String, JsonElement>.requireArray(key: String, parent: String = "$"): JsonArray =
        this[key]?.arrayAt("$parent.$key") ?: throw BuddyBackupException("$parent.$key 缺失")

    private fun Map<String, JsonElement>.arrayValue(key: String, parent: String): JsonArray =
        this[key]?.arrayAt("$parent.$key") ?: throw BuddyBackupException("$parent.$key 缺失")

    private fun Map<String, JsonElement>.optionalArray(key: String, parent: String): JsonArray? = this[key]?.let { it.arrayAt("$parent.$key") }

    private fun JsonElement.arrayAt(path: String): JsonArray = try {
        jsonArray
    } catch (error: Exception) {
        throw BuddyBackupException("$path 必须是列表", error)
    }

    private fun JsonElement.objectAt(path: String): JsonObject = try {
        jsonObject
    } catch (error: Exception) {
        throw BuddyBackupException("$path 必须是对象", error)
    }

    private fun Map<String, JsonElement>.stringValue(key: String, path: String, default: String? = null): String =
        this[key]?.stringValue("$path.$key") ?: default ?: throw BuddyBackupException("$path.$key 缺失")

    private fun JsonElement.stringValue(path: String): String =
        stringOrNull() ?: throw BuddyBackupException("$path 必须是文本")

    private fun JsonElement.stringOrNull(): String? = try {
        jsonPrimitive.takeIf { it.isString }?.content
    } catch (_: Exception) {
        null
    }

    private fun Map<String, JsonElement>.intValue(key: String, default: Int? = null, path: String): Int =
        this[key]?.let { intValue(it, "$path.$key") } ?: default ?: throw BuddyBackupException("$path.$key 缺失")

    private fun Map<String, JsonElement>.longValue(key: String, path: String): Long =
        this[key]?.let { longValue(it, "$path.$key") } ?: throw BuddyBackupException("$path.$key 缺失")

    private fun intValue(value: JsonElement, path: String): Int = value.jsonPrimitive.intOrNull
        ?: throw BuddyBackupException("$path 必须是整数")

    private fun longValue(value: JsonElement, path: String): Long = value.jsonPrimitive.longOrNull
        ?: throw BuddyBackupException("$path 必须是整数")

    private fun Map<String, JsonElement>.booleanValue(key: String, path: String): Boolean? = this[key]?.asBooleanOrNull()
        ?: if (this[key] == null || this[key] is JsonNull) null else throw BuddyBackupException("$path.$key 必须是布尔值")

    private fun JsonElement.asBooleanOrNull(): Boolean? = try {
        jsonPrimitive.booleanOrNull
    } catch (_: Exception) {
        null
    }

    private fun Map<String, JsonElement>.optionalBoolean(key: String, path: String) {
        if (containsKey(key) && booleanValue(key, path) == null) {
            throw BuddyBackupException("$path.$key 必须是布尔值")
        }
    }

    private fun Map<String, JsonElement>.optionalString(key: String, path: String, values: Set<String>) {
        this[key]?.let {
            val value = it.stringValue("$path.$key")
            if (value !in values) throw BuddyBackupException("$path.$key 不支持")
        }
    }

    private fun Map<String, JsonElement>.optionalProfession(key: String, path: String) {
        this[key]?.let { validateProfession(it, "$path.$key", nullable = true) }
    }

    private fun Map<String, JsonElement>.optionalInt(key: String, path: String, range: IntRange, allowNull: Boolean) {
        val element = this[key] ?: return
        if (element is JsonNull && allowNull) return
        if (intValue(element, "$path.$key") !in range) throw BuddyBackupException("$path.$key 数值范围不正确")
    }

    private fun Boolean?.orFalse(): Boolean = this ?: false
}
