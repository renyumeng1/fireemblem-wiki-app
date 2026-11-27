package com.fireemblem.companion.importer

import com.fireemblem.companion.personal.PersonalRevision
import com.fireemblem.companion.personal.PersonalSnapshot
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class FefwGrowthSeedSourceArchive(
    val sourceSha256: String,
    val originalSize: Int,
    val dataBase64: String,
) {
    fun validated(): FefwGrowthSeedSourceArchive {
        require(SHA256_PATTERN.matches(sourceSha256)) { "原始成长文件摘要无效" }
        require(originalSize in 1..FefwGrowthSeedImporter.MAX_BYTES) { "原始成长文件大小无效" }
        require(dataBase64.length <= MAX_BASE64_CHARS) { "原始成长文件超过个人存档容量" }
        val bytes = try {
            Base64.getDecoder().decode(dataBase64)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("原始成长文件编码无效", error)
        }
        try {
            require(bytes.size == originalSize && Base64.getEncoder().encodeToString(bytes) == dataBase64) {
                "原始成长文件长度或编码不一致"
            }
            require(sha256(bytes) == sourceSha256) { "原始成长文件校验失败" }
        } finally {
            bytes.fill(0)
        }
        return this
    }

    fun restoreBytes(): ByteArray {
        validated()
        return Base64.getDecoder().decode(dataBase64)
    }

    fun toPayload(): JsonObject = buildJsonObject {
        put("format", FORMAT)
        put("version", 1)
        put("sourceSha256", sourceSha256)
        put("originalSize", originalSize)
        put("dataBase64", dataBase64)
    }

    fun toRevision(): PersonalRevision {
        validated()
        return PersonalRevision(
            revisionId = stableId("revision:$sourceSha256"),
            entityId = stableId("entity:$sourceSha256"),
            kind = KIND,
            deviceId = ARCHIVE_DEVICE_ID,
            parents = emptyList(),
            deleted = false,
            modifiedAt = 0,
            payload = toPayload(),
        )
    }

    companion object {
        const val KIND = "growth-seed-source"
        private const val FORMAT = "fefw-growth-seed-source"
        private const val MAX_BASE64_CHARS = ((FefwGrowthSeedImporter.MAX_BYTES + 2) / 3) * 4
        private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
        private val ARCHIVE_DEVICE_ID = stableId("device:fefw-growth-seed-source")

        fun fromBytes(bytes: ByteArray): FefwGrowthSeedSourceArchive {
            require(bytes.size in 1..FefwGrowthSeedImporter.MAX_BYTES) { "成长种子文件不能超过 10 MiB" }
            return FefwGrowthSeedSourceArchive(
                sourceSha256 = sha256(bytes),
                originalSize = bytes.size,
                dataBase64 = Base64.getEncoder().encodeToString(bytes),
            )
        }

        fun fromPayload(payload: JsonObject): FefwGrowthSeedSourceArchive {
            require(payload["format"]?.jsonPrimitive?.content == FORMAT && payload["version"]?.jsonPrimitive?.int == 1) {
                "不支持的原始成长文件存档格式"
            }
            return FefwGrowthSeedSourceArchive(
                sourceSha256 = payload["sourceSha256"]?.jsonPrimitive?.content ?: error("原始成长文件缺少摘要"),
                originalSize = payload["originalSize"]?.jsonPrimitive?.int ?: error("原始成长文件缺少长度"),
                dataBase64 = payload["dataBase64"]?.jsonPrimitive?.content ?: error("原始成长文件内容缺失"),
            ).validated()
        }

        fun find(snapshot: PersonalSnapshot, sourceSha256: String): FefwGrowthSeedSourceArchive? {
            if (!SHA256_PATTERN.matches(sourceSha256)) return null
            val current = snapshot.heads().values.flatten().filter {
                it.kind == KIND && !it.deleted && it.payload["sourceSha256"]?.jsonPrimitive?.content == sourceSha256
            }
            if (current.isEmpty()) return null
            require(current.size == 1) { "原始成长文件档案存在冲突，未导出任何版本" }
            return fromPayload(current.single().payload)
        }

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

        private fun stableId(value: String): String = UUID.nameUUIDFromBytes(value.toByteArray(Charsets.UTF_8)).toString()
    }
}
