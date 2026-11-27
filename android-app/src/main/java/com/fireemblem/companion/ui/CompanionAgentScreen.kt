package com.fireemblem.companion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fireemblem.companion.agent.AgentClientFactory
import com.fireemblem.companion.agent.AgentEngageProposal
import com.fireemblem.companion.agent.AgentLlmClient
import com.fireemblem.companion.agent.AgentMemoryEntry
import com.fireemblem.companion.agent.AgentMemoryStore
import com.fireemblem.companion.agent.AgentModelRegistry
import com.fireemblem.companion.agent.AgentPlanProposal
import com.fireemblem.companion.agent.AgentSessionDocument
import com.fireemblem.companion.agent.AgentSessionMeta
import com.fireemblem.companion.agent.AgentSessionStore
import com.fireemblem.companion.agent.AgentThreeHousesProposal
import com.fireemblem.companion.agent.AgentToolTrace
import com.fireemblem.companion.agent.AgentTurnMessage
import com.fireemblem.companion.agent.AgentWikiCitation
import com.fireemblem.companion.agent.AgentWikiSearchHit
import com.fireemblem.companion.agent.CompanionAgentOrchestrator
import com.fireemblem.companion.agent.CompanionAgentTools
import com.fireemblem.companion.agent.CompanionToolExecutor
import com.fireemblem.companion.agent.CrossGameWikiRetriever
import com.fireemblem.companion.agent.PersistedAgentBubble
import com.fireemblem.companion.agent.PersistedEngageProposalSpec
import com.fireemblem.companion.agent.PersistedProposalSpec
import com.fireemblem.companion.agent.PersistedThreeHousesProposalSpec
import com.fireemblem.companion.agent.SecureAgentSettings
import com.fireemblem.companion.content.CompanionGame
import com.fireemblem.companion.content.StatRatingTier
import com.fireemblem.companion.content.WikiRepository
import com.fireemblem.companion.engine.BuddyCampaignLine
import com.fireemblem.companion.engine.BuddyRules
import com.fireemblem.companion.engine.EngageCharacterData
import com.fireemblem.companion.engine.EngageClassData
import com.fireemblem.companion.engine.GrowthStat
import com.fireemblem.companion.engine.LevelJournal
import com.fireemblem.companion.engine.ParetoGrowthPlanRequest
import com.fireemblem.companion.engine.ParetoGrowthPlanner
import com.fireemblem.companion.engine.ParetoPlanTier
import com.fireemblem.companion.engine.SeedEngine
import com.fireemblem.companion.engine.SeedWorkbenchEngine
import com.fireemblem.companion.engine.SeedWorkbenchRecord
import com.fireemblem.companion.engine.ThreeHousesCharacterData
import com.fireemblem.companion.engine.ThreeHousesClassData
import com.fireemblem.companion.personal.PersonalStore
import com.fireemblem.companion.ui.theme.tabularNumbers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class AgentChatBubble(
    val id: Int,
    val isUser: Boolean,
    val text: String,
    val toolTraces: List<AgentToolTrace> = emptyList(),
    val citations: List<AgentWikiCitation> = emptyList(),
    val proposals: List<AgentPlanProposal> = emptyList(),
    val threeHousesProposals: List<AgentThreeHousesProposal> = emptyList(),
    val engageProposals: List<AgentEngageProposal> = emptyList(),
    val isError: Boolean = false,
) {
    fun toPersisted(): PersistedAgentBubble = PersistedAgentBubble(
        id = id,
        isUser = isUser,
        text = text,
        toolTraces = toolTraces,
        citations = citations,
        proposalSpecs = proposals.map(PersistedProposalSpec::fromProposal),
        threeHousesSpecs = threeHousesProposals.map(PersistedThreeHousesProposalSpec::fromProposal),
        engageSpecs = engageProposals.map(PersistedEngageProposalSpec::fromProposal),
        isError = isError,
    )

    companion object {
        fun fromPersisted(
            p: PersistedAgentBubble,
            rules: BuddyRules?,
            retriever: CrossGameWikiRetriever? = null,
        ): AgentChatBubble = AgentChatBubble(
            id = p.id,
            isUser = p.isUser,
            text = p.text,
            toolTraces = p.toolTraces,
            citations = p.citations,
            proposals = if (rules != null) p.proposalSpecs.mapNotNull { it.rehydrate(rules) } else emptyList(),
            threeHousesProposals = p.threeHousesSpecs.mapNotNull { it.rehydrate(retriever) },
            engageProposals = p.engageSpecs.mapNotNull { it.rehydrate(retriever) },
            isError = p.isError,
        )
    }
}

internal data class AgentSavedSeedQuickPick(
    val label: String,
    val characterId: String,
    val characterName: String,
    val line: BuddyCampaignLine,
    val part3: Boolean,
    val seed: Long,
    val level: Int,
    val profession: String,
    val unlockedProfessions: Set<String>,
    val stats: List<Int>,
)

@Stable
internal class CompanionAgentSessionHolder {
    var activeMode by mutableStateOf<String?>(null)
    var activeModeInitialized by mutableStateOf(false)
    var activeSessionId by mutableStateOf<String?>(null)
    var activeSessionTitle by mutableStateOf("新对话")
    var activeSessionCreatedAt by mutableStateOf(0L)
    var activeParentSessionId by mutableStateOf<String?>(null)
    var compactedSummary by mutableStateOf<String?>(null)
    val bubbles = mutableStateListOf<AgentChatBubble>()
    val llmHistory = mutableListOf<AgentTurnMessage>()
    var nextBubbleId by mutableIntStateOf(1)
    var sessionHydrated by mutableStateOf(false)
    var lastAutoMemoryBubbleCount by mutableIntStateOf(0)

    // Scroll & expanded preview state preserved across Wiki citation & Workbench round-trips
    var chatListIndex by mutableIntStateOf(0)
    var chatListOffset by mutableIntStateOf(0)
    var offlineListIndex by mutableIntStateOf(0)
    var offlineListOffset by mutableIntStateOf(0)
    val expandedProposalSteps = mutableStateMapOf<String, Boolean>()

    // Offline tool state
    var offlineQuery by mutableStateOf("")
    var offlineGameFilter by mutableStateOf("all")
    var offlinePlannerGame by mutableStateOf<String?>(null)
    var offlineHits by mutableStateOf<List<AgentWikiSearchHit>>(emptyList())
    var offlineSearchDone by mutableStateOf(false)
    var offlineProposal by mutableStateOf<AgentPlanProposal?>(null)
    var offlineThreeHousesProposal by mutableStateOf<AgentThreeHousesProposal?>(null)
    var offlineEngageProposal by mutableStateOf<AgentEngageProposal?>(null)
    var offlinePlannerError by mutableStateOf<String?>(null)

    fun loadFromDocument(
        doc: AgentSessionDocument,
        rules: BuddyRules?,
        retriever: CrossGameWikiRetriever? = null,
        resetScroll: Boolean = true,
    ) {
        activeSessionId = doc.id
        activeSessionTitle = doc.title
        activeSessionCreatedAt = doc.createdAtMillis
        activeParentSessionId = doc.parentSessionId
        compactedSummary = doc.compactedSummary
        bubbles.clear()
        bubbles.addAll(doc.bubbles.map { AgentChatBubble.fromPersisted(it, rules, retriever) })
        llmHistory.clear()
        llmHistory.addAll(doc.llmHistory)
        nextBubbleId = (bubbles.maxOfOrNull { it.id } ?: 0) + 1
        lastAutoMemoryBubbleCount = bubbles.size
        if (resetScroll) {
            chatListIndex = 0
            chatListOffset = 0
        }
        sessionHydrated = true
    }

    fun toDocument(): AgentSessionDocument {
        val now = System.currentTimeMillis()
        return AgentSessionDocument(
            id = activeSessionId ?: java.util.UUID.randomUUID().toString(),
            title = activeSessionTitle,
            createdAtMillis = activeSessionCreatedAt.takeIf { it > 0L } ?: now,
            updatedAtMillis = now,
            parentSessionId = activeParentSessionId,
            compactedSummary = compactedSummary,
            bubbles = bubbles.map(AgentChatBubble::toPersisted),
            llmHistory = llmHistory.toList(),
        )
    }
}

