package com.fireemblem.companion.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fireemblem.companion.agent.AgentClientFactory
import com.fireemblem.companion.agent.AgentMemoryEntry
import com.fireemblem.companion.agent.AgentMemoryStore
import com.fireemblem.companion.agent.AgentModelProfile
import com.fireemblem.companion.agent.AgentModelRegistry
import com.fireemblem.companion.agent.AgentProviderConfig
import com.fireemblem.companion.agent.AgentProviderKind
import com.fireemblem.companion.agent.AgentSessionMeta
import com.fireemblem.companion.agent.SecureAgentSettings
import com.fireemblem.companion.content.StatRatingTier
import com.fireemblem.companion.ui.theme.tabularNumbers
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun AgentSettingsSummaryCard() {
    val context = LocalContext.current
    val secure = remember(context) { SecureAgentSettings(context) }
    var registry by remember { mutableStateOf(AgentModelRegistry()) }
    var dialogOpen by remember { mutableStateOf(false) }

    LaunchedEffect(context) {
        registry = withContext(Dispatchers.IO) { runCatching { secure.loadRegistry() }.getOrDefault(AgentModelRegistry()) }
    }

    if (dialogOpen) {
        AgentModelRegistryDialog(
            initial = registry,
            secureSettings = secure,
            onDismiss = { dialogOpen = false },
            onRegistryChanged = { next -> registry = next },
        )
    }

    EditorialCard(modifier = Modifier.fillMaxWidth().testTag("agent-settings-card")) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("战策参谋模型配置", style = MaterialTheme.typography.titleMedium)
                    val chat = registry.activeChatProfile
                    val mem = registry.activeMemoryProfile
                    Text(
                        when {
                            chat != null -> buildString {
                                append("主对话：${chat.shortLabel()}")
                                if (mem != null && mem.id != chat.id) append(" · 记忆/摘要：${mem.shortLabel()}")
                                append("（共 ${registry.profiles.size} 套，本机 Keystore 加密）")
                            }
                            else -> "未配置模型 Key；战策参谋的跨作离线检索与乱数跳过规划仍可免密钥使用。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            OutlinedButton(
                onClick = { dialogOpen = true },
                modifier = Modifier.heightIn(min = 44.dp).testTag("agent-settings-open"),
            ) {
                Icon(Icons.Outlined.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (registry.hasAnyReadyProfile) "管理多模型与角色分工" else "配置模型接口")
            }
        }
    }
}

