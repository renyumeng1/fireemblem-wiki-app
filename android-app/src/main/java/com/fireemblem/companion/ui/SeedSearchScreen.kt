package com.fireemblem.companion.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fireemblem.companion.engine.BuddyCampaignLine
import com.fireemblem.companion.engine.BuddyGrowthInput
import com.fireemblem.companion.engine.BuddyRideSelection
import com.fireemblem.companion.engine.BuddyRules
import com.fireemblem.companion.engine.BuddySeedHistory
import com.fireemblem.companion.engine.GrowthStat
import com.fireemblem.companion.engine.LevelJournal
import com.fireemblem.companion.engine.QuickManualObservation
import com.fireemblem.companion.engine.SeedEngine
import com.fireemblem.companion.engine.SeedSearchCandidate
import com.fireemblem.companion.engine.SeedSearchProgress
import com.fireemblem.companion.engine.SeedSearchResult
import com.fireemblem.companion.engine.SeedSearchStatus
import com.fireemblem.companion.engine.SeedWorkbenchEngine
import com.fireemblem.companion.importer.AtomicBuddyArchiveStore
import com.fireemblem.companion.importer.BuddyArchiveSummary
import com.fireemblem.companion.personal.PersonalStore
import com.fireemblem.companion.ui.theme.tabularNumbers
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SeedSearchScreen(
    archiveStore: AtomicBuddyArchiveStore,
    archives: List<BuddyArchiveSummary>,
    onBack: () -> Unit,
    onChooseBackup: () -> Unit,
    recordedHistory: BuddySeedHistory? = null,
    recordLabel: String? = null,
    onOpenRecords: (() -> Unit)? = null,
    onSelectCandidate: ((SeedSearchCandidate, SeedSearchResult) -> Unit)? = null,
    onPreviewCandidate: ((SeedWorkbenchLaunchContext) -> Unit)? = null,
    savingSelection: Boolean = false,
    saveError: String? = null,
    readingPositionKey: String = "seed-search",
) {
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()
    var rules by remember { mutableStateOf<BuddyRules?>(null) }
    var rulesError by remember { mutableStateOf<String?>(null) }

    // Source mode when not launched from a specific LevelJournal: "manual" | "journal" | "backup"
    var sourceMode by rememberSaveable { mutableStateOf(if (archives.isNotEmpty()) "backup" else "manual") }
    var archiveId by rememberSaveable { mutableStateOf<String?>(null) }
    var characterId by rememberSaveable { mutableStateOf("c044") }
    var archivePickerOpen by rememberSaveable { mutableStateOf(false) }
    var characterPickerOpen by rememberSaveable { mutableStateOf(false) }
    var characterQuery by rememberSaveable { mutableStateOf("") }

    // Local LevelJournal list for direct selection inside SeedSearchScreen
    var localJournals by remember { mutableStateOf<List<Pair<String, LevelJournal>>>(emptyList()) }
    var selectedJournalEntityId by rememberSaveable { mutableStateOf<String?>(null) }

    // Quick manual observation entry state (works out-of-the-box with zero Buddy JSON!)
    var manualLineId by rememberSaveable { mutableStateOf(BuddyCampaignLine.KAI.id) }
    var manualPart3 by rememberSaveable { mutableStateOf(false) }
    var manualStartLevelText by rememberSaveable { mutableStateOf("1") }
    var manualProfession by rememberSaveable { mutableStateOf("平民") }
    var manualRideId by rememberSaveable { mutableStateOf("") }
    var manualRideLevel by rememberSaveable { mutableStateOf("1") }
    var manualRows by remember {
        mutableStateOf(
            List(4) { idx ->
                QuickManualObservation(level = idx + 1, profession = "平民", gains = List(9) { 0 })
            },
        )
    }

    var history by remember { mutableStateOf<BuddySeedHistory?>(null) }
    var historyError by remember { mutableStateOf<String?>(null) }
    var beginText by rememberSaveable { mutableStateOf("0") }
    var endText by rememberSaveable { mutableStateOf(SeedEngine.UINT32_LIMIT.toString()) }
    var searchResult by remember { mutableStateOf<SeedSearchResult?>(null) }
    var searchBackend by remember { mutableStateOf<SeedSearchBackend?>(null) }
    var searchElapsedMillis by remember { mutableLongStateOf(0L) }
    var gpuReadyHint by remember { mutableStateOf<Boolean?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var cancellation by remember { mutableStateOf<AtomicBoolean?>(null) }
    var searchStartedAtMillis by remember { mutableLongStateOf(0L) }
    val progressUpdates = remember { MutableStateFlow<SeedSearchProgress?>(null) }
    val progress by progressUpdates.collectAsState()
    val scope = rememberCoroutineScope()
    val visuals = rememberBuddyVisuals()
    var showAlgorithmDetails by rememberSaveable { mutableStateOf(false) }

    fun handleBack() {
        if (isRunning) cancellation?.set(true)
        else if (!savingSelection) onBack()
    }
    BackHandler(enabled = isRunning || !savingSelection, onBack = ::handleBack)
    DisposableEffect(Unit) {
        onDispose { cancellation?.set(true) }
    }
    LaunchedEffect(archives) {
        if (archiveId !in archives.map(BuddyArchiveSummary::archiveId)) archiveId = archives.firstOrNull()?.archiveId
        if (archives.isNotEmpty() && sourceMode == "backup" && archiveId == null) {
            archiveId = archives.firstOrNull()?.archiveId
        }
    }
    LaunchedEffect(Unit) {
        gpuReadyHint = withContext(Dispatchers.Default) { GpuSeedSearchAccelerator.isGpuAvailableHint() }
    }
    LaunchedEffect(Unit) {
        try {
            rules = withContext(Dispatchers.IO) { BuddyRules.bundled() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            rulesError = "成长规则未能读取或校验未通过，请重试或重新安装应用。"
        }
    }
    LaunchedEffect(recordedHistory) {
        if (recordedHistory != null) return@LaunchedEffect
        localJournals = withContext(Dispatchers.IO) {
            runCatching {
                val profile = context.getSharedPreferences("personal-view", 0)
                    .getString("profile", "global") ?: "global"
                val store = PersonalStore(context.applicationContext)
                store.snapshot().heads().values
                    .mapNotNull { it.singleOrNull()?.takeIf { rev -> !rev.deleted && rev.kind == LevelJournal.KIND } }
                    .mapNotNull { rev -> runCatching { rev.entityId to LevelJournal.decode(rev.payload) }.getOrNull() }
                    .filter { it.second.profile == profile }
                    .sortedByDescending { it.second.observations.size }
            }.getOrDefault(emptyList())
        }
        if (selectedJournalEntityId == null || localJournals.none { it.first == selectedJournalEntityId }) {
            selectedJournalEntityId = localJournals.firstOrNull()?.first
        }
    }

    val activeRules = rules
    LaunchedEffect(activeRules) {
        if (activeRules != null && activeRules.characters.none { it.id == characterId }) {
            characterId = activeRules.characters.firstOrNull()?.id.orEmpty()
        }
    }

    // Keep manual row levels/profession/ride synchronized when base manual settings change
    fun syncManualLevels(rows: List<QuickManualObservation>, startLevel: Int, prof: String, ride: BuddyRideSelection?): List<QuickManualObservation> {
        var curLv = startLevel.coerceIn(1, 98)
        return rows.map { row ->
            val updated = row.copy(level = curLv.coerceIn(1, 98), profession = prof, ride = ride)
            if (!row.skipped) curLv = (curLv + 1).coerceAtMost(98)
            updated
        }
    }

    val manualLine = BuddyCampaignLine.entries.firstOrNull { it.id == manualLineId } ?: BuddyCampaignLine.KAI
    val manualChar = activeRules?.characters?.firstOrNull { it.id == characterId }
    val manualEffectivePart3 = manualPart3 || manualChar?.part3Only == true
    val manualRideSelection = manualRideId.takeIf { it.isNotEmpty() }?.let {
        BuddyRideSelection(it, (manualRideLevel.toIntOrNull() ?: 1).coerceIn(1, 5))
    }

    LaunchedEffect(
        archiveId, characterId, activeRules, recordedHistory, sourceMode,
        selectedJournalEntityId, localJournals, manualLineId, manualEffectivePart3, manualRows,
    ) {
        history = null
        historyError = null
        searchResult = null
        if (recordedHistory != null) {
            history = recordedHistory
            return@LaunchedEffect
        }
        if (activeRules == null) return@LaunchedEffect
        when (sourceMode) {
            "manual" -> {
                history = SeedWorkbenchEngine.manualObservationsToHistory(
                    rules = activeRules,
                    characterId = characterId,
                    line = manualLine,
                    part3 = manualEffectivePart3,
                    observations = manualRows,
                )
            }
            "journal" -> {
                val chosen = localJournals.firstOrNull { it.first == selectedJournalEntityId }?.second
                if (chosen != null) {
                    history = withContext(Dispatchers.Default) { chosen.history(activeRules) }
                }
            }
            else -> {
                if (archiveId == null) return@LaunchedEffect
                try {
                    val payload = withContext(Dispatchers.IO) { archiveStore.readPayload(requireNotNull(archiveId)) }
                    history = withContext(Dispatchers.Default) { BuddySeedHistory.fromBackup(payload, characterId, activeRules) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    historyError = "无法读取所选存档，请重新导入备份。"
                }
            }
        }
    }

    LaunchedEffect(beginText, endText) { searchResult = null }
    val begin = beginText.toLongOrNull()
    val end = endText.toLongOrNull()
    val validRange = begin != null && end != null && begin in 0..SeedEngine.UINT32_LIMIT &&
        end in begin..SeedEngine.UINT32_LIMIT && end > begin

    fun buildPreviewContext(candidate: SeedSearchCandidate): SeedWorkbenchLaunchContext? {
        val loadedHistory = history ?: return null
        val loadedRules = activeRules ?: return null
        val endState = loadedHistory.endState
        val resolvedCharId = loadedHistory.characterId
        val resolvedPart3 = endState?.part3 ?: loadedRules.characters.firstOrNull { it.id == resolvedCharId }?.part3Only ?: false
        val resolvedProf = endState?.profession ?: loadedRules.professions.firstOrNull {
            loadedRules.classAllowed(resolvedCharId, it.name, resolvedPart3)
        }?.name ?: "平民"
        val resolvedLevel = (endState?.level ?: 1).coerceIn(1, 98)
        val resolvedLine = endState?.line ?: manualLine
        val resolvedUnlocked = endState?.unlockedProfessions ?: setOf(resolvedProf)
        val sourceHint = when {
            recordLabel != null -> "来自反推种子 · $recordLabel"
            sourceMode == "manual" -> "来自手动录入反推（${resolvedLine.label}）"
            sourceMode == "journal" -> {
                val jName = localJournals.firstOrNull { it.first == selectedJournalEntityId }?.second?.name
                if (jName != null) "来自反推种子 · $jName" else "来自升级记录反推"
            }
            else -> "来自 Buddy 备份反推"
        }
        return SeedWorkbenchLaunchContext(
            characterId = resolvedCharId,
            campaignLine = resolvedLine,
            part3 = resolvedPart3,
            seed = candidate.currentSeed,
            inferredLevel = resolvedLevel,
            profession = resolvedProf,
            ride = endState?.ride,
            unlockedProfessions = resolvedUnlocked + resolvedProf,
            sourceLabel = sourceHint,
            backLabel = "返回反推结果",
            autoRun = true,
        )
    }

    fun startSearch() {
        val selectedHistory = history?.takeIf(BuddySeedHistory::ready) ?: return
        val rangeStart = begin ?: return
        val rangeEnd = end ?: return
        val cancelled = AtomicBoolean(false)
        val lastPublishedAt = AtomicLong(0L)
        cancellation = cancelled
        progressUpdates.value = SeedSearchProgress(0L, rangeEnd - rangeStart)
        searchResult = null
        searchBackend = null
        searchElapsedMillis = 0L
        searchError = null
        searchStartedAtMillis = SystemClock.elapsedRealtime()
        isRunning = true
        scope.launch {
            try {
                val outcome = withContext(Dispatchers.Default) {
                    GpuSeedSearchAccelerator.search(
                        events = selectedHistory.events,
                        beginInclusive = rangeStart,
                        endExclusive = rangeEnd,
                        isCancelled = cancelled::get,
                        onProgress = { update ->
                            val now = SystemClock.elapsedRealtime()
                            val prev = lastPublishedAt.get()
                            if (update.processedSeeds >= update.totalSeeds ||
                                (now - prev >= 200L && lastPublishedAt.compareAndSet(prev, now))
                            ) {
                                progressUpdates.value = update
                            }
                        },
                    )
                }
                searchResult = outcome.result
                searchBackend = outcome.backend
                searchElapsedMillis = outcome.elapsedMillis
            } catch (cancelledException: CancellationException) {
                throw cancelledException
            } catch (_: Exception) {
                searchError = "搜索未能完成，记录和备份均未修改。"
            } finally {
                isRunning = false
                cancellation = null
            }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = 920.dp)
            .testTag("seed-search-screen"),
    ) {
        val expanded = maxWidth >= 640.dp
        LazyColumn(
            modifier = Modifier.fillMaxWidth().testTag("seed-search-list"),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
            state = rememberReadingListState("$readingPositionKey:$sourceMode:$archiveId:$characterId", rules != null),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                TextButton(
                    onClick = ::handleBack,
                    enabled = isRunning || !savingSelection,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(if (isRunning) "取消搜索" else if (recordedHistory == null) "返回工具" else "返回升级记录")
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScreenHeading(
                        title = "反推种子",
                        description = "录入实测升级高速定位种子，命中后一键进入 99 级乱数预览与全职业培养规划。",
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val gpuReady = gpuReadyHint
                        EditorialBadge(
                            text = when (gpuReady) {
                                true -> "⚡ GPU 计算着色器加速已就绪"
                                false -> "CPU 多核无除法加速"
                                null -> "正在检测 GPU 计算加速…"
                            },
                            containerColor = if (gpuReady == true) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = if (gpuReady == true) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        EditorialBadge(
                            text = "只读搜索 · 不修改原存档",
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            recordLabel?.let { label ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            val selectedCharacter = activeRules?.characters?.firstOrNull { it.id == recordedHistory?.characterId }
                            if (recordedHistory != null && selectedCharacter != null) {
                                BuddyPortrait(
                                    recordedHistory.characterId,
                                    characterLabel(selectedCharacter.id, selectedCharacter.name),
                                    visuals,
                                    Modifier.size(56.dp),
                                )
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    "当前升级记录",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                                Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                selectedCharacter?.let {
                                    Text(
                                        characterLabel(it.id, it.name),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Source switcher when standalone (recordedHistory == null)
            if (recordedHistory == null) {
                item(key = "search-source-tabs") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "manual" to "手动录入实测",
                            "journal" to "升级记录册（${localJournals.size}）",
                            "backup" to "Buddy 备份（${archives.size}）",
                        ).forEach { (key, label) ->
                            val sel = sourceMode == key
                            Surface(
                                onClick = { if (!isRunning) sourceMode = key },
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 46.dp)
                                    .semantics { selected = sel }
                                    .testTag("seed-search-source:$key"),
                                shape = RoundedCornerShape(14.dp),
                                color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                contentColor = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                border = BorderStroke(1.dp, if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                            ) {
                                Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                                    Text(
                                        label,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            onOpenRecords?.let { open ->
                item {
                    OutlinedButton(
                        onClick = open,
                        enabled = !isRunning && !savingSelection,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text(if (recordedHistory == null) "管理升级记录册" else "返回记录册")
                    }
                }
            }

            saveError?.let { message ->
                item { Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }

            // Source-specific input card (only when recordedHistory == null)
            if (recordedHistory == null) {
                when (sourceMode) {
                    "manual" -> {
                        if (activeRules != null) {
                            item(key = "manual-config") {
                                val selectedCharacter = activeRules.characters.firstOrNull { it.id == characterId }
                                val allowedProfs = activeRules.professions.filter {
                                    activeRules.classAllowed(characterId, it.name, manualEffectivePart3)
                                }
                                LaunchedEffect(characterId, manualEffectivePart3) {
                                    if (allowedProfs.isNotEmpty() && allowedProfs.none { it.name == manualProfession }) {
                                        val nextProf = allowedProfs.first().name
                                        manualProfession = nextProf
                                        manualRows = syncManualLevels(manualRows, manualStartLevelText.toIntOrNull() ?: 1, nextProf, manualRideSelection)
                                    }
                                }
                                EditorialCard(Modifier.fillMaxWidth().testTag("seed-search-manual-card")) {
                                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text("快速录入实测升级（无需导入 JSON）", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                                        OutlinedButton(
                                            onClick = { characterPickerOpen = true },
                                            enabled = !isRunning,
                                            modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp),
                                        ) {
                                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                selectedCharacter?.let {
                                                    BuddyPortrait(characterId, characterLabel(it.id, it.name), visuals, Modifier.size(42.dp))
                                                }
                                                Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                                                    Text("角色", style = MaterialTheme.typography.labelSmall)
                                                    Text(selectedCharacter?.let { characterLabel(it.id, it.name) } ?: "选择角色")
                                                }
                                                Text("更换", style = MaterialTheme.typography.labelMedium)
                                            }
                                        }
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Box(Modifier.weight(1f)) {
                                                ChoiceField(
                                                    "路线", manualLine.label,
                                                    BuddyCampaignLine.entries.map { it.id to it.label },
                                                ) { manualLineId = it }
                                            }
                                            OutlinedTextField(
                                                value = manualStartLevelText,
                                                onValueChange = { raw ->
                                                    val digits = raw.filter(Char::isDigit).take(2)
                                                    manualStartLevelText = digits
                                                    digits.toIntOrNull()?.takeIf { it in 1..98 }?.let { lv ->
                                                        manualRows = syncManualLevels(manualRows, lv, manualProfession, manualRideSelection)
                                                    }
                                                },
                                                label = { Text("起始等级") },
                                                singleLine = true,
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                modifier = Modifier.weight(0.6f),
                                            )
                                        }
                                        IllustratedChoice(
                                            "职业", manualProfession, manualProfession,
                                            allowedProfs.map { it.name to it.name }, "professions", visuals,
                                        ) { nextProf ->
                                            manualProfession = nextProf
                                            manualRows = syncManualLevels(manualRows, manualStartLevelText.toIntOrNull() ?: 1, nextProf, manualRideSelection)
                                        }
                                        val availableRides = activeRules.availableRides(
                                            BuddyGrowthInput(characterId, manualProfession, 1, manualEffectivePart3, manualLine.rulesRoute),
                                        )
                                        if (availableRides.isNotEmpty()) {
                                            val rideChoices = listOf("" to "不骑乘") + availableRides.map { it.id to it.name }
                                            val selectedRide = availableRides.firstOrNull { it.id == manualRideId }
                                            IllustratedChoice(
                                                "坐骑", manualRideId,
                                                selectedRide?.name ?: "不骑乘",
                                                rideChoices, "mounts", visuals,
                                            ) { nextRideId ->
                                                manualRideId = nextRideId
                                                val selRide = nextRideId.takeIf { it.isNotEmpty() }?.let {
                                                    BuddyRideSelection(it, (manualRideLevel.toIntOrNull() ?: 1).coerceIn(1, 5))
                                                }
                                                manualRows = syncManualLevels(manualRows, manualStartLevelText.toIntOrNull() ?: 1, manualProfession, selRide)
                                            }
                                            if (manualRideId.isNotEmpty()) {
                                                ChoiceField("友好等级", manualRideLevel, (1..5).map { it.toString() to "等级 $it" }) { lv ->
                                                    manualRideLevel = lv
                                                    val selRide = BuddyRideSelection(manualRideId, lv.toInt())
                                                    manualRows = syncManualLevels(manualRows, manualStartLevelText.toIntOrNull() ?: 1, manualProfession, selRide)
                                                }
                                            }
                                        }
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            Text("第三部规则", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                            Switch(
                                                checked = manualEffectivePart3,
                                                enabled = manualChar?.part3Only != true && !isRunning,
                                                onCheckedChange = { manualPart3 = it },
                                                colors = SwitchDefaults.colors(
                                                    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                                    uncheckedBorderColor = MaterialTheme.colorScheme.outline,
                                                ),
                                            )
                                        }
                                        Text(
                                            "点按下方每次升级的属性格可循环切换 0 / +1 / +2；录满 4 次实测升级即可反推：",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        manualRows.forEachIndexed { idx, row ->
                                            ManualObservationRowEditor(
                                                index = idx,
                                                row = row,
                                                darkTheme = darkTheme,
                                                canDelete = manualRows.size > 1,
                                                enabled = !isRunning,
                                                onCycleStat = { statIdx ->
                                                    val updated = manualRows.toMutableList()
                                                    val g = row.gains.toMutableList()
                                                    g[statIdx] = (g[statIdx] + 1) % 3
                                                    updated[idx] = row.copy(gains = g, skipped = false)
                                                    manualRows = syncManualLevels(updated, manualStartLevelText.toIntOrNull() ?: 1, manualProfession, manualRideSelection)
                                                },
                                                onToggleSkip = {
                                                    val updated = manualRows.toMutableList()
                                                    updated[idx] = row.copy(skipped = !row.skipped)
                                                    manualRows = syncManualLevels(updated, manualStartLevelText.toIntOrNull() ?: 1, manualProfession, manualRideSelection)
                                                },
                                                onDelete = {
                                                    val updated = manualRows.toMutableList().also { it.removeAt(idx) }
                                                    manualRows = syncManualLevels(updated, manualStartLevelText.toIntOrNull() ?: 1, manualProfession, manualRideSelection)
                                                },
                                            )
                                        }
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(
                                                onClick = {
                                                    val next = manualRows + QuickManualObservation(1, manualProfession, List(9) { 0 })
                                                    manualRows = syncManualLevels(next, manualStartLevelText.toIntOrNull() ?: 1, manualProfession, manualRideSelection)
                                                },
                                                enabled = !isRunning && manualRows.size < 20,
                                                modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                                            ) {
                                                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Text("添加升级")
                                            }
                                            OutlinedButton(
                                                onClick = {
                                                    val next = manualRows + QuickManualObservation(1, manualProfession, List(9) { 0 }, skipped = true)
                                                    manualRows = syncManualLevels(next, manualStartLevelText.toIntOrNull() ?: 1, manualProfession, manualRideSelection)
                                                },
                                                enabled = !isRunning && manualRows.size < 20,
                                                modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                                            ) {
                                                Text("+ 添加跳过")
                                            }
                                        }
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            TextButton(
                                                onClick = {
                                                    val startLv = (manualStartLevelText.toIntOrNull() ?: 1).coerceIn(1, 94)
                                                    val sampleInput = BuddyGrowthInput(
                                                        characterId = characterId,
                                                        profession = manualProfession,
                                                        level = startLv,
                                                        part3 = manualEffectivePart3,
                                                        route = manualLine.rulesRoute,
                                                        ride = manualRideSelection,
                                                    )
                                                    val sampleRows = runCatching {
                                                        activeRules.forecast(123_456L, sampleInput, count = 4).rows
                                                    }.getOrNull()
                                                    if (sampleRows != null && sampleRows.size == 4) {
                                                        manualRows = sampleRows.map { r ->
                                                            QuickManualObservation(
                                                                level = r.fromLevel,
                                                                profession = manualProfession,
                                                                gains = GrowthStat.entries.map { r.gains.getValue(it) },
                                                                ride = manualRideSelection,
                                                            )
                                                        }
                                                        beginText = "0"
                                                        endText = "1000000"
                                                    }
                                                },
                                                enabled = !isRunning,
                                                modifier = Modifier.weight(1f).heightIn(min = 40.dp).testTag("seed-manual-sample"),
                                            ) {
                                                Text("填入示例试玩（前 100 万速测）")
                                            }
                                            TextButton(
                                                onClick = {
                                                    val startLv = (manualStartLevelText.toIntOrNull() ?: 1).coerceIn(1, 95)
                                                    manualRows = syncManualLevels(
                                                        List(4) { QuickManualObservation(1, manualProfession, List(9) { 0 }) },
                                                        startLv,
                                                        manualProfession,
                                                        manualRideSelection,
                                                    )
                                                },
                                                enabled = !isRunning,
                                                modifier = Modifier.heightIn(min = 40.dp),
                                            ) {
                                                Text("清空")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    "journal" -> {
                        item(key = "journal-picker") {
                            EditorialCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("选择本地升级记录册", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                                    if (localJournals.isEmpty()) {
                                        Text(
                                            "当前存档还没有升级记录册。可切换到「手动录入实测」直接录入，或先前往升级记录册建册。",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    } else {
                                        localJournals.forEach { (eid, j) ->
                                            val sel = eid == selectedJournalEntityId
                                            val cName = activeRules?.characters?.firstOrNull { it.id == j.characterId }?.name ?: j.characterId
                                            Card(
                                                onClick = { if (!isRunning) selectedJournalEntityId = eid },
                                                shape = RoundedCornerShape(14.dp),
                                                colors = CardDefaults.cardColors(
                                                    containerColor = if (sel) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                                                    else MaterialTheme.colorScheme.surfaceContainerLow,
                                                ),
                                                border = BorderStroke(1.dp, if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                                            ) {
                                                Row(
                                                    Modifier.fillMaxWidth().padding(12.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                ) {
                                                    BuddyPortrait(j.characterId, characterLabel(j.characterId, cName), visuals, Modifier.size(42.dp))
                                                    Column(Modifier.weight(1f)) {
                                                        Text(j.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                                        Text(
                                                            "${j.line.label} · ${characterLabel(j.characterId, cName)} · ${j.observations.count { obs -> !obs.skipped && obs.exam == null && obs.professionChangeFrom == null }} 次升级",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        )
                                                    }
                                                    if (sel) {
                                                        Text("已选", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    else -> {
                        if (archives.isEmpty()) {
                            item(key = "backup-empty") {
                                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text("先导入 Buddy 备份", style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            "需要至少四条有效的非跳过升级记录才能开始反推；也可以切换到「手动录入实测」直接录入。",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Button(onClick = onChooseBackup, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                                            Text("选择 JSON 备份")
                                        }
                                    }
                                }
                            }
                        } else {
                            item(key = "backup-picker") {
                                val selectedArchive = archives.firstOrNull { it.archiveId == archiveId }
                                val selectedCharacter = activeRules?.characters?.firstOrNull { it.id == characterId }
                                if (expanded) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                                        OutlinedButton(
                                            onClick = { archivePickerOpen = true },
                                            enabled = !isRunning,
                                            modifier = Modifier.weight(1f).heightIn(min = 60.dp),
                                        ) {
                                            Column(horizontalAlignment = Alignment.Start) {
                                                Text("导入备份", style = MaterialTheme.typography.labelMedium)
                                                Text(selectedArchive?.let(::archiveLabel) ?: "选择备份", maxLines = 2)
                                            }
                                        }
                                        if (activeRules != null) {
                                            OutlinedButton(
                                                onClick = { characterPickerOpen = true },
                                                enabled = !isRunning,
                                                modifier = Modifier.weight(1f).heightIn(min = 60.dp),
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                    selectedCharacter?.let {
                                                        BuddyPortrait(characterId, characterLabel(it.id, it.name), visuals, Modifier.size(44.dp))
                                                    }
                                                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                                                        Text("角色", style = MaterialTheme.typography.labelMedium)
                                                        Text(selectedCharacter?.let { characterLabel(it.id, it.name) } ?: "选择角色", maxLines = 1)
                                                    }
                                                    Text("更换", style = MaterialTheme.typography.labelSmall)
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(
                                            onClick = { archivePickerOpen = true },
                                            enabled = !isRunning,
                                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                                        ) {
                                            Text("导入备份：${selectedArchive?.let(::archiveLabel) ?: "选择备份"}", maxLines = 2)
                                        }
                                        if (activeRules != null) {
                                            OutlinedButton(
                                                onClick = { characterPickerOpen = true },
                                                enabled = !isRunning,
                                                modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                                            ) {
                                                Row(
                                                    Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                                ) {
                                                    selectedCharacter?.let {
                                                        BuddyPortrait(characterId, characterLabel(it.id, it.name), visuals, Modifier.size(48.dp))
                                                    }
                                                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                                                        Text("角色", style = MaterialTheme.typography.labelMedium)
                                                        Text(selectedCharacter?.let { characterLabel(it.id, it.name) } ?: "选择角色")
                                                    }
                                                    Text("更换", style = MaterialTheme.typography.labelMedium)
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

            // Show history validation, search range, progress & results whenever we have an active source
            val showSearchSection = recordedHistory != null || sourceMode == "manual" ||
                (sourceMode == "journal" && localJournals.isNotEmpty()) ||
                (sourceMode == "backup" && archives.isNotEmpty())

            if (showSearchSection) {
                item(key = "search-setup-and-range") {
                    @Composable
                    fun HistorySummaryCard(modifier: Modifier = Modifier) {
                        EditorialCard(modifier) {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("参与反推的记录", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                    if (history?.ready == true) {
                                        Text("可开始", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                when {
                                    rulesError != null -> Text(rulesError!!, color = MaterialTheme.colorScheme.error)
                                    historyError != null -> Text(historyError!!, color = MaterialTheme.colorScheme.error)
                                    history == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                                        Text("正在检查升级记录…")
                                    }
                                    else -> {
                                        val loadedHistory = requireNotNull(history)
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            SearchMetric("有效升级", loadedHistory.observedLevels.toString(), Modifier.weight(1f))
                                            SearchMetric("跳过", loadedHistory.skippedAttempts.toString(), Modifier.weight(1f))
                                            SearchMetric("种子事件", loadedHistory.events.size.toString(), Modifier.weight(1f))
                                        }
                                        loadedHistory.endState?.let { endState ->
                                            Text(
                                                "反推终点：Lv.${endState.level} · ${endState.profession}（命中可直达 Lv.99 预览）",
                                                style = MaterialTheme.typography.bodySmall.tabularNumbers(),
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                        loadedHistory.errors.forEach {
                                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                        }
                                        loadedHistory.warnings.forEach {
                                            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    @Composable
                    fun RangeConfigCard(modifier: Modifier = Modifier) {
                        EditorialCard(modifier) {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("搜索范围", style = MaterialTheme.typography.titleMedium)
                                if (recordedHistory == null) {
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        RangePresetChip("完整 32 位空间", beginText == "0" && endText == SeedEngine.UINT32_LIMIT.toString(), !isRunning) {
                                            beginText = "0"; endText = SeedEngine.UINT32_LIMIT.toString()
                                        }
                                        RangePresetChip("前 100 万（速测）", beginText == "0" && endText == "1000000", !isRunning) {
                                            beginText = "0"; endText = "1000000"
                                        }
                                        RangePresetChip("前 1000 万", beginText == "0" && endText == "10000000", !isRunning) {
                                            beginText = "0"; endText = "10000000"
                                        }
                                    }
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SeedRangeField(
                                        "起始种子（含）", beginText,
                                        { value -> if (value.length <= 10 && value.all(Char::isDigit)) beginText = value },
                                        Modifier.weight(1f), !isRunning,
                                    )
                                    SeedRangeField(
                                        "结束种子（不含）", endText,
                                        { value -> if (value.length <= 10 && value.all(Char::isDigit)) endText = value },
                                        Modifier.weight(1f), !isRunning,
                                    )
                                }
                                Text(
                                    "32 位范围：0–4,294,967,295；结束值 4,294,967,296 表示搜索完整空间。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (!validRange) {
                                    Text(
                                        "请输入有效范围，并确保结束值大于起始值。",
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }

                    if (expanded) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                            HistorySummaryCard(Modifier.weight(1f))
                            RangeConfigCard(Modifier.weight(1f))
                        }
                    } else {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            HistorySummaryCard(Modifier.fillMaxWidth())
                            RangeConfigCard(Modifier.fillMaxWidth())
                        }
                    }
                }

                // Keep action CTA, running progress, error, and search result together in one lazy item so
                // progress & results compose immediately without being cut off at the bottom fold on phones.
                item(key = "search-action-and-result") {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (isRunning) {
                            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("正在搜索候选种子…", style = MaterialTheme.typography.titleSmall)
                                    progress?.let { current ->
                                        val ratio = if (current.totalSeeds == 0L) 0f else current.processedSeeds.toFloat() / current.totalSeeds
                                        val elapsedMillis = (SystemClock.elapsedRealtime() - searchStartedAtMillis).coerceAtLeast(1L)
                                        val speedMps = current.processedSeeds.toDouble() / (elapsedMillis * 1_000.0)
                                        val remainingSeeds = (current.totalSeeds - current.processedSeeds).coerceAtLeast(0)
                                        val estimatedRemainingMillis = if (current.processedSeeds > 0 && elapsedMillis > 0) {
                                            (remainingSeeds.toDouble() / current.processedSeeds * elapsedMillis).toLong()
                                        } else null
                                        Text(
                                            "已用 ${formatSeedDuration(elapsedMillis)} · ${estimatedRemainingMillis?.let {
                                                "预计剩余 ${formatSeedDuration(it)}"
                                            } ?: "正在估算"} · ${"%.1f".format(speedMps)} 百万/秒",
                                            style = MaterialTheme.typography.bodySmall.tabularNumbers(),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            "已检查 ${current.processedSeeds} / ${current.totalSeeds} · ${(ratio * 100).toInt()}%",
                                            style = MaterialTheme.typography.bodySmall.tabularNumbers(),
                                        )
                                        LinearProgressIndicator(
                                            progress = { ratio.coerceIn(0f, 1f) },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                }
                            }
                        } else {
                            Button(
                                onClick = ::startSearch,
                                enabled = validRange && history?.ready == true && !savingSelection,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("seed-search-start"),
                            ) {
                                Text(if (recordedHistory != null) "用当前记录重新反推" else if (searchResult != null) "重新搜索此范围" else "开始搜索")
                            }
                        }
                        searchError?.let { message -> Text(message, color = MaterialTheme.colorScheme.error) }
                        searchResult?.let { result ->
                            SearchResultCard(
                                result = result,
                                backend = searchBackend,
                                elapsedMillis = searchElapsedMillis,
                                onSelectCandidate = onSelectCandidate,
                                onPreviewCandidate = onPreviewCandidate?.let { handler ->
                                    { candidate -> buildPreviewContext(candidate)?.let(handler) }
                                },
                                expanded = expanded,
                                savingSelection = savingSelection,
                            )
                        }
                    }
                }

                item(key = "search-algo-footer") {
                    Column {
                        TextButton(onClick = { showAlgorithmDetails = !showAlgorithmDetails }) {
                            Text(if (showAlgorithmDetails) "收起计算说明" else "查看计算说明")
                        }
                        if (showAlgorithmDetails) {
                            Text(
                                "算法参考 FE_Rigging_Buddy ${BuddyRules.REVISION.take(8)}，内置多线程与无分支提前剪枝加速；搜寻到的种子表示匹配，不代表存档会自动更新。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    if (archivePickerOpen) AlertDialog(
        onDismissRequest = { archivePickerOpen = false },
        title = { Text("选择 Buddy 存档") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(archives, key = BuddyArchiveSummary::archiveId) { item ->
                    OutlinedButton(
                        onClick = { archiveId = item.archiveId; archivePickerOpen = false },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    ) {
                        Text(archiveLabel(item), modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { archivePickerOpen = false }) { Text("关闭") } },
    )
    if (characterPickerOpen) AlertDialog(
        onDismissRequest = { characterPickerOpen = false; characterQuery = "" },
        title = { Text("选择角色") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(characterQuery, { characterQuery = it }, label = { Text("搜索角色") }, singleLine = true)
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(activeRules?.characters.orEmpty().filter { it.name.contains(characterQuery.trim(), ignoreCase = true) }, key = { it.id }) { item ->
                        TextButton(
                            onClick = { characterId = item.id; characterPickerOpen = false; characterQuery = "" },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                BuddyPortrait(item.id, characterLabel(item.id, item.name), visuals, Modifier.size(44.dp))
                                Text(characterLabel(item.id, item.name), modifier = Modifier.weight(1f))
                                if (item.id == characterId) {
                                    Text("已选", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { characterPickerOpen = false; characterQuery = "" }) { Text("关闭") } },
    )
}

@Composable
private fun ManualObservationRowEditor(
    index: Int,
    row: QuickManualObservation,
    darkTheme: Boolean,
    canDelete: Boolean,
    enabled: Boolean,
    onCycleStat: (Int) -> Unit,
    onToggleSkip: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (row.skipped) "第 ${index + 1} 步 · Lv.${row.level}（跳过/考试）"
                    else "第 ${index + 1} 步 · Lv.${row.level} → ${row.level + 1}",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge.tabularNumbers(),
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(onClick = onToggleSkip, enabled = enabled, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(if (row.skipped) "改为升级" else "标为跳过", style = MaterialTheme.typography.labelSmall)
                }
                if (canDelete) {
                    TextButton(onClick = onDelete, enabled = enabled, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除此行", modifier = Modifier.size(18.dp))
                    }
                }
            }
            if (!row.skipped) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    GrowthStat.entries.forEach { stat ->
                        val g = row.gains[stat.ordinal]
                        val tone = growthStatTone(stat, darkTheme)
                        val active = g > 0
                        Surface(
                            onClick = { if (enabled) onCycleStat(stat.ordinal) },
                            modifier = Modifier.weight(1f).heightIn(min = 42.dp).testTag("manual-row-$index-stat-${stat.name}"),
                            shape = RoundedCornerShape(8.dp),
                            color = if (active) tone.container else MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, if (active) tone.accent else MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    when (stat) {
                                        GrowthStat.HP -> "HP"
                                        GrowthStat.STRENGTH -> "力"
                                        GrowthStat.MAGIC -> "魔"
                                        GrowthStat.DEXTERITY -> "技"
                                        GrowthStat.SPEED -> "速"
                                        GrowthStat.LUCK -> "幸"
                                        GrowthStat.DEFENSE -> "防"
                                        GrowthStat.RESISTANCE -> "抗"
                                        GrowthStat.CHARM -> "魅"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (active) tone.content else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    if (active) "+$g" else "0",
                                    style = MaterialTheme.typography.labelSmall.tabularNumbers(),
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                    color = if (active) tone.content else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RangePresetChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = { if (enabled) onClick() },
        shape = RoundedCornerShape(100.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.heightIn(min = 36.dp),
    ) {
        Box(Modifier.padding(horizontal = 11.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatSeedDuration(durationMillis: Long): String {
    val totalSeconds = (durationMillis / 1_000).coerceAtLeast(0)
    val days = totalSeconds / 86_400
    val hours = totalSeconds / 3_600 % 24
    val minutes = totalSeconds / 60 % 60
    val seconds = totalSeconds % 60
    return when {
        days > 0 -> "${days}天${hours}小时"
        totalSeconds >= 3_600 -> "${totalSeconds / 3_600}小时${minutes}分"
        totalSeconds >= 60 -> "${totalSeconds / 60}分${seconds}秒"
        else -> "${seconds}秒"
    }
}

@Composable
private fun SearchResultCard(
    result: SeedSearchResult,
    backend: SeedSearchBackend? = null,
    elapsedMillis: Long = 0L,
    onSelectCandidate: ((SeedSearchCandidate, SeedSearchResult) -> Unit)?,
    onPreviewCandidate: ((SeedSearchCandidate) -> Unit)?,
    expanded: Boolean,
    savingSelection: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        EditorialCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        when (result.status) {
                            SeedSearchStatus.UNIQUE -> "当前范围内唯一匹配"
                            SeedSearchStatus.AMBIGUOUS -> "当前范围内存在多个匹配"
                            SeedSearchStatus.NO_MATCH -> "当前范围没有匹配"
                            SeedSearchStatus.IMPOSSIBLE -> "记录间存在不可能条件"
                            SeedSearchStatus.INCOMPLETE -> "候选数超过安全上限"
                            SeedSearchStatus.CANCELLED -> "搜索已取消，结果不完整"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = if (result.status == SeedSearchStatus.UNIQUE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                    backend?.let { b ->
                        val sec = (elapsedMillis.coerceAtLeast(1L)) / 1000.0
                        val mps = result.processedSeeds.toDouble() / (elapsedMillis.coerceAtLeast(1L) * 1_000.0)
                        EditorialBadge(
                            text = "${b.label} · ${"%.2f".format(sec)}s · ${"%.1f".format(mps)}M/s",
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SearchMetric("已检查", "${result.processedSeeds} / ${result.totalSeeds}", Modifier.weight(1f))
                    SearchMetric("候选起点", result.matches.toString(), Modifier.weight(1f))
                }
                Text(
                    if (result.fullUint32SpaceScanned) "已扫描完整 32 位种子空间。" else "本次只扫描所选范围，结论仅适用于该范围。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (result.status == SeedSearchStatus.INCOMPLETE) Text("候选集合达到安全上限；当前列表不是完整结果。")
                if (result.status == SeedSearchStatus.UNIQUE && result.fullUint32SpaceScanned) {
                    Text("该候选在完整 32 位种子空间内唯一。")
                }
                if (result.status == SeedSearchStatus.CANCELLED) Text("本次扫描没有覆盖完整范围，不能据此判断无解。")
                result.impossibleEventIndex?.let { Text("第 ${it + 1} 个种子事件与固定规则不相容。") }
                if (result.prefixMatches.isNotEmpty()) Text("逐条通过数：${result.prefixMatches.joinToString(" · ")}")
            }
        }
        result.candidates.take(30).chunked(if (expanded) 2 else 1).forEach { group ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                group.forEach { candidate ->
                    EditorialCard(Modifier.weight(1f)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "记录结束后的当前种子",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(candidate.currentSeed.toString(), style = MaterialTheme.typography.titleLarge.tabularNumbers(), fontWeight = FontWeight.Bold)
                            Text("起始候选：${candidate.originSeeds.take(8).joinToString()}", style = MaterialTheme.typography.bodySmall.tabularNumbers())
                            onPreviewCandidate?.let { preview ->
                                Button(
                                    onClick = { preview(candidate) },
                                    enabled = !savingSelection,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .testTag("seed-candidate-preview:${candidate.currentSeed}"),
                                ) {
                                    Icon(Icons.AutoMirrored.Outlined.TrendingUp, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.size(6.dp))
                                    Text("预览后续升级与规划")
                                }
                            }
                            onSelectCandidate?.let { select ->
                                OutlinedButton(
                                    onClick = { select(candidate, result) },
                                    enabled = !savingSelection,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                ) {
                                    Text(if (savingSelection) "正在保存…" else "保存此候选种子")
                                }
                            }
                        }
                    }
                }
                if (expanded && group.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (result.candidates.size > 30) {
            Text(
                "其余候选已省略显示，可缩小范围后重新搜索。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SearchMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleSmall.tabularNumbers())
        }
    }
}

@Composable
private fun SeedRangeField(label: String, value: String, onValueChange: (String) -> Unit, modifier: Modifier, enabled: Boolean) {
    OutlinedTextField(
        value,
        onValueChange,
        modifier = modifier,
        enabled = enabled,
        label = { Text(label) },
        singleLine = true,
    )
}

private fun archiveLabel(archive: BuddyArchiveSummary): String =
    "${archive.archiveId.take(8)} · ${archive.characterCount} 名角色 · ${archive.recordCount} 条记录"
