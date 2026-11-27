package com.fireemblem.companion.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.CompareArrows
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fireemblem.companion.content.StatRatingTier
import com.fireemblem.companion.content.WikiEntry
import com.fireemblem.companion.content.WikiPageRequest
import com.fireemblem.companion.content.WikiRepository
import com.fireemblem.companion.engine.BuddyCampaignLine
import com.fireemblem.companion.engine.GrowthStat
import com.fireemblem.companion.engine.LevelJournal
import com.fireemblem.companion.ocr.OcrModelManager
import com.fireemblem.companion.ocr.OcrModelStatus
import com.fireemblem.companion.ocr.ScreenshotStatRecognizer
import com.fireemblem.companion.personal.PersonalStore
import com.fireemblem.companion.personal.RouteMergeCharacter
import com.fireemblem.companion.personal.RouteMergeCollection
import com.fireemblem.companion.personal.RouteMergeComparison
import com.fireemblem.companion.personal.RouteMergeOverviewProjector
import com.fireemblem.companion.personal.RouteMergeOverviewRow
import com.fireemblem.companion.personal.RouteMergeOverviewSort
import com.fireemblem.companion.personal.RouteMergeSnapshot
import com.fireemblem.companion.personal.RouteMergeStoredRecord
import com.fireemblem.companion.ui.theme.tabularNumbers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class RouteMergeRosterState(
    val profileId: String = "global",
    val characters: List<WikiEntry> = emptyList(),
    val collection: RouteMergeCollection = RouteMergeCollection(emptyList(), emptyList(), emptyList()),
    val journals: List<LevelJournal> = emptyList(),
    val ready: Boolean = false,
)