@Composable
internal fun CompanionAgentScreen(
    activeGame: CompanionGame,
    fwRepository: WikiRepository?,
    session: CompanionAgentSessionHolder = remember { CompanionAgentSessionHolder() },
    onBack: () -> Unit,
    onOpenWikiCitation: (AgentWikiCitation) -> Unit,
    onOpenWorkbench: (SeedWorkbenchLaunchContext) -> Unit,
    llmClientOverride: AgentLlmClient? = null,
    initialModeOverride: String? = null,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val secureSettings = remember(context) { SecureAgentSettings(context) }
    val sessionStore = remember(context) { AgentSessionStore(context) }
    val memoryStore = remember(context) { AgentMemoryStore(context) }

    val retriever = remember(context, fwRepository) {
        CrossGameWikiRetriever(context.applicationContext, borrowedFwRepository = fwRepository)
    }
    DisposableEffect(retriever) {
        onDispose { retriever.close() }
    }

    var registry by remember { mutableStateOf(AgentModelRegistry()) }
    var configLoaded by remember { mutableStateOf(false) }
    var activeMode by remember(initialModeOverride) {
        mutableStateOf(initialModeOverride ?: session.activeMode ?: "offline")
    }
    var autoMemoryEnabled by remember(context) { mutableStateOf(AgentMemoryStore.isAutoMemoryEnabled(context)) }
    var autoCompactEnabled by remember(context) { mutableStateOf(AgentMemoryStore.isAutoCompactEnabled(context)) }

    // Scroll states hydrated from hoisted session holder so Wiki/Workbench return restores exact position
    val chatListState = remember(session.activeSessionId) {
        LazyListState(session.chatListIndex, session.chatListOffset)
    }
    val offlineListState = remember {
        LazyListState(session.offlineListIndex, session.offlineListOffset)
    }

    LaunchedEffect(chatListState) {
        snapshotFlow { chatListState.firstVisibleItemIndex to chatListState.firstVisibleItemScrollOffset }
            .collect { (idx, off) ->
                session.chatListIndex = idx
                session.chatListOffset = off
            }
    }
    LaunchedEffect(offlineListState) {
        snapshotFlow { offlineListState.firstVisibleItemIndex to offlineListState.firstVisibleItemScrollOffset }
            .collect { (idx, off) ->
                session.offlineListIndex = idx
                session.offlineListOffset = off
            }
    }
    DisposableEffect(chatListState, offlineListState) {
        onDispose {
            session.chatListIndex = chatListState.firstVisibleItemIndex
            session.chatListOffset = chatListState.firstVisibleItemScrollOffset
            session.offlineListIndex = offlineListState.firstVisibleItemIndex
            session.offlineListOffset = offlineListState.firstVisibleItemScrollOffset
        }
    }

    fun snapshotScrollNow() {
        session.activeMode = activeMode
        session.chatListIndex = chatListState.firstVisibleItemIndex
        session.chatListOffset = chatListState.firstVisibleItemScrollOffset
        session.offlineListIndex = offlineListState.firstVisibleItemIndex
        session.offlineListOffset = offlineListState.firstVisibleItemScrollOffset
    }

    val openCitationPreservingScroll: (AgentWikiCitation) -> Unit = { cite ->
        snapshotScrollNow()
        onOpenWikiCitation(cite)
    }

    // Persistent multi-session & memory state
    var sessionMetas by remember { mutableStateOf<List<AgentSessionMeta>>(emptyList()) }
    var memories by remember { mutableStateOf<List<AgentMemoryEntry>>(emptyList()) }
    var starterPrompts by remember { mutableStateOf<List<String>>(emptyList()) }

    // Dialogs & menus
    var configDialogOpen by remember { mutableStateOf(false) }
    var historyDialogOpen by remember { mutableStateOf(false) }
    var memoryDialogOpen by remember { mutableStateOf(false) }
    var quickModelMenuOpen by remember { mutableStateOf(false) }
    var charPickerOpen by remember { mutableStateOf(false) }
    var profPickerOpen by remember { mutableStateOf(false) }
    var pendingImportDialog by remember { mutableStateOf<Pair<AgentPlanProposal, Int>?>(null) }

    // Chat state
    var chatInput by remember { mutableStateOf("") }
    var chatBusy by remember { mutableStateOf(false) }
    var memoryBusy by remember { mutableStateOf(false) }
    var streamingText by remember { mutableStateOf("") }
    var activeToolLabel by remember { mutableStateOf<String?>(null) }
    val liveToolTraces = remember { mutableStateListOf<AgentToolTrace>() }
    var activeJob by remember { mutableStateOf<Job?>(null) }

    // Offline planner state (upgraded with exam-skip, promotion-open gating, plus 3H & Engage planners!)
    var rules by remember { mutableStateOf<BuddyRules?>(null) }
    var threeHousesData by remember { mutableStateOf<Pair<List<ThreeHousesCharacterData>, List<ThreeHousesClassData>>?>(null) }
    var engageData by remember { mutableStateOf<Pair<List<EngageCharacterData>, List<EngageClassData>>?>(null) }
    var savedPicks by remember { mutableStateOf<List<AgentSavedSeedQuickPick>>(emptyList()) }
    var selectedCharId by remember { mutableStateOf("c044") }
    var selectedLine by remember { mutableStateOf(BuddyCampaignLine.KAI) }
    var part3 by remember { mutableStateOf(false) }
    var seedText by remember { mutableStateOf("") }
    var fromLevelText by remember { mutableStateOf("20") }
    var toLevelText by remember { mutableStateOf("30") }
    var maxSwitchesText by remember { mutableStateOf("3") }
    var minPromoLevelText by remember { mutableStateOf("") }
    var minTotalGainFilter by remember { mutableStateOf<Int?>(null) }
    var optimizeSkips by remember { mutableStateOf(false) }
    var maxSkipsText by remember { mutableStateOf("0") }
    var selectedProfession by remember { mutableStateOf("") }
    var includeLocked by remember { mutableStateOf(true) }
    var unlockedSet by remember { mutableStateOf<Set<String>>(emptySet()) }
    var weights by remember { mutableStateOf(listOf(1, 5, 1, 2, 3, 1, 1, 1, 1)) }
    var currentStats by remember { mutableStateOf(List(GrowthStat.entries.size) { 0 }) }
    var solvingPareto by remember { mutableStateOf(false) }
    var searchingOffline by remember { mutableStateOf(false) }

    var statusBanner by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }

    fun refreshDynamicPrompts(loadedRules: BuddyRules?, currentMems: List<AgentMemoryEntry>) {
        scope.launch {
            starterPrompts = withContext(Dispatchers.IO) {
                CompanionToolExecutor.buildDynamicStarterPrompts(context, activeGame, loadedRules, currentMems)
            }
        }
    }

    fun persistCurrentSession() {
        val doc = session.toDocument()
        session.activeSessionId = doc.id
        scope.launch(Dispatchers.IO) {
            runCatching { sessionStore.saveSession(doc) }
            val updated = sessionStore.listSessions()
            withContext(Dispatchers.Main) {
                sessionMetas = updated
                val refreshed = updated.firstOrNull { it.id == doc.id }
                if (refreshed != null) session.activeSessionTitle = refreshed.title
            }
        }
    }

    fun startFreshSession() {
        if (chatBusy) return
        scope.launch {
            val doc = withContext(Dispatchers.IO) { sessionStore.createNewSession("新对话") }
            val list = withContext(Dispatchers.IO) { sessionStore.listSessions() }
            session.loadFromDocument(doc, rules)
            sessionMetas = list
            statusIsError = false
            statusBanner = "已开启新对话，旧对话已保存在「历史」中。"
            refreshDynamicPrompts(rules, memories)
        }
    }

    LaunchedEffect(context, activeGame) {
        val loadedRegistry = withContext(Dispatchers.IO) {
            runCatching { secureSettings.loadRegistry() }.getOrDefault(AgentModelRegistry())
        }
        registry = loadedRegistry
        configLoaded = true
        if (initialModeOverride == null && !session.activeModeInitialized) {
            val defaultMode = if (llmClientOverride != null || loadedRegistry.hasAnyReadyProfile) "chat" else "offline"
            activeMode = defaultMode
            session.activeMode = defaultMode
            session.activeModeInitialized = true
        }

        val loadedRules = withContext(Dispatchers.IO) { runCatching { BuddyRules.bundled() }.getOrNull() }
        rules = loadedRules
        if (session.offlinePlannerGame == null) {
            session.offlinePlannerGame = activeGame.id
        }
        if (threeHousesData == null) {
            threeHousesData = withContext(Dispatchers.IO) { runCatching { retriever.threeHousesPlannerData() }.getOrNull() }
        }
        if (engageData == null) {
            engageData = withContext(Dispatchers.IO) { runCatching { retriever.engagePlannerData() }.getOrNull() }
        }
        val loadedMemories = withContext(Dispatchers.IO) { memoryStore.list() }
        memories = loadedMemories
        starterPrompts = withContext(Dispatchers.IO) {
            CompanionToolExecutor.buildDynamicStarterPrompts(context, activeGame, loadedRules, loadedMemories)
        }

        val metas = withContext(Dispatchers.IO) { sessionStore.listSessions() }
        sessionMetas = metas
        if (!session.sessionHydrated) {
            val latestDoc = withContext(Dispatchers.IO) {
                metas.firstOrNull()?.let { sessionStore.loadSession(it.id) }
                    ?: sessionStore.createNewSession("新对话")
            }
            session.loadFromDocument(latestDoc, loadedRules, retriever)
            sessionMetas = withContext(Dispatchers.IO) { sessionStore.listSessions() }
        }

        if (loadedRules != null) {
            val picks = withContext(Dispatchers.IO) { loadSavedSeedPicks(context, loadedRules) }
            savedPicks = picks
            val firstPick = picks.firstOrNull()
            if (firstPick != null && seedText.isBlank()) {
                selectedCharId = firstPick.characterId
                selectedLine = firstPick.line
                part3 = firstPick.part3
                seedText = firstPick.seed.toString()
                fromLevelText = firstPick.level.coerceIn(1, 98).toString()
                toLevelText = (firstPick.level + 10).coerceAtMost(99).toString()
                selectedProfession = firstPick.profession
                unlockedSet = firstPick.unlockedProfessions
                currentStats = firstPick.stats
            } else if (selectedProfession.isBlank()) {
                val ch = loadedRules.characters.firstOrNull { it.id == selectedCharId } ?: loadedRules.characters.first()
                selectedCharId = ch.id
                val legal = loadedRules.professions.firstOrNull { loadedRules.classAllowed(ch.id, it.name, part3 || ch.part3Only) }
                selectedProfession = legal?.name.orEmpty()
            }
        }
    }

    val effectiveReadyForChat = llmClientOverride != null || registry.hasAnyReadyProfile

    fun resolveChatClient(): AgentLlmClient? =
        llmClientOverride ?: registry.activeChatProfile?.toProviderConfig()?.let { AgentClientFactory.create(it) }

    fun resolveMemoryClient(): AgentLlmClient? =
        llmClientOverride ?: registry.activeMemoryProfile?.toProviderConfig()?.let { AgentClientFactory.create(it) }

    fun commitPlanToWorkbench(proposal: AgentPlanProposal, tierIndex: Int) {
        val tier = proposal.report.paretoTiers.getOrNull(tierIndex) ?: proposal.report.recommendedTier
        val report = proposal.report
        scope.launch {
            val savedResult = withContext(Dispatchers.IO) {
                runCatching {
                    val profileId = context.getSharedPreferences("personal-view", 0)
                        .getString("profile", "global") ?: "global"
                    val record = proposal.recordTemplate.copy(
                        profileId = profileId,
                        unlockedProfessions = proposal.recordTemplate.unlockedProfessions +
                            tier.steps.map { it.profession } + report.initialProfession,
                        weights = SeedWorkbenchEngine.normalizeWeights(report.weights),
                        planSteps = tier.workbenchSteps,
                        completedSteps = 0,
                    )
                    val store = PersonalStore(context)
                    val entityId = SeedWorkbenchRecord.entityId(profileId, record.characterId, record.line)
                    val parents = store.snapshot().heads()[entityId].orEmpty().map { it.revisionId }.toSet()
                    store.edit(
                        entityId = entityId,
                        kind = SeedWorkbenchRecord.KIND,
                        payload = record.encode(),
                        deleted = false,
                        expectedParents = parents,
                    )
                    record
                }
            }
            savedResult.onSuccess { record ->
                pendingImportDialog = null
                statusIsError = false
                statusBanner = "已导入「${report.characterName} · ${tier.label}」到成长工作台（${tier.workbenchSteps.size} 步）"
                snapshotScrollNow()
                onOpenWorkbench(
                    SeedWorkbenchLaunchContext(
                        characterId = record.characterId,
                        campaignLine = record.line,
                        part3 = record.part3,
                        seed = record.baseSeed,
                        inferredLevel = record.inferredLevel,
                        profession = record.profession,
                        ride = record.ride,
                        unlockedProfessions = record.unlockedProfessions,
                        sourceLabel = "参谋规划 · ${tier.label}",
                        backLabel = "返回战策参谋",
                        autoRun = true,
                        initialTab = "plan",
                        initialWeights = record.weights,
                        initialPlanSteps = record.planSteps,
                    ),
                )
            }.onFailure { err ->
                pendingImportDialog = null
                statusIsError = true
                statusBanner = "导入成长工作台失败：${err.message ?: "请重试"}"
            }
        }
    }

    fun triggerExtractMemories() {
        val memClient = resolveMemoryClient()
        if (memClient == null || session.bubbles.isEmpty() || memoryBusy) return
        val transcript = session.bubbles.filterNot { it.isError }.joinToString("\n") { b ->
            (if (b.isUser) "玩家：" else "战策参谋：") + b.text
        }
        memoryBusy = true
        scope.launch {
            val res = runCatching {
                AgentMemoryStore.extractMemoriesWithModel(memClient, transcript, memoryStore)
            }
            res.onSuccess { added ->
                val latest = withContext(Dispatchers.IO) { memoryStore.list() }
                memories = latest
                refreshDynamicPrompts(rules, latest)
                statusIsError = false
                statusBanner = if (added.isNotEmpty()) {
                    "记忆模型已提炼 ${added.size} 条长期记忆写入「记忆本」"
                } else {
                    "已整理当前对话，暂无需要新增的长期记忆。"
                }
            }.onFailure { err ->
                statusIsError = true
                statusBanner = "记忆提炼失败：${err.message ?: "请检查记忆模型配置"}"
            }
            memoryBusy = false
        }
    }

    fun triggerCompactContext() {
        if (chatBusy || memoryBusy || session.llmHistory.size <= 4) {
            statusIsError = false
            statusBanner = "当前对话上下文尚短，无需压缩。"
            return
        }
        memoryBusy = true
        scope.launch {
            val res = runCatching {
                AgentMemoryStore.compactHistory(
                    memoryClient = resolveMemoryClient(),
                    existingSummary = session.compactedSummary,
                    history = session.llmHistory.toList(),
                    keepRecentMessages = 6,
                )
            }
            res.onSuccess { (summary, recent) ->
                session.compactedSummary = summary
                session.llmHistory.clear()
                session.llmHistory.addAll(recent)
                persistCurrentSession()
                statusIsError = false
                statusBanner = "已由记忆模型压缩早期上下文（保留最近 ${recent.size} 轮明细，省 Token 更稳健）。"
            }.onFailure { err ->
                statusIsError = true
                statusBanner = "压缩上下文失败：${err.message ?: "请重试"}"
            }
            memoryBusy = false
        }
    }

    fun sendChatPrompt(rawPrompt: String) {
        val text = rawPrompt.trim()
        if (text.isEmpty() || chatBusy) return
        val client = resolveChatClient()
        if (client == null) {
            configDialogOpen = true
            return
        }
        chatInput = ""
        focusManager.clearFocus()
        keyboardController?.hide()
        chatBusy = true
        streamingText = ""
        activeToolLabel = null
        liveToolTraces.clear()
        session.bubbles += AgentChatBubble(id = session.nextBubbleId++, isUser = true, text = text)
        persistCurrentSession()

        activeJob = scope.launch {
            try {
                // 1. Threshold-based automatic context compaction before exceeding window budget
                if (autoCompactEnabled && AgentMemoryStore.shouldAutoCompact(session.compactedSummary, session.llmHistory)) {
                    activeToolLabel = "上下文达到阈值，自动压缩早期历史"
                    val compacted = runCatching {
                        AgentMemoryStore.compactHistory(
                            memoryClient = resolveMemoryClient(),
                            existingSummary = session.compactedSummary,
                            history = session.llmHistory.toList(),
                            keepRecentMessages = 6,
                        )
                    }.getOrNull()
                    if (compacted != null) {
                        session.compactedSummary = compacted.first
                        session.llmHistory.clear()
                        session.llmHistory.addAll(compacted.second)
                        persistCurrentSession()
                        statusIsError = false
                        statusBanner = "上下文达到阈值，已自动压缩早期对话（保留最近 ${compacted.second.size} 条明细）。"
                    }
                    activeToolLabel = null
                }

                val executor = CompanionToolExecutor(context, retriever, memoryStore)
                val orchestrator = CompanionAgentOrchestrator(client, executor)
                val sysPrompt = CompanionAgentTools.buildSystemPrompt(
                    activeGameId = activeGame.id,
                    activeGameTitle = activeGame.title,
                    memories = withContext(Dispatchers.IO) { memoryStore.list() },
                    compactedSummary = session.compactedSummary,
                )
                val result = orchestrator.runTurn(
                    systemPrompt = sysPrompt,
                    priorHistory = session.llmHistory.toList(),
                    userPrompt = text,
                    onTextDelta = { chunk -> streamingText += chunk },
                    onToolStarted = { label -> activeToolLabel = label },
                    onToolFinished = { trace ->
                        liveToolTraces += trace
                        activeToolLabel = null
                    },
                )
                session.llmHistory.clear()
                session.llmHistory.addAll(result.updatedHistory)
                session.bubbles += AgentChatBubble(
                    id = session.nextBubbleId++,
                    isUser = false,
                    text = result.assistantText.ifBlank { "已完成查询，详见下方卡片。" },
                    toolTraces = result.toolTraces,
                    citations = result.citations,
                    proposals = result.planProposals,
                    threeHousesProposals = result.threeHousesProposals,
                    engageProposals = result.engageProposals,
                )
                // Refresh memories in case the model invoked save_memory/forget_memory during the turn
                memories = withContext(Dispatchers.IO) { memoryStore.list() }
                persistCurrentSession()

                // 2. Non-blocking background automatic memory distillation using the dedicated Memory Model
                val bubbleCountNow = session.bubbles.size
                val memClientForBg = resolveMemoryClient()
                if (autoMemoryEnabled && memClientForBg != null &&
                    AgentMemoryStore.shouldAutoExtractMemories(
                        bubblesCount = bubbleCountNow,
                        lastExtractedBubbleCount = session.lastAutoMemoryBubbleCount,
                        latestUserText = text,
                        hadPlanProposal = result.planProposals.isNotEmpty() ||
                            result.threeHousesProposals.isNotEmpty() ||
                            result.engageProposals.isNotEmpty(),
                    )
                ) {
                    session.lastAutoMemoryBubbleCount = bubbleCountNow
                    val transcriptSnapshot = session.bubbles.takeLast(8)
                        .filterNot { it.isError }
                        .joinToString("\n") { b -> (if (b.isUser) "玩家：" else "战策参谋：") + b.text }
                    scope.launch {
                        val added = runCatching {
                            AgentMemoryStore.extractMemoriesWithModel(memClientForBg, transcriptSnapshot, memoryStore)
                        }.getOrDefault(emptyList())
                        if (added.isNotEmpty()) {
                            val latest = withContext(Dispatchers.IO) { memoryStore.list() }
                            memories = latest
                            refreshDynamicPrompts(rules, latest)
                            statusIsError = false
                            statusBanner = "记忆模型已自动提炼 ${added.size} 条长期偏好写入「记忆本」"
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                val partial = streamingText.trim()
                if (partial.isNotEmpty()) {
                    session.bubbles += AgentChatBubble(
                        id = session.nextBubbleId++,
                        isUser = false,
                        text = "$partial\n\n（已停止生成）",
                        toolTraces = liveToolTraces.toList(),
                    )
                    persistCurrentSession()
                }
                throw cancelled
            } catch (err: Exception) {
                session.bubbles += AgentChatBubble(
                    id = session.nextBubbleId++,
                    isUser = false,
                    text = "请求失败：${err.message ?: "请检查网络或模型配置"}",
                    toolTraces = liveToolTraces.toList(),
                    isError = true,
                )
                persistCurrentSession()
            } finally {
                streamingText = ""
                activeToolLabel = null
                liveToolTraces.clear()
                chatBusy = false
                activeJob = null
            }
        }
    }

    if (configDialogOpen) {
        AgentModelRegistryDialog(
            initial = registry,
            secureSettings = secureSettings,
            onDismiss = { configDialogOpen = false },
            onRegistryChanged = { next ->
                registry = next
                if (next.hasAnyReadyProfile) {
                    activeMode = "chat"
                    statusIsError = false
                    statusBanner = "已更新模型配置 · 主对话：${next.activeChatProfile?.shortLabel().orEmpty()}"
                } else if (llmClientOverride == null) {
                    activeMode = "offline"
                    statusIsError = false
                    statusBanner = "已清空模型配置，可继续使用离线快捷功能。"
                }
            },
        )
    }

    if (historyDialogOpen) {
        AgentSessionHistoryDialog(
            sessions = sessionMetas,
            activeSessionId = session.activeSessionId,
            onDismiss = { historyDialogOpen = false },
            onCreateNew = ::startFreshSession,
            onSelect = { targetId ->
                scope.launch {
                    val loaded = withContext(Dispatchers.IO) { sessionStore.loadSession(targetId) }
                    if (loaded != null) {
                        session.loadFromDocument(loaded, rules, retriever)
                        statusIsError = false
                        statusBanner = "已切换至对话「${loaded.title}」"
                    }
                }
            },
            onRename = { id, newTitle ->
                scope.launch {
                    withContext(Dispatchers.IO) { sessionStore.renameSession(id, newTitle) }
                    sessionMetas = withContext(Dispatchers.IO) { sessionStore.listSessions() }
                    if (session.activeSessionId == id) session.activeSessionTitle = newTitle.trim().take(36)
                }
            },
            onDelete = { id ->
                scope.launch {
                    withContext(Dispatchers.IO) { sessionStore.deleteSession(id) }
                    val metas = withContext(Dispatchers.IO) { sessionStore.listSessions() }
                    sessionMetas = metas
                    if (session.activeSessionId == id) {
                        val fallback = withContext(Dispatchers.IO) {
                            metas.firstOrNull()?.let { sessionStore.loadSession(it.id) }
                                ?: sessionStore.createNewSession("新对话")
                        }
                        session.loadFromDocument(fallback, rules)
                        sessionMetas = withContext(Dispatchers.IO) { sessionStore.listSessions() }
                    }
                }
            },
        )
    }

    if (memoryDialogOpen) {
        AgentMemoryBookDialog(
            memories = memories,
            memoryModelLabel = registry.activeMemoryProfile?.shortLabel()
                ?: if (llmClientOverride != null) "测试模型" else null,
            autoMemoryEnabled = autoMemoryEnabled,
            autoCompactEnabled = autoCompactEnabled,
            onToggleAutoMemory = { enabled ->
                autoMemoryEnabled = enabled
                AgentMemoryStore.setAutoMemoryEnabled(context, enabled)
            },
            onToggleAutoCompact = { enabled ->
                autoCompactEnabled = enabled
                AgentMemoryStore.setAutoCompactEnabled(context, enabled)
            },
            canExtractFromChat = effectiveReadyForChat && session.bubbles.isNotEmpty(),
            extracting = memoryBusy,
            onDismiss = { memoryDialogOpen = false },
            onAdd = { cat, content ->
                scope.launch {
                    withContext(Dispatchers.IO) { memoryStore.add(cat, content) }
                    val latest = withContext(Dispatchers.IO) { memoryStore.list() }
                    memories = latest
                    refreshDynamicPrompts(rules, latest)
                }
            },
            onDelete = { id ->
                scope.launch {
                    withContext(Dispatchers.IO) { memoryStore.remove(id) }
                    val latest = withContext(Dispatchers.IO) { memoryStore.list() }
                    memories = latest
                    refreshDynamicPrompts(rules, latest)
                }
            },
            onExtractNow = ::triggerExtractMemories,
        )
    }

    pendingImportDialog?.let { (proposal, tierIdx) ->
        val tier = proposal.report.paretoTiers.getOrNull(tierIdx) ?: proposal.report.recommendedTier
        AlertDialog(
            onDismissRequest = { pendingImportDialog = null },
            title = { Text("确认导入成长工作台") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "将把「${proposal.report.characterName} · ${proposal.report.line.label}」的「${tier.label}」方案写入当前存档并打开乱数养成工作台：",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val skipDesc = if (tier.skips > 0) "、${tier.skips} 次考试跳过" else ""
                    Text(
                        "• 等级范围：Lv.${proposal.report.fromLevel} → Lv.${proposal.report.targetLevel}（共 ${tier.workbenchSteps.size} 步，含 ${tier.switches} 次转职$skipDesc）\n" +
                            "• 预计属性总成长：+${tier.totalStatGain}（加权分 ${tier.weightedScore}，较基准 ${if (tier.scoreDeltaVsBaseline >= 0) "+" else ""}${tier.scoreDeltaVsBaseline}）",
                        style = MaterialTheme.typography.bodySmall.tabularNumbers(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { commitPlanToWorkbench(proposal, tierIdx) },
                    modifier = Modifier.testTag("agent-confirm-import"),
                ) {
                    Text("确认导入并打开")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingImportDialog = null }) { Text("取消") }
            },
        )
    }

    if (charPickerOpen && rules != null) {
        var q by remember { mutableStateOf("") }
        val chars = remember(rules, q) {
            val trimmed = q.trim()
            val all = rules!!.characters
            if (trimmed.isEmpty()) all
            else all.mapNotNull { ch ->
                val sc = com.fireemblem.companion.content.FuzzyMatcher.score(trimmed, ch.name, ch.id)
                if (sc > 0) sc to ch else null
            }.sortedByDescending { it.first }.map { it.second }
        }
        AlertDialog(
            onDismissRequest = { charPickerOpen = false },
            title = { Text("选择规划角色") },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = q,
                        onValueChange = { q = it },
                        placeholder = { Text("搜索角色名…") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(chars.take(40), key = { it.id }) { ch ->
                            Surface(
                                onClick = {
                                    selectedCharId = ch.id
                                    val effPart3 = part3 || ch.part3Only
                                    val legal = rules!!.professions.filter { rules!!.classAllowed(ch.id, it.name, effPart3) }
                                    if (legal.none { it.name == selectedProfession }) {
                                        selectedProfession = legal.firstOrNull()?.name.orEmpty()
                                    }
                                    charPickerOpen = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                            ) {
                                Text(ch.name, Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { charPickerOpen = false }) { Text("取消") } },
        )
    }

    if (profPickerOpen && rules != null) {
        val effPart3 = part3 || (rules!!.characters.firstOrNull { it.id == selectedCharId }?.part3Only == true)
        val legalProfs = remember(rules, selectedCharId, effPart3) {
            rules!!.professions.filter { rules!!.classAllowed(selectedCharId, it.name, effPart3) }
        }
        AlertDialog(
            onDismissRequest = { profPickerOpen = false },
            title = { Text("选择当前职业") },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(legalProfs, key = { it.name }) { p ->
                        Surface(
                            onClick = {
                                selectedProfession = p.name
                                profPickerOpen = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = if (p.name == selectedProfession) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            Text(
                                "${p.name}（${BuddyRules.tierLabel(p.tier)}）",
                                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { profPickerOpen = false }) { Text("取消") } },
        )
    }

    Column(Modifier.fillMaxSize().testTag("companion-agent-screen")) {
        // Header + Quick Actions + Mode Switch
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.heightIn(min = 44.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("返回")
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        "战策参谋",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { heading() },
                    )
                    Box {
                        val activeChat = registry.activeChatProfile
                        val activeMem = registry.activeMemoryProfile
                        Surface(
                            onClick = {
                                if (registry.profiles.size > 1) quickModelMenuOpen = true else configDialogOpen = true
                            },
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    when {
                                        llmClientOverride != null -> "已连接测试模型 · 记忆与规划引擎就绪"
                                        activeChat != null -> buildString {
                                            append(activeChat.shortLabel())
                                            if (activeMem != null && activeMem.id != activeChat.id) {
                                                append(" · 记忆:${activeMem.shortLabel()}")
                                            }
                                        }
                                        else -> "离线快捷 · 三作检索与乱数跳过立即可用"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (registry.profiles.size > 1) {
                                    Icon(Icons.Outlined.SwapHoriz, contentDescription = "切换模型", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                        DropdownMenu(
                            expanded = quickModelMenuOpen,
                            onDismissRequest = { quickModelMenuOpen = false },
                        ) {
                            registry.profiles.forEach { p ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            (if (p.id == registry.activeChatProfile?.id) "✓ " else "") +
                                                "${p.shortLabel()} (${p.modelId})",
                                        )
                                    },
                                    onClick = {
                                        quickModelMenuOpen = false
                                        val next = registry.copy(chatProfileId = p.id)
                                        runCatching { secureSettings.saveRegistry(next) }.onSuccess {
                                            registry = next
                                            statusIsError = false
                                            statusBanner = "已切换主对话模型：${p.shortLabel()}"
                                        }
                                    },
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("管理全部模型与记忆分工…") },
                                onClick = {
                                    quickModelMenuOpen = false
                                    configDialogOpen = true
                                },
                            )
                        }
                    }
                }
                OutlinedButton(
                    onClick = { historyDialogOpen = true },
                    modifier = Modifier.heightIn(min = 40.dp).testTag("agent-history-button"),
                    contentPadding = PaddingValues(horizontal = 9.dp, vertical = 6.dp),
                ) {
                    Icon(Icons.Outlined.History, contentDescription = "历史对话", modifier = Modifier.size(17.dp))
                }
                OutlinedButton(
                    onClick = { memoryDialogOpen = true },
                    modifier = Modifier.heightIn(min = 40.dp).testTag("agent-memory-button"),
                    contentPadding = PaddingValues(horizontal = 9.dp, vertical = 6.dp),
                ) {
                    Icon(Icons.Outlined.Psychology, contentDescription = "参谋记忆本", modifier = Modifier.size(17.dp))
                    if (memories.isNotEmpty()) {
                        Spacer(Modifier.width(3.dp))
                        Text("${memories.size}", style = MaterialTheme.typography.labelSmall.tabularNumbers())
                    }
                }
                OutlinedButton(
                    onClick = { configDialogOpen = true },
                    modifier = Modifier.heightIn(min = 40.dp).testTag("agent-config-button"),
                    contentPadding = PaddingValues(horizontal = 9.dp, vertical = 6.dp),
                ) {
                    Icon(Icons.Outlined.Tune, contentDescription = "模型配置", modifier = Modifier.size(17.dp))
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("offline" to "离线快捷", "chat" to "对话参谋").forEach { (key, label) ->
                    val sel = activeMode == key
                    Surface(
                        onClick = {
                            activeMode = key
                            session.activeMode = key
                            session.activeModeInitialized = true
                        },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 42.dp)
                            .semantics { selected = sel }
                            .testTag("agent-mode:$key"),
                        shape = RoundedCornerShape(14.dp),
                        color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                        contentColor = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        border = BorderStroke(1.dp, if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Box(Modifier.fillMaxWidth().padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
                            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium)
                        }
                    }
                }
            }

            statusBanner?.let { msg ->
                Surface(
                    color = if (statusIsError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = if (statusIsError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("agent-status-banner"),
                ) {
                    Text(msg, Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (activeMode == "offline") {
            LazyColumn(
                state = offlineListState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp).testTag("agent-offline-list"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (configLoaded && !effectiveReadyForChat) {
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Text(
                                    "配置云端或局域网模型后可开启「对话参谋」、历史多会话与自动记忆整理。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { configDialogOpen = true }) { Text("去配置") }
                            }
                        }
                    }
                }

                // Section 1: Cross-Game Offline Wiki Search
                item {
                    EditorialCard {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("跨作图鉴检索", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(
                                    "all" to "全部三作",
                                    CompanionGame.FORTUNES_WEAVE.id to "万缕千丝",
                                    CompanionGame.THREE_HOUSES.id to "风花雪月",
                                    CompanionGame.ENGAGE.id to "Engage",
                                ).forEach { (gid, label) ->
                                    val sel = session.offlineGameFilter == gid
                                    Surface(
                                        onClick = { session.offlineGameFilter = gid },
                                        modifier = Modifier.heightIn(min = 36.dp).testTag("agent-game-filter:$gid"),
                                        shape = RoundedCornerShape(100.dp),
                                        color = if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                                        contentColor = if (sel) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                        border = BorderStroke(1.dp, if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                                    ) {
                                        Box(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                                            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
                                        }
                                    }
                                }
                            }

                            fun triggerOfflineSearch() {
                                val q = session.offlineQuery.trim()
                                if (q.isEmpty()) return
                                searchingOffline = true
                                scope.launch {
                                    val hits = withContext(Dispatchers.IO) {
                                        retriever.search(q, gameFilter = session.offlineGameFilter, limit = 10)
                                    }
                                    session.offlineHits = hits
                                    session.offlineSearchDone = true
                                    searchingOffline = false
                                }
                            }

                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                                FuzzySearchBox(
                                    query = session.offlineQuery,
                                    onQueryChange = { session.offlineQuery = it },
                                    placeholder = "模糊搜三作角色、职业、特技、招募条件…",
                                    suggestionsProvider = { q -> retriever.suggest(q, gameFilter = session.offlineGameFilter, limit = 7) },
                                    onSelectSuggestion = { item ->
                                        val gid = item.gameId ?: CompanionGame.FORTUNES_WEAVE.id
                                        val gTitle = CompanionGame.fromId(gid).shortTitle
                                        openCitationPreservingScroll(
                                            AgentWikiCitation(
                                                gameId = gid,
                                                gameTitle = gTitle,
                                                categoryId = item.categoryId ?: item.id.substringBefore(':'),
                                                entryId = item.id,
                                                title = item.title,
                                                subtitle = item.subtitle,
                                            ),
                                        )
                                    },
                                    onSubmitSearch = { triggerOfflineSearch() },
                                    modifier = Modifier.weight(1f),
                                    fieldTestTag = "agent-offline-search",
                                    suggestionKey = session.offlineGameFilter,
                                    shapeRadiusDp = 14,
                                )
                                Button(
                                    onClick = ::triggerOfflineSearch,
                                    enabled = !searchingOffline && session.offlineQuery.isNotBlank(),
                                    modifier = Modifier.heightIn(min = 52.dp).testTag("agent-offline-search-go"),
                                ) {
                                    Text("检索")
                                }
                            }

                            if (searchingOffline) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Text("正在检索离线三作资料库…", style = MaterialTheme.typography.bodySmall)
                                }
                            } else if (session.offlineSearchDone && session.offlineHits.isEmpty()) {
                                Text("未找到匹配资料，可尝试缩短关键词。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else if (session.offlineHits.isNotEmpty()) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    session.offlineHits.forEach { hit ->
                                        Surface(
                                            onClick = { openCitationPreservingScroll(hit.toCitation()) },
                                            modifier = Modifier.fillMaxWidth().testTag("agent-offline-hit:${hit.entryId}"),
                                            shape = RoundedCornerShape(12.dp),
                                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                        ) {
                                            Row(
                                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            ) {
                                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                                        EditorialBadge(text = hit.gameTitle)
                                                        Text(hit.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                                    }
                                                    if (hit.snippet.isNotBlank()) {
                                                        Text(
                                                            hit.snippet,
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            maxLines = 2,
                                                            overflow = TextOverflow.Ellipsis,
                                                        )
                                                    }
                                                }
                                                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Section 2: 3-Game Offline Growth Planners Switcher
                item(key = "offline-planner-switcher") {
                    val activePlannerGame = session.offlinePlannerGame ?: activeGame.id
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("三作离线培养规划器", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                CompanionGame.FORTUNES_WEAVE.id to "万缕千丝·乱数分档",
                                CompanionGame.THREE_HOUSES.id to "风花雪月·期望转职",
                                CompanionGame.ENGAGE.id to "Engage·固定推算",
                            ).forEach { (gid, label) ->
                                val sel = activePlannerGame == gid
                                Surface(
                                    onClick = { session.offlinePlannerGame = gid },
                                    modifier = Modifier.weight(1f).heightIn(min = 40.dp).testTag("agent-offline-planner-tab:$gid"),
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLow,
                                    contentColor = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    border = BorderStroke(1.dp, if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                                ) {
                                    Box(Modifier.padding(horizontal = 6.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                                        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }

                val activeOfflinePlanner = session.offlinePlannerGame ?: activeGame.id
                if (activeOfflinePlanner == CompanionGame.THREE_HOUSES.id) {
                    item(key = "offline-th-planner") {
                        val th = threeHousesData
                        if (th == null) {
                            EditorialCard {
                                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Text("正在载入《风花雪月》40 名角色与兵种数据…", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        } else {
                            OfflineThreeHousesPlannerCard(
                                characters = th.first,
                                classes = th.second,
                                onSolved = { session.offlineThreeHousesProposal = it },
                            )
                        }
                    }
                    session.offlineThreeHousesProposal?.let { thProp ->
                        item(key = "offline-th-result") {
                            val expandKey = "offline-th:${thProp.report.characterName}:${thProp.report.targetLevel}"
                            ThreeHousesPlanProposalCard(
                                proposal = thProp,
                                stepsExpanded = session.expandedProposalSteps[expandKey] == true,
                                onToggleStepsExpanded = {
                                    session.expandedProposalSteps[expandKey] = !(session.expandedProposalSteps[expandKey] == true)
                                },
                            )
                        }
                    }
                } else if (activeOfflinePlanner == CompanionGame.ENGAGE.id) {
                    item(key = "offline-engage-planner") {
                        val eg = engageData
                        if (eg == null) {
                            EditorialCard {
                                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Text("正在载入《Engage》42 名角色与兵种固定成长数据…", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        } else {
                            OfflineEngagePlannerCard(
                                characters = eg.first,
                                classes = eg.second,
                                onSolved = { session.offlineEngageProposal = it },
                            )
                        }
                    }
                    session.offlineEngageProposal?.let { egProp ->
                        item(key = "offline-engage-result") {
                            val expandKey = "offline-eg:${egProp.report.characterName}:${egProp.report.totalLevelUps}:${egProp.report.finalClassName}"
                            EngagePlanProposalCard(
                                proposal = egProp,
                                stepsExpanded = session.expandedProposalSteps[expandKey] == true,
                                onToggleStepsExpanded = {
                                    session.expandedProposalSteps[expandKey] = !(session.expandedProposalSteps[expandKey] == true)
                                },
                            )
                        }
                    }
                } else {
                // Direct Fortune's Weave Tiered Min-Switch & Exam-Skip Growth Planner
                item(key = "offline-fw-planner") {
                    EditorialCard {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("《万缕千丝》乱数转职分档与跳过规划", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                            Text(
                                "基于精确乱数表与二维动态规划，自动校验路线与最早可转职等级，支持过滤低成长与考试跳过。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            if (savedPicks.isNotEmpty()) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("从已有存档快选：", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Row(
                                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        savedPicks.forEach { pick ->
                                            Surface(
                                                onClick = {
                                                    selectedCharId = pick.characterId
                                                    selectedLine = pick.line
                                                    part3 = pick.part3
                                                    seedText = pick.seed.toString()
                                                    fromLevelText = pick.level.coerceIn(1, 98).toString()
                                                    toLevelText = (pick.level + 10).coerceAtMost(99).toString()
                                                    selectedProfession = pick.profession
                                                    unlockedSet = pick.unlockedProfessions
                                                    currentStats = pick.stats
                                                },
                                                shape = RoundedCornerShape(10.dp),
                                                color = MaterialTheme.colorScheme.secondaryContainer,
                                            ) {
                                                Text(
                                                    pick.label,
                                                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            val activeCharName = rules?.characters?.firstOrNull { it.id == selectedCharId }?.name ?: selectedCharId
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { charPickerOpen = true },
                                    modifier = Modifier.weight(1f).heightIn(min = 46.dp).testTag("agent-pick-char"),
                                ) {
                                    Text("角色：$activeCharName", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                OutlinedButton(
                                    onClick = { profPickerOpen = true },
                                    modifier = Modifier.weight(1f).heightIn(min = 46.dp).testTag("agent-pick-prof"),
                                ) {
                                    Text("当前：${selectedProfession.ifBlank { "选择职业" }}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }

                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                BuddyCampaignLine.entries.forEach { line ->
                                    val sel = selectedLine == line
                                    Surface(
                                        onClick = { selectedLine = line },
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                                        border = BorderStroke(1.dp, if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                                    ) {
                                        Text(
                                            line.label,
                                            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                    }
                                }
                            }

                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = seedText,
                                    onValueChange = { v -> if (v.length <= 10 && v.all(Char::isDigit)) seedText = v },
                                    label = { Text("当前乱数种子") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(1.4f).testTag("agent-planner-seed"),
                                    shape = RoundedCornerShape(12.dp),
                                )
                                OutlinedTextField(
                                    value = fromLevelText,
                                    onValueChange = { v -> if (v.length <= 2 && v.all(Char::isDigit)) fromLevelText = v },
                                    label = { Text("当前Lv") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(0.75f).testTag("agent-planner-from-lv"),
                                    shape = RoundedCornerShape(12.dp),
                                )
                                OutlinedTextField(
                                    value = toLevelText,
                                    onValueChange = { v -> if (v.length <= 2 && v.all(Char::isDigit)) toLevelText = v },
                                    label = { Text("目标Lv") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(0.75f).testTag("agent-planner-to-lv"),
                                    shape = RoundedCornerShape(12.dp),
                                )
                                OutlinedTextField(
                                    value = maxSwitchesText,
                                    onValueChange = { v -> if (v.length <= 2 && v.all(Char::isDigit)) maxSwitchesText = v },
                                    label = { Text("转职上限") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(0.8f).testTag("agent-planner-budget"),
                                    shape = RoundedCornerShape(12.dp),
                                )
                            }

                            // Exam-Skip & Low-Gain Filter Section
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("考试跳过策略（用转职考试消耗劣质乱数）：", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        listOf(
                                            null to "不限低成长",
                                            3 to "跳过总成长 <3",
                                            4 to "跳过总成长 <4",
                                            5 to "跳过总成长 <5",
                                        ).forEach { (minVal, label) ->
                                            val sel = minTotalGainFilter == minVal
                                            val tagKey = minVal?.toString() ?: "none"
                                            Surface(
                                                onClick = {
                                                    minTotalGainFilter = minVal
                                                    if (minVal != null && (maxSkipsText.toIntOrNull() ?: 0) == 0) {
                                                        maxSkipsText = "15"
                                                    }
                                                },
                                                shape = RoundedCornerShape(100.dp),
                                                color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                                contentColor = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                border = BorderStroke(1.dp, if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                                                modifier = Modifier.testTag("agent-min-gain:$tagKey"),
                                            ) {
                                                Text(label, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text("按权重自动寻找最优考试跳过", style = MaterialTheme.typography.bodySmall)
                                            Text(
                                                "开启后自动在跳过预算内挑选加权分最高的乱数段",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Switch(
                                            checked = optimizeSkips,
                                            onCheckedChange = { checked ->
                                                optimizeSkips = checked
                                                if (checked && (maxSkipsText.toIntOrNull() ?: 0) == 0) {
                                                    maxSkipsText = "12"
                                                }
                                            },
                                            modifier = Modifier.testTag("agent-opt-skips-switch"),
                                        )
                                    }
                                    if (optimizeSkips || minTotalGainFilter != null) {
                                        OutlinedTextField(
                                            value = maxSkipsText,
                                            onValueChange = { v -> if (v.length <= 2 && v.all(Char::isDigit)) maxSkipsText = v },
                                            label = { Text("最多考试跳过次数（0~30）") },
                                            singleLine = true,
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            modifier = Modifier.fillMaxWidth().testTag("agent-max-skips"),
                                            shape = RoundedCornerShape(10.dp),
                                        )
                                    }
                                }
                            }

                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text("预演当前路线合法职业（含未解锁）", style = MaterialTheme.typography.bodySmall)
                                Switch(checked = includeLocked, onCheckedChange = { includeLocked = it })
                            }
                            if (includeLocked) {
                                OutlinedTextField(
                                    value = minPromoLevelText,
                                    onValueChange = { v -> if (v.length <= 2 && v.all(Char::isDigit)) minPromoLevelText = v },
                                    label = { Text("未解锁职业最早转职等级（留空自动：仅基础职业默认 Lv.10 起）") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.fillMaxWidth().testTag("agent-min-promo-lv"),
                                    shape = RoundedCornerShape(10.dp),
                                )
                            }

                            // 9-stat weight quick presets + 3x3 tappable 0..5 selector
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    listOf(
                                        "力量优先" to listOf(1, 5, 0, 2, 3, 1, 1, 0, 1),
                                        "魔力优先" to listOf(1, 0, 5, 2, 3, 1, 0, 2, 1),
                                        "力速双修" to listOf(1, 5, 0, 2, 5, 1, 1, 0, 0),
                                        "均衡成长" to listOf(2, 2, 2, 2, 2, 2, 2, 2, 2),
                                    ).forEach { (presetLabel, presetVec) ->
                                        Surface(
                                            onClick = { weights = presetVec },
                                            shape = RoundedCornerShape(100.dp),
                                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        ) {
                                            Text(presetLabel, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                                Text("属性权重（点击循环 0~5）：", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                GrowthStat.entries.chunked(3).forEach { rowStats ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        rowStats.forEach { stat ->
                                            val w = weights[stat.ordinal]
                                            Surface(
                                                onClick = {
                                                    val next = weights.toMutableList()
                                                    next[stat.ordinal] = (w + 1) % 6
                                                    weights = next
                                                },
                                                modifier = Modifier.weight(1f).heightIn(min = 38.dp).testTag("agent-weight:${stat.name}"),
                                                shape = RoundedCornerShape(10.dp),
                                                color = if (w >= 4) MaterialTheme.colorScheme.primaryContainer
                                                else if (w > 0) MaterialTheme.colorScheme.surfaceContainerLow
                                                else MaterialTheme.colorScheme.surface,
                                                border = BorderStroke(1.dp, if (w >= 4) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                                            ) {
                                                Row(
                                                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    Text(stat.label, style = MaterialTheme.typography.labelMedium)
                                                    Text(
                                                        "×$w",
                                                        style = MaterialTheme.typography.labelMedium.tabularNumbers(),
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (w >= 4) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            session.offlinePlannerError?.let { err ->
                                Text(err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }

                            Button(
                                enabled = !solvingPareto && rules != null,
                                onClick = {
                                    val r = rules ?: return@Button
                                    val seedVal = seedText.trim().toLongOrNull()
                                    val lvFrom = fromLevelText.trim().toIntOrNull()
                                    val lvTo = toLevelText.trim().toIntOrNull()
                                    val budget = (maxSwitchesText.trim().toIntOrNull() ?: 3).coerceIn(0, 10)
                                    val skipBudget = (maxSkipsText.trim().toIntOrNull() ?: 0)
                                        .coerceIn(0, ParetoGrowthPlanner.MAX_ALLOWED_SKIPS)
                                    if (seedVal == null || seedVal !in 0 until SeedEngine.UINT32_LIMIT) {
                                        session.offlinePlannerError = "请输入有效的乱数种子（0 ~ 4294967295）。"
                                        return@Button
                                    }
                                    if (lvFrom == null || lvTo == null || lvFrom !in 1..98 || lvTo !in (lvFrom + 1)..99) {
                                        session.offlinePlannerError = "目标等级需高于当前等级且不超过 99。"
                                        return@Button
                                    }
                                    if (selectedProfession.isBlank()) {
                                        session.offlinePlannerError = "请先选择当前职业。"
                                        return@Button
                                    }
                                    session.offlinePlannerError = null
                                    solvingPareto = true
                                    scope.launch {
                                        val res = withContext(Dispatchers.Default) {
                                            runCatching {
                                                val report = ParetoGrowthPlanner.solve(
                                                    ParetoGrowthPlanRequest(
                                                        seed = seedVal,
                                                        characterId = selectedCharId,
                                                        line = selectedLine,
                                                        currentLevel = lvFrom,
                                                        targetLevel = lvTo,
                                                        currentPart3 = part3,
                                                        initialProfession = selectedProfession,
                                                        unlockedProfessions = unlockedSet + selectedProfession,
                                                        includeLockedPreview = includeLocked,
                                                        weights = weights,
                                                        currentStats = currentStats,
                                                        maxSwitchBudget = budget,
                                                        maxSkips = skipBudget,
                                                        optimizeSkips = optimizeSkips,
                                                        minTotalGain = minTotalGainFilter,
                                                        minPromotionLevel = minPromoLevelText.trim().toIntOrNull(),
                                                    ),
                                                    r,
                                                )
                                                val best = report.recommendedTier
                                                val profileId = context.getSharedPreferences("personal-view", 0)
                                                    .getString("profile", "global") ?: "global"
                                                AgentPlanProposal(
                                                    report = report,
                                                    recordTemplate = SeedWorkbenchRecord(
                                                        profileId = profileId,
                                                        characterId = report.characterId,
                                                        line = report.line,
                                                        part3 = report.part3,
                                                        baseSeed = report.seed,
                                                        inferredLevel = report.fromLevel,
                                                        startLevel = report.fromLevel,
                                                        extraAdvances = 0,
                                                        profession = report.initialProfession,
                                                        ride = report.ride,
                                                        unlockedProfessions = unlockedSet + report.initialProfession + best.steps.map { it.profession },
                                                        weights = SeedWorkbenchEngine.normalizeWeights(weights),
                                                        planSteps = best.workbenchSteps,
                                                        previewOverrides = emptyMap(),
                                                        completedSteps = 0,
                                                    ),
                                                    defaultTierIndex = report.paretoTiers.lastIndex.coerceAtLeast(0),
                                                )
                                            }
                                        }
                                        res.onSuccess { proposal ->
                                            session.offlineProposal = proposal
                                        }.onFailure { err ->
                                            session.offlinePlannerError = err.message ?: "计算失败"
                                        }
                                        solvingPareto = false
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("agent-solve-pareto"),
                            ) {
                                if (solvingPareto) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(if (solvingPareto) "正在求解最优转职与跳过…" else "计算最优转职与跳过方案")
                            }
                        }
                    }
                }

                session.offlineProposal?.let { proposal ->
                    item(key = "offline-pareto-result") {
                        val expandKey = "offline:${proposal.report.characterId}:${proposal.report.seed}"
                        ParetoPlanProposalCard(
                            proposal = proposal,
                            stepsExpanded = session.expandedProposalSteps[expandKey] == true,
                            onToggleStepsExpanded = {
                                session.expandedProposalSteps[expandKey] = !(session.expandedProposalSteps[expandKey] == true)
                            },
                            onRequestImport = { tierIdx -> pendingImportDialog = proposal to tierIdx },
                        )
                    }
                }
                }

                item { Spacer(Modifier.height(16.dp)) }
            }
        } else {
            // Mode B: Conversational Pi-Inspired Multi-Session, Memory-Aware Agent
            if (!effectiveReadyForChat) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    EditorialCard {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                            Text("配置模型开启对话参谋", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "支持配置多套模型并按角色分工（主对话模型 + 记忆/摘要模型），兼容 DeepSeek、千问、Claude（Opus 5.5）及局域网 OpenAI 网关。密钥仅使用 AndroidKeyStore 硬件加密存于本机。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { configDialogOpen = true },
                                    modifier = Modifier.heightIn(min = 48.dp).testTag("agent-empty-configure"),
                                ) {
                                    Text("配置模型接口")
                                }
                                OutlinedButton(
                                    onClick = { activeMode = "offline" },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) {
                                    Text("先用离线快捷模式")
                                }
                            }
                        }
                    }
                }
            } else {
                // Session Control Strip (Title + New Chat + Compact + Extract Memory)
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    session.activeSessionTitle,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                if (session.activeParentSessionId != null) EditorialBadge(text = "分支")
                                if (!session.compactedSummary.isNullOrBlank()) EditorialBadge(text = "已压缩")
                            }
                        }
                        Surface(
                            onClick = ::startFreshSession,
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.testTag("agent-new-chat"),
                        ) {
                            Row(Modifier.padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                Icon(Icons.Outlined.AddComment, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                Text("新建", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        if (session.bubbles.isNotEmpty() && !chatBusy) {
                            Surface(
                                onClick = ::triggerCompactContext,
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.testTag("agent-compact-chat"),
                            ) {
                                Row(Modifier.padding(horizontal = 7.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Icon(Icons.Outlined.Compress, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Text("压缩", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            Surface(
                                onClick = ::triggerExtractMemories,
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.testTag("agent-extract-memory"),
                            ) {
                                Row(Modifier.padding(horizontal = 7.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Icon(Icons.Outlined.Psychology, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Text(if (memoryBusy) "整理…" else "记入", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))

                LazyColumn(
                    state = chatListState,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp).testTag("agent-chat-list"),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (session.bubbles.isEmpty() && !chatBusy) {
                        item {
                            EditorialCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Text("基于你的存档与当前视角推荐：", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                                        if (memories.isNotEmpty()) {
                                            EditorialBadge(text = "已加载 ${memories.size} 条记忆")
                                        }
                                    }
                                    starterPrompts.forEach { prompt ->
                                        Surface(
                                            onClick = { sendChatPrompt(prompt) },
                                            shape = RoundedCornerShape(12.dp),
                                            color = MaterialTheme.colorScheme.surface,
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                            modifier = Modifier.fillMaxWidth().testTag("agent-starter-prompt"),
                                        ) {
                                            Text(prompt, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    items(session.bubbles, key = { it.id }) { bubble ->
                        AgentChatBubbleCard(
                            bubble = bubble,
                            canFork = !bubble.isUser && !bubble.isError && !chatBusy,
                            expandedProposalSteps = session.expandedProposalSteps,
                            onOpenCitation = openCitationPreservingScroll,
                            onRequestImport = { proposal, tierIdx -> pendingImportDialog = proposal to tierIdx },
                            onForkFromHere = {
                                val currentDoc = session.toDocument()
                                scope.launch {
                                    val forked = withContext(Dispatchers.IO) {
                                        sessionStore.saveSession(currentDoc)
                                        sessionStore.forkSession(currentDoc, bubble.id)
                                    }
                                    val metas = withContext(Dispatchers.IO) { sessionStore.listSessions() }
                                    session.loadFromDocument(forked, rules, retriever)
                                    sessionMetas = metas
                                    statusIsError = false
                                    statusBanner = "已从此轮分叉出新分支对话「${forked.title}」"
                                }
                            },
                        )
                    }

                    if (chatBusy) {
                        item(key = "streaming-bubble") {
                            EditorialCard(containerColor = MaterialTheme.colorScheme.surface) {
                                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                        Text(
                                            activeToolLabel?.let { "正在调用工具：$it…" } ?: "战策参谋推演中…",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                    if (liveToolTraces.isNotEmpty()) {
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            liveToolTraces.forEach { t -> EditorialBadge(text = "✓ ${t.summary}") }
                                        }
                                    }
                                    if (streamingText.isNotBlank()) {
                                        MarkdownText(markdown = streamingText)
                                    }
                                }
                            }
                        }
                    }

                    item { Spacer(Modifier.height(8.dp)) }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = chatInput,
                            onValueChange = { chatInput = it },
                            modifier = Modifier.weight(1f).testTag("agent-chat-input"),
                            placeholder = { Text("问三作图鉴、乱数跳过或转职规划…") },
                            maxLines = 3,
                            shape = RoundedCornerShape(16.dp),
                        )
                        if (chatBusy) {
                            OutlinedButton(
                                onClick = { activeJob?.cancel() },
                                modifier = Modifier.heightIn(min = 48.dp).testTag("agent-stop"),
                            ) {
                                Icon(Icons.Outlined.StopCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("停止")
                            }
                        } else {
                            Button(
                                onClick = { sendChatPrompt(chatInput) },
                                enabled = chatInput.isNotBlank(),
                                modifier = Modifier.heightIn(min = 48.dp).testTag("agent-chat-send"),
                            ) {
                                Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("发送")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentChatBubbleCard(
    bubble: AgentChatBubble,
    canFork: Boolean,
    expandedProposalSteps: MutableMap<String, Boolean>,
    onOpenCitation: (AgentWikiCitation) -> Unit,
    onRequestImport: (AgentPlanProposal, Int) -> Unit,
    onForkFromHere: () -> Unit,
) {
    val container = when {
        bubble.isUser -> MaterialTheme.colorScheme.primaryContainer
        bubble.isError -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surface
    }
    val contentColor = when {
        bubble.isUser -> MaterialTheme.colorScheme.onPrimaryContainer
        bubble.isError -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        EditorialCard(containerColor = container) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (bubble.isUser) "你" else "战策参谋",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (bubble.isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (canFork) {
                        Surface(
                            onClick = onForkFromHere,
                            shape = RoundedCornerShape(100.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.testTag("agent-fork:${bubble.id}"),
                        ) {
                            Row(
                                Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Icon(Icons.AutoMirrored.Outlined.CallSplit, contentDescription = null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("从此分叉", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (bubble.toolTraces.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        bubble.toolTraces.forEach { trace ->
                            EditorialBadge(text = (if (trace.isError) "! " else "✓ ") + trace.summary)
                        }
                    }
                }
                if (bubble.isUser || bubble.isError) {
                    Text(bubble.text, style = MaterialTheme.typography.bodyMedium, color = contentColor)
                } else {
                    MarkdownText(
                        markdown = bubble.text,
                        color = contentColor,
                        baseStyle = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (bubble.citations.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        bubble.citations.forEach { cite ->
                            Surface(
                                onClick = { onOpenCitation(cite) },
                                modifier = Modifier.heightIn(min = 36.dp).testTag("agent-citation:${cite.entryId}"),
                                shape = RoundedCornerShape(100.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("${cite.gameTitle} · ${cite.title}", style = MaterialTheme.typography.labelMedium)
                                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
        bubble.proposals.forEachIndexed { pIdx, proposal ->
            val expandKey = "chat:${bubble.id}:$pIdx:${proposal.report.seed}"
            ParetoPlanProposalCard(
                proposal = proposal,
                stepsExpanded = expandedProposalSteps[expandKey] == true,
                onToggleStepsExpanded = {
                    expandedProposalSteps[expandKey] = !(expandedProposalSteps[expandKey] == true)
                },
                onRequestImport = { tierIdx -> onRequestImport(proposal, tierIdx) },
            )
        }
        bubble.threeHousesProposals.forEachIndexed { thIdx, thProposal ->
            val expandKey = "chat-th:${bubble.id}:$thIdx:${thProposal.report.characterName}"
            ThreeHousesPlanProposalCard(
                proposal = thProposal,
                stepsExpanded = expandedProposalSteps[expandKey] == true,
                onToggleStepsExpanded = {
                    expandedProposalSteps[expandKey] = !(expandedProposalSteps[expandKey] == true)
                },
            )
        }
        bubble.engageProposals.forEachIndexed { egIdx, egProposal ->
            val expandKey = "chat-eg:${bubble.id}:$egIdx:${egProposal.report.characterName}"
            EngagePlanProposalCard(
                proposal = egProposal,
                stepsExpanded = expandedProposalSteps[expandKey] == true,
                onToggleStepsExpanded = {
                    expandedProposalSteps[expandKey] = !(expandedProposalSteps[expandKey] == true)
                },
            )
        }
    }
}

@Composable
internal fun ParetoPlanProposalCard(
    proposal: AgentPlanProposal,
    stepsExpanded: Boolean = false,
    onToggleStepsExpanded: (() -> Unit)? = null,
    onRequestImport: (Int) -> Unit,
) {
    val report = proposal.report
    var selectedTierIdx by remember(proposal) {
        mutableIntStateOf(proposal.defaultTierIndex.coerceIn(0, report.paretoTiers.lastIndex.coerceAtLeast(0)))
    }
    var localStepsExpanded by remember(proposal) { mutableStateOf(stepsExpanded) }
    val effectiveStepsExpanded = if (onToggleStepsExpanded != null) stepsExpanded else localStepsExpanded
    val toggleSteps: () -> Unit = {
        if (onToggleStepsExpanded != null) onToggleStepsExpanded() else localStepsExpanded = !localStepsExpanded
    }
    var showAllSkips by remember(proposal, selectedTierIdx) { mutableStateOf(false) }
    val tier: ParetoPlanTier = report.paretoTiers.getOrElse(selectedTierIdx) { report.baseline }
    val highPal = statTierPalette(StatRatingTier.HIGH)
    val lowPal = statTierPalette(StatRatingTier.LOW)

    EditorialCard(
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().testTag("pareto-proposal-card"),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${report.characterName} · Lv.${report.fromLevel} → Lv.${report.targetLevel} 转职分档方案",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "${report.line.label} · 初始职业 ${report.initialProfession} · 种子 ${report.seed}",
                        style = MaterialTheme.typography.labelSmall.tabularNumbers(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (tier.skips > 0) {
                        EditorialBadge(
                            text = "跳 ${tier.skips} 次",
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    EditorialBadge(
                        text = "共 ${report.paretoTiers.size} 档",
                        containerColor = highPal.badgeContainer,
                        contentColor = highPal.content,
                    )
                }
            }

            // Pareto Tier Selector
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                report.paretoTiers.forEachIndexed { idx, t ->
                    val sel = idx == selectedTierIdx
                    val deltaTxt = if (t.scoreDeltaVsBaseline > 0) " +${t.scoreDeltaVsBaseline}" else ""
                    Surface(
                        onClick = { selectedTierIdx = idx },
                        modifier = Modifier.heightIn(min = 40.dp).semantics { selected = sel }.testTag("pareto-tier:$idx"),
                        shape = RoundedCornerShape(10.dp),
                        color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLow,
                        contentColor = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        border = BorderStroke(1.dp, if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Box(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
                            Text(
                                "${t.label}（${t.weightedScore}分$deltaTxt）",
                                style = MaterialTheme.typography.labelMedium.tabularNumbers(),
                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }

            // 9-stat gains grid with delta vs baseline
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    "总成长 +${tier.totalStatGain} · 加权分 ${tier.weightedScore}" +
                        (if (tier.skips > 0) " · 考试跳过 ${tier.skips} 次" else "") +
                        if (tier.scoreDeltaVsBaseline > 0) "（较基准 +${tier.scoreDeltaVsBaseline}）" else "（基准）",
                    style = MaterialTheme.typography.labelLarge.tabularNumbers(),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                GrowthStat.entries.chunked(3).forEach { rowStats ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        rowStats.forEach { stat ->
                            val idx = stat.ordinal
                            val g = tier.statGains[idx]
                            val d = tier.statGainsDeltaVsBaseline[idx]
                            Surface(
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                                color = when {
                                    d > 0 -> highPal.container
                                    d < 0 -> lowPal.container
                                    else -> MaterialTheme.colorScheme.surfaceContainerLow
                                },
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(stat.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        "+$g" + when {
                                            d > 0 -> "(+$d)"
                                            d < 0 -> "($d)"
                                            else -> ""
                                        },
                                        style = MaterialTheme.typography.labelMedium.tabularNumbers(),
                                        fontWeight = if (d != 0) FontWeight.Bold else FontWeight.Medium,
                                        color = when {
                                            d > 0 -> highPal.content
                                            d < 0 -> lowPal.content
                                            else -> MaterialTheme.colorScheme.onSurface
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Switch schedule
            if (tier.switchEvents.isEmpty()) {
                Text(
                    "本方案全程保持「${report.initialProfession}」，无需转职。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("转职节点明细（共 ${tier.switchEvents.size} 次）：", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    tier.switchEvents.forEach { ev ->
                        val diffParts = GrowthStat.entries.mapNotNull { st ->
                            val diff = ev.gainsAtLevel[st.ordinal] - ev.baselineGainsAtLevel[st.ordinal]
                            if (diff != 0) "${st.label}${if (diff > 0) "+$diff" else "$diff"}" else null
                        }.joinToString("、")
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "Lv.${ev.atLevel}：${ev.fromProfession} → ${ev.toProfession}" +
                                            if (diffParts.isNotBlank()) "（本级较基准 $diffParts）" else "",
                                        style = MaterialTheme.typography.bodySmall.tabularNumbers(),
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (ev.requiresUnlockExam) {
                                        EditorialBadge(
                                            text = "需考试解锁",
                                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                        )
                                    }
                                }
                                ev.unlockRequirement?.takeIf { ev.requiresUnlockExam && it.isNotBlank() }?.let { req ->
                                    Text(
                                        req,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Exam Skip schedule
            if (tier.skipEvents.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "考试跳过节点（共 ${tier.skipEvents.size} 次）：",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                        if (tier.skipEvents.size > 6) {
                            TextButton(
                                onClick = { showAllSkips = !showAllSkips },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            ) {
                                Text(if (showAllSkips) "收起" else "展开全部 ${tier.skipEvents.size} 次", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    val visibleSkips = if (showAllSkips) tier.skipEvents else tier.skipEvents.take(6)
                    visibleSkips.forEach { sk ->
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "Lv.${sk.atLevel} 前考试跳过（乱数步 #${sk.rngStepIndex}）：原总成长 +${sk.skippedTotal}，最低通过率 ${sk.examMinimumProbability}%",
                                Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                style = MaterialTheme.typography.bodySmall.tabularNumbers(),
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
            }

            report.warnings.forEach { w ->
                Text("提示：$w", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // Expandable level-by-level growth preview table (interleaving exam skips + class switches + 9-stat procs)
            if (tier.steps.isNotEmpty()) {
                val skipsSuffix = if (tier.skips > 0) " · 含 ${tier.skips} 次跳点" else ""
                Surface(
                    onClick = toggleSteps,
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth().testTag("pareto-toggle-steps"),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (effectiveStepsExpanded) "收起逐级成长明细预览"
                                else "展开逐级成长明细预览（Lv.${report.fromLevel} → Lv.${report.targetLevel} · 共 ${tier.steps.size} 级$skipsSuffix）",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (!effectiveStepsExpanded) {
                                Text(
                                    "提前查看每一级职业、9 项具体成长值与考试跳过的劣质乱数点",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Icon(
                            if (effectiveStepsExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                if (effectiveStepsExpanded) {
                    ParetoLevelByLevelPreviewTable(
                        tier = tier,
                        highPal = highPal,
                        modifier = Modifier.fillMaxWidth().testTag("pareto-steps-table"),
                    )
                }
            }

            Button(
                onClick = { onRequestImport(selectedTierIdx) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp).testTag("pareto-import-workbench"),
            ) {
                Text("一键导入成长工作台（${tier.label}）")
            }
        }
    }
}

@Composable
private fun ParetoLevelByLevelPreviewTable(
    tier: ParetoPlanTier,
    highPal: StatTierPalette,
    modifier: Modifier = Modifier,
) {
    val skipsByLevel = remember(tier) { tier.skipEvents.groupBy { it.atLevel } }
    val outline = MaterialTheme.colorScheme.outlineVariant
    val headerBg = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
    val headerFg = MaterialTheme.colorScheme.onPrimaryContainer
    val skipBg = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.42f)
    val altRowBg = MaterialTheme.colorScheme.surfaceContainerLow
    val statShortLabels = remember { listOf("HP", "力", "魔", "技", "速", "幸", "防", "魔防", "魅") }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, outline),
        modifier = modifier,
    ) {
        Column(
            Modifier
                .horizontalScroll(rememberScrollState())
                .width(IntrinsicSize.Max),
        ) {
            // Table Header
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(headerBg)
                    .padding(horizontal = 8.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("步骤", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = headerFg, modifier = Modifier.width(66.dp))
                Text("职业 / 动作", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = headerFg, modifier = Modifier.width(104.dp))
                Text("总计", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = headerFg, modifier = Modifier.width(34.dp))
                statShortLabels.forEach { stLabel ->
                    Text(stLabel, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = headerFg, modifier = Modifier.width(28.dp))
                }
            }

            // Interleaved Skip Events + Level-Up Steps
            tier.steps.forEachIndexed { idx, step ->
                skipsByLevel[step.level].orEmpty().forEach { sk ->
                    HorizontalDivider(color = outline.copy(alpha = 0.45f))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(skipBg)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "跳 #${sk.rngStepIndex}",
                            style = MaterialTheme.typography.labelSmall.tabularNumbers(),
                            color = MaterialTheme.colorScheme.secondary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.width(66.dp),
                        )
                        Text(
                            "考试跳过(${sk.examMinimumProbability}%)",
                            style = MaterialTheme.typography.labelSmall.tabularNumbers(),
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.width(104.dp),
                        )
                        Text(
                            "+${sk.skippedTotal}",
                            style = MaterialTheme.typography.labelSmall.tabularNumbers(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(34.dp),
                        )
                        sk.skippedGains.forEach { g ->
                            Text(
                                if (g > 0) "+$g" else "·",
                                style = MaterialTheme.typography.labelSmall.tabularNumbers(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.width(28.dp),
                            )
                        }
                    }
                }

                HorizontalDivider(color = outline.copy(alpha = 0.45f))
                val isSwitch = step.switchFrom != null
                val totalGain = step.gains.sum()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            when {
                                isSwitch -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                                idx % 2 == 1 -> altRowBg
                                else -> Color.Transparent
                            },
                        )
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Lv.${step.level}→${step.level + 1}",
                        style = MaterialTheme.typography.labelSmall.tabularNumbers(),
                        fontWeight = if (isSwitch) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.width(66.dp),
                    )
                    Text(
                        (if (isSwitch) "转·" else "") + step.profession,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isSwitch) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSwitch) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(104.dp),
                    )
                    Text(
                        "+$totalGain",
                        style = MaterialTheme.typography.labelSmall.tabularNumbers(),
                        fontWeight = FontWeight.Bold,
                        color = if (totalGain >= 4) highPal.content else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.width(34.dp),
                    )
                    step.gains.forEach { g ->
                        Text(
                            if (g > 0) "+$g" else "·",
                            style = MaterialTheme.typography.labelSmall.tabularNumbers(),
                            fontWeight = if (g > 0) FontWeight.Bold else FontWeight.Normal,
                            color = if (g > 0) highPal.content else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                            modifier = Modifier.width(28.dp),
                        )
                    }
                }
            }

            // Summary Footer Row
            HorizontalDivider(color = outline)
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(headerBg.copy(alpha = 0.4f))
                    .padding(horizontal = 8.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("合计", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.width(66.dp))
                Text("${tier.steps.size} 级·跳${tier.skips}次", style = MaterialTheme.typography.labelSmall.tabularNumbers(), fontWeight = FontWeight.SemiBold, modifier = Modifier.width(104.dp))
                Text("+${tier.totalStatGain}", style = MaterialTheme.typography.labelSmall.tabularNumbers(), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(34.dp))
                tier.statGains.forEach { g ->
                    Text(
                        "+$g",
                        style = MaterialTheme.typography.labelSmall.tabularNumbers(),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.width(28.dp),
                    )
                }
            }
        }
    }
}

private fun loadSavedSeedPicks(
    context: android.content.Context,
    rules: BuddyRules,
): List<AgentSavedSeedQuickPick> = runCatching {
    val profileId = context.getSharedPreferences("personal-view", 0)
        .getString("profile", "global") ?: "global"
    val heads = PersonalStore(context).snapshot().heads().values
        .mapNotNull { it.singleOrNull() }
        .filter { !it.deleted }
    val picks = mutableListOf<AgentSavedSeedQuickPick>()
    heads.filter { it.kind == SeedWorkbenchRecord.KIND }
        .mapNotNull { runCatching { SeedWorkbenchRecord.decode(it.payload) }.getOrNull() }
        .filter { it.profileId == profileId }
        .forEach { wb ->
            val name = rules.characters.firstOrNull { it.id == wb.characterId }?.name ?: wb.characterId
            picks += AgentSavedSeedQuickPick(
                label = "$name · ${wb.line.label} Lv.${wb.startLevel}",
                characterId = wb.characterId,
                characterName = name,
                line = wb.line,
                part3 = wb.part3,
                seed = wb.baseSeed,
                level = wb.startLevel,
                profession = wb.profession,
                unlockedProfessions = wb.unlockedProfessions,
                stats = List(GrowthStat.entries.size) { 0 },
            )
        }
    heads.filter { it.kind == LevelJournal.KIND }
        .mapNotNull { runCatching { LevelJournal.decode(it.payload) }.getOrNull() }
        .filter { it.profile == profileId && it.hasCurrentSeedCheckpoint() }
        .forEach { j ->
            val cp = j.seedCheckpoint ?: return@forEach
            val name = rules.characters.firstOrNull { it.id == j.characterId }?.name ?: j.characterId
            picks += AgentSavedSeedQuickPick(
                label = "$name · ${j.name} Lv.${cp.level}",
                characterId = j.characterId,
                characterName = name,
                line = j.line,
                part3 = cp.part3,
                seed = cp.seed,
                level = cp.level,
                profession = cp.profession,
                unlockedProfessions = j.confirmedUnlockedProfessions() + cp.profession,
                stats = cp.stats ?: List(GrowthStat.entries.size) { 0 },
            )
        }
    picks.distinctBy { "${it.characterId}|${it.line.id}|${it.seed}" }
}.getOrDefault(emptyList())
