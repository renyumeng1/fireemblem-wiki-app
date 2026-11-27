package com.fireemblem.companion.personal

import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class RestorationProgress(
    val profileId: String,
    val inventory: Map<String, Int> = emptyMap(),
    val completedLocations: Set<String> = emptySet(),
) {
    init {
        require(profileId.isNotBlank()) { "请选择个人存档" }
        require(inventory.keys.all { it.startsWith("restoration-material:") }) { "材料编号无效" }
        require(inventory.values.all { it in 0..1_000_000 }) { "库存数量应为0至1000000" }
        require(completedLocations.all { it.startsWith("restoration-location:") }) { "复兴地点编号无效" }
    }

    fun payload(): JsonObject = buildJsonObject {
        put("recordType", "restoration")
        put("version", 1)
        put("profileId", profileId)
        put("name", "第3部复兴清单")
        put("inventory", JsonObject(inventory.toSortedMap().mapValues { JsonPrimitive(it.value) }))
        put("completedLocations", JsonArray(completedLocations.sorted().map(::JsonPrimitive)))
    }

    companion object {
        fun entityId(profileId: String): String {
            require(profileId.isNotBlank())
            return UUID.nameUUIDFromBytes("restoration\u0000$profileId".toByteArray(Charsets.UTF_8)).toString()
        }

        fun decode(payload: JsonObject): RestorationProgress {
            require(payload["recordType"]?.jsonPrimitive?.content == "restoration") { "不是复兴清单" }
            val version = payload.getValue("version").jsonPrimitive
            require(!version.isString && version.int == 1) { "不支持的复兴清单版本" }
            val completed = payload.getValue("completedLocations").jsonArray.map {
                require(it.jsonPrimitive.isString) { "复兴地点编号无效" }
                it.jsonPrimitive.content
            }
            require(completed.distinct().size == completed.size) { "复兴地点重复" }
            return RestorationProgress(
                payload.getValue("profileId").jsonPrimitive.let { require(it.isString); it.content },
                payload.getValue("inventory").jsonObject.mapValues { (_, value) ->
                    value.jsonPrimitive.let { require(!it.isString); it.int }
                },
                completed.toSet(),
            )
        }

        fun read(snapshot: PersonalSnapshot, profileId: String): RestorationProgress {
            val heads = snapshot.heads()[entityId(profileId)].orEmpty()
            require(heads.size <= 1) { "复兴清单有多个版本，请先到个人存档处理冲突" }
            val head = heads.singleOrNull() ?: return RestorationProgress(profileId)
            require(head.kind == "progress") { "复兴清单记录类型不匹配" }
            if (head.deleted) return RestorationProgress(profileId)
            return decode(head.payload).also { require(it.profileId == profileId) { "复兴清单不属于当前存档" } }
        }
    }
}
