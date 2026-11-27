package com.fireemblem.companion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.CompareArrows
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fireemblem.companion.engine.BuddyCampaignLine
import com.fireemblem.companion.engine.BuddyForecast
import com.fireemblem.companion.engine.BuddyGrowthInput
import com.fireemblem.companion.engine.BuddyGrowthPlanRequest
import com.fireemblem.companion.engine.BuddyGrowthPlanResult
import com.fireemblem.companion.engine.BuddyGrowthPlanStep
import com.fireemblem.companion.engine.BuddyGrowthPlanner
import com.fireemblem.companion.engine.BuddyProfessionWindow
import com.fireemblem.companion.engine.BuddyRideSelection
import com.fireemblem.companion.engine.BuddyRules
import com.fireemblem.companion.engine.GrowthStat
import com.fireemblem.companion.engine.JournalGrowthPlan
import com.fireemblem.companion.engine.SameSeedClassOption
import com.fireemblem.companion.engine.SeedEngine
import com.fireemblem.companion.engine.SeedEvaluatedPlanRow
import com.fireemblem.companion.engine.SeedPlanActionKind
import com.fireemblem.companion.engine.SeedPreviewFilter
import com.fireemblem.companion.engine.SeedPreviewOverride
import com.fireemblem.companion.engine.SeedWorkbenchEngine
import com.fireemblem.companion.engine.SeedWorkbenchEvaluation
import com.fireemblem.companion.engine.SeedWorkbenchPlanStep
import com.fireemblem.companion.engine.SeedWorkbenchPreviewRow
import com.fireemblem.companion.engine.SeedWorkbenchRecord
import com.fireemblem.companion.engine.SeedWorkbenchStart
import com.fireemblem.companion.engine.TrialProfessionStatus
import com.fireemblem.companion.personal.PersonalStore
import com.fireemblem.companion.ui.theme.tabularNumbers
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Context passed into [SeedForecastScreen] when launched from reverse seed search or a LevelJournal checkpoint.
 */
internal data class SeedWorkbenchLaunchContext(
    val characterId: String,
    val campaignLine: BuddyCampaignLine = BuddyCampaignLine.KAI,
    val part3: Boolean = false,
    val seed: Long,
    val inferredLevel: Int,
    val profession: String,
    val ride: BuddyRideSelection? = null,
    val unlockedProfessions: Set<String> = setOf(profession),
    val baseStats: List<Int>? = null,
    val caps: List<Int?>? = null,
    val sourceLabel: String? = null,
    val backLabel: String = "返回上页",
    val autoRun: Boolean = true,
    val initialTab: String = "preview", // preview | auto | plan
    val initialWeights: List<Int>? = null,
    val initialPlanSteps: List<SeedWorkbenchPlanStep> = emptyList(),
    val journalHistoryDigest: String? = null,
    val journalRuleRevision: String = BuddyRules.REVISION,
)

