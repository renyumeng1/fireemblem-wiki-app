package com.fireemblem.companion.agent

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ThinkingConfigAdaptive
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUseBlockParam
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Official Anthropic Java SDK (`com.anthropic:anthropic-java:2.34.0`) client implementation.
 * Uses streaming Messages API with adaptive thinking & Effort control (default `claude-opus-5-5`).
 */
class AnthropicAgentClient(
    private val config: AgentProviderConfig,
    private val clientOverride: AnthropicClient? = null,
) : AgentLlmClient {
    private val sdkClient: AnthropicClient by lazy {
        clientOverride ?: AnthropicOkHttpClient.builder()
            .apiKey(config.apiKey.trim())
            .apply {
                val base = config.baseUrl.trim().trimEnd('/')
                if (base.isNotEmpty() && base != "https://api.anthropic.com") {
                    baseUrl(base)
                }
            }
            .build()
    }

    override suspend fun complete(
        systemPrompt: String,
        messages: List<AgentTurnMessage>,
        tools: List<AgentToolDefinition>,
        onTextDelta: (String) -> Unit,
    ): AgentLlmResponse = withContext(Dispatchers.IO) {
        val paramsBuilder = MessageCreateParams.builder()
            .model(config.modelId.trim().ifBlank { AgentProviderConfig.PRESET_CLAUDE.defaultModelId })
            .maxTokens(8_192L)
            .system(systemPrompt)

        val effort = when (config.thinkingEffort.trim().lowercase()) {
            "low" -> OutputConfig.Effort.LOW
            "high" -> OutputConfig.Effort.HIGH
            else -> OutputConfig.Effort.MEDIUM
        }
        val modelLower = config.modelId.trim().lowercase()
        if (modelLower.contains("opus-5") || modelLower.contains("sonnet-5") || modelLower.contains("opus-4-6") || modelLower.contains("sonnet-4-6") || modelLower.contains("fable")) {
            paramsBuilder.thinking(ThinkingConfigAdaptive.builder().build())
            paramsBuilder.outputConfig(OutputConfig.builder().effort(effort).build())
        }

        tools.forEach { def -> paramsBuilder.addTool(toAnthropicTool(def)) }

        // Group consecutive ToolResult turns into a single USER MessageParam per Anthropic API contract
        var idx = 0
        while (idx < messages.size) {
            when (val msg = messages[idx]) {
                is AgentTurnMessage.User -> {
                    paramsBuilder.addUserMessage(msg.text)
                    idx++
                }
                is AgentTurnMessage.Assistant -> {
                    if (msg.toolCalls.isEmpty()) {
                        paramsBuilder.addAssistantMessage(msg.text.ifBlank { " " })
                    } else {
                        val blocks = mutableListOf<ContentBlockParam>()
                        if (msg.text.isNotBlank()) {
                            blocks += ContentBlockParam.ofText(TextBlockParam.builder().text(msg.text).build())
                        }
                        msg.toolCalls.forEach { tc ->
                            blocks += ContentBlockParam.ofToolUse(
                                ToolUseBlockParam.builder()
                                    .id(tc.id)
                                    .name(tc.name)
                                    .input(JsonValue.from(jsonObjectToJavaMap(tc.arguments)))
                                    .build(),
                            )
                        }
                        paramsBuilder.addMessage(
                            MessageParam.builder()
                                .role(MessageParam.Role.ASSISTANT)
                                .contentOfBlockParams(blocks)
                                .build(),
                        )
                    }
                    idx++
                }
                is AgentTurnMessage.ToolResult -> {
                    val resultBlocks = mutableListOf<ContentBlockParam>()
                    while (idx < messages.size && messages[idx] is AgentTurnMessage.ToolResult) {
                        val tr = messages[idx] as AgentTurnMessage.ToolResult
                        resultBlocks += ContentBlockParam.ofToolResult(
                            ToolResultBlockParam.builder()
                                .toolUseId(tr.toolCallId)
                                .content(tr.contentJson)
                                .isError(tr.isError)
                                .build(),
                        )
                        idx++
                    }
                    paramsBuilder.addMessage(
                        MessageParam.builder()
                            .role(MessageParam.Role.USER)
                            .contentOfBlockParams(resultBlocks)
                            .build(),
                    )
                }
            }
        }

        val textBuf = StringBuilder()
        val toolAccumByIndex = linkedMapOf<Long, Triple<String, String, StringBuilder>>()
        var stopReason = "end_turn"

        sdkClient.messages().createStreaming(paramsBuilder.build()).use { streamResponse ->
            val iterator = streamResponse.stream().iterator()
            while (iterator.hasNext()) {
                if (!currentCoroutineContext().isActive) throw CancellationException()
                val event = iterator.next()
                event.contentBlockStart().ifPresent { start ->
                    start.contentBlock().toolUse().ifPresent { tu ->
                        toolAccumByIndex[start.index()] = Triple(tu.id(), tu.name(), StringBuilder())
                    }
                }
                event.contentBlockDelta().ifPresent { deltaEvent ->
                    deltaEvent.delta().text().ifPresent { td ->
                        val chunk = td.text()
                        if (chunk.isNotEmpty()) {
                            textBuf.append(chunk)
                            onTextDelta(chunk)
                        }
                    }
                    deltaEvent.delta().inputJson().ifPresent { jd ->
                        toolAccumByIndex[deltaEvent.index()]?.third?.append(jd.partialJson())
                    }
                }
                event.messageDelta().ifPresent { md ->
                    md.delta().stopReason().ifPresent { sr -> stopReason = sr.asString() }
                }
            }
        }

        val parsedCalls = toolAccumByIndex.values.map { (id, name, rawJson) ->
            val argsObj = parseJsonObjectSafely(rawJson.toString())
            AgentToolCall(id = id, name = name, arguments = argsObj)
        }

        AgentLlmResponse(
            text = textBuf.toString(),
            toolCalls = parsedCalls,
            stopReason = stopReason,
        )
    }

    private fun toAnthropicTool(def: AgentToolDefinition): Tool {
        val propsBuilder = Tool.InputSchema.Properties.builder()
        val rawProps = def.inputSchema["properties"] as? JsonObject
        rawProps?.forEach { (k, v) ->
            propsBuilder.putAdditionalProperty(k, JsonValue.from(jsonElementToJava(v)))
        }
        val required = (def.inputSchema["required"] as? JsonArray)
            ?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
            .orEmpty()
        val schemaBuilder = Tool.InputSchema.builder().properties(propsBuilder.build())
        if (required.isNotEmpty()) schemaBuilder.required(required)
        return Tool.builder()
            .name(def.name)
            .description(def.description)
            .inputSchema(schemaBuilder.build())
            .build()
    }
}

