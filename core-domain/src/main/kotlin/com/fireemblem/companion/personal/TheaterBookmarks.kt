package com.fireemblem.companion.personal

import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class TheaterBookmark(val profileId: String, val playId: String, val name: String, val favorite: Boolean = false) {
    init {
        require(profileId.isNotBlank() && name.isNotBlank()) { "剧场收藏内容不完整" }
        require(playId.startsWith("theater-play:") && playId.length > "theater-play:".length) { "剧目编号无效" }
    }

    fun payload(): JsonObject = buildJsonObject {
        put("recordType", "theater-play"); put("version", 1); put("profileId", profileId)
        put("playId", playId); put("name", name); put("favorite", favorite)
    }

    companion object {
        fun entityId(profileId: String, playId: String): String {
            require(profileId.isNotBlank() && playId.startsWith("theater-play:"))
            return UUID.nameUUIDFromBytes("theater-bookmark\u0000$profileId\u0000$playId".toByteArray(Charsets.UTF_8)).toString()
        }

        fun decode(payload: JsonObject): TheaterBookmark {
            fun text(key: String) = payload.getValue(key).jsonPrimitive.let { require(it.isString); it.content }
            require(text("recordType") == "theater-play") { "不是剧场收藏" }
            val version = payload.getValue("version").jsonPrimitive
            require(!version.isString && version.int == 1) { "不支持的剧场收藏版本" }
            val favorite = payload.getValue("favorite").jsonPrimitive
            require(!favorite.isString) { "剧场收藏状态无效" }
            return TheaterBookmark(text("profileId"), text("playId"), text("name"), favorite.boolean)
        }

        fun read(snapshot: PersonalSnapshot, profileId: String, playId: String, title: String): TheaterBookmark {
            val heads = snapshot.heads()[entityId(profileId, playId)].orEmpty()
            require(heads.size <= 1) { "此剧目收藏有设备间冲突，请到个人存档处理" }
            val head = heads.singleOrNull() ?: return TheaterBookmark(profileId, playId, title)
            require(head.kind == "bookmark") { "剧场收藏记录类型不匹配" }
            if (head.deleted) return TheaterBookmark(profileId, playId, title)
            return decode(head.payload).also {
                require(it.profileId == profileId && it.playId == playId) { "剧场收藏不属于当前存档或剧目" }
            }
        }
    }
}
