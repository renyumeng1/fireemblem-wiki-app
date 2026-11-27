package com.fireemblem.companion.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fireemblem.companion.engine.*
import com.fireemblem.companion.importer.AtomicBuddyArchiveStore
import com.fireemblem.companion.importer.FefwGrowthSeedImporter
import com.fireemblem.companion.importer.FefwGrowthSeedImportPreview
import com.fireemblem.companion.importer.FefwGrowthChaptersImporter
import com.fireemblem.companion.importer.FefwGrowthChaptersImportPreview
import com.fireemblem.companion.personal.PersonalRevision
import com.fireemblem.companion.personal.PersonalSnapshot
import com.fireemblem.companion.personal.PersonalStore
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class StoredGrowthPlanState { NONE, CHECKING, TRUSTED, INVALID }

@Composable
internal fun LevelJournalScreen(
    archiveStore: AtomicBuddyArchiveStore,
    onBack: () -> Unit,
    initialSelectedId: String? = null,
) {
    val context = LocalContext.current
    val store = remember(context) { PersonalStore(context.applicationContext) }
    val profile = remember(context) { context.getSharedPreferences("personal-view", 0).getString("profile", "global") ?: "global" }
    val scope = rememberCoroutineScope()
    val visuals = rememberBuddyVisuals()
    var rules by remember { mutableStateOf<BuddyRules?>(null) }
    var snapshot by remember { mutableStateOf<PersonalSnapshot?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var pendingExportSha256 by rememberSaveable { mutableStateOf<String?>(null) }
    var importPreview by remember { mutableStateOf<FefwGrowthSeedImportPreview?>(null) }
    var chaptersImportPreview by remember { mutableStateOf<FefwGrowthChaptersImportPreview?>(null) }
    var importEntryIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var importRouteIndex by rememberSaveable { mutableIntStateOf(-1) }
    var importLineId by rememberSaveable { mutableStateOf<String?>(null) }
    var importName by rememberSaveable { mutableStateOf("") }
    var importOutcomeJournalId by rememberSaveable { mutableStateOf<String?>(null) }
    var importOutcomeMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var lineId by rememberSaveable { mutableStateOf("kai") }
    var selectedId by rememberSaveable(initialSelectedId) { mutableStateOf<String?>(initialSelectedId) }
    var selectingObservations by rememberSaveable { mutableStateOf(false) }
    var selectedObservationIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var characterId by rememberSaveable { mutableStateOf("c044") }
    var editorId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var bulkDelete by rememberSaveable { mutableStateOf(false) }
    var restorePrevious by rememberSaveable { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var planning by rememberSaveable { mutableStateOf(false) }
    var workbenchContext by remember { mutableStateOf<SeedWorkbenchLaunchContext?>(null) }
    var unlockEditor by rememberSaveable { mutableStateOf(false) }
    var examEditorId by rememberSaveable { mutableStateOf<String?>(null) }
    var professionChangeEditor by rememberSaveable { mutableStateOf(false) }
    var professionChangeEditId by rememberSaveable { mutableStateOf<String?>(null) }
    var plannedStepIndex by rememberSaveable { mutableIntStateOf(-1) }
    var importedActionIndex by rememberSaveable { mutableIntStateOf(-1) }
    var importedExamStepIndex by rememberSaveable { mutableIntStateOf(-1) }
    var importedExamPassed by rememberSaveable { mutableStateOf(true) }
    var undoImportedStep by rememberSaveable { mutableStateOf(false) }
    var examTarget by rememberSaveable { mutableStateOf("") }
    var examProbability by rememberSaveable { mutableStateOf("100") }
    var examPassed by rememberSaveable { mutableStateOf(true) }
    var examResourceCost by rememberSaveable { mutableStateOf("1") }
    var changeTarget by rememberSaveable { mutableStateOf("") }
    var unlockedEditText by rememberSaveable { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(selectedId) {
        selectingObservations = false
        selectedObservationIds = emptySet()
    }
    LaunchedEffect(reload) {
        try {
            val loaded = withContext(Dispatchers.IO) { BuddyRules.bundled() to store.snapshot() }
            rules = loaded.first
            snapshot = loaded.second
            error = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "升级记录未能读取，请重试；原记录没有删除。" }
    }
    val heads = snapshot?.heads().orEmpty()
    val journals = remember(snapshot, profile) {
        heads.values.mapNotNull { group -> group.singleOrNull()?.takeIf { !it.deleted && it.kind == LevelJournal.KIND } }
            .mapNotNull { revision -> runCatching { revision to LevelJournal.decode(revision.payload) }.getOrNull() }
            .filter { it.second.profile == profile }
    }
    val unreadable = heads.values.count { group -> group.any { it.kind == LevelJournal.KIND } &&
        (group.size > 1 || group.singleOrNull()?.let { !it.deleted && runCatching { LevelJournal.decode(it.payload) }.isFailure } == true) }
    val selected = journals.find { it.first.entityId == selectedId }
    val loadedRules = rules
    val journal = selected?.second
    val previousJournal = selected?.let { (head, _) ->
        snapshot?.previousVersion(head.entityId, head.revisionId)?.takeIf { it.kind == LevelJournal.KIND }
            ?.let { revision -> runCatching { LevelJournal.decode(revision.payload) }.getOrNull() }
            ?.takeIf { it.profile == profile }
    }
    val history = remember(journal, loadedRules) { if (journal != null && loadedRules != null) journal.history(loadedRules) else null }
    val currentCheckpoint = journal?.takeIf { it.hasCurrentSeedCheckpoint() }?.seedCheckpoint
    var storedGrowthPlanState by remember(journal, loadedRules) {
        mutableStateOf(if (journal?.growthPlan == null) StoredGrowthPlanState.NONE else StoredGrowthPlanState.CHECKING)
    }
    LaunchedEffect(journal, loadedRules) {
        val currentJournal = journal
        val currentRules = loadedRules
        val plan = currentJournal?.growthPlan
        when {
            plan == null -> storedGrowthPlanState = StoredGrowthPlanState.NONE
            currentRules == null || !currentJournal.hasCurrentGrowthPlan() ->
                storedGrowthPlanState = StoredGrowthPlanState.INVALID
            else -> {
                storedGrowthPlanState = StoredGrowthPlanState.CHECKING
                val verified = withContext(Dispatchers.Default) {
                    try {
                        BuddyGrowthPlanner.plan(plan.request, currentRules) == plan.result
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        false
                    }
                }
                storedGrowthPlanState = if (verified) StoredGrowthPlanState.TRUSTED else StoredGrowthPlanState.INVALID
            }
        }
    }
    val activePlan = journal?.growthPlan?.takeIf { storedGrowthPlanState == StoredGrowthPlanState.TRUSTED }
    val selectedImportEntry = importPreview?.entries?.find { it.index == importEntryIndex }
    val selectedChaptersEntry = chaptersImportPreview?.entries?.find { it.index == importEntryIndex }
    val selectedChaptersRoute = selectedChaptersEntry?.routes?.find { it.index == importRouteIndex }
    val duplicateImportedJournal = selectedImportEntry?.let { entry ->
        journals.firstOrNull { (_, existing) ->
            existing.importSourceSha256 == importPreview?.sourceSha256 &&
                existing.importSourceRecordIndex == entry.index &&
                existing.line.id == importLineId
        }
    }
    val duplicateImportedRouteJournal = selectedChaptersEntry?.let { entry ->
        selectedChaptersRoute?.let { route -> journals.firstOrNull { (_, existing) ->
            existing.importSourceSha256 == chaptersImportPreview?.sourceSha256 &&
                existing.importSourceRecordIndex == entry.index && existing.line.id == importLineId &&
                existing.importedGrowthPlan?.let { plan -> plan.entryId == entry.entryId && plan.routeIndex == route.index } == true
        } }
    }
    val activeImportedGrowthPlan = journal?.takeIf(LevelJournal::hasCurrentImportedGrowthPlan)?.importedGrowthPlan

    fun openExamEditor(observation: LevelObservation? = null) {
        val targetPart3 = observation?.part3 ?: journal?.currentPart3 ?: false
        val currentRules = loadedRules
        val currentJournal = journal
        examTarget = observation?.exam?.targetProfession ?: if (currentRules != null && currentJournal != null) {
            currentRules.professions.firstOrNull { currentRules.classAllowed(currentJournal.characterId, it.name, targetPart3) }?.name.orEmpty()
        } else ""
        examProbability = observation?.exam?.let { it.probability?.toString().orEmpty() } ?: "100"
        examPassed = observation?.exam?.passed ?: true
        examResourceCost = observation?.exam?.let { it.resourceCost?.toString().orEmpty() } ?: "1"
        examEditorId = observation?.id ?: "new"
    }

    val growthSeedDocumentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val importRules = loadedRules
            if (importRules == null) {
                error = "成长规则还没准备好，请稍后再试。"
            } else if (!busy && !importing) {
                importing = true
                error = null
                scope.launch {
                    try {
                        val previews = withContext(Dispatchers.IO) {
                            val bytes = context.contentResolver.openInputStream(uri)?.use(::readBoundedGrowthSeedJson)
                                ?: throw IOException("无法读取所选文件。")
                            try {
                                if (FefwGrowthChaptersImporter.isGrowthChaptersFile(bytes)) {
                                    null to FefwGrowthChaptersImporter.inspect(bytes, importRules)
                                } else {
                                    FefwGrowthSeedImporter.inspect(bytes, importRules) to null
                                }
                            } finally {
                                bytes.fill(0)
                            }
                        }
                        importPreview = previews.first
                        chaptersImportPreview = previews.second
                        importEntryIndex = previews.first?.entries?.singleOrNull()?.index
                            ?: previews.second?.entries?.singleOrNull()?.index
                        importRouteIndex = previews.second?.entries?.singleOrNull()?.routes?.singleOrNull()?.index ?: -1
                        importLineId = null
                        importName = ""
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        val reason = failure.message?.takeIf(String::isNotBlank)
                        error = "导入失败，现有记录未变更。${reason?.let { " $it" }.orEmpty()}"
                    } finally {
                        importing = false
                    }
                }
            }
        }
    }

    val growthSeedExportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        val sourceSha256 = pendingExportSha256
        pendingExportSha256 = null
        if (uri != null && sourceSha256 != null && !busy) {
            busy = true
            error = null
            scope.launch {
                try {
                    val bytes = withContext(Dispatchers.IO) {
                        store.growthSeedSourceArchive(sourceSha256)?.restoreBytes()
                            ?: throw IOException("原始成长文件不在当前个人存档中。")
                    }
                    try {
                        withContext(Dispatchers.IO) {
                            val output = context.contentResolver.openOutputStream(uri, "wt")
                                ?: throw IOException("无法创建导出文件。")
                            output.use { it.write(bytes) }
                        }
                    } finally {
                        bytes.fill(0)
                    }
                    error = null
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = failure.message ?: "原始成长文件导出失败。" }
                finally { busy = false }
            }
        }
    }

    fun save(entityId: String, value: LevelJournal, after: () -> Unit) {
        if (busy) return
        val parents = heads[entityId].orEmpty().map(PersonalRevision::revisionId).toSet()
        error = null
        busy = true
        scope.launch {
            try {
                snapshot = withContext(Dispatchers.IO) { store.edit(entityId, LevelJournal.KIND, value.encode(), expectedParents = parents) }
                error = null
                after()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "未能保存，请重试。" }
            finally { busy = false }
        }
    }

    fun dismissImportPreview() {
        importPreview = null
        chaptersImportPreview = null
        importEntryIndex = null
        importRouteIndex = -1
        importLineId = null
        importName = ""
    }

    fun back() {
        if (busy) return
        when {
            importPreview != null || chaptersImportPreview != null -> dismissImportPreview()
            importedExamStepIndex >= 0 -> importedExamStepIndex = -1
            undoImportedStep -> undoImportedStep = false
            workbenchContext != null -> workbenchContext = null
            searching -> searching = false
            planning -> planning = false
            editorId != null -> { editorId = null; importedActionIndex = -1; plannedStepIndex = -1 }
            examEditorId != null -> examEditorId = null
            professionChangeEditor -> professionChangeEditor = false
            unlockEditor -> unlockEditor = false
            creating -> creating = false
            initialSelectedId != null && selectedId == initialSelectedId -> onBack()
            selectedId != null -> selectedId = null
            else -> onBack()
        }
    }
    BackHandler { back() }
    workbenchContext?.let { ctx ->
        val activeJournal = journal
        val activeCheckpoint = currentCheckpoint
        val activeEntityId = selectedId
        SeedForecastScreen(
            onBack = { workbenchContext = null },
            initialContext = ctx,
            savingJournalPlan = busy,
            journalPlanSaveError = error,
            onSaveJournalGrowthPlan = if (activeJournal != null && activeCheckpoint != null && activeEntityId != null) {
                { plan, stats, caps, unlocked ->
                    val confirmedCheckpoint = activeCheckpoint.copy(
                        stats = stats,
                        caps = caps,
                        unlockedProfessions = unlocked,
                    ).validated()
                    val savedPlan = plan.copy(
                        historyDigest = activeJournal.historyDigest(),
                        ruleRevision = activeJournal.ruleRevision,
                    ).validated()
                    save(
                        activeEntityId,
                        activeJournal.copy(
                            seedCheckpoint = confirmedCheckpoint,
                            unlockedProfessions = unlocked,
                            growthPlan = savedPlan,
                        ),
                    ) { workbenchContext = null }
                }
            } else null,
        )
        return
    }
    if (searching && history != null && journal != null) {
        val searchHistory = history
        val searchJournal = journal
        val searchEntityId = selectedId
        SeedSearchScreen(archiveStore, emptyList(), onBack = { searching = false }, onChooseBackup = {},
            readingPositionKey = "journal:$selectedId/search",
            savingSelection = busy, saveError = error,
            recordedHistory = searchHistory, recordLabel = "${searchJournal.line.label} · ${searchJournal.name}",
            onOpenRecords = { searching = false },
            onPreviewCandidate = { ctx -> workbenchContext = ctx },
            onSelectCandidate = { candidate, result ->
                val entityId = searchEntityId
                if (entityId != null) {
                    val currentCheckpoint = searchJournal.seedCheckpoint
                        ?.takeIf { searchJournal.hasCurrentSeedCheckpoint() }
                    val lastObservation = searchJournal.observations.lastOrNull()
                    val latestLevel = lastObservation?.let { it.level + if (it.levelsUp) 1 else 0 }
                        ?: currentCheckpoint?.level
                    val latestProfession = lastObservation?.profession ?: currentCheckpoint?.profession
                    val latestPart3 = lastObservation?.part3 ?: currentCheckpoint?.part3
                    if (latestLevel == null || latestProfession == null || latestPart3 == null) {
                        error = "无法确认这册记录的当前等级、职业或篇章；请先补齐实测记录，再保存种子。"
                        searching = false
                    } else {
                        val observedProfessions = buildSet {
                            searchJournal.observations.forEach { observation ->
                                add(observation.profession)
                                observation.professionChangeFrom?.let(::add)
                                observation.exam?.takeIf { it.passed }?.targetProfession?.let(::add)
                            }
                            add(latestProfession)
                        }
                        val isUniqueCompleteSearch = result.fullUint32SpaceScanned &&
                            result.matches == 1L && candidate.originSeeds.size == 1
                        val checkpoint = JournalSeedCheckpoint(
                            seed = candidate.currentSeed,
                            eventIndex = searchHistory.events.size,
                            level = latestLevel,
                            profession = latestProfession,
                            part3 = latestPart3,
                            stats = currentCheckpoint?.stats,
                            caps = currentCheckpoint?.caps,
                            unlockedProfessions = currentCheckpoint?.unlockedProfessions ?: observedProfessions,
                            evidence = if (isUniqueCompleteSearch) JournalSeedEvidence.FULL_SEARCH
                                else JournalSeedEvidence.PARTIAL_SEARCH,
                            historyDigest = searchJournal.historyDigest(),
                            ruleRevision = searchJournal.ruleRevision,
                            originSeed = candidate.originSeeds.singleOrNull(),
                            matchCount = if (isUniqueCompleteSearch) result.matches else null,
                        ).validated()
                        save(entityId, searchJournal.copy(seedCheckpoint = checkpoint)) { searching = false }
                    }
                }
            })
        return
    }
    if (planning && journal != null && loadedRules != null) {
        val checkpoint = journal.seedCheckpoint?.takeIf { journal.hasCurrentSeedCheckpoint() }
        if (checkpoint != null) {
            BuddyGrowthPlanningScreen(
                readingPositionKey = "journal:$selectedId/planning",
                journal = journal,
                checkpoint = checkpoint,
                rules = loadedRules,
                saving = busy,
                saveError = error,
                onBack = { planning = false },
                onSave = { plan, stats, caps, unlocked ->
                    selectedId?.let { entityId ->
                        val confirmedCheckpoint = checkpoint.copy(stats = stats, caps = caps, unlockedProfessions = unlocked).validated()
                        val savedPlan = plan.copy(historyDigest = journal.historyDigest(), ruleRevision = journal.ruleRevision).validated()
                        save(entityId, journal.copy(seedCheckpoint = confirmedCheckpoint,
                            unlockedProfessions = unlocked, growthPlan = savedPlan)) { planning = false }
                    }
                },
            )
            return
        }
    }
    if (editorId != null && journal != null && loadedRules != null) {
        val existing = journal.observations.find { it.id == editorId }
        val last = journal.observations.lastOrNull()
        val plan = activePlan?.takeIf { plannedStepIndex >= 0 }
        val plannedStep = plan?.result?.steps?.getOrNull(plannedStepIndex)
        val importedAction = activeImportedGrowthPlan?.takeIf { importedActionIndex >= 0 }
            ?.actions?.getOrNull(importedActionIndex)
            ?.takeIf { it.kind == ImportedGrowthActionKind.UPGRADE }
        val plannedInput = plan?.takeIf { plannedStep != null }?.request
        val plannedPart3 = journal.seedCheckpoint?.part3 == true ||
            plannedStep?.let { plannedInput?.currentPart3 == true || plannedInput?.part3StartLevel?.let { start -> it.level >= start } == true } == true
        val plannedRoom = plannedInput?.caps?.indices?.map { index ->
            val currentStat = currentCheckpoint?.stats?.get(index) ?: plannedInput.currentStats[index]
            ((plannedInput.caps[index] ?: 32_767) - currentStat).coerceAtLeast(0)
        } ?: List(9) { 32767 }
        val importedRoom = activeImportedGrowthPlan?.takeIf { importedAction != null }?.caps?.indices?.map { index ->
            activeImportedGrowthPlan.caps[index]?.let { cap ->
                (cap - activeImportedGrowthPlan.currentStats()[index]).coerceAtLeast(0)
            } ?: 32767
        } ?: List(9) { 32767 }
        val initial = existing ?: plannedStep?.let { step -> LevelObservation(id = requireNotNull(editorId),
            level = step.level, profession = step.profession, part3 = plannedPart3, ride = step.ride,
            gains = step.gains, room = plannedRoom) } ?: LevelObservation(id = requireNotNull(editorId),
            level = importedAction?.let { activeImportedGrowthPlan?.currentLevel() }
                ?: (last?.let { it.level + if (it.levelsUp) 1 else 0 } ?: journal.seedCheckpoint?.level ?: 1).coerceAtMost(98),
            profession = importedAction?.profession ?: last?.profession ?: currentCheckpoint?.profession ?: loadedRules.professions.first {
                loadedRules.classAllowed(journal.characterId, it.name, loadedRules.characters.first { character -> character.id == journal.characterId }.part3Only)
            }.name,
            part3 = activeImportedGrowthPlan?.takeIf { importedAction != null }?.part3 ?:
                last?.part3 ?: currentCheckpoint?.part3 ?: loadedRules.characters.first { it.id == journal.characterId }.part3Only,
            ride = if (importedAction != null) null else last?.ride,
            room = importedRoom)
        key(editorId) {
            LevelObservationEditor(journal, initial, loadedRules, visuals, busy, error,
                planned = plannedStep != null, importedRouteStep = importedAction != null, onBack = ::back) { observation ->
                val entityId = requireNotNull(selectedId)
                if (plannedStep != null) {
                    val updated = runCatching { journal.recordPlannedUpgrade(observation, loadedRules) }
                        .getOrElse { failure -> error = failure.message ?: "无法确认计划步骤"; return@LevelObservationEditor }
                    val matched = updated.hasCurrentGrowthPlan()
                    save(entityId, updated) {
                        editorId = null; plannedStepIndex = -1; importedActionIndex = -1
                        if (!matched) error = "实测提升与预测不同，已保留实测记录；旧种子和后续方案已失效，请重新反推。"
                    }
                } else if (importedAction != null) {
                    val updated = runCatching { journal.confirmImportedGrowthUpgrade(observation, loadedRules) }
                        .getOrElse { failure -> error = failure.message ?: "无法确认导入路线步骤"; return@LevelObservationEditor }
                    val matched = updated.seedCheckpoint?.let { updated.hasCurrentSeedCheckpoint() } == true
                    save(entityId, updated) {
                        editorId = null
                        importedActionIndex = -1
                        if (!matched) importOutcomeMessage = "实际成长已记录，路线进度已保存；种子未通过本地规则核对，后续种子状态保持未知。"
                        else importOutcomeMessage = "实际成长已记录，路线和已确认种子状态均已更新。"
                        importOutcomeJournalId = entityId
                    }
                } else {
                    val rows = if (existing == null) journal.observations + observation
                        else journal.observations.map { if (it.id == observation.id) observation else it }
                    save(entityId, journal.copy(observations = rows)) { editorId = null }
                }
            }
        }
        return
    }
    LazyVerticalGrid(columns = GridCells.Adaptive(150.dp),
        state = rememberReadingGridState("journal:$profile:$lineId:${selectedId ?: "list"}", snapshot != null && loadedRules != null),
        modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 880.dp),
        contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 112.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) { Column {
            TextButton(onClick = ::back, enabled = !busy) {
                Text(if (selectedId != null && selectedId != initialSelectedId) "返回记录册" else "返回工具")
            }
            ScreenHeading("升级记录", "记下实际成长，随时用新记录重新反推。")
            error?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.colorScheme.error) }
            if (snapshot == null || loadedRules == null) {
                if (error == null) CircularProgressIndicator()
                else OutlinedButton(onClick = { reload++ }) { Text("重新读取") }
            }
            if (busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("正在保存更改…", Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } }
        if (snapshot != null && loadedRules != null) {
            if (selectedId == null) {
                item(span = { GridItemSpan(maxLineSpan) }) { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("选择路线", style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary)
                            BoxWithConstraints(Modifier.fillMaxWidth()) {
                                if (maxWidth >= 600.dp) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        BuddyCampaignLine.entries.forEach { line ->
                                            FilterChip(selected = lineId == line.id, onClick = { lineId = line.id },
                                                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                                label = { Text(line.label, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                                        }
                                    }
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        BuddyCampaignLine.entries.chunked(2).forEach { row ->
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                row.forEach { line ->
                                                    FilterChip(selected = lineId == line.id, onClick = { lineId = line.id },
                                                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                                        label = { Text(line.label, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                                                }
                                                if (row.size == 1) Spacer(Modifier.weight(1f))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Text("同一路线可建多册，分别记录不同周目或角色。记录随个人备份和 WebDAV 同步。", style = MaterialTheme.typography.bodySmall)
                    if (unreadable > 0) Text("有 $unreadable 册存在同步冲突或无法读取，请到个人存档检查；不会自动覆盖。", color = MaterialTheme.colorScheme.error)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { creating = true }, enabled = !busy && !importing,
                            modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("新建记录册") }
                        OutlinedButton(onClick = { growthSeedDocumentPicker.launch(arrayOf("application/json")) },
                            enabled = !busy && !importing,
                            modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                            Text(if (importing) "正在读取文件…" else "导入成长记录", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (importing) LinearProgressIndicator(Modifier.fillMaxWidth())
                } }
                itemsIndexed(journals.filter { it.second.line.id == lineId }, key = { _, pair -> pair.first.entityId }) { _, pair ->
                    Card(onClick = { selectedId = pair.first.entityId }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val character = loadedRules.characters.find { it.id == pair.second.characterId }
                            val displayCharacterName = character?.let { characterLabel(it.id, it.name) } ?: pair.second.characterId
                            BuddyPortrait(pair.second.characterId, displayCharacterName, visuals,
                                part3 = pair.second.observations.lastOrNull()?.part3 == true)
                            Text(displayCharacterName, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text(pair.second.name, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(10.dp)) {
                                    Text("${pair.second.observations.count { it.levelsUp }} 次升级",
                                        Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                                }
                                pair.second.currentLevel?.let { level ->
                                    Text("Lv. $level", style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                if (journals.none { it.second.line.id == lineId }) item(span = { GridItemSpan(maxLineSpan) }) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        shape = RoundedCornerShape(18.dp)) {
                        Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.AutoStories, contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("这条路线还没有记录", style = MaterialTheme.typography.titleSmall)
                                Text("新建一册，从首次实测开始。", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            } else if (journal != null) {
                item(span = { GridItemSpan(maxLineSpan) }) { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val character = loadedRules.characters.find { it.id == journal.characterId }
                    val displayCharacterName = character?.let { characterLabel(it.id, it.name) } ?: journal.characterId
                    val sameRouteJournals = journals.filter { it.second.line == journal.line }
                    if (sameRouteJournals.size > 1) {
                        val switcherState = rememberLazyListState()
                        LaunchedEffect(selectedId, sameRouteJournals.map { it.first.entityId }) {
                            val selectedIndex = sameRouteJournals.indexOfFirst { it.first.entityId == selectedId }
                            if (selectedIndex >= 0 &&
                                switcherState.layoutInfo.visibleItemsInfo.none { it.index == selectedIndex }
                            ) {
                                switcherState.scrollToItem((selectedIndex - 1).coerceAtLeast(0))
                            }
                        }
                        Text("同路线记录", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary)
                        LazyRow(
                            state = switcherState,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(vertical = 2.dp),
                        ) {
                            itemsIndexed(sameRouteJournals, key = { _, pair -> pair.first.entityId }) { _, pair ->
                                val entryCharacter = loadedRules.characters.find { it.id == pair.second.characterId }
                                val entryName = entryCharacter?.let { characterLabel(it.id, it.name) } ?: pair.second.characterId
                                val isSelected = pair.first.entityId == selectedId
                                Card(onClick = { selectedId = pair.first.entityId },
                                    colors = CardDefaults.cardColors(containerColor = if (isSelected)
                                        MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow),
                                    shape = RoundedCornerShape(16.dp)) {
                                    Row(Modifier.widthIn(min = 164.dp).padding(10.dp),
                                        horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                                        BuddyPortrait(pair.second.characterId, entryName, visuals,
                                            part3 = pair.second.observations.lastOrNull()?.part3 == true,
                                            modifier = Modifier.size(48.dp))
                                        Column(Modifier.widthIn(max = 128.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(entryName, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                                                overflow = TextOverflow.Ellipsis)
                                            Text(pair.second.name, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                                overflow = TextOverflow.Ellipsis)
                                            Text(pair.second.currentLevel?.let { "Lv. $it" } ?: "尚未记录升级",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                BuddyPortrait(
                                    journal.characterId,
                                    displayCharacterName,
                                    visuals,
                                    modifier = Modifier.size(60.dp),
                                    part3 = journal.observations.lastOrNull()?.part3 == true,
                                )
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(displayCharacterName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        journal.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        journal.line.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                journal.currentLevel?.let { level ->
                                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(10.dp)) {
                                        Text(
                                            "Lv. $level",
                                            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        )
                                    }
                                }
                            }
                            // Inline seed status strip
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                if (currentCheckpoint != null) {
                                    val evidenceTag = when (currentCheckpoint.evidence) {
                                        JournalSeedEvidence.FULL_SEARCH -> "唯一匹配"
                                        JournalSeedEvidence.IMPORTED_REPLAY -> "回放核对"
                                        JournalSeedEvidence.USER_CONFIRMED_IMPORT -> "导入确认"
                                        JournalSeedEvidence.PARTIAL_SEARCH -> "部分候选"
                                        JournalSeedEvidence.MANUAL -> "手动"
                                        JournalSeedEvidence.EVENT_CONFIRMED -> "考试核对"
                                        JournalSeedEvidence.PLAN_CONFIRMED -> "计划核对"
                                    }
                                    Text(
                                        "当前种子 ${currentCheckpoint.seed} · $evidenceTag",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                } else {
                                    Text(
                                        if (journal.seedCheckpoint != null) "记录已变，请重新反推种子"
                                        else "尚未定位种子（记录 4 次升级即可反推）",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (journal.seedCheckpoint != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (currentCheckpoint != null && history?.ready == true) {
                                    TextButton(
                                        onClick = { searching = true },
                                        enabled = !busy,
                                        modifier = Modifier.heightIn(min = 34.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp),
                                    ) { Text("重新反推") }
                                }
                            }
                        }
                    }

                    if (importOutcomeJournalId == selectedId) {
                        importOutcomeMessage?.let {
                            Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    journal.importSourceSha256?.let { sourceSha256 ->
                        OutlinedButton(
                            onClick = {
                                pendingExportSha256 = sourceSha256
                                growthSeedExportPicker.launch(growthSeedExportFileName(journal.name, journal.importedGrowthPlan != null))
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Outlined.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (journal.importedGrowthPlan != null) "导出原始路线方案" else "导出原始成长文件")
                        }
                    }

                    // Unified Core Seed / Workbench Action (ONE clean entry — replaces 3 overlapping cards!)
                    if (currentCheckpoint != null && currentCheckpoint.level < 99) {
                        val openWorkbench: (String) -> Unit = { startTab ->
                            workbenchContext = SeedWorkbenchLaunchContext(
                                characterId = journal.characterId,
                                campaignLine = journal.line,
                                part3 = currentCheckpoint.part3,
                                seed = currentCheckpoint.seed,
                                inferredLevel = currentCheckpoint.level,
                                profession = currentCheckpoint.profession,
                                ride = journal.observations.lastOrNull()?.ride,
                                unlockedProfessions = journal.confirmedUnlockedProfessions() + currentCheckpoint.profession,
                                baseStats = currentCheckpoint.stats,
                                caps = currentCheckpoint.caps,
                                sourceLabel = "${journal.line.label} · ${journal.name}",
                                backLabel = "返回记录册",
                                autoRun = true,
                                initialTab = startTab,
                                journalHistoryDigest = journal.historyDigest(),
                                journalRuleRevision = journal.ruleRevision,
                            )
                        }
                        JournalActionCard(
                            title = "乱数预览与培养规划",
                            description = "99 级乱数预览 · 换职对比 · 自动规划最优转职路线",
                            icon = Icons.AutoMirrored.Outlined.TrendingUp,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { openWorkbench("preview") },
                        )
                    } else if (currentCheckpoint == null) {
                        JournalActionCard(
                            title = "用这些记录反推种子",
                            description = "用已录入的实测升级极速定位乱数种子",
                            icon = Icons.Outlined.AutoFixHigh,
                            enabled = !busy && history?.ready == true,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { searching = true },
                        )
                    }

                    // Streamlined Recording Bar (1 primary button + 1 compact row of 3 utility pills!)
                    Button(
                        onClick = { plannedStepIndex = -1; importedActionIndex = -1; editorId = UUID.randomUUID().toString() },
                        enabled = !busy && (journal.currentLevel ?: currentCheckpoint?.level ?: 1) < 99,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text("记录本次升级") }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (journal.currentLevel != null) {
                            OutlinedButton(
                                onClick = { openExamEditor() },
                                enabled = !busy,
                                modifier = Modifier.weight(1f).heightIn(min = 42.dp),
                                contentPadding = PaddingValues(horizontal = 6.dp),
                            ) { Text("记录考试结果", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            OutlinedButton(
                                onClick = {
                                    professionChangeEditId = null
                                    changeTarget = journal.confirmedUnlockedProfessions().sorted().firstOrNull {
                                        it != journal.currentProfession && loadedRules.classAllowed(journal.characterId, it, journal.currentPart3 ?: false)
                                    }.orEmpty()
                                    professionChangeEditor = true
                                },
                                enabled = !busy,
                                modifier = Modifier.weight(0.85f).heightIn(min = 42.dp),
                                contentPadding = PaddingValues(horizontal = 6.dp),
                            ) { Text("职业切换", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                        OutlinedButton(
                            onClick = {
                                unlockedEditText = journal.confirmedUnlockedProfessions().sorted().joinToString("\n")
                                unlockEditor = true
                            },
                            enabled = !busy,
                            modifier = Modifier.weight(1.1f).heightIn(min = 42.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp),
                        ) {
                            Text(
                                "已解锁职业 (${journal.confirmedUnlockedProfessions().size})",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    if (activePlan != null) {
                        val nextStep = activePlan.result.steps.getOrNull(activePlan.nextStepIndex)
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    if (nextStep == null) "培养方案已完成" else "已关联培养方案 · 还剩 ${activePlan.result.steps.size - activePlan.nextStepIndex} 步",
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                nextStep?.let { step ->
                                    Text("下一步：Lv.${step.level} · ${step.profession}" + (step.switchFrom?.let { "（从 $it 转入）" } ?: ""))
                                    Text(
                                        GrowthStat.entries.mapIndexedNotNull { index, stat ->
                                            step.gains[index].takeIf { it > 0 }?.let { "${stat.label} +$it" }
                                        }.ifEmpty { listOf("无属性提升") }.joinToString("、"),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Button(
                                        onClick = { plannedStepIndex = activePlan.nextStepIndex; importedActionIndex = -1; editorId = UUID.randomUUID().toString() },
                                        enabled = !busy,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) { Text("记录这步实际结果") }
                                }
                            }
                        }
                    } else if (journal.growthPlan != null && storedGrowthPlanState == StoredGrowthPlanState.CHECKING) {
                        Text("正在核对已保存的培养方案…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else if (journal.growthPlan != null) {
                        Text("已保存方案与当前记录不一致，请重新反推并规划。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }

                    activeImportedGrowthPlan?.let { importedPlan ->
                        val nextAction = importedPlan.actions.getOrNull(importedPlan.nextStepIndex)
                        Card(
                            Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    if (nextAction == null) "导入路线已完成" else "已导入路线 · ${importedPlan.nextStepIndex + 1}/${importedPlan.actions.size}",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                                Text(
                                    "${importedPlan.chapter} · ${importedPlan.routeName}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                                Text(
                                    importedPlan.currentSeed()?.let { "当前种子：$it" } ?: "当前种子未核实；实测仍会保存。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                                nextAction?.let { action ->
                                    if (action.kind == ImportedGrowthActionKind.UPGRADE) {
                                        Text(
                                            "下一步升级 · Lv.${importedPlan.currentLevel()} · ${action.profession}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                                        )
                                        Button(
                                            onClick = {
                                                plannedStepIndex = -1
                                                importedActionIndex = importedPlan.nextStepIndex
                                                editorId = UUID.randomUUID().toString()
                                            },
                                            enabled = !busy,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) { Text("录入这步的实际成长") }
                                    } else {
                                        Text(
                                            "下一步考试 · ${action.profession}（不升级）",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                                        )
                                        Button(
                                            onClick = {
                                                importedExamPassed = true
                                                importedExamStepIndex = importedPlan.nextStepIndex
                                            },
                                            enabled = !busy,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) { Text("确认这次考试结果") }
                                    }
                                }
                                if (importedPlan.confirmations.isNotEmpty()) {
                                    OutlinedButton(
                                        onClick = { undoImportedStep = true },
                                        enabled = !busy,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) { Text("撤销最近一次路线确认") }
                                }
                            }
                        }
                    }
                    if (journal.importedGrowthPlan != null && activeImportedGrowthPlan == null) {
                        Text("导入路线与记录历史不一致，已停用继续与撤销；实测记录仍保留。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    history?.errors?.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    history?.warnings?.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }

                    // Compact Observation List Header (merges 批量管理 & 恢复上个版本 right into the header row!)
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "实测记录 (${journal.observations.size})",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (selectingObservations) {
                                Button(
                                    onClick = { bulkDelete = true },
                                    enabled = !busy && selectedObservationIds.isNotEmpty(),
                                    modifier = Modifier.heightIn(min = 36.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp),
                                ) { Text("删除已选 ${selectedObservationIds.size}") }
                                TextButton(
                                    onClick = { selectingObservations = false; selectedObservationIds = emptySet() },
                                    enabled = !busy,
                                    modifier = Modifier.heightIn(min = 36.dp),
                                ) { Text("取消") }
                            } else {
                                if (journal.observations.isNotEmpty()) {
                                    TextButton(
                                        onClick = { selectingObservations = true },
                                        enabled = !busy,
                                        modifier = Modifier.heightIn(min = 36.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp),
                                    ) { Text("批量管理记录") }
                                }
                                if (previousJournal != null) {
                                    TextButton(
                                        onClick = { restorePrevious = true },
                                        enabled = !busy,
                                        modifier = Modifier.heightIn(min = 36.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp),
                                    ) { Text("恢复上个版本") }
                                }
                            }
                        }
                    }
                } }
                itemsIndexed(journal.observations, key = { _, row -> row.id }, span = { _, _ -> GridItemSpan(maxLineSpan) }) { index, row ->
                    val exam = row.exam
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (selectingObservations) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(row.id in selectedObservationIds, onCheckedChange = { checked ->
                                        selectedObservationIds = if (checked) selectedObservationIds + row.id
                                            else selectedObservationIds - row.id
                                    }, enabled = !busy, modifier = Modifier.semantics {
                                        contentDescription = "选择第 ${index + 1} 条记录"
                                    })
                                    Text("选择这条记录")
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("${index + 1}. " + when {
                                    exam != null -> "考试 · ${if (exam.passed) "通过" else "未通过"} · ${exam.targetProfession.ifBlank { "职业未记录" }}"
                                    row.professionChangeFrom != null -> "切换职业 · ${row.professionChangeFrom} → ${row.profession}"
                                    row.skipped -> "跳过 · ${row.level} 级"
                                    else -> "Lv.${row.level} → Lv.${row.level + 1}"
                                }, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${row.profession} · ${if (row.part3) "第三部" else "前篇"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            row.growthLevel?.let { level ->
                                Text("按 Lv.$level 规则计算", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                            }
                            if (exam != null) Text(
                                "成功率 ${exam.probability?.let { "$it%" } ?: "未记录"} · 消耗 ${exam.resourceCost?.toString() ?: "未记录"}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (row.levelsUp) Text(
                                GrowthStat.entries.mapIndexedNotNull { statIndex, stat ->
                                    row.gains[statIndex].takeIf { it > 0 }?.let { "${stat.label} +$it" }
                                }.joinToString("　").ifEmpty { "没有属性提升" },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (!selectingObservations) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    if (row.exam != null) TextButton(onClick = { openExamEditor(row) }, enabled = !busy) { Text("纠正考试") }
                                    else if (row.professionChangeFrom != null) TextButton(onClick = {
                                        professionChangeEditId = row.id; changeTarget = row.profession; professionChangeEditor = true
                                    }, enabled = !busy) { Text("纠正转职") }
                                    else TextButton(onClick = { plannedStepIndex = -1; importedActionIndex = -1; editorId = row.id }, enabled = !busy) { Text("纠正记录") }
                                    TextButton(onClick = { deleteId = row.id }, enabled = !busy) { Text("删除") }
                                    Spacer(Modifier.weight(1f))
                                    TextButton(onClick = {
                                        runCatching { journal.moveObservation(row.id, -1) }.onSuccess { updated ->
                                            save(requireNotNull(selectedId), updated) {}
                                        }.onFailure { failure -> error = failure.message ?: "无法调整记录顺序" }
                                    }, enabled = !busy && index > 0, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("上移") }
                                    TextButton(onClick = {
                                        runCatching { journal.moveObservation(row.id, 1) }.onSuccess { updated ->
                                            save(requireNotNull(selectedId), updated) {}
                                        }.onFailure { failure -> error = failure.message ?: "无法调整记录顺序" }
                                    }, enabled = !busy && index < journal.observations.lastIndex, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("下移") }
                                }
                            }
                        }
                    }
                }
            } else item(span = { GridItemSpan(maxLineSpan) }) { Text("记录已删除、发生同步冲突或无法读取，请返回检查个人存档。") }
        }
    }
    if (creating && loadedRules != null) AlertDialog(onDismissRequest = { if (!busy) creating = false },
        title = { Text("新建${BuddyCampaignLine.entries.first { it.id == lineId }.label}记录册") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it.take(100) }, label = { Text("名称（如一周目·凯伊）") }, singleLine = true)
            val selectedCharacter = loadedRules.characters.find { it.id == characterId }
            IllustratedChoice("角色", characterId,
                selectedCharacter?.let { characterLabel(it.id, it.name) } ?: "选择角色",
                loadedRules.characters.map { it.id to characterLabel(it.id, it.name) }, "characters", visuals) { characterId = it }
        } },
        confirmButton = { TextButton(enabled = name.isNotBlank() && !busy, onClick = {
            val id = UUID.randomUUID().toString()
            save(id, LevelJournal(name.trim(), profile, BuddyCampaignLine.entries.first { it.id == lineId }, characterId)) {
                creating = false; selectedId = id; name = ""
            }
        }) { Text("创建") } },
        dismissButton = { TextButton(onClick = { creating = false }, enabled = !busy) { Text("取消") } })
    val preview = importPreview
    val previewEntry = selectedImportEntry
    if (preview != null && loadedRules != null) AlertDialog(
        onDismissRequest = { if (!busy) dismissImportPreview() },
        title = { Text("导入成长记录") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text("选择一条要加入记录册的实测成长。文件中的路线标签不会替你决定所属路线。",
                        style = MaterialTheme.typography.bodyMedium)
                    Text("共发现 ${preview.entries.size} 条角色记录。含多条记录时，请明确选择要导入的一条。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                itemsIndexed(preview.entries, key = { _, entry -> entry.index }) { _, entry ->
                    val chosen = importEntryIndex == entry.index
                    val displayCharacterName = characterLabel(entry.characterId, entry.characterName)
                    Card(onClick = { importEntryIndex = entry.index },
                        colors = CardDefaults.cardColors(containerColor = if (chosen)
                            MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.RadioButton(selected = chosen,
                                onClick = { importEntryIndex = entry.index },
                                modifier = Modifier.semantics {
                                    contentDescription = "选择${displayCharacterName}的成长记录（第 ${entry.index + 1} 条）"
                                })
                            BuddyPortrait(entry.characterId, displayCharacterName, visuals, part3 = entry.part3)
                            Column(Modifier.weight(1f).padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(displayCharacterName, style = MaterialTheme.typography.titleSmall)
                                Text("${entry.profession} · ${entry.observations.count { it.levelsUp }} 次升级 · ${entry.observations.count { it.exam != null }} 次考试",
                                    style = MaterialTheme.typography.bodySmall)
                                Text(when {
                                    entry.ready -> "可以导入"
                                    entry.errors.isNotEmpty() -> "有 ${entry.errors.size} 项问题，暂不能导入"
                                    else -> "尚未通过导入校验"
                                }, style = MaterialTheme.typography.labelMedium,
                                    color = if (entry.ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                if (preview.entries.isEmpty()) item {
                    Text("文件中没有可加入记录册的实测成长。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                previewEntry?.let { entry -> item {
                    HorizontalDivider()
                    Text("记录内容", style = MaterialTheme.typography.titleSmall)
                    Text("${characterLabel(entry.characterId, entry.characterName)} · ${entry.profession}")
                    Text("等级：${entry.startLevel} 级起${entry.currentLevel?.let { "，记录到 $it 级" } ?: "，未提供当前等级"}")
                    Text("实测记录：${entry.observations.count { it.levelsUp }} 次升级，${entry.observations.count { it.exam != null }} 次考试")
                    Text("候选起始种子：${entry.candidateSeed?.toString() ?: "未提供"}")
                    Text("记录末尾种子：${entry.currentSeed?.toString() ?: "未提供"}")
                    Text(when {
                        entry.fullSearch == true && entry.matchCount == 1L -> "原文件记载：全范围搜索，报告唯一匹配。"
                        entry.fullSearch == true && entry.matchCount != null -> "原文件记载：全范围搜索，报告 ${entry.matchCount} 个匹配。"
                        entry.fullSearch == true -> "原文件记载：全范围搜索，未提供匹配数量。"
                        entry.fullSearch == false -> "原文件记载：全范围搜索未完成。"
                        else -> "原文件未说明搜索范围。"
                    }, style = MaterialTheme.typography.bodySmall)
                    Text("原文件里的搜索范围和匹配数量仅作来源信息，不作为已确认种子的依据。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("只有种子能由实测成长复现、且等级一致时，才会保存种子核对点。来源文件中的当前属性和上限不会当作实测状态；规划前请填写游戏内当前属性。历史计划、预测结果和未完成进度不会作为实测记录导入。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!entry.ready && entry.errors.isEmpty()) {
                        Text("这条记录尚未通过校验，暂时不能创建记录册。", color = MaterialTheme.colorScheme.error)
                    }
                    entry.errors.forEach { message ->
                        Text("问题：$message", color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    entry.warnings.forEach { message ->
                        Text("说明：$message", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall)
                    }
                } }
                item {
                    HorizontalDivider()
                    Text("选择所属路线", style = MaterialTheme.typography.titleSmall)
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val columns = if (maxWidth >= 520.dp) 4 else 2
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            BuddyCampaignLine.entries.chunked(columns).forEach { rowLines ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    rowLines.forEach { line ->
                                        FilterChip(selected = importLineId == line.id, onClick = { importLineId = line.id },
                                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                            label = { Text(line.label, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                                    }
                                    repeat(columns - rowLines.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                    if (duplicateImportedJournal != null) {
                        Card(colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        ), shape = RoundedCornerShape(14.dp)) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("这条实测记录已在当前路线", style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer)
                                Text("已存入「${duplicateImportedJournal.second.name}」，不会重复创建。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer)
                            }
                        }
                    } else {
                        OutlinedTextField(importName, { importName = it.take(100) },
                            label = { Text("新记录册名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = {
            if (duplicateImportedJournal != null) {
                TextButton(enabled = !busy, onClick = {
                    val existingId = duplicateImportedJournal.first.entityId
                    dismissImportPreview()
                    selectedId = existingId
                }) { Text("打开已导入记录") }
            } else TextButton(enabled = !busy && previewEntry?.ready == true && importLineId != null && importName.isNotBlank(),
                onClick = {
                    val entry = previewEntry ?: return@TextButton
                    val line = BuddyCampaignLine.entries.find { it.id == importLineId } ?: return@TextButton
                    val journalToSave = runCatching {
                        entry.toJournal(importName.trim(), profile, line, preview.sourceSha256, loadedRules)
                    }
                        .getOrElse { failure ->
                            error = failure.message ?: "这条成长记录无法加入记录册。"
                            return@TextButton
                        }
                    val entityId = UUID.randomUUID().toString()
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            snapshot = withContext(Dispatchers.IO) {
                                store.importGrowthSeedJournal(entityId, journalToSave.encode(), preview.sourceArchive)
                            }
                            dismissImportPreview()
                            selectedId = entityId
                            importOutcomeJournalId = entityId
                            importOutcomeMessage = if (journalToSave.hasCurrentSeedCheckpoint())
                                "已导入实测成长，并保存了可回放核对的种子状态。原始文件及其中的其他路线、预测和历史也已保留。"
                            else "已导入实测成长，但种子状态不完整或与记录不一致；原始文件及其中的其他路线、预测和历史已保留，可重新反推后再规划。"
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = failure.message ?: "导入失败，现有记录未变更。" }
                        finally { busy = false }
                    }
                }) { Text(if (busy) "正在保存…" else "创建新记录册") }
        },
        dismissButton = { TextButton(onClick = ::dismissImportPreview, enabled = !busy) { Text("取消") } })
    val chaptersPreview = chaptersImportPreview
    val chaptersEntry = selectedChaptersEntry
    val chaptersRoute = selectedChaptersRoute
    if (chaptersPreview != null && loadedRules != null) AlertDialog(
        onDismissRequest = { if (!busy) dismissImportPreview() },
        title = { Text("导入已规划路线") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 540.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text("种子按你已确认的文件起点保存，不会重新执行反推。路线中的未来步骤仍是计划；只有逐步录入游戏里的实际结果后，才会加入实测记录。",
                        style = MaterialTheme.typography.bodyMedium)
                    Text("${chaptersPreview.entries.size} 条角色记录 · 原文件其他分支、未知字段和历史会一并归档。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                itemsIndexed(chaptersPreview.entries, key = { _, entry -> entry.index }) { _, entry ->
                    val chosen = importEntryIndex == entry.index
                    val displayName = characterLabel(entry.characterId, entry.characterName)
                    Card(onClick = {
                        importEntryIndex = entry.index
                        importRouteIndex = entry.routes.singleOrNull()?.index ?: -1
                    }, colors = CardDefaults.cardColors(containerColor = if (chosen)
                        MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow),
                        shape = RoundedCornerShape(14.dp)) {
                        Row(Modifier.fillMaxWidth().padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = chosen, onClick = {
                                importEntryIndex = entry.index
                                importRouteIndex = entry.routes.singleOrNull()?.index ?: -1
                            }, modifier = Modifier.semantics {
                                contentDescription = "选择${displayName}已规划路线（第 ${entry.index + 1} 条）"
                            })
                            BuddyPortrait(entry.characterId, displayName, visuals, part3 = entry.part3)
                            Column(Modifier.weight(1f).padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(displayName, style = MaterialTheme.typography.titleSmall)
                                Text("${entry.chapter} · 第 ${entry.round} 轮 · ${entry.routes.size} 条分支",
                                    style = MaterialTheme.typography.bodySmall)
                                Text(if (entry.ready) "角色与起点映射已核对" else "有 ${entry.errors.size} 项问题，暂不能导入",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (entry.ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                if (chaptersPreview.entries.isEmpty()) item { Text("文件中没有角色记录。") }
                chaptersEntry?.let { entry -> item {
                    HorizontalDivider()
                    Text("选择路线分支", style = MaterialTheme.typography.titleSmall)
                    entry.routes.forEach { route ->
                        val chosen = importRouteIndex == route.index
                        Card(onClick = { importRouteIndex = route.index },
                            colors = CardDefaults.cardColors(containerColor = if (chosen)
                                MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerLow),
                            shape = RoundedCornerShape(14.dp)) {
                            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = chosen, onClick = { importRouteIndex = route.index },
                                    modifier = Modifier.semantics { contentDescription = "选择路线 ${route.name}" })
                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(route.name, style = MaterialTheme.typography.titleSmall)
                                    Text("起点 ${route.initialLevel?.let { "Lv.$it" } ?: "未知"} · 种子 ${route.initialSeed?.toString() ?: "未知"}",
                                        style = MaterialTheme.typography.bodySmall)
                                    Text("${route.actions.count { it.kind == ImportedGrowthActionKind.UPGRADE }} 次升级 · " +
                                        "${route.actions.count { it.kind == ImportedGrowthActionKind.EXAM }} 次考试 · " +
                                        "起始职业 ${entry.initialProfession.ifBlank { "未核实" }}",
                                        style = MaterialTheme.typography.bodySmall)
                                    Text(if (route.ready) "路线步骤已校验" else "此分支有 ${route.errors.size} 项问题",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (route.ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                    entry.errors.forEach { Text("问题：$it", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall) }
                    chaptersRoute?.errors?.forEach { Text("问题：$it", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall) }
                    chaptersRoute?.let { route ->
                        if (route.ready && entry.ready) {
                            Text("起点属性：${GrowthStat.entries.mapIndexed { index, stat ->
                                "${stat.label} ${route.initialStats!![index]}"
                            }.joinToString(" · ")}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } }
                item {
                    HorizontalDivider()
                    Text("选择战役线", style = MaterialTheme.typography.titleSmall)
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val columns = if (maxWidth >= 520.dp) 4 else 2
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            BuddyCampaignLine.entries.chunked(columns).forEach { rowLines ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    rowLines.forEach { line ->
                                        FilterChip(selected = importLineId == line.id, onClick = { importLineId = line.id },
                                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                            label = { Text(line.label, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                                    }
                                    repeat(columns - rowLines.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                    if (duplicateImportedRouteJournal != null) {
                        Text("这条来源分支已加入「${duplicateImportedRouteJournal.second.name}」，可直接打开已有记录。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    } else {
                        OutlinedTextField(importName, { importName = it.take(100) },
                            label = { Text("新记录册名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = {
            if (duplicateImportedRouteJournal != null) {
                TextButton(enabled = !busy, onClick = {
                    val existingId = duplicateImportedRouteJournal.first.entityId
                    dismissImportPreview()
                    selectedId = existingId
                }) { Text("打开已导入路线") }
            } else TextButton(enabled = !busy && chaptersEntry?.ready == true && chaptersRoute?.ready == true &&
                importLineId != null && importName.isNotBlank(), onClick = {
                val entry = chaptersEntry ?: return@TextButton
                val route = chaptersRoute ?: return@TextButton
                val line = BuddyCampaignLine.entries.find { it.id == importLineId } ?: return@TextButton
                val journalToSave = runCatching {
                    chaptersPreview.toJournal(entry.index, route.index, importName.trim(), profile, line, loadedRules)
                }.getOrElse { failure -> error = failure.message ?: "这条路线无法加入记录册。"; return@TextButton }
                val entityId = UUID.randomUUID().toString()
                busy = true
                error = null
                scope.launch {
                    try {
                        snapshot = withContext(Dispatchers.IO) {
                            store.importGrowthSeedJournal(entityId, journalToSave.encode(), chaptersPreview.sourceArchive)
                        }
                        dismissImportPreview()
                        selectedId = entityId
                        importOutcomeJournalId = entityId
                        importOutcomeMessage = "已导入路线。种子作为你确认的来源起点保存；后续步骤须逐步录入实际结果才会计入实测。"
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = failure.message ?: "导入失败，现有记录未变更。" }
                    finally { busy = false }
                }
            }) { Text(if (busy) "正在保存…" else "创建路线记录册") }
        },
        dismissButton = { TextButton(onClick = ::dismissImportPreview, enabled = !busy) { Text("取消") } })
    if (examEditorId != null && journal != null && loadedRules != null) {
        val existingExam = journal.observations.find { it.id == examEditorId }
        val examPart3 = existingExam?.part3 ?: journal.currentPart3 ?: false
        val examOptions = loadedRules.professions.filter { loadedRules.classAllowed(journal.characterId, it.name, examPart3) }
        val originalExam = existingExam?.exam
        val targetValid = examTarget.isNotBlank() || originalExam?.targetProfession == ""
        val probabilityValid = examProbability.toDoubleOrNull()?.let { it in 0.0..100.0 } == true ||
            (originalExam != null && originalExam.probability == null && examProbability.isBlank())
        val costValid = examResourceCost.toIntOrNull()?.let { it in 0..10_000 } == true ||
            (originalExam != null && originalExam.resourceCost == null && examResourceCost.isBlank())
        AlertDialog(onDismissRequest = { if (!busy) examEditorId = null },
            title = { Text(if (existingExam == null) "记录已发生的考试" else "纠正考试记录") },
            text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("请按游戏里实际显示的成功率和结果填写。考试成功或失败都会推进一次种子；通过后目标职业会记为已解锁。")
                if (originalExam != null && (originalExam.probability == null || originalExam.resourceCost == null || originalExam.targetProfession.isBlank())) {
                    Text("来源未记载的资料可以留空；不知道成功率时，考试成败不用于筛选种子。", style = MaterialTheme.typography.bodySmall)
                }
                IllustratedChoice("考试目标职业", examTarget, examTarget.ifBlank { "未记载职业" },
                    examOptions.map { it.name to it.name }, "professions", visuals) { examTarget = it }
                OutlinedTextField(examProbability, { value -> if (value.matches(Regex("[0-9.]*"))) examProbability = value },
                    label = { Text("游戏显示的成功率（0–100）") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (examPassed) "考试通过" else "考试未通过", Modifier.weight(1f))
                    Switch(examPassed, { examPassed = it },
                        modifier = Modifier.semantics {
                            contentDescription = "考试结果"
                            stateDescription = if (examPassed) "考试通过" else "考试未通过"
                        })
                }
                OutlinedTextField(examResourceCost, { value -> if (value.all(Char::isDigit)) examResourceCost = value.take(5) },
                    label = { Text("实际消耗资源") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            } },
            confirmButton = { TextButton(enabled = !busy && targetValid && probabilityValid && costValid,
                onClick = {
                    val probability = examProbability.toDoubleOrNull()
                    val cost = examResourceCost.toIntOrNull()
                    if (examTarget.isNotBlank() && !loadedRules.classAllowed(journal.characterId, examTarget, examPart3)) {
                        error = "考试目标不适用于这条记录的篇章"
                        return@TextButton
                    }
                    val hadCurrentSeed = journal.hasCurrentSeedCheckpoint()
                    val updated = if (existingExam == null) runCatching {
                        journal.recordExam(examTarget, requireNotNull(probability), examPassed, requireNotNull(cost), loadedRules)
                    }.getOrElse { error = it.message ?: "无法保存考试结果"; return@TextButton }
                    else journal.copy(observations = journal.observations.map { row ->
                        if (row.id == existingExam.id) row.copy(exam = JournalExam(examTarget, probability, examPassed, cost)) else row
                    })
                    val seedMatches = updated.hasCurrentSeedCheckpoint()
                    save(requireNotNull(selectedId), updated) {
                        examEditorId = null
                        if (hadCurrentSeed && !seedMatches) error = "考试结果与当前种子推演不符；实测记录已保留，请重新反推。"
                    }
                }) { Text(if (busy) "正在保存…" else "保存考试结果") } },
            dismissButton = { TextButton(onClick = { examEditorId = null }, enabled = !busy) { Text("取消") } })
    }
    if (professionChangeEditor && journal != null && loadedRules != null) {
        val editingChange = professionChangeEditId?.let { id -> journal.observations.find { it.id == id } }
        val changePart3 = editingChange?.part3 ?: journal.currentPart3 ?: false
        val unlockedChoices = journal.confirmedUnlockedProfessions().filter { name ->
            name != journal.currentProfession && loadedRules.classAllowed(journal.characterId, name, changePart3)
        }.sorted()
        AlertDialog(onDismissRequest = { if (!busy) professionChangeEditor = false },
            title = { Text(if (editingChange == null) "记录已解锁职业切换" else "纠正职业切换") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("这里只能选择已确认解锁的职业。职业切换不升级，也不会额外推进种子。")
                if (unlockedChoices.isEmpty()) Text("当前没有其他已解锁且适用于本篇章的职业。")
                else IllustratedChoice("切换到", changeTarget, changeTarget,
                    (unlockedChoices + changeTarget.takeIf { it !in unlockedChoices }.orEmpty()).filter(String::isNotBlank).distinct().map { it to it },
                    "professions", visuals) { changeTarget = it }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { TextButton(enabled = !busy && changeTarget.isNotBlank(), onClick = {
                val updated = if (editingChange != null) {
                    if (changeTarget !in journal.confirmedUnlockedProfessions()) {
                        error = "目标职业尚未确认解锁"; return@TextButton
                    }
                    if (!loadedRules.classAllowed(journal.characterId, changeTarget, editingChange.part3)) {
                        error = "目标职业不适用于这条记录的篇章"; return@TextButton
                    }
                    journal.copy(observations = journal.observations.map { row ->
                        if (row.id == editingChange.id) row.copy(profession = changeTarget) else row
                    })
                } else runCatching { journal.recordProfessionChange(changeTarget, loadedRules) }
                    .getOrElse { error = it.message ?: "无法保存职业切换"; return@TextButton }
                save(requireNotNull(selectedId), updated) { professionChangeEditor = false; professionChangeEditId = null }
            }) { Text(if (busy) "正在保存…" else "保存职业切换") } },
            dismissButton = { TextButton(onClick = { professionChangeEditor = false; professionChangeEditId = null }, enabled = !busy) { Text("取消") } })
    }
    if (unlockEditor && journal != null && loadedRules != null) {
        val activePart3 = journal.currentPart3 ?: currentCheckpoint?.part3 ?: false
        val unlockOptions = loadedRules.professions.filter { loadedRules.classAllowed(journal.characterId, it.name, activePart3) }
        val fixedUnlocked = buildSet {
            journal.observations.forEach { row ->
                add(row.profession)
                row.professionChangeFrom?.let(::add)
                row.exam?.takeIf { it.passed }?.targetProfession?.let(::add)
            }
            currentCheckpoint?.profession?.let(::add)
        }
        val selectedUnlocked = unlockedEditText.lineSequence().map(String::trim).filter(String::isNotEmpty).toSet()
        var unlockQuery by remember { mutableStateOf("") }
        var unlockTier by remember { mutableStateOf<Int?>(null) }
        AlertDialog(onDismissRequest = { if (!busy) unlockEditor = false },
            title = { Text("确认已解锁职业") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("只勾选游戏里已解锁的职业；按阶位筛选可快速定位。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(unlockQuery, { unlockQuery = it }, label = { Text("搜索职业") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                BuddyProfessionTierFilterBar(
                    availableTiers = unlockOptions.map { it.tier },
                    selectedTier = unlockTier,
                    onSelectTier = { unlockTier = it },
                )
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    unlockOptions.filter {
                        (unlockTier == null || it.tier == unlockTier) &&
                            it.name.contains(unlockQuery.trim(), ignoreCase = true)
                    }.forEach { profession ->
                        val checked = profession.name in selectedUnlocked || profession.name in fixedUnlocked
                        val minLv = BuddyRules.defaultUnlockLevelForTier(profession.tier)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Checkbox(checked, onCheckedChange = { value ->
                                val values = selectedUnlocked.toMutableSet()
                                if (value) values += profession.name else if (profession.name !in fixedUnlocked) values -= profession.name
                                unlockedEditText = values.sorted().joinToString("\n")
                            }, enabled = profession.name !in fixedUnlocked,
                                modifier = Modifier.semantics { contentDescription = profession.name })
                            WikiImage(visuals.image("professions", profession.name), "${profession.name}图标", Modifier.size(28.dp), crop = false)
                            Column(Modifier.weight(1f)) {
                                Text(profession.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    if (minLv > 1) "${BuddyRules.tierLabel(profession.tier)} · Lv.$minLv+" else BuddyRules.tierLabel(profession.tier),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            } },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                val unlocked = (selectedUnlocked + fixedUnlocked).filter { it in unlockOptions.map { option -> option.name } || it in fixedUnlocked }.toSet()
                val updated = runCatching { journal.withConfirmedUnlockedProfessions(unlocked).copy(growthPlan = null) }
                    .getOrElse { failure -> error = failure.message ?: "无法保存已解锁职业"; return@TextButton }
                save(requireNotNull(selectedId), updated) { unlockEditor = false }
            }) { Text(if (busy) "正在保存…" else "保存解锁状态") } },
            dismissButton = { TextButton(onClick = { unlockEditor = false }, enabled = !busy) { Text("取消") } })
    }
    if (deleteId != null && journal != null) AlertDialog(onDismissRequest = { if (!busy) deleteId = null },
        title = { Text("删除这次记录？") }, text = { Text("删除中间的升级可能造成等级断档；反推前需要核对后续记录。") },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            runCatching { journal.removeObservations(setOf(requireNotNull(deleteId))) }.onSuccess { updated ->
                save(requireNotNull(selectedId), updated) { deleteId = null }
            }.onFailure { failure -> error = failure.message ?: "无法删除记录"; deleteId = null }
        }) { Text("删除") } }, dismissButton = { TextButton(onClick = { deleteId = null }, enabled = !busy) { Text("取消") } })
    if (bulkDelete && journal != null) AlertDialog(onDismissRequest = { if (!busy) bulkDelete = false },
        title = { Text("批量删除记录？") },
        text = { Text("将删除已选 ${selectedObservationIds.size} 条记录。等级断档或记录顺序变化后，原有种子和培养方案会失效；确认后可恢复上个版本。") },
        confirmButton = { TextButton(enabled = !busy && selectedObservationIds.isNotEmpty(), onClick = {
            runCatching { journal.removeObservations(selectedObservationIds) }.onSuccess { updated ->
                save(requireNotNull(selectedId), updated) {
                    bulkDelete = false
                    selectingObservations = false
                    selectedObservationIds = emptySet()
                }
            }.onFailure { failure -> error = failure.message ?: "无法批量删除记录"; bulkDelete = false }
        }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { bulkDelete = false }, enabled = !busy) { Text("取消") } })
    if (restorePrevious && journal != null && previousJournal != null) AlertDialog(
        onDismissRequest = { if (!busy) restorePrevious = false },
        title = { Text("恢复上个版本？") },
        text = { Text("将以新修订恢复上一版记录册内容，并保留当前版本在历史中。该操作会随个人备份和 WebDAV 同步；种子证据需按恢复后的记录重新核对。") },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            save(requireNotNull(selectedId), previousJournal) {
                restorePrevious = false
                selectingObservations = false
                selectedObservationIds = emptySet()
            }
        }) { Text("恢复") } },
        dismissButton = { TextButton(onClick = { restorePrevious = false }, enabled = !busy) { Text("取消") } })
    val importedExamAction = activeImportedGrowthPlan?.takeIf { importedExamStepIndex >= 0 }
        ?.actions?.getOrNull(importedExamStepIndex)
        ?.takeIf { it.kind == ImportedGrowthActionKind.EXAM }
    if (importedExamStepIndex >= 0 && journal != null && loadedRules != null && importedExamAction != null) {
        AlertDialog(
            onDismissRequest = { if (!busy) importedExamStepIndex = -1 },
            title = { Text("确认实际考试结果") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("目标职业：${importedExamAction.profession} · 当前等级：${activeImportedGrowthPlan?.currentLevel()}")
                Text("请选择游戏里实际显示的结果。无论通过或失败都会推进一次种子，不增加等级；来源未提供的成功率和消耗继续留空。",
                    style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = importedExamPassed, onClick = { importedExamPassed = true },
                        label = { Text("通过") }, modifier = Modifier.heightIn(min = 48.dp))
                    FilterChip(selected = !importedExamPassed, onClick = { importedExamPassed = false },
                        label = { Text("未通过") }, modifier = Modifier.heightIn(min = 48.dp))
                }
            } },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                val updated = runCatching { journal.confirmImportedGrowthExam(importedExamPassed, loadedRules) }
                    .getOrElse { failure -> error = failure.message ?: "无法确认考试结果"; return@TextButton }
                val seedKnown = updated.seedCheckpoint?.let { updated.hasCurrentSeedCheckpoint() } == true
                val entityId = requireNotNull(selectedId)
                save(entityId, updated) {
                    importedExamStepIndex = -1
                    importOutcomeJournalId = entityId
                    importOutcomeMessage = if (seedKnown) "考试实测已确认，等级不变；种子和路线进度已推进。"
                        else "考试实测已确认，等级不变；路线已推进，种子状态仍未知。"
                }
            }) { Text(if (busy) "正在保存…" else "保存考试结果") } },
            dismissButton = { TextButton(onClick = { importedExamStepIndex = -1 }, enabled = !busy) { Text("取消") } },
        )
    }
    if (undoImportedStep && journal != null && activeImportedGrowthPlan != null) AlertDialog(
        onDismissRequest = { if (!busy) undoImportedStep = false },
        title = { Text("撤销最近一次路线确认？") },
        text = { Text("将从当前记录中移除最近确认的那一步，并追加一条新修订恢复路线游标和种子状态。历史版本仍会保留并随备份同步。") },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            val updated = runCatching { journal.undoLatestImportedGrowthAction() }
                .getOrElse { failure -> error = failure.message ?: "无法撤销路线步骤"; return@TextButton }
            val entityId = requireNotNull(selectedId)
            save(entityId, updated) {
                undoImportedStep = false
                importOutcomeJournalId = entityId
                importOutcomeMessage = "最近一次路线确认已撤销，历史修订仍已保留。"
            }
        }) { Text(if (busy) "正在保存…" else "撤销这一步") } },
        dismissButton = { TextButton(onClick = { undoImportedStep = false }, enabled = !busy) { Text("取消") } })
}

private fun readBoundedGrowthSeedJson(input: InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (output.size() > FefwGrowthSeedImporter.MAX_BYTES - count) {
            throw IOException("文件超过 10 MiB 上限，请选择较小的成长记录文件。")
        }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun growthSeedExportFileName(name: String, plannedRoute: Boolean = false): String {
    val safeName = name.map { character -> if (character.isLetterOrDigit() || character in "-_ ") character else '_' }
        .joinToString("").trim().take(80).ifBlank { "growth-seed" }
    return "$safeName-${if (plannedRoute) "growth-chapters" else "growth-seed"}.json"
}

internal fun resolveObservationRoom(capped: Boolean, skipped: Boolean, roomValues: List<String>): List<Int> =
    if (capped && !skipped) {
        require(roomValues.size == GrowthStat.entries.size) { "请填写全部属性的剩余空间" }
        roomValues.map { requireNotNull(it.toIntOrNull()) { "请填写全部属性的剩余空间" } }
    } else {
        List(GrowthStat.entries.size) { 32767 }
    }

@Composable
private fun LevelObservationEditor(journal: LevelJournal, initial: LevelObservation, rules: BuddyRules, visuals: BuddyVisuals,
    busy: Boolean, saveError: String?, planned: Boolean = false, importedRouteStep: Boolean = false,
    onBack: () -> Unit, onSave: (LevelObservation) -> Unit,
) {
    var level by rememberSaveable { mutableStateOf(initial.level.toString()) }
    var profession by rememberSaveable { mutableStateOf(initial.profession) }
    var part3 by rememberSaveable { mutableStateOf(initial.part3) }
    var rideId by rememberSaveable { mutableStateOf(initial.ride?.id.orEmpty()) }
    var friendship by rememberSaveable { mutableStateOf((initial.ride?.level ?: 1).toString()) }
    var skipped by rememberSaveable { mutableStateOf(initial.skipped) }
    var gains by rememberSaveable { mutableStateOf(initial.gains.joinToString(",")) }
    var room by rememberSaveable { mutableStateOf(initial.room.joinToString(",")) }
    var capped by rememberSaveable { mutableStateOf(if (planned) {
        journal.growthPlan?.request?.caps?.any { it != null } == true
    } else initial.room.any { it < 32767 }) }
    val gainValues = gains.split(",").map(String::toInt)
    val roomValues = room.split(",")
    val input = BuddyGrowthInput(journal.characterId, profession, level.toIntOrNull() ?: 1, part3, journal.line.rulesRoute)
    val rides = runCatching { rules.availableRides(input) }.getOrDefault(emptyList())
    val observation = runCatching {
        LevelObservation(initial.id, requireNotNull(level.toIntOrNull()) { "请填写升级前等级" }, profession, part3,
            rideId.takeIf { it.isNotEmpty() }?.let { BuddyRideSelection(it, friendship.toInt()) }, gainValues,
            resolveObservationRoom(capped, skipped, roomValues), skipped, growthLevel = initial.growthLevel)
    }
    val validation = observation.fold(onSuccess = { row -> runCatching {
        val rowsToValidate = if (row.growthLevel != null) {
            journal.observations.map { existing -> if (existing.id == row.id) row else existing }
        } else listOf(row)
        journal.copy(observations = rowsToValidate).history(rules).errors.firstOrNull()
    }.getOrElse { it.message ?: "记录无效" } }, onFailure = { it.message ?: "请检查输入" })
    LazyColumn(modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 880.dp),
        state = rememberReadingListState("observation:${journal.profile}:${journal.line.id}:${journal.characterId}:${initial.id}"),
        contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            TextButton(onClick = onBack, enabled = !busy) { Text("取消，返回记录册") }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                val character = rules.characters.first { it.id == journal.characterId }
                val displayCharacterName = characterLabel(character.id, character.name)
                BuddyPortrait(journal.characterId, displayCharacterName, visuals, part3 = part3)
                Column {
                    Text(displayCharacterName, style = MaterialTheme.typography.headlineSmall)
                    Text("${journal.line.label} · ${journal.name}", style = MaterialTheme.typography.bodySmall)
                    Text(when {
                        importedRouteStep -> "录入这一步实际升级成长"
                        planned -> "核对计划这步的实测结果"
                        else -> "记录实际成长"
                    },
                        color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                }
            }
            Spacer(Modifier.height(16.dp))
            if (planned || importedRouteStep) Text("${level} 级升级 · ${profession}${rideId.takeIf(String::isNotEmpty)?.let { " · ${rides.find { ride -> ride.id == it }?.name ?: it}" }.orEmpty()}")
            else OutlinedTextField(level, { value -> if (value.all(Char::isDigit)) level = value.take(2) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                label = { Text("升级前等级（1–98）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("第三部", Modifier.weight(1f))
                Switch(part3, { part3 = it }, enabled = !rules.characters.first { it.id == journal.characterId }.part3Only,
                    modifier = Modifier.semantics { contentDescription = "第三部" })
            }
            if (planned) Text("请确认这次实际升级使用的职业、篇章和坐骑。若与方案不同，仍会保留实测记录并要求重新反推。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (importedRouteStep) Text("按游戏里的实际结果填写九项成长；只有与本地规则相符时才继续核对种子。与路线职业不同的实测也会保留。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) {
                    val professions = rules.professions.filter {
                        rules.classAllowed(journal.characterId, it.name, part3) &&
                            (!planned && !importedRouteStep || it.name in journal.confirmedUnlockedProfessions())
                    }
                    IllustratedChoice(if (planned || importedRouteStep) "实际职业" else "职业", profession, profession,
                        professions.map { it.name to it.name }, "professions", visuals) { profession = it }
                }
                Box(Modifier.weight(1f)) {
                    IllustratedChoice(if (planned) "实际坐骑" else "坐骑", rideId,
                        if (rideId.isEmpty()) "不骑乘" else rides.find { it.id == rideId }?.name ?: "请重选",
                        listOf("" to "不骑乘") + rides.map { it.id to it.name }, "mounts", visuals) { rideId = it }
                }
            }
            if (rideId.isNotEmpty()) ChoiceField("友好等级", friendship, (1..5).map { it.toString() to it.toString() }) { friendship = it }
            if (!planned) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("本次跳过（不升级，只推进种子）", Modifier.weight(1f))
                Switch(skipped, { skipped = it },
                    modifier = Modifier.semantics { contentDescription = "本次跳过（不升级，只推进种子）" })
            }
            if (!skipped) {
                Text("本次提升", style = MaterialTheme.typography.titleMedium)
                Text(if (planned) "显示的是预测值，请按游戏实际结果点按修正后确认。" else "点按切换提升值（0–9）；正常升级通常为 0、+1 或 +2。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!skipped) item {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val columns = if (maxWidth >= 600.dp) 6 else 3
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GrowthStat.entries.chunked(columns).forEach { stats ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            stats.forEach { stat ->
                                val index = stat.ordinal
                                val gain = gainValues[index]
                                Card(onClick = { gains = gainValues.toMutableList().also { it[index] = (it[index] + 1) % 10 }.joinToString(",") },
                                    modifier = Modifier.weight(1f).heightIn(min = 88.dp), shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(containerColor = if (gain > 0) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)) {
                                    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        Icon(when (stat) {
                                            GrowthStat.HP -> Icons.Outlined.FavoriteBorder
                                            GrowthStat.STRENGTH -> Icons.Outlined.FitnessCenter
                                            GrowthStat.MAGIC -> Icons.Outlined.AutoAwesome
                                            GrowthStat.DEXTERITY -> Icons.Outlined.MyLocation
                                            GrowthStat.SPEED -> Icons.Outlined.Bolt
                                            GrowthStat.LUCK -> Icons.Outlined.Casino
                                            GrowthStat.DEFENSE -> Icons.Outlined.Shield
                                            GrowthStat.RESISTANCE -> Icons.Outlined.VerifiedUser
                                            GrowthStat.CHARM -> Icons.Outlined.StarBorder
                                        }, contentDescription = null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                                        Text("${stat.label} +$gain", style = MaterialTheme.typography.titleSmall,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            repeat(columns - stats.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
        if (!skipped) item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("有属性接近或达到上限", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(capped, { capped = it },
                    modifier = Modifier.semantics { contentDescription = "有属性接近或达到上限" })
            }
            if (capped) Text("剩余空间 = 上限 − 升级前数值；已满填 0，其余保持 32767。", style = MaterialTheme.typography.bodySmall)
        }
        if (!skipped && capped) itemsIndexed(GrowthStat.entries.toList()) { index, stat ->
                OutlinedTextField(roomValues[index], { value ->
                    if (value.all(Char::isDigit)) room = roomValues.toMutableList().also { it[index] = value.take(5) }.joinToString(",")
                }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text("${stat.label}剩余空间") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        }
        item {
            validation?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = { onSave(observation.getOrThrow()) }, enabled = !busy && validation == null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text(when {
                    busy -> "正在保存…"
                    planned -> "确认实测并更新方案"
                    importedRouteStep -> "保存实际成长并继续路线"
                    else -> "保存实测记录"
                })
            }
        }
    }
}

@Composable
private fun JournalActionCard(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 104.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
