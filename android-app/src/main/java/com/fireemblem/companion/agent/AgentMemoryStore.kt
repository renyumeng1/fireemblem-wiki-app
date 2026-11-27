package com.fireemblem.companion.agent

import android.content.Context
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Transparent, user-editable persistent Memory Book inspired by Pi's `pi-session-memory`
 * plus hybrid `/compact` conversation summarization routed to the configured Memory Model.
 */
class AgentMemoryStore(private val memoryFile: File) {
    constructor(context: Context) : this(File(context.filesDir, "agent-memory/memories.json"))

    private val lock = Any()

    fun list(): List<AgentMemoryEntry> = synchronized(lock) {
        if (!memoryFile.isFile) return emptyList()
        runCatching {
            val root = Json.parseToJsonElement(memoryFile.readText(Charsets.UTF_8)).jsonObject
            (root["entries"] as? JsonArray).orEmpty().mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val content = obj["content"]?.jsonPrimitive?.content?.trim().orEmpty()
                if (content.isEmpty()) return@mapNotNull null
                AgentMemoryEntry(
                    id = obj["id"]?.jsonPrimitive?.content ?: UUID.randomUUID().toString().take(8),
                    category = obj["category"]?.jsonPrimitive?.content ?: "note",
                    content = content,
                    updatedAtMillis = obj["updatedAtMillis"]?.jsonPrimitive?.longOrNull ?: 0L,
                )
            }.sortedByDescending { it.updatedAtMillis }
        }.getOrDefault(emptyList())
    }

    fun add(category: String, content: String): AgentMemoryEntry = synchronized(lock) {
        val clean = content.trim().take(160)
        require(clean.isNotEmpty()) { "记忆内容不能为空" }
        val normCat = normalizeCategory(category)
        val current = list().toMutableList()
        val existing = current.firstOrNull { it.content.equals(clean, ignoreCase = true) }
        if (existing != null) {
            val refreshed = existing.copy(category = normCat, updatedAtMillis = System.currentTimeMillis())
            current.removeAll { it.id == existing.id }
            current.add(0, refreshed)
            writeAll(current)
            return refreshed
        }
        val entry = AgentMemoryEntry(
            id = UUID.randomUUID().toString().take(8),
            category = normCat,
            content = clean,
            updatedAtMillis = System.currentTimeMillis(),
        )
        current.add(0, entry)
        writeAll(current.take(60))
        entry
    }

    fun update(id: String, category: String, content: String): Boolean = synchronized(lock) {
        val clean = content.trim().take(160)
        if (clean.isEmpty()) return false
        val current = list().toMutableList()
        val idx = current.indexOfFirst { it.id == id }
        if (idx < 0) return false
        current[idx] = current[idx].copy(
            category = normalizeCategory(category),
            content = clean,
            updatedAtMillis = System.currentTimeMillis(),
        )
        writeAll(current)
        true
    }

    fun remove(id: String): Boolean = synchronized(lock) {
        val current = list()
        val next = current.filterNot { it.id == id }
        if (next.size == current.size) return false
        writeAll(next)
        true
    }

    fun clear() = synchronized(lock) {
        if (memoryFile.exists()) memoryFile.delete()
    }

    private fun writeAll(entries: List<AgentMemoryEntry>) {
        val parent = memoryFile.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        val json = buildJsonObject {
            put("version", 1)
            put("entries", buildJsonArray {
                entries.forEach { e ->
                    add(buildJsonObject {
                        put("id", e.id)
                        put("category", e.category)
                        put("content", e.content)
                        put("updatedAtMillis", e.updatedAtMillis)
                    })
                }
            })
        }.toString()
        val tmp = File(memoryFile.parentFile, "${memoryFile.name}.tmp")
        tmp.writeText(json, Charsets.UTF_8)
        if (memoryFile.exists()) memoryFile.delete()
        check(tmp.renameTo(memoryFile)) { "无法写入参谋记忆本" }
    }

    companion object {
        const val AUTO_COMPACT_MESSAGE_THRESHOLD = 12
        const val AUTO_COMPACT_CHAR_THRESHOLD = 9_000
        private const val AUTO_PREFS_NAME = "agent-automation-prefs"
        private const val KEY_AUTO_MEMORY = "auto_memory_enabled"
        private const val KEY_AUTO_COMPACT = "auto_compact_enabled"

        private val MEMORY_SIGNAL_KEYWORDS = listOf(
            "偏好", "优先", "跳过", "主玩", "一直", "以后", "习惯", "记住",
            "培养", "路线", "规划", "主力", "不要", "尽量", "固定", "目标",
        )

        fun isAutoMemoryEnabled(context: Context): Boolean =
            context.getSharedPreferences(AUTO_PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO_MEMORY, true)

        fun setAutoMemoryEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(AUTO_PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_AUTO_MEMORY, enabled).apply()
        }

        fun isAutoCompactEnabled(context: Context): Boolean =
            context.getSharedPreferences(AUTO_PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO_COMPACT, true)

        fun setAutoCompactEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(AUTO_PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_AUTO_COMPACT, enabled).apply()
        }

        fun estimateHistoryChars(summary: String?, history: List<AgentTurnMessage>): Int {
            var total = summary?.length ?: 0
            for (m in history) {
                total += when (m) {
                    is AgentTurnMessage.User -> m.text.length
                    is AgentTurnMessage.Assistant -> m.text.length + m.toolCalls.sumOf { it.name.length + it.arguments.toString().length }
                    is AgentTurnMessage.ToolResult -> m.contentJson.length
                }
            }
            return total
        }

        fun shouldAutoCompact(summary: String?, history: List<AgentTurnMessage>): Boolean {
            if (history.size <= 6) return false
            return history.size >= AUTO_COMPACT_MESSAGE_THRESHOLD ||
                estimateHistoryChars(summary, history) >= AUTO_COMPACT_CHAR_THRESHOLD
        }

        fun shouldAutoExtractMemories(
            bubblesCount: Int,
            lastExtractedBubbleCount: Int,
            latestUserText: String,
            hadPlanProposal: Boolean,
        ): Boolean {
            if (bubblesCount < 2 || bubblesCount <= lastExtractedBubbleCount) return false
            if (bubblesCount - lastExtractedBubbleCount >= 4) return true
            if (hadPlanProposal) return true
            return MEMORY_SIGNAL_KEYWORDS.any { latestUserText.contains(it) }
        }

        fun normalizeCategory(raw: String?): String = when (raw?.trim()?.lowercase()) {
            "preference", "偏好" -> "preference"
            "build", "养成", "角色养成" -> "build"
            "roster", "路线", "队伍" -> "roster"
            else -> "note"
        }

        fun categoryLabel(cat: String): String = when (cat) {
            "preference" -> "养成偏好"
            "build" -> "角色规划"
            "roster" -> "路线队伍"
            else -> "备忘"
        }

        /**
         * Uses the dedicated Memory/Compaction model (or falls back to active chat model) to distill
         * durable player preferences/goals from a conversation transcript into [AgentMemoryStore].
         */
        suspend fun extractMemoriesWithModel(
            memoryClient: AgentLlmClient,
            transcript: String,
            store: AgentMemoryStore,
        ): List<AgentMemoryEntry> = withContext(Dispatchers.IO) {
            val existing = store.list()
            val existingLines = if (existing.isEmpty()) "（空）" else existing.joinToString("\n") { "- [${it.category}] ${it.content}" }
            val prompt = """
请从下面这段玩家与「战策参谋」的对话中，提炼**值得跨会话长期保留**的玩家偏好、路线目标或养成规则（例如：偏爱某属性权重、习惯跳过成长值<3的加点、正在重点培养某角色某职业、主玩某条路线等）。
不要记录一次性闲聊或已存在于旧记忆中的重复内容。

已有记忆：
$existingLines

对话记录：
${transcript.take(6_000)}

严格只输出 JSON 数组，不要输出任何额外解释或代码块标记，格式如下：
[{"category":"preference|build|roster|note","content":"精炼的一句话（不超过60字）"}]
若没有值得新增的长期记忆，输出 []。
""".trimIndent()

            val resp = memoryClient.complete(
                systemPrompt = "你是严谨的记忆整理引擎，只输出合法 JSON 数组。",
                messages = listOf(AgentTurnMessage.User(prompt)),
                tools = emptyList(),
            )
            val raw = resp.text.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            val startIdx = raw.indexOf('[')
            val endIdx = raw.lastIndexOf(']')
            if (startIdx < 0 || endIdx <= startIdx) return@withContext emptyList()
            val arr = runCatching { Json.parseToJsonElement(raw.substring(startIdx, endIdx + 1)).jsonArray }.getOrNull()
                ?: return@withContext emptyList()
            val added = mutableListOf<AgentMemoryEntry>()
            arr.forEach { el ->
                val obj = el as? JsonObject ?: return@forEach
                val cat = obj["category"]?.jsonPrimitive?.content ?: "note"
                val content = obj["content"]?.jsonPrimitive?.content?.trim().orEmpty()
                if (content.length >= 4 && existing.none { it.content.equals(content, ignoreCase = true) }) {
                    added += store.add(cat, content)
                }
            }
            added
        }

        /**
         * Pi-style hybrid context compaction: preserves recent [keepRecentMessages] verbatim while
         * summarizing older history via the Memory/Compaction model (with deterministic fallback).
         */
        suspend fun compactHistory(
            memoryClient: AgentLlmClient?,
            existingSummary: String?,
            history: List<AgentTurnMessage>,
            keepRecentMessages: Int = 6,
        ): Pair<String, List<AgentTurnMessage>> = withContext(Dispatchers.IO) {
            if (history.size <= keepRecentMessages) {
                return@withContext (existingSummary.orEmpty() to history)
            }
            val splitAt = (history.size - keepRecentMessages).coerceAtLeast(1)
            val older = history.subList(0, splitAt)
            val recent = history.subList(splitAt, history.size)
            val olderText = buildString {
                if (!existingSummary.isNullOrBlank()) {
                    appendLine("【已有前序摘要】")
                    appendLine(existingSummary.trim())
                    appendLine()
                }
                older.forEach { m ->
                    when (m) {
                        is AgentTurnMessage.User -> appendLine("玩家：${m.text.take(400)}")
                        is AgentTurnMessage.Assistant -> if (m.text.isNotBlank()) appendLine("参谋：${m.text.take(500)}")
                        is AgentTurnMessage.ToolResult -> appendLine("工具(${m.toolName})：${m.contentJson.take(260)}")
                    }
                }
            }

            val summary = if (memoryClient != null) {
                runCatching {
                    val resp = memoryClient.complete(
                        systemPrompt = "你是上下文压缩引擎。请将历史对话压缩为简洁要点（不超过 400 字），务必保留涉及的角色名、种子号、等级、路线、属性权重、跳过规则与已得出的关键结论。",
                        messages = listOf(AgentTurnMessage.User(olderText.take(8_000))),
                        tools = emptyList(),
                    )
                    resp.text.trim()
                }.getOrNull().orEmpty()
            } else ""

            val resolvedSummary = summary.ifBlank {
                // Deterministic prose-preserving fallback (Pi transparent-compact style)
                older.filterIsInstance<AgentTurnMessage.User>()
                    .joinToString("；") { it.text.take(60) }
                    .let { "早前讨论要点：$it" }
            }
            resolvedSummary to recent
        }
    }
}
