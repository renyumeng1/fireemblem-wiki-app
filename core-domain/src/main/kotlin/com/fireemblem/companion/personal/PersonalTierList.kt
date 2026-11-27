package com.fireemblem.companion.personal

import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class PersonalTier(
    val id: String,
    val label: String,
    val description: String = "",
) {
    init {
        require(id.isNotBlank() && id.length <= 40) { "分档编号无效" }
        require(label.trim().isNotEmpty() && label.trim().length <= 24) { "分档名称应为 1 至 24 字" }
        require(description.length <= 200) { "分档说明不能超过 200 字" }
    }
}

data class PersonalTierEntry(
    val characterId: String,
    val tierId: String,
    val note: String = "",
) {
    init {
        require(
            characterId.startsWith(CHARACTER_PREFIX) &&
                characterId.length > CHARACTER_PREFIX.length &&
                characterId.length <= 128,
        ) { "角色图鉴编号无效" }
        require(tierId.isNotBlank() && tierId.length <= 40) { "分档编号无效" }
        require(note.length <= 500) { "角色评价不能超过 500 字" }
    }

    companion object {
        const val CHARACTER_PREFIX = "characters:"
    }
}

data class PersonalTierRecord(
    val entityId: String,
    val revisionId: String,
    val list: PersonalTierList,
)

data class PersonalTierCollection(
    val lists: List<PersonalTierRecord>,
    val conflictEntityIds: List<String>,
    val corruptedEntityIds: List<String>,
)