/**
 * OpenAI Chat Completions compatible SSE streaming client supporting DeepSeek, Qwen (DashScope),
 * SiliconFlow, and any custom OpenAI-compatible HTTPS endpoint.
 */
class OpenAiCompatAgentClient(
    private val config: AgentProviderConfig,
    private val httpClient: OkHttpClient = defaultAgentHttpClient(),
) : AgentLlmClient {
    override suspend fun complete(
        systemPrompt: String,
        messages: List<AgentTurnMessage>,
        tools: List<AgentToolDefinition>,
        onTextDelta: (String) -> Unit,
    ): AgentLlmResponse = withContext(Dispatchers.IO) {
        val endpoint = resolveChatCompletionsUrl(config.baseUrl)
        if (!AgentProviderConfig.isAllowedEndpointUrl(endpoint)) {
            throw IOException("公网接口必须使用 https://，仅局域网私网地址允许 http://")
        }
        val payload = buildJsonObject {
            put("model", config.modelId.trim())
            put("stream", true)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                messages.forEach { msg ->
                    when (msg) {
                        is AgentTurnMessage.User -> add(buildJsonObject {
                            put("role", "user")
                            put("content", msg.text)
                        })
                        is AgentTurnMessage.Assistant -> add(buildJsonObject {
                            put("role", "assistant")
                            if (msg.text.isNotEmpty()) put("content", msg.text)
                            else if (msg.toolCalls.isEmpty()) put("content", "")
                            if (msg.toolCalls.isNotEmpty()) {
                                put("tool_calls", buildJsonArray {
                                    msg.toolCalls.forEach { tc ->
                                        add(buildJsonObject {
                                            put("id", tc.id)
                                            put("type", "function")
                                            put("function", buildJsonObject {
                                                put("name", tc.name)
                                                put("arguments", tc.arguments.toString())
                                            })
                                        })
                                    }
                                })
                            }
                        })
                        is AgentTurnMessage.ToolResult -> add(buildJsonObject {
                            put("role", "tool")
                            put("tool_call_id", msg.toolCallId)
                            put("content", msg.contentJson)
                        })
                    }
                }
            })
            if (tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    tools.forEach { def ->
                        add(buildJsonObject {
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", def.name)
                                put("description", def.description)
                                put("parameters", def.inputSchema)
                            })
                        })
                    }
                })
            }
        }

        val reqBuilder = Request.Builder()
            .url(endpoint)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "text/event-stream, application/json")
        if (config.apiKey.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer ${config.apiKey.trim()}")
        }

        val call = httpClient.newCall(reqBuilder.build())
        call.execute().use { response ->
            if (!response.isSuccessful) {
                val errBody = runCatching { response.body?.string()?.take(240) }.getOrNull().orEmpty()
                throw IOException("模型接口返回 HTTP ${response.code}${if (errBody.isNotBlank()) "：$errBody" else ""}")
            }
            val body = response.body ?: throw IOException("模型接口响应为空")
            val contentType = response.header("Content-Type").orEmpty().lowercase()

            // Fallback if proxy ignores `stream: true` and returns single JSON object
            if (contentType.contains("application/json")) {
                return@withContext parseSingleJsonResponse(body.string(), onTextDelta)
            }

            val textBuf = StringBuilder()
            val toolAccum = linkedMapOf<Int, MutableToolAccum>()
            var finishReason = "stop"

            body.charStream().buffered().use { reader ->
                while (true) {
                    if (!currentCoroutineContext().isActive) {
                        call.cancel()
                        throw CancellationException()
                    }
                    val line = reader.readLine() ?: break
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || !trimmed.startsWith("data:")) continue
                    val data = trimmed.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val chunkObj = runCatching { Json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                    val choice = (chunkObj["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: continue
                    choice["finish_reason"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content?.let {
                        finishReason = it
                    }
                    val delta = choice["delta"] as? JsonObject ?: continue
                    delta["content"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content?.let { txt ->
                        if (txt.isNotEmpty()) {
                            textBuf.append(txt)
                            onTextDelta(txt)
                        }
                    }
                    (delta["tool_calls"] as? JsonArray)?.forEach { tcEl ->
                        val tcObj = tcEl as? JsonObject ?: return@forEach
                        val idx = tcObj["index"]?.jsonPrimitive?.intOrNull ?: 0
                        val acc = toolAccum.getOrPut(idx) { MutableToolAccum() }
                        tcObj["id"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let {
                            acc.id = it
                        }
                        val fn = tcObj["function"] as? JsonObject
                        fn?.get("name")?.takeUnless { it is JsonNull }?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let {
                            acc.name = it
                        }
                        fn?.get("arguments")?.takeUnless { it is JsonNull }?.jsonPrimitive?.content?.let {
                            acc.args.append(it)
                        }
                    }
                }
            }

            val parsedCalls = toolAccum.entries.sortedBy { it.key }.mapNotNull { (idx, acc) ->
                if (acc.name.isBlank()) null
                else AgentToolCall(
                    id = acc.id.ifBlank { "call_$idx" },
                    name = acc.name,
                    arguments = parseJsonObjectSafely(acc.args.toString()),
                )
            }
            val normalizedStop = when (finishReason) {
                "tool_calls" -> "tool_use"
                "length" -> "max_tokens"
                else -> "end_turn"
            }
            AgentLlmResponse(text = textBuf.toString(), toolCalls = parsedCalls, stopReason = normalizedStop)
        }
    }

    private fun parseSingleJsonResponse(raw: String, onTextDelta: (String) -> Unit): AgentLlmResponse {
        val root = Json.parseToJsonElement(raw).jsonObject
        val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return AgentLlmResponse("")
        val msg = choice["message"]?.jsonObject ?: return AgentLlmResponse("")
        val text = msg["content"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content.orEmpty()
        if (text.isNotEmpty()) onTextDelta(text)
        val calls = (msg["tool_calls"] as? JsonArray).orEmpty().mapIndexedNotNull { idx, el ->
            val obj = el as? JsonObject ?: return@mapIndexedNotNull null
            val fn = obj["function"] as? JsonObject ?: return@mapIndexedNotNull null
            val name = fn["name"]?.jsonPrimitive?.content.orEmpty()
            if (name.isBlank()) return@mapIndexedNotNull null
            val argsStr = fn["arguments"]?.jsonPrimitive?.content.orEmpty()
            AgentToolCall(
                id = obj["id"]?.jsonPrimitive?.content ?: "call_$idx",
                name = name,
                arguments = parseJsonObjectSafely(argsStr),
            )
        }
        val finish = choice["finish_reason"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content ?: "stop"
        return AgentLlmResponse(
            text = text,
            toolCalls = calls,
            stopReason = if (finish == "tool_calls") "tool_use" else "end_turn",
        )
    }

    private class MutableToolAccum(
        var id: String = "",
        var name: String = "",
        val args: StringBuilder = StringBuilder(),
    )

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun resolveChatCompletionsUrl(baseUrl: String): String {
            val clean = baseUrl.trim().trimEnd('/')
            return if (clean.endsWith("/chat/completions")) clean else "$clean/chat/completions"
        }
    }
}

object AgentClientFactory {
    fun create(
        config: AgentProviderConfig,
        httpClient: OkHttpClient? = null,
    ): AgentLlmClient = when (config.provider) {
        AgentProviderKind.ANTHROPIC -> AnthropicAgentClient(config)
        AgentProviderKind.OPENAI_COMPAT -> OpenAiCompatAgentClient(
            config = config,
            httpClient = httpClient ?: defaultAgentHttpClient(),
        )
    }

    suspend fun testConnection(
        config: AgentProviderConfig,
        httpClient: OkHttpClient? = null,
    ): String {
        val client = create(config, httpClient)
        val resp = client.complete(
            systemPrompt = "You are a connectivity test. Reply with OK.",
            messages = listOf(AgentTurnMessage.User("Reply OK")),
            tools = emptyList(),
        )
        return resp.text.trim().ifBlank { "连接正常" }.take(60)
    }
}

internal fun defaultAgentHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(90, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .followRedirects(true)
    .addInterceptor { chain ->
        val req = chain.request()
        if (!req.url.isHttps && !AgentProviderConfig.isLocalEndpoint(req.url.toString())) {
            throw IOException("拒绝向公网地址发起明文 HTTP 请求：${req.url.host}")
        }
        chain.proceed(req)
    }
    .build()

private fun parseJsonObjectSafely(raw: String): JsonObject {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return buildJsonObject {}
    return runCatching { Json.parseToJsonElement(trimmed).jsonObject }
        .getOrElse { buildJsonObject {} }
}

private fun jsonObjectToJavaMap(obj: JsonObject): Map<String, Any?> =
    obj.mapValues { (_, v) -> jsonElementToJava(v) }

private fun jsonElementToJava(el: JsonElement): Any? = when (el) {
    is JsonNull -> null
    is JsonPrimitive -> when {
        el.isString -> el.content
        el.booleanOrNull != null -> el.booleanOrNull
        el.longOrNull != null -> el.longOrNull
        el.doubleOrNull != null -> el.doubleOrNull
        else -> el.content
    }
    is JsonArray -> el.map { jsonElementToJava(it) }
    is JsonObject -> jsonObjectToJavaMap(el)
}
