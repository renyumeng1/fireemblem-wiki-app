package com.fireemblem.companion.agent

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

enum class AgentProviderKind(val id: String, val label: String) {
    ANTHROPIC("anthropic", "Anthropic Claude"),
    OPENAI_COMPAT("openai_compat", "OpenAI 兼容接口"),
    ;

    companion object {
        fun fromId(id: String?): AgentProviderKind =
            entries.firstOrNull { it.id == id } ?: OPENAI_COMPAT
    }
}

data class AgentProviderPreset(
    val id: String,
    val label: String,
    val provider: AgentProviderKind,
    val defaultBaseUrl: String,
    val defaultModelId: String,
    val note: String,
)

data class AgentProviderConfig(
    val provider: AgentProviderKind = AgentProviderKind.OPENAI_COMPAT,
    val presetId: String = PRESET_DEEPSEEK.id,
    val baseUrl: String = PRESET_DEEPSEEK.defaultBaseUrl,
    val apiKey: String = "",
    val modelId: String = PRESET_DEEPSEEK.defaultModelId,
    val thinkingEffort: String = "medium",
    val enableVisionForOcr: Boolean = false,
) {
    val isReadyToChat: Boolean
        get() {
            if (modelId.isBlank()) return false
            return when (provider) {
                AgentProviderKind.ANTHROPIC -> apiKey.isNotBlank()
                AgentProviderKind.OPENAI_COMPAT -> baseUrl.isNotBlank() && (apiKey.isNotBlank() || isLocalEndpoint(baseUrl))
            }
        }

    fun displaySummary(): String {
        val presetName = ALL_PRESETS.firstOrNull { it.id == presetId }?.label ?: provider.label
        return "$presetName · ${modelId.ifBlank { "未指定模型" }}"
    }

    companion object {
        val PRESET_CLAUDE = AgentProviderPreset(
            id = "claude",
            label = "Claude",
            provider = AgentProviderKind.ANTHROPIC,
            defaultBaseUrl = "https://api.anthropic.com",
            defaultModelId = "claude-opus-5-5",
            note = "官方 Anthropic Messages API（默认 Opus 5.5，支持自适应思考）",
        )
        val PRESET_DEEPSEEK = AgentProviderPreset(
            id = "deepseek",
            label = "DeepSeek",
            provider = AgentProviderKind.OPENAI_COMPAT,
            defaultBaseUrl = "https://api.deepseek.com/v1",
            defaultModelId = "deepseek-chat",
            note = "国内直连，性价比高，支持完整 Function Calling",
        )
        val PRESET_QWEN = AgentProviderPreset(
            id = "qwen",
            label = "阿里百炼 (千问)",
            provider = AgentProviderKind.OPENAI_COMPAT,
            defaultBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            defaultModelId = "qwen-plus",
            note = "阿里云百炼 OpenAI 兼容模式，国内直连稳定",
        )
        val PRESET_SILICONFLOW = AgentProviderPreset(
            id = "siliconflow",
            label = "硅基流动",
            provider = AgentProviderKind.OPENAI_COMPAT,
            defaultBaseUrl = "https://api.siliconflow.cn/v1",
            defaultModelId = "Qwen/Qwen2.5-72B-Instruct",
            note = "聚合多款开源中文大模型",
        )
        val PRESET_CUSTOM = AgentProviderPreset(
            id = "custom",
            label = "自定义接口",
            provider = AgentProviderKind.OPENAI_COMPAT,
            defaultBaseUrl = "",
            defaultModelId = "",
            note = "兼容 OpenAI 的 HTTPS 服务或局域网本地网关（192.168.x.x / localhost）",
        )

        val ALL_PRESETS: List<AgentProviderPreset> = listOf(
            PRESET_DEEPSEEK,
            PRESET_QWEN,
            PRESET_CLAUDE,
            PRESET_SILICONFLOW,
            PRESET_CUSTOM,
        )

        fun isLocalEndpoint(url: String): Boolean {
            val host = runCatching { java.net.URI(url.trim()).host?.lowercase().orEmpty() }.getOrDefault("")
            if (host.isEmpty()) return false
            if (host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "10.0.2.2" || host.endsWith(".local")) {
                return true
            }
            val octets = host.split('.')
            if (octets.size == 4) {
                val a = octets[0].toIntOrNull() ?: return false
                val b = octets[1].toIntOrNull() ?: return false
                val c = octets[2].toIntOrNull() ?: return false
                val d = octets[3].toIntOrNull() ?: return false
                if (a !in 0..255 || b !in 0..255 || c !in 0..255 || d !in 0..255) return false
                return a == 10 ||
                    (a == 192 && b == 168) ||
                    (a == 172 && b in 16..31) ||
                    (a == 100 && b in 64..127) ||
                    a == 127
            }
            return false
        }

        fun isAllowedEndpointUrl(url: String): Boolean {
            val trimmed = url.trim().lowercase()
            return trimmed.startsWith("https://") ||
                (trimmed.startsWith("http://") && isLocalEndpoint(trimmed))
        }
    }
}