@Composable
internal fun AgentModelRegistryDialog(
    initial: AgentModelRegistry,
    secureSettings: SecureAgentSettings,
    onDismiss: () -> Unit,
    onRegistryChanged: (AgentModelRegistry) -> Unit,
) {
    var registry by remember(initial) { mutableStateOf(initial) }
    var editingProfile by remember { mutableStateOf<AgentModelProfile?>(null) }
    var isAddingNew by remember { mutableStateOf(false) }
    var statusError by remember { mutableStateOf<String?>(null) }

    fun persist(next: AgentModelRegistry) {
        runCatching {
            if (next.profiles.isEmpty()) {
                secureSettings.clear()
            } else {
                secureSettings.saveRegistry(next)
            }
        }.onSuccess {
            registry = next
            statusError = null
            onRegistryChanged(next)
        }.onFailure { err ->
            statusError = err.message ?: "保存模型配置失败"
        }
    }

    if (editingProfile != null) {
        AgentProfileEditorDialog(
            initial = editingProfile!!,
            isNew = isAddingNew,
            onDismiss = {
                editingProfile = null
                isAddingNew = false
            },
            onSave = { savedProfile ->
                val list = registry.profiles.toMutableList()
                val idx = list.indexOfFirst { it.id == savedProfile.id }
                if (idx >= 0) list[idx] = savedProfile else list += savedProfile
                val nextChatId = registry.chatProfileId?.takeIf { id -> list.any { it.id == id } } ?: savedProfile.id
                val next = registry.copy(profiles = list, chatProfileId = nextChatId)
                persist(next)
                if (statusError == null) {
                    editingProfile = null
                    isAddingNew = false
                }
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("战策参谋 · 多模型配置") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "可配置多套模型并按角色分工：主对话模型负责策略分析与工具调用；记忆/摘要模型负责提炼长期记忆与上下文压缩（未单独指定时跟随主对话模型）。支持公网 HTTPS 及局域网本地 HTTP（192.168.x.x / localhost）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                statusError?.let { err ->
                    Text(err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }

                if (registry.profiles.isEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "暂无已保存的模型配置。点击下方「添加模型配置」即可接入 DeepSeek、千问、Claude 或局域网自建模型。",
                            Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    registry.profiles.forEach { prof ->
                        val isChat = registry.activeChatProfile?.id == prof.id
                        val isMemExplicit = registry.memoryProfileId == prof.id
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isChat) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                            else MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(
                                1.dp,
                                if (isChat) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            ),
                            modifier = Modifier.fillMaxWidth().testTag("agent-profile:${prof.id}"),
                        ) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            prof.shortLabel(),
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            "${prof.modelId} · ${prof.baseUrl.ifBlank { prof.provider.label }}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            isAddingNew = false
                                            editingProfile = prof
                                        },
                                        modifier = Modifier.size(36.dp),
                                    ) {
                                        Icon(Icons.Outlined.Edit, contentDescription = "编辑模型", modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(
                                        onClick = {
                                            val remaining = registry.profiles.filterNot { it.id == prof.id }
                                            persist(
                                                registry.copy(
                                                    profiles = remaining,
                                                    chatProfileId = registry.chatProfileId?.takeIf { id -> remaining.any { it.id == id } }
                                                        ?: remaining.firstOrNull()?.id,
                                                    memoryProfileId = registry.memoryProfileId?.takeIf { id -> remaining.any { it.id == id } },
                                                ),
                                            )
                                        },
                                        modifier = Modifier.size(36.dp),
                                    ) {
                                        Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除模型", modifier = Modifier.size(18.dp))
                                    }
                                }

                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Surface(
                                        onClick = { persist(registry.copy(chatProfileId = prof.id)) },
                                        shape = RoundedCornerShape(100.dp),
                                        color = if (isChat) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                        contentColor = if (isChat) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        border = BorderStroke(1.dp, if (isChat) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                                    ) {
                                        Text(
                                            if (isChat) "✓ 主对话模型" else "设为主对话",
                                            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                    Surface(
                                        onClick = {
                                            val nextMem = if (isMemExplicit) null else prof.id
                                            persist(registry.copy(memoryProfileId = nextMem))
                                        },
                                        shape = RoundedCornerShape(100.dp),
                                        color = if (isMemExplicit) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surface,
                                        contentColor = if (isMemExplicit) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        border = BorderStroke(1.dp, if (isMemExplicit) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant),
                                    ) {
                                        Text(
                                            if (isMemExplicit) "✓ 专用记忆/摘要模型" else "设为记忆/摘要",
                                            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Button(
                    onClick = {
                        isAddingNew = true
                        editingProfile = AgentModelProfile(
                            id = UUID.randomUUID().toString(),
                            name = "",
                            provider = AgentProviderKind.OPENAI_COMPAT,
                            presetId = AgentProviderConfig.PRESET_CUSTOM.id,
                            baseUrl = "",
                            apiKey = "",
                            modelId = "",
                        )
                    },
                    modifier = Modifier.fillMaxWidth().testTag("agent-add-profile"),
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("添加模型配置")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
        dismissButton = {
            if (registry.profiles.isNotEmpty()) {
                TextButton(
                    onClick = { persist(AgentModelRegistry()) },
                ) { Text("全部清空") }
            }
        },
    )
}

@Composable
private fun AgentProfileEditorDialog(
    initial: AgentModelProfile,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (AgentModelProfile) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial.name) }
    var presetId by remember { mutableStateOf(initial.presetId) }
    var provider by remember { mutableStateOf(initial.provider) }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var modelId by remember { mutableStateOf(initial.modelId) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var effort by remember { mutableStateOf(initial.thinkingEffort) }
    var testing by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "添加模型配置" else "编辑模型配置") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AgentProviderConfig.ALL_PRESETS.forEach { preset ->
                        val sel = presetId == preset.id
                        Surface(
                            onClick = {
                                presetId = preset.id
                                provider = preset.provider
                                if (preset.defaultBaseUrl.isNotEmpty()) baseUrl = preset.defaultBaseUrl
                                if (preset.defaultModelId.isNotEmpty()) modelId = preset.defaultModelId
                                if (name.isBlank() && preset.id != AgentProviderConfig.PRESET_CUSTOM.id) {
                                    name = preset.label
                                }
                                feedback = null
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(1.dp, if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            Text(preset.label, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("配置名称（如：主力推理 / 记忆轻量模型）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("agent-cfg-name"),
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text(if (provider == AgentProviderKind.ANTHROPIC) "接口地址（可选覆盖）" else "接口地址（HTTPS 或局域网 HTTP）") },
                    placeholder = { Text("https://... 或 http://192.168.x.x:port/v1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("agent-cfg-baseurl"),
                )
                OutlinedTextField(
                    value = modelId,
                    onValueChange = { modelId = it },
                    label = { Text("模型 ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("agent-cfg-model"),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key（局域网无鉴权可留空）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().testTag("agent-cfg-apikey"),
                )
                if (provider == AgentProviderKind.ANTHROPIC) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("思考深度：", style = MaterialTheme.typography.labelMedium)
                        listOf("low" to "低", "medium" to "中", "high" to "高").forEach { (k, l) ->
                            val sel = effort == k
                            Surface(
                                onClick = { effort = k },
                                shape = RoundedCornerShape(8.dp),
                                color = if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                            ) {
                                Text(l, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
                OutlinedButton(
                    enabled = !testing,
                    onClick = {
                        testing = true
                        feedback = null
                        isError = false
                        val candidate = AgentProviderConfig(provider, presetId, baseUrl, apiKey, modelId, effort)
                        scope.launch {
                            runCatching { AgentClientFactory.testConnection(candidate) }
                                .onSuccess { reply ->
                                    isError = false
                                    feedback = "连接成功：$reply"
                                }
                                .onFailure { err ->
                                    isError = true
                                    feedback = "连接失败：${err.message ?: "请检查地址、模型与 Key"}"
                                }
                            testing = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("agent-cfg-test"),
                ) {
                    Text(if (testing) "正在测试连接…" else "测试连接")
                }
                feedback?.let { msg ->
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isError) MaterialTheme.colorScheme.error else statTierPalette(StatRatingTier.HIGH).content,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmedUrl = baseUrl.trim().trimEnd('/')
                    val trimmedModel = modelId.trim()
                    if (trimmedModel.isEmpty()) {
                        isError = true
                        feedback = "请填写模型 ID"
                        return@Button
                    }
                    if (provider == AgentProviderKind.OPENAI_COMPAT && !AgentProviderConfig.isAllowedEndpointUrl(trimmedUrl)) {
                        isError = true
                        feedback = "公网接口必须使用 https://，仅局域网私网（192.168.x.x / 10.x.x.x / localhost）允许 http://"
                        return@Button
                    }
                    onSave(
                        initial.copy(
                            name = name.trim().ifBlank { trimmedModel },
                            provider = provider,
                            presetId = presetId,
                            baseUrl = trimmedUrl,
                            apiKey = apiKey.trim(),
                            modelId = trimmedModel,
                            thinkingEffort = effort,
                        ),
                    )
                },
                modifier = Modifier.testTag("agent-cfg-save"),
            ) {
                Text("保存配置")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
internal fun AgentSessionHistoryDialog(
    sessions: List<AgentSessionMeta>,
    activeSessionId: String?,
    onDismiss: () -> Unit,
    onCreateNew: () -> Unit,
    onSelect: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var renamingMeta by remember { mutableStateOf<AgentSessionMeta?>(null) }
    var renameText by remember { mutableStateOf("") }
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.CHINA) }

    if (renamingMeta != null) {
        AlertDialog(
            onDismissRequest = { renamingMeta = null },
            title = { Text("重命名对话") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("对话标题") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRename(renamingMeta!!.id, renameText)
                        renamingMeta = null
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renamingMeta = null }) { Text("取消") }
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("历史对话（${sessions.size}）")
                OutlinedButton(
                    onClick = {
                        onCreateNew()
                        onDismiss()
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.heightIn(min = 36.dp).testTag("agent-history-new"),
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("新建对话", style = MaterialTheme.typography.labelMedium)
                }
            }
        },
        text = {
            if (sessions.isEmpty()) {
                Text("暂无历史对话，开始提问后会自动保存。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(sessions, key = { it.id }) { meta ->
                        val isActive = meta.id == activeSessionId
                        Surface(
                            onClick = {
                                onSelect(meta.id)
                                onDismiss()
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                            else MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(1.dp, if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth().testTag("agent-session-item:${meta.id}"),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            meta.title,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false),
                                        )
                                        if (meta.parentSessionId != null) {
                                            EditorialBadge(text = "分支")
                                        }
                                    }
                                    if (meta.preview.isNotBlank()) {
                                        Text(
                                            meta.preview,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Text(
                                        "${fmt.format(Date(meta.updatedAtMillis))} · ${meta.messageCount} 条消息",
                                        style = MaterialTheme.typography.labelSmall.tabularNumbers(),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        renamingMeta = meta
                                        renameText = meta.title
                                    },
                                    modifier = Modifier.size(34.dp),
                                ) {
                                    Icon(Icons.Outlined.Edit, contentDescription = "重命名", modifier = Modifier.size(16.dp))
                                }
                                IconButton(
                                    onClick = { onDelete(meta.id) },
                                    modifier = Modifier.size(34.dp),
                                ) {
                                    Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除", modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@Composable
internal fun AgentMemoryBookDialog(
    memories: List<AgentMemoryEntry>,
    memoryModelLabel: String?,
    autoMemoryEnabled: Boolean,
    autoCompactEnabled: Boolean,
    onToggleAutoMemory: (Boolean) -> Unit,
    onToggleAutoCompact: (Boolean) -> Unit,
    canExtractFromChat: Boolean,
    extracting: Boolean,
    onDismiss: () -> Unit,
    onAdd: (category: String, content: String) -> Unit,
    onDelete: (String) -> Unit,
    onExtractNow: () -> Unit,
) {
    var draftCategory by remember { mutableStateOf("preference") }
    var draftContent by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Psychology, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("参谋记忆本（${memories.size}）")
            }
        },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 500.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "跨对话生效的个人习惯、主力路线与养成偏好（完全透明可编辑）。" +
                        (memoryModelLabel?.let { "\n当前记忆/压缩模型：$it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("自动提炼长期记忆", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                Text("对话后由记忆模型在后台自动整理习惯偏好", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = autoMemoryEnabled,
                                onCheckedChange = onToggleAutoMemory,
                                modifier = Modifier.testTag("agent-auto-memory-switch"),
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("自动阈值压缩上下文", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "达到 ${AgentMemoryStore.AUTO_COMPACT_MESSAGE_THRESHOLD} 条消息或 ${AgentMemoryStore.AUTO_COMPACT_CHAR_THRESHOLD} 字自动压缩",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = autoCompactEnabled,
                                onCheckedChange = onToggleAutoCompact,
                                modifier = Modifier.testTag("agent-auto-compact-switch"),
                            )
                        }
                    }
                }

                if (canExtractFromChat) {
                    OutlinedButton(
                        enabled = !extracting,
                        onClick = onExtractNow,
                        modifier = Modifier.fillMaxWidth().testTag("agent-memory-extract"),
                    ) {
                        if (extracting) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (extracting) "记忆模型整理中…" else "立即从当前对话手动提炼一次")
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Quick manual add
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("preference" to "养成偏好", "build" to "角色规划", "roster" to "路线队伍", "note" to "备忘").forEach { (cat, label) ->
                        val sel = draftCategory == cat
                        Surface(
                            onClick = { draftCategory = cat },
                            shape = RoundedCornerShape(100.dp),
                            color = if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(1.dp, if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            Text(label, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = draftContent,
                        onValueChange = { draftContent = it },
                        placeholder = { Text("如：习惯跳过总成长 <3 的升级点") },
                        singleLine = true,
                        modifier = Modifier.weight(1f).testTag("agent-memory-input"),
                    )
                    Button(
                        enabled = draftContent.trim().isNotEmpty(),
                        onClick = {
                            onAdd(draftCategory, draftContent.trim())
                            draftContent = ""
                        },
                        modifier = Modifier.heightIn(min = 50.dp).testTag("agent-memory-add"),
                    ) { Text("记下") }
                }

                if (memories.isEmpty()) {
                    Text(
                        "暂无记忆条目。你可以手动记下习惯，或在对话后点击上方按钮由记忆模型自动提炼。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        Modifier.fillMaxWidth().weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(memories, key = { it.id }) { mem ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        EditorialBadge(text = AgentMemoryStore.categoryLabel(mem.category))
                                        Text(mem.content, style = MaterialTheme.typography.bodySmall)
                                    }
                                    IconButton(
                                        onClick = { onDelete(mem.id) },
                                        modifier = Modifier.size(34.dp).testTag("agent-memory-del:${mem.id}"),
                                    ) {
                                        Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除记忆", modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )
}
