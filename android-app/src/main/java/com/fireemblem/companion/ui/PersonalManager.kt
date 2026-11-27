package com.fireemblem.companion.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.fireemblem.companion.engine.BuddyCampaignLine
import com.fireemblem.companion.engine.BuddyRules
import com.fireemblem.companion.engine.LevelJournal
import com.fireemblem.companion.engine.SeedWorkbenchRecord
import com.fireemblem.companion.engine.levelsUp
import com.fireemblem.companion.importer.AtomicBuddyArchiveStore
import com.fireemblem.companion.importer.BuddyBackupInspector
import com.fireemblem.companion.importer.FefwGrowthSeedSourceArchive
import com.fireemblem.companion.personal.EncryptedBackup
import com.fireemblem.companion.personal.PersonalRevision
import com.fireemblem.companion.personal.PersonalSnapshot
import com.fireemblem.companion.personal.PersonalStore
import com.fireemblem.companion.personal.PersonalSync
import com.fireemblem.companion.personal.PersonalTierList
import com.fireemblem.companion.personal.RouteMergeCharacter
import com.fireemblem.companion.personal.SecureSyncSettings
import com.fireemblem.companion.personal.SyncSettings
import com.fireemblem.companion.personal.TheaterBookmark
import com.fireemblem.companion.personal.WebDavRemote
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private data class PersonalRecordChoice(
    val revision: PersonalRevision,
    val delete: Boolean,
    val expectedParents: Set<String>,
)

