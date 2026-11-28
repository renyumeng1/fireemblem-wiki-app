package com.fireemblem.companion.ui

import android.graphics.BitmapFactory
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.automirrored.outlined.CompareArrows
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.TheaterComedy
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.Leaderboard
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fireemblem.companion.agent.AgentWikiCitation
import com.fireemblem.companion.campaign.CampaignTaskKind
import com.fireemblem.companion.content.CompanionGame
import com.fireemblem.companion.content.GameDataV1Repository
import com.fireemblem.companion.content.WikiDatabaseRepository
import com.fireemblem.companion.content.WikiCategorySummary
import com.fireemblem.companion.engine.BuddyCampaignLine
import com.fireemblem.companion.engine.LevelJournal
import com.fireemblem.companion.importer.ArchiveSaveResult
import com.fireemblem.companion.importer.AtomicBuddyArchiveStore
import com.fireemblem.companion.importer.BuddyArchiveSummary
import com.fireemblem.companion.importer.BuddyBackupException
import com.fireemblem.companion.importer.BuddyBackupInspector
import com.fireemblem.companion.importer.BuddyBackupPreview
import com.fireemblem.companion.personal.PersonalSnapshot
import com.fireemblem.companion.personal.PersonalStore
import com.fireemblem.companion.ui.theme.tabularNumbers
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class AppDestination(val label: String, val icon: ImageVector) {
    Home("首页", Icons.Outlined.Home),
    Atlas("图鉴", Icons.Outlined.Book),
    Guides("攻略", Icons.Outlined.Map),
    Tools("工具", Icons.Outlined.AutoFixHigh),
    Personal("我的", Icons.Outlined.Person),
}

private data class PendingImport(
    val preview: BuddyBackupPreview,
    val duplicate: BuddyArchiveSummary? = null,
)