/**
 * Named model profile in the Pi-inspired multi-model registry.
 * Users can configure multiple profiles and route roles independently (Chat vs Memory/Compaction).
 */
data class AgentModelProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val provider: AgentProviderKind = AgentProviderKind.OPENAI_COMPAT,
    val presetId: String = AgentProviderConfig.PRESET_DEEPSEEK.id,
    val baseUrl: String = AgentProviderConfig.PRESET_DEEPSEEK.defaultBaseUrl,
    val apiKey: String = "",
    val modelId: String = AgentProviderConfig.PRESET_DEEPSEEK.defaultModelId,
    val thinkingEffort: String = "medium",
    val enableVisionForOcr: Boolean = false,
) {
    fun toProviderConfig(): AgentProviderConfig = AgentProviderConfig(
        provider = provider,
        presetId = presetId,
        baseUrl = baseUrl,
        apiKey = apiKey,
        modelId = modelId,
        thinkingEffort = thinkingEffort,
        enableVisionForOcr = enableVisionForOcr,
    )

    val isReady: Boolean get() = toProviderConfig().isReadyToChat

    fun shortLabel(): String = name.ifBlank { modelId.ifBlank { presetId } }

    companion object {
        fun fromConfig(config: AgentProviderConfig, id: String = "default", name: String? = null): AgentModelProfile {
            val autoName = name?.takeIf { it.isNotBlank() } ?: config.displaySummary()
            return AgentModelProfile(
                id = id,
                name = autoName,
                provider = config.provider,
                presetId = config.presetId,
                baseUrl = config.baseUrl,
                apiKey = config.apiKey,
                modelId = config.modelId,
                thinkingEffort = config.thinkingEffort,
                enableVisionForOcr = config.enableVisionForOcr,
            )
        }
    }
}

data class AgentModelRegistry(
    val profiles: List<AgentModelProfile> = emptyList(),
    val chatProfileId: String? = null,
    val memoryProfileId: String? = null,
) {
    val activeChatProfile: AgentModelProfile?
        get() = profiles.firstOrNull { it.id == chatProfileId && it.isReady }
            ?: profiles.firstOrNull { it.isReady }

    val activeMemoryProfile: AgentModelProfile?
        get() = profiles.firstOrNull { it.id == memoryProfileId && it.isReady }
            ?: activeChatProfile

    val hasAnyReadyProfile: Boolean
        get() = activeChatProfile != null
}

/**
 * Hardware-backed AndroidKeyStore AES-256-GCM storage for BYOK multi-model profiles.
 * Seamlessly migrates v1 single-config payloads to v2 multi-model registry without losing keys.
 */