@Composable
internal fun PersonalManager(
    legacy: AtomicBuddyArchiveStore,
    onArchivesChanged: () -> Unit,
    onOpenWiki: (String) -> Unit,
    onContentReady: () -> Unit = {},
    onOpenTheater: ((String) -> Unit)? = null,
    onOpenTier: ((String) -> Unit)? = null,
    onOpenRouteMerge: ((String?) -> Unit)? = null,
) {
    val context = LocalContext.current
    val store = remember(context) { PersonalStore(context) }
    val secure = remember(context) { SecureSyncSettings(context) }
    val preferences = remember(context) { context.getSharedPreferences("personal-view", 0) }
    val readingSettings = rememberReadingSettings()
    val buddyNames = remember {
        runCatching { BuddyRules.bundled().characters.associate { it.id to it.name } }.getOrDefault(emptyMap())
    }
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<PersonalSnapshot?>(null) }
    var selectedProfile by rememberSaveable { mutableStateOf(preferences.getString("profile", "global") ?: "global") }
    var status by remember { mutableStateOf<String?>(null) }
    var statusError by remember { mutableStateOf(false) }
    var dialogError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var syncJob by remember { mutableStateOf<Job?>(null) }
    var backupPassword by remember { mutableStateOf("") }
    var configured by remember { mutableStateOf(false) }
    var configuredAddress by remember { mutableStateOf("") }
    var syncDialog by remember { mutableStateOf(false) }
    var syncUrl by remember { mutableStateOf("") }
    var syncUser by remember { mutableStateOf("") }
    var syncPassword by remember { mutableStateOf("") }
    var syncEncryptionPassword by remember { mutableStateOf("") }
    var syncMessage by remember { mutableStateOf<String?>(null) }
    var syncFailed by remember { mutableStateOf(false) }
    var syncAttempted by remember { mutableStateOf(false) }
    var profileDialog by rememberSaveable { mutableStateOf(false) }
    var editingProfile by rememberSaveable { mutableStateOf<String?>(null) }
    var profileParent by rememberSaveable { mutableStateOf<String?>(null) }
    var profileName by rememberSaveable { mutableStateOf("") }
    var profileLine by rememberSaveable { mutableStateOf("kai") }
    var pendingImport by remember { mutableStateOf<PersonalSnapshot?>(null) }
    var pendingExport by remember { mutableStateOf<ByteArray?>(null) }
    var pendingChoice by remember { mutableStateOf<PersonalRecordChoice?>(null) }
    var panel by rememberSaveable { mutableStateOf("records") }
    var conflictsOnly by rememberSaveable { mutableStateOf(false) }
    var recordCategory by rememberSaveable { mutableStateOf("grouped") }
    var mergeFolderExpanded by rememberSaveable { mutableStateOf(false) }
    var seedFolderExpanded by rememberSaveable { mutableStateOf(false) }
    var recordPage by rememberSaveable(selectedProfile, conflictsOnly, recordCategory) { mutableIntStateOf(0) }

    fun work(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        dialogError = null
        status = null
        statusError = false
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                val message = "操作未完成，原记录仍保留。${error.message?.take(180).orEmpty()}"
                status = message; dialogError = message; statusError = true
            }
            finally { busy = false }
        }
    }

    fun startSync() {
        if (busy || !configured || snapshot == null) return
        busy = true
        syncAttempted = true
        syncFailed = false
        syncMessage = "正在与 WebDAV 同步；完成远端写入和本机合并后才会报告成功。"
        syncJob = scope.launch {
            var password = charArrayOf()
            try {
                val settings = withContext(Dispatchers.IO) { requireNotNull(secure.load()) { "请先配置同步" } }
                password = settings.encryptionPassword.toCharArray()
                val jobContext = currentCoroutineContext()
                snapshot = withContext(Dispatchers.IO) {
                    val merged = PersonalSync.synchronize(store.snapshot(), WebDavRemote(settings), password) { !jobContext.isActive }
                    if (!jobContext.isActive) throw CancellationException()
                    store.merge(merged)
                }
                val count = snapshot!!.heads().values.count { it.size > 1 }
                syncMessage = if (count == 0) "同步完成，没有待处理冲突。" else "同步完成；$count 条记录需要选择保留版本。"
                syncFailed = false
            } catch (cancelled: CancellationException) {
                syncMessage = "同步已停止。本机记录保留；远端请求可能已完成，下次同步会先重新读取并合并。"
                syncFailed = false
                throw cancelled
            } catch (error: Exception) {
                syncMessage = personalSyncFailureMessage(error)
                syncFailed = true
            } finally {
                password.fill('\u0000')
                busy = false
                syncJob = null
            }
        }
    }

    fun openProfile(profile: PersonalRevision?) {
        editingProfile = profile?.entityId
        profileParent = profile?.revisionId
        profileName = profile?.payload?.get("name")?.jsonPrimitive?.content.orEmpty()
        profileLine = profile?.payload?.get("route")?.jsonPrimitive?.content ?: "kai"
        dialogError = null
        profileDialog = true
    }

    LaunchedEffect(store, retry) {
        loading = true
        loadFailed = false
        status = null
        statusError = false
        var contentLoaded = false
        try {
            withContext(Dispatchers.IO) { store.importLegacyArchives(legacy) }
            snapshot = withContext(Dispatchers.IO) { store.snapshot() }
            contentLoaded = true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            loadFailed = true; statusError = true
            status = "读取或迁移个人记录失败；旧 Buddy 文件未删除，请重新读取后再操作。"
        } finally {
            loading = false
            if (contentLoaded) onContentReady()
        }
        try {
            val settings = withContext(Dispatchers.IO) { secure.load() }
            if (settings != null) {
                syncUrl = settings.url; configuredAddress = settings.url
                syncUser = settings.username; syncPassword = settings.password
                syncEncryptionPassword = settings.encryptionPassword
                backupPassword = settings.encryptionPassword; configured = true
                syncMessage = "已读取保存的同步设置；本次打开后尚未确认同步结果。"
                syncFailed = false; syncAttempted = false
            } else {
                configured = false; configuredAddress = ""
                syncMessage = null; syncFailed = false; syncAttempted = false
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            configured = false
            syncFailed = true
            syncMessage = "无法读取已保存的同步设置。请重新配置 WebDAV；个人记录与远端备份未改动。"
        }
    }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val bytes = pendingExport
        pendingExport = null
        if (uri != null && bytes != null) work {
            try {
                withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { it.write(bytes) } }
                status = "加密备份已导出，请另行保存口令；丢失口令无法恢复。"
            } finally { bytes.fill(0) }
        } else bytes?.fill(0)
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) work {
            val password = backupPassword.toCharArray()
            try {
                pendingImport = withContext(Dispatchers.IO) {
                    val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                        val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                        var count = input.read(buffer)
                        while (count >= 0) {
                            require(output.size() + count <= PersonalSnapshot.MAX_BYTES + 128) { "备份文件过大" }
                            output.write(buffer, 0, count); count = input.read(buffer)
                        }
                        output.toByteArray()
                    }
                    EncryptedBackup.decrypt(bytes, password)
                }
            } catch (_: javax.crypto.AEADBadTagException) {
                statusError = true; status = "口令不正确或备份已损坏，未更改任何记录。"
            } finally { password.fill('\u0000') }
        }
    }

    val heads = snapshot?.heads().orEmpty()
    val profiles = heads.values.mapNotNull { it.singleOrNull() }.filter { it.kind == "profile" && !it.deleted }
    val activeProfile = profiles.firstOrNull { it.entityId == selectedProfile }
    val records = heads.values.filter { group -> group.size > 1 || group.singleOrNull()?.let {
        !it.deleted && it.kind != "profile" && (
            it.kind == "buddy" ||
                it.kind == FefwGrowthSeedSourceArchive.KIND ||
                it.payload["profileId"]?.jsonPrimitive?.content == selectedProfile
            )
    } == true }.sortedWith(compareByDescending<List<PersonalRevision>> { it.size > 1 }
        .thenByDescending { group -> group.maxOf(PersonalRevision::modifiedAt) }.thenBy { it.first().entityId })
    val conflictCount = records.count { it.size > 1 }
    val mergeGroups = records.filter { it.size == 1 && it.first().kind == RouteMergeCharacter.KIND }
    val seedGroups = records.filter { it.size == 1 && it.first().kind == SeedWorkbenchRecord.KIND }
    val systemArchiveCount = records.count { it.size == 1 && it.first().kind == FefwGrowthSeedSourceArchive.KIND }
    val cultivationKinds = setOf("level-journal", "growth-plan", "tier", SeedWorkbenchRecord.KIND)
    val noteKinds = setOf("bookmark", "progress", "note", "map-route", "buddy")
    val displayedRecords = when {
        conflictsOnly -> records.filter { it.size > 1 }
        recordCategory == "merge" -> mergeGroups
        recordCategory == "cultivation" -> records.filter { it.size > 1 || it.first().kind in cultivationKinds }
        recordCategory == "notes" -> records.filter { it.size > 1 || it.first().kind in noteKinds }
        else -> records.filter { group ->
            group.size > 1 || group.first().kind !in setOf(
                RouteMergeCharacter.KIND,
                SeedWorkbenchRecord.KIND,
                FefwGrowthSeedSourceArchive.KIND,
            )
        }
    }
    val page = recordPage.coerceIn(0, (displayedRecords.size - 1).coerceAtLeast(0) / 20)
    val appearanceSettings = rememberAppearanceSettings()

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PersonalSectionCard("外观主题", "选择适合阅读的暖白浅色或炭黑深色界面。", Icons.Outlined.Settings) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AppAppearanceMode.entries.forEach { mode ->
                    FilterChip(
                        selected = appearanceSettings.mode == mode,
                        onClick = { appearanceSettings.setMode(mode) },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("appearance-mode:${mode.id}"),
                        label = { Text(mode.label) },
                    )
                }
            }
        }
        PersonalSectionCard("阅读设置", "按你的习惯浏览资料和记录。", Icons.Outlined.Settings) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(
                value = readingSettings.restorePosition,
                role = Role.Switch,
                onValueChange = readingSettings.setRestorePosition,
            ), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("返回原来的位置", style = MaterialTheme.typography.titleSmall)
                    Text("返回资料、攻略或记录时，继续从离开的位置阅读；关闭后从顶部开始。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = readingSettings.restorePosition, onCheckedChange = null)
            }
        }
        LocalOcrModelSettingsCard()
        AgentSettingsSummaryCard()
        PersonalSectionCard("个人存档", "收藏、笔记与探索进度按存档分别保存。", Icons.Outlined.Person) {
            val profileTitle = if (selectedProfile == "global") "通用记录"
                else activeProfile?.payload?.get("name")?.jsonPrimitive?.content ?: "当前存档暂不可用"
            Text(profileTitle, style = MaterialTheme.typography.titleMedium)
            activeProfile?.let { active ->
                val routeLabel = BuddyCampaignLine.entries.firstOrNull { it.id == active.payload["route"]?.jsonPrimitive?.content }?.label ?: "未设置路线"
                Text("当前路线：$routeLabel", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = selectedProfile == "global", onClick = {
                    selectedProfile = "global"; preferences.edit().putString("profile", "global").apply()
                }, modifier = Modifier.heightIn(min = 48.dp), label = { Text("通用记录") })
                profiles.forEach { profile ->
                    FilterChip(selected = selectedProfile == profile.entityId, onClick = {
                        selectedProfile = profile.entityId; preferences.edit().putString("profile", profile.entityId).apply()
                    }, modifier = Modifier.heightIn(min = 48.dp),
                        label = { Text(profile.payload["name"]?.jsonPrimitive?.content ?: "未命名存档") })
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(enabled = !busy && snapshot != null, onClick = { openProfile(null) },
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("新建存档") }
                if (activeProfile != null) TextButton(enabled = !busy, onClick = { openProfile(activeProfile) },
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("编辑当前存档") }
            }
        }
        if (loading || busy && syncJob == null) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                Text(when { loading -> "正在读取个人记录…"; else -> "正在处理，请稍候…" },
                    style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        status?.let { PersonalNotice(it, statusError) }
        if (loadFailed) OutlinedButton(enabled = !busy && !loading, onClick = { retry++ },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("重新读取个人记录") }
        else if (statusError) TextButton(enabled = !busy && !loading, onClick = { retry++ },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("刷新个人记录") }
        if (conflictCount > 0) Card(
            onClick = { panel = "records"; conflictsOnly = true; recordPage = 0 },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        ) {
            Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("$conflictCount 条记录待处理 · 查看版本", style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer)
                    Text("各设备的修改均已保留，请选择要继续使用的版本。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }

        val recordsContent: @Composable (Modifier) -> Unit = { modifier ->
            Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("个人记录 · ${records.size}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(selected = !conflictsOnly && recordCategory == "grouped", onClick = {
                        conflictsOnly = false; recordCategory = "grouped"
                    }, modifier = Modifier.heightIn(min = 48.dp), label = { Text("全部记录") })
                    if (mergeGroups.isNotEmpty()) FilterChip(
                        selected = !conflictsOnly && recordCategory == "merge",
                        onClick = { conflictsOnly = false; recordCategory = "merge" },
                        modifier = Modifier.heightIn(min = 48.dp),
                        label = { Text("四线融合（${mergeGroups.size} 人）") },
                    )
                    FilterChip(selected = !conflictsOnly && recordCategory == "cultivation", onClick = {
                        conflictsOnly = false; recordCategory = "cultivation"
                    }, modifier = Modifier.heightIn(min = 48.dp), label = { Text("升级与规划") })
                    FilterChip(selected = !conflictsOnly && recordCategory == "notes", onClick = {
                        conflictsOnly = false; recordCategory = "notes"
                    }, modifier = Modifier.heightIn(min = 48.dp), label = { Text("收藏与探索") })
                    FilterChip(selected = conflictsOnly, onClick = { conflictsOnly = true },
                        modifier = Modifier.heightIn(min = 48.dp), label = { Text("待处理（$conflictCount）") })
                }
                if (conflictCount > 0) Text("待处理列表同时包含其他存档的冲突，处理前请核对名称和修改内容。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!conflictsOnly && recordCategory == "grouped" && mergeGroups.isNotEmpty()) {
                    PersonalGroupedFolderCard(
                        title = "四线融合记录本 · 共 ${mergeGroups.size} 名角色",
                        description = "每位角色的四线升级与融合快照单独存储以便安全同步，已统一收纳于此，避免列表刷屏或误删。",
                        expanded = mergeFolderExpanded,
                        onToggle = { mergeFolderExpanded = !mergeFolderExpanded },
                        primaryActionLabel = if (onOpenRouteMerge != null) "前往多线融合面板" else null,
                        onPrimaryAction = { onOpenRouteMerge?.invoke(null) },
                        items = mergeGroups.map { it.first() },
                        buddyNames = buddyNames,
                        busy = busy,
                        onInspectItem = { rev ->
                            rev.payload["characterId"]?.jsonPrimitive?.content?.let { cid -> onOpenRouteMerge?.invoke(cid) }
                        },
                        onDeleteItem = { rev ->
                            dialogError = null
                            pendingChoice = PersonalRecordChoice(rev, true, setOf(rev.revisionId))
                        },
                    )
                }
                if (!conflictsOnly && recordCategory == "grouped" && seedGroups.isNotEmpty()) {
                    PersonalGroupedFolderCard(
                        title = "成长种子工作台方案 · 共 ${seedGroups.size} 项",
                        description = "凹点成长工作台按角色与路线保存的步进规划与种子预设。",
                        expanded = seedFolderExpanded,
                        onToggle = { seedFolderExpanded = !seedFolderExpanded },
                        items = seedGroups.map { it.first() },
                        buddyNames = buddyNames,
                        busy = busy,
                        onInspectItem = null,
                        onDeleteItem = { rev ->
                            dialogError = null
                            pendingChoice = PersonalRecordChoice(rev, true, setOf(rev.revisionId))
                        },
                    )
                }
                if (!conflictsOnly && recordCategory == "grouped" && systemArchiveCount > 0) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
                    ) {
                        Text(
                            "已内置同步 $systemArchiveCount 份成长种子基础源数据（系统自动维护，不在普通列表删除以免影响成长预测）。",
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (snapshot != null && displayedRecords.isEmpty() &&
                    (conflictsOnly || recordCategory != "grouped" || (mergeGroups.isEmpty() && seedGroups.isEmpty()))
                ) PersonalNotice(
                    if (conflictsOnly) "没有待处理冲突。各设备出现不同修改时，会在这里供你选择。"
                    else "当前分类下还没有记录。图鉴收藏、培养规划和探索进度会显示在这里。",
                )
                displayedRecords.drop(page * 20).take(20).forEach { group ->
                    val first = group.first()
                    val conflict = group.size > 1
                    val resolvedTitle = personalRecordTitle(first, buddyNames)
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(resolvedTitle, style = MaterialTheme.typography.titleSmall)
                            Text(personalRecordKindLabel(first.kind) + if (conflict) " · ${group.size} 个待选版本" else "",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (conflict) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                            group.forEachIndexed { index, revision ->
                                if (conflict) Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(12.dp)) {
                                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("版本 ${index + 1} · ${if (revision.deleted) "已删除" else "保留内容"}", style = MaterialTheme.typography.titleSmall)
                                        Text("设备 ${revision.deviceId.take(8)} · ${personalRevisionTime(revision.modifiedAt)}",
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        PersonalRevisionSummary(revision, conflict = true, buddyNames = buddyNames)
                                        OutlinedButton(enabled = !busy, onClick = {
                                            dialogError = null
                                            pendingChoice = PersonalRecordChoice(revision, revision.deleted, group.map(PersonalRevision::revisionId).toSet())
                                        }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                            Text(if (revision.deleted) "保留此删除状态" else "保留此版本")
                                        }
                                    }
                                } else {
                                    PersonalRevisionSummary(revision, buddyNames = buddyNames)
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        revision.payload["wikiId"]?.jsonPrimitive?.content?.let { id ->
                                            TextButton(onClick = { onOpenWiki(id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("查看资料") }
                                        }
                                        if (revision.kind == RouteMergeCharacter.KIND && onOpenRouteMerge != null) {
                                            val charId = revision.payload["characterId"]?.jsonPrimitive?.content
                                            TextButton(onClick = { onOpenRouteMerge(charId) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("前往融合面板") }
                                        }
                                        if (revision.kind == "bookmark" && onOpenTheater != null) {
                                            runCatching { TheaterBookmark.decode(revision.payload) }.getOrNull()?.let { bookmark ->
                                                TextButton(onClick = { onOpenTheater(bookmark.playId) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("查看剧目") }
                                            }
                                        }
                                        if (revision.kind == PersonalTierList.KIND && onOpenTier != null) {
                                            runCatching { PersonalTierList.decode(revision.payload) }.getOrNull()?.let {
                                                TextButton(onClick = { onOpenTier(revision.entityId) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("查看梯度榜") }
                                            }
                                        }
                                        if (revision.kind == "buddy") TextButton(enabled = !busy, onClick = { work {
                                            withContext(Dispatchers.IO) {
                                                val preview = BuddyBackupInspector.inspect(requireNotNull(revision.payload["backup"]).toString().toByteArray())
                                                legacy.save(preview)
                                            }
                                            onArchivesChanged(); status = "已恢复到工具的 Buddy 备份列表。"
                                        } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("恢复到工具") }
                                        TextButton(enabled = !busy, onClick = {
                                            dialogError = null
                                            pendingChoice = PersonalRecordChoice(revision, true, setOf(revision.revisionId))
                                        }, modifier = Modifier.heightIn(min = 48.dp)) { Text("删除记录", color = MaterialTheme.colorScheme.error) }
                                    }
                                }
                            }
                        }
                    }
                }
                if (displayedRecords.size > 20) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(enabled = page > 0, onClick = { recordPage = page - 1 },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("上一页") }
                    Text("${page + 1} / ${(displayedRecords.size + 19) / 20}", style = MaterialTheme.typography.labelLarge)
                    TextButton(enabled = (page + 1) * 20 < displayedRecords.size, onClick = { recordPage = page + 1 },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("下一页") }
                }
            }
        }
        val backupContent: @Composable (Modifier) -> Unit = { modifier ->
            Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PersonalSectionCard("加密备份", "导出全部存档，或在确认后合并恢复。", Icons.Outlined.Backup) {
                    OutlinedTextField(backupPassword, { backupPassword = it }, Modifier.fillMaxWidth(), enabled = !busy,
                        label = { Text("备份加密口令") },
                        supportingText = { Text("导出至少需要 12 个字符；恢复时填写备份原口令。请在设备之外保存，丢失后无法恢复。") },
                        visualTransformation = PasswordVisualTransformation(), singleLine = true)
                    Button(enabled = !busy && snapshot != null && backupPassword.length >= 12, onClick = { work {
                        val password = backupPassword.toCharArray()
                        pendingExport = try { withContext(Dispatchers.IO) { EncryptedBackup.encrypt(store.snapshot(), password) } }
                            finally { password.fill('\u0000') }
                        export.launch("fireemblem-personal.fec")
                    } }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("导出备份") }
                    OutlinedButton(enabled = !busy && snapshot != null && backupPassword.isNotEmpty(),
                        onClick = { importer.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("选择备份恢复") }
                    Text("备份包含所有个人存档，不含游戏资料与图片。恢复前会先展示记录数量，现有修改不会被静默覆盖。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                PersonalSectionCard("多设备同步", "设置保存与同步结果分开显示；只有远端写入并完成本机合并后才会报告成功。", Icons.Outlined.CloudSync) {
                    if (configured) Text(android.net.Uri.parse(configuredAddress).host ?: "已保存的 WebDAV 目录",
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(if (configured) "已保存同步设置 · 手动同步" else "尚未配置 WebDAV",
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("远端只保存加密的个人记录。所有设备须使用相同的同步口令；不同修改会保留供你选择。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(enabled = !busy && configured && snapshot != null, onClick = ::startSync,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Icon(Icons.Outlined.Sync, contentDescription = null, modifier = Modifier.size(20.dp))
                        Text(when { syncFailed -> "重试同步"; syncAttempted -> "再次同步"; else -> "立即同步" }, Modifier.padding(start = 8.dp))
                    }
                    if (syncJob != null) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                            Text("正在与 WebDAV 同步，请保持应用在前台。", style = MaterialTheme.typography.bodySmall)
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                    syncMessage?.let { PersonalNotice(it, syncFailed) }
                    if (syncJob != null) TextButton(onClick = { syncJob?.cancel() },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("停止同步") }
                    OutlinedButton(enabled = !busy, onClick = { dialogError = null; syncDialog = true },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (configured) "管理同步设置" else "配置 WebDAV") }
                    Text("同步使用本机安全保存的口令，不受上方备份口令输入的影响。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 720.dp) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                recordsContent(Modifier.weight(1.2f))
                backupContent(Modifier.weight(1f))
            } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(selected = panel == "records", onClick = { panel = "records" },
                        modifier = Modifier.heightIn(min = 48.dp), label = { Text("我的记录") })
                    FilterChip(selected = panel == "backup", onClick = { panel = "backup" },
                        modifier = Modifier.heightIn(min = 48.dp), label = { Text("备份与同步") })
                }
                if (panel == "records") recordsContent(Modifier.fillMaxWidth()) else backupContent(Modifier.fillMaxWidth())
            }
        }
        Spacer(Modifier.height(32.dp))
    }

    if (profileDialog) AlertDialog(onDismissRequest = { if (!busy) profileDialog = false },
        title = { Text(if (editingProfile == null) "新建个人存档" else "编辑个人存档") }, text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(profileName, { profileName = it.take(80) }, Modifier.fillMaxWidth(), enabled = !busy,
                    label = { Text("存档名称") }, singleLine = true)
                Text("存档路线", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BuddyCampaignLine.entries.forEach { line ->
                        FilterChip(selected = profileLine == line.id, onClick = { profileLine = line.id }, enabled = !busy,
                            modifier = Modifier.heightIn(min = 48.dp), label = { Text(line.label) })
                    }
                }
                Text("收藏、笔记和进度会按存档分别保存。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                dialogError?.let { PersonalNotice(it, true) }
            }
        }, confirmButton = {
            TextButton(enabled = !busy && snapshot != null && profileName.isNotBlank(), onClick = { work {
                val id = editingProfile ?: UUID.randomUUID().toString()
                val payload = buildJsonObject { put("name", profileName.trim()); put("route", profileLine) }
                snapshot = withContext(Dispatchers.IO) { store.edit(id, "profile", payload, expectedParents = setOfNotNull(profileParent)) }
                selectedProfile = id; preferences.edit().putString("profile", id).apply()
                status = if (editingProfile == null) "个人存档已创建。" else "当前存档名称和路线已更新。"
                profileDialog = false
            } }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (editingProfile == null) "创建存档" else "保存更改") }
        }, dismissButton = { TextButton(enabled = !busy, onClick = { profileDialog = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } })

    if (syncDialog) AlertDialog(onDismissRequest = { if (!busy) syncDialog = false }, title = { Text("WebDAV 同步设置") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(syncUrl, { syncUrl = it }, Modifier.fillMaxWidth(), enabled = !busy,
                label = { Text("HTTPS 目录地址") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(syncUser, { syncUser = it }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("用户名") }, singleLine = true)
            OutlinedTextField(syncPassword, { syncPassword = it }, Modifier.fillMaxWidth(), enabled = !busy,
                label = { Text("WebDAV 密码") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            OutlinedTextField(syncEncryptionPassword, { syncEncryptionPassword = it }, Modifier.fillMaxWidth(), enabled = !busy,
                label = { Text("同步加密口令（至少 12 个字符）") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                supportingText = { Text("与 WebDAV 密码不同，所有设备须一致。更改设置不会重置或重新加密远端已有内容。") })
            Text("只访问该目录下的 fireemblem-companion。凭证在本机加密保存，不上传游戏资料。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(enabled = !busy, onClick = { work {
                withContext(Dispatchers.IO) { secure.clear() }
                configured = false; configuredAddress = ""; syncPassword = ""; syncEncryptionPassword = ""; syncDialog = false
                syncFailed = false; syncAttempted = false
                syncMessage = "本机同步设置已移除；个人记录与远端备份未删除。"
            } }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("移除本机同步设置") }
            dialogError?.let { PersonalNotice(it, true) }
        }
    }, confirmButton = { TextButton(enabled = !busy && syncEncryptionPassword.length >= 12 && syncUrl.isNotBlank(), onClick = { work {
        val settings = SyncSettings(syncUrl.trim(), syncUser, syncPassword, syncEncryptionPassword)
        withContext(Dispatchers.IO) { secure.save(settings) }
        configured = true; configuredAddress = settings.url; syncDialog = false
        syncFailed = false; syncAttempted = false
        syncMessage = "同步设置已保存；尚未执行同步，远端数据不会因保存设置而更新。"
    } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("保存设置") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { syncDialog = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } })

    pendingImport?.let { incoming -> AlertDialog(onDismissRequest = { if (!busy) pendingImport = null }, title = { Text("确认恢复备份") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
            Text("${incoming.heads().size} 条记录 · ${incoming.revisions.size} 次修改", style = MaterialTheme.typography.titleMedium)
            Text("将与本机全部存档合并，不覆盖并发修改。冲突会保留，供你逐条选择。")
            dialogError?.let { PersonalNotice(it, true) }
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = { work {
        snapshot = withContext(Dispatchers.IO) { store.merge(incoming) }; pendingImport = null
        status = "恢复完成；请在个人记录中检查待处理版本。"
    } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("合并恢复") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { pendingImport = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } }) }

    pendingChoice?.let { choice -> AlertDialog(onDismissRequest = { if (!busy) pendingChoice = null }, title = {
        Text(when { choice.expectedParents.size > 1 && choice.delete -> "确认保留删除状态"; choice.delete -> "确认删除此记录"; else -> "确认保留此版本" })
    }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
            Text(personalRecordTitle(choice.revision, buddyNames),
                style = MaterialTheme.typography.titleSmall)
            Text(personalRecordKindLabel(choice.revision.kind),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
            PersonalRevisionSummary(choice.revision, buddyNames = buddyNames)
            Text(
                when {
                    choice.expectedParents.size > 1 -> "将以所选版本继续保存，历史修改仍保留。若其他设备又有新修改，将要求重新核对，不会自动覆盖。"
                    choice.revision.kind == RouteMergeCharacter.KIND -> "将清除该角色在多线融合面板中录入的四线属性记录（可在融合面板随时重新录入）；不会改动游戏资料。"
                    else -> "只删除这条个人记录，不改动游戏资料或原始 Buddy 文件。删除状态会参与同步。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            dialogError?.let { PersonalNotice(it, true) }
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = { work {
        snapshot = withContext(Dispatchers.IO) { store.edit(choice.revision.entityId, choice.revision.kind, choice.revision.payload,
            choice.delete, choice.expectedParents) }
        pendingChoice = null; status = if (choice.delete) "删除状态已保存。" else "已保存所选版本。"
    } }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (choice.delete) "确认删除" else "保留此版本") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { pendingChoice = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } }) }
}

@Composable
private fun PersonalSectionCard(title: String, description: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp)) {
                    Icon(icon, contentDescription = null, modifier = Modifier.padding(10.dp).size(22.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            content()
        }
    }
}

@Composable
private fun PersonalNotice(message: String, error: Boolean = false) {
    Surface(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, shape = RoundedCornerShape(12.dp),
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
        Text(message, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium,
            color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PersonalGroupedFolderCard(
    title: String,
    description: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    items: List<PersonalRevision>,
    buddyNames: Map<String, String>,
    busy: Boolean,
    primaryActionLabel: String? = null,
    onPrimaryAction: (() -> Unit)? = null,
    onInspectItem: ((PersonalRevision) -> Unit)? = null,
    onDeleteItem: (PersonalRevision) -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onToggle, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (expanded) "收起明细 ▲" else "展开名单 ▼")
                }
            }
            if (primaryActionLabel != null && onPrimaryAction != null) {
                OutlinedButton(onClick = onPrimaryAction, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                    Text(primaryActionLabel)
                }
            }
            if (expanded) {
                items.forEach { rev ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(personalRecordTitle(rev, buddyNames), style = MaterialTheme.typography.titleSmall)
                            PersonalRevisionSummary(rev, buddyNames = buddyNames)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                if (onInspectItem != null) {
                                    TextButton(onClick = { onInspectItem(rev) }, modifier = Modifier.heightIn(min = 42.dp)) {
                                        Text("打开编辑")
                                    }
                                }
                                TextButton(
                                    enabled = !busy,
                                    onClick = { onDeleteItem(rev) },
                                    modifier = Modifier.heightIn(min = 42.dp),
                                ) {
                                    Text("删除此条", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonalRevisionSummary(
    revision: PersonalRevision,
    conflict: Boolean = false,
    buddyNames: Map<String, String> = emptyMap(),
) {
    revision.payload["name"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium)
    }
    if (revision.kind == RouteMergeCharacter.KIND) {
        runCatching { RouteMergeCharacter.decode(revision.payload) }.getOrNull()?.let { rm ->
            val lines = rm.recordedLines
            val summary = if (lines.isEmpty()) "暂未填报具体路线数值"
            else lines.joinToString(" · ") { line ->
                val snap = rm.snapshots[line]
                if (snap == null) line.label
                else buildString {
                    append(line.label)
                    append(" Lv.").append(snap.level)
                    snap.profession.takeIf(String::isNotBlank)?.let { append(" ").append(it) }
                }
            }
            val customSuffix = rm.selectedLines?.takeIf { it.isNotEmpty() && it.size < lines.size }?.let { sel ->
                " · 自选融 ${sel.size} 线"
            }.orEmpty()
            Text("已录 ${lines.size}/4 线$customSuffix：$summary",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (revision.kind == SeedWorkbenchRecord.KIND) {
        runCatching { SeedWorkbenchRecord.decode(revision.payload) }.getOrNull()?.let { sw ->
            val charName = buddyNames[sw.characterId] ?: sw.characterId
            Text(
                "$charName · ${sw.line.label} · Lv.${sw.startLevel}（${sw.profession}）· 已完成 ${sw.completedSteps}/${sw.planSteps.size} 步",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (revision.kind == FefwGrowthSeedSourceArchive.KIND) {
        val bytesLen = revision.payload["originalSize"]?.jsonPrimitive?.content
        val sha = revision.payload["sourceSha256"]?.jsonPrimitive?.content?.take(8)
        Text(
            "成长源数据档案" + (bytesLen?.let { " · ${it}B" } ?: "") + (sha?.let { " · SHA $it" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (revision.kind == LevelJournal.KIND) {
        runCatching { LevelJournal.decode(revision.payload) }.getOrNull()?.let { journal ->
            Text("${journal.line.label} · ${journal.observations.count { it.levelsUp }} 次升级 · ${journal.observations.size} 条记录",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            journal.currentLevel?.let { level ->
                Text("当前等级：$level", style = MaterialTheme.typography.labelMedium)
            }
            journal.observations.lastOrNull()?.let { observation ->
                Text("最近记录：Lv. ${observation.level} · ${observation.profession}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (revision.kind == PersonalTierList.KIND) {
        runCatching { PersonalTierList.decode(revision.payload) }.getOrNull()?.let { tierList ->
            Text("${tierList.tiers.size} 个档位 · ${tierList.entries.size} 名角色",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            tierList.note.takeIf(String::isNotBlank)?.let { summaryNote ->
                Text(summaryNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    val note = revision.payload["note"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
    if (note != null) Text(note, style = MaterialTheme.typography.bodyMedium)
    else if (conflict && revision.payload.containsKey("note")) {
        Text("没有笔记", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val states = buildList {
        revision.payload["favorite"]?.jsonPrimitive?.booleanOrNull?.let { add(if (it) "已收藏" else "未收藏") }
        revision.payload["completed"]?.jsonPrimitive?.booleanOrNull?.let { add(if (it) "已完成" else "未完成") }
        revision.payload["route"]?.jsonPrimitive?.content?.let { id -> BuddyCampaignLine.entries.firstOrNull { it.id == id }?.let { add(it.label) } }
        revision.payload["fame"]?.jsonPrimitive?.content?.let { add("名声 $it") }
    }
    if (states.isNotEmpty()) Text(states.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
}

private fun personalRevisionTime(modifiedAt: Long): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(modifiedAt))

private fun personalRecordTitle(revision: PersonalRevision, buddyNames: Map<String, String>): String {
    revision.payload["name"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)?.let { return it }
    return when (revision.kind) {
        RouteMergeCharacter.KIND -> {
            val charName = revision.payload["characterName"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
                ?: "未命名角色"
            val lineCount = runCatching { RouteMergeCharacter.decode(revision.payload).recordedLines.size }.getOrNull()
            if (lineCount != null && lineCount > 0) "$charName · 四线融合记录（$lineCount/4 线）" else "$charName · 四线融合记录"
        }
        SeedWorkbenchRecord.KIND -> {
            val decoded = runCatching { SeedWorkbenchRecord.decode(revision.payload) }.getOrNull()
            val cid = decoded?.characterId ?: revision.payload["characterId"]?.jsonPrimitive?.content.orEmpty()
            val cname = buddyNames[cid] ?: cid.ifBlank { "角色" }
            if (decoded != null) "$cname · 成长工作台（${decoded.line.label}）" else "$cname · 成长工作台"
        }
        FefwGrowthSeedSourceArchive.KIND -> "内置成长规则档案（自动同步源数据）"
        "map-route" -> revision.payload["title"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
            ?: revision.payload["mapId"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)?.let { "地图路线 · $it" }
            ?: personalRecordKindLabel(revision.kind)
        "growth-plan" -> revision.payload["characterName"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)?.let { "$it · 培养方案" }
            ?: personalRecordKindLabel(revision.kind)
        "buddy" -> revision.payload["character"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)?.let { "$it · Buddy 备份" }
            ?: personalRecordKindLabel(revision.kind)
        else -> personalRecordKindLabel(revision.kind)
    }
}

private fun personalRecordKindLabel(kind: String): String = when (kind) {
    "profile" -> "个人存档"
    "bookmark" -> "图鉴收藏与进度"
    "progress" -> "任务与探索进度"
    "note" -> "个人笔记"
    "buddy" -> "Buddy 备份"
    "growth-plan" -> "培养方案"
    "tier" -> "角色分级"
    "map-route" -> "地图路线"
    "level-journal" -> "升级记录"
    RouteMergeCharacter.KIND -> "四线融合角色记录"
    SeedWorkbenchRecord.KIND -> "成长种子工作台"
    FefwGrowthSeedSourceArchive.KIND -> "内置成长规则档案"
    else -> "个人记录"
}

private fun personalSyncFailureMessage(error: Exception): String {
    if (error is javax.crypto.AEADBadTagException) {
        return "同步口令不匹配或远端备份已损坏。请核对所有设备的加密口令；不要用新口令覆盖现有远端备份。"
    }
    val detail = error.message?.trim()?.takeIf(String::isNotBlank)
    return when {
        detail?.contains("ETag", ignoreCase = true) == true ->
            "服务器未提供可靠的 ETag，已停止写入以避免覆盖其他设备。请使用支持条件写入的 WebDAV 服务后重试；本机记录未改动。"
        detail?.contains("账号、密码", ignoreCase = true) == true ->
            "$detail。请核对账号、密码和目录写入权限后重试；本机记录未被覆盖。"
        detail?.contains("跳转", ignoreCase = true) == true ->
            "$detail。请改用服务器最终的 HTTPS 目录地址后重试；本机记录未被覆盖。"
        detail?.contains("空间不足", ignoreCase = true) == true ->
            "$detail。请清理远端空间或联系服务提供方后重试；本机记录未被覆盖。"
        detail?.contains("其他设备正在更新", ignoreCase = true) == true ->
            "$detail。稍后重试；本机记录未被覆盖。"
        error is java.io.IOException ->
            "无法连接 WebDAV。请检查网络和 HTTPS 目录地址后重试；本机记录未被覆盖。"
        else -> {
            val reason = detail?.takeIf { it.contains("WebDAV", ignoreCase = true) || it.contains("服务器", ignoreCase = true) }
                ?.take(140)?.let { " $it" }.orEmpty()
            "同步未完成，本机记录未被覆盖。$reason 请检查网络、WebDAV 地址和账号权限后重试；如果远端已接收本次内容，再次同步会先重新读取并合并。"
        }
    }
}
