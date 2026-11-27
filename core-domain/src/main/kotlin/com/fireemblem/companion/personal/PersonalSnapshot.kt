package com.fireemblem.companion.personal

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
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

data class PersonalRevision(
    val revisionId: String,
    val entityId: String,
    val kind: String,
    val deviceId: String,
    val parents: List<String>,
    val deleted: Boolean,
    val modifiedAt: Long,
    val payload: JsonObject,
)

data class PersonalSnapshot(val revisions: List<PersonalRevision>) {
    fun validated(): PersonalSnapshot {
        require(revisions.size <= MAX_REVISIONS) { "个人记录超过支持的数量" }
        val byId = revisions.associateBy(PersonalRevision::revisionId)
        require(byId.size == revisions.size) { "修订编号重复" }
        revisions.forEach { revision ->
            require(listOf(revision.revisionId, revision.entityId, revision.deviceId).all { UUID_PATTERN.matches(it) }) { "个人记录编号不合法" }
            require(revision.kind in KINDS && revision.modifiedAt >= 0) { "个人记录类型或日期不合法" }
            require(revision.parents.size == revision.parents.distinct().size) { "父修订重复" }
            revision.parents.forEach { parentId ->
                val parent = requireNotNull(byId[parentId]) { "缺少父修订" }
                require(parent.entityId == revision.entityId && parent.kind == revision.kind) { "父修订属于不同记录" }
            }
        }
        require(revisions.groupBy(PersonalRevision::entityId).values.all { group -> group.map(PersonalRevision::kind).distinct().size == 1 }) {
            "同一记录的类型不一致"
        }
        val pending = revisions.associate { it.revisionId to it.parents.size }.toMutableMap()
        val children = mutableMapOf<String, MutableList<String>>()
        revisions.forEach { revision -> revision.parents.forEach { children.getOrPut(it) { mutableListOf() }.add(revision.revisionId) } }
        val ready = ArrayDeque(pending.filterValues { it == 0 }.keys)
        var visited = 0
        while (ready.isNotEmpty()) {
            val id = ready.removeFirst()
            visited++
            children[id].orEmpty().forEach { child ->
                val count = pending.getValue(child) - 1
                pending[child] = count
                if (count == 0) ready.addLast(child)
            }
        }
        require(visited == revisions.size) { "修订历史包含循环" }
        return PersonalSnapshot(revisions.sortedBy(PersonalRevision::revisionId))
    }

    fun heads(): Map<String, List<PersonalRevision>> {
        val superseded = revisions.flatMap(PersonalRevision::parents).toHashSet()
        return revisions.filter { it.revisionId !in superseded }.groupBy(PersonalRevision::entityId)
    }

    fun previousVersion(entityId: String, currentHeadId: String): PersonalRevision? {
        val head = heads()[entityId]?.singleOrNull()?.takeIf { it.revisionId == currentHeadId && !it.deleted }
            ?: return null
        val parentId = head.parents.singleOrNull() ?: return null
        return revisions.singleOrNull { it.revisionId == parentId && it.entityId == entityId && !it.deleted }
    }

    fun merge(other: PersonalSnapshot): PersonalSnapshot {
        validated()
        other.validated()
        val combined = revisions.associateBy(PersonalRevision::revisionId).toMutableMap()
        other.revisions.forEach { revision ->
            val existing = combined[revision.revisionId]
            require(existing == null || existing == revision) { "同一修订编号内容不一致" }
            combined[revision.revisionId] = revision
        }
        return PersonalSnapshot(combined.values.sortedBy(PersonalRevision::revisionId)).validated()
    }

    fun edit(entityId: String, kind: String, deviceId: String, payload: JsonObject, deleted: Boolean = false,
        expectedParents: Set<String> = heads()[entityId].orEmpty().map(PersonalRevision::revisionId).toSet(),
    ): PersonalSnapshot {
        val actual = heads()[entityId].orEmpty().map(PersonalRevision::revisionId).toSet()
        require(expectedParents == actual) { "记录已被修改，请重新打开后再保存" }
        val revision = PersonalRevision(UUID.randomUUID().toString(), entityId, kind, deviceId, actual.sorted(),
            deleted, System.currentTimeMillis(), payload)
        return PersonalSnapshot(revisions + revision).validated()
    }

    fun encode(): ByteArray {
        validated()
        val bytes = buildJsonObject {
            put("format", "fireemblem-personal")
            put("version", 1)
            put("revisions", JsonArray(revisions.sortedBy(PersonalRevision::revisionId).map { revision -> buildJsonObject {
                put("revisionId", revision.revisionId); put("entityId", revision.entityId); put("kind", revision.kind)
                put("deviceId", revision.deviceId); put("parents", JsonArray(revision.parents.map(::JsonPrimitive)))
                put("deleted", revision.deleted); put("modifiedAt", revision.modifiedAt); put("payload", revision.payload)
            } }))
        }.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "个人备份超过16MiB" }
        return bytes
    }

    companion object {
        const val MAX_BYTES = 16 * 1024 * 1024
        const val MAX_REVISIONS = 50000
        private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val KINDS = setOf("profile", "bookmark", "progress", "note", "buddy", "growth-plan", "tier", "map-route",
            "level-journal", "growth-seed-source", "route-merge-character", "seed-workbench")

        fun decode(bytes: ByteArray): PersonalSnapshot {
            require(bytes.size <= MAX_BYTES) { "个人备份超过16MiB" }
            val root = Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
            require(root["format"]?.jsonPrimitive?.content == "fireemblem-personal" && root["version"]?.jsonPrimitive?.int == 1) {
                "不支持的个人备份格式"
            }
            val rows = root.getValue("revisions").jsonArray
            require(rows.size <= MAX_REVISIONS) { "个人记录超过支持的数量" }
            return PersonalSnapshot(rows.map { element ->
                val record = element.jsonObject
                PersonalRevision(record.getValue("revisionId").jsonPrimitive.content, record.getValue("entityId").jsonPrimitive.content,
                    record.getValue("kind").jsonPrimitive.content, record.getValue("deviceId").jsonPrimitive.content,
                    record.getValue("parents").jsonArray.map { it.jsonPrimitive.content }, record.getValue("deleted").jsonPrimitive.boolean,
                    record.getValue("modifiedAt").jsonPrimitive.long, record.getValue("payload").jsonObject)
            }).validated()
        }
    }
}