@Composable
internal fun RouteMergeScreen(
    repository: WikiRepository?,
    onBack: () -> Unit,
    onOpenWiki: (String) -> Unit = {},
    initialCharacterId: String? = null,
    ocrModelManager: OcrModelManager? = null,
    ocrRecognizerFactory: (() -> ScreenshotStatRecognizer)? = null,
    initialImportUris: List<Uri>? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val resolvedOcrManager = remember(context, ocrModelManager) { ocrModelManager ?: OcrModelManager(context) }
    var reloadToken by remember { mutableIntStateOf(0) }
    var activeMode by rememberSaveable(initialCharacterId) {
        mutableStateOf(if (!initialCharacterId.isNullOrBlank()) "detail" else "overview")
    }
    var enteredDetailFromOverview by rememberSaveable { mutableStateOf(false) }

    // OCR batch import state
    var pendingOcrUris by remember(initialImportUris) { mutableStateOf(initialImportUris.orEmpty()) }
    var showOcrDownloadPrompt by remember { mutableStateOf(false) }
    var installedOcrStatus by remember { mutableStateOf<OcrModelStatus.Installed?>(null) }

    val ocrImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            pendingOcrUris = uris
        }
    }

    // Detail pane state
    var search by rememberSaveable { mutableStateOf("") }
    var rosterFilter by rememberSaveable { mutableStateOf("all") } // all | recorded | multi
    var selectedCharacterId by rememberSaveable(initialCharacterId) { mutableStateOf(initialCharacterId) }

    // Overview pane persistent UI state
    var globalLinesEncoded by rememberSaveable {
        mutableStateOf(BuddyCampaignLine.entries.joinToString(",") { it.id })
    }
    var respectPerCharacterOverride by rememberSaveable { mutableStateOf(true) }
    var sortKey by rememberSaveable { mutableStateOf(RouteMergeOverviewSort.SelectedTotalDesc.key) }
    var overviewDensity by rememberSaveable { mutableStateOf("auto") } // auto | table | cards
    var overviewSearch by rememberSaveable { mutableStateOf("") }
    var editingOverrideCharId by remember { mutableStateOf<String?>(null) }

    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    val state by produceState(RouteMergeRosterState(), context, repository, reloadToken) {
        value = withContext(Dispatchers.IO) {
            val profileId = context.getSharedPreferences("personal-view", 0)
                .getString("profile", "global") ?: "global"
            val store = PersonalStore(context)
            val snap = runCatching { store.snapshot() }.getOrNull()
            val collection = snap?.let { RouteMergeCharacter.readProfile(it, profileId) }
                ?: RouteMergeCollection(emptyList(), emptyList(), emptyList())
            val journals = snap?.heads()?.values?.mapNotNull { it.singleOrNull() }
                ?.filter { !it.deleted && it.kind == LevelJournal.KIND }
                ?.mapNotNull { rev -> runCatching { LevelJournal.decode(rev.payload) }.getOrNull() }
                ?.filter { it.profile == profileId }
                .orEmpty()
            val chars = repository?.let { repo ->
                buildList {
                    var offset = 0
                    while (true) {
                        val page = runCatching {
                            repo.page(WikiPageRequest(categoryId = "characters", offset = offset, limit = 100))
                        }.getOrNull() ?: break
                        addAll(page.entries)
                        if (!page.hasNext || page.entries.isEmpty()) break
                        offset += page.entries.size
                    }
                }
            }.orEmpty()
            RouteMergeRosterState(
                profileId = profileId,
                characters = chars,
                collection = collection,
                journals = journals,
                ready = true,
            )
        }
    }

    LaunchedEffect(initialCharacterId) {
        if (!initialCharacterId.isNullOrBlank()) {
            selectedCharacterId = initialCharacterId
            activeMode = "detail"
            enteredDetailFromOverview = false
        }
    }

    val recordByCharId = remember(state.collection) {
        state.collection.records.associateBy { it.character.characterId }
    }
    val entryByCharId = remember(state.characters) {
        state.characters.associateBy { it.id }
    }
    val globalSelectedLines = remember(globalLinesEncoded) {
        val parsed = globalLinesEncoded.split(",")
            .mapNotNull { id -> BuddyCampaignLine.entries.firstOrNull { it.id == id } }
            .toCollection(LinkedHashSet())
        parsed.ifEmpty { BuddyCampaignLine.entries.toCollection(LinkedHashSet()) }
    }
    val activeSort = remember(sortKey) { RouteMergeOverviewSort.fromKey(sortKey) }

    fun saveCharacterRecord(
        characterId: String,
        updated: RouteMergeCharacter,
        existing: RouteMergeStoredRecord?,
        successHint: String,
    ) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val store = PersonalStore(context)
                    val entityId = existing?.entityId ?: RouteMergeCharacter.entityId(state.profileId, characterId)
                    val currentHeads = store.snapshot().heads()[entityId].orEmpty().map { it.revisionId }.toSet()
                    if (updated.snapshots.isEmpty() && currentHeads.isNotEmpty()) {
                        store.edit(
                            entityId = entityId,
                            kind = RouteMergeCharacter.KIND,
                            payload = updated.payload(),
                            deleted = true,
                            expectedParents = currentHeads,
                        )
                    } else {
                        store.edit(
                            entityId = entityId,
                            kind = RouteMergeCharacter.KIND,
                            payload = updated.payload(),
                            deleted = false,
                            expectedParents = currentHeads,
                        )
                    }
                }
            }
            result.onSuccess {
                isError = false
                statusMessage = successHint
                reloadToken++
            }.onFailure { err ->
                isError = true
                statusMessage = "保存失败：${err.message ?: "请检查输入内容"}"
            }
        }
    }

    fun launchOcrImport() {
        if (ocrRecognizerFactory != null) {
            ocrImagePicker.launch(arrayOf("image/*"))
            return
        }
        scope.launch {
            val st = withContext(Dispatchers.IO) { resolvedOcrManager.status() }
            when (st) {
                is OcrModelStatus.Installed -> {
                    installedOcrStatus = st
                    ocrImagePicker.launch(arrayOf("image/*"))
                }
                OcrModelStatus.NotInstalled -> {
                    showOcrDownloadPrompt = true
                }
            }
        }
    }

    fun commitOcrBatch(entries: List<OcrBatchCommitEntry>) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val store = PersonalStore(context)
                    val grouped = entries.groupBy { it.characterId }
                    var snapshotCount = 0
                    grouped.forEach { (charId, charEntries) ->
                        val currentSnap = store.snapshot()
                        val collection = RouteMergeCharacter.readProfile(currentSnap, state.profileId)
                        val existingRec = collection.records.firstOrNull { it.character.characterId == charId }
                        val entityId = existingRec?.entityId ?: RouteMergeCharacter.entityId(state.profileId, charId)
                        val currentHeads = currentSnap.heads()[entityId].orEmpty().map { it.revisionId }.toSet()
                        var record = existingRec?.character ?: RouteMergeCharacter(
                            profileId = state.profileId,
                            characterId = charId,
                            characterName = charEntries.first().characterName,
                            snapshots = emptyMap(),
                            selectedLines = null,
                        )
                        charEntries.forEach { entry ->
                            val existingNote = record.snapshots[entry.snapshot.line]?.note.orEmpty()
                            val snapWithNote = if (entry.snapshot.note.isBlank() && existingNote.isNotBlank()) {
                                entry.snapshot.copy(note = existingNote)
                            } else {
                                entry.snapshot
                            }
                            record = record.withSnapshot(snapWithNote)
                            snapshotCount++
                        }
                        store.edit(
                            entityId = entityId,
                            kind = RouteMergeCharacter.KIND,
                            payload = record.payload(),
                            deleted = false,
                            expectedParents = currentHeads,
                        )
                    }
                    grouped.size to snapshotCount
                }
            }
            result.onSuccess { (charCount, snapCount) ->
                pendingOcrUris = emptyList()
                activeMode = "overview"
                enteredDetailFromOverview = false
                isError = false
                statusMessage = "已批量写入 $charCount 名角色 · 共 $snapCount 条路线快照"
                reloadToken++
            }.onFailure { err ->
                isError = true
                statusMessage = "批量写入失败：${err.message ?: "请稍后重试"}"
            }
        }
    }

    if (showOcrDownloadPrompt) {
        OcrModelDownloadPromptDialog(
            manager = resolvedOcrManager,
            onDismiss = { showOcrDownloadPrompt = false },
            onReady = { installed ->
                showOcrDownloadPrompt = false
                installedOcrStatus = installed
                ocrImagePicker.launch(arrayOf("image/*"))
            },
        )
    }

    val overrideRecord = editingOverrideCharId?.let { recordByCharId[it] }
    if (overrideRecord != null) {
        RouteMergeQuickOverrideDialog(
            record = overrideRecord.character,
            onDismiss = { editingOverrideCharId = null },
            onSaveSelection = { nextSelection ->
                val updated = overrideRecord.character.withSelectedLines(nextSelection)
                val msg = if (nextSelection == null) {
                    "已恢复 ${updated.characterName} 跟随全局路线开关"
                } else {
                    val labels = BuddyCampaignLine.entries.filter { it in nextSelection }.joinToString(" + ") { it.shortTag() }
                    "已保存 ${updated.characterName} 自选融合路线：$labels"
                }
                editingOverrideCharId = null
                saveCharacterRecord(updated.characterId, updated, overrideRecord, msg)
            },
        )
    }

    if (pendingOcrUris.isNotEmpty()) {
        RouteMergeOcrImportSheet(
            uris = pendingOcrUris,
            repository = repository,
            characters = state.characters,
            recordByCharId = recordByCharId,
            modelStatus = installedOcrStatus,
            modelManager = resolvedOcrManager,
            recognizerFactory = ocrRecognizerFactory,
            onCancel = { pendingOcrUris = emptyList() },
            onCommitBatch = ::commitOcrBatch,
        )
        return
    }

    BoxWithConstraints(Modifier.fillMaxSize().testTag("route-merge-screen")) {
        val wide = maxWidth >= 720.dp
        val activeEntry = state.characters.firstOrNull { it.id == selectedCharacterId }
            ?: if (wide) state.characters.firstOrNull() else null
        val activeId = activeEntry?.id ?: selectedCharacterId
        val activeName = activeEntry?.title
            ?: activeId?.let { recordByCharId[it]?.character?.characterName }
            ?: activeId?.substringAfter(':')

        BackHandler {
            when {
                activeMode == "detail" && enteredDetailFromOverview -> {
                    activeMode = "overview"
                    enteredDetailFromOverview = false
                    statusMessage = null
                }
                activeMode == "detail" && !wide && selectedCharacterId != null -> {
                    selectedCharacterId = null
                    statusMessage = null
                }
                else -> onBack()
            }
        }

        if (activeMode == "overview") {
            RouteMergeOverviewPane(
                state = state,
                entryByCharId = entryByCharId,
                globalSelectedLines = globalSelectedLines,
                onToggleGlobalLine = { line ->
                    val next = LinkedHashSet(globalSelectedLines)
                    if (line in next) {
                        if (next.size <= 1) {
                            isError = false
                            statusMessage = "至少保留 1 条路线参与融合对比。"
                            return@RouteMergeOverviewPane
                        }
                        next.remove(line)
                    } else {
                        next.add(line)
                    }
                    globalLinesEncoded = BuddyCampaignLine.entries.filter { it in next }.joinToString(",") { it.id }
                    statusMessage = null
                },
                onSelectAllGlobalLines = {
                    globalLinesEncoded = BuddyCampaignLine.entries.joinToString(",") { it.id }
                    statusMessage = null
                },
                respectPerCharacterOverride = respectPerCharacterOverride,
                onRespectOverrideChange = { respectPerCharacterOverride = it },
                sort = activeSort,
                onSortChange = { sortKey = it.key },
                density = overviewDensity,
                onDensityChange = { overviewDensity = it },
                search = overviewSearch,
                onSearchChange = { overviewSearch = it },
                statusMessage = statusMessage,
                isError = isError,
                wideDefault = wide,
                onSwitchToDetail = {
                    activeMode = "detail"
                    enteredDetailFromOverview = false
                    statusMessage = null
                },
                onOpenCharacterDetail = { charId ->
                    selectedCharacterId = charId
                    activeMode = "detail"
                    enteredDetailFromOverview = true
                    statusMessage = null
                },
                onCustomizeCharacterLines = { charId ->
                    editingOverrideCharId = charId
                },
                onImportScreenshots = ::launchOcrImport,
                onBack = onBack,
            )
        } else if (wide) {
            Row(Modifier.fillMaxSize()) {
                RouteMergeRosterListPane(
                    state = state,
                    recordByCharId = recordByCharId,
                    search = search,
                    onSearchChange = { search = it },
                    filter = rosterFilter,
                    onFilterChange = { rosterFilter = it },
                    selectedCharacterId = activeId,
                    onSelectCharacter = { id ->
                        selectedCharacterId = id
                        statusMessage = null
                    },
                    onOpenOverview = {
                        activeMode = "overview"
                        enteredDetailFromOverview = false
                        statusMessage = null
                    },
                    onImportScreenshots = ::launchOcrImport,
                    onBack = onBack,
                    modifier = Modifier.weight(0.40f),
                )
                Box(Modifier.fillMaxHeight().width(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                if (activeId != null && !activeName.isNullOrBlank()) {
                    RouteMergeDetailPane(
                        profileId = state.profileId,
                        characterId = activeId,
                        characterName = activeName,
                        characterEntry = activeEntry,
                        repository = repository,
                        storedRecord = recordByCharId[activeId],
                        journals = state.journals,
                        statusMessage = statusMessage,
                        isError = isError,
                        showBackToList = enteredDetailFromOverview,
                        backLabel = if (enteredDetailFromOverview) "返回总览对比" else "返回角色列表",
                        onBackToList = {
                            if (enteredDetailFromOverview) {
                                activeMode = "overview"
                                enteredDetailFromOverview = false
                            } else {
                                selectedCharacterId = null
                            }
                            statusMessage = null
                        },
                        onOpenWiki = onOpenWiki,
                        onSave = { updated, msg ->
                            saveCharacterRecord(activeId, updated, recordByCharId[activeId], msg)
                        },
                        onMessage = { msg, err ->
                            statusMessage = msg
                            isError = err
                        },
                        modifier = Modifier.weight(0.60f),
                    )
                } else {
                    Column(
                        Modifier.weight(0.60f).fillMaxHeight().padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        EditorialCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("四线融合属性面板", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                                Text(
                                    "从左侧选择一位《万缕千丝》角色，填写他在凯伊篇、迪托利希篇、赛奥朵拉篇与蕾达篇的等级、职业和九项属性，即可自动高亮各线最强属性、对比自选路线并预览融合上限。",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        } else {
            if (activeId != null && !activeName.isNullOrBlank()) {
                RouteMergeDetailPane(
                    profileId = state.profileId,
                    characterId = activeId,
                    characterName = activeName,
                    characterEntry = activeEntry,
                    repository = repository,
                    storedRecord = recordByCharId[activeId],
                    journals = state.journals,
                    statusMessage = statusMessage,
                    isError = isError,
                    showBackToList = true,
                    backLabel = if (enteredDetailFromOverview) "返回总览对比" else "返回角色列表",
                    onBackToList = {
                        if (enteredDetailFromOverview) {
                            activeMode = "overview"
                            enteredDetailFromOverview = false
                        } else {
                            selectedCharacterId = null
                        }
                        statusMessage = null
                    },
                    onOpenWiki = onOpenWiki,
                    onSave = { updated, msg ->
                        saveCharacterRecord(activeId, updated, recordByCharId[activeId], msg)
                    },
                    onMessage = { msg, err ->
                        statusMessage = msg
                        isError = err
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                RouteMergeRosterListPane(
                    state = state,
                    recordByCharId = recordByCharId,
                    search = search,
                    onSearchChange = { search = it },
                    filter = rosterFilter,
                    onFilterChange = { rosterFilter = it },
                    selectedCharacterId = null,
                    onSelectCharacter = { id ->
                        selectedCharacterId = id
                        statusMessage = null
                    },
                    onOpenOverview = {
                        activeMode = "overview"
                        enteredDetailFromOverview = false
                        statusMessage = null
                    },
                    onImportScreenshots = ::launchOcrImport,
                    onBack = onBack,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun RouteMergeOverviewPane(
    state: RouteMergeRosterState,
    entryByCharId: Map<String, WikiEntry>,
    globalSelectedLines: Set<BuddyCampaignLine>,
    onToggleGlobalLine: (BuddyCampaignLine) -> Unit,
    onSelectAllGlobalLines: () -> Unit,
    respectPerCharacterOverride: Boolean,
    onRespectOverrideChange: (Boolean) -> Unit,
    sort: RouteMergeOverviewSort,
    onSortChange: (RouteMergeOverviewSort) -> Unit,
    density: String,
    onDensityChange: (String) -> Unit,
    search: String,
    onSearchChange: (String) -> Unit,
    statusMessage: String?,
    isError: Boolean,
    wideDefault: Boolean,
    onSwitchToDetail: () -> Unit,
    onOpenCharacterDetail: (String) -> Unit,
    onCustomizeCharacterLines: (String) -> Unit,
    onImportScreenshots: () -> Unit,
    onBack: () -> Unit,
) {
    val allRows = remember(state.collection.records, globalSelectedLines, respectPerCharacterOverride, sort) {
        RouteMergeOverviewProjector.project(
            records = state.collection.records,
            globalLines = globalSelectedLines,
            respectPerCharacterOverride = respectPerCharacterOverride,
            sort = sort,
        )
    }
    val visibleRows = remember(allRows, search) {
        val q = search.trim()
        if (q.isEmpty()) allRows
        else allRows.mapNotNull { row ->
            val ch = row.record.character
            val profs = ch.snapshots.values.joinToString(" ") { it.profession }
            val sc = com.fireemblem.companion.content.FuzzyMatcher.score(q, ch.characterName, profs)
            if (sc > 0) sc to row else null
        }.sortedByDescending { it.first }.map { it.second }
    }
    val customOverrideCount = remember(state.collection.records) {
        state.collection.records.count { it.character.hasCustomSelection }
    }
    val multiLineCount = remember(state.collection.records) {
        state.collection.records.count { it.character.recordedLines.size >= 2 }
    }
    val activeRowsCount = remember(allRows) { allRows.count { it.comparison.hasSelectedSnapshot } }
    val cumulativeDelta = remember(allRows) { allRows.filter { it.comparison.hasSelectedSnapshot }.sumOf { it.comparison.totalDelta } }
    val useTable = when (density) {
        "table" -> true
        "cards" -> false
        else -> wideDefault
    }
    val highPal = statTierPalette(StatRatingTier.HIGH)
    val lowPal = statTierPalette(StatRatingTier.LOW)
    val medPal = statTierPalette(StatRatingTier.MEDIUM)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("route-merge-overview"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(Modifier.height(6.dp)) }

        // Header + Top Mode Switcher
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                        Text("返回工具")
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            "多线融合总览",
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.semantics { heading() },
                        )
                        Text(
                            "已录 ${allRows.size} 人 · 多线 $multiLineCount 人" +
                                if (customOverrideCount > 0) " · 自定方案 $customOverrideCount 人" else "",
                            style = MaterialTheme.typography.bodySmall.tabularNumbers(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                RouteMergeModeSwitcher(
                    activeMode = "overview",
                    onSelectOverview = {},
                    onSelectDetail = onSwitchToDetail,
                    onImportScreenshots = onImportScreenshots,
                )
            }
        }

        // Global 4-Route Selection & Precedence Control Card
        item {
            EditorialCard(containerColor = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "参与融合路线",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (globalSelectedLines.size < BuddyCampaignLine.entries.size) {
                            TextButton(
                                onClick = onSelectAllGlobalLines,
                                modifier = Modifier.heightIn(min = 40.dp).testTag("route-merge-global-all"),
                            ) {
                                Text("全选四线")
                            }
                        }
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        BuddyCampaignLine.entries.forEach { line ->
                            val active = line in globalSelectedLines
                            val tone = routeLineTone(line)
                            Surface(
                                onClick = { onToggleGlobalLine(line) },
                                modifier = Modifier
                                    .heightIn(min = 44.dp)
                                    .semantics { selected = active }
                                    .testTag("route-merge-global-line:${line.id}"),
                                shape = RoundedCornerShape(12.dp),
                                color = if (active) tone.first else MaterialTheme.colorScheme.surfaceContainerLow,
                                contentColor = if (active) tone.second else MaterialTheme.colorScheme.onSurfaceVariant,
                                border = BorderStroke(
                                    width = if (active) 1.5.dp else 1.dp,
                                    color = if (active) tone.second.copy(alpha = 0.65f) else MaterialTheme.colorScheme.outlineVariant,
                                ),
                            ) {
                                Box(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                                    Text(
                                        if (active) "✓ ${line.shortTag()}" else "○ ${line.shortTag()}",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                    )
                                }
                            }
                        }
                    }

                    // Aggregate status & override precedence switch
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        EditorialBadge(
                            text = "有效参与 $activeRowsCount / ${allRows.size} 人",
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        )
                        if (allRows.isNotEmpty()) {
                            EditorialBadge(
                                text = if (cumulativeDelta < 0) "较全线差值 $cumulativeDelta" else "等同全线",
                                containerColor = if (cumulativeDelta < 0) lowPal.badgeContainer else highPal.badgeContainer,
                                contentColor = if (cumulativeDelta < 0) lowPal.content else highPal.content,
                            )
                        }
                        if (customOverrideCount > 0) {
                            Surface(
                                onClick = { onRespectOverrideChange(!respectPerCharacterOverride) },
                                modifier = Modifier
                                    .heightIn(min = 38.dp)
                                    .testTag("route-merge-override-precedence"),
                                shape = RoundedCornerShape(100.dp),
                                color = if (respectPerCharacterOverride) MaterialTheme.colorScheme.primaryContainer else medPal.badgeContainer,
                                contentColor = if (respectPerCharacterOverride) MaterialTheme.colorScheme.onPrimaryContainer else medPal.content,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            ) {
                                Box(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
                                    Text(
                                        if (respectPerCharacterOverride) "模式：遵循角色自定（$customOverrideCount 人）· 点此切全局"
                                        else "模式：全部按上方全局试算 · 点此恢复自定",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                    }

                    statusMessage?.let { msg ->
                        Surface(
                            color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().testTag("route-merge-overview-status"),
                        ) {
                            Text(msg, Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        if (!state.ready) {
            item { LoadingPackage() }
        } else if (allRows.isEmpty()) {
            item {
                EditorialCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(
                        Modifier.fillMaxWidth().padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.CompareArrows, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text("还没有录入任何角色的路线属性", style = MaterialTheme.typography.titleMedium)
                        }
                        Text(
                            "录入任意角色在凯伊、迪托利希、赛奥朵拉或蕾达篇的属性后，这里会一次性汇总全队九项融合面板，并支持勾选任意路线组合实时查看与全线的差值。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = onImportScreenshots,
                                modifier = Modifier.heightIn(min = 48.dp).testTag("route-merge-empty-ocr"),
                            ) {
                                Icon(Icons.Outlined.DocumentScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("NS 截图批量导入")
                            }
                            OutlinedButton(
                                onClick = onSwitchToDetail,
                                modifier = Modifier.heightIn(min = 48.dp).testTag("route-merge-empty-go-detail"),
                            ) {
                                Text("手动选择角色录入")
                            }
                        }
                    }
                }
            }
        } else {
            // Search + Sort + Table/Cards Toggle Bar
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FuzzySearchBox(
                            query = search,
                            onQueryChange = onSearchChange,
                            placeholder = "模糊搜角色或职业…",
                            suggestionsProvider = { q ->
                                allRows.mapNotNull { row ->
                                    val ch = row.record.character
                                    val profs = ch.snapshots.values.joinToString(" ") { it.profession }
                                    val sc = com.fireemblem.companion.content.FuzzyMatcher.score(q, ch.characterName, profs)
                                    if (sc > 0) {
                                        com.fireemblem.companion.content.FuzzySuggestion(
                                            id = ch.characterId,
                                            title = ch.characterName,
                                            subtitle = ch.recordedLines.joinToString("/") { it.label.removeSuffix("篇") } + " · 融合总和 ${row.comparison.mergedMaxTotal}",
                                            badge = "${ch.recordedLines.size}线",
                                            imageAsset = entryByCharId[ch.characterId]?.imageAsset,
                                            score = sc,
                                        )
                                    } else null
                                }.sortedByDescending { it.score }.take(6)
                            },
                            onSelectSuggestion = { item ->
                                onSearchChange("")
                                onOpenCharacterDetail(item.id)
                            },
                            modifier = Modifier.weight(1f),
                            fieldTestTag = "route-merge-overview-search",
                            suggestionKey = allRows.size,
                            shapeRadiusDp = 14,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("table" to "总表", "cards" to "卡片").forEach { (key, label) ->
                                val sel = (key == "table" && useTable) || (key == "cards" && !useTable)
                                Surface(
                                    onClick = { onDensityChange(key) },
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .semantics { selected = sel }
                                        .testTag("route-merge-view:$key"),
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                    contentColor = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    border = BorderStroke(1.dp, if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                                ) {
                                    Box(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                                        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val baseSorts = listOf(
                            RouteMergeOverviewSort.SelectedTotalDesc,
                            RouteMergeOverviewSort.LossLargestFirst,
                            RouteMergeOverviewSort.LossSmallestFirst,
                            RouteMergeOverviewSort.FullTotalDesc,
                            RouteMergeOverviewSort.LineCountDesc,
                        )
                        val allSorts = baseSorts + GrowthStat.entries.map { RouteMergeOverviewSort.StatDesc(it) }
                        allSorts.forEach { opt ->
                            val sel = sort.key == opt.key
                            Surface(
                                onClick = { onSortChange(opt) },
                                modifier = Modifier
                                    .heightIn(min = 38.dp)
                                    .semantics { selected = sel }
                                    .testTag("route-merge-sort:${opt.key}"),
                                shape = RoundedCornerShape(100.dp),
                                color = if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                contentColor = if (sel) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                border = BorderStroke(1.dp, if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                            ) {
                                Box(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
                                    Text(opt.label, style = MaterialTheme.typography.labelMedium, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
                                }
                            }
                        }
                    }
                }
            }

            if (visibleRows.isEmpty()) {
                item {
                    EditorialCard {
                        Text(
                            "没有匹配「$search」的已录角色。",
                            Modifier.fillMaxWidth().padding(18.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else if (useTable) {
                item(key = "route-merge-overview-matrix") {
                    RouteMergeOverviewTable(
                        rows = visibleRows,
                        entryByCharId = entryByCharId,
                        activeSort = sort,
                        onSortChange = onSortChange,
                        onOpenCharacter = onOpenCharacterDetail,
                        onCustomizeLines = onCustomizeCharacterLines,
                    )
                }
            } else {
                items(visibleRows, key = { it.record.entityId }) { row ->
                    RouteMergeOverviewCard(
                        row = row,
                        entry = entryByCharId[row.record.character.characterId],
                        onOpenCharacter = { onOpenCharacterDetail(row.record.character.characterId) },
                        onCustomizeLines = { onCustomizeCharacterLines(row.record.character.characterId) },
                    )
                }
            }
        }

        item { Spacer(Modifier.height(18.dp)) }
    }
}

@Composable
private fun RouteMergeOverviewTable(
    rows: List<RouteMergeOverviewRow>,
    entryByCharId: Map<String, WikiEntry>,
    activeSort: RouteMergeOverviewSort,
    onSortChange: (RouteMergeOverviewSort) -> Unit,
    onOpenCharacter: (String) -> Unit,
    onCustomizeLines: (String) -> Unit,
) {
    val highPal = statTierPalette(StatRatingTier.HIGH)
    val lowPal = statTierPalette(StatRatingTier.LOW)
    val minTableWidth = 980.dp

    EditorialCard(modifier = Modifier.fillMaxWidth().testTag("route-merge-overview-table")) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "九项属性总表",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val stretch = maxWidth >= minTableWidth
                val tableMod = if (stretch) Modifier.fillMaxWidth() else Modifier.width(minTableWidth)

                @Composable
                fun RowScope.charCol(): Modifier = if (stretch) Modifier.weight(1.75f) else Modifier.width(136.dp)
                @Composable
                fun RowScope.linesCol(): Modifier = if (stretch) Modifier.weight(1.20f) else Modifier.width(96.dp)
                @Composable
                fun RowScope.lvCol(): Modifier = if (stretch) Modifier.weight(0.60f) else Modifier.width(48.dp)
                @Composable
                fun RowScope.statCol(): Modifier = if (stretch) Modifier.weight(0.72f) else Modifier.width(58.dp)
                @Composable
                fun RowScope.totalCol(): Modifier = if (stretch) Modifier.weight(0.95f) else Modifier.width(78.dp)
                @Composable
                fun RowScope.refCol(): Modifier = if (stretch) Modifier.weight(1.12f) else Modifier.width(92.dp)

                Box(
                    Modifier
                        .fillMaxWidth()
                        .then(if (stretch) Modifier else Modifier.horizontalScroll(rememberScrollState())),
                ) {
                    Column(tableMod, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        // Header Row
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ComparisonCell("角色", charCol(), bold = true, align = TextAlign.Start)
                            ComparisonCell("参与线", linesCol(), bold = true)
                            ComparisonCell("Lv", lvCol(), bold = true)
                            GrowthStat.entries.forEach { stat ->
                                val isSorted = (activeSort as? RouteMergeOverviewSort.StatDesc)?.stat == stat
                                Surface(
                                    onClick = { onSortChange(RouteMergeOverviewSort.StatDesc(stat)) },
                                    modifier = statCol(),
                                    color = if (isSorted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                                    shape = RoundedCornerShape(8.dp),
                                ) {
                                    Text(
                                        text = if (isSorted) "${stat.shortHeader()}↓" else stat.shortHeader(),
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 7.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSorted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                        textAlign = TextAlign.Center,
                                        maxLines = 1,
                                    )
                                }
                            }
                            ComparisonCell(
                                text = "自选总和",
                                modifier = totalCol(),
                                bold = true,
                                container = highPal.badgeContainer,
                                content = highPal.content,
                            )
                            ComparisonCell("全线 / 差", refCol(), bold = true)
                        }

                        HorizontalDivider(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.outlineVariant)

                        rows.forEach { row ->
                            val ch = row.record.character
                            val comp = row.comparison
                            val entry = entryByCharId[ch.characterId]
                            Surface(
                                onClick = { onOpenCharacter(ch.characterId) },
                                modifier = Modifier.fillMaxWidth().testTag("route-merge-overview-row:${ch.characterId}"),
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // Character cell
                                    Row(
                                        modifier = charCol()
                                            .clickable { onOpenCharacter(ch.characterId) }
                                            .padding(horizontal = 4.dp, vertical = 4.dp)
                                            .testTag("route-merge-overview-char:${ch.characterId}"),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        WikiImage(entry?.imageAsset, ch.characterName, Modifier.size(30.dp), crop = true)
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                ch.characterName,
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            if (row.usingCustomOverride) {
                                                Text(
                                                    "自定方案",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    maxLines = 1,
                                                )
                                            }
                                        }
                                    }

                                    // Participating lines + quick override trigger
                                    Column(
                                        modifier = linesCol()
                                            .clickable { onCustomizeLines(ch.characterId) }
                                            .padding(horizontal = 2.dp, vertical = 2.dp)
                                            .testTag("route-merge-row-customize:${ch.characterId}"),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(3.dp),
                                    ) {
                                        if (!comp.hasSelectedSnapshot) {
                                            Text("未录入", style = MaterialTheme.typography.labelSmall, color = lowPal.content, fontWeight = FontWeight.SemiBold)
                                        } else {
                                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                                comp.selectedLines.forEach { line ->
                                                    val tone = routeLineTone(line)
                                                    Surface(color = tone.first, contentColor = tone.second, shape = RoundedCornerShape(5.dp)) {
                                                        Text(
                                                            line.tinyGlyph(),
                                                            Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                                            style = MaterialTheme.typography.labelSmall,
                                                            fontWeight = FontWeight.Bold,
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                        Text(
                                            if (ch.recordedLines.size >= 2) "改线 ▾" else "仅1线",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }

                                    // Level cell
                                    val lvDelta = (comp.highestLevel ?: 0) - (comp.fullHighestLevel ?: 0)
                                    Column(
                                        modifier = lvCol(),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Text(
                                            comp.highestLevel?.toString() ?: "—",
                                            style = MaterialTheme.typography.labelLarge.tabularNumbers(),
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        if (comp.hasSelectedSnapshot && lvDelta < 0) {
                                            Text(
                                                "$lvDelta",
                                                style = MaterialTheme.typography.labelSmall.tabularNumbers(),
                                                color = lowPal.content,
                                            )
                                        }
                                    }

                                    // 9 Stat cells
                                    GrowthStat.entries.forEach { stat ->
                                        val idx = stat.ordinal
                                        val value = comp.mergedMaxStats[idx]
                                        val delta = comp.statDeltas[idx]
                                        val leaders = comp.statLeaders[stat].orEmpty()
                                        val srcLabel = when {
                                            !comp.hasSelectedSnapshot -> "—"
                                            leaders.size == 1 -> leaders.single().tinyGlyph()
                                            leaders.size > 1 -> "多"
                                            else -> ""
                                        }
                                        Surface(
                                            modifier = statCol(),
                                            shape = RoundedCornerShape(7.dp),
                                            color = when {
                                                !comp.hasSelectedSnapshot -> MaterialTheme.colorScheme.surfaceContainerLow
                                                delta < 0 -> lowPal.container
                                                else -> MaterialTheme.colorScheme.surfaceContainerLow
                                            },
                                        ) {
                                            Column(
                                                Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 2.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                            ) {
                                                Text(
                                                    if (comp.hasSelectedSnapshot) value.toString() else "—",
                                                    style = MaterialTheme.typography.labelLarge.tabularNumbers(),
                                                    fontWeight = if (delta < 0) FontWeight.Bold else FontWeight.SemiBold,
                                                    color = if (delta < 0) lowPal.content else MaterialTheme.colorScheme.onSurface,
                                                    maxLines = 1,
                                                )
                                                Text(
                                                    text = if (comp.hasSelectedSnapshot && delta < 0) "$delta" else srcLabel,
                                                    style = MaterialTheme.typography.labelSmall.tabularNumbers(),
                                                    color = if (comp.hasSelectedSnapshot && delta < 0) lowPal.content else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                )
                                            }
                                        }
                                    }

                                    // Selected Total cell
                                    Surface(
                                        modifier = totalCol(),
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (comp.hasSelectedSnapshot) highPal.container else lowPal.container,
                                    ) {
                                        Text(
                                            text = if (comp.hasSelectedSnapshot) "${comp.mergedMaxTotal}" else "未入",
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 4.dp),
                                            style = MaterialTheme.typography.labelLarge.tabularNumbers(),
                                            fontWeight = FontWeight.Bold,
                                            color = if (comp.hasSelectedSnapshot) highPal.content else lowPal.content,
                                            textAlign = TextAlign.Center,
                                            maxLines = 1,
                                        )
                                    }

                                    // Full Reference & Delta cell
                                    Column(
                                        modifier = refCol(),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Text(
                                            "${comp.fullMergedMaxTotal}",
                                            style = MaterialTheme.typography.labelMedium.tabularNumbers(),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            text = when {
                                                !comp.hasSelectedSnapshot -> "未覆盖"
                                                comp.totalDelta < 0 -> "${comp.totalDelta}"
                                                else -> "无损失"
                                            },
                                            style = MaterialTheme.typography.labelSmall.tabularNumbers(),
                                            fontWeight = FontWeight.Bold,
                                            color = if (!comp.hasSelectedSnapshot || comp.totalDelta < 0) lowPal.content else highPal.content,
                                        )
                                    }
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
private fun RouteMergeOverviewCard(
    row: RouteMergeOverviewRow,
    entry: WikiEntry?,
    onOpenCharacter: () -> Unit,
    onCustomizeLines: () -> Unit,
) {
    val ch = row.record.character
    val comp = row.comparison
    val highPal = statTierPalette(StatRatingTier.HIGH)
    val lowPal = statTierPalette(StatRatingTier.LOW)

    EditorialCard(
        onClick = onOpenCharacter,
        modifier = Modifier.fillMaxWidth().testTag("route-merge-overview-card:${ch.characterId}"),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                WikiImage(entry?.imageAsset, ch.characterName, Modifier.size(44.dp), crop = true)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(ch.characterName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (row.usingCustomOverride) {
                            EditorialBadge(
                                text = "自定方案",
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                    Text(
                        if (comp.hasSelectedSnapshot) "融合 Lv.${comp.highestLevel ?: "-"} · 参与 ${comp.selectedLines.size}/${comp.recordedLines.size} 线"
                        else "当前勾选路线未录入（已录 ${comp.recordedLines.size} 线）",
                        style = MaterialTheme.typography.bodySmall.tabularNumbers(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    EditorialBadge(
                        text = if (comp.hasSelectedSnapshot) "自选 ${comp.mergedMaxTotal}" else "未入选中线",
                        containerColor = if (comp.hasSelectedSnapshot) highPal.badgeContainer else lowPal.badgeContainer,
                        contentColor = if (comp.hasSelectedSnapshot) highPal.content else lowPal.content,
                    )
                    Text(
                        if (comp.totalDelta < 0) "全线 ${comp.fullMergedMaxTotal} (${comp.totalDelta})"
                        else "全线 ${comp.fullMergedMaxTotal} · 无损失",
                        style = MaterialTheme.typography.labelSmall.tabularNumbers(),
                        color = if (comp.totalDelta < 0) lowPal.content else highPal.content,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            // Recorded vs selected route pills + quick customize
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    comp.recordedLines.forEach { line ->
                        val included = line in comp.selectedLines
                        val tone = routeLineTone(line)
                        val snap = ch.snapshots.getValue(line)
                        Surface(
                            color = if (included) tone.first else MaterialTheme.colorScheme.surfaceContainerLow,
                            contentColor = if (included) tone.second else MaterialTheme.colorScheme.onSurfaceVariant,
                            shape = RoundedCornerShape(7.dp),
                            border = if (included) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            Text(
                                "${if (included) "✓" else "○"} ${line.shortTag()} Lv.${snap.level}",
                                Modifier.padding(horizontal = 7.dp, vertical = 3.dp).alpha(if (included) 1f else 0.7f),
                                style = MaterialTheme.typography.labelSmall.tabularNumbers(),
                                fontWeight = if (included) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
                if (ch.recordedLines.size >= 2) {
                    TextButton(
                        onClick = onCustomizeLines,
                        modifier = Modifier.heightIn(min = 42.dp).testTag("route-merge-card-customize:${ch.characterId}"),
                    ) {
                        Icon(Icons.Outlined.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("改线")
                    }
                }
            }

            // Loss breakdown chips when subset drops stats
            if (comp.hasSelectedSnapshot && comp.droppedStats.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    comp.droppedStats.forEach { stat ->
                        val delta = comp.statDeltas[stat.ordinal]
                        Surface(
                            color = lowPal.container,
                            contentColor = lowPal.content,
                            shape = RoundedCornerShape(7.dp),
                        ) {
                            Text(
                                "${stat.label} $delta",
                                Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall.tabularNumbers(),
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }

            // Compact 9-stat 3x3 strip so full fused panel is readable on phones too
            if (comp.hasSelectedSnapshot) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    GrowthStat.entries.chunked(3).forEach { rowStats ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            rowStats.forEach { stat ->
                                val idx = stat.ordinal
                                val v = comp.mergedMaxStats[idx]
                                val d = comp.statDeltas[idx]
                                val leaders = comp.statLeaders[stat].orEmpty()
                                val tag = when {
                                    leaders.size == 1 -> leaders.single().tinyGlyph()
                                    leaders.size > 1 -> "多"
                                    else -> ""
                                }
                                Surface(
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (d < 0) lowPal.container else MaterialTheme.colorScheme.surfaceContainerLow,
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(stat.shortHeader(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(
                                            if (d < 0) "$v($d)" else "$v $tag",
                                            style = MaterialTheme.typography.labelMedium.tabularNumbers(),
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (d < 0) lowPal.content else MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
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
private fun RouteMergeQuickOverrideDialog(
    record: RouteMergeCharacter,
    onDismiss: () -> Unit,
    onSaveSelection: (Set<BuddyCampaignLine>?) -> Unit,
) {
    val recorded = record.recordedLines
    var draft by remember(record) {
        mutableStateOf((record.selectedLines ?: recorded.toSet()).toSet())
    }
    val preview = remember(record, draft) { record.compare(activeLinesOverride = draft) }
    val lowPal = statTierPalette(StatRatingTier.LOW)
    val highPal = statTierPalette(StatRatingTier.HIGH)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${record.characterName} · 自选融合路线") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "勾选该角色实际打算融合的路线，未勾选路线不会删除原数据。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    recorded.forEach { line ->
                        val active = line in draft
                        val tone = routeLineTone(line)
                        val snap = record.snapshots.getValue(line)
                        Surface(
                            onClick = {
                                val next = LinkedHashSet(draft)
                                if (line in next) {
                                    if (next.size > 1) next.remove(line)
                                } else {
                                    next.add(line)
                                }
                                draft = next
                            },
                            modifier = Modifier.heightIn(min = 44.dp).testTag("route-merge-dialog-line:${line.id}"),
                            shape = RoundedCornerShape(12.dp),
                            color = if (active) tone.first else MaterialTheme.colorScheme.surfaceContainerLow,
                            contentColor = if (active) tone.second else MaterialTheme.colorScheme.onSurfaceVariant,
                            border = BorderStroke(1.dp, if (active) tone.second else MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            Box(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    "${if (active) "✓" else "○"} ${line.shortTag()} Lv.${snap.level}",
                                    style = MaterialTheme.typography.labelLarge.tabularNumbers(),
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                }
                Surface(
                    color = if (preview.totalDelta < 0) lowPal.container else highPal.container,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "融合总和：${preview.mergedMaxTotal} / 全线 ${preview.fullMergedMaxTotal}" +
                                if (preview.totalDelta < 0) "（差值 ${preview.totalDelta}）" else "（无属性损失）",
                            style = MaterialTheme.typography.labelLarge.tabularNumbers(),
                            color = if (preview.totalDelta < 0) lowPal.content else highPal.content,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (preview.droppedStats.isNotEmpty()) {
                            Text(
                                "影响项：" + preview.droppedStats.joinToString("、") { s ->
                                    "${s.label} ${preview.statDeltas[s.ordinal]}"
                                },
                                style = MaterialTheme.typography.bodySmall.tabularNumbers(),
                                color = lowPal.content,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSaveSelection(draft) },
                modifier = Modifier.testTag("route-merge-dialog-save"),
            ) {
                Text("保存此角色方案")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (record.hasCustomSelection) {
                    TextButton(
                        onClick = { onSaveSelection(null) },
                        modifier = Modifier.testTag("route-merge-dialog-reset"),
                    ) {
                        Text("恢复跟随全局")
                    }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

@Composable
private fun RouteMergeModeSwitcher(
    activeMode: String,
    onSelectOverview: () -> Unit,
    onSelectDetail: () -> Unit,
    onImportScreenshots: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(
            "overview" to "总览对比",
            "detail" to "单人录入",
        ).forEach { (key, label) ->
            val sel = activeMode == key
            Surface(
                onClick = if (key == "overview") onSelectOverview else onSelectDetail,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .semantics { selected = sel }
                    .testTag("route-merge-mode:$key"),
                shape = RoundedCornerShape(14.dp),
                color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                contentColor = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                border = BorderStroke(1.dp, if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
            ) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Surface(
            onClick = onImportScreenshots,
            modifier = Modifier
                .heightIn(min = 44.dp)
                .testTag("route-merge-ocr-import"),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.DocumentScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    "截图导入",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun RouteMergeRosterListPane(
    state: RouteMergeRosterState,
    recordByCharId: Map<String, RouteMergeStoredRecord>,
    search: String,
    onSearchChange: (String) -> Unit,
    filter: String,
    onFilterChange: (String) -> Unit,
    selectedCharacterId: String?,
    onSelectCharacter: (String) -> Unit,
    onOpenOverview: () -> Unit,
    onImportScreenshots: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val recordedCount = state.collection.records.count { it.character.recordedLines.isNotEmpty() }
    val multiLineCount = state.collection.records.count { it.character.recordedLines.size >= 2 }

    val filteredChars = remember(state.characters, recordByCharId, search, filter) {
        val q = search.trim()
        state.characters.mapNotNull { entry ->
            val rec = recordByCharId[entry.id]?.character
            val lineCount = rec?.recordedLines?.size ?: 0
            val passFilter = when (filter) {
                "recorded" -> lineCount >= 1
                "multi" -> lineCount >= 2
                else -> true
            }
            if (!passFilter) return@mapNotNull null
            val sc = if (q.isEmpty()) 1 else com.fireemblem.companion.content.FuzzyMatcher.score(q, entry.title, entry.subtitle)
            if (sc > 0) Triple(sc, lineCount, entry) else null
        }.sortedWith(
            if (q.isEmpty()) {
                compareByDescending<Triple<Int, Int, WikiEntry>> { it.second }.thenBy { it.third.title }
            } else {
                compareByDescending<Triple<Int, Int, WikiEntry>> { it.first }
                    .thenByDescending { it.second }
                    .thenBy { it.third.title }
            },
        ).map { it.third }
    }

    LazyColumn(
        modifier = modifier.fillMaxHeight().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Spacer(Modifier.height(6.dp)) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                        Text("返回工具")
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            "多线融合面板",
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.semantics { heading() },
                        )
                        Text(
                            "已记录 $recordedCount 人 · 多线对比 $multiLineCount 人",
                            style = MaterialTheme.typography.bodySmall.tabularNumbers(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                RouteMergeModeSwitcher(
                    activeMode = "detail",
                    onSelectOverview = onOpenOverview,
                    onSelectDetail = {},
                    onImportScreenshots = onImportScreenshots,
                )
            }
        }
        item {
            EditorialCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.AutoMirrored.Outlined.CompareArrows, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        "记录同一角色在四条路线的等级、职业与九项属性，可在对比表表头勾选自选融合路线，或切到「总览对比」一次性看完所有角色。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            FuzzySearchBox(
                query = search,
                onQueryChange = onSearchChange,
                placeholder = "模糊搜万缕千丝角色姓名或初始职业…",
                suggestionsProvider = { q ->
                    state.characters.mapNotNull { entry ->
                        val sc = com.fireemblem.companion.content.FuzzyMatcher.score(q, entry.title, entry.subtitle)
                        if (sc > 0) {
                            val lineCnt = recordByCharId[entry.id]?.character?.recordedLines?.size ?: 0
                            com.fireemblem.companion.content.FuzzySuggestion(
                                id = entry.id,
                                title = entry.title,
                                subtitle = entry.subtitle,
                                badge = if (lineCnt > 0) "已录 ${lineCnt} 线" else "未记录",
                                imageAsset = entry.imageAsset,
                                score = sc,
                            )
                        } else null
                    }.sortedByDescending { it.score }.take(6)
                },
                onSelectSuggestion = { item ->
                    onSearchChange("")
                    onSelectCharacter(item.id)
                },
                modifier = Modifier.fillMaxWidth(),
                fieldTestTag = "route-merge-search",
                suggestionKey = state.characters.size,
                shapeRadiusDp = 16,
            )
        }
        item {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    "all" to "全部角色 · ${state.characters.size}",
                    "recorded" to "已记录 · $recordedCount",
                    "multi" to "多线对比 · $multiLineCount",
                ).forEach { (key, label) ->
                    val sel = filter == key
                    Surface(
                        onClick = { onFilterChange(key) },
                        modifier = Modifier.heightIn(min = 42.dp).semantics { selected = sel }.testTag("route-merge-filter:$key"),
                        shape = RoundedCornerShape(100.dp),
                        color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                        contentColor = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        border = BorderStroke(1.dp, if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Box(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), contentAlignment = Alignment.Center) {
                            Text(label, style = MaterialTheme.typography.labelLarge.tabularNumbers())
                        }
                    }
                }
            }
        }
        if (!state.ready) {
            item { LoadingPackage() }
        } else if (filteredChars.isEmpty()) {
            item {
                EditorialCard {
                    Text(
                        if (state.characters.isEmpty()) "请先加载《万缕千丝》离线图鉴以浏览角色名册。"
                        else "当前筛选下暂无匹配角色，点击「全部角色」可为任意角色添加四线属性记录。",
                        Modifier.fillMaxWidth().padding(18.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            items(filteredChars, key = { it.id }) { entry ->
                val rec = recordByCharId[entry.id]?.character
                val lines = rec?.recordedLines.orEmpty()
                val isSelected = entry.id == selectedCharacterId
                EditorialCard(
                    onClick = { onSelectCharacter(entry.id) },
                    containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    else MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth().testTag("route-merge-char:${entry.id}"),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        WikiImage(entry.imageAsset, entry.title, Modifier.size(48.dp), crop = true)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(entry.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                if (rec?.hasCustomSelection == true) {
                                    Text("自定", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            if (lines.isEmpty()) {
                                Text(
                                    entry.subtitle.ifBlank { "尚未填写路线属性" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            } else {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    lines.forEach { line ->
                                        val snap = rec!!.snapshots.getValue(line)
                                        val tone = routeLineTone(line)
                                        val included = rec.selectedLines == null || line in rec.selectedLines!!
                                        Surface(
                                            color = if (included) tone.first else MaterialTheme.colorScheme.surfaceContainerLow,
                                            contentColor = if (included) tone.second else MaterialTheme.colorScheme.onSurfaceVariant,
                                            shape = RoundedCornerShape(7.dp),
                                        ) {
                                            Text(
                                                "${line.shortTag()} Lv.${snap.level}",
                                                Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                                style = MaterialTheme.typography.labelSmall.tabularNumbers(),
                                                fontWeight = if (included) FontWeight.SemiBold else FontWeight.Normal,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Text(
                            if (lines.size >= 2) "对比 ›" else "填写 ›",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun RouteMergeDetailPane(
    profileId: String,
    characterId: String,
    characterName: String,
    characterEntry: WikiEntry?,
    repository: WikiRepository?,
    storedRecord: RouteMergeStoredRecord?,
    journals: List<LevelJournal>,
    statusMessage: String?,
    isError: Boolean,
    showBackToList: Boolean,
    backLabel: String = "返回角色列表",
    onBackToList: () -> Unit,
    onOpenWiki: (String) -> Unit,
    onSave: (RouteMergeCharacter, String) -> Unit,
    onMessage: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fullEntry by produceState<WikiEntry?>(characterEntry, repository, characterId) {
        value = withContext(Dispatchers.IO) {
            repository?.entry(characterId) ?: characterEntry
        }
    }

    val currentRecord = storedRecord?.character ?: RouteMergeCharacter(
        profileId = profileId,
        characterId = characterId,
        characterName = characterName,
    )

    var selectedLine by rememberSaveable(characterId) {
        mutableStateOf(currentRecord.recordedLines.firstOrNull() ?: BuddyCampaignLine.KAI)
    }

    // Active trial selection for single-character comparison table
    var tableSelectedLines by remember(characterId, currentRecord.recordedLines, currentRecord.selectedLines) {
        mutableStateOf((currentRecord.selectedLines ?: currentRecord.recordedLines.toSet()).toSet())
    }

    val activeSnap = currentRecord.snapshots[selectedLine]

    var levelText by remember(characterId, selectedLine, activeSnap) {
        mutableStateOf((activeSnap?.level ?: 1).toString())
    }
    var professionText by remember(characterId, selectedLine, activeSnap) {
        mutableStateOf(activeSnap?.profession.orEmpty())
    }
    var statTexts by remember(characterId, selectedLine, activeSnap) {
        mutableStateOf(
            activeSnap?.stats?.map(Int::toString)
                ?: List(GrowthStat.entries.size) { "0" },
        )
    }
    var noteText by remember(characterId, selectedLine, activeSnap) {
        mutableStateOf(activeSnap?.note.orEmpty())
    }

    val matchingJournal = remember(journals, characterId, characterName, selectedLine) {
        journals.lastOrNull { j ->
            j.line == selectedLine && (
                j.characterId == characterId ||
                    j.characterId == characterName ||
                    j.characterId == characterId.substringAfter(':')
                )
        }
    }

    fun applyBaseStatsFromWiki() {
        val entry = fullEntry
        if (entry == null) {
            onMessage("未读取到该角色的图鉴初始属性。", true)
            return
        }
        val map = entry.fields.associate { it.label to it.value.trim() }
        val initLv = map["初始等级"]?.toIntOrNull()
        val initProf = map["初始兵种"] ?: map["初始职业"]
        if (initLv != null) levelText = initLv.toString()
        if (!initProf.isNullOrBlank()) professionText = initProf
        val mappedStats = GrowthStat.entries.mapIndexed { idx, stat ->
            val raw = map["初始${stat.label}"] ?: map[stat.label]
            raw?.toIntOrNull()?.toString() ?: statTexts[idx]
        }
        statTexts = mappedStats
        onMessage("已从图鉴填入 ${characterName} 的初始等级与属性，可按当前进度修改后保存。", false)
    }

    fun applyFromJournal() {
        val journal = matchingJournal
        if (journal == null) {
            onMessage("当前存档在「${selectedLine.label}」下暂无 ${characterName} 的升级记录。", true)
            return
        }
        journal.currentLevel?.let { levelText = it.toString() }
        journal.currentProfession?.let { professionText = it }
        val cpStats = journal.seedCheckpoint?.stats
        if (cpStats != null && cpStats.size == GrowthStat.entries.size) {
            statTexts = cpStats.map(Int::toString)
            onMessage("已从「${journal.name}」读取 Lv.${journal.currentLevel ?: "?"} 与实测属性，确认无误后点击保存。", false)
        } else {
            val entry = fullEntry
            val baseMap = entry?.fields?.associate { it.label to it.value.trim() }.orEmpty()
            val summed = GrowthStat.entries.mapIndexed { idx, stat ->
                val baseVal = (baseMap["初始${stat.label}"] ?: baseMap[stat.label])?.toIntOrNull() ?: 0
                val gained = journal.observations.sumOf { it.gains.getOrElse(idx) { 0 } }
                (baseVal + gained).toString()
            }
            statTexts = summed
            onMessage("已从「${journal.name}」估算当前属性（初始属性 + 升级记录累计提升），请核对后保存。", false)
        }
    }

    fun submitCurrentLine() {
        val lv = levelText.trim().toIntOrNull()
        if (lv == null || lv !in 1..99) {
            onMessage("请输入 1 至 99 之间的有效等级。", true)
            return
        }
        val parsedStats = statTexts.map { it.trim().toIntOrNull() }
        if (parsedStats.any { it == null || it !in 0..999 }) {
            onMessage("九项属性必须全部为 0 至 999 的整数。", true)
            return
        }
        val snap = RouteMergeSnapshot(
            line = selectedLine,
            level = lv,
            profession = professionText.trim(),
            stats = parsedStats.filterNotNull(),
            note = noteText.trim(),
        )
        val updated = currentRecord.withSnapshot(snap)
        onSave(updated, "已保存 $characterName · ${selectedLine.label}（Lv.$lv，属性总和 ${snap.totalStats}）")
    }

    val comparison = remember(currentRecord, tableSelectedLines) {
        val effectiveMask = tableSelectedLines.intersect(currentRecord.recordedLines.toSet())
            .ifEmpty { currentRecord.recordedLines.toSet() }
        currentRecord.compare(activeLinesOverride = effectiveMask)
    }

    LazyColumn(
        modifier = modifier.fillMaxHeight().padding(horizontal = 16.dp).testTag("route-merge-detail"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(Modifier.height(6.dp)) }
        if (showBackToList) {
            item {
                OutlinedButton(
                    onClick = onBackToList,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("route-merge-detail-back"),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                    Text(backLabel)
                }
            }
        }
        // Hero card
        item {
            EditorialCard {
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    WikiImage(fullEntry?.imageAsset, characterName, Modifier.size(64.dp), crop = true)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            characterName,
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.semantics { heading() },
                        )
                        val recText = if (currentRecord.recordedLines.isEmpty()) {
                            "尚未记录任何路线进度"
                        } else {
                            val customNote = currentRecord.selectedLines?.let { sel ->
                                " · 自选融 ${sel.size} 线"
                            }.orEmpty()
                            "已录入 ${currentRecord.recordedLines.size} / 4 条路线：${currentRecord.recordedLines.joinToString("、") { it.shortTag() }}$customNote"
                        }
                        Text(recText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (characterId.startsWith("characters:")) {
                        TextButton(onClick = { onOpenWiki(characterId) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("查看图鉴")
                        }
                    }
                }
            }
        }

        // Interactive Multi-Line Comparison Table Card
        if (comparison.recordedLines.isNotEmpty()) {
            item(key = "route-merge-comparison-item") {
                RouteMergeComparisonCard(
                    record = currentRecord,
                    comparison = comparison,
                    onToggleLine = { line ->
                        val next = LinkedHashSet(tableSelectedLines)
                        if (line in next) {
                            if (next.size <= 1) {
                                onMessage("至少保留 1 条路线参与融合计算。", false)
                                return@RouteMergeComparisonCard
                            }
                            next.remove(line)
                        } else {
                            next.add(line)
                        }
                        tableSelectedLines = next
                    },
                    onSaveCustomSelection = {
                        val updated = currentRecord.withSelectedLines(tableSelectedLines)
                        val labels = BuddyCampaignLine.entries.filter { it in tableSelectedLines }.joinToString(" + ") { it.shortTag() }
                        onSave(updated, "已保存 $characterName 的自选融合组合：$labels")
                    },
                    onResetSelection = {
                        val all = currentRecord.recordedLines.toSet()
                        tableSelectedLines = all
                        if (currentRecord.hasCustomSelection) {
                            val updated = currentRecord.withSelectedLines(null)
                            onSave(updated, "已恢复 $characterName 使用全部已录路线融合")
                        }
                    },
                )
            }
        }

        // 4 Route Line Tabs
        item(key = "route-merge-tabs-item") {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val fillTabs = maxWidth >= 420.dp
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(if (fillTabs) Modifier else Modifier.horizontalScroll(rememberScrollState())),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BuddyCampaignLine.entries.forEach { line ->
                        val sel = selectedLine == line
                        val hasData = line in currentRecord.snapshots
                        val tone = routeLineTone(line)
                        Surface(
                            onClick = { selectedLine = line },
                            modifier = Modifier
                                .then(if (fillTabs) Modifier.weight(1f) else Modifier)
                                .heightIn(min = 46.dp)
                                .semantics { selected = sel }
                                .testTag("route-merge-line:${line.id}"),
                            shape = RoundedCornerShape(14.dp),
                            color = when {
                                sel -> MaterialTheme.colorScheme.primary
                                hasData -> tone.first
                                else -> MaterialTheme.colorScheme.surface
                            },
                            contentColor = when {
                                sel -> MaterialTheme.colorScheme.onPrimary
                                hasData -> tone.second
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            border = BorderStroke(
                                1.dp,
                                if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            ),
                        ) {
                            Box(
                                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    if (hasData) "✓ ${line.label}" else line.label,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = if (sel || hasData) FontWeight.SemiBold else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }

        // Line Editor Card
        item(key = "route-merge-editor-item") {
            EditorialCard(modifier = Modifier.fillMaxWidth().testTag("route-merge-editor")) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${selectedLine.label} · 当前面板录入",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        EditorialBadge(
                            text = "合计 ${statTexts.sumOf { it.trim().toIntOrNull() ?: 0 }}",
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        OutlinedButton(
                            onClick = ::applyFromJournal,
                            modifier = Modifier.heightIn(min = 42.dp).testTag("route-merge-import-journal"),
                        ) {
                            Text(if (matchingJournal != null) "从升级记录读取（${matchingJournal.name}）" else "从升级记录读取")
                        }
                        if (fullEntry != null) {
                            OutlinedButton(
                                onClick = ::applyBaseStatsFromWiki,
                                modifier = Modifier.heightIn(min = 42.dp).testTag("route-merge-fill-base"),
                            ) {
                                Text("填入图鉴初始属性")
                            }
                        }
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = levelText,
                            onValueChange = { if (it.length <= 2 && it.all(Char::isDigit)) levelText = it },
                            label = { Text("当前等级") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(0.36f).testTag("route-merge-level"),
                            shape = RoundedCornerShape(14.dp),
                        )
                        OutlinedTextField(
                            value = professionText,
                            onValueChange = { if (it.length <= 48) professionText = it },
                            label = { Text("当前职业（选填）") },
                            singleLine = true,
                            modifier = Modifier.weight(0.64f).testTag("route-merge-profession"),
                            shape = RoundedCornerShape(14.dp),
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        GrowthStat.entries.chunked(3).forEach { statRow ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                statRow.forEach { stat ->
                                    val idx = stat.ordinal
                                    OutlinedTextField(
                                        value = statTexts[idx],
                                        onValueChange = { next ->
                                            if (next.length <= 3 && next.all(Char::isDigit)) {
                                                statTexts = statTexts.toMutableList().also { it[idx] = next }
                                            }
                                        },
                                        label = { Text(stat.label, maxLines = 1) },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        textStyle = MaterialTheme.typography.bodyLarge.tabularNumbers(),
                                        modifier = Modifier.weight(1f).testTag("route-merge-stat:${stat.name}"),
                                        shape = RoundedCornerShape(12.dp),
                                    )
                                }
                            }
                        }
                    }

                    OutlinedTextField(
                        value = noteText,
                        onValueChange = { if (it.length <= 300) noteText = it },
                        label = { Text("培养备注（选填，如吃药情况或配装）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    )
                }
            }
        }

        item(key = "route-merge-actions-item") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                statusMessage?.let { msg ->
                    Surface(
                        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().testTag("route-merge-status"),
                    ) {
                        Text(
                            text = msg,
                            modifier = Modifier.padding(14.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = ::submitCurrentLine,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("route-merge-save"),
                    ) {
                        Text("保存${selectedLine.label}属性")
                    }
                    if (activeSnap != null) {
                        OutlinedButton(
                            onClick = {
                                val updated = currentRecord.withoutLine(selectedLine)
                                onSave(updated, "已清除 $characterName · ${selectedLine.label} 记录")
                            },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("route-merge-clear"),
                        ) {
                            Text("清除本线")
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun RouteMergeComparisonCard(
    record: RouteMergeCharacter,
    comparison: RouteMergeComparison,
    onToggleLine: (BuddyCampaignLine) -> Unit,
    onSaveCustomSelection: () -> Unit,
    onResetSelection: () -> Unit,
) {
    val highPal = statTierPalette(StatRatingTier.HIGH)
    val lowPal = statTierPalette(StatRatingTier.LOW)
    val lines = comparison.recordedLines
    val selectedSet = comparison.selectedLines.toSet()
    val showMergeMax = lines.size >= 2
    val showDualColumns = showMergeMax && comparison.isSubsetMode

    EditorialCard(modifier = Modifier.fillMaxWidth().testTag("route-merge-comparison")) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("多线属性横向对比", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                if (showMergeMax) {
                    EditorialBadge(
                        text = if (showDualColumns) {
                            "自选 ${comparison.mergedMaxTotal} (${comparison.totalDelta}) / 全线 ${comparison.fullMergedMaxTotal}"
                        } else {
                            "融合上限 ${comparison.mergedMaxTotal}"
                        },
                        containerColor = if (showDualColumns && comparison.totalDelta < 0) lowPal.badgeContainer else highPal.badgeContainer,
                        contentColor = if (showDualColumns && comparison.totalDelta < 0) lowPal.content else highPal.content,
                    )
                }
            }
            Text(
                if (showMergeMax) "点击表头路线可自由勾选参与融合的线（不删数据）；绿色「★」为当前勾选线中最高项，取消路线后自动并排对比「自选融合」与「全线参考」。"
                else "再录入另一条路线的属性后，即可自动高亮单项领先路线与融合最高上限。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Per-character custom selection save/reset bar right above the table (always reachable above the fold)
            if (showMergeMax) {
                val savedSet = record.selectedLines
                val selectionChanged = (savedSet == null && comparison.isSubsetMode) ||
                    (savedSet != null && savedSet != selectedSet)
                val showSaveBar = comparison.isSubsetMode || record.hasCustomSelection
                if (showSaveBar) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            itemVerticalAlignment = Alignment.CenterVertically,
                        ) {
                            val statusLabel = when {
                                record.hasCustomSelection && !selectionChanged ->
                                    "已保存自选组合：${comparison.selectedLines.joinToString(" + ") { it.shortTag() }}"
                                comparison.isSubsetMode ->
                                    "当前试算：${comparison.selectedLines.joinToString(" + ") { it.shortTag() }}（未保存）"
                                else -> "当前使用全部已录路线（未覆盖旧自选）"
                            }
                            Text(
                                statusLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (selectionChanged || comparison.isSubsetMode) {
                                OutlinedButton(
                                    onClick = onSaveCustomSelection,
                                    modifier = Modifier.heightIn(min = 42.dp).testTag("route-merge-save-selection"),
                                ) {
                                    Text("保存此角色融合组合")
                                }
                            }
                            if (comparison.isSubsetMode || record.hasCustomSelection) {
                                TextButton(
                                    onClick = onResetSelection,
                                    modifier = Modifier.heightIn(min = 42.dp).testTag("route-merge-reset-selection"),
                                ) {
                                    Text("恢复全线")
                                }
                            }
                        }
                    }
                }
            }

            BoxWithConstraints(Modifier.fillMaxWidth().testTag("route-merge-comparison-table")) {
                val summaryCols = when {
                    showDualColumns -> 2
                    showMergeMax -> 1
                    else -> 0
                }
                val dataColCount = lines.size + summaryCols
                val minTableWidth = 66.dp + (dataColCount * 82).dp + (dataColCount * 6).dp
                val stretchToFill = maxWidth >= minTableWidth
                val tableModifier = if (stretchToFill) Modifier.fillMaxWidth() else Modifier.width(minTableWidth)

                @Composable
                fun RowScope.labelCellModifier(): Modifier =
                    if (stretchToFill) Modifier.weight(0.80f) else Modifier.width(66.dp)

                @Composable
                fun RowScope.lineCellModifier(): Modifier =
                    if (stretchToFill) Modifier.weight(1f) else Modifier.width(82.dp)

                @Composable
                fun RowScope.mergeCellModifier(): Modifier =
                    if (stretchToFill) Modifier.weight(1.08f) else Modifier.width(88.dp)

                Box(
                    Modifier
                        .fillMaxWidth()
                        .then(if (stretchToFill) Modifier else Modifier.horizontalScroll(rememberScrollState())),
                ) {
                    Column(tableModifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        // Interactive Header Row
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ComparisonCell("项目", labelCellModifier(), bold = true, align = TextAlign.Start)
                            lines.forEach { line ->
                                val active = line in selectedSet
                                val tone = routeLineTone(line)
                                Surface(
                                    onClick = { if (showMergeMax) onToggleLine(line) },
                                    modifier = lineCellModifier()
                                        .semantics { selected = active }
                                        .testTag("route-merge-col-toggle:${line.id}"),
                                    color = if (active) tone.first else MaterialTheme.colorScheme.surfaceContainerLow,
                                    shape = RoundedCornerShape(8.dp),
                                    border = if (showMergeMax) {
                                        BorderStroke(
                                            1.dp,
                                            if (active) tone.second.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant,
                                        )
                                    } else null,
                                ) {
                                    Text(
                                        text = if (showMergeMax) "${if (active) "✓" else "○"} ${line.shortTag()}" else line.shortTag(),
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 7.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                        color = if (active) tone.second else MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (showDualColumns) {
                                ComparisonCell(
                                    text = "自选融合",
                                    modifier = mergeCellModifier(),
                                    bold = true,
                                    container = if (comparison.totalDelta < 0) lowPal.badgeContainer else highPal.badgeContainer,
                                    content = if (comparison.totalDelta < 0) lowPal.content else highPal.content,
                                )
                                ComparisonCell(
                                    text = "全线参考",
                                    modifier = mergeCellModifier(),
                                    bold = true,
                                    container = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    content = MaterialTheme.colorScheme.onSurface,
                                )
                            } else if (showMergeMax) {
                                ComparisonCell(
                                    text = "融合最高",
                                    modifier = mergeCellModifier(),
                                    bold = true,
                                    container = highPal.badgeContainer,
                                    content = highPal.content,
                                )
                            }
                        }

                        HorizontalDivider(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.outlineVariant)

                        // Level Row
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ComparisonCell("等级", labelCellModifier(), bold = true, align = TextAlign.Start)
                            lines.forEach { line ->
                                val active = line in selectedSet
                                val lv = record.snapshots.getValue(line).level
                                val isTop = showMergeMax && active && line in comparison.levelLeaders
                                ComparisonCell(
                                    text = if (isTop) "Lv.$lv ★" else "Lv.$lv",
                                    modifier = lineCellModifier().alpha(if (active) 1f else 0.45f),
                                    bold = isTop,
                                    container = if (isTop) highPal.container else MaterialTheme.colorScheme.surfaceContainerLow,
                                    content = if (isTop) highPal.content else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            if (showDualColumns) {
                                val lvDelta = (comparison.highestLevel ?: 0) - (comparison.fullHighestLevel ?: 0)
                                ComparisonCell(
                                    text = "Lv.${comparison.highestLevel ?: "-"}",
                                    modifier = mergeCellModifier(),
                                    bold = true,
                                    container = if (lvDelta < 0) lowPal.container else highPal.container,
                                    content = if (lvDelta < 0) lowPal.content else highPal.content,
                                )
                                ComparisonCell(
                                    text = "Lv.${comparison.fullHighestLevel ?: "-"}",
                                    modifier = mergeCellModifier(),
                                    container = MaterialTheme.colorScheme.surfaceContainerLow,
                                    content = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else if (showMergeMax) {
                                ComparisonCell(
                                    text = "Lv.${comparison.highestLevel ?: "-"}",
                                    modifier = mergeCellModifier(),
                                    bold = true,
                                    container = highPal.container,
                                    content = highPal.content,
                                )
                            }
                        }

                        // Profession Row
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ComparisonCell("职业", labelCellModifier(), bold = true, align = TextAlign.Start)
                            lines.forEach { line ->
                                val active = line in selectedSet
                                val prof = record.snapshots.getValue(line).profession.ifBlank { "—" }
                                ComparisonCell(
                                    text = prof,
                                    modifier = lineCellModifier().alpha(if (active) 1f else 0.45f),
                                )
                            }
                            if (showDualColumns) {
                                ComparisonCell(text = "自选", modifier = mergeCellModifier())
                                ComparisonCell(text = "全部", modifier = mergeCellModifier())
                            } else if (showMergeMax) {
                                ComparisonCell(text = "自选", modifier = mergeCellModifier())
                            }
                        }

                        HorizontalDivider(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.outlineVariant)

                        // 9 Stat Rows
                        GrowthStat.entries.forEach { stat ->
                            val idx = stat.ordinal
                            val leaders = comparison.statLeaders[stat].orEmpty()
                            val delta = comparison.statDeltas[idx]
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ComparisonCell(stat.label, labelCellModifier(), bold = true, align = TextAlign.Start)
                                lines.forEach { line ->
                                    val active = line in selectedSet
                                    val v = record.snapshots.getValue(line).stat(stat)
                                    val isTop = showMergeMax && active && line in leaders
                                    ComparisonCell(
                                        text = if (isTop) "$v ★" else "$v",
                                        modifier = lineCellModifier().alpha(if (active) 1f else 0.45f),
                                        bold = isTop,
                                        container = if (isTop) highPal.container else MaterialTheme.colorScheme.surfaceContainerLow,
                                        content = if (isTop) highPal.content else MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                                if (showDualColumns) {
                                    ComparisonCell(
                                        text = if (delta < 0) "${comparison.mergedMaxStats[idx]} ($delta)" else "${comparison.mergedMaxStats[idx]}",
                                        modifier = mergeCellModifier(),
                                        bold = true,
                                        container = if (delta < 0) lowPal.container else highPal.container,
                                        content = if (delta < 0) lowPal.content else highPal.content,
                                    )
                                    ComparisonCell(
                                        text = "${comparison.fullMergedMaxStats[idx]}",
                                        modifier = mergeCellModifier(),
                                        container = MaterialTheme.colorScheme.surfaceContainerLow,
                                        content = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else if (showMergeMax) {
                                    ComparisonCell(
                                        text = "${comparison.mergedMaxStats[idx]}",
                                        modifier = mergeCellModifier(),
                                        bold = true,
                                        container = highPal.container,
                                        content = highPal.content,
                                    )
                                }
                            }
                        }

                        HorizontalDivider(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.outlineVariant)

                        // Total Row
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ComparisonCell("总和", labelCellModifier(), bold = true, align = TextAlign.Start)
                            lines.forEach { line ->
                                val active = line in selectedSet
                                val total = record.snapshots.getValue(line).totalStats
                                val isTop = showMergeMax && active && line in comparison.totalLeaders
                                ComparisonCell(
                                    text = if (isTop) "$total ★" else "$total",
                                    modifier = lineCellModifier().alpha(if (active) 1f else 0.45f),
                                    bold = true,
                                    container = if (isTop) highPal.badgeContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                                    content = if (isTop) highPal.content else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            if (showDualColumns) {
                                ComparisonCell(
                                    text = if (comparison.totalDelta < 0) "${comparison.mergedMaxTotal} (${comparison.totalDelta})" else "${comparison.mergedMaxTotal}",
                                    modifier = mergeCellModifier(),
                                    bold = true,
                                    container = if (comparison.totalDelta < 0) lowPal.badgeContainer else highPal.badgeContainer,
                                    content = if (comparison.totalDelta < 0) lowPal.content else highPal.content,
                                )
                                ComparisonCell(
                                    text = "${comparison.fullMergedMaxTotal}",
                                    modifier = mergeCellModifier(),
                                    bold = true,
                                    container = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    content = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else if (showMergeMax) {
                                ComparisonCell(
                                    text = "${comparison.mergedMaxTotal}",
                                    modifier = mergeCellModifier(),
                                    bold = true,
                                    container = highPal.badgeContainer,
                                    content = highPal.content,
                                )
                            }
                        }
                    }
                }
            }

            val notes = lines.mapNotNull { line ->
                record.snapshots.getValue(line).note.takeIf { it.isNotBlank() }?.let { line to it }
            }
            if (notes.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("各线备注", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    notes.forEach { (line, note) ->
                        Text("• ${line.label}：$note", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ComparisonCell(
    text: String,
    modifier: Modifier = Modifier,
    bold: Boolean = false,
    align: TextAlign = TextAlign.Center,
    container: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: Color = MaterialTheme.colorScheme.onSurface,
) {
    Surface(
        modifier = modifier,
        color = container,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = text,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium.tabularNumbers(),
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            color = content,
            textAlign = align,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun GrowthStat.shortHeader(): String = when (this) {
    GrowthStat.HP -> "HP"
    GrowthStat.STRENGTH -> "力"
    GrowthStat.MAGIC -> "魔"
    GrowthStat.DEXTERITY -> "技"
    GrowthStat.SPEED -> "速"
    GrowthStat.LUCK -> "幸"
    GrowthStat.DEFENSE -> "防"
    GrowthStat.RESISTANCE -> "魔防"
    GrowthStat.CHARM -> "魅"
}

private fun BuddyCampaignLine.shortTag(): String = when (this) {
    BuddyCampaignLine.KAI -> "凯伊线"
    BuddyCampaignLine.DIT -> "迪托线"
    BuddyCampaignLine.SEO -> "赛奥线"
    BuddyCampaignLine.LEDA -> "蕾达线"
}

private fun BuddyCampaignLine.tinyGlyph(): String = when (this) {
    BuddyCampaignLine.KAI -> "凯"
    BuddyCampaignLine.DIT -> "迪"
    BuddyCampaignLine.SEO -> "赛"
    BuddyCampaignLine.LEDA -> "蕾"
}

@Composable
private fun routeLineTone(line: BuddyCampaignLine): Pair<Color, Color> {
    val dark = isSystemInDarkTheme()
    return when (line) {
        BuddyCampaignLine.KAI -> if (dark) Color(0xFF4A2524) to Color(0xFFFFDAD6) else Color(0xFFF9E2E0) to Color(0xFF7D241E)
        BuddyCampaignLine.DIT -> if (dark) Color(0xFF20354A) to Color(0xFFD6E9FF) else Color(0xFFE0ECF8) to Color(0xFF1F4770)
        BuddyCampaignLine.SEO -> if (dark) Color(0xFF45371B) to Color(0xFFFFE5A6) else Color(0xFFF9EED2) to Color(0xFF6B4C0B)
        BuddyCampaignLine.LEDA -> if (dark) Color(0xFF352C44) to Color(0xFFE6DCFA) else Color(0xFFECE6F6) to Color(0xFF483668)
    }
}
