package com.fireemblem.companion.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Leaderboard
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fireemblem.companion.content.WikiEntry
import com.fireemblem.companion.content.WikiPageRequest
import com.fireemblem.companion.content.WikiRepository
import com.fireemblem.companion.personal.PersonalRevision
import com.fireemblem.companion.personal.PersonalSnapshot
import com.fireemblem.companion.personal.PersonalStore
import com.fireemblem.companion.personal.PersonalTier
import com.fireemblem.companion.personal.PersonalTierCollection
import com.fireemblem.companion.personal.PersonalTierEntry
import com.fireemblem.companion.personal.PersonalTierList
import com.fireemblem.companion.personal.PersonalTierRecord
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonPrimitive

private data class TierReadyState(
    val snapshot: PersonalSnapshot,
    val profileName: String,
    val collection: PersonalTierCollection,
    val characters: List<WikiEntry>,
    val charactersById: Map<String, WikiEntry>,
)

@Composable
internal fun PersonalTierListScreen(
    repository: WikiRepository?,
    onBack: () -> Unit,
    onOpenWiki: (String) -> Unit,
    initialEntityId: String? = null,
) {
    val context = LocalContext.current
    val store = remember(context) { PersonalStore(context) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val profileId = remember(context) {
        context.getSharedPreferences("personal-view", 0).getString("profile", "global") ?: "global"
    }

    var ready by remember(repository, profileId) { mutableStateOf<TierReadyState?>(null) }
    var loadError by remember(repository, profileId) { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageError by remember { mutableStateOf(false) }

    var selectedEntityId by rememberSaveable(profileId, initialEntityId) { mutableStateOf(initialEntityId) }
    var mode by rememberSaveable { mutableStateOf("board") }
    var query by rememberSaveable { mutableStateOf("") }
    var pickFilter by rememberSaveable { mutableStateOf("all") }

    var editingCharacter by remember { mutableStateOf<WikiEntry?>(null) }
    var editingCharacterId by remember { mutableStateOf<String?>(null) }
    var draftTierId by remember { mutableStateOf("") }
    var draftNote by remember { mutableStateOf("") }

    var showListMetaDialog by remember { mutableStateOf(false) }
    var creatingNewList by remember { mutableStateOf(false) }
    var metaName by remember { mutableStateOf("") }
    var metaNote by remember { mutableStateOf("") }

    var showTierManager by remember { mutableStateOf(false) }
    var newTierLabel by remember { mutableStateOf("") }
    var newTierDescription by remember { mutableStateOf("") }
    var editingTierId by remember { mutableStateOf<String?>(null) }
    var editTierLabel by remember { mutableStateOf("") }
    var editTierDescription by remember { mutableStateOf("") }

    var confirmDeleteList by remember { mutableStateOf(false) }
    var pendingExportBytes by remember { mutableStateOf<ByteArray?>(null) }
    var pendingImportList by remember { mutableStateOf<PersonalTierList?>(null) }

    LaunchedEffect(repository, profileId, initialEntityId, retry) {
        loadError = null
        try {
            val loaded = withContext(Dispatchers.IO) {
                val snapshot = store.snapshot()
                val profileName = if (profileId == "global") {
                    "通用记录"
                } else {
                    snapshot.heads()[profileId]?.singleOrNull()?.takeUnless { it.deleted }
                        ?.payload?.get("name")?.jsonPrimitive?.content ?: "当前个人存档"
                }
                val collection = PersonalTierList.readProfile(snapshot, profileId)
                val characters = buildList {
                    if (repository != null) {
                        var offset = 0
                        while (true) {
                            val page = repository.page(
                                WikiPageRequest(
                                    categoryId = "characters",
                                    offset = offset,
                                    limit = WikiPageRequest.MAX_PAGE_SIZE,
                                ),
                            )
                            addAll(page.entries)
                            if (!page.hasNext || page.entries.isEmpty()) break
                            offset += page.entries.size
                        }
                    }
                }
                TierReadyState(
                    snapshot = snapshot,
                    profileName = profileName,
                    collection = collection,
                    characters = characters,
                    charactersById = characters.associateBy(WikiEntry::id),
                )
            }
            ready = loaded
            val currentId = selectedEntityId
            if (initialEntityId == null) {
                val validIds = loaded.collection.lists.map { it.entityId }.toSet() +
                    loaded.collection.conflictEntityIds + loaded.collection.corruptedEntityIds
                if (currentId == null || (validIds.isNotEmpty() && currentId !in validIds &&
                        currentId != PersonalTierList.defaultEntityId(profileId))) {
                    selectedEntityId = loaded.collection.lists.firstOrNull()?.entityId
                        ?: loaded.collection.conflictEntityIds.firstOrNull()
                        ?: loaded.collection.corruptedEntityIds.firstOrNull()
                        ?: PersonalTierList.defaultEntityId(profileId)
                }
            } else {
                selectedEntityId = initialEntityId
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            loadError = error.message ?: "私人梯度榜读取失败，请重试。"
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        val bytes = pendingExportBytes
        pendingExportBytes = null
        if (uri != null && bytes != null) {
            saving = true
            message = null
            messageError = false
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use {
                            it.write(bytes)
                        }
                    }
                    message = "私人梯度榜已导出为 JSON 文件。"
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    messageError = true
                    message = "导出失败：${error.message ?: "无法写入文件"}"
                } finally {
                    saving = false
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            saving = true
            message = null
            messageError = false
            scope.launch {
                try {
                    val imported = withContext(Dispatchers.IO) {
                        val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                            readBoundedTierBytes(input)
                        }
                        PersonalTierList.decodeExport(bytes, profileId)
                    }
                    pendingImportList = imported
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    messageError = true
                    message = "导入文件无效，未改动已有梯度榜：${error.message ?: "格式错误"}"
                } finally {
                    saving = false
                }
            }
        }
    }

    fun persistList(
        targetEntityId: String,
        updated: PersonalTierList,
        expectedParents: Set<String>,
        successMessage: String,
        deleted: Boolean = false,
        onComplete: (PersonalSnapshot) -> Unit = {},
    ) {
        if (saving) return
        saving = true
        message = null
        messageError = false
        scope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) {
                    store.edit(
                        entityId = targetEntityId,
                        kind = PersonalTierList.KIND,
                        payload = updated.payload(),
                        deleted = deleted,
                        expectedParents = expectedParents,
                    )
                }
                val current = ready
                if (current != null) {
                    ready = current.copy(
                        snapshot = snapshot,
                        collection = PersonalTierList.readProfile(snapshot, profileId),
                    )
                }
                message = successMessage
                messageError = false
                onComplete(snapshot)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                messageError = true
                message = "保存未成功，原梯度榜仍保留：${error.message ?: "请刷新后再试"}"
                retry++
            } finally {
                saving = false
            }
        }
    }

    BackHandler(onBack = onBack)

    val currentReady = ready
    val activeId = selectedEntityId ?: PersonalTierList.defaultEntityId(profileId)
    val activeRecord: PersonalTierRecord? = currentReady?.collection?.lists?.firstOrNull { it.entityId == activeId }
    val isConflicted = currentReady != null && activeId in currentReady.collection.conflictEntityIds
    val isCorrupted = currentReady != null && activeId in currentReady.collection.corruptedEntityIds
    val wrongProfileRecord = currentReady != null && initialEntityId != null &&
        activeRecord == null && !isConflicted && !isCorrupted &&
        currentReady.snapshot.heads()[initialEntityId]?.any { it.kind == PersonalTierList.KIND } == true
    val activeList: PersonalTierList? = when {
        currentReady == null -> null
        isConflicted || isCorrupted || wrongProfileRecord -> null
        activeRecord != null -> activeRecord.list
        else -> PersonalTierList(profileId = profileId, name = "我的私人梯度榜")
    }
    val canEdit = currentReady != null && activeList != null && !isConflicted && !isCorrupted && !saving
    val expectedParents: Set<String> = currentReady?.snapshot?.heads()?.get(activeId)
        .orEmpty().map(PersonalRevision::revisionId).toSet()

    val gridReady = currentReady != null
    val gridState = rememberReadingGridState(
        key = "tier/$profileId/$activeId/$mode/$query/$pickFilter",
        ready = gridReady,
    )

    TierAdaptiveGrid(state = gridState) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("tier-back"),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                    Text(if (initialEntityId != null) "返回个人记录" else "返回工具")
                }
                ScreenHeading("私人梯度榜", "按个人喜好给角色分档、排序与记录培养评价。")
            }
        }

        if (currentReady == null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                if (loadError == null) {
                    Row(
                        Modifier.fillMaxWidth().padding(24.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator() }
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(requireNotNull(loadError), color = MaterialTheme.colorScheme.onErrorContainer)
                            TextButton(onClick = { retry++ }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text("重试")
                            }
                        }
                    }
                }
            }
        } else {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                "个人主观分档 · 仅保存在当前个人存档，不代表官方或站点评级",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.testTag("tier-disclaimer"),
                            )
                            Text(
                                "当前存档：${currentReady.profileName} · 已保存 ${currentReady.collection.lists.size} 份梯度榜",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
                            )
                        }
                    }

                    if (repository == null) {
                        TierNoticeCard(
                            "离线角色图鉴尚未就绪，可先查看已保存记录；待图鉴就绪后可添加新角色。",
                            isError = false,
                        )
                    }

                    if (currentReady.collection.conflictEntityIds.isNotEmpty() ||
                        currentReady.collection.corruptedEntityIds.isNotEmpty()
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(14.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Outlined.WarningAmber,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                )
                                Text(
                                    "检测到梯度榜存在设备间冲突或损坏修订。受影响榜单已锁定编辑，请到“我的 → 个人记录”核对版本后再继续。",
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f).testTag("tier-conflict-banner"),
                                )
                            }
                        }
                    }

                    if (wrongProfileRecord) {
                        TierNoticeCard(
                            "这条梯度榜属于其他个人存档或已被删除，无法在当前存档下编辑。",
                            isError = true,
                        )
                    }

                    if (currentReady.collection.lists.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            currentReady.collection.lists.forEach { record ->
                                FilterChip(
                                    selected = record.entityId == activeId,
                                    onClick = { selectedEntityId = record.entityId },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                    label = { Text("${record.list.name}（${record.list.entries.size}）") },
                                )
                            }
                        }
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        FilterChip(
                            selected = mode == "board",
                            onClick = { mode = "board" },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("tier-mode-board"),
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.Leaderboard,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            label = { Text("分档总览") },
                        )
                        FilterChip(
                            selected = mode == "pick",
                            onClick = { mode = "pick" },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("tier-mode-pick"),
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.Person,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            label = { Text("选择角色") },
                        )
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        OutlinedButton(
                            enabled = !saving,
                            onClick = {
                                creatingNewList = true
                                metaName = ""
                                metaNote = ""
                                showListMetaDialog = true
                            },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("tier-new-list"),
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("新建榜单")
                        }
                        if (activeList != null) {
                            OutlinedButton(
                                enabled = canEdit,
                                onClick = {
                                    creatingNewList = false
                                    metaName = activeList.name
                                    metaNote = activeList.note
                                    showListMetaDialog = true
                                },
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Icon(Icons.Outlined.EditNote, contentDescription = null, modifier = Modifier.size(18.dp))
                                Text("名称与说明")
                            }
                            OutlinedButton(
                                enabled = canEdit,
                                onClick = { showTierManager = true },
                                modifier = Modifier.heightIn(min = 48.dp).testTag("tier-manage-tiers"),
                            ) {
                                Icon(Icons.Outlined.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                                Text("管理档位")
                            }
                            OutlinedButton(
                                enabled = !saving,
                                onClick = {
                                    runCatching { activeList.encodeExport() }.onSuccess { bytes ->
                                        pendingExportBytes = bytes
                                        exportLauncher.launch("${activeList.name}.json")
                                    }.onFailure { err ->
                                        messageError = true
                                        message = err.message ?: "无法导出梯度榜"
                                    }
                                },
                                modifier = Modifier.heightIn(min = 48.dp).testTag("tier-export"),
                            ) {
                                Icon(Icons.Outlined.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                                Text("导出 JSON")
                            }
                        }
                        OutlinedButton(
                            enabled = !saving,
                            onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("tier-import"),
                        ) {
                            Icon(Icons.Outlined.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("导入 JSON")
                        }
                        if (activeRecord != null && canEdit) {
                            TextButton(
                                enabled = canEdit,
                                onClick = { confirmDeleteList = true },
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Text("删除榜单", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }

                    message?.let { TierNoticeCard(it, isError = messageError) }
                }
            }

            if (activeList != null) {
                if (mode == "board") {
                    if (activeList.note.isNotBlank()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                shape = RoundedCornerShape(16.dp),
                            ) {
                                Text(
                                    activeList.note,
                                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    activeList.tiers.forEach { tier ->
                        val tierEntries = activeList.entriesForTier(tier.id)
                        item(
                            key = "tier-header:${tier.id}",
                            span = { GridItemSpan(maxLineSpan) },
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        shape = RoundedCornerShape(10.dp),
                                    ) {
                                        Text(
                                            tier.label,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        )
                                    }
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            tier.description.ifBlank { "主观分档 ${tier.label}" },
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.semantics { heading() },
                                        )
                                        Text(
                                            "${tierEntries.size} 名角色",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }

                        if (tierEntries.isEmpty()) {
                            item(
                                key = "tier-empty:${tier.id}",
                                span = { GridItemSpan(maxLineSpan) },
                            ) {
                                Text(
                                    "该档位暂未放入角色，可在“选择角色”中添加。",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            itemsIndexed(
                                tierEntries,
                                key = { _, entry -> "board:${tier.id}:${entry.characterId}" },
                            ) { index, entry ->
                                val wikiEntry = currentReady.charactersById[entry.characterId]
                                val displayName = wikiEntry?.title ?: "已不在当前图鉴版本（${entry.characterId}）"
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    shape = RoundedCornerShape(18.dp),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                                    modifier = Modifier.fillMaxWidth().testTag("tier-entry:${entry.characterId}"),
                                ) {
                                    Column(
                                        Modifier.fillMaxWidth().padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        ) {
                                            if (wikiEntry != null) {
                                                WikiImage(
                                                    path = wikiEntry.imageAsset,
                                                    description = "${wikiEntry.title}头像",
                                                    modifier = Modifier.size(56.dp),
                                                    crop = true,
                                                )
                                            } else {
                                                Surface(
                                                    modifier = Modifier.size(56.dp),
                                                    shape = RoundedCornerShape(12.dp),
                                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                                ) {
                                                    Icon(
                                                        Icons.Outlined.WarningAmber,
                                                        contentDescription = "已不在当前图鉴版本",
                                                        modifier = Modifier.padding(14.dp),
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            }
                                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                Text(displayName, style = MaterialTheme.typography.titleSmall)
                                                Text(
                                                    "${tier.label} 档 · 第 ${index + 1} 位",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.primary,
                                                )
                                            }
                                        }
                                        if (entry.note.isNotBlank()) {
                                            Text(
                                                entry.note,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            IconButton(
                                                enabled = canEdit && index > 0,
                                                onClick = {
                                                    persistList(
                                                        activeId,
                                                        activeList.moveWithinTier(entry.characterId, -1),
                                                        expectedParents,
                                                        "已上移 $displayName。",
                                                    )
                                                },
                                                modifier = Modifier
                                                    .size(48.dp)
                                                    .testTag("tier-move-up:${entry.characterId}"),
                                            ) {
                                                Icon(
                                                    Icons.Outlined.ArrowUpward,
                                                    contentDescription = "在档位内上移 $displayName",
                                                )
                                            }
                                            IconButton(
                                                enabled = canEdit && index < tierEntries.lastIndex,
                                                onClick = {
                                                    persistList(
                                                        activeId,
                                                        activeList.moveWithinTier(entry.characterId, 1),
                                                        expectedParents,
                                                        "已下移 $displayName。",
                                                    )
                                                },
                                                modifier = Modifier
                                                    .size(48.dp)
                                                    .testTag("tier-move-down:${entry.characterId}"),
                                            ) {
                                                Icon(
                                                    Icons.Outlined.ArrowDownward,
                                                    contentDescription = "在档位内下移 $displayName",
                                                )
                                            }
                                            TextButton(
                                                enabled = canEdit,
                                                onClick = {
                                                    editingCharacter = wikiEntry
                                                    editingCharacterId = entry.characterId
                                                    draftTierId = entry.tierId
                                                    draftNote = entry.note
                                                },
                                                modifier = Modifier.heightIn(min = 48.dp),
                                            ) { Text("编辑评价") }
                                            if (wikiEntry != null) {
                                                TextButton(
                                                    onClick = { onOpenWiki(wikiEntry.id) },
                                                    modifier = Modifier
                                                        .heightIn(min = 48.dp)
                                                        .testTag("tier-open-wiki:${wikiEntry.id}"),
                                                ) { Text("看图鉴") }
                                            }
                                            IconButton(
                                                enabled = canEdit,
                                                onClick = {
                                                    persistList(
                                                        activeId,
                                                        activeList.remove(entry.characterId),
                                                        expectedParents,
                                                        "已将 $displayName 移出梯度榜。",
                                                    )
                                                },
                                                modifier = Modifier.size(48.dp),
                                            ) {
                                                Icon(
                                                    Icons.Outlined.DeleteOutline,
                                                    contentDescription = "将 $displayName 移出梯度榜",
                                                    tint = MaterialTheme.colorScheme.error,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                modifier = Modifier.fillMaxWidth().testTag("tier-search"),
                                singleLine = true,
                                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                                label = { Text("搜索角色名称或副标题") },
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                FilterChip(
                                    selected = pickFilter == "all",
                                    onClick = { pickFilter = "all" },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                    label = { Text("全部（${currentReady.characters.size}）") },
                                )
                                FilterChip(
                                    selected = pickFilter == "unassigned",
                                    onClick = { pickFilter = "unassigned" },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                    label = { Text("未分档") },
                                )
                                FilterChip(
                                    selected = pickFilter == "assigned",
                                    onClick = { pickFilter = "assigned" },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                    label = { Text("已分档（${activeList.entries.size}）") },
                                )
                            }
                        }
                    }

                    val trimmed = query.trim()
                    val filteredCharacters = currentReady.characters.filter { character ->
                        val assigned = activeList.entryFor(character.id) != null
                        val matchesFilter = when (pickFilter) {
                            "unassigned" -> !assigned
                            "assigned" -> assigned
                            else -> true
                        }
                        val matchesQuery = trimmed.isEmpty() ||
                            character.title.contains(trimmed, ignoreCase = true) ||
                            character.subtitle.contains(trimmed, ignoreCase = true)
                        matchesFilter && matchesQuery
                    }

                    if (filteredCharacters.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                if (currentReady.characters.isEmpty()) "当前没有可浏览的离线角色条目。" else "没有匹配的角色，试试调整搜索词或筛选。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        items(filteredCharacters, key = { "pick:${it.id}" }) { character ->
                            val existing = activeList.entryFor(character.id)
                            val tierLabel = existing?.let { entry ->
                                activeList.tiers.firstOrNull { it.id == entry.tierId }?.label
                            }
                            Card(
                                onClick = {
                                    focusManager.clearFocus()
                                    if (canEdit) {
                                        editingCharacter = character
                                        editingCharacterId = character.id
                                        draftTierId = existing?.tierId ?: activeList.tiers.first().id
                                        draftNote = existing?.note.orEmpty()
                                    }
                                },
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                shape = RoundedCornerShape(18.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                                modifier = Modifier.fillMaxWidth().testTag("tier-pick:${character.id}"),
                            ) {
                                Column(
                                    Modifier.fillMaxWidth().padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        WikiImage(
                                            path = character.imageAsset,
                                            description = "${character.title}头像",
                                            modifier = Modifier.size(52.dp),
                                            crop = true,
                                        )
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(character.title, style = MaterialTheme.typography.titleSmall)
                                            if (character.subtitle.isNotBlank()) {
                                                Text(
                                                    character.subtitle,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            if (tierLabel != null) "当前：$tierLabel 档" else "尚未分档",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (tierLabel != null) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        TextButton(
                                            onClick = { onOpenWiki(character.id) },
                                            modifier = Modifier.heightIn(min = 48.dp),
                                        ) { Text("看图鉴") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val targetCharacterId = editingCharacterId
    if (targetCharacterId != null && activeList != null) {
        val displayTitle = editingCharacter?.title ?: "已不在当前图鉴版本（$targetCharacterId）"
        val existingEntry = activeList.entryFor(targetCharacterId)
        AlertDialog(
            onDismissRequest = { if (!saving) editingCharacterId = null },
            title = { Text("$displayTitle · 个人分档") },
            text = {
                Column(
                    Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("选择档位", style = MaterialTheme.typography.labelLarge)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        activeList.tiers.forEach { tier ->
                            FilterChip(
                                selected = draftTierId == tier.id,
                                onClick = { draftTierId = tier.id },
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .semantics { contentDescription = "分档 ${tier.label}" }
                                    .testTag("tier-dialog-choice:${tier.id}"),
                                label = { Text(tier.label) },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = draftNote,
                        onValueChange = { draftNote = it.take(500) },
                        modifier = Modifier.fillMaxWidth().testTag("tier-dialog-note"),
                        minLines = 2,
                        maxLines = 5,
                        label = { Text("个人培养评价（选填）") },
                    )
                    if (existingEntry != null) {
                        TextButton(
                            enabled = canEdit,
                            onClick = {
                                val next = activeList.remove(targetCharacterId)
                                persistList(activeId, next, expectedParents, "已将 $displayTitle 移出梯度榜。") {
                                    editingCharacterId = null
                                }
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text("移出梯度榜", color = MaterialTheme.colorScheme.error) }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = canEdit && activeList.tiers.any { it.id == draftTierId },
                    onClick = {
                        val next = activeList.assign(targetCharacterId, draftTierId, draftNote)
                        persistList(activeId, next, expectedParents, "已保存 $displayTitle 的分档。") {
                            editingCharacterId = null
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("tier-dialog-save"),
                ) { Text("保存分档") }
            },
            dismissButton = {
                TextButton(
                    enabled = !saving,
                    onClick = { editingCharacterId = null },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("取消") }
            },
        )
    }

    if (showListMetaDialog) {
        AlertDialog(
            onDismissRequest = { if (!saving) showListMetaDialog = false },
            title = { Text(if (creatingNewList) "新建私人梯度榜" else "编辑梯度榜信息") },
            text = {
                Column(
                    Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = metaName,
                        onValueChange = { metaName = it.take(80) },
                        modifier = Modifier.fillMaxWidth().testTag("tier-meta-name"),
                        singleLine = true,
                        label = { Text("榜单名称") },
                    )
                    OutlinedTextField(
                        value = metaNote,
                        onValueChange = { metaNote = it.take(2000) },
                        modifier = Modifier.fillMaxWidth().testTag("tier-meta-note"),
                        minLines = 2,
                        maxLines = 4,
                        label = { Text("榜单说明（选填）") },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !saving && metaName.trim().isNotEmpty(),
                    onClick = {
                        if (creatingNewList) {
                            val newId = UUID.randomUUID().toString()
                            val created = PersonalTierList(
                                profileId = profileId,
                                name = metaName.trim(),
                                note = metaNote.trim(),
                            )
                            persistList(newId, created, emptySet(), "已新建梯度榜“${created.name}”。") {
                                selectedEntityId = newId
                                showListMetaDialog = false
                            }
                        } else if (activeList != null) {
                            val updated = activeList.updateMeta(metaName, metaNote)
                            persistList(activeId, updated, expectedParents, "已更新梯度榜名称与说明。") {
                                showListMetaDialog = false
                            }
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("tier-meta-save"),
                ) { Text(if (creatingNewList) "创建榜单" else "保存") }
            },
            dismissButton = {
                TextButton(
                    enabled = !saving,
                    onClick = { showListMetaDialog = false },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("取消") }
            },
        )
    }

    if (showTierManager && activeList != null) {
        AlertDialog(
            onDismissRequest = { if (!saving) showTierManager = false },
            title = { Text("管理分档") },
            text = {
                Column(
                    Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "删除档位会清除该档内角色的分档归属，不会删除角色图鉴资料。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    activeList.tiers.forEach { tier ->
                        if (editingTierId == tier.id) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedTextField(
                                    value = editTierLabel,
                                    onValueChange = { editTierLabel = it.take(24) },
                                    singleLine = true,
                                    label = { Text("档位名称") },
                                )
                                OutlinedTextField(
                                    value = editTierDescription,
                                    onValueChange = { editTierDescription = it.take(200) },
                                    singleLine = true,
                                    label = { Text("说明") },
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(
                                        enabled = canEdit && editTierLabel.trim().isNotEmpty(),
                                        onClick = {
                                            runCatching {
                                                activeList.renameTier(tier.id, editTierLabel, editTierDescription)
                                            }.onSuccess { updated ->
                                                persistList(activeId, updated, expectedParents, "已更新档位。") {
                                                    editingTierId = null
                                                }
                                            }.onFailure { err ->
                                                messageError = true
                                                message = err.message
                                            }
                                        },
                                        modifier = Modifier.heightIn(min = 48.dp),
                                    ) { Text("保存") }
                                    TextButton(
                                        onClick = { editingTierId = null },
                                        modifier = Modifier.heightIn(min = 48.dp),
                                    ) { Text("取消") }
                                }
                            }
                        } else {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(tier.label, style = MaterialTheme.typography.titleSmall)
                                    if (tier.description.isNotBlank()) {
                                        Text(
                                            tier.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                TextButton(
                                    enabled = canEdit,
                                    onClick = {
                                        editingTierId = tier.id
                                        editTierLabel = tier.label
                                        editTierDescription = tier.description
                                    },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) { Text("改名") }
                                TextButton(
                                    enabled = canEdit && activeList.tiers.size > 1,
                                    onClick = {
                                        persistList(
                                            activeId,
                                            activeList.removeTier(tier.id),
                                            expectedParents,
                                            "已移除档位 ${tier.label}。",
                                        )
                                    },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                        HorizontalDivider()
                    }

                    Text("新增档位", style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(
                        value = newTierLabel,
                        onValueChange = { newTierLabel = it.take(24) },
                        modifier = Modifier.fillMaxWidth().testTag("tier-add-label"),
                        singleLine = true,
                        label = { Text("新档位名称（如 T0）") },
                    )
                    OutlinedTextField(
                        value = newTierDescription,
                        onValueChange = { newTierDescription = it.take(200) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("新档位说明（选填）") },
                    )
                    Button(
                        enabled = canEdit && newTierLabel.trim().isNotEmpty() &&
                            activeList.tiers.size < PersonalTierList.MAX_TIERS,
                        onClick = {
                            runCatching {
                                activeList.addTier(newTierLabel, newTierDescription)
                            }.onSuccess { updated ->
                                persistList(activeId, updated, expectedParents, "已添加档位 ${newTierLabel.trim()}。") {
                                    newTierLabel = ""
                                    newTierDescription = ""
                                }
                            }.onFailure { err ->
                                messageError = true
                                message = err.message
                            }
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("tier-add-submit"),
                    ) { Text("添加档位") }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !saving,
                    onClick = { showTierManager = false },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("关闭") }
            },
        )
    }

    if (confirmDeleteList && activeList != null && activeRecord != null) {
        AlertDialog(
            onDismissRequest = { if (!saving) confirmDeleteList = false },
            title = { Text("删除梯度榜“${activeList.name}”") },
            text = { Text("仅将此榜单标记为已删除并参与多设备同步，不影响图鉴或游戏资料。") },
            confirmButton = {
                TextButton(
                    enabled = canEdit,
                    onClick = {
                        persistList(
                            activeId,
                            activeList,
                            expectedParents,
                            "已删除梯度榜“${activeList.name}”。",
                            deleted = true,
                        ) {
                            confirmDeleteList = false
                            selectedEntityId = null
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("确认删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(
                    enabled = !saving,
                    onClick = { confirmDeleteList = false },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("取消") }
            },
        )
    }

    pendingImportList?.let { preview ->
        AlertDialog(
            onDismissRequest = { if (!saving) pendingImportList = null },
            title = { Text("确认导入私人梯度榜") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(preview.name, style = MaterialTheme.typography.titleMedium)
                    Text("包含 ${preview.tiers.size} 个档位、${preview.entries.size} 名角色。")
                    Text(
                        "将在当前存档“${currentReady?.profileName ?: profileId}”下新建一份独立榜单，不会覆盖现有榜单。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !saving,
                    onClick = {
                        val newId = UUID.randomUUID().toString()
                        persistList(newId, preview, emptySet(), "已导入并新建梯度榜“${preview.name}”。") {
                            selectedEntityId = newId
                            pendingImportList = null
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("tier-import-confirm"),
                ) { Text("确认新建并导入") }
            },
            dismissButton = {
                TextButton(
                    enabled = !saving,
                    onClick = { pendingImportList = null },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("取消") }
            },
        )
    }
}

@Composable
private fun TierAdaptiveGrid(state: LazyGridState, content: LazyGridScope.() -> Unit) {
    BoxWithConstraints(
        Modifier.fillMaxSize().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 1040.dp),
    ) {
        val columns = when {
            maxWidth < 332.dp -> 1
            maxWidth < 600.dp -> 2
            maxWidth < 900.dp -> 3
            else -> 4
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = state,
            modifier = Modifier.fillMaxSize().testTag("tier-grid"),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun TierNoticeCard(text: String, isError: Boolean) {
    Surface(
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(14.dp),
    ) {
        Text(
            text,
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun readBoundedTierBytes(input: java.io.InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val chunk = ByteArray(8192)
    var count = input.read(chunk)
    while (count >= 0) {
        if (output.size() + count > PersonalTierList.MAX_EXPORT_BYTES) {
            throw IOException("梯度榜文件超过 1 MiB 上限")
        }
        output.write(chunk, 0, count)
        count = input.read(chunk)
    }
    return output.toByteArray()
}