data class PersonalTierList(
    val profileId: String,
    val name: String,
    val note: String = "",
    val tiers: List<PersonalTier> = DEFAULT_TIERS,
    val entries: List<PersonalTierEntry> = emptyList(),
    val gameId: String = DEFAULT_GAME_ID,
) {
    init {
        require(profileId.isNotBlank() && profileId.length <= 128) { "请选择个人存档" }
        require(name.trim().isNotEmpty() && name.trim().length <= 80) { "梯度榜名称应为 1 至 80 字" }
        require(note.length <= 2000) { "梯度榜说明不能超过 2000 字" }
        require(gameId.isNotBlank() && gameId.length <= 64) { "游戏标识无效" }
        require(tiers.size in 1..MAX_TIERS) { "分档数量应为 1 至 $MAX_TIERS 个" }
        require(tiers.map(PersonalTier::id).distinct().size == tiers.size) { "分档编号重复" }
        require(tiers.map { it.label.trim() }.distinct().size == tiers.size) { "分档名称重复" }
        require(entries.size <= MAX_ENTRIES) { "角色分档数量不能超过 $MAX_ENTRIES 条" }
        require(entries.map(PersonalTierEntry::characterId).distinct().size == entries.size) {
            "同一角色在同一梯度榜中只能出现一次"
        }
        val tierIds = tiers.mapTo(HashSet(), PersonalTier::id)
        require(entries.all { it.tierId in tierIds }) { "角色分档引用了不存在的档位" }
    }

    fun entriesForTier(tierId: String): List<PersonalTierEntry> = entries.filter { it.tierId == tierId }

    fun entryFor(characterId: String): PersonalTierEntry? = entries.firstOrNull { it.characterId == characterId }

    fun assign(characterId: String, tierId: String, note: String? = null): PersonalTierList {
        require(tiers.any { it.id == tierId }) { "找不到目标档位" }
        val existing = entryFor(characterId)
        val resolvedNote = (note ?: existing?.note.orEmpty()).trim()
        val updatedEntry = PersonalTierEntry(characterId, tierId, resolvedNote)
        val nextEntries = when {
            existing == null -> entries + updatedEntry
            existing.tierId == tierId -> entries.map { if (it.characterId == characterId) updatedEntry else it }
            else -> entries.filterNot { it.characterId == characterId } + updatedEntry
        }
        return copy(entries = nextEntries)
    }

    fun remove(characterId: String): PersonalTierList =
        copy(entries = entries.filterNot { it.characterId == characterId })

    fun moveWithinTier(characterId: String, delta: Int): PersonalTierList {
        val target = entryFor(characterId) ?: return this
        if (delta == 0) return this
        val tierEntries = entries.filter { it.tierId == target.tierId }.toMutableList()
        val index = tierEntries.indexOfFirst { it.characterId == characterId }
        val swapIndex = index + delta
        if (index < 0 || swapIndex !in tierEntries.indices) return this
        val moved = tierEntries[index]
        tierEntries[index] = tierEntries[swapIndex]
        tierEntries[swapIndex] = moved
        var cursor = 0
        val reordered = entries.map { entry ->
            if (entry.tierId == target.tierId) tierEntries[cursor++] else entry
        }
        return copy(entries = reordered)
    }

    fun addTier(label: String, description: String = ""): PersonalTierList {
        val cleanLabel = label.trim()
        val cleanDescription = description.trim()
        val used = tiers.mapTo(HashSet(), PersonalTier::id)
        var candidate = "tier-${UUID.randomUUID().toString().take(8)}"
        while (candidate in used) {
            candidate = "tier-${UUID.randomUUID().toString().take(8)}"
        }
        return copy(tiers = tiers + PersonalTier(candidate, cleanLabel, cleanDescription))
    }

    fun renameTier(tierId: String, label: String, description: String = ""): PersonalTierList {
        require(tiers.any { it.id == tierId }) { "找不到目标档位" }
        val cleanLabel = label.trim()
        val cleanDescription = description.trim()
        return copy(tiers = tiers.map { tier ->
            if (tier.id == tierId) tier.copy(label = cleanLabel, description = cleanDescription) else tier
        })
    }

    fun removeTier(tierId: String): PersonalTierList {
        require(tiers.any { it.id == tierId }) { "找不到目标档位" }
        require(tiers.size > 1) { "至少需要保留一个档位" }
        return copy(
            tiers = tiers.filterNot { it.id == tierId },
            entries = entries.filterNot { it.tierId == tierId },
        )
    }

    fun updateMeta(name: String, note: String): PersonalTierList =
        copy(name = name.trim(), note = note.trim())

    fun payload(): JsonObject = buildJsonObject {
        put("recordType", RECORD_TYPE)
        put("version", VERSION)
        put("profileId", profileId)
        put("gameId", gameId)
        put("name", name.trim())
        put("note", note.trim())
        put("tiers", encodeTiers())
        put("entries", encodeEntries())
    }

    fun encodeExport(): ByteArray {
        val bytes = buildJsonObject {
            put("format", EXPORT_FORMAT)
            put("version", VERSION)
            put("gameId", gameId)
            put("name", name.trim())
            put("note", note.trim())
            put("tiers", encodeTiers())
            put("entries", encodeEntries())
        }.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_EXPORT_BYTES) { "梯度榜导出文件超过 1 MiB 上限" }
        return bytes
    }

    private fun encodeTiers(): JsonArray = JsonArray(tiers.map { tier ->
        buildJsonObject {
            put("id", tier.id)
            put("label", tier.label.trim())
            put("description", tier.description.trim())
        }
    })

    private fun encodeEntries(): JsonArray = JsonArray(entries.map { entry ->
        buildJsonObject {
            put("characterId", entry.characterId)
            put("tierId", entry.tierId)
            put("note", entry.note.trim())
        }
    })

    companion object {
        const val KIND = "tier"
        const val RECORD_TYPE = "personal-tier-list"
        const val VERSION = 1
        const val DEFAULT_GAME_ID = "fortunes-weave"
        const val EXPORT_FORMAT = "fireemblem-personal-tier"
        const val MAX_EXPORT_BYTES = 1024 * 1024
        const val MAX_TIERS = 12
        const val MAX_ENTRIES = 300

        val DEFAULT_TIERS = listOf(
            PersonalTier("tier-s", "S", "主力核心"),
            PersonalTier("tier-a", "A", "稳定可靠"),
            PersonalTier("tier-b", "B", "视阵容启用"),
            PersonalTier("tier-c", "C", "特定场合"),
            PersonalTier("tier-d", "D", "暂不优先"),
        )

        fun defaultEntityId(profileId: String): String {
            require(profileId.isNotBlank()) { "请选择个人存档" }
            return UUID.nameUUIDFromBytes("personal-tier-list\u0000$profileId".toByteArray(Charsets.UTF_8)).toString()
        }

        fun decode(payload: JsonObject): PersonalTierList {
            require(strictText(payload, "recordType") == RECORD_TYPE) { "不是私人梯度榜记录" }
            val version = payload["version"]?.jsonPrimitive ?: throw IllegalArgumentException("梯度榜缺少版本信息")
            require(!version.isString && version.int == VERSION) { "不支持的私人梯度榜版本" }
            val profileId = strictText(payload, "profileId")
            val gameId = optionalStrictText(payload, "gameId") ?: DEFAULT_GAME_ID
            val name = strictText(payload, "name")
            val note = optionalStrictText(payload, "note") ?: ""
            val tiers = decodeTiers(payload)
            val entries = decodeEntries(payload)
            return PersonalTierList(profileId, name, note, tiers, entries, gameId)
        }

        fun decodeExport(bytes: ByteArray, targetProfileId: String): PersonalTierList {
            require(bytes.size <= MAX_EXPORT_BYTES) { "梯度榜文件超过 1 MiB 上限" }
            val root = try {
                Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            } catch (error: Exception) {
                throw IllegalArgumentException("梯度榜文件不是有效的 JSON", error)
            }
            require(strictText(root, "format") == EXPORT_FORMAT) { "不是私人梯度榜导出文件" }
            val version = root["version"]?.jsonPrimitive ?: throw IllegalArgumentException("梯度榜文件缺少版本号")
            require(!version.isString && version.int == VERSION) { "不支持的梯度榜文件版本" }
            val gameId = optionalStrictText(root, "gameId") ?: DEFAULT_GAME_ID
            val name = strictText(root, "name")
            val note = optionalStrictText(root, "note") ?: ""
            val tiers = decodeTiers(root)
            val entries = decodeEntries(root)
            return PersonalTierList(targetProfileId, name, note, tiers, entries, gameId)
        }

        fun readProfile(
            snapshot: PersonalSnapshot,
            profileId: String,
            gameId: String = DEFAULT_GAME_ID,
        ): PersonalTierCollection {
            val lists = mutableListOf<PersonalTierRecord>()
            val conflicts = mutableListOf<String>()
            val corrupted = mutableListOf<String>()
            snapshot.heads().toSortedMap().forEach { (entityId, group) ->
                if (group.none { it.kind == KIND }) return@forEach
                if (group.size > 1) {
                    val belongs = group.any { revision ->
                        val recordProfile = (revision.payload["profileId"] as? JsonPrimitive)
                            ?.takeIf { it.isString }?.content
                        val recordGame = (revision.payload["gameId"] as? JsonPrimitive)
                            ?.takeIf { it.isString }?.content ?: DEFAULT_GAME_ID
                        (recordProfile == profileId || recordProfile == null) && recordGame == gameId
                    }
                    if (belongs) conflicts += entityId
                    return@forEach
                }
                val head = group.single()
                if (head.deleted) return@forEach
                val decoded = runCatching { decode(head.payload) }
                decoded.onSuccess { list ->
                    if (list.profileId == profileId && list.gameId == gameId) {
                        lists += PersonalTierRecord(entityId, head.revisionId, list)
                    }
                }.onFailure {
                    val recordProfile = (head.payload["profileId"] as? JsonPrimitive)
                        ?.takeIf { it.isString }?.content
                    val recordGame = (head.payload["gameId"] as? JsonPrimitive)
                        ?.takeIf { it.isString }?.content ?: DEFAULT_GAME_ID
                    if ((recordProfile == profileId || recordProfile == null) && recordGame == gameId) {
                        corrupted += entityId
                    }
                }
            }
            return PersonalTierCollection(
                lists = lists.sortedBy { it.list.name.lowercase() },
                conflictEntityIds = conflicts,
                corruptedEntityIds = corrupted,
            )
        }

        private fun decodeTiers(root: JsonObject): List<PersonalTier> {
            val array = root["tiers"]?.jsonArray ?: throw IllegalArgumentException("梯度榜缺少分档定义")
            return array.map { element ->
                val obj = element.jsonObject
                PersonalTier(
                    id = strictText(obj, "id"),
                    label = strictText(obj, "label"),
                    description = optionalStrictText(obj, "description") ?: "",
                )
            }
        }

        private fun decodeEntries(root: JsonObject): List<PersonalTierEntry> {
            val array = root["entries"]?.jsonArray ?: throw IllegalArgumentException("梯度榜缺少角色条目")
            return array.map { element ->
                val obj = element.jsonObject
                PersonalTierEntry(
                    characterId = strictText(obj, "characterId"),
                    tierId = strictText(obj, "tierId"),
                    note = optionalStrictText(obj, "note") ?: "",
                )
            }
        }

        private fun strictText(obj: JsonObject, key: String): String {
            val primitive = obj[key]?.jsonPrimitive ?: throw IllegalArgumentException("梯度榜缺少字段：$key")
            require(primitive.isString) { "梯度榜字段格式无效：$key" }
            return primitive.content
        }

        private fun optionalStrictText(obj: JsonObject, key: String): String? {
            val element = obj[key] ?: return null
            val primitive = element.jsonPrimitive
            require(primitive.isString) { "梯度榜字段格式无效：$key" }
            return primitive.content
        }
    }
}