@Composable
internal fun SeedForecastScreen(
    onBack: () -> Unit,
    forecastCalculator: (suspend (BuddyRules, Long, BuddyGrowthInput) -> BuddyForecast)? = null,
    initialContext: SeedWorkbenchLaunchContext? = null,
    savingJournalPlan: Boolean = false,
    journalPlanSaveError: String? = null,
    onSaveJournalGrowthPlan: ((JournalGrowthPlan, List<Int>, List<Int?>, Set<String>) -> Unit)? = null,
) {
    BackHandler(onBack = onBack)
    val appContext = LocalContext.current
    val scope = rememberCoroutineScope()
    val visuals = rememberBuddyVisuals()
    val darkTheme = isSystemInDarkTheme()

    var rules by remember { mutableStateOf<BuddyRules?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }

    var characterId by rememberSaveable(initialContext) { mutableStateOf(initialContext?.characterId ?: "c044") }
    var profession by rememberSaveable(initialContext) { mutableStateOf(initialContext?.profession ?: "平民") }
    var inferredLevel by rememberSaveable(initialContext) { mutableIntStateOf(initialContext?.inferredLevel ?: 1) }
    var levelText by rememberSaveable(initialContext) { mutableStateOf((initialContext?.inferredLevel ?: 1).toString()) }
    var extraAdvancesText by rememberSaveable(initialContext) { mutableStateOf("0") }
    var seedText by rememberSaveable(initialContext) { mutableStateOf(initialContext?.seed?.toString().orEmpty()) }
    var part3 by rememberSaveable(initialContext) { mutableStateOf(initialContext?.part3 ?: false) }
    var campaignLineId by rememberSaveable(initialContext) {
        mutableStateOf(initialContext?.campaignLine?.id ?: BuddyCampaignLine.KAI.id)
    }
    var rideId by rememberSaveable(initialContext) { mutableStateOf(initialContext?.ride?.id.orEmpty()) }
    var friendship by rememberSaveable(initialContext) { mutableStateOf((initialContext?.ride?.level ?: 1).toString()) }
    var unlockedProfessions by remember(initialContext) {
        mutableStateOf(initialContext?.unlockedProfessions?.plus(initialContext.profession) ?: setOf(profession))
    }

    var previewHorizon by rememberSaveable { mutableIntStateOf(98) }

    // Interactive workbench states
    var weights by remember(initialContext) {
        mutableStateOf(
            initialContext?.initialWeights?.let(SeedWorkbenchEngine::normalizeWeights)
                ?: SeedWorkbenchEngine.DEFAULT_WEIGHTS,
        )
    }
    var planSteps by remember(initialContext) {
        mutableStateOf(initialContext?.initialPlanSteps.orEmpty())
    }
    var previewOverrides by remember { mutableStateOf<Map<Int, SeedPreviewOverride>>(emptyMap()) }
    var completedSteps by rememberSaveable { mutableIntStateOf(0) }

    // Smart Filter state
    var filterMinTotal by rememberSaveable { mutableIntStateOf(0) }
    var filterMaxExam by rememberSaveable { mutableStateOf<Int?>(null) }
    var filterOnlyModified by rememberSaveable { mutableStateOf(false) }
    var filterRequiredStats by remember { mutableStateOf<Set<GrowthStat>>(emptySet()) }

    // Tabs & collapsible drawers (minimal chrome by default!)
    var activeTab by rememberSaveable(initialContext) { mutableStateOf(initialContext?.initialTab ?: "preview") } // preview | auto | plan
    var configExpanded by rememberSaveable(initialContext) { mutableStateOf(initialContext == null) }
    var weightsDrawerOpen by rememberSaveable { mutableStateOf(false) }
    var filterDrawerOpen by rememberSaveable { mutableStateOf(false) }
    var showAlgorithmDetails by rememberSaveable { mutableStateOf(false) }
    var unlockedDialogOpen by remember { mutableStateOf(false) }
    var compareDialogRow by remember { mutableStateOf<SeedWorkbenchPreviewRow?>(null) }
    var addActionDialogOpen by remember { mutableStateOf(false) }
    var actionFeedback by remember { mutableStateOf<String?>(null) }
    var actionIsError by remember { mutableStateOf(false) }
    var savedRecordCandidate by remember { mutableStateOf<SeedWorkbenchRecord?>(null) }

    // Auto-Planner (rebuilt 规划后续培养) state
    var autoTargetLevelText by rememberSaveable(initialContext) {
        mutableStateOf(((initialContext?.inferredLevel ?: 1) + 10).coerceAtMost(99).toString())
    }
    var autoMaxSwitches by rememberSaveable { mutableIntStateOf(98) }
    var autoRespectTierLevel by rememberSaveable { mutableStateOf(true) }
    var autoCustomMinLevels by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var autoAdvancedOpen by rememberSaveable { mutableStateOf(false) }
    var autoPart3StartText by rememberSaveable(initialContext) {
        mutableStateOf(if (initialContext?.part3 == true) "1" else "")
    }
    var autoBaseStatsText by rememberSaveable(initialContext) {
        mutableStateOf(initialContext?.baseStats?.joinToString(",") ?: List(9) { "" }.joinToString(","))
    }
    var autoCapsText by rememberSaveable(initialContext) {
        mutableStateOf(initialContext?.caps?.joinToString(",") { it?.toString().orEmpty() } ?: List(9) { "" }.joinToString(","))
    }
    var autoMinimumsText by rememberSaveable { mutableStateOf(List(9) { "" }.joinToString(",")) }
    var autoBusy by remember { mutableStateOf(false) }
    var autoError by remember { mutableStateOf<String?>(null) }
    var autoResult by remember { mutableStateOf<BuddyGrowthPlanResult?>(null) }
    var autoRequest by remember { mutableStateOf<BuddyGrowthPlanRequest?>(null) }
    var autoCancellation by remember { mutableStateOf<AtomicBoolean?>(null) }
    DisposableEffect(Unit) { onDispose { autoCancellation?.set(true) } }

    // Calculation triggers
    var requestedKey by rememberSaveable(initialContext) { mutableStateOf<String?>(null) }
    var calculationRequest by rememberSaveable(initialContext) { mutableIntStateOf(0) }
    var evaluation by remember { mutableStateOf<SeedWorkbenchEvaluation?>(null) }
    var calculationError by remember { mutableStateOf<String?>(null) }
    var computing by remember { mutableStateOf(false) }

    val inputKey = listOf(
        characterId, profession, levelText, extraAdvancesText, seedText, part3, campaignLineId, rideId, friendship,
    ).joinToString("|")

    LaunchedEffect(retry) {
        loadError = null
        try {
            rules = withContext(Dispatchers.IO) { BuddyRules.bundled() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            loadError = "成长规则未能读取或校验未通过，请重试或重新安装应用。"
        }
    }

    val loaded = rules
    val level = levelText.toIntOrNull()
    val extraAdvances = extraAdvancesText.toIntOrNull() ?: 0
    val seed = seedText.toLongOrNull()
    val character = loaded?.characters?.find { it.id == characterId }
    val campaignLine = BuddyCampaignLine.entries.firstOrNull { it.id == campaignLineId } ?: BuddyCampaignLine.KAI
    val effectivePart3 = part3 || character?.part3Only == true
    val contextInput = BuddyGrowthInput(
        characterId = characterId,
        profession = profession,
        level = (level ?: 1).coerceIn(1, 98),
        part3 = effectivePart3,
        route = campaignLine.rulesRoute,
    )
    val classAllowed = loaded?.classAllowed(characterId, profession, effectivePart3) == true
    val validSeed = seed != null && seed in 0 until SeedEngine.UINT32_LIMIT
    val validLevel = level != null && level in 1..98
    val validExtra = extraAdvances in 0..SeedWorkbenchEngine.MAX_PRE_ADVANCES
    val valid = loaded != null && validSeed && validLevel && validExtra && classAllowed

    LaunchedEffect(initialContext, loaded) {
        if (initialContext?.autoRun == true && loaded != null && valid && requestedKey == null) {
            requestedKey = inputKey
            calculationRequest++
        }
    }

    // Check if there is an existing saved workbench record for this character & line
    LaunchedEffect(characterId, campaignLineId, seedText) {
        val currentSeed = seedText.toLongOrNull()
        if (currentSeed == null) {
            savedRecordCandidate = null
            return@LaunchedEffect
        }
        savedRecordCandidate = withContext(Dispatchers.IO) {
            runCatching {
                val profileId = appContext.getSharedPreferences("personal-view", 0)
                    .getString("profile", "global") ?: "global"
                val store = PersonalStore(appContext.applicationContext)
                val entityId = SeedWorkbenchRecord.entityId(profileId, characterId, campaignLine)
                val head = store.snapshot().heads()[entityId]?.singleOrNull()?.takeIf { !it.deleted && it.kind == SeedWorkbenchRecord.KIND }
                head?.let { SeedWorkbenchRecord.decode(it.payload) }?.takeIf { it.baseSeed == currentSeed }
            }.getOrNull()
        }
    }

    // Core calculation effect
    LaunchedEffect(
        inputKey, requestedKey, loaded, calculationRequest,
        planSteps, previewOverrides, weights, unlockedProfessions, previewHorizon,
    ) {
        calculationError = null
        if (requestedKey != inputKey || loaded == null || !valid) {
            evaluation = null
            computing = false
            return@LaunchedEffect
        }
        computing = true
        try {
            val rideSelection = rideId.takeIf { it.isNotEmpty() }?.let {
                BuddyRideSelection(it, (friendship.toIntOrNull() ?: 1).coerceIn(1, 5))
            }
            val start = SeedWorkbenchStart(
                characterId = characterId,
                line = campaignLine,
                part3 = effectivePart3,
                baseSeed = requireNotNull(seed),
                inferredLevel = if (initialContext != null) inferredLevel else requireNotNull(level),
                startLevel = requireNotNull(level),
                extraAdvances = extraAdvances,
                profession = profession,
                ride = rideSelection,
                unlockedProfessions = unlockedProfessions + profession,
                baseStats = initialContext?.baseStats,
            )
            if (forecastCalculator != null) {
                val request = contextInput.copy(ride = rideSelection)
                forecastCalculator(loaded, start.baseSeed, request)
            }
            evaluation = withContext(Dispatchers.Default) {
                SeedWorkbenchEngine.evaluate(
                    rules = loaded,
                    start = start,
                    planSteps = planSteps,
                    previewOverrides = previewOverrides,
                    weights = weights,
                    maxPreviewSteps = previewHorizon,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IllegalArgumentException) {
            evaluation = null
            calculationError = error.message ?: "输入无法用于预测，请检查后重试。"
        } finally {
            computing = false
        }
    }

    fun saveWorkbenchToStore() {
        val eval = evaluation ?: return
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    val profileId = appContext.getSharedPreferences("personal-view", 0)
                        .getString("profile", "global") ?: "global"
                    val store = PersonalStore(appContext.applicationContext)
                    val entityId = SeedWorkbenchRecord.entityId(profileId, characterId, campaignLine)
                    val record = SeedWorkbenchRecord(
                        profileId = profileId,
                        characterId = characterId,
                        line = campaignLine,
                        part3 = effectivePart3,
                        baseSeed = eval.start.baseSeed,
                        inferredLevel = eval.start.inferredLevel,
                        startLevel = eval.start.startLevel,
                        extraAdvances = eval.start.extraAdvances,
                        profession = eval.start.profession,
                        ride = eval.start.ride,
                        unlockedProfessions = eval.start.unlockedProfessions,
                        weights = weights,
                        planSteps = planSteps,
                        previewOverrides = previewOverrides,
                        completedSteps = completedSteps.coerceIn(0, planSteps.size),
                    )
                    val currentHeads = store.snapshot().heads()[entityId].orEmpty().map { it.revisionId }.toSet()
                    store.edit(
                        entityId = entityId,
                        kind = SeedWorkbenchRecord.KIND,
                        payload = record.encode(),
                        deleted = false,
                        expectedParents = currentHeads,
                    )
                    record
                }
            }
            res.onSuccess { rec ->
                savedRecordCandidate = rec
                actionIsError = false
                actionFeedback = "已保存培养方案（${rec.planSteps.size} 步计划 · ${rec.previewOverrides.size} 处标记）。"
            }.onFailure { err ->
                actionIsError = true
                actionFeedback = "保存失败：${err.message ?: "请稍后重试"}"
            }
        }
    }

    fun setCsvSlot(csv: String, idx: Int, value: String): String {
        val list = csv.split(",").toMutableList().also { while (it.size < 9) it += "" }
        list[idx] = value
        return list.take(9).joinToString(",")
    }

    fun runAutoPlanner() {
        val currentRules = loaded ?: return
        val currentEval = evaluation ?: return
        val currentLv = currentEval.planEndLevel
        val targetLv = autoTargetLevelText.toIntOrNull()?.coerceIn(currentLv + 1, 99)
        if (targetLv == null || targetLv <= currentLv) {
            autoError = "目标等级应高于当前等级（Lv.$currentLv）"
            return
        }
        val activeWeights = if (weights.any { it > 0 }) weights else List(9) { 1 }
        val baseStats = autoBaseStatsText.split(",").let { parts ->
            List(9) { i -> parts.getOrNull(i)?.toIntOrNull() ?: (initialContext?.baseStats?.getOrNull(i) ?: 0) }
        }
        val caps = autoCapsText.split(",").let { parts -> List(9) { i -> parts.getOrNull(i)?.toIntOrNull() } }
        val mins = autoMinimumsText.split(",").let { parts -> List(9) { i -> parts.getOrNull(i)?.toIntOrNull() } }
        val unlockedSet = (currentEval.planEndUnlocked + currentEval.planEndProfession).toSet()
        val windows = unlockedSet.map { prof ->
            val customStart = autoCustomMinLevels[prof]?.toIntOrNull()
            val tierStart = if (prof == currentEval.planEndProfession || !autoRespectTierLevel) {
                currentLv
            } else {
                BuddyRules.defaultUnlockLevelForTier(currentRules.professionTier(prof))
            }
            val effectiveFrom = maxOf(currentLv, customStart ?: tierStart).coerceIn(1, 99)
            BuddyProfessionWindow(prof, effectiveFrom, 99)
        }
        val request = BuddyGrowthPlanRequest(
            seed = currentEval.planEndSeed,
            characterId = characterId,
            line = campaignLine,
            currentLevel = currentLv,
            targetLevel = targetLv,
            part3StartLevel = autoPart3StartText.toIntOrNull() ?: if (effectivePart3) 1 else null,
            currentPart3 = effectivePart3,
            initialProfession = currentEval.planEndProfession,
            unlockedProfessions = unlockedSet,
            professionWindows = windows,
            weights = activeWeights,
            currentStats = baseStats,
            caps = caps,
            minimumStats = mins,
            maxSwitches = autoMaxSwitches,
            ride = currentEval.start.ride,
        )
        val token = AtomicBoolean(false)
        autoCancellation?.set(true)
        autoCancellation = token
        autoBusy = true
        autoError = null
        autoResult = null
        autoRequest = null
        scope.launch {
            try {
                val computed = withContext(Dispatchers.Default) {
                    BuddyGrowthPlanner.plan(request, currentRules, token::get)
                }
                autoResult = computed
                autoRequest = request
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                autoError = failure.message ?: "自动规划计算失败，请检查职业或约束设置。"
            } finally {
                autoBusy = false
                autoCancellation = null
            }
        }
    }

    val currentFilter = SeedPreviewFilter(
        minTotalGain = filterMinTotal,
        requiredStats = filterRequiredStats,
        maxExamMinimum = filterMaxExam,
        onlyModifiedRows = filterOnlyModified,
    )
    val activeEvaluation = if (requestedKey == inputKey) evaluation else null
    val filteredPreviewRows = remember(activeEvaluation, currentFilter) {
        activeEvaluation?.let { SeedWorkbenchEngine.filterPreview(it.previewRows, currentFilter) }.orEmpty()
    }
    val hasActiveWeights = weights.any { it > 0 }
    val showRunButton = configExpanded || activeEvaluation == null || requestedKey != inputKey

    // Always start cleanly at the top when launched with a specific seed/journal context!
    val listState = if (initialContext != null) {
        rememberLazyListState()
    } else {
        rememberReadingListState("seed-forecast-standalone", loaded != null && !computing)
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = 980.dp)
            .testTag("seed-workbench-screen"),
    ) {
        val expanded = maxWidth >= 680.dp
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().testTag("seed-workbench-list"),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Compact Top Bar
            item(key = "workbench-top") {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onBack,
                        modifier = Modifier.heightIn(min = 44.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text(if (initialContext == null) "返回工具" else initialContext.backLabel)
                    }
                    Spacer(Modifier.weight(1f))
                    if (activeEvaluation != null) {
                        OutlinedButton(
                            onClick = ::saveWorkbenchToStore,
                            modifier = Modifier.heightIn(min = 44.dp).testTag("workbench-save"),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("保存方案")
                        }
                    }
                }
            }

            // Loading / error
            if (loaded == null) {
                item(key = "workbench-loading") {
                    EditorialCard {
                        Column(
                            Modifier.fillMaxWidth().padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            if (loadError == null) {
                                CircularProgressIndicator(modifier = Modifier.size(28.dp))
                                Text("正在准备成长规则…", style = MaterialTheme.typography.bodyMedium)
                            } else {
                                Text(requireNotNull(loadError), color = MaterialTheme.colorScheme.error)
                                OutlinedButton(onClick = { retry++ }) { Text("重新读取规则") }
                            }
                        }
                    }
                }
            } else {
                // Saved workbench quick restore banner (only when user has a saved plan matching this seed)
                savedRecordCandidate?.let { saved ->
                    if (planSteps.isEmpty() && previewOverrides.isEmpty() && (saved.planSteps.isNotEmpty() || saved.previewOverrides.isNotEmpty())) {
                        item(key = "workbench-restore-banner") {
                            EditorialCard(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            "已有保存的培养方案",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        )
                                        Text(
                                            "${saved.planSteps.size} 步计划 · ${saved.previewOverrides.size} 处标记（已完成 ${saved.completedSteps}/${saved.planSteps.size}）",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        )
                                    }
                                    Button(
                                        onClick = {
                                            levelText = saved.startLevel.toString()
                                            extraAdvancesText = saved.extraAdvances.toString()
                                            profession = saved.profession
                                            rideId = saved.ride?.id.orEmpty()
                                            friendship = (saved.ride?.level ?: 1).toString()
                                            unlockedProfessions = saved.unlockedProfessions + saved.profession
                                            weights = saved.weights
                                            planSteps = saved.planSteps
                                            previewOverrides = saved.previewOverrides
                                            completedSteps = saved.completedSteps
                                            requestedKey = null
                                            actionIsError = false
                                            actionFeedback = "已载入先前保存的方案。"
                                        },
                                        modifier = Modifier.heightIn(min = 40.dp),
                                    ) { Text("载入") }
                                }
                            }
                        }
                    }
                }

                // Compact Character & Seed Header Card
                item(key = "workbench-config-toggle") {
                    EditorialCard(containerColor = MaterialTheme.colorScheme.surface) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            BuddyPortrait(
                                characterId = characterId,
                                name = characterLabel(characterId, character?.name ?: characterId),
                                visuals = visuals,
                                modifier = Modifier.size(46.dp),
                                part3 = effectivePart3,
                            )
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    "${characterLabel(characterId, character?.name ?: characterId)} · ${campaignLine.label}" +
                                        if (effectivePart3) " · 第三部" else "",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "Lv.${level ?: "?"} · $profession · 种子 ${seedText.ifBlank { "未填写" }}" +
                                        if (extraAdvances > 0) " · +$extraAdvances" else "",
                                    style = MaterialTheme.typography.bodySmall.tabularNumbers(),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            TextButton(
                                onClick = { configExpanded = !configExpanded },
                                modifier = Modifier.heightIn(min = 40.dp).testTag("workbench-toggle-config"),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                Text(if (configExpanded) "收起" else "参数")
                                Icon(
                                    if (configExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }

                // Expandable Config Drawer
                if (configExpanded || activeEvaluation == null) {
                    item(key = "workbench-config-cards") {
                        val characterChoices = loaded.characters.map { it.id to characterLabel(it.id, it.name) }
                        val professionChoices = loaded.professions.filter {
                            loaded.classAllowed(characterId, it.name, effectivePart3)
                        }.map { it.name to it.name }
                        val rides = loaded.availableRides(contextInput.copy(level = 1))
                        val selectedRide = rides.find { it.id == rideId }
                        val rideChoices = listOf("" to "不骑乘") + rides.map { it.id to it.name }

                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            ForecastGroupCard("角色与起点") {
                                if (expanded) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Box(Modifier.weight(1f)) {
                                            IllustratedChoice(
                                                "角色", characterId, characterLabel(characterId, character?.name ?: "选择角色"),
                                                characterChoices, "characters", visuals,
                                            ) { newChar ->
                                                characterId = newChar
                                                val firstAllowed = loaded.professions.firstOrNull {
                                                    loaded.classAllowed(newChar, it.name, part3 || loaded.characters.firstOrNull { c -> c.id == newChar }?.part3Only == true)
                                                }?.name
                                                if (firstAllowed != null && !loaded.classAllowed(newChar, profession, effectivePart3)) {
                                                    profession = firstAllowed
                                                    unlockedProfessions = setOf(firstAllowed)
                                                }
                                                planSteps = emptyList()
                                                previewOverrides = emptyMap()
                                                completedSteps = 0
                                            }
                                        }
                                        Box(Modifier.weight(1f)) {
                                            IllustratedChoice("职业", profession, profession, professionChoices, "professions", visuals) {
                                                profession = it
                                                unlockedProfessions = unlockedProfessions + it
                                            }
                                        }
                                    }
                                } else {
                                    IllustratedChoice(
                                        "角色", characterId, characterLabel(characterId, character?.name ?: "选择角色"),
                                        characterChoices, "characters", visuals,
                                    ) { newChar ->
                                        characterId = newChar
                                        val firstAllowed = loaded.professions.firstOrNull {
                                            loaded.classAllowed(newChar, it.name, part3 || loaded.characters.firstOrNull { c -> c.id == newChar }?.part3Only == true)
                                        }?.name
                                        if (firstAllowed != null && !loaded.classAllowed(newChar, profession, effectivePart3)) {
                                            profession = firstAllowed
                                            unlockedProfessions = setOf(firstAllowed)
                                        }
                                        planSteps = emptyList()
                                        previewOverrides = emptyMap()
                                        completedSteps = 0
                                    }
                                    IllustratedChoice("职业", profession, profession, professionChoices, "professions", visuals) {
                                        profession = it
                                        unlockedProfessions = unlockedProfessions + it
                                    }
                                }
                                if (!classAllowed) {
                                    Text("该角色在当前篇章不能使用此职业。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        seedText,
                                        { seedText = it.filter(Char::isDigit).take(10) },
                                        Modifier.weight(1.4f),
                                        label = { Text("升级前种子") },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        isError = seedText.isNotEmpty() && !validSeed,
                                    )
                                    OutlinedTextField(
                                        levelText,
                                        { levelText = it.filter(Char::isDigit).take(2) },
                                        Modifier.weight(0.75f),
                                        label = { Text("当前等级") },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        isError = !validLevel,
                                    )
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.weight(1f)) {
                                        ChoiceField(
                                            "路线", campaignLine.label,
                                            BuddyCampaignLine.entries.map { it.id to it.label },
                                        ) { campaignLineId = it }
                                    }
                                    OutlinedButton(
                                        onClick = { unlockedDialogOpen = true },
                                        modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("workbench-manage-unlocked"),
                                    ) {
                                        Text("已解锁职业 (${(unlockedProfessions + profession).size})", maxLines = 1)
                                    }
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Box(Modifier.weight(1f)) {
                                        IllustratedChoice(
                                            "坐骑", rideId,
                                            if (rideId.isEmpty()) "不骑乘" else selectedRide?.name ?: "已保存坐骑",
                                            rideChoices, "mounts", visuals,
                                        ) { rideId = it }
                                    }
                                    if (rideId.isNotEmpty()) {
                                        Box(Modifier.weight(0.7f)) {
                                            ChoiceField("友好", friendship, (1..5).map { it.toString() to "Lv.$it" }) { friendship = it }
                                        }
                                    }
                                }
                                ExtraAdvanceStepper(
                                    valueText = extraAdvancesText,
                                    onValueChange = { extraAdvancesText = it },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                ToggleField("第三部规则", effectivePart3, enabled = character?.part3Only != true) { part3 = it }
                                HorizonChipRow(previewHorizon) { previewHorizon = it }
                            }
                        }
                    }
                }

                // Run / Re-predict CTA (ONLY shown when config drawer open or before first evaluation!)
                if (showRunButton || calculationError != null || actionFeedback != null) {
                    item(key = "workbench-cta") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (showRunButton) {
                                Button(
                                    onClick = {
                                        requestedKey = inputKey
                                        calculationRequest++
                                        actionFeedback = null
                                        if (activeEvaluation != null) configExpanded = false
                                    },
                                    enabled = valid && !computing,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("workbench-run-btn"),
                                ) {
                                    Text(
                                        when {
                                            computing -> "正在推演…"
                                            requestedKey == inputKey && activeEvaluation != null -> "重新预测"
                                            else -> "预测后续升级"
                                        },
                                    )
                                }
                            }
                            calculationError?.let {
                                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                            actionFeedback?.let { msg ->
                                Surface(
                                    color = if (actionIsError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = if (actionIsError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth().testTag("workbench-feedback"),
                                ) {
                                    Text(msg, Modifier.padding(horizontal = 12.dp, vertical = 9.dp), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }

                // Unified 3-Tab Workbench Results
                if (activeEvaluation != null) {
                    // 3-Tab Segmented Switcher: 乱数预览 | 自动规划 | 培养计划
                    item(key = "workbench-tabs") {
                        val planBadge = if (planSteps.isEmpty()) "培养计划" else "培养计划 (${planSteps.size})"
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                "preview" to "乱数预览",
                                "auto" to "自动规划",
                                "plan" to planBadge,
                            ).forEach { (key, label) ->
                                val sel = activeTab == key
                                Surface(
                                    onClick = { activeTab = key },
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 44.dp)
                                        .semantics { selected = sel }
                                        .testTag("workbench-tab:$key"),
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                    contentColor = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    border = BorderStroke(1.dp, if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                                ) {
                                    Box(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 9.dp), contentAlignment = Alignment.Center) {
                                        Text(
                                            label,
                                            style = MaterialTheme.typography.labelLarge.tabularNumbers(),
                                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    when (activeTab) {
                        "preview" -> {
                            // Sleek Single-Row Utility Bar (collapsed drawers by default — reclaims ~200dp!)
                            item(key = "workbench-preview-toolbar") {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        val activeWeightCount = weights.count { it > 0 }
                                        SelectablePill(
                                            label = if (activeWeightCount > 0) "偏好 ($activeWeightCount)" else "偏好高亮",
                                            selected = weightsDrawerOpen || activeWeightCount > 0,
                                            testTag = "workbench-toggle-weights",
                                            onClick = {
                                                weightsDrawerOpen = !weightsDrawerOpen
                                                if (weightsDrawerOpen) filterDrawerOpen = false
                                            },
                                        )
                                        SelectablePill(
                                            label = if (currentFilter.isActive) "筛选 (${filteredPreviewRows.size}/${activeEvaluation.previewRows.size})" else "筛选",
                                            selected = filterDrawerOpen || currentFilter.isActive,
                                            testTag = "workbench-toggle-filter",
                                            onClick = {
                                                filterDrawerOpen = !filterDrawerOpen
                                                if (filterDrawerOpen) weightsDrawerOpen = false
                                            },
                                        )
                                        Spacer(Modifier.weight(1f))
                                        if (previewOverrides.isNotEmpty()) {
                                            TextButton(
                                                onClick = { previewOverrides = emptyMap() },
                                                modifier = Modifier.heightIn(min = 36.dp),
                                                contentPadding = PaddingValues(horizontal = 8.dp),
                                            ) { Text("清除标记 (${previewOverrides.size})") }
                                        } else {
                                            Text(
                                                "共 ${activeEvaluation.previewRows.size} 步至 Lv.${activeEvaluation.previewRows.lastOrNull()?.nextLevel ?: activeEvaluation.planEndLevel}",
                                                style = MaterialTheme.typography.labelMedium.tabularNumbers(),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }

                                    // Collapsible Weights Drawer
                                    if (weightsDrawerOpen) {
                                        EditorialCard(modifier = Modifier.fillMaxWidth().testTag("workbench-weights-card")) {
                                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                WeightSelectorPanel(
                                                    weights = weights,
                                                    darkTheme = darkTheme,
                                                    onChange = { weights = it },
                                                )
                                            }
                                        }
                                    }

                                    // Collapsible Smart Filter Drawer
                                    if (filterDrawerOpen) {
                                        EditorialCard(modifier = Modifier.fillMaxWidth().testTag("workbench-filter-bar")) {
                                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Row(
                                                    Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    Text(
                                                        "筛选升级行（命中 ${filteredPreviewRows.size} / ${activeEvaluation.previewRows.size}）",
                                                        style = MaterialTheme.typography.labelLarge,
                                                    )
                                                    if (currentFilter.isActive) {
                                                        TextButton(onClick = {
                                                            filterMinTotal = 0
                                                            filterMaxExam = null
                                                            filterOnlyModified = false
                                                            filterRequiredStats = emptySet()
                                                        }) { Text("清空") }
                                                    }
                                                }
                                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    listOf(0 to "涨点不限", 4 to "≥4 点", 5 to "≥5 点", 6 to "≥6 点").forEach { (minVal, label) ->
                                                        SelectablePill(label, filterMinTotal == minVal, testTag = "filter-min:$minVal") {
                                                            filterMinTotal = minVal
                                                        }
                                                    }
                                                    listOf(null to "考试不限", 50 to "考 ≤50%", 30 to "考 ≤30%").forEach { (maxEx, label) ->
                                                        SelectablePill(label, filterMaxExam == maxEx, testTag = "filter-exam:${maxEx ?: "any"}") {
                                                            filterMaxExam = maxEx
                                                        }
                                                    }
                                                    SelectablePill("仅已改行", filterOnlyModified, testTag = "filter-modified") {
                                                        filterOnlyModified = !filterOnlyModified
                                                    }
                                                }
                                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    GrowthStat.entries.forEach { stat ->
                                                        val sel = stat in filterRequiredStats
                                                        SelectablePill(stat.label, sel, testTag = "filter-stat:${stat.name}") {
                                                            filterRequiredStats = if (sel) filterRequiredStats - stat else filterRequiredStats + stat
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            if (filteredPreviewRows.isEmpty()) {
                                item(key = "workbench-preview-empty") {
                                    EditorialCard {
                                        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(
                                                if (activeEvaluation.previewRows.isEmpty()) "角色已达 Lv.99，无后续升级。"
                                                else "当前筛选条件下暂无匹配行，可放宽总涨点或必涨属性条件。",
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            if (currentFilter.isActive) {
                                                OutlinedButton(onClick = {
                                                    filterMinTotal = 0
                                                    filterMaxExam = null
                                                    filterOnlyModified = false
                                                    filterRequiredStats = emptySet()
                                                }) { Text("清空筛选") }
                                            }
                                        }
                                    }
                                }
                            } else {
                                items(filteredPreviewRows, key = { it.stepIndex }) { row ->
                                    WorkbenchPreviewRowCard(
                                        row = row,
                                        weights = weights,
                                        hasActiveWeights = hasActiveWeights,
                                        visuals = visuals,
                                        darkTheme = darkTheme,
                                        onToggleSkip = {
                                            val current = previewOverrides[row.stepIndex] ?: SeedPreviewOverride()
                                            val updated = current.copy(skipOrExam = !current.skipOrExam)
                                            previewOverrides = if (updated.isDefault) {
                                                previewOverrides - row.stepIndex
                                            } else {
                                                previewOverrides + (row.stepIndex to updated)
                                            }
                                        },
                                        onOpenCompareAndPick = { compareDialogRow = row },
                                        onCommitUpToHere = {
                                            val res = SeedWorkbenchEngine.commitPreviewUpTo(
                                                evaluation = activeEvaluation,
                                                currentPlan = planSteps,
                                                currentOverrides = previewOverrides,
                                                targetStepIndex = row.stepIndex,
                                            )
                                            if (res.error != null) {
                                                actionIsError = true
                                                actionFeedback = res.error
                                            } else {
                                                planSteps = res.planSteps
                                                previewOverrides = res.shiftedOverrides
                                                actionIsError = false
                                                actionFeedback = "已将前 ${row.stepIndex} 步加入培养计划（新增 ${res.addedStepCount} 步）。"
                                            }
                                        },
                                    )
                                }
                            }
                        }

                        "auto" -> {
                            // Rebuilt Auto-Planner (replaces the old 35-textbox 规划后续培养!)
                            item(key = "workbench-auto-config") {
                                val currentLv = activeEvaluation.planEndLevel
                                val unlockedList = (activeEvaluation.planEndUnlocked + activeEvaluation.planEndProfession).sorted()
                                EditorialCard(modifier = Modifier.fillMaxWidth().testTag("workbench-auto-card")) {
                                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text(
                                            "自动寻找最优转职路线（从 Lv.$currentLv 出发）",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )

                                        // Target level quick chips + custom field
                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text("目标等级", style = MaterialTheme.typography.labelLarge)
                                            FlowRow(
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                            ) {
                                                val presets = listOf(
                                                    (currentLv + 5).coerceAtMost(99) to "+5 级",
                                                    (currentLv + 10).coerceAtMost(99) to "+10 级",
                                                    (currentLv + 20).coerceAtMost(99) to "+20 级",
                                                    40 to "Lv.40",
                                                ).filter { it.first > currentLv }.distinctBy { it.first }
                                                presets.forEach { (lv, lbl) ->
                                                    SelectablePill(
                                                        label = "$lbl (Lv.$lv)",
                                                        selected = autoTargetLevelText == lv.toString(),
                                                        onClick = { autoTargetLevelText = lv.toString(); autoResult = null },
                                                    )
                                                }
                                            }
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                                OutlinedTextField(
                                                    value = autoTargetLevelText,
                                                    onValueChange = { autoTargetLevelText = it.filter(Char::isDigit).take(2); autoResult = null },
                                                    label = { Text("自定义目标等级") },
                                                    singleLine = true,
                                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                    modifier = Modifier.weight(1f).testTag("auto-target-level"),
                                                )
                                                Column(Modifier.weight(1.4f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Text("最多转职次数", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                        listOf(98 to "不限", 1 to "1次", 2 to "2次", 3 to "3次").forEach { (cnt, lbl) ->
                                                            SelectablePill(lbl, autoMaxSwitches == cnt) {
                                                                autoMaxSwitches = cnt; autoResult = null
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                                        // Unlocked professions visual strip + dialog button
                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                Text("候选职业（已选 ${unlockedList.size} 个）", style = MaterialTheme.typography.labelLarge)
                                                TextButton(
                                                    onClick = { unlockedDialogOpen = true },
                                                    modifier = Modifier.heightIn(min = 36.dp).testTag("auto-pick-professions"),
                                                ) {
                                                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                                    Text("勾选职业")
                                                }
                                            }
                                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                unlockedList.forEach { profName ->
                                                    val isCur = profName == activeEvaluation.planEndProfession
                                                    val tier = loaded.professionTier(profName)
                                                    val minLv = if (isCur || !autoRespectTierLevel) {
                                                        currentLv
                                                    } else {
                                                        maxOf(currentLv, autoCustomMinLevels[profName]?.toIntOrNull() ?: BuddyRules.defaultUnlockLevelForTier(tier))
                                                    }
                                                    Surface(
                                                        shape = RoundedCornerShape(10.dp),
                                                        color = if (isCur)
                                                            MaterialTheme.colorScheme.primaryContainer
                                                        else MaterialTheme.colorScheme.surfaceContainerLow,
                                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                                    ) {
                                                        Row(
                                                            Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                                                        ) {
                                                            WikiImage(visuals.image("professions", profName), "$profName", Modifier.size(20.dp), crop = false)
                                                            Text(
                                                                if (isCur) "$profName · 当前"
                                                                else if (minLv > currentLv) "$profName · Lv.$minLv+"
                                                                else profName,
                                                                style = MaterialTheme.typography.labelMedium.tabularNumbers(),
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                            ToggleField(
                                                label = "按阶位等级门槛转职（下级5 / 中级20 / 上级35 / 最上级·神将45）",
                                                checked = autoRespectTierLevel,
                                                onChange = { autoRespectTierLevel = it; autoResult = null },
                                            )
                                        }

                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                                        // Visual 1-tap weight selector (zero textboxes!)
                                        WeightSelectorPanel(
                                            weights = weights,
                                            darkTheme = darkTheme,
                                            onChange = { weights = it; autoResult = null },
                                        )

                                        // Collapsible Advanced Constraints (real stats / caps / minimums / part3)
                                        TextButton(
                                            onClick = { autoAdvancedOpen = !autoAdvancedOpen },
                                            modifier = Modifier.heightIn(min = 36.dp),
                                            contentPadding = PaddingValues(horizontal = 4.dp),
                                        ) {
                                            Icon(Icons.Outlined.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.size(4.dp))
                                            Text(if (autoAdvancedOpen) "收起高级约束（实测属性 / 上限 / 保底）" else "高级约束（可选：实测属性 / 上限 / 保底）")
                                        }

                                        if (autoAdvancedOpen) {
                                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                val nonCurrentUnlocked = unlockedList.filter { it != activeEvaluation.planEndProfession }
                                                if (nonCurrentUnlocked.isNotEmpty()) {
                                                    Text("自定义职业最早可转等级（留空按阶位默认）", style = MaterialTheme.typography.labelMedium)
                                                    nonCurrentUnlocked.forEach { profName ->
                                                        val defaultMin = BuddyRules.defaultUnlockLevelForTier(loaded.professionTier(profName))
                                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                                            Text(profName, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                                            OutlinedTextField(
                                                                value = autoCustomMinLevels[profName].orEmpty(),
                                                                onValueChange = { v ->
                                                                    val digits = v.filter(Char::isDigit).take(2)
                                                                    autoCustomMinLevels = if (digits.isEmpty()) autoCustomMinLevels - profName
                                                                    else autoCustomMinLevels + (profName to digits)
                                                                    autoResult = null
                                                                },
                                                                label = { Text("默认 Lv.$defaultMin") },
                                                                singleLine = true,
                                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                                modifier = Modifier.weight(1f),
                                                            )
                                                        }
                                                    }
                                                }
                                                if (!effectivePart3) {
                                                    OutlinedTextField(
                                                        value = autoPart3StartText,
                                                        onValueChange = { autoPart3StartText = it.filter(Char::isDigit).take(2); autoResult = null },
                                                        label = { Text("预计进入第三部等级（留空表示前篇）") },
                                                        singleLine = true,
                                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                        modifier = Modifier.fillMaxWidth(),
                                                    )
                                                }
                                                Text(
                                                    "无需填满；仅在需要考虑属性封顶或最低达标值时填写。",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                                GrowthStat.entries.forEachIndexed { idx, stat ->
                                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                                        Text(stat.label, Modifier.widthIn(min = 36.dp), style = MaterialTheme.typography.labelMedium)
                                                        OutlinedTextField(
                                                            value = autoBaseStatsText.split(",").getOrElse(idx) { "" },
                                                            onValueChange = { autoBaseStatsText = setCsvSlot(autoBaseStatsText, idx, it.filter(Char::isDigit).take(3)); autoResult = null },
                                                            label = { Text("当前") },
                                                            singleLine = true,
                                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                            modifier = Modifier.weight(1f),
                                                        )
                                                        OutlinedTextField(
                                                            value = autoCapsText.split(",").getOrElse(idx) { "" },
                                                            onValueChange = { autoCapsText = setCsvSlot(autoCapsText, idx, it.filter(Char::isDigit).take(3)); autoResult = null },
                                                            label = { Text("上限") },
                                                            singleLine = true,
                                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                            modifier = Modifier.weight(1f),
                                                        )
                                                        OutlinedTextField(
                                                            value = autoMinimumsText.split(",").getOrElse(idx) { "" },
                                                            onValueChange = { autoMinimumsText = setCsvSlot(autoMinimumsText, idx, it.filter(Char::isDigit).take(3)); autoResult = null },
                                                            label = { Text("最低") },
                                                            singleLine = true,
                                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                            modifier = Modifier.weight(1f),
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        // Primary CTA
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(
                                                onClick = ::runAutoPlanner,
                                                enabled = !autoBusy && currentLv < 99,
                                                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("auto-plan-run"),
                                            ) {
                                                if (autoBusy) {
                                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                                    Spacer(Modifier.size(8.dp))
                                                    Text("正在寻优…")
                                                } else {
                                                    Text(if (autoResult != null) "重新规划最优路线" else "生成最优转职方案")
                                                }
                                            }
                                            if (autoBusy) {
                                                OutlinedButton(
                                                    onClick = { autoCancellation?.set(true) },
                                                    modifier = Modifier.heightIn(min = 48.dp),
                                                ) { Text("取消") }
                                            }
                                        }
                                        autoError?.let {
                                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }

                            // Auto-Planner Results
                            autoResult?.let { planRes ->
                                item(key = "workbench-auto-summary") {
                                    val totalGains = List(9) { i -> planRes.steps.sumOf { it.gains.getOrElse(i) { 0 } } }
                                    val switchCount = planRes.steps.count { it.switchFrom != null }
                                    EditorialCard(modifier = Modifier.fillMaxWidth().testTag("auto-plan-result")) {
                                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                Text(planRes.reason, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                                                EditorialBadge(
                                                    text = "总涨 +${totalGains.sum()}",
                                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                                )
                                            }
                                            if (planRes.hasPlan) {
                                                Text(
                                                    "共 ${planRes.steps.size} 次升级 · 转职 $switchCount 次 · 探索 ${planRes.exploredStates} 个状态",
                                                    style = MaterialTheme.typography.bodySmall.tabularNumbers(),
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                                NineStatStrip(values = totalGains, weights = weights, darkTheme = darkTheme, showPlusSign = true)
                                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    Button(
                                                        onClick = {
                                                            val converted = mutableListOf<SeedWorkbenchPlanStep>()
                                                            var curProf = activeEvaluation.planEndProfession
                                                            planRes.steps.forEach { step ->
                                                                if (step.profession != curProf) {
                                                                    converted += SeedWorkbenchPlanStep(
                                                                        kind = SeedPlanActionKind.CLASS_CHANGE,
                                                                        targetProfession = step.profession,
                                                                    )
                                                                    curProf = step.profession
                                                                }
                                                                converted += SeedWorkbenchPlanStep(kind = SeedPlanActionKind.UPGRADE)
                                                            }
                                                            planSteps = planSteps + converted
                                                            previewOverrides = emptyMap()
                                                            activeTab = "plan"
                                                            actionIsError = false
                                                            actionFeedback = "已将自动规划路线（${converted.size} 步）加入培养计划。"
                                                        },
                                                        modifier = Modifier.weight(1f).heightIn(min = 44.dp).testTag("auto-plan-apply"),
                                                    ) { Text("套用到培养计划") }
                                                    val req = autoRequest
                                                    val digest = initialContext?.journalHistoryDigest
                                                    if (onSaveJournalGrowthPlan != null && req != null && digest != null) {
                                                        OutlinedButton(
                                                            enabled = !savingJournalPlan,
                                                            onClick = {
                                                                runCatching {
                                                                    val jPlan = JournalGrowthPlan(
                                                                        request = req,
                                                                        result = planRes,
                                                                        historyDigest = digest,
                                                                        ruleRevision = initialContext.journalRuleRevision,
                                                                    ).validated()
                                                                    onSaveJournalGrowthPlan(jPlan, req.currentStats, req.caps, req.unlockedProfessions)
                                                                }.onFailure { err ->
                                                                    autoError = err.message ?: "关联保存失败"
                                                                }
                                                            },
                                                            modifier = Modifier.weight(1f).heightIn(min = 44.dp).testTag("auto-plan-save-journal"),
                                                        ) { Text(if (savingJournalPlan) "保存中…" else "关联到记录册") }
                                                    }
                                                }
                                                journalPlanSaveError?.let {
                                                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                                }
                                            }
                                        }
                                    }
                                }
                                itemsIndexed(planRes.steps, key = { _, s -> "auto-${s.level}-${s.seed}" }) { idx, step ->
                                    AutoPlanStepRow(index = idx + 1, step = step, weights = weights, visuals = visuals, darkTheme = darkTheme)
                                }
                            }
                        }

                        else -> {
                            // Plan & Execution Progress Tab (streamlined 3-button controls!)
                            item(key = "workbench-plan-progress") {
                                val clampedDone = completedSteps.coerceIn(0, planSteps.size)
                                val progressRatio = if (planSteps.isEmpty()) 0f else clampedDone.toFloat() / planSteps.size
                                EditorialCard(modifier = Modifier.fillMaxWidth().testTag("workbench-plan-summary")) {
                                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                "Lv.${activeEvaluation.start.startLevel} → Lv.${activeEvaluation.planEndLevel}（${activeEvaluation.planEndProfession}）",
                                                style = MaterialTheme.typography.titleSmall.tabularNumbers(),
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                            EditorialBadge(
                                                text = "已完成 $clampedDone / ${planSteps.size}",
                                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                            )
                                        }
                                        LinearProgressIndicator(
                                            progress = { progressRatio.coerceIn(0f, 1f) },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                        NineStatStrip(
                                            values = activeEvaluation.planTotals,
                                            weights = weights,
                                            darkTheme = darkTheme,
                                            showPlusSign = true,
                                        )
                                        activeEvaluation.planError?.let { err ->
                                            Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                        }
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(
                                                onClick = { completedSteps = (clampedDone + 1).coerceAtMost(planSteps.size) },
                                                enabled = clampedDone < planSteps.size,
                                                modifier = Modifier.weight(1.2f).heightIn(min = 42.dp).testTag("plan-step-complete"),
                                                contentPadding = PaddingValues(horizontal = 8.dp),
                                            ) {
                                                Icon(Icons.Outlined.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.size(4.dp))
                                                Text("完成当前步", maxLines = 1)
                                            }
                                            OutlinedButton(
                                                onClick = { completedSteps = (clampedDone - 1).coerceAtLeast(0) },
                                                enabled = clampedDone > 0,
                                                modifier = Modifier.weight(0.75f).heightIn(min = 42.dp).testTag("plan-step-undo"),
                                                contentPadding = PaddingValues(horizontal = 6.dp),
                                            ) { Text("回退", maxLines = 1) }
                                            OutlinedButton(
                                                onClick = { addActionDialogOpen = true },
                                                modifier = Modifier.weight(1f).heightIn(min = 42.dp).testTag("plan-add-action"),
                                                contentPadding = PaddingValues(horizontal = 8.dp),
                                            ) { Text("+ 考试/转职", maxLines = 1) }
                                        }
                                        if (planSteps.isNotEmpty()) {
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                                TextButton(
                                                    onClick = {
                                                        planSteps = planSteps.dropLast(1)
                                                        completedSteps = completedSteps.coerceAtMost(planSteps.size)
                                                    },
                                                    modifier = Modifier.heightIn(min = 34.dp),
                                                ) { Text("撤销末步") }
                                                TextButton(
                                                    onClick = {
                                                        planSteps = emptyList()
                                                        completedSteps = 0
                                                    },
                                                    modifier = Modifier.heightIn(min = 34.dp),
                                                ) { Text("清空计划") }
                                            }
                                        }
                                    }
                                }
                            }

                            if (activeEvaluation.planRows.isEmpty()) {
                                item(key = "workbench-plan-empty") {
                                    EditorialCard {
                                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text("尚未加入任何计划步骤", style = MaterialTheme.typography.titleSmall)
                                            Text(
                                                "在「乱数预览」点任意行的「加入计划」，或在「自动规划」生成最优转职路线后一键套用。",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                OutlinedButton(onClick = { activeTab = "preview" }) { Text("去乱数预览") }
                                                OutlinedButton(onClick = { activeTab = "auto" }) { Text("去自动规划") }
                                            }
                                        }
                                    }
                                }
                            } else {
                                items(activeEvaluation.planRows, key = { "plan-${it.stepIndex}" }) { planRow ->
                                    val clampedDone = completedSteps.coerceIn(0, planSteps.size)
                                    val stateLabel = when {
                                        planRow.stepIndex <= clampedDone -> "已完成"
                                        planRow.stepIndex == clampedDone + 1 -> "待执行"
                                        else -> "后续"
                                    }
                                    WorkbenchPlanRowCard(
                                        row = planRow,
                                        stateLabel = stateLabel,
                                        isDone = planRow.stepIndex <= clampedDone,
                                        isCurrent = planRow.stepIndex == clampedDone + 1,
                                        weights = weights,
                                        darkTheme = darkTheme,
                                        visuals = visuals,
                                        onJumpCursorHere = { completedSteps = planRow.stepIndex },
                                    )
                                }
                            }
                        }
                    }
                }

                // Minimal Footer
                item(key = "workbench-footer") {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        TextButton(onClick = { showAlgorithmDetails = !showAlgorithmDetails }) {
                            Text(if (showAlgorithmDetails) "收起计算说明" else "计算说明")
                        }
                        if (showAlgorithmDetails) {
                            Text(
                                "依据 FE_Rigging_Buddy ${BuddyRules.REVISION.take(8)}；预览与计划均不修改实测记录册，接近属性上限请以游戏内结果为准。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    // Dialog 1: Searchable & Tier-Filtered Unlocked Professions Checklist
    if (unlockedDialogOpen && loaded != null) {
        val allowedProfs = loaded.professions.filter { loaded.classAllowed(characterId, it.name, effectivePart3) }
        var profQuery by remember { mutableStateOf("") }
        var profTier by remember { mutableStateOf<Int?>(null) }
        AlertDialog(
            onDismissRequest = { unlockedDialogOpen = false },
            title = { Text("勾选候选 / 已解锁职业") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        profQuery, { profQuery = it },
                        label = { Text("搜索职业") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    BuddyProfessionTierFilterBar(
                        availableTiers = allowedProfs.map { it.tier },
                        selectedTier = profTier,
                        onSelectTier = { profTier = it },
                    )
                    LazyColumn(Modifier.heightIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(
                            allowedProfs.filter {
                                (profTier == null || it.tier == profTier) &&
                                    it.name.contains(profQuery.trim(), ignoreCase = true)
                            },
                            key = { it.name },
                        ) { prof ->
                            val checked = prof.name in unlockedProfessions || prof.name == profession
                            val minLv = BuddyRules.defaultUnlockLevelForTier(prof.tier)
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Checkbox(
                                    checked = checked,
                                    enabled = prof.name != profession,
                                    onCheckedChange = { isChecked ->
                                        unlockedProfessions = if (isChecked) unlockedProfessions + prof.name
                                        else unlockedProfessions - prof.name
                                    },
                                )
                                WikiImage(visuals.image("professions", prof.name), "${prof.name}图标", Modifier.size(30.dp), crop = false)
                                Column(Modifier.weight(1f)) {
                                    Text(prof.name + if (prof.name == profession) "（当前）" else "", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        if (minLv > 1) "${BuddyRules.tierLabel(prof.tier)} · 推荐 Lv.$minLv 起" else BuddyRules.tierLabel(prof.tier),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { unlockedDialogOpen = false }) { Text("完成") } },
        )
    }

    // Dialog 2: Unified Compare + Pick Trial Profession Dialog (with Tier Filter!)
    val activeCompareRow = compareDialogRow
    if (activeCompareRow != null && loaded != null && activeEvaluation != null) {
        val options = remember(activeCompareRow, activeEvaluation, weights) {
            SeedWorkbenchEngine.compareProfessionsAtRow(
                rules = loaded,
                start = activeEvaluation.start,
                rowSeed = activeCompareRow.seed,
                rowLevel = activeCompareRow.fromLevel,
                currentProfession = activeEvaluation.planEndProfession,
                unlockedProfessions = activeEvaluation.planEndUnlocked,
                weights = weights,
            )
        }
        var searchQuery by remember { mutableStateOf("") }
        var onlyUnlocked by remember { mutableStateOf(false) }
        var compareTier by remember { mutableStateOf<Int?>(null) }
        val visibleOptions = options.filter {
            (!onlyUnlocked || it.unlocked) &&
                (compareTier == null || loaded.professionTier(it.profession) == compareTier) &&
                it.profession.contains(searchQuery.trim(), ignoreCase = true)
        }
        AlertDialog(
            onDismissRequest = { compareDialogRow = null },
            title = { Text("换职对比 · Lv.${activeCompareRow.fromLevel}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            searchQuery, { searchQuery = it },
                            label = { Text("搜职业") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        SelectablePill("仅已解锁", onlyUnlocked) { onlyUnlocked = !onlyUnlocked }
                    }
                    BuddyProfessionTierFilterBar(
                        availableTiers = options.map { loaded.professionTier(it.profession) },
                        selectedTier = compareTier,
                        onSelectTier = { compareTier = it },
                    )
                    if (activeCompareRow.trialStatus != TrialProfessionStatus.CURRENT) {
                        OutlinedButton(
                            onClick = {
                                val cur = previewOverrides[activeCompareRow.stepIndex] ?: SeedPreviewOverride()
                                val next = cur.copy(trialProfession = null)
                                previewOverrides = if (next.isDefault) previewOverrides - activeCompareRow.stepIndex
                                else previewOverrides + (activeCompareRow.stepIndex to next)
                                compareDialogRow = null
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 38.dp),
                        ) { Text("恢复默认职业（${activeEvaluation.planEndProfession}）") }
                    }
                    LazyColumn(
                        Modifier.fillMaxWidth().heightIn(max = 380.dp).testTag("same-seed-compare-list"),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(visibleOptions, key = { it.profession }) { opt ->
                            SameSeedOptionCard(
                                option = opt,
                                weights = weights,
                                hasActiveWeights = hasActiveWeights,
                                visuals = visuals,
                                darkTheme = darkTheme,
                                onApplyTrial = { unlockToo ->
                                    if (unlockToo) unlockedProfessions = unlockedProfessions + opt.profession
                                    val cur = previewOverrides[activeCompareRow.stepIndex] ?: SeedPreviewOverride()
                                    val next = cur.copy(
                                        skipOrExam = false,
                                        trialProfession = if (opt.profession == activeEvaluation.planEndProfession) null else opt.profession,
                                    )
                                    previewOverrides = if (next.isDefault) previewOverrides - activeCompareRow.stepIndex
                                    else previewOverrides + (activeCompareRow.stepIndex to next)
                                    compareDialogRow = null
                                    actionIsError = false
                                    actionFeedback = "第 ${activeCompareRow.stepIndex} 步已切换为「${opt.profession}」（+${opt.totalGain}）" +
                                        if (unlockToo) "并标记解锁。" else "。"
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { compareDialogRow = null }) { Text("关闭") } },
        )
    }

    // Dialog 3: Unified Add Exam / Class Change Action Dialog
    if (addActionDialogOpen && loaded != null && activeEvaluation != null) {
        val allowedProfs = loaded.professions.filter { loaded.classAllowed(characterId, it.name, effectivePart3) }
        val classChoices = activeEvaluation.planEndUnlocked.filter { it != activeEvaluation.planEndProfession }.sorted()
        var mode by remember { mutableStateOf("exam") } // exam | class
        var examTarget by remember { mutableStateOf(allowedProfs.firstOrNull { it.name !in activeEvaluation.planEndUnlocked }?.name ?: profession) }
        var examProbText by remember { mutableStateOf("100") }
        var classTarget by remember { mutableStateOf(classChoices.firstOrNull().orEmpty()) }

        AlertDialog(
            onDismissRequest = { addActionDialogOpen = false },
            title = { Text("添加计划动作") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SelectablePill("考试（推进 1 步）", mode == "exam", Modifier.weight(1f)) { mode = "exam" }
                        SelectablePill("转职（不推进）", mode == "class", Modifier.weight(1f)) { mode = "class" }
                    }
                    if (mode == "exam") {
                        IllustratedChoice(
                            "考试目标职业", examTarget, examTarget,
                            allowedProfs.map { it.name to it.name }, "professions", visuals,
                        ) { examTarget = it }
                        OutlinedTextField(
                            examProbText, { examProbText = it.filter(Char::isDigit).take(3) },
                            label = { Text("成功率（1–100%）") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        if (classChoices.isEmpty()) {
                            Text("暂无其他已解锁职业，请先勾选职业或添加考试。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            IllustratedChoice("目标职业", classTarget, classTarget, classChoices.map { it to it }, "professions", visuals) {
                                classTarget = it
                            }
                        }
                    }
                }
            },
            confirmButton = {
                val prob = examProbText.toDoubleOrNull()?.takeIf { it in 0.0..100.0 }
                val canSubmit = if (mode == "exam") prob != null && examTarget.isNotBlank() else classTarget.isNotBlank()
                TextButton(
                    enabled = canSubmit,
                    onClick = {
                        if (mode == "exam") {
                            planSteps = planSteps + SeedWorkbenchPlanStep(
                                kind = SeedPlanActionKind.EXAM,
                                targetProfession = examTarget,
                                examProbability = prob,
                            )
                            previewOverrides = buildMap {
                                previewOverrides.forEach { (step, ov) ->
                                    if (step > 1 && !ov.isDefault) put(step - 1, ov)
                                }
                            }
                        } else {
                            planSteps = planSteps + SeedWorkbenchPlanStep(
                                kind = SeedPlanActionKind.CLASS_CHANGE,
                                targetProfession = classTarget,
                            )
                        }
                        addActionDialogOpen = false
                    },
                ) { Text("加入计划") }
            },
            dismissButton = { TextButton(onClick = { addActionDialogOpen = false }) { Text("取消") } },
        )
    }
}

@Composable
internal fun WeightSelectorPanel(
    weights: List<Int>,
    darkTheme: Boolean,
    onChange: (List<Int>) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Text("属性偏好（点按 0/1/3/5）", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            if (weights.any { it > 0 }) {
                TextButton(
                    onClick = { onChange(SeedWorkbenchEngine.DEFAULT_WEIGHTS) },
                    modifier = Modifier.heightIn(min = 32.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp),
                ) { Text("清空") }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            WeightPresetChip("力量优先") { onChange(listOf(1, 5, 0, 2, 3, 0, 1, 0, 0)) }
            WeightPresetChip("魔力优先") { onChange(listOf(1, 0, 5, 2, 3, 0, 0, 2, 0)) }
            WeightPresetChip("速技优先") { onChange(listOf(0, 2, 0, 4, 5, 1, 0, 0, 0)) }
            WeightPresetChip("均衡") { onChange(List(9) { 2 }) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            GrowthStat.entries.forEach { stat ->
                val w = weights.getOrElse(stat.ordinal) { 0 }
                val tone = growthStatTone(stat, darkTheme)
                val bg = when {
                    w >= 3 -> tone.accent.copy(alpha = if (darkTheme) 0.34f else 0.22f)
                    w >= 1 -> tone.container
                    else -> MaterialTheme.colorScheme.surfaceContainerLow
                }
                val border = when {
                    w >= 3 -> BorderStroke(1.5.dp, tone.accent)
                    w >= 1 -> BorderStroke(1.dp, tone.accent.copy(alpha = 0.55f))
                    else -> BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                }
                Surface(
                    onClick = {
                        val next = when (w) {
                            0 -> 1
                            1, 2 -> 3
                            3, 4 -> 5
                            else -> 0
                        }
                        onChange(weights.toMutableList().also { it[stat.ordinal] = next })
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp).testTag("workbench-weight:${stat.name}"),
                    shape = RoundedCornerShape(9.dp),
                    color = bg,
                    border = border,
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 1.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            statShortLabel(stat),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (w > 0) tone.content else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (w >= 3) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                        )
                        Text(
                            if (w == 0) "—" else "$w",
                            style = MaterialTheme.typography.labelMedium.tabularNumbers(),
                            color = if (w > 0) tone.content else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (w >= 3) FontWeight.Bold else FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AutoPlanStepRow(
    index: Int,
    step: BuddyGrowthPlanStep,
    weights: List<Int>,
    visuals: BuddyVisuals,
    darkTheme: Boolean,
) {
    EditorialCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Text(
                        "#$index",
                        Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium.tabularNumbers(),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                WikiImage(visuals.image("professions", step.profession), "${step.profession}图标", Modifier.size(30.dp), crop = false)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text("Lv.${step.level} → Lv.${step.level + 1}", style = MaterialTheme.typography.titleSmall.tabularNumbers(), fontWeight = FontWeight.SemiBold)
                    Text(
                        step.profession + (step.switchFrom?.let { "（从 $it 转入）" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (step.switchFrom != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                EditorialBadge(
                    text = "+${step.gains.sum()}",
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            NineStatStrip(values = step.gains, weights = weights, darkTheme = darkTheme, showPlusSign = true)
        }
    }
}

@Composable
private fun WorkbenchPreviewRowCard(
    row: SeedWorkbenchPreviewRow,
    weights: List<Int>,
    hasActiveWeights: Boolean,
    visuals: BuddyVisuals,
    darkTheme: Boolean,
    onToggleSkip: () -> Unit,
    onOpenCompareAndPick: () -> Unit,
    onCommitUpToHere: () -> Unit,
) {
    val borderColor = when {
        row.actionIsSkip -> MaterialTheme.colorScheme.outline
        row.trialStatus == TrialProfessionStatus.LOCKED_TRIAL_ONLY -> MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
        row.trialStatus == TrialProfessionStatus.UNLOCKED_PLANABLE -> MaterialTheme.colorScheme.primary
        row.totalGain >= 5 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    val containerColor = if (row.actionIsSkip) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surface
    Card(
        modifier = Modifier.fillMaxWidth().testTag("preview-row:${row.stepIndex}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(1.dp, borderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Header
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (row.actionIsSkip) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        "#${row.stepIndex}",
                        Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium.tabularNumbers(),
                        fontWeight = FontWeight.Bold,
                        color = if (row.actionIsSkip) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                WikiImage(visuals.image("professions", row.profession), "${row.profession}图标", Modifier.size(30.dp), crop = false)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        if (row.actionIsSkip) "Lv.${row.fromLevel} · 跳过/考试"
                        else "Lv.${row.fromLevel} → Lv.${row.nextLevel}",
                        style = MaterialTheme.typography.titleSmall.tabularNumbers(),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(row.profession, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (row.trialStatus != TrialProfessionStatus.CURRENT) {
                            Surface(
                                shape = RoundedCornerShape(5.dp),
                                color = if (row.trialStatus == TrialProfessionStatus.LOCKED_TRIAL_ONLY)
                                    MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                            ) {
                                Text(
                                    row.trialStatus.label,
                                    Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (row.trialStatus == TrialProfessionStatus.LOCKED_TRIAL_ONLY)
                                        MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!row.actionIsSkip) {
                        EditorialBadge(
                            text = "+${row.totalGain}",
                            containerColor = if (row.totalGain >= 5) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
                            contentColor = if (row.totalGain >= 5) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        if (hasActiveWeights) {
                            EditorialBadge(
                                text = "权${row.weightedScore}",
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                    val easyExam = row.examMinimumProbability <= 50
                    EditorialBadge(
                        text = "考≥${row.examMinimumProbability}%",
                        containerColor = if (easyExam) {
                            if (darkTheme) Color(0xFF1F3B2B) else Color(0xFFE0F2E8)
                        } else MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = if (easyExam) {
                            if (darkTheme) Color(0xFFA7E8C2) else Color(0xFF1A5E3A)
                        } else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 9-stat gains strip
            NineStatStrip(
                values = row.gains,
                weights = weights,
                darkTheme = darkTheme,
                dimmed = row.actionIsSkip,
                showPlusSign = true,
            )

            // SINGLE sleek 3-action strip (merged 试算职业 + 同种子对比 -> 换职对比!)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    onClick = onToggleSkip,
                    modifier = Modifier.weight(1f).heightIn(min = 36.dp).testTag("preview-toggle-skip:${row.stepIndex}"),
                    shape = RoundedCornerShape(10.dp),
                    color = if (row.actionIsSkip) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, if (row.actionIsSkip) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Box(Modifier.padding(horizontal = 6.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
                        Text(
                            if (row.actionIsSkip) "取消跳过" else "跳过/考试",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (row.actionIsSkip) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                        )
                    }
                }
                Surface(
                    onClick = onOpenCompareAndPick,
                    modifier = Modifier.weight(1.1f).heightIn(min = 36.dp).testTag("preview-compare-classes:${row.stepIndex}"),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Row(
                        Modifier.padding(horizontal = 6.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.CompareArrows, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.size(3.dp))
                        Text("换职对比", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                }
                Surface(
                    onClick = onCommitUpToHere,
                    modifier = Modifier.weight(1f).heightIn(min = 36.dp).testTag("preview-commit:${row.stepIndex}"),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    Box(Modifier.padding(horizontal = 6.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "加入计划",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                }
            }

            // Subdued 1-line footer
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "种子 ${row.seed} → ${row.nextSeed}",
                    style = MaterialTheme.typography.labelSmall.tabularNumbers(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "累计 +${row.cumulativeGainsFromStart.sum()}" +
                        if (row.rideDisabledByTrialClass) " · 无坐骑加成" else "",
                    style = MaterialTheme.typography.labelSmall.tabularNumbers(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun WorkbenchPlanRowCard(
    row: SeedEvaluatedPlanRow,
    stateLabel: String,
    isDone: Boolean,
    isCurrent: Boolean,
    weights: List<Int>,
    darkTheme: Boolean,
    visuals: BuddyVisuals,
    onJumpCursorHere: () -> Unit,
) {
    val borderColor = when {
        row.error != null -> MaterialTheme.colorScheme.error
        isCurrent -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    Card(
        modifier = Modifier.fillMaxWidth().testTag("plan-row:${row.stepIndex}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            else MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(if (isCurrent) 1.5.dp else 1.dp, borderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = when {
                        isDone -> MaterialTheme.colorScheme.secondaryContainer
                        isCurrent -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                ) {
                    Text(
                        "${row.stepIndex}",
                        Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium.tabularNumbers(),
                        color = when {
                            isDone -> MaterialTheme.colorScheme.onSecondaryContainer
                            isCurrent -> MaterialTheme.colorScheme.onPrimary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontWeight = FontWeight.Bold,
                    )
                }
                WikiImage(visuals.image("professions", row.professionAfter), "${row.professionAfter}图标", Modifier.size(30.dp), crop = false)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text("${row.kind.label} · ${row.statusNote}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${row.professionAfter} · 种子 ${row.seedBefore} → ${row.seedAfter}",
                        style = MaterialTheme.typography.labelSmall.tabularNumbers(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                EditorialBadge(
                    text = stateLabel,
                    containerColor = when {
                        isDone -> MaterialTheme.colorScheme.secondaryContainer
                        isCurrent -> MaterialTheme.colorScheme.primaryContainer
                        else -> MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    contentColor = when {
                        isDone -> MaterialTheme.colorScheme.onSecondaryContainer
                        isCurrent -> MaterialTheme.colorScheme.onPrimaryContainer
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (row.kind == SeedPlanActionKind.UPGRADE) {
                NineStatStrip(values = row.gains, weights = weights, darkTheme = darkTheme, showPlusSign = true)
            }
            row.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "至本步累计 +${row.cumulativeGains.sum()}",
                    style = MaterialTheme.typography.labelSmall.tabularNumbers(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onJumpCursorHere, modifier = Modifier.heightIn(min = 34.dp)) {
                    Text(if (isDone) "已走过" else "标记至此")
                }
            }
        }
    }
}

@Composable
private fun SameSeedOptionCard(
    option: SameSeedClassOption,
    weights: List<Int>,
    hasActiveWeights: Boolean,
    visuals: BuddyVisuals,
    darkTheme: Boolean,
    onApplyTrial: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("same-seed-option:${option.profession}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(
            1.dp,
            if (option.isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WikiImage(visuals.image("professions", option.profession), "${option.profession}图标", Modifier.size(30.dp), crop = false)
                Column(Modifier.weight(1f)) {
                    Text(option.profession, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        when {
                            option.isCurrent -> "当前职业"
                            option.unlocked -> "已解锁"
                            else -> "未解锁"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (option.unlocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                EditorialBadge(
                    text = "+${option.totalGain}",
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                if (hasActiveWeights) {
                    EditorialBadge(
                        text = "权${option.weightedScore}",
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            NineStatStrip(values = option.gains, weights = weights, darkTheme = darkTheme, showPlusSign = true)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onApplyTrial(false) },
                    modifier = Modifier.weight(1f).heightIn(min = 36.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) { Text("套用试算") }
                if (!option.unlocked) {
                    Button(
                        onClick = { onApplyTrial(true) },
                        modifier = Modifier.weight(1f).heightIn(min = 36.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) { Text("解锁并套用") }
                }
            }
        }
    }
}

@Composable
internal fun NineStatStrip(
    values: List<Int>,
    weights: List<Int>,
    darkTheme: Boolean,
    dimmed: Boolean = false,
    showPlusSign: Boolean = true,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        GrowthStat.entries.forEach { stat ->
            val gain = values.getOrElse(stat.ordinal) { 0 }
            val w = weights.getOrElse(stat.ordinal) { 0 }
            val tone = growthStatTone(stat, darkTheme)
            val hasGain = gain > 0
            val container = when {
                !hasGain -> MaterialTheme.colorScheme.surfaceContainerLow
                dimmed -> tone.container.copy(alpha = 0.45f)
                w >= 3 -> tone.accent.copy(alpha = if (darkTheme) 0.38f else 0.24f)
                w >= 1 -> tone.container
                else -> tone.container.copy(alpha = 0.72f)
            }
            val border = when {
                !hasGain -> BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                w >= 3 && !dimmed -> BorderStroke(1.5.dp, tone.accent)
                else -> BorderStroke(1.dp, tone.accent.copy(alpha = if (dimmed) 0.25f else 0.5f))
            }
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                color = container,
                border = border,
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 1.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        statShortLabel(stat),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (hasGain) tone.content else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Text(
                        text = if (hasGain) (if (showPlusSign) "+$gain" else "$gain") else "·",
                        style = MaterialTheme.typography.labelMedium.tabularNumbers(),
                        fontWeight = if (hasGain) FontWeight.Bold else FontWeight.Normal,
                        color = if (hasGain) tone.content else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        textDecoration = if (dimmed && hasGain) TextDecoration.LineThrough else TextDecoration.None,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun ExtraAdvanceStepper(
    valueText: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = valueText.toIntOrNull() ?: 0
    OutlinedTextField(
        value = valueText,
        onValueChange = { onValueChange(it.filter(Char::isDigit).take(4)) },
        modifier = modifier.testTag("workbench-extra-advances"),
        label = { Text("额外推进次数（未记录的考试/跳过）") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { onValueChange((current - 1).coerceAtLeast(0).toString()) },
                    enabled = current > 0,
                    contentPadding = PaddingValues(horizontal = 6.dp),
                ) { Text("−") }
                TextButton(
                    onClick = { onValueChange((current + 1).coerceAtMost(SeedWorkbenchEngine.MAX_PRE_ADVANCES).toString()) },
                    contentPadding = PaddingValues(horizontal = 6.dp),
                ) { Text("+") }
            }
        },
    )
}

@Composable
private fun HorizonChipRow(selectedHorizon: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(20 to "未来 20 步", 50 to "未来 50 步", 98 to "预览至 99 级").forEach { (h, label) ->
            SelectablePill(
                label = label,
                selected = selectedHorizon == h,
                modifier = Modifier.weight(1f),
                testTag = "workbench-horizon:$h",
                onClick = { onSelect(h) },
            )
        }
    }
}

@Composable
private fun WeightPresetChip(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(100.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.heightIn(min = 32.dp),
    ) {
        Box(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
internal fun SelectablePill(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    testTag: String? = null,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = 36.dp)
            .semantics { this.selected = selected }
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        shape = RoundedCornerShape(100.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

internal fun statShortLabel(stat: GrowthStat): String = when (stat) {
    GrowthStat.HP -> "HP"
    GrowthStat.STRENGTH -> "力"
    GrowthStat.MAGIC -> "魔"
    GrowthStat.DEXTERITY -> "技"
    GrowthStat.SPEED -> "速"
    GrowthStat.LUCK -> "幸"
    GrowthStat.DEFENSE -> "防"
    GrowthStat.RESISTANCE -> "抗"
    GrowthStat.CHARM -> "魅"
}

@Composable
private fun ForecastGroupCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    EditorialCard(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun ToggleField(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                uncheckedBorderColor = MaterialTheme.colorScheme.outline,
            ),
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

@Composable
internal fun ChoiceField(label: String, selected: String, choices: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        Text("$label：$selected", maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (open) {
        var query by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("选择$label") },
            confirmButton = { TextButton(onClick = { open = false }) { Text("取消") } },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (choices.size > 8) OutlinedTextField(query, { query = it }, label = { Text("搜索$label") }, singleLine = true)
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(choices.filter { it.second.contains(query.trim(), ignoreCase = true) }, key = { it.first }) { choice ->
                            TextButton(onClick = { onSelect(choice.first); open = false }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text(choice.second)
                            }
                        }
                    }
                }
            },
        )
    }
}