private data class HomeTile(
    val title: String,
    val description: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

private enum class ToolsShortcutSection {
    AGENT,
    JOURNAL,
    FORECAST,
    SEED_SEARCH,
    TIER_LIST,
    GROWTH_SIMULATOR,
    ROUTE_MERGE,
}

private data class HomeWorkbenchSummary(
    val profileTitle: String,
    val routeLabel: String,
    val journalCount: Int,
    val tierCount: Int,
    val bookmarkCount: Int,
    val conflictCount: Int,
)

@Composable
fun FireEmblemCompanionApp() {
    ReadingPositionProvider { FireEmblemCompanionContent() }
}

@Composable
private fun FireEmblemCompanionContent() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { createStore(context) }
    val activeGameSettings = rememberActiveGameSettings()
    var selectedDestination by rememberSaveable { mutableStateOf(AppDestination.Home) }
    val destinationStateHolder = rememberSaveableStateHolder()
    var archives by remember { mutableStateOf<List<BuddyArchiveSummary>>(emptyList()) }
    var pendingImport by remember { mutableStateOf<PendingImport?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var isReading by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var atlasState by remember { mutableStateOf<AtlasLibraryState>(AtlasLibraryState.NotLoaded) }
    var atlasQuery by rememberSaveable { mutableStateOf("") }
    var selectedWikiCategoryId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedWikiEntryId by rememberSaveable { mutableStateOf<String?>(null) }
    var wikiReturnDestination by rememberSaveable { mutableStateOf<AppDestination?>(null) }
    var wikiExternalDetail by rememberSaveable { mutableStateOf(false) }
    var wikiNavigationSession by rememberSaveable { mutableStateOf(0) }
    var atlasRetryToken by remember { mutableStateOf(0) }
    var toolsShortcut by rememberSaveable { mutableStateOf<ToolsShortcutSection?>(null) }
    var toolsShortcutNonce by rememberSaveable { mutableIntStateOf(0) }
    var routeMergeInitialCharId by rememberSaveable { mutableStateOf<String?>(null) }
    var recentHistoryNonce by remember { mutableIntStateOf(0) }
    var gamePackState by remember { mutableStateOf<GamePackLibraryState>(GamePackLibraryState.Idle) }
    var gamePackRetryToken by remember { mutableIntStateOf(0) }
    var selectedGamePackCategoryId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedGamePackEntryId by rememberSaveable { mutableStateOf<String?>(null) }
    var gamePackQuery by rememberSaveable { mutableStateOf("") }
    var gamePackReturnDestination by rememberSaveable { mutableStateOf<AppDestination?>(null) }
    var gamePackExternalDetail by rememberSaveable { mutableStateOf(false) }
    var agentCitationOriginGameId by rememberSaveable { mutableStateOf<String?>(null) }

    fun switchGame(nextGame: CompanionGame) {
        if (nextGame != activeGameSettings.game) {
            activeGameSettings.setGame(nextGame)
            atlasQuery = ""
            selectedWikiCategoryId = null
            selectedWikiEntryId = null
            wikiReturnDestination = null
            wikiExternalDetail = false
            gamePackQuery = ""
            selectedGamePackCategoryId = null
            selectedGamePackEntryId = null
            gamePackReturnDestination = null
            gamePackExternalDetail = false
        }
    }

    fun openAgentCitation(citation: AgentWikiCitation) {
        val targetGame = CompanionGame.fromId(citation.gameId)
        agentCitationOriginGameId = activeGameSettings.game.id
        if (targetGame == CompanionGame.FORTUNES_WEAVE) {
            if (activeGameSettings.game != CompanionGame.FORTUNES_WEAVE) {
                activeGameSettings.setGame(CompanionGame.FORTUNES_WEAVE)
            }
            wikiExternalDetail = true
            wikiReturnDestination = AppDestination.Tools
            wikiNavigationSession++
            atlasQuery = ""
            selectedWikiCategoryId = citation.categoryId.ifBlank { citation.entryId.substringBefore(':') }
            selectedWikiEntryId = citation.entryId
            selectedDestination = AppDestination.Atlas
        } else {
            if (activeGameSettings.game != targetGame) {
                activeGameSettings.setGame(targetGame)
            }
            gamePackQuery = ""
            selectedGamePackCategoryId = citation.categoryId
            selectedGamePackEntryId = citation.entryId
            gamePackReturnDestination = AppDestination.Tools
            gamePackExternalDetail = true
            selectedDestination = AppDestination.Atlas
        }
    }

    suspend fun reloadArchives() {
        archives = withContext(Dispatchers.IO) { store.list() }
    }

    LaunchedEffect(store) {
        runCatching { reloadArchives() }
            .onFailure { errorMessage = "无法读取本机存档：${it.message ?: "存储异常"}" }
    }

    LaunchedEffect(context, activeGameSettings.game, gamePackRetryToken) {
        val currentGame = activeGameSettings.game
        if (currentGame == CompanionGame.FORTUNES_WEAVE) {
            gamePackState = GamePackLibraryState.Idle
            return@LaunchedEffect
        }
        var opened: GameDataV1Repository? = null
        gamePackState = GamePackLibraryState.Loading
        try {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    GameDataV1Repository.open(context.applicationContext, currentGame.id).also { opened = it }
                }
            }
            gamePackState = result.fold(
                onSuccess = { repo -> GamePackLibraryState.Ready(currentGame.id, repo) },
                onFailure = { err ->
                    GamePackLibraryState.Failed(err.message ?: "${currentGame.shortTitle} 离线资料读取失败，请重试。")
                },
            )
            if (result.isSuccess) awaitCancellation()
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { opened?.close() }
        }
    }

    LaunchedEffect(context, atlasRetryToken) {
        var opened: WikiDatabaseRepository? = null
        atlasState = AtlasLibraryState.Loading
        try {
            val result = withContext(Dispatchers.IO) {
                runCatching { WikiDatabaseRepository.open(context.applicationContext).also { opened = it } }
            }
            atlasState = result.fold(
                onSuccess = AtlasLibraryState::Ready,
                onFailure = { AtlasLibraryState.Failed(it.message ?: "本地资料读取失败，请稍后重试。") },
            )
            if (result.isSuccess) awaitCancellation()
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { opened?.close() }
        }
    }

    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            pendingImport = null
            resultMessage = null
            errorMessage = null
            isReading = true
            scope.launch {
                runCatching {
                    val preview = withContext(Dispatchers.IO) {
                        val bytes = context.contentResolver.openInputStream(uri)?.use(::readBounded)
                            ?: throw IOException("无法读取所选文件")
                        BuddyBackupInspector.inspect(bytes)
                    }
                    pendingImport = PendingImport(preview)
                }.onFailure { failure ->
                    errorMessage = failure.message ?: "文件读取失败，原存档未改动"
                }
                isReading = false
            }
        }
    }

    fun chooseBackup() {
        documentPicker.launch(arrayOf("*/*"))
    }

    fun saveBackup(allowDuplicateCopy: Boolean = false) {
        val draft = pendingImport ?: return
        isSaving = true
        errorMessage = null
        resultMessage = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { store.save(draft.preview, allowDuplicateCopy) }
            }.onSuccess { outcome ->
                when (outcome) {
                    is ArchiveSaveResult.Saved -> {
                        pendingImport = null
                        resultMessage = "已在本机新建 Buddy 凹点存档"
                        reloadArchives()
                    }
                    is ArchiveSaveResult.Duplicate -> pendingImport = draft.copy(duplicate = outcome.existing)
                }
            }.onFailure { failure ->
                errorMessage = failure.message ?: "保存失败，已有存档未改动"
            }
            isSaving = false
        }
    }

    LaunchedEffect(selectedWikiEntryId, atlasState, activeGameSettings.game) {
        val entryId = selectedWikiEntryId ?: return@LaunchedEffect
        val ready = atlasState as? AtlasLibraryState.Ready ?: return@LaunchedEffect
        if (activeGameSettings.game != CompanionGame.FORTUNES_WEAVE) return@LaunchedEffect
        val summary = withContext(Dispatchers.IO) {
            runCatching { ready.repository.entry(entryId) }.getOrNull()?.let { entry ->
                RecentWikiItem(
                    gameId = CompanionGame.FORTUNES_WEAVE.id,
                    entryId = entry.id,
                    title = entry.title,
                    categoryLabel = wikiCategoryLabel(entry.id.substringBefore(':')),
                )
            }
        }
        if (summary != null) {
            RecentWikiHistory.record(context, summary)
            recentHistoryNonce++
        }
    }

    val openToolsShortcut: (ToolsShortcutSection) -> Unit = { section ->
        toolsShortcut = section
        toolsShortcutNonce++
        selectedDestination = AppDestination.Tools
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val expanded = maxWidth >= 840.dp
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                if (!expanded) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        AppDestination.entries.forEach { destination ->
                            NavigationBarItem(
                                selected = selectedDestination == destination,
                                onClick = {
                                    selectedDestination = destination
                                    wikiReturnDestination = null
                                    gamePackReturnDestination = null
                                },
                                icon = {
                                    androidx.compose.material3.Icon(
                                        destination.icon,
                                        contentDescription = destination.label,
                                    )
                                },
                                label = { Text(destination.label) },
                            )
                        }
                    }
                }
            },
        ) { contentPadding ->
            Row(Modifier.fillMaxSize().padding(contentPadding)) {
                if (expanded) {
                    NavigationRail(containerColor = MaterialTheme.colorScheme.background) {
                        Spacer(Modifier.height(16.dp))
                        AppDestination.entries.forEach { destination ->
                            NavigationRailItem(
                                selected = selectedDestination == destination,
                                onClick = {
                                    selectedDestination = destination
                                    wikiReturnDestination = null
                                    gamePackReturnDestination = null
                                },
                                icon = {
                                    androidx.compose.material3.Icon(
                                        destination.icon,
                                        contentDescription = destination.label,
                                    )
                                },
                                label = { Text(destination.label) },
                                alwaysShowLabel = true,
                            )
                        }
                    }
                }
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                ) {
                    when (selectedDestination) {
                        AppDestination.Home -> destinationStateHolder.SaveableStateProvider("home") {
                            if (activeGameSettings.game == CompanionGame.FORTUNES_WEAVE) {
                                HomeScreen(
                                    activeGame = activeGameSettings.game,
                                    onSelectGame = ::switchGame,
                                    archives = archives,
                                    atlasState = atlasState,
                                    recentHistoryNonce = recentHistoryNonce,
                                    onClearRecent = {
                                        RecentWikiHistory.clear(context, activeGameSettings.game.id)
                                        recentHistoryNonce++
                                    },
                                    query = atlasQuery,
                                    onQueryChange = { atlasQuery = it },
                                    onRetryWiki = {
                                        atlasState = AtlasLibraryState.NotLoaded
                                        atlasRetryToken++
                                    },
                                    onImport = { selectedDestination = AppDestination.Tools; chooseBackup() },
                                    onOpenTools = {
                                        toolsShortcut = null
                                        selectedDestination = AppDestination.Tools
                                    },
                                    onOpenToolsSection = openToolsShortcut,
                                    onOpenGuides = { selectedDestination = AppDestination.Guides },
                                    onOpenPersonal = { selectedDestination = AppDestination.Personal },
                                    onOpenAtlas = {
                                        wikiReturnDestination = null
                                        atlasQuery = ""
                                        selectedWikiCategoryId = null
                                        selectedWikiEntryId = null
                                        selectedDestination = AppDestination.Atlas
                                    },
                                    onOpenCategory = { categoryId ->
                                        wikiExternalDetail = false
                                        wikiReturnDestination = AppDestination.Home
                                        wikiNavigationSession++
                                        atlasQuery = ""
                                        selectedWikiCategoryId = categoryId
                                        selectedWikiEntryId = null
                                        selectedDestination = AppDestination.Atlas
                                    },
                                    onOpenRecentEntry = { entryId ->
                                        wikiExternalDetail = true
                                        wikiReturnDestination = AppDestination.Home
                                        wikiNavigationSession++
                                        atlasQuery = ""
                                        selectedWikiCategoryId = entryId.substringBefore(':')
                                        selectedWikiEntryId = entryId
                                        selectedDestination = AppDestination.Atlas
                                    },
                                    onSearch = { search ->
                                        wikiExternalDetail = false
                                        wikiReturnDestination = AppDestination.Home
                                        wikiNavigationSession++
                                        atlasQuery = search
                                        selectedWikiCategoryId = null
                                        selectedWikiEntryId = null
                                        selectedDestination = AppDestination.Atlas
                                    },
                                )
                            } else {
                                GamePackHomeScreen(
                                    game = activeGameSettings.game,
                                    onSelectGame = ::switchGame,
                                    packState = gamePackState,
                                    query = gamePackQuery,
                                    onQueryChange = { gamePackQuery = it },
                                    onRetry = { gamePackRetryToken++ },
                                    onOpenAtlas = {
                                        gamePackReturnDestination = null
                                        selectedGamePackCategoryId = null
                                        selectedGamePackEntryId = null
                                        gamePackQuery = ""
                                        selectedDestination = AppDestination.Atlas
                                    },
                                    onOpenGuides = { selectedDestination = AppDestination.Guides },
                                    onOpenCategory = { catId ->
                                        gamePackReturnDestination = AppDestination.Home
                                        selectedGamePackCategoryId = catId
                                        selectedGamePackEntryId = null
                                        gamePackQuery = ""
                                        selectedDestination = AppDestination.Atlas
                                    },
                                    onSearch = { q ->
                                        gamePackReturnDestination = AppDestination.Home
                                        gamePackExternalDetail = false
                                        selectedGamePackCategoryId = null
                                        selectedGamePackEntryId = null
                                        gamePackQuery = q
                                        selectedDestination = AppDestination.Atlas
                                    },
                                    onOpenEntry = { entryId ->
                                        gamePackReturnDestination = AppDestination.Home
                                        gamePackExternalDetail = true
                                        selectedGamePackCategoryId = null
                                        selectedGamePackEntryId = entryId
                                        gamePackQuery = ""
                                        selectedDestination = AppDestination.Atlas
                                    },
                                    onOpenAgent = { openToolsShortcut(ToolsShortcutSection.AGENT) },
                                )
                            }
                        }
                        AppDestination.Atlas -> destinationStateHolder.SaveableStateProvider("atlas") {
                            if (activeGameSettings.game == CompanionGame.FORTUNES_WEAVE) {
                                when (val state = atlasState) {
                                    AtlasLibraryState.NotLoaded, AtlasLibraryState.Loading -> LoadingPackage()
                                    is AtlasLibraryState.Failed -> FailedPackage(state.message) {
                                        atlasState = AtlasLibraryState.NotLoaded
                                        atlasRetryToken++
                                    }
                                    is AtlasLibraryState.Ready -> androidx.compose.runtime.key(wikiNavigationSession) { WikiCatalogScreen(
                                        repository = state.repository,
                                        query = atlasQuery,
                                        selectedCategoryId = selectedWikiCategoryId,
                                        selectedEntryId = selectedWikiEntryId,
                                        navigationSession = wikiNavigationSession,
                                        categoryBackLabel = when (wikiReturnDestination) {
                                            AppDestination.Home -> "返回首页"
                                            AppDestination.Guides -> "返回攻略"
                                            AppDestination.Tools -> "返回工具"
                                            AppDestination.Personal -> "返回我的记录"
                                            else -> "全部分类"
                                        },
                                        externalBackLabel = when (wikiReturnDestination) {
                                            AppDestination.Home -> if (wikiExternalDetail) "返回首页" else null
                                            AppDestination.Guides -> if (wikiExternalDetail) "返回攻略" else null
                                            AppDestination.Tools -> if (wikiExternalDetail) "返回工具" else null
                                            AppDestination.Personal -> if (wikiExternalDetail) "返回我的记录" else null
                                            else -> null
                                        },
                                        onQueryChange = { atlasQuery = it },
                                        onOpenCategory = { categoryId ->
                                            selectedWikiCategoryId = categoryId
                                            selectedWikiEntryId = null
                                        },
                                        onOpenEntry = { entryId ->
                                            selectedWikiEntryId = entryId
                                        },
                                        onBackToCategories = {
                                            selectedWikiCategoryId = null
                                            selectedWikiEntryId = null
                                            wikiReturnDestination?.let { selectedDestination = it }
                                            wikiReturnDestination = null
                                        },
                                        onCloseEntry = {
                                            selectedWikiEntryId = null
                                            if (wikiExternalDetail) {
                                                val retDest = wikiReturnDestination
                                                retDest?.let { selectedDestination = it }
                                                if (retDest == AppDestination.Tools) {
                                                    agentCitationOriginGameId?.let { orig ->
                                                        val origGame = CompanionGame.fromId(orig)
                                                        if (activeGameSettings.game != origGame) activeGameSettings.setGame(origGame)
                                                    }
                                                    agentCitationOriginGameId = null
                                                }
                                                wikiReturnDestination = null
                                                wikiExternalDetail = false
                                            }
                                        },
                                        onOpenJournal = { openToolsShortcut(ToolsShortcutSection.JOURNAL) },
                                        onOpenTierList = { openToolsShortcut(ToolsShortcutSection.TIER_LIST) },
                                        onOpenRouteMerge = { charId ->
                                            routeMergeInitialCharId = charId
                                            openToolsShortcut(ToolsShortcutSection.ROUTE_MERGE)
                                        },
                                    ) }
                                }
                            } else {
                                when (val ps = gamePackState) {
                                    GamePackLibraryState.Idle, GamePackLibraryState.Loading -> LoadingPackage()
                                    is GamePackLibraryState.Failed -> FailedPackage(ps.message) { gamePackRetryToken++ }
                                    is GamePackLibraryState.Ready -> GamePackCatalogScreen(
                                        game = activeGameSettings.game,
                                        repository = ps.repository,
                                        query = gamePackQuery,
                                        selectedCategoryId = selectedGamePackCategoryId,
                                        selectedEntryId = selectedGamePackEntryId,
                                        onQueryChange = { gamePackQuery = it },
                                        onOpenCategory = { catId ->
                                            selectedGamePackCategoryId = catId
                                            selectedGamePackEntryId = null
                                        },
                                        onOpenEntry = { entryId ->
                                            selectedGamePackEntryId = entryId
                                        },
                                        onBackToCategories = {
                                            selectedGamePackCategoryId = null
                                            selectedGamePackEntryId = null
                                            gamePackReturnDestination?.let { selectedDestination = it }
                                            gamePackReturnDestination = null
                                        },
                                        onCloseEntry = {
                                            selectedGamePackEntryId = null
                                            if (gamePackExternalDetail) {
                                                val retDest = gamePackReturnDestination
                                                retDest?.let { selectedDestination = it }
                                                if (retDest == AppDestination.Tools) {
                                                    agentCitationOriginGameId?.let { orig ->
                                                        val origGame = CompanionGame.fromId(orig)
                                                        if (activeGameSettings.game != origGame) activeGameSettings.setGame(origGame)
                                                    }
                                                    agentCitationOriginGameId = null
                                                }
                                                gamePackReturnDestination = null
                                                gamePackExternalDetail = false
                                            }
                                        },
                                        categoryBackLabel = when (gamePackReturnDestination) {
                                            AppDestination.Home -> "返回首页"
                                            AppDestination.Guides -> "返回攻略"
                                            AppDestination.Tools -> "返回战策参谋"
                                            else -> "全部分类"
                                        },
                                    )
                                }
                            }
                        }
                        AppDestination.Guides -> destinationStateHolder.SaveableStateProvider("guides") {
                            if (activeGameSettings.game == CompanionGame.FORTUNES_WEAVE) {
                                GuidesScreen(
                                    atlasState = atlasState,
                                    onOpenWiki = { entryId ->
                                        wikiExternalDetail = ':' in entryId
                                        wikiReturnDestination = AppDestination.Guides
                                        wikiNavigationSession++
                                        atlasQuery = ""
                                        selectedWikiCategoryId = entryId.substringBefore(':')
                                        selectedWikiEntryId = entryId.takeIf { ':' in it }
                                        selectedDestination = AppDestination.Atlas
                                    },
                                )
                            } else {
                                GamePackGuidesScreen(
                                    game = activeGameSettings.game,
                                    packState = gamePackState,
                                    onRetry = { gamePackRetryToken++ },
                                    onOpenCategory = { catId ->
                                        gamePackReturnDestination = AppDestination.Guides
                                        selectedGamePackCategoryId = catId
                                        selectedGamePackEntryId = null
                                        gamePackQuery = ""
                                        selectedDestination = AppDestination.Atlas
                                    },
                                )
                            }
                        }
                        AppDestination.Tools -> destinationStateHolder.SaveableStateProvider("tools") {
                            ToolsScreen(
                                activeGame = activeGameSettings.game,
                                archiveStore = store,
                                archives = archives,
                                atlasState = atlasState,
                                pendingImport = pendingImport,
                                errorMessage = errorMessage,
                                resultMessage = resultMessage,
                                isReading = isReading,
                                isSaving = isSaving,
                                shortcutSection = toolsShortcut,
                                shortcutNonce = toolsShortcutNonce,
                                routeMergeInitialCharId = routeMergeInitialCharId,
                                onConsumeShortcut = { toolsShortcut = null },
                                onClearRouteMergeChar = { routeMergeInitialCharId = null },
                                onChooseBackup = ::chooseBackup,
                                onSave = { saveBackup() },
                                onSaveDuplicate = { saveBackup(allowDuplicateCopy = true) },
                                onDismissPreview = { pendingImport = null; errorMessage = null },
                                onOpenWiki = { id ->
                                    wikiExternalDetail = ':' in id
                                    wikiReturnDestination = AppDestination.Tools
                                    wikiNavigationSession++
                                    atlasQuery = ""
                                    selectedWikiCategoryId = id.substringBefore(':')
                                    selectedWikiEntryId = id.takeIf { ':' in it }
                                    selectedDestination = AppDestination.Atlas
                                },
                                onOpenWikiCitation = ::openAgentCitation,
                            )
                        }
                        AppDestination.Personal -> destinationStateHolder.SaveableStateProvider("personal") { PersonalScreen(archives, store,
                            wikiRepository = (atlasState as? AtlasLibraryState.Ready)?.repository,
                            onArchivesChanged = { scope.launch { reloadArchives() } },
                            onOpenWiki = { id ->
                                wikiExternalDetail = ':' in id
                                wikiReturnDestination = AppDestination.Personal
                                wikiNavigationSession++
                                atlasQuery = ""
                                selectedWikiCategoryId = id.substringBefore(':'); selectedWikiEntryId = id
                                selectedDestination = AppDestination.Atlas },
                            onOpenRouteMerge = { charId ->
                                routeMergeInitialCharId = charId
                                openToolsShortcut(ToolsShortcutSection.ROUTE_MERGE)
                            }) }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    activeGame: CompanionGame,
    onSelectGame: (CompanionGame) -> Unit,
    archives: List<BuddyArchiveSummary>,
    atlasState: AtlasLibraryState,
    recentHistoryNonce: Int,
    onClearRecent: () -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    onRetryWiki: () -> Unit,
    onImport: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenToolsSection: (ToolsShortcutSection) -> Unit,
    onOpenGuides: () -> Unit,
    onOpenPersonal: () -> Unit,
    onOpenAtlas: () -> Unit,
    onOpenCategory: (String) -> Unit,
    onOpenRecentEntry: (String) -> Unit,
    onSearch: (String) -> Unit,
) {
    val context = LocalContext.current
    val workbench by produceState<HomeWorkbenchSummary?>(null, context, archives) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val store = PersonalStore(context)
                val profileId = context.getSharedPreferences("personal-view", 0)
                    .getString("profile", "global") ?: "global"
                val heads = store.snapshot().heads()
                val profiles = heads.values.mapNotNull { it.singleOrNull() }
                    .filter { it.kind == "profile" && !it.deleted }
                val activeProfile = profiles.firstOrNull { it.entityId == profileId }
                val profileTitle = when {
                    profileId == "global" -> "通用记录"
                    else -> activeProfile?.payload?.get("name")?.jsonPrimitive?.content ?: "通用记录"
                }
                val routeId = activeProfile?.payload?.get("route")?.jsonPrimitive?.content
                val routeLabel = BuddyCampaignLine.entries.firstOrNull { it.id == routeId }?.label
                    ?: "四线通用"
                val activeSingleHeads = heads.values.mapNotNull { it.singleOrNull() }.filter { !it.deleted }
                val journalCount = activeSingleHeads.count { revision ->
                    revision.kind == LevelJournal.KIND &&
                        (profileId == "global" || revision.payload["profileId"]?.jsonPrimitive?.content == profileId)
                }
                val tierCount = activeSingleHeads.count { revision ->
                    revision.kind == "tier" &&
                        (profileId == "global" || revision.payload["profileId"]?.jsonPrimitive?.content == profileId)
                }
                val bookmarkCount = activeSingleHeads.count { revision ->
                    revision.kind == "bookmark" &&
                        (profileId == "global" || revision.payload["profileId"]?.jsonPrimitive?.content == profileId)
                }
                val conflictCount = heads.values.count { it.size > 1 }
                HomeWorkbenchSummary(
                    profileTitle = profileTitle,
                    routeLabel = routeLabel,
                    journalCount = journalCount,
                    tierCount = tierCount,
                    bookmarkCount = bookmarkCount,
                    conflictCount = conflictCount,
                )
            }.getOrNull()
        }
    }
    val recentItems = remember(context, activeGame, recentHistoryNonce) {
        RecentWikiHistory.list(context, activeGame.id).take(6)
    }

    PageColumn("home") {
        GameSwitcherBar(activeGame = activeGame, onSelectGame = onSelectGame)
        LibraryHero(atlasState, onOpenAtlas)

        val fwRepo = (atlasState as? AtlasLibraryState.Ready)?.repository
        FuzzySearchBox(
            query = query,
            onQueryChange = onQueryChange,
            placeholder = "模糊搜角色、职业、技能、道具…",
            suggestionsProvider = { q -> fwRepo?.suggest(q, limit = 7).orEmpty() },
            onSelectSuggestion = { item ->
                onQueryChange("")
                onOpenRecentEntry(item.id)
            },
            onSubmitSearch = { q -> onSearch(q) },
            modifier = Modifier.fillMaxWidth(),
            fieldTestTag = "home-fuzzy-search",
            suggestionKey = fwRepo?.catalog?.sourceRevision,
            shapeRadiusDp = 18,
        )

        workbench?.let { summary ->
            EditorialSectionHeader(
                title = "当前工作台",
                subtitle = "随时接续你的路线、升级记录与私人榜单",
                actionLabel = "切换存档",
                onAction = onOpenPersonal,
            )
            EditorialCard {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(summary.profileTitle, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "当前路线：${summary.routeLabel}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (summary.conflictCount > 0) {
                            FilterChip(
                                selected = false,
                                onClick = onOpenPersonal,
                                modifier = Modifier.heightIn(min = 48.dp),
                                leadingIcon = {
                                    androidx.compose.material3.Icon(
                                        Icons.Outlined.WarningAmber,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                                label = { Text("${summary.conflictCount} 项冲突待确认") },
                            )
                        }
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        EditorialBadge(text = "升级记录 ${summary.journalCount}")
                        EditorialBadge(text = "梯度记录 ${summary.tierCount}")
                        EditorialBadge(text = "收藏 ${summary.bookmarkCount}")
                        EditorialBadge(text = "Buddy 备份 ${archives.size}")
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        OutlinedButton(
                            onClick = { onOpenToolsSection(ToolsShortcutSection.JOURNAL) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text("升级记录") }
                        OutlinedButton(
                            onClick = { onOpenToolsSection(ToolsShortcutSection.TIER_LIST) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text("私人梯度榜") }
                        TextButton(
                            onClick = onOpenGuides,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text("继续路线攻略") }
                    }
                }
            }
        }

        if (recentItems.isNotEmpty()) {
            EditorialSectionHeader(
                title = "最近浏览",
                actionLabel = "清空",
                onAction = onClearRecent,
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                recentItems.forEach { item ->
                    EditorialCard(
                        onClick = { onOpenRecentEntry(item.entryId) },
                        modifier = Modifier.widthIn(min = 148.dp, max = 210.dp),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                item.categoryLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                item.title,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        SectionHeading("常用入口")
        ResponsiveHomeGrid(
            listOf(
                HomeTile("战策参谋", "三作资料、乱数跳过规划与记忆对话", Icons.Outlined.AutoAwesome) {
                    onOpenToolsSection(ToolsShortcutSection.AGENT)
                },
                HomeTile("世界地图", "按篇章探索地点与路线", Icons.Outlined.Map, onOpenGuides),
                HomeTile("培养工具", "升级记录、反推与成长预测", Icons.Outlined.AutoFixHigh, onOpenTools),
                HomeTile("图鉴全书", "按分类检索角色、职业与技能", Icons.Outlined.Book, onOpenAtlas),
            ),
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("图鉴目录", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium)
            TextButton(onClick = onOpenAtlas, modifier = Modifier.heightIn(min = 48.dp)) { Text("全部分类") }
        }
        HomeWikiCatalogContent(atlasState, onRetryWiki, onOpenCategory)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("本机存档", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium)
            TextButton(onClick = onOpenTools, modifier = Modifier.heightIn(min = 48.dp)) { Text("管理存档") }
        }
        if (archives.isNotEmpty()) {
            ArchiveList(archives.take(3))
        } else {
            EmptyStateCard(
                title = "还没有导入的存档",
                description = "导入 Buddy 备份并确认后，记录会保存在本机。",
            )
        }
        OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text("导入 Buddy 备份")
        }
    }
}

@Composable
internal fun LibraryHero(atlasState: AtlasLibraryState, onOpenAtlas: () -> Unit) {
    val categories = (atlasState as? AtlasLibraryState.Ready)?.repository?.catalog?.categories.orEmpty()
    val catalogSummary = when (atlasState) {
        AtlasLibraryState.NotLoaded, AtlasLibraryState.Loading -> "正在打开离线资料"
        is AtlasLibraryState.Failed -> "离线资料暂时不可用"
        is AtlasLibraryState.Ready -> if (categories.isEmpty()) {
            "当前没有可浏览的图鉴分类"
        } else {
            "${categories.size} 类资料  ·  ${categories.sumOf(WikiCategorySummary::entryCount)} 条可查"
        }
    }
    val artwork = rememberMapArtwork()
    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = RoundedCornerShape(24.dp),
    ) {
        Box(Modifier.fillMaxWidth().heightIn(min = 228.dp).clip(RoundedCornerShape(24.dp))) {
            artwork?.let { image ->
                Image(image, contentDescription = null, modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop)
            }
            Box(
                Modifier.matchParentSize()
                    .background(homeHeroScrimColor(MaterialTheme.colorScheme.primary))
                    .testTag("home-hero-scrim"),
            )
            Row(
                Modifier.fillMaxWidth().padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "FIRE EMBLEM  ·  随行资料库",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.82f),
                    )
                    Text("万缕千丝", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("离线查阅图鉴、路线与成长资料。", color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.92f))
                    Text(
                        catalogSummary,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.82f),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Button(
                        onClick = onOpenAtlas,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.primary,
                        ),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("打开图鉴")
                    }
                }
                Surface(
                    modifier = Modifier.size(58.dp),
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.9f),
                    contentColor = MaterialTheme.colorScheme.primary,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        androidx.compose.material3.Icon(Icons.Outlined.Book, null, modifier = Modifier.size(28.dp))
                    }
                }
            }
        }
    }
}