class SecureAgentSettings(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val alias = KEYSTORE_ALIAS

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(alias, null) as? SecretKey)
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(
                    KeyGenParameterSpec.Builder(
                        alias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build(),
                )
                generateKey()
            }
    }

    private fun validateProfile(profile: AgentModelProfile): AgentModelProfile {
        val trimmedBase = profile.baseUrl.trim().trimEnd('/')
        val trimmedModel = profile.modelId.trim()
        require(trimmedModel.isNotEmpty()) { "请填写模型 ID" }
        if (profile.provider == AgentProviderKind.OPENAI_COMPAT) {
            require(AgentProviderConfig.isAllowedEndpointUrl(trimmedBase)) {
                "公网接口必须使用 https://，仅本机或局域网私网（192.168.x.x / 10.x.x.x / localhost）允许 http://"
            }
        } else if (trimmedBase.isNotEmpty()) {
            require(AgentProviderConfig.isAllowedEndpointUrl(trimmedBase)) {
                "公网 Anthropic 接口必须使用 https://（仅局域网私网允许 http://）"
            }
        }
        return profile.copy(
            name = profile.name.trim().ifBlank { trimmedModel },
            baseUrl = trimmedBase,
            apiKey = profile.apiKey.trim(),
            modelId = trimmedModel,
            thinkingEffort = profile.thinkingEffort.trim().ifBlank { "medium" },
        )
    }

    fun saveRegistry(registry: AgentModelRegistry) {
        val validated = registry.profiles.map(::validateProfile)
        val validIds = validated.map { it.id }.toSet()
        val chatId = registry.chatProfileId?.takeIf { it in validIds } ?: validated.firstOrNull()?.id
        val memId = registry.memoryProfileId?.takeIf { it in validIds }

        val json = buildJsonObject {
            put("version", 2)
            chatId?.let { put("chatProfileId", it) }
            memId?.let { put("memoryProfileId", it) }
            put("profiles", buildJsonArray {
                validated.forEach { p ->
                    add(buildJsonObject {
                        put("id", p.id)
                        put("name", p.name)
                        put("provider", p.provider.id)
                        put("presetId", p.presetId)
                        put("baseUrl", p.baseUrl)
                        put("apiKey", p.apiKey)
                        put("modelId", p.modelId)
                        put("thinkingEffort", p.thinkingEffort)
                        put("enableVisionForOcr", p.enableVisionForOcr)
                    })
                }
            })
        }.toString().toByteArray(Charsets.UTF_8)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = try {
            cipher.iv + cipher.doFinal(json)
        } finally {
            json.fill(0)
        }
        check(preferences.edit().putString(KEY_PAYLOAD, Base64.getEncoder().encodeToString(encrypted)).commit()) {
            "无法保存 AI 模型配置"
        }
    }

    fun loadRegistry(): AgentModelRegistry {
        val saved = preferences.getString(KEY_PAYLOAD, null) ?: return AgentModelRegistry()
        val bytes = Base64.getDecoder().decode(saved)
        require(bytes.size > 28) { "AI 模型配置已损坏，请重新设置" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        }
        val plain = cipher.doFinal(bytes, 12, bytes.size - 12)
        return try {
            val root = Json.parseToJsonElement(plain.toString(Charsets.UTF_8)).jsonObject
            val version = root["version"]?.jsonPrimitive?.intOrNull ?: 1
            if (version >= 2 && root["profiles"] is JsonArray) {
                val profiles = root["profiles"]!!.jsonArray.mapNotNull { el ->
                    val obj = el as? JsonObject ?: return@mapNotNull null
                    AgentModelProfile(
                        id = obj["id"]?.jsonPrimitive?.content ?: UUID.randomUUID().toString(),
                        name = obj["name"]?.jsonPrimitive?.content.orEmpty(),
                        provider = AgentProviderKind.fromId(obj["provider"]?.jsonPrimitive?.content),
                        presetId = obj["presetId"]?.jsonPrimitive?.content ?: AgentProviderConfig.PRESET_CUSTOM.id,
                        baseUrl = obj["baseUrl"]?.jsonPrimitive?.content.orEmpty(),
                        apiKey = obj["apiKey"]?.jsonPrimitive?.content.orEmpty(),
                        modelId = obj["modelId"]?.jsonPrimitive?.content.orEmpty(),
                        thinkingEffort = obj["thinkingEffort"]?.jsonPrimitive?.content ?: "medium",
                        enableVisionForOcr = obj["enableVisionForOcr"]?.jsonPrimitive?.booleanOrNull == true,
                    )
                }
                AgentModelRegistry(
                    profiles = profiles,
                    chatProfileId = root["chatProfileId"]?.jsonPrimitive?.content,
                    memoryProfileId = root["memoryProfileId"]?.jsonPrimitive?.content,
                )
            } else {
                // Backward-compatible migration from v1 single config
                val legacy = AgentProviderConfig(
                    provider = AgentProviderKind.fromId(root["provider"]?.jsonPrimitive?.content),
                    presetId = root["presetId"]?.jsonPrimitive?.content ?: AgentProviderConfig.PRESET_CUSTOM.id,
                    baseUrl = root["baseUrl"]?.jsonPrimitive?.content.orEmpty(),
                    apiKey = root["apiKey"]?.jsonPrimitive?.content.orEmpty(),
                    modelId = root["modelId"]?.jsonPrimitive?.content.orEmpty(),
                    thinkingEffort = root["thinkingEffort"]?.jsonPrimitive?.content ?: "medium",
                    enableVisionForOcr = root["enableVisionForOcr"]?.jsonPrimitive?.booleanOrNull == true,
                )
                val migrated = AgentModelProfile.fromConfig(legacy, id = "migrated-v1")
                AgentModelRegistry(
                    profiles = listOf(migrated),
                    chatProfileId = migrated.id,
                    memoryProfileId = null,
                )
            }
        } finally {
            plain.fill(0)
        }
    }

    /** Legacy single-config helpers retained for backward compatibility. */
    fun save(config: AgentProviderConfig) {
        val current = runCatching { loadRegistry() }.getOrDefault(AgentModelRegistry())
        val existingId = current.activeChatProfile?.id ?: "default"
        val updatedProfile = AgentModelProfile.fromConfig(config, id = existingId)
        val others = current.profiles.filterNot { it.id == existingId }
        saveRegistry(
            current.copy(
                profiles = listOf(updatedProfile) + others,
                chatProfileId = updatedProfile.id,
            ),
        )
    }

    fun load(): AgentProviderConfig? = loadRegistry().activeChatProfile?.toProviderConfig()

    fun clear() {
        check(preferences.edit().clear().commit()) { "无法清除 AI 模型配置" }
    }

    companion object {
        private const val PREFS_NAME = "agent-credentials"
        private const val KEYSTORE_ALIAS = "fireemblem-agent-local-v1"
        private const val KEY_PAYLOAD = "encrypted"
    }
}
