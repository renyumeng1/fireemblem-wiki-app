package com.fireemblem.companion.personal

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class SecureSyncSettings(context: Context) {
    private val preferences = context.getSharedPreferences("sync-credentials", Context.MODE_PRIVATE)
    private val alias = "fireemblem-webdav-local-v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }

    fun save(settings: SyncSettings) {
        WebDavRemote(settings)
        require(settings.encryptionPassword.length >= 12) { "同步口令至少需要12个字符" }
        val json = buildJsonObject {
            put("url", settings.url); put("username", settings.username); put("password", settings.password)
            put("encryptionPassword", settings.encryptionPassword)
        }.toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = try { cipher.iv + cipher.doFinal(json) } finally { json.fill(0) }
        check(preferences.edit().putString("encrypted", Base64.getEncoder().encodeToString(encrypted)).commit()) { "无法保存同步设置" }
    }

    fun load(): SyncSettings? {
        val saved = preferences.getString("encrypted", null) ?: return null
        val bytes = Base64.getDecoder().decode(saved)
        require(bytes.size > 28) { "同步设置已损坏，请重新配置" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        }
        val plain = cipher.doFinal(bytes, 12, bytes.size - 12)
        return try {
            val root = Json.parseToJsonElement(plain.toString(Charsets.UTF_8)).jsonObject
            SyncSettings(root.getValue("url").jsonPrimitive.content, root.getValue("username").jsonPrimitive.content,
                root.getValue("password").jsonPrimitive.content, root.getValue("encryptionPassword").jsonPrimitive.content)
        } finally { plain.fill(0) }
    }

    fun clear() { check(preferences.edit().clear().commit()) { "无法清除同步设置" } }
}