internal fun homeHeroScrimColor(primary: Color): Color = primary.copy(alpha = 0.92f)

@Composable
internal fun HomeWikiCatalogContent(
    state: AtlasLibraryState,
    onRetry: () -> Unit,
    onOpenCategory: (String) -> Unit,
) {
    when (state) {
        AtlasLibraryState.NotLoaded, AtlasLibraryState.Loading -> LoadingPackage()
        is AtlasLibraryState.Failed -> FailedPackage(state.message, onRetry)
        is AtlasLibraryState.Ready -> {
            val categories = state.repository.catalog.categories
            if (categories.isEmpty()) {
                Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(18.dp)) {
                    Text(
                        "当前没有可浏览的图鉴分类。",
                        modifier = Modifier.fillMaxWidth().padding(18.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                ResponsiveHomeGrid(
                    categories.take(4).map { category ->
                        HomeTile(category.title, "${category.entryCount} 条资料", categoryIcon(category.title)) {
                            onOpenCategory(category.id)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ResponsiveHomeGrid(tiles: List<HomeTile>) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = when {
            maxWidth >= 780.dp -> 4
            maxWidth >= 540.dp -> 3
            else -> 2
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            tiles.chunked(columns).forEach { rowTiles ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    rowTiles.forEach { tile -> HomeFeatureCard(tile, Modifier.weight(1f)) }
                    repeat(columns - rowTiles.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun HomeFeatureCard(tile: HomeTile, modifier: Modifier = Modifier) {
    Card(
        onClick = tile.onClick,
        modifier = modifier.heightIn(min = 108.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Surface(
                modifier = Modifier.size(38.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    androidx.compose.material3.Icon(tile.icon, contentDescription = null, modifier = Modifier.size(20.dp))
                }
            }
            Text(tile.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(tile.description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun FeaturedActionCard(title: String, description: String, icon: ImageVector, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                Box(Modifier.size(58.dp), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(30.dp),
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(description, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.88f))
            }
        }
    }
}

@Composable
internal fun GuidesScreen(atlasState: AtlasLibraryState, onOpenWiki: (String) -> Unit) {
    val subpages = rememberSaveableStateHolder()
    var mapOpen by rememberSaveable { mutableStateOf(false) }
    var campaignOpen by rememberSaveable { mutableStateOf(false) }
    var campaignInitialKind by rememberSaveable { mutableStateOf<CampaignTaskKind?>(null) }
    var beginnerOpen by rememberSaveable { mutableStateOf(false) }
    var endingsOpen by rememberSaveable { mutableStateOf(false) }
    var giftsOpen by rememberSaveable { mutableStateOf(false) }
    var diningOpen by rememberSaveable { mutableStateOf(false) }
    var birdDialoguesOpen by rememberSaveable { mutableStateOf(false) }
    var supportsOpen by rememberSaveable { mutableStateOf(false) }
    var plantingOpen by rememberSaveable { mutableStateOf(false) }
    var restorationOpen by rememberSaveable { mutableStateOf(false) }
    var theaterOpen by rememberSaveable { mutableStateOf(false) }
    var mechanicsOpen by rememberSaveable { mutableStateOf(false) }
    var mapChapter by rememberSaveable { mutableStateOf<String?>(null) }
    var mapPoint by rememberSaveable { mutableStateOf<String?>(null) }
    if (mechanicsOpen) {
        subpages.SaveableStateProvider("mechanics-guide") {
            MechanicsGuideScreen(
                repository = (atlasState as? AtlasLibraryState.Ready)?.repository,
                onBack = { mechanicsOpen = false },
            )
        }
        return
    }
    if (theaterOpen) {
        subpages.SaveableStateProvider("theater-guide") {
            TheaterGuideScreen(
                repository = (atlasState as? AtlasLibraryState.Ready)?.repository,
                onBack = { theaterOpen = false },
                onOpenWiki = onOpenWiki,
            )
        }
        return
    }
    if (restorationOpen) {
        subpages.SaveableStateProvider("restoration-guide") {
            RestorationGuideScreen(
                repository = (atlasState as? AtlasLibraryState.Ready)?.repository,
                onBack = { restorationOpen = false },
                onOpenWiki = onOpenWiki,
            )
        }
        return
    }
    if (birdDialoguesOpen) {
        subpages.SaveableStateProvider("bird-dialogues") {
            BirdDialoguesGuideScreen(
                repository = (atlasState as? AtlasLibraryState.Ready)?.repository,
                onBack = { birdDialoguesOpen = false },
            )
        }
        return
    }
    if (supportsOpen) {
        subpages.SaveableStateProvider("support-guide") {
            SupportGuideScreen(
                repository = (atlasState as? AtlasLibraryState.Ready)?.repository,
                onBack = { supportsOpen = false },
                onOpenWiki = onOpenWiki,
            )
        }
        return
    }
    if (plantingOpen && !mapOpen) {
        subpages.SaveableStateProvider("planting-guide") {
            PlantingGuideScreen(
                repository = (atlasState as? AtlasLibraryState.Ready)?.repository,
                onBack = { plantingOpen = false },
                onOpenMap = { chapterId, sourceMapId ->
                    mapChapter = chapterId
                    mapPoint = sourceMapId
                    mapOpen = true
                },
                onOpenWiki = onOpenWiki,
            )
        }
        return
    }
    if (diningOpen) {
        subpages.SaveableStateProvider("dining-guide") {
            DiningGuideScreen(
                repository = (atlasState as? AtlasLibraryState.Ready)?.repository,
                onBack = { diningOpen = false },
            )
        }
        return
    }
    if (giftsOpen) {
        subpages.SaveableStateProvider("gift-guide") {
            GiftGuideScreen(
                repository = (atlasState as? AtlasLibraryState.Ready)?.repository,
                onBack = { giftsOpen = false },
            )
        }
        return
    }
    if (endingsOpen) {
        subpages.SaveableStateProvider("endings") {
        EndingsGuideScreen(
            repository = (atlasState as? AtlasLibraryState.Ready)?.repository,
            onBack = { endingsOpen = false },
            onOpenWiki = onOpenWiki,
        )
        }
        return
    }
    if (beginnerOpen && !mapOpen) {
        subpages.SaveableStateProvider("beginner") {
        BeginnerGuideScreen(
            repository = (atlasState as? AtlasLibraryState.Ready)?.repository,
            onBack = { beginnerOpen = false },
            onOpenWorldMap = {
                mapChapter = null
                mapPoint = null
                mapOpen = true
            },
        )
        }
        return
    }
    if (campaignOpen) {
        subpages.SaveableStateProvider("campaign") {
        CampaignGuideScreen(
            repository = (atlasState as? AtlasLibraryState.Ready)?.repository,
            onBack = {
                campaignOpen = false
                campaignInitialKind = null
            },
            onOpenWiki = onOpenWiki,
            initialKind = campaignInitialKind,
        )
        }
        return
    }
    if (mapOpen) {
        subpages.SaveableStateProvider("map") {
        androidx.activity.compose.BackHandler { mapOpen = false }
        WorldMapScreen(onBack = { mapOpen = false }, initialChapterId = mapChapter, initialPointId = mapPoint)
        }
        return
    }
    PageColumn("guides/menu") {
        ScreenHeading("攻略", "按主线探索、日常交互与人物外传分组查阅。")
        MapPreviewCard(onClick = { mapChapter = null; mapPoint = null; mapOpen = true })
        SectionHeading("路线与任务")
        ResponsiveHomeGrid(
            listOf(
                HomeTile("四线任务清单", "按路线追踪支线、外传和完成状态", Icons.Outlined.CheckCircleOutline) {
                    campaignInitialKind = null
                    campaignOpen = true
                },
                HomeTile("事件时间轴", "按主线阶段查看节点事件与期限", Icons.Outlined.Timeline) {
                    campaignInitialKind = CampaignTaskKind.TIMELINE
                    campaignOpen = true
                },
                HomeTile("迪篇挑战书", "查阅迪篇专属挑战目标与报酬", Icons.Outlined.AutoFixHigh) {
                    campaignInitialKind = CampaignTaskKind.CHALLENGE
                    campaignOpen = true
                },
                HomeTile("新手指南", "路线结构、队伍培养与常见问题", Icons.Outlined.Book) {
                    beginnerOpen = true
                },
                HomeTile("机制与成长公式", "19 类系统机制、7 节公式与固定成长试算", Icons.Outlined.AutoFixHigh) {
                    mechanicsOpen = true
                },
            ),
        )
        SectionHeading("日常与社交")
        ResponsiveHomeGrid(
            listOf(
                HomeTile("礼物查询", "按角色或礼物反查已记录的偏好", Icons.Outlined.Inventory2) {
                    giftsOpen = true
                },
                HomeTile("用餐搭配", "查看角色口味，挑选三人共同喜欢的料理", Icons.Outlined.Restaurant) {
                    diningOpen = true
                },
                HomeTile("支援关系", "查角色组合、职业补正与对话条件", Icons.Outlined.Groups) {
                    supportsOpen = true
                },
                HomeTile("逗鸟问答", "按角色找对话与推荐反应", Icons.Outlined.ChatBubbleOutline) {
                    birdDialoguesOpen = true
                },
                HomeTile("种植与收获", "按地点找植物，并查看肥料获取点", Icons.Outlined.Map) {
                    plantingOpen = true
                },
                HomeTile("复兴清单", "记录材料库存，查看待办地点与材料缺口", Icons.Outlined.Inventory2) {
                    restorationOpen = true
                },
                HomeTile("剧场演出", "查演技选项、评价报酬与每周活动", Icons.Outlined.TheaterComedy) {
                    theaterOpen = true
                },
            ),
        )
        SectionHeading("剧情、外传与人物")
        ResponsiveHomeGrid(
            listOf(
                HomeTile("外传", "解锁条件与任务奖励", Icons.Outlined.History) { onOpenWiki("paralogues") },
                HomeTile("支线任务", "触发地点、期限与奖励", Icons.Outlined.EditNote) { onOpenWiki("side-quests") },
                HomeTile("角色图鉴", "角色头像、成长率与招募信息", Icons.Outlined.Person) { onOpenWiki("characters") },
                HomeTile("结局与后日谈", "按角色查找，阅读前由你确认剧透", Icons.Outlined.History) {
                    endingsOpen = true
                },
            ),
        )
    }
}

@Composable
private fun MapPreviewCard(onClick: () -> Unit) {
    val image = rememberMapArtwork()
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(188.dp).clip(RoundedCornerShape(24.dp))) {
            image?.let { preview ->
                Image(preview, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } ?: Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.primaryContainer) {
                Box(contentAlignment = Alignment.Center) {
                    androidx.compose.material3.Icon(
                        Icons.Outlined.Map,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Surface(
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
                color = Color(0xCC1F3447),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(color = Color.White.copy(alpha = 0.16f), shape = CircleShape) {
                        Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                            androidx.compose.material3.Icon(
                                Icons.Outlined.Map,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("主线世界地图", style = MaterialTheme.typography.titleLarge,
                            color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text("按篇章探索地点、记录进度并规划路线", style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.88f))
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberMapArtwork(): ImageBitmap? {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(null, context) {
        value = try {
            withContext(Dispatchers.IO) {
                val options = BitmapFactory.Options().apply { inSampleSize = 2 }
                context.assets.open("world-map/terrain.webp").use { stream ->
                    BitmapFactory.decodeStream(stream, null, options)?.asImageBitmap()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
    return image
}

@Composable
private fun ToolsScreen(
    activeGame: CompanionGame,
    archiveStore: AtomicBuddyArchiveStore,
    archives: List<BuddyArchiveSummary>,
    atlasState: AtlasLibraryState,
    pendingImport: PendingImport?,
    errorMessage: String?,
    resultMessage: String?,
    isReading: Boolean,
    isSaving: Boolean,
    shortcutSection: ToolsShortcutSection?,
    shortcutNonce: Int,
    routeMergeInitialCharId: String?,
    onConsumeShortcut: () -> Unit,
    onClearRouteMergeChar: () -> Unit,
    onChooseBackup: () -> Unit,
    onSave: () -> Unit,
    onSaveDuplicate: () -> Unit,
    onDismissPreview: () -> Unit,
    onOpenWiki: (String) -> Unit,
    onOpenWikiCitation: (AgentWikiCitation) -> Unit,
) {
    var showAgent by rememberSaveable { mutableStateOf(false) }
    val agentSession = remember { CompanionAgentSessionHolder() }
    var showForecast by rememberSaveable { mutableStateOf(false) }
    var forecastInitialContext by remember { mutableStateOf<SeedWorkbenchLaunchContext?>(null) }
    var showSeedSearch by rememberSaveable { mutableStateOf(false) }
    var showLevelJournal by rememberSaveable { mutableStateOf(false) }
    var journalInitialSelectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var showTierList by rememberSaveable { mutableStateOf(false) }
    var showMechanics by rememberSaveable { mutableStateOf(false) }
    var showGrowthSimulator by rememberSaveable { mutableStateOf(false) }
    var showRouteMerge by rememberSaveable { mutableStateOf(false) }
    var toolsJournalRefresh by remember { mutableIntStateOf(0) }
    val appContext = LocalContext.current
    val visuals = rememberBuddyVisuals()
    val toolsJournals by produceState<Pair<com.fireemblem.companion.engine.BuddyRules?, List<Pair<String, LevelJournal>>>>(
        initialValue = null to emptyList(),
        key1 = toolsJournalRefresh,
        key2 = showLevelJournal,
        key3 = showSeedSearch,
    ) {
        if (!showLevelJournal && !showSeedSearch && !showForecast) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    val rules = com.fireemblem.companion.engine.BuddyRules.bundled()
                    val profile = appContext.getSharedPreferences("personal-view", 0)
                        .getString("profile", "global") ?: "global"
                    val store = PersonalStore(appContext.applicationContext)
                    val list = store.snapshot().heads().values
                        .mapNotNull { it.singleOrNull()?.takeIf { rev -> !rev.deleted && rev.kind == LevelJournal.KIND } }
                        .mapNotNull { rev -> runCatching { rev.entityId to LevelJournal.decode(rev.payload) }.getOrNull() }
                        .filter { it.second.profile == profile }
                        .sortedByDescending { it.second.observations.size }
                    rules to list
                }.getOrDefault(null to emptyList())
            }
        }
    }
    LaunchedEffect(shortcutNonce, shortcutSection) {
        when (shortcutSection) {
            ToolsShortcutSection.AGENT -> {
                showAgent = true
                showLevelJournal = false
                showForecast = false
                showSeedSearch = false
                showTierList = false
                showMechanics = false
                showGrowthSimulator = false
                showRouteMerge = false
                onConsumeShortcut()
            }
            ToolsShortcutSection.JOURNAL -> {
                journalInitialSelectedId = null
                showAgent = false
                showLevelJournal = true
                showForecast = false
                showSeedSearch = false
                showTierList = false
                showMechanics = false
                showGrowthSimulator = false
                showRouteMerge = false
                onConsumeShortcut()
            }
            ToolsShortcutSection.FORECAST -> {
                showAgent = false
                showForecast = true
                showLevelJournal = false
                showSeedSearch = false
                showTierList = false
                showMechanics = false
                showGrowthSimulator = false
                showRouteMerge = false
                onConsumeShortcut()
            }
            ToolsShortcutSection.SEED_SEARCH -> {
                showAgent = false
                showSeedSearch = true
                showLevelJournal = false
                showForecast = false
                showTierList = false
                showMechanics = false
                showGrowthSimulator = false
                showRouteMerge = false
                onConsumeShortcut()
            }
            ToolsShortcutSection.TIER_LIST -> {
                showAgent = false
                showTierList = true
                showLevelJournal = false
                showForecast = false
                showSeedSearch = false
                showMechanics = false
                showGrowthSimulator = false
                showRouteMerge = false
                onConsumeShortcut()
            }
            ToolsShortcutSection.GROWTH_SIMULATOR -> {
                showAgent = false
                showGrowthSimulator = true
                showLevelJournal = false
                showForecast = false
                showSeedSearch = false
                showTierList = false
                showMechanics = false
                showRouteMerge = false
                onConsumeShortcut()
            }
            ToolsShortcutSection.ROUTE_MERGE -> {
                showAgent = false
                showRouteMerge = true
                showLevelJournal = false
                showForecast = false
                showSeedSearch = false
                showTierList = false
                showMechanics = false
                showGrowthSimulator = false
                onConsumeShortcut()
            }
            null -> Unit
        }
    }
    val agentStateHolder = rememberSaveableStateHolder()
    val journalStateHolder = rememberSaveableStateHolder()
    val forecastStateHolder = rememberSaveableStateHolder()
    val seedSearchStateHolder = rememberSaveableStateHolder()
    val tierListStateHolder = rememberSaveableStateHolder()
    val mechanicsStateHolder = rememberSaveableStateHolder()
    val growthSimStateHolder = rememberSaveableStateHolder()
    val routeMergeStateHolder = rememberSaveableStateHolder()
    if (showAgent && pendingImport == null) {
        agentStateHolder.SaveableStateProvider("tools-agent") {
            CompanionAgentScreen(
                activeGame = activeGame,
                fwRepository = (atlasState as? AtlasLibraryState.Ready)?.repository,
                session = agentSession,
                onBack = { showAgent = false },
                onOpenWikiCitation = onOpenWikiCitation,
                onOpenWorkbench = { ctx ->
                    forecastInitialContext = ctx
                    showAgent = false
                    showForecast = true
                },
            )
        }
        return
    }
    if (showRouteMerge && pendingImport == null) {
        routeMergeStateHolder.SaveableStateProvider("tools-route-merge") {
            RouteMergeScreen(
                repository = (atlasState as? AtlasLibraryState.Ready)?.repository,
                onBack = {
                    showRouteMerge = false
                    onClearRouteMergeChar()
                },
                onOpenWiki = onOpenWiki,
                initialCharacterId = routeMergeInitialCharId,
            )
        }
        return
    }
    if (showGrowthSimulator && pendingImport == null) {
        growthSimStateHolder.SaveableStateProvider("tools-growth-sim") {
            GrowthSimulatorScreen(
                repository = (atlasState as? AtlasLibraryState.Ready)?.repository,
                onBack = { showGrowthSimulator = false },
                onOpenFixedCalc = {
                    showGrowthSimulator = false
                    showMechanics = true
                },
            )
        }
        return
    }
    if (showMechanics && pendingImport == null) {
        mechanicsStateHolder.SaveableStateProvider("tools-mechanics") {
            MechanicsGuideScreen(
                repository = (atlasState as? AtlasLibraryState.Ready)?.repository,
                onBack = {
                    showMechanics = false
                    showGrowthSimulator = true
                },
                initialTabId = "fixed-calc",
            )
        }
        return
    }
    if (showTierList && pendingImport == null) {
        tierListStateHolder.SaveableStateProvider("tier-list") {
            PersonalTierListScreen(
                repository = (atlasState as? AtlasLibraryState.Ready)?.repository,
                onBack = { showTierList = false },
                onOpenWiki = onOpenWiki,
            )
        }
        return
    }
    if (showLevelJournal && pendingImport == null) {
        journalStateHolder.SaveableStateProvider("journals") {
            LevelJournalScreen(
                archiveStore = archiveStore,
                initialSelectedId = journalInitialSelectedId,
                onBack = {
                    showLevelJournal = false
                    journalInitialSelectedId = null
                    toolsJournalRefresh++
                },
            )
        }
        return
    }
    if (showForecast && pendingImport == null) {
        forecastStateHolder.SaveableStateProvider("forecast") {
            SeedForecastScreen(
                onBack = {
                    val returnToSearch = forecastInitialContext?.backLabel == "返回反推结果"
                    val returnToAgent = forecastInitialContext?.backLabel == "返回战策参谋"
                    forecastInitialContext = null
                    showForecast = false
                    if (returnToSearch) showSeedSearch = true
                    if (returnToAgent) showAgent = true
                    toolsJournalRefresh++
                },
                initialContext = forecastInitialContext,
            )
        }
        return
    }
    if (showSeedSearch && pendingImport == null) {
        seedSearchStateHolder.SaveableStateProvider("seed-search") {
            SeedSearchScreen(
                archiveStore = archiveStore,
                archives = archives,
                onBack = {
                    showSeedSearch = false
                    toolsJournalRefresh++
                },
                onChooseBackup = onChooseBackup,
                onOpenRecords = {
                    showSeedSearch = false
                    journalInitialSelectedId = null
                    showLevelJournal = true
                },
                onPreviewCandidate = { ctx ->
                    forecastInitialContext = ctx
                    showSeedSearch = false
                    showForecast = true
                },
            )
        }
        return
    }
    PageColumn("tools/menu") {
        ScreenHeading("工具", "战策参谋、乱数养成、四线融合、成长模拟与梯度榜，清晰直达。")
        if (pendingImport == null) {
            // Top Companion Agent Card
            EditorialCard(
                onClick = { showAgent = true },
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
                modifier = Modifier.fillMaxWidth().testTag("tools-agent-hub"),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                        androidx.compose.material3.Icon(
                            Icons.Outlined.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.padding(10.dp).size(22.dp),
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("战策参谋", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "三作离线图鉴 · 三作专属成长规划 · 多模型记忆对话",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text("打开 ›", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
            }

            // Core Pillar 1: Unified RNG Seed & Character Growth Hub (consolidates Journal + Seed Search + Lv.99 Preview + Auto-Planner!)
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier.fillMaxWidth().testTag("tools-rng-hub"),
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                                androidx.compose.material3.Icon(
                                    Icons.Outlined.AutoFixHigh,
                                    contentDescription = null,
                                    modifier = Modifier.padding(9.dp).size(20.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                            Column {
                                Text("乱数养成工作台", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "记录升级 · GPU 极速反推 · 99 级预览与自动规划",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        TextButton(
                            onClick = {
                                forecastInitialContext = null
                                showForecast = true
                            },
                            modifier = Modifier.heightIn(min = 38.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) { Text("输种子预览", maxLines = 1, softWrap = false) }
                    }

                    val (loadedRules, activeJournals) = toolsJournals
                    if (activeJournals.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            activeJournals.take(3).forEach { (entityId, j) ->
                                val charName = loadedRules?.characters?.firstOrNull { it.id == j.characterId }?.let {
                                    characterLabel(it.id, it.name)
                                } ?: j.characterId
                                val cp = j.takeIf { it.hasCurrentSeedCheckpoint() }?.seedCheckpoint
                                Surface(
                                    onClick = {
                                        journalInitialSelectedId = entityId
                                        showLevelJournal = true
                                    },
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        BuddyPortrait(
                                            characterId = j.characterId,
                                            name = charName,
                                            visuals = visuals,
                                            modifier = Modifier.size(44.dp),
                                            part3 = j.observations.lastOrNull()?.part3 == true,
                                        )
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(
                                                "$charName · ${j.name}",
                                                style = MaterialTheme.typography.titleSmall,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                "${j.line.label} · Lv.${j.currentLevel ?: 1} · " +
                                                    if (cp != null) "种子 ${cp.seed}" else "${j.observations.count { !it.skipped && it.exam == null && it.professionChangeFrom == null }} 次升级",
                                                style = MaterialTheme.typography.labelSmall.tabularNumbers(),
                                                color = if (cp != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        if (cp != null && cp.level < 99) {
                                            Button(
                                                onClick = {
                                                    forecastInitialContext = SeedWorkbenchLaunchContext(
                                                        characterId = j.characterId,
                                                        campaignLine = j.line,
                                                        part3 = cp.part3,
                                                        seed = cp.seed,
                                                        inferredLevel = cp.level,
                                                        profession = cp.profession,
                                                        ride = j.observations.lastOrNull()?.ride,
                                                        unlockedProfessions = j.confirmedUnlockedProfessions() + cp.profession,
                                                        baseStats = cp.stats,
                                                        caps = cp.caps,
                                                        sourceLabel = "${j.line.label} · ${j.name}",
                                                        backLabel = "返回工具",
                                                        autoRun = true,
                                                    )
                                                    showForecast = true
                                                },
                                                modifier = Modifier.heightIn(min = 36.dp),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            ) { Text("预览规划", style = MaterialTheme.typography.labelMedium) }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "录入 4 次升级即可 GPU 极速定位种子，解锁 99 级乱数预览、换职对比与自动转职规划。",
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { showSeedSearch = true },
                            modifier = Modifier.weight(1f).heightIn(min = 46.dp).testTag("tools-open-seed-search"),
                        ) { Text("反推乱数种子") }
                        OutlinedButton(
                            onClick = {
                                journalInitialSelectedId = null
                                showLevelJournal = true
                            },
                            modifier = Modifier.weight(1f).heightIn(min = 46.dp).testTag("tools-open-journals"),
                        ) {
                            Text(if (activeJournals.isEmpty()) "升级记录册" else "全部记录 (${activeJournals.size})")
                        }
                    }
                }
            }

            // Core Pillars 2–4: Strictly 3 Clean, Non-Overlapping Utility Cards!
            SectionHeading("实用工具")
            ResponsiveHomeGrid(
                listOf(
                    HomeTile("多线融合面板", "对比同一角色在四条路线的等级与九项属性", Icons.AutoMirrored.Outlined.CompareArrows) {
                        onClearRouteMergeChar()
                        showRouteMerge = true
                    },
                    HomeTile("成长模拟与试算", "多阶段转职期望、固定成长余数与对比导出", Icons.Outlined.Timeline) {
                        showGrowthSimulator = true
                    },
                    HomeTile("私人梯度榜", "按个人喜好给三作角色分档、排序与写评价", Icons.Outlined.Leaderboard) {
                        showTierList = true
                    },
                ),
            )
        } else {
            ImportPreviewCard(
                pending = pendingImport,
                isSaving = isSaving,
                onSave = onSave,
                onSaveDuplicate = onSaveDuplicate,
                onDismiss = onDismissPreview,
            )
        }
        errorMessage?.let { NoticeCard(it, isError = true) }
        resultMessage?.let { NoticeCard(it, isError = false) }
    }
}

@Composable
private fun ImportPreviewCard(
    pending: PendingImport,
    isSaving: Boolean,
    onSave: () -> Unit,
    onSaveDuplicate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val preview = pending.preview
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("检查结果", style = MaterialTheme.typography.titleLarge)
            Text("FE_Rigging_Buddy · v${preview.sourceVersion}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            PreviewMetric("角色", "${preview.activeCharacterCount} 名在册 / ${preview.deletedCharacterCount} 名已删除")
            PreviewMetric("记录", "${preview.recordCount} 条，其中 ${preview.skippedRecordCount} 次跳过、${preview.upgradedRecordCount} 次已升级")
            PreviewMetric("种子", "${preview.seedCount} 个；${preview.highSeedCount} 个使用 uint32 高位")
            if (preview.unknownFieldCount > 0) {
                NoticeCard(
                    "发现 ${preview.unknownFieldCount} 个当前尚未映射的字段。导入会完整保留这些字段，但暂不用于计算。",
                    isError = false,
                )
            }
            pending.duplicate?.let {
                NoticeCard("这份文件已经导入过。你可以取消，或明确选择再保存一份副本。", isError = false)
            }
            Button(
                onClick = if (pending.duplicate == null) onSave else onSaveDuplicate,
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(when {
                    isSaving -> "正在保存…"
                    pending.duplicate != null -> "仍然保存为新存档副本"
                    else -> "确认并新建存档"
                })
            }
            OutlinedButton(onClick = onDismiss, enabled = !isSaving, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("取消")
            }
        }
    }
}

@Composable
private fun PersonalScreen(archives: List<BuddyArchiveSummary>, store: AtomicBuddyArchiveStore,
    onArchivesChanged: () -> Unit, onOpenWiki: (String) -> Unit,
    wikiRepository: com.fireemblem.companion.content.WikiRepository?,
    onOpenRouteMerge: ((String?) -> Unit)? = null) {
    var sourcesExpanded by rememberSaveable { mutableStateOf(false) }
    var personalDataReady by remember { mutableStateOf(false) }
    var theaterPlayId by rememberSaveable { mutableStateOf<String?>(null) }
    var tierEntityId by rememberSaveable { mutableStateOf<String?>(null) }
    val personalPages = rememberSaveableStateHolder()
    if (theaterPlayId != null) {
        TheaterGuideScreen(wikiRepository, onBack = { theaterPlayId = null }, onOpenWiki = onOpenWiki, initialPlayId = theaterPlayId)
        return
    }
    if (tierEntityId != null) {
        PersonalTierListScreen(wikiRepository, onBack = { tierEntityId = null }, onOpenWiki = onOpenWiki, initialEntityId = tierEntityId)
        return
    }
    personalPages.SaveableStateProvider("personal-manager") { PageColumn("personal", ready = personalDataReady) {
        ScreenHeading("我的", "管理个人路线、已保存资料与多设备备份。")
        PersonalManager(
            store,
            onArchivesChanged,
            onOpenWiki,
            onContentReady = { personalDataReady = true },
            onOpenTheater = { personalDataReady = false; theaterPlayId = it },
            onOpenTier = { personalDataReady = false; tierEntityId = it },
            onOpenRouteMerge = onOpenRouteMerge,
        )
        if (archives.isNotEmpty()) {
            SectionHeading("导入的 Buddy 备份")
            Text("兼容性备份保留在本机；个人记录可通过上方的加密备份与 WebDAV 同步。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            ArchiveList(archives)
        } else {
            EmptyStateCard("还没有导入 Buddy 备份", "升级记录、收藏与路线进度可在上方个人存档中管理。")
        }
        Card(
            onClick = { sourcesExpanded = !sourcesExpanded },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("来源与权利", style = MaterialTheme.typography.titleMedium)
                        Text("保留算法、资料与美术素材的出处说明", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(if (sourcesExpanded) "收起" else "查看", color = MaterialTheme.colorScheme.primary)
                }
                if (sourcesExpanded) {
                    SourceCard(
                        title = "《万缕千丝》图鉴与攻略数据",
                        source = "社区在线数据表 · KeenGamer · RaiderKing",
                        details = listOf(
                            "基础角色、职业、血印、技能、战技、魔法、武器装备、外传支线、坐骑军团、礼物料理、时间轴与挑战书数据整理自《火焰之纹章 万紫千红》社区在线数据表。",
                            "逗鸟问答参考 KeenGamer 与 RaiderKing 的 Perfect Bird Time 攻略页（经社区在线表交叉核对）。",
                        ),
                        links = listOf(
                            "腾讯文档《火焰之纹章 万紫千红》在线数据表" to "https://docs.qq.com/sheet/DV0N0VUZLSXRmUWFq?tab=lnafol",
                            "KeenGamer" to "https://www.keengamer.com/",
                            "RaiderKing" to "https://raiderking.com/",
                        ),
                    )
                    SourceCard(
                        title = "《万缕千丝》地图与专题资料",
                        source = "fireemblem.top",
                        details = listOf(
                            "主线世界地图地形与路网点位（/api/world-map）、结局后日谈、种植收获、支援关系、19 类系统机制与成长公式、成长模拟器参考数据均整理自 fireemblem.top。",
                        ),
                        links = listOf(
                            "fireemblem.top 首页" to "https://fireemblem.top/",
                            "世界地图数据" to "https://fireemblem.top/api/world-map",
                            "结局与后日谈" to "https://fireemblem.top/topics/endings",
                            "种植与收获" to "https://fireemblem.top/database/planting",
                            "支援关系" to "https://fireemblem.top/archive/supports",
                            "机制与成长公式" to "https://fireemblem.top/archive/mechanics",
                        ),
                    )
                    SourceCard(
                        title = "《万缕千丝》乱数算法与备份格式参考",
                        source = "FE_Rigging_Buddy",
                        details = listOf(
                            "作者 GitHub：@kskdmcph58-lgtm",
                            "参考提交：016f048b5c636035ac0b56b0c4b9d89a742fba4a",
                            "参考其乱数种子反推、成长预测规则基线、Buddy 备份格式与角色头像索引；当前规则基线按「凯伊篇」与「其它路线」区分。",
                        ),
                        links = listOf(
                            "FE_Rigging_Buddy GitHub 仓库" to "https://github.com/kskdmcph58-lgtm/FE_Rigging_Buddy",
                        ),
                    )
                    SourceCard(
                        title = "《火焰纹章：风花雪月》离线资料包",
                        source = "Fire Emblem Wiki · Fandom · Serenes Forest · Fedatamine · Samurai Gamers · 萌娘百科",
                        details = listOf(
                            "角色、职业、属性与成长率、武器装备、技能战技、骑士团、章节与修道院资料整理自 Fire Emblem Wiki（GFDL 1.3）。",
                            "部分立绘与点阵素材参考 Fire Emblem Wiki (Fandom)；职业成长率与骑士团参考 Serenes Forest 交叉核对。",
                            "官方简体中文支援对话、课堂教导问答、烦恼信箱、失物与茶会主题/对话参考 Fedatamine；茶会第 4 轮应答答案参考 Samurai Gamers；部分中文译名参考萌娘百科（CC BY-NC-SA 4.0）。",
                        ),
                        links = listOf(
                            "Fire Emblem Wiki (fireemblemwiki.org)" to "https://fireemblemwiki.org/",
                            "Fire Emblem Wiki (Fandom)" to "https://fireemblem.fandom.com/",
                            "Serenes Forest (Three Houses)" to "https://serenesforest.net/three-houses/",
                            "Fedatamine (简体中文资料)" to "https://houses.fedatamine.com/zh-cn/",
                            "Samurai Gamers (Tea Party)" to "https://samurai-gamers.com/fire-emblem-three-houses/",
                            "萌娘百科 · 火焰之纹章 风花雪月" to "https://zh.moegirl.org.cn/%E7%81%AB%E7%84%B0%E4%B9%8B%E7%BA%B9%E7%AB%A0_%E9%A3%8E%E8%8A%B1%E9%9B%AA%E6%9C%88",
                        ),
                    )
                    SourceCard(
                        title = "《火焰纹章：Engage》离线资料包",
                        source = "社区在线数据表 · Fire Emblem Wiki",
                        details = listOf(
                            "角色、职业基础值/成长率/上限、纹章士、牵绊戒指、特技、武器装备、索拉涅尔活动与成就数据整理自《火焰之纹章 ENGAGE结合》社区在线数据表。",
                            "章节索引与部分图标资源参考 Fire Emblem Wiki（GFDL 1.3）。",
                        ),
                        links = listOf(
                            "Fire Emblem Wiki · Engage 章节列表" to "https://fireemblemwiki.org/wiki/List_of_chapters_in_Fire_Emblem_Engage",
                        ),
                    )
                    SourceCard(
                        title = "端侧 OCR 模型与核心开源组件",
                        source = "RapidOCR / PaddleOCR PP-OCRv4 · ONNX Runtime · OkHttp · Anthropic SDK",
                        details = listOf(
                            "本地 OCR 采用 RapidOCR / PaddleOCR PP-OCRv4 移动端中文 ONNX 模型，由用户在设置中按需下载（带 SHA-256 校验），不随安装包预装。",
                            "客户端基于 Jetpack Compose、Material 3、ONNX Runtime Android、OkHttp 与 Anthropic Java SDK 构建。",
                        ),
                        links = listOf(
                            "RapidOCR 模型 (ModelScope)" to "https://www.modelscope.cn/models/RapidAI/RapidOCR",
                            "RapidOCR 模型 (Hugging Face)" to "https://huggingface.co/RapidAI/RapidOCR",
                            "ONNX Runtime" to "https://github.com/microsoft/onnxruntime",
                            "OkHttp" to "https://github.com/square/okhttp",
                            "Anthropic Java SDK" to "https://github.com/anthropics/anthropic-sdk-java",
                        ),
                    )
                    SourceCard(
                        title = "权利归属与免责声明",
                        source = "非官方个人伴侣工具",
                        details = listOf(
                            "本项目为个人学习与游玩辅助用途的非商业工具，与任天堂、Intelligent Systems 及上述各网站无隶属关系。",
                            "游戏名称、角色设定、数值、立绘、地图与社区攻略资料的权利均归原版权方及原作者所有；如有侵权或异议请通过 GitHub 仓库 Issue 联系删除。",
                        ),
                        links = listOf(
                            "本项目 GitHub 仓库" to "https://github.com/renyumeng1/fireemblem-wiki-app",
                        ),
                    )
                }
            }
        }
    } }
}

@Composable
private fun ArchiveList(archives: List<BuddyArchiveSummary>) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 560.dp) 2 else 1
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            archives.chunked(columns).forEach { rowArchives ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    rowArchives.forEach { archive ->
                        Card(
                            modifier = Modifier.weight(1f).heightIn(min = 104.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(18.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Surface(
                                    modifier = Modifier.size(42.dp),
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.primary,
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        androidx.compose.material3.Icon(Icons.Outlined.FolderOpen, null)
                                    }
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text("Buddy v${archive.sourceVersion} 存档", style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold)
                                    Text("${archive.characterCount} 名角色 · ${archive.recordCount} 条记录",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                    Text("${archive.seedCount} 个种子 · ${formatTime(archive)}",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    repeat(columns - rowArchives.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
internal fun ScreenHeading(title: String, description: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Surface(
            modifier = Modifier.size(44.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(14.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                androidx.compose.material3.Icon(
                    screenHeadingIcon(title),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(23.dp),
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() })
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun screenHeadingIcon(title: String): ImageVector = when (title) {
    "图鉴" -> Icons.Outlined.Book
    "攻略", "世界地图" -> Icons.Outlined.Map
    "工具", "反推种子" -> Icons.Outlined.AutoFixHigh
    "已知种子预测" -> Icons.AutoMirrored.Outlined.TrendingUp
    "升级记录" -> Icons.Outlined.EditNote
    "剧场演出" -> Icons.Outlined.TheaterComedy
    "规划后续培养" -> Icons.Outlined.Timeline
    "私人梯度榜" -> Icons.Outlined.Leaderboard
    "我的" -> Icons.Outlined.Person
    else -> Icons.Outlined.History
}

@Composable
private fun SectionHeading(title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Surface(
            modifier = Modifier.width(4.dp).height(20.dp),
            color = MaterialTheme.colorScheme.secondary,
            shape = RoundedCornerShape(2.dp),
        ) {}
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() })
    }
}

@Composable
private fun EmptyStateCard(title: String, description: String) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AvailabilityCard(title: String, detail: String, available: Boolean) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                StatusLabel(if (available) "可用" else "尚未开放", available)
            }
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatusLabel(label: String, available: Boolean) {
    Surface(
        color = if (available) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(100.dp),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            color = if (available) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun UnavailableCategory(label: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            StatusLabel("尚未开放", available = false)
        }
    }
}

@Composable
private fun SourceCard(
    title: String,
    source: String,
    details: List<String>,
    links: List<Pair<String, String>> = emptyList(),
) {
    val uriHandler = LocalUriHandler.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(source, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            details.forEach { detail ->
                Text(
                    detail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = if (detail.startsWith("参考提交：")) FontFamily.Monospace else FontFamily.Default,
                    ),
                )
            }
            if (links.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                    links.forEach { (label, url) ->
                        Surface(
                            onClick = { runCatching { uriHandler.openUri(url) } },
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    url,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        textDecoration = TextDecoration.Underline,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
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
private fun ActionRow(label: String, description: String, icon: ImageVector, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
    ) {
        androidx.compose.material3.Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text(label, color = MaterialTheme.colorScheme.onSurface)
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PreviewMetric(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(value, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun NoticeCard(message: String, isError: Boolean) {
    val containerColor = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer
    val contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer
    Surface(color = containerColor, contentColor = contentColor, shape = RoundedCornerShape(16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            androidx.compose.material3.Icon(
                if (isError) Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircleOutline,
                contentDescription = if (isError) "操作未完成" else "操作已完成",
                modifier = Modifier.size(20.dp),
            )
            Text(message, color = contentColor, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun PageColumn(positionKey: String, ready: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val horizontalPadding = if (maxWidth < 600.dp) 16.dp else 24.dp
        Box(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberReadingScrollState(positionKey, ready)),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 880.dp).fillMaxWidth()
                    .padding(horizontal = horizontalPadding, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                content = content,
            )
        }
    }
}

private fun createStore(context: Context): AtomicBuddyArchiveStore =
    AtomicBuddyArchiveStore(context.filesDir.toPath().resolve("buddy-archives"))

private fun readBounded(uri: java.io.InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val chunk = ByteArray(16 * 1024)
    while (true) {
        val count = uri.read(chunk)
        if (count < 0) break
        if (output.size() + count > BuddyBackupInspector.MAX_FILE_BYTES) {
            throw BuddyBackupException("备份超过 10 MiB 上限")
        }
        output.write(chunk, 0, count)
    }
    return output.toByteArray()
}

private fun formatTime(archive: BuddyArchiveSummary): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(archive.importedAt)
