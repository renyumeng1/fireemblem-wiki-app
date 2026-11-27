package com.fireemblem.companion.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MilitaryTech
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fireemblem.companion.content.CompanionGame
import com.fireemblem.companion.content.GamePackCategory
import com.fireemblem.companion.content.GamePackCharacterWikiData
import com.fireemblem.companion.content.GamePackDetailField
import com.fireemblem.companion.content.GamePackDetailSection
import com.fireemblem.companion.content.GamePackEntryDetail
import com.fireemblem.companion.content.GamePackEntrySummary
import com.fireemblem.companion.content.GamePackFilterOption
import com.fireemblem.companion.content.GamePackPage
import com.fireemblem.companion.content.GamePackPageRequest
import com.fireemblem.companion.content.GamePackPreferenceItem
import com.fireemblem.companion.content.GamePackRepository
import com.fireemblem.companion.content.GamePackSocialPartner
import com.fireemblem.companion.content.GameStatBenchmarks
import com.fireemblem.companion.content.StatMetricKind
import com.fireemblem.companion.content.StatRatingTier
import com.fireemblem.companion.content.SupportRankConversation
import com.fireemblem.companion.content.parseStatNumeric
import com.fireemblem.companion.ui.theme.tabularNumbers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal sealed interface GamePackLibraryState {
    data object Idle : GamePackLibraryState
    data object Loading : GamePackLibraryState
    data class Ready(val gameId: String, val repository: GamePackRepository) : GamePackLibraryState
    data class Failed(val message: String) : GamePackLibraryState
}

@Composable
internal fun GamePackHomeScreen(
    game: CompanionGame,
    onSelectGame: (CompanionGame) -> Unit,
    packState: GamePackLibraryState,
    query: String,
    onQueryChange: (String) -> Unit,
    onRetry: () -> Unit,
    onOpenAtlas: () -> Unit,
    onOpenGuides: () -> Unit,
    onOpenCategory: (String) -> Unit,
    onSearch: (String) -> Unit,
    onOpenEntry: ((String) -> Unit)? = null,
    onOpenAgent: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val packRepo = (packState as? GamePackLibraryState.Ready)?.repository
    Column(modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 880.dp)
                .align(Alignment.CenterHorizontally)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { Spacer(Modifier.height(6.dp)) }
            item { GameSwitcherBar(activeGame = game, onSelectGame = onSelectGame) }
            item { GamePackHero(game = game, packState = packState, onOpenAtlas = onOpenAtlas) }
            item {
                FuzzySearchBox(
                    query = query,
                    onQueryChange = onQueryChange,
                    placeholder = "模糊搜${game.shortTitle}角色、职业、特技、关卡…",
                    suggestionsProvider = { q -> packRepo?.suggest(q, limit = 7).orEmpty() },
                    onSelectSuggestion = { item ->
                        if (onOpenEntry != null) {
                            onQueryChange("")
                            onOpenEntry(item.id)
                        } else {
                            onSearch(item.title)
                        }
                    },
                    onSubmitSearch = { q -> onSearch(q) },
                    modifier = Modifier.fillMaxWidth(),
                    fieldTestTag = "game-pack-search",
                    suggestionKey = packRepo?.catalog?.gameId,
                    shapeRadiusDp = 18,
                )
            }
            if (onOpenAgent != null) {
                item {
                    EditorialCard(
                        onClick = onOpenAgent,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
                        modifier = Modifier.fillMaxWidth().testTag("game-pack-home-agent"),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("战策参谋", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "跨作检索${game.shortTitle}及系列图鉴，并支持乱数转职与跳过规划",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text("进入 ›", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            when (packState) {
                GamePackLibraryState.Idle, GamePackLibraryState.Loading -> item { LoadingPackage() }
                is GamePackLibraryState.Failed -> item { FailedPackage(packState.message, onRetry) }
                is GamePackLibraryState.Ready -> {
                    val catalog = packState.repository.catalog
                    item {
                        EditorialSectionHeader(
                            title = "${game.shortTitle} 专题入口",
                            subtitle = "SQLite 只读索引资料 · 支持按学级/地区与分类快速查阅",
                            actionLabel = "攻略分类",
                            onAction = onOpenGuides,
                        )
                    }
                    item {
                        val featuredIds = featuredCategoryIdsFor(game)
                        val featured = featuredIds.mapNotNull { id ->
                            catalog.categories.firstOrNull { it.id == id }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            featured.chunked(2).forEach { pair ->
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    pair.forEach { cat ->
                                        GamePackCategoryTile(cat, Modifier.weight(1f)) { onOpenCategory(cat.id) }
                                    }
                                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                    item {
                        EditorialSectionHeader(
                            title = "全部分类目录",
                            subtitle = "共 ${catalog.categories.size} 类",
                            actionLabel = "全部",
                            onAction = onOpenAtlas,
                        )
                    }
                    items(catalog.categories.chunked(2), key = { it.first().id }) { rowCats ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            rowCats.forEach { cat ->
                                GamePackCategoryTile(cat, Modifier.weight(1f), tagPrefix = "game-pack-all-cat") { onOpenCategory(cat.id) }
                            }
                            if (rowCats.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun GamePackHero(
    game: CompanionGame,
    packState: GamePackLibraryState,
    onOpenAtlas: () -> Unit,
) {
    val summaryText = when (packState) {
        GamePackLibraryState.Idle, GamePackLibraryState.Loading -> "正在验证本地 SQLite 索引库…"
        is GamePackLibraryState.Failed -> "离线资料包暂不可用"
        is GamePackLibraryState.Ready -> {
            val cats = packState.repository.catalog.categories
            "${cats.size} 类索引 · ${cats.sumOf(GamePackCategory::recordCount)} 条资料"
        }
    }
    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth().testTag("game-pack-hero:${game.id}"),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "FIRE EMBLEM · 离线索引库",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                )
                Text(game.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    game.tagline,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f),
                )
                Text(
                    summaryText,
                    style = MaterialTheme.typography.labelLarge.tabularNumbers(),
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                )
                Button(
                    onClick = onOpenAtlas,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("浏览${game.shortTitle}图鉴") }
            }
            Surface(
                modifier = Modifier.size(56.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    GameEmblemIcon(game = game, modifier = Modifier.size(30.dp))
                }
            }
        }
    }
}

@Composable
internal fun GamePackCatalogScreen(
    game: CompanionGame,
    repository: GamePackRepository,
    query: String,
    selectedCategoryId: String?,
    selectedEntryId: String?,
    onQueryChange: (String) -> Unit,
    onOpenCategory: (String) -> Unit,
    onOpenEntry: (String) -> Unit,
    onBackToCategories: () -> Unit,
    onCloseEntry: () -> Unit,
    categoryBackLabel: String = "全部分类",
) {
    var history by rememberSaveable(game.id, selectedCategoryId, query) { mutableStateOf(arrayListOf<String>()) }
    var filterByCategory by rememberSaveable(game.id) { mutableStateOf(mapOf<String, String>()) }
    var pageByCategory by rememberSaveable(game.id) { mutableStateOf(mapOf<String, Int>()) }
    val catKey = selectedCategoryId ?: "_search_"
    val selectedFilterGroup = filterByCategory[catKey]
    val pageIndex = pageByCategory[catKey] ?: 0
    val detailStates = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val openFromList: (String) -> Unit = { id ->
        history = arrayListOf()
        onOpenEntry(id)
    }
    val openLinked: (String) -> Unit = { id ->
        if (id != selectedEntryId) {
            selectedEntryId?.let { history = ArrayList(history + it) }
            onOpenEntry(id)
        }
    }
    val closeEntry: () -> Unit = {
        if (history.isEmpty()) {
            onCloseEntry()
        } else {
            val previous = history.last()
            history = ArrayList(history.dropLast(1))
            onOpenEntry(previous)
        }
    }
    val activeCategory = repository.catalog.categories.firstOrNull { it.id == selectedCategoryId }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        val leftWeight = if (wide && selectedEntryId == null) 0.6f else 0.44f
        val detailBackLabel = when {
            history.isNotEmpty() -> "返回上一级"
            wide -> "关闭详情"
            activeCategory != null -> "返回${activeCategory.title}"
            else -> "返回列表"
        }
        if (wide && selectedEntryId != null) {
            BackHandler(onBack = closeEntry)
        }
        Row(Modifier.fillMaxSize()) {
            GamePackCatalogListPane(
                game = game,
                repository = repository,
                query = query,
                selectedCategoryId = selectedCategoryId,
                selectedEntryId = if (wide) null else selectedEntryId,
                selectedFilterGroup = selectedFilterGroup,
                pageIndex = pageIndex,
                onSelectFilterGroup = { next ->
                    filterByCategory = if (next == null) {
                        filterByCategory - catKey
                    } else {
                        filterByCategory + (catKey to next)
                    }
                    pageByCategory = pageByCategory + (catKey to 0)
                },
                onPageChange = { nextPage ->
                    pageByCategory = pageByCategory + (catKey to nextPage.coerceAtLeast(0))
                },
                onQueryChange = {
                    history = arrayListOf()
                    pageByCategory = pageByCategory + (catKey to 0)
                    onQueryChange(it)
                },
                onOpenCategory = { history = arrayListOf(); onOpenCategory(it) },
                onOpenEntryFromList = openFromList,
                onOpenLinkedEntry = openLinked,
                onBackToCategories = { history = arrayListOf(); onBackToCategories() },
                onCloseEntry = closeEntry,
                categoryBackLabel = categoryBackLabel,
                detailBackLabel = detailBackLabel,
                detailStates = detailStates,
                modifier = Modifier.weight(if (wide) leftWeight else 1f),
            )
            if (wide) {
                Box(Modifier.fillMaxHeight().width(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                if (selectedEntryId == null) {
                    Column(
                        Modifier.weight(1f - leftWeight).fillMaxHeight().padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        EditorialCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("${game.shortTitle} 百科详情区", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                                Text("点击左侧任一角色、职业或装备条目，查看分档属性面板、可学技能矩阵与支援/好感资料。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                } else {
                    detailStates.SaveableStateProvider(selectedEntryId) {
                        GamePackDetailLoaderPane(
                            game = game,
                            repository = repository,
                            entryId = selectedEntryId,
                            onOpenEntry = openLinked,
                            onCloseEntry = closeEntry,
                            backLabel = detailBackLabel,
                            modifier = Modifier.weight(1f - leftWeight),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GamePackCatalogListPane(
    game: CompanionGame,
    repository: GamePackRepository,
    query: String,
    selectedCategoryId: String?,
    selectedEntryId: String?,
    selectedFilterGroup: String?,
    pageIndex: Int,
    onSelectFilterGroup: (String?) -> Unit,
    onPageChange: (Int) -> Unit,
    onQueryChange: (String) -> Unit,
    onOpenCategory: (String) -> Unit,
    onOpenEntryFromList: (String) -> Unit,
    onOpenLinkedEntry: (String) -> Unit,
    onBackToCategories: () -> Unit,
    onCloseEntry: () -> Unit,
    categoryBackLabel: String,
    detailBackLabel: String,
    detailStates: androidx.compose.runtime.saveable.SaveableStateHolder,
    modifier: Modifier = Modifier,
) {
    val filterOptions by produceState<List<GamePackFilterOption>>(emptyList(), repository, selectedCategoryId) {
        value = if (selectedCategoryId == null) emptyList() else withContext(Dispatchers.IO) {
            runCatching { repository.filterOptions(selectedCategoryId) }.getOrDefault(emptyList())
        }
    }
    val activeGroup = selectedFilterGroup?.takeIf { g -> filterOptions.any { it.value == g } }

    val request = remember(selectedCategoryId, activeGroup, query, pageIndex) {
        GamePackPageRequest(
            categoryId = selectedCategoryId,
            query = query,
            filterGroup = activeGroup,
            offset = pageIndex * GamePackPageRequest.PAGE_SIZE,
        )
    }
    val showList = selectedCategoryId != null || query.isNotBlank()
    val pageState by produceState<GamePackPage?>(null, repository, request, showList) {
        value = if (!showList) null else withContext(Dispatchers.IO) {
            runCatching { repository.page(request) }.getOrNull()
        }
    }
    val listState = rememberReadingListState(
        key = "gamepack:${game.id}:${selectedCategoryId ?: "_all_"}:${activeGroup ?: "_all_"}:$query:$pageIndex",
        ready = pageState != null,
    )

    if (selectedEntryId != null) {
        BackHandler(onBack = onCloseEntry)
        detailStates.SaveableStateProvider(selectedEntryId) {
            GamePackDetailLoaderPane(
                game = game,
                repository = repository,
                entryId = selectedEntryId,
                onOpenEntry = onOpenLinkedEntry,
                onCloseEntry = onCloseEntry,
                backLabel = detailBackLabel,
                modifier = modifier,
            )
        }
        return
    }
    if (selectedCategoryId != null) {
        BackHandler(onBack = onBackToCategories)
    }

    val activeCategory = repository.catalog.categories.firstOrNull { it.id == selectedCategoryId }

    Column(
        modifier = modifier.fillMaxHeight().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (activeCategory != null) {
                OutlinedButton(onClick = onBackToCategories, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                    Text(categoryBackLabel)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    activeCategory?.title ?: "${game.shortTitle} 图鉴",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
                activeCategory?.let {
                    Text("${it.recordCount} 条记录", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        FuzzySearchBox(
            query = query,
            onQueryChange = { onPageChange(0); onQueryChange(it) },
            placeholder = "模糊搜角色、技能、职业、中文或英文原名…",
            suggestionsProvider = { q -> repository.suggest(q, categoryId = selectedCategoryId, limit = 7) },
            onSelectSuggestion = { item -> onOpenEntryFromList(item.id) },
            modifier = Modifier.fillMaxWidth(),
            fieldTestTag = "game-pack-catalog-search",
            suggestionKey = "${game.id}:$selectedCategoryId",
            shapeRadiusDp = 16,
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (filterOptions.isNotEmpty() && selectedCategoryId != null) {
                item(key = "filter-bar:$selectedCategoryId") {
                    GamePackFilterBar(
                        game = game,
                        categoryId = selectedCategoryId,
                        options = filterOptions,
                        totalCount = activeCategory?.recordCount ?: filterOptions.sumOf { it.count },
                        selectedGroup = activeGroup,
                        onSelectGroup = { next -> onSelectFilterGroup(next) },
                    )
                }
            }
            if (selectedCategoryId == "community-skill-ratings") {
                item(key = "community-notice") {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    ) {
                        Text(
                            "社区评级说明：本分类数据整理自民间攻略表格的主观实用度评级，仅供构建思路参考，不代表官方设定或唯一标准。",
                            Modifier.fillMaxWidth().padding(14.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
            if (!showList) {
                items(repository.catalog.categories, key = { "cat:${it.id}" }) { cat ->
                    GamePackCategoryTile(cat, Modifier.fillMaxWidth()) { onOpenCategory(cat.id) }
                }
            } else {
                val page = pageState
                if (page == null) {
                    item(key = "loading") { LoadingPackage() }
                } else if (page.totalMatches == 0) {
                    item(key = "empty") {
                        EditorialCard {
                            Text(
                                "没有找到匹配条目，试试清空搜索或切换筛选条件。",
                                Modifier.fillMaxWidth().padding(18.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    item(key = "page-top") {
                        GamePackPageBar(
                            page = page,
                            onPrev = { onPageChange((pageIndex - 1).coerceAtLeast(0)) },
                            onNext = { if (page.hasNext) onPageChange(pageIndex + 1) },
                        )
                    }
                    items(page.entries, key = { "entry:${it.id}" }) { entry ->
                        GamePackEntryRowCard(game = game, entry = entry) { onOpenEntryFromList(entry.id) }
                    }
                    item(key = "page-bottom") {
                        GamePackPageBar(
                            page = page,
                            onPrev = { onPageChange((pageIndex - 1).coerceAtLeast(0)) },
                            onNext = { if (page.hasNext) onPageChange(pageIndex + 1) },
                        )
                    }
                }
            }
            item(key = "bottom-spacer") { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun GamePackFilterBar(
    game: CompanionGame,
    categoryId: String,
    options: List<GamePackFilterOption>,
    totalCount: Int,
    selectedGroup: String?,
    onSelectGroup: (String?) -> Unit,
) {
    val isCharacterGroup = categoryId == "characters"
    val heading = when {
        categoryId == "characters" && game == CompanionGame.THREE_HOUSES -> "按学级 / 势力筛选"
        categoryId == "characters" && game == CompanionGame.ENGAGE -> "按地区 / 国家筛选"
        categoryId == "classes" -> "按职业阶位筛选"
        categoryId == "skills" -> "按技能类型筛选"
        categoryId == "equipment" || categoryId == "weapons" -> "按武器 / 魔法分类筛选"
        categoryId == "chapters" || categoryId == "battles" -> "按路线 / 篇章筛选"
        else -> "按分类筛选"
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(heading, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .testTag("game-pack-filter-bar:$categoryId"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val allSelected = selectedGroup == null
            Surface(
                onClick = { onSelectGroup(null) },
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .semantics { selected = allSelected }
                    .testTag("game-pack-filter:all"),
                shape = RoundedCornerShape(14.dp),
                color = if (allSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                contentColor = if (allSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                border = BorderStroke(
                    1.dp,
                    if (allSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                ),
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (isCharacterGroup) {
                        GameEmblemIcon(
                            game = game,
                            modifier = Modifier.size(18.dp),
                            tint = if (allSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text("全部 · $totalCount", style = MaterialTheme.typography.labelLarge.tabularNumbers())
                }
            }
            options.forEach { opt ->
                val isSelected = selectedGroup == opt.value
                val visual = if (isCharacterGroup) factionVisualFor(game.id, opt.label) else null
                val containerColor = when {
                    isSelected && visual != null -> visual.accent
                    isSelected -> MaterialTheme.colorScheme.primary
                    visual != null -> visual.container
                    else -> MaterialTheme.colorScheme.surface
                }
                val contentColor = when {
                    isSelected -> MaterialTheme.colorScheme.onPrimary
                    visual != null -> visual.content
                    else -> MaterialTheme.colorScheme.onSurface
                }
                val borderColor = when {
                    isSelected && visual != null -> visual.accent
                    isSelected -> MaterialTheme.colorScheme.primary
                    visual != null -> visual.accent.copy(alpha = 0.35f)
                    else -> MaterialTheme.colorScheme.outlineVariant
                }
                Surface(
                    onClick = { onSelectGroup(if (isSelected) null else opt.value) },
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .semantics { selected = isSelected }
                        .testTag("game-pack-filter:${opt.value}"),
                    shape = RoundedCornerShape(14.dp),
                    color = containerColor,
                    contentColor = contentColor,
                    border = BorderStroke(1.dp, borderColor),
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        visual?.let { v ->
                            Icon(
                                painter = painterResource(v.iconRes),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else v.accent,
                            )
                        }
                        Text(
                            "${opt.label} · ${opt.count}",
                            style = MaterialTheme.typography.labelLarge.tabularNumbers(),
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GamePackDetailLoaderPane(
    game: CompanionGame,
    repository: GamePackRepository,
    entryId: String,
    onOpenEntry: (String) -> Unit,
    onCloseEntry: () -> Unit,
    backLabel: String,
    modifier: Modifier = Modifier,
) {
    val detail by produceState<GamePackEntryDetail?>(null, repository, entryId) {
        value = withContext(Dispatchers.IO) {
            runCatching { repository.entry(entryId) }.getOrNull()
        }
    }
    val current = detail
    if (current != null && current.summary.categoryId == "characters" && current.characterWiki != null) {
        GamePackCharacterWikiHub(
            game = game,
            detail = current,
            wiki = current.characterWiki!!,
            benchmarks = repository.statBenchmarks,
            onOpenEntry = onOpenEntry,
            onCloseEntry = onCloseEntry,
            backLabel = backLabel,
            modifier = modifier,
        )
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxHeight().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(Modifier.height(6.dp)) }
        item {
            OutlinedButton(onClick = onCloseEntry, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                Text(backLabel)
            }
        }
        if (current == null) {
            item { LoadingPackage() }
        } else {
            item { GamePackGenericHeaderCard(game, current) }
            if (current.isCommunityOpinion) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    ) {
                        Text(
                            "提示：此条属于社区玩家整理的实用度评价，而非游戏官方文档结论。",
                            Modifier.fillMaxWidth().padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
            if (current.summary.categoryId == "recruitment" && current.sections.isNotEmpty()) {
                item(key = "recruitment-structured") {
                    GamePackRecruitmentDetailCard(current)
                }
            } else {
                itemsIndexed(current.sections, key = { index, section -> "$index:${section.title}" }) { _, section ->
                    GamePackSmartSectionCard(
                        game = game,
                        categoryId = current.summary.categoryId,
                        section = section,
                        benchmarks = repository.statBenchmarks,
                    )
                }
            }
            if (current.supportConversations.isNotEmpty()) {
                item(key = "support-dialogues") {
                    GamePackSupportDialogueCard(
                        entryId = current.summary.id,
                        conversations = current.supportConversations,
                    )
                }
            }
            if (current.images.size > 1 && !(current.summary.categoryId == "supports" && current.images.size <= 2)) {
                item { GamePackGalleryCard(current) }
            }
            if (current.relations.isNotEmpty()) {
                item { GamePackRelationsCard(current, onOpenEntry) }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun GamePackCharacterWikiHub(
    game: CompanionGame,
    detail: GamePackEntryDetail,
    wiki: GamePackCharacterWikiData,
    benchmarks: GameStatBenchmarks,
    onOpenEntry: (String) -> Unit,
    onCloseEntry: () -> Unit,
    backLabel: String,
    modifier: Modifier = Modifier,
) {
    val hasSkills = wiki.skillTracks.isNotEmpty()
    val hasSocial = wiki.supportPartners.isNotEmpty() ||
        wiki.likedGifts.isNotEmpty() ||
        wiki.dislikedGifts.isNotEmpty() ||
        wiki.likedMeals.isNotEmpty() ||
        wiki.dislikedMeals.isNotEmpty()

    val tabs = remember(hasSkills, hasSocial, game) {
        buildList {
            add("overview" to "基础信息")
            if (hasSkills) add("skills" to if (game == CompanionGame.ENGAGE) "技能与资质" else "可学技能")
            if (hasSocial) add("social" to "好感与支援")
            add("archive" to "立绘与档案")
        }
    }
    var selectedTab by rememberSaveable(detail.summary.id) { mutableStateOf("overview") }
    if (tabs.none { it.first == selectedTab }) selectedTab = "overview"

    var selectedPortraitIndex by rememberSaveable(detail.summary.id) { mutableIntStateOf(0) }
    val activePortraitPath = detail.images.getOrNull(selectedPortraitIndex)?.assetPath ?: detail.summary.imageAsset

    val baseSection = detail.sections.firstOrNull { it.title == "初始属性" }
    val growthSection = detail.sections.firstOrNull { it.title == "成长率" }
    val capSection = detail.sections.firstOrNull { it.title == "能力上限" }
    val gearSection = detail.sections.firstOrNull { it.title == "人物武装与特技" }

    LazyColumn(
        modifier = modifier.fillMaxHeight().padding(horizontal = 16.dp).testTag("character-wiki-hub"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(Modifier.height(6.dp)) }
        item {
            OutlinedButton(onClick = onCloseEntry, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                Text(backLabel)
            }
        }

        // 1. Editorial Hero Card with Faction Emblem, Live Portrait Switcher & Personal Skill Spotlight
        item {
            EditorialCard {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            activePortraitPath?.let { path ->
                                FramedGamePackImage(
                                    assetPath = path,
                                    contentDescription = detail.summary.title,
                                    size = 96.dp,
                                )
                            }
                            if (detail.images.size > 1) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    detail.images.take(3).forEachIndexed { idx, img ->
                                        val isCur = idx == selectedPortraitIndex
                                        val shortLabel = when {
                                            img.roleLabel.contains("五年后") -> "五年后"
                                            idx == 0 -> "默认"
                                            else -> "造型${idx + 1}"
                                        }
                                        Surface(
                                            onClick = { selectedPortraitIndex = idx },
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (isCur) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLow,
                                            contentColor = if (isCur) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        ) {
                                            Text(
                                                shortLabel,
                                                Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            wiki.affiliation?.let { aff ->
                                val visual = factionVisualFor(game.id, aff)
                                Surface(
                                    color = visual.container,
                                    contentColor = visual.content,
                                    shape = RoundedCornerShape(100.dp),
                                    border = BorderStroke(1.dp, visual.accent.copy(alpha = 0.35f)),
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    ) {
                                        Icon(
                                            painter = painterResource(visual.iconRes),
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp),
                                            tint = visual.accent,
                                        )
                                        Text(aff, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                            Text(
                                detail.summary.title,
                                style = MaterialTheme.typography.headlineSmall,
                                modifier = Modifier.semantics { heading() },
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                wiki.startingClass?.let { cls ->
                                    EditorialBadge(
                                        text = "初始职业：$cls",
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                }
                                wiki.crest?.let { crest ->
                                    EditorialBadge(
                                        text = "纹章：$crest",
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                }
                            }
                        }
                    }

                    // Personal Skill Spotlight right in Hero (like fireemblem.top!)
                    wiki.personalSkillName?.let { skillName ->
                        val skillClickable = wiki.personalSkillEntryId != null
                        Surface(
                            onClick = { wiki.personalSkillEntryId?.let(onOpenEntry) },
                            enabled = skillClickable,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(
                                1.dp,
                                if (skillClickable) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                                else MaterialTheme.colorScheme.outlineVariant,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                wiki.personalSkillIconAsset?.let { iconPath ->
                                    FramedGamePackImage(
                                        assetPath = iconPath,
                                        contentDescription = "$skillName 图标",
                                        size = 40.dp,
                                    )
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        EditorialBadge(
                                            text = "个人特技",
                                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        )
                                        Text(skillName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    }
                                    wiki.personalSkillDescription?.let { desc ->
                                        Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                if (skillClickable) {
                                    Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2. Wiki Section Tab Bar
        item {
            WikiSectionTabBar(
                tabs = tabs,
                selectedKey = selectedTab,
                onSelect = { selectedTab = it },
            )
        }

        // 3. Active Tab Body
        when (selectedTab) {
            "overview" -> {
                // Base Stats (Per-game rated 3-col / 5-col visual grid)
                if (baseSection != null) {
                    val (metaPairs, statTiles) = splitMetaAndStats(baseSection.fields)
                    item(key = "char-base-stats") {
                        RatedStatGridCard(
                            title = "初始属性",
                            gameShortTitle = game.shortTitle,
                            metricKind = StatMetricKind.BASE_STAT,
                            benchmarks = benchmarks,
                            stats = statTiles,
                            metaBadges = metaPairs,
                        )
                    }
                }
                // Growth Rates (Per-game rated grid + rated Total badge!)
                if (growthSection != null) {
                    val totalField = growthSection.fields.firstOrNull { it.label == "成长合计" }
                    val growthTiles = growthSection.fields
                        .filter { it.label != "成长合计" }
                        .map { StatTileEntry(it.label, it.value, it.value) }
                    val totalTier = totalField?.let {
                        benchmarks.evaluate(StatMetricKind.GROWTH_TOTAL, it.label, it.value)?.tier
                    }
                    val totalBadge = totalField?.let {
                        val suffix = totalTier?.let { t -> " · ${t.label}" } ?: ""
                        "合计 ${it.value}$suffix" to totalTier
                    }
                    item(key = "char-growth-rates") {
                        RatedStatGridCard(
                            title = "成长率",
                            gameShortTitle = game.shortTitle,
                            metricKind = StatMetricKind.GROWTH_RATE,
                            benchmarks = benchmarks,
                            stats = growthTiles,
                            summaryBadge = totalBadge,
                        )
                    }
                }
                // Cap Modifiers (Engage)
                if (capSection != null) {
                    val capTiles = capSection.fields.map { StatTileEntry(it.label, it.value, it.value) }
                    item(key = "char-caps") {
                        RatedStatGridCard(
                            title = "能力上限修正",
                            gameShortTitle = game.shortTitle,
                            metricKind = StatMetricKind.CAP_MODIFIER,
                            benchmarks = benchmarks,
                            stats = capTiles,
                        )
                    }
                }
                // Recruitment & Route Availability (#recruitment)
                if (!wiki.routeAvailability.isNullOrBlank() || !wiki.joinCondition.isNullOrBlank()) {
                    item(key = "char-recruitment") {
                        EditorialCard(modifier = Modifier.testTag("char-recruitment-card")) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("入队与可用路线", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                wiki.routeAvailability?.let { routes ->
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("可用路线 / 章节", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        RouteAvailabilityBadgesRow(routes)
                                    }
                                }
                                wiki.joinCondition?.let { join ->
                                    val lines = join.lines().map { it.trim() }.filter { it.isNotEmpty() }
                                    val headParts = lines.firstOrNull().orEmpty()
                                        .split(" · ").map { it.trim() }.filter { it.isNotEmpty() }
                                    val noteBody = lines.drop(1).joinToString("\n")
                                    if (headParts.size >= 2) {
                                        val reqFields = headParts.mapIndexed { idx, part ->
                                            val label = when {
                                                part.startsWith("需求：") -> "招募门槛"
                                                idx == 0 -> "加入方式"
                                                "章" in part || "序幕" in part -> "最早章节"
                                                else -> "补充信息"
                                            }
                                            GamePackDetailField(label, part.removePrefix("需求："))
                                        }
                                        GamePackCompactFieldsGrid(reqFields)
                                    } else if (headParts.isNotEmpty()) {
                                        Text(headParts.first(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    }
                                    if (noteBody.isNotBlank()) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Text(
                                                noteBody,
                                                Modifier.padding(12.dp),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                // Starting Gear & Loadout Card
                if (wiki.startingWeapons.isNotEmpty() || gearSection != null) {
                    item(key = "char-loadout") {
                        val extraLoadoutFields = gearSection?.fields.orEmpty().filter { f ->
                            f.label !in setOf(
                                "初始职业", "纹章", "个人特技", "个人特技说明", "初始武器与道具", "初始持有武器与道具",
                            )
                        }
                        if (wiki.startingWeapons.isNotEmpty() || extraLoadoutFields.isNotEmpty()) {
                            EditorialCard {
                                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("入队配置与武装", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    val weaponItems = wiki.startingWeaponItems.ifEmpty {
                                        wiki.startingWeapons.map { com.fireemblem.companion.content.GamePackLinkedItem(it) }
                                    }
                                    if (weaponItems.isNotEmpty()) {
                                        Text("初始持有武器与道具", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            weaponItems.forEach { w ->
                                                val clickable = w.targetEntryId != null
                                                Surface(
                                                    onClick = { w.targetEntryId?.let(onOpenEntry) },
                                                    enabled = clickable,
                                                    shape = RoundedCornerShape(10.dp),
                                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                                    border = BorderStroke(
                                                        1.dp,
                                                        if (clickable) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                                                        else MaterialTheme.colorScheme.outlineVariant,
                                                    ),
                                                ) {
                                                    Row(
                                                        Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                    ) {
                                                        w.imageAsset?.let { iconPath ->
                                                            GamePackAssetImage(iconPath, w.name, Modifier.size(20.dp))
                                                        }
                                                        Text(
                                                            w.name,
                                                            style = MaterialTheme.typography.labelMedium,
                                                            color = MaterialTheme.colorScheme.onSurface,
                                                        )
                                                        if (clickable) {
                                                            Text("›", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    if (extraLoadoutFields.isNotEmpty()) {
                                        GamePackCompactFieldsGrid(extraLoadoutFields)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            "skills" -> {
                item(key = "char-skill-matrix") {
                    StructuredSkillMatrixCard(
                        tracks = wiki.skillTracks,
                        onOpenEntry = onOpenEntry,
                        universalFilterLabel = if (game == CompanionGame.ENGAGE) "纹章士传授" else "通用技能",
                        strengthLabel = if (game == CompanionGame.ENGAGE) "天性擅长" else "擅长",
                        skillIconContent = { item ->
                            item.imageAsset?.let { assetPath ->
                                GamePackAssetImage(
                                    assetPath = assetPath,
                                    contentDescription = "${item.name}图标",
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        },
                        trackIconContent = { track ->
                            val rankPath = "games/${game.id}/assets/app/rank_${track.trackKey}.webp"
                            GamePackAssetImageOrFallback(
                                assetPath = rankPath,
                                contentDescription = "${track.trackLabel}图标",
                                modifier = Modifier.size(30.dp),
                            ) {
                                Surface(
                                    modifier = Modifier.size(30.dp),
                                    shape = RoundedCornerShape(9.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.primary,
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            painter = painterResource(skillTrackDrawable(track.trackKey.ifBlank { track.trackLabel })),
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            }
                        },
                    )
                }
            }

            "social" -> {
                if (wiki.supportPartners.isNotEmpty()) {
                    item(key = "char-supports") {
                        GamePackSupportPartnersCard(
                            partners = wiki.supportPartners,
                            supportBonusNote = wiki.supportBonusNote,
                            onOpenEntry = onOpenEntry,
                        )
                    }
                }
                if (wiki.likedGifts.isNotEmpty() || wiki.dislikedGifts.isNotEmpty()) {
                    item(key = "char-gifts") {
                        GamePackPreferencesCard(
                            title = "礼物偏好",
                            likedTitle = "喜欢的礼物",
                            dislikedTitle = "不喜欢的礼物",
                            liked = wiki.likedGifts,
                            disliked = wiki.dislikedGifts,
                            onOpenEntry = onOpenEntry,
                        )
                    }
                }
                if (wiki.likedMeals.isNotEmpty() || wiki.dislikedMeals.isNotEmpty()) {
                    item(key = "char-meals") {
                        GamePackPreferencesCard(
                            title = "食堂料理偏好",
                            likedTitle = "喜欢的料理",
                            dislikedTitle = "不喜欢的料理",
                            liked = wiki.likedMeals,
                            disliked = wiki.dislikedMeals,
                            onOpenEntry = onOpenEntry,
                        )
                    }
                }
            }

            "archive" -> {
                if (detail.images.isNotEmpty()) {
                    item(key = "char-gallery") { GamePackGalleryCard(detail) }
                }
                val nonSocialRelations = detail.relations.filterNot { rel ->
                    rel.relationType in setOf("支援对象", "喜欢的礼物", "不喜欢的礼物", "喜欢的料理", "不喜欢的料理")
                }
                if (nonSocialRelations.isNotEmpty()) {
                    item(key = "char-relations") {
                        GamePackRelationsCard(detail.copy(relations = nonSocialRelations), onOpenEntry)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun GamePackSupportPartnersCard(
    partners: List<GamePackSocialPartner>,
    supportBonusNote: String?,
    onOpenEntry: (String) -> Unit,
) {
    EditorialCard(modifier = Modifier.fillMaxWidth().testTag("character-support-partners")) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("支援对象", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                EditorialBadge(text = "${partners.size} 位")
            }
            if (!supportBonusNote.isNullOrBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        supportBonusNote,
                        Modifier.padding(10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val cols = if (maxWidth >= 560.dp) 3 else 2
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    partners.chunked(cols).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { partner ->
                                val clickable = partner.targetEntryId != null
                                Surface(
                                    onClick = { partner.targetEntryId?.let(onOpenEntry) },
                                    enabled = clickable,
                                    modifier = Modifier.weight(1f).heightIn(min = 54.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        partner.imageAsset?.let { path ->
                                            FramedGamePackImage(path, partner.name, 36.dp)
                                        }
                                        Text(
                                            partner.name,
                                            Modifier.weight(1f),
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (clickable) {
                                            Text("›", color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                            }
                            repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GamePackPreferencesCard(
    title: String,
    likedTitle: String,
    dislikedTitle: String,
    liked: List<GamePackPreferenceItem>,
    disliked: List<GamePackPreferenceItem>,
    onOpenEntry: (String) -> Unit,
) {
    val highPal = statTierPalette(StatRatingTier.HIGH)
    val lowPal = statTierPalette(StatRatingTier.LOW)
    EditorialCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (liked.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("$likedTitle（${liked.size}）", style = MaterialTheme.typography.labelLarge, color = highPal.content)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        liked.forEach { item ->
                            Surface(
                                onClick = { item.targetEntryId?.let(onOpenEntry) },
                                enabled = item.targetEntryId != null,
                                shape = RoundedCornerShape(100.dp),
                                color = highPal.container,
                                contentColor = highPal.content,
                                border = BorderStroke(1.dp, highPal.bar.copy(alpha = 0.35f)),
                            ) {
                                Text(
                                    item.name,
                                    Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                }
            }
            if (disliked.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("$dislikedTitle（${disliked.size}）", style = MaterialTheme.typography.labelLarge, color = lowPal.content)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        disliked.forEach { item ->
                            Surface(
                                onClick = { item.targetEntryId?.let(onOpenEntry) },
                                enabled = item.targetEntryId != null,
                                shape = RoundedCornerShape(100.dp),
                                color = lowPal.container,
                                contentColor = lowPal.content,
                                border = BorderStroke(1.dp, lowPal.bar.copy(alpha = 0.35f)),
                            ) {
                                Text(
                                    item.name,
                                    Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.labelMedium,
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
private fun GamePackGenericHeaderCard(game: CompanionGame, current: GamePackEntryDetail) {
    EditorialCard {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val isSupportDual = current.summary.categoryId == "supports" && current.images.size >= 2
            val imgPath = current.summary.imageAsset
            if (isSupportDual) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FramedGamePackImage(
                        assetPath = current.images[0].assetPath,
                        contentDescription = current.summary.title,
                        size = 56.dp,
                    )
                    Text("×", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    FramedGamePackImage(
                        assetPath = current.images[1].assetPath,
                        contentDescription = current.summary.title,
                        size = 56.dp,
                    )
                }
            } else if (imgPath != null) {
                FramedGamePackImage(
                    assetPath = imgPath,
                    contentDescription = current.summary.title,
                    size = 88.dp,
                )
            } else {
                GamePackSemanticBadgeTile(
                    gameId = game.id,
                    categoryId = current.summary.categoryId,
                    title = current.summary.title,
                    subtitle = current.summary.subtitle,
                    size = 84.dp,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(current.summary.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                val hasChineseTitle = current.summary.title.any { it in '\u4E00'..'\u9FFF' }
                if (!hasChineseTitle) {
                    val altNames = listOfNotNull(current.summary.titleJa)
                        .distinct()
                        .filter { it != current.summary.title }
                    if (altNames.isNotEmpty()) {
                        Text(
                            altNames.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (current.summary.subtitle.isNotBlank()) {
                    Text(
                        current.summary.subtitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (current.isCommunityOpinion) {
                    EditorialBadge(
                        text = "社区主观观点",
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun GamePackSmartSectionCard(
    game: CompanionGame,
    categoryId: String,
    section: GamePackDetailSection,
    benchmarks: GameStatBenchmarks,
) {
    when {
        categoryId == "classes" && section.title == "基础能力" -> {
            val (meta, tiles) = splitMetaAndStats(section.fields)
            RatedStatGridCard(
                title = section.title,
                gameShortTitle = game.shortTitle,
                metricKind = StatMetricKind.CLASS_BASE_STAT,
                benchmarks = benchmarks,
                stats = tiles,
                metaBadges = meta,
            )
            return
        }
        categoryId == "classes" && section.title == "成长补正" -> {
            val tiles = section.fields.map { StatTileEntry(it.label, it.value, it.value) }
            RatedStatGridCard(
                title = section.title,
                gameShortTitle = game.shortTitle,
                metricKind = StatMetricKind.CLASS_GROWTH_MODIFIER,
                benchmarks = benchmarks,
                stats = tiles,
            )
            return
        }
        section.title in setOf("转职能力补正", "下马能力变化", "满级属性加成（Lv.5）", "初始属性加成（Lv.1）") &&
            section.fields.any { parseStatNumeric(it.value) != null } -> {
            val tiles = section.fields.map { StatTileEntry(it.label, it.value, it.value) }
            RatedStatGridCard(
                title = section.title,
                gameShortTitle = game.shortTitle,
                metricKind = StatMetricKind.CAP_MODIFIER,
                benchmarks = benchmarks,
                stats = tiles,
            )
            return
        }
        section.title in setOf("能力上限", "属性加成") && section.fields.all { parseStatNumeric(it.value) != null } -> {
            CompactNumericGridCard(
                title = section.title,
                items = section.fields.map { it.label to it.value },
            )
            return
        }
    }

    val numericFields = section.fields.filter { f ->
        f.label in NUMERIC_COMBAT_LABELS && (
            parseStatNumeric(f.value) != null ||
                (f.label in FLEX_COMBAT_LABELS && f.value.isNotBlank() && f.value.length <= 16)
            )
    }
    val showCombatGrid = numericFields.size >= 3 || (numericFields.size >= 2 && numericFields.any { it.label == "射程" })
    val otherFields = section.fields.filterNot { it in numericFields }
    val isBulky = section.fields.size >= 6 || section.fields.sumOf { it.value.length } >= 260
    var expanded by rememberSaveable(categoryId, section.title, section.fields.size) { mutableStateOf(true) }
    val cardBody: @Composable ColumnScope.() -> Unit = {
        if (showCombatGrid) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val cols = if (maxWidth >= 560.dp) 4 else 3
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    numericFields.chunked(cols).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { f ->
                                val isRange = f.label == "射程"
                                Surface(
                                    modifier = Modifier.weight(1f),
                                    color = if (isRange) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(
                                        1.dp,
                                        if (isRange) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outlineVariant,
                                    ),
                                ) {
                                    Column(
                                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        Text(
                                            f.label,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isRange) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                        )
                                        Text(
                                            f.value,
                                            style = MaterialTheme.typography.titleSmall.tabularNumbers(),
                                            color = if (isRange) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                            repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
        val remaining = if (showCombatGrid) otherFields else section.fields
        if (remaining.isNotEmpty()) {
            GamePackCompactFieldsGrid(remaining)
        }
    }
    if (isBulky) {
        CollapsibleEditorialCard(
            title = section.title,
            badge = "${section.fields.size} 项",
            expanded = expanded,
            onToggle = { expanded = !expanded },
        ) {
            cardBody()
        }
    } else {
        EditorialCard {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(section.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                cardBody()
            }
        }
    }
}

@Composable
private fun GamePackCompactFieldsGrid(fields: List<GamePackDetailField>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        var idx = 0
        while (idx < fields.size) {
            val cur = fields[idx]
            val isLong = cur.value.length > 22 || '\n' in cur.value || cur.label.contains("说明") || cur.label.contains("效果") || cur.label.contains("备注") || cur.label.contains("条件")
            if (isLong) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(cur.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(cur.value, style = MaterialTheme.typography.bodyMedium.tabularNumbers())
                    }
                }
                idx++
            } else {
                val nxt = fields.getOrNull(idx + 1)?.takeUnless { f ->
                    f.value.length > 22 || '\n' in f.value || f.label.contains("说明") || f.label.contains("效果") || f.label.contains("备注") || f.label.contains("条件")
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompactFactTile(cur, Modifier.weight(1f))
                    if (nxt != null) CompactFactTile(nxt, Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
                }
                idx += if (nxt != null) 2 else 1
            }
        }
    }
}

@Composable
private fun CompactFactTile(field: GamePackDetailField, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(field.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(field.value, style = MaterialTheme.typography.bodyMedium.tabularNumbers(), fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun GamePackGalleryCard(current: GamePackEntryDetail) {
    EditorialCard {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("相关立绘与图像", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(current.images, key = { it.assetPath }) { img ->
                    Column(
                        Modifier.width(104.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FramedGamePackImage(
                            assetPath = img.assetPath,
                            contentDescription = "${current.summary.title} · ${img.roleLabel}",
                            size = 96.dp,
                        )
                        Text(
                            img.roleLabel,
                            style = MaterialTheme.typography.labelSmall,
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

@Composable
private fun GamePackRecruitmentDetailCard(current: GamePackEntryDetail) {
    val allFields = current.sections.flatMap { it.fields }
    val fieldMap = allFields.associate { it.label to it.value }
    val routes = fieldMap["可用路线"]
    val house = fieldMap["所属学级 / 阵营"] ?: fieldMap["归属分类"]
    val method = fieldMap["招募方式"]
    val chapter = fieldMap["最早加入章节"] ?: fieldMap["首次登场"]
    val statReq = fieldMap["属性门槛"]
    val skillReq = fieldMap["技能门槛"]
    val tips = fieldMap["招募贴士"] ?: fieldMap["规则说明"]
    val supportRule = fieldMap["计算公式"]

    EditorialCard(modifier = Modifier.fillMaxWidth().testTag("recruitment-detail-card")) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("招募与入队攻略", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                house?.let { EditorialBadge(text = it) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            routes?.let {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("可用路线", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    RouteAvailabilityBadgesRow(it)
                }
            }
            val requirementTiles = buildList {
                method?.let { add(GamePackDetailField("招募方式", it)) }
                chapter?.let { add(GamePackDetailField("加入时机", it)) }
                statReq?.let { add(GamePackDetailField("属性门槛", it)) }
                skillReq?.let { add(GamePackDetailField("技能门槛", it)) }
            }
            if (requirementTiles.isNotEmpty()) {
                GamePackCompactFieldsGrid(requirementTiles)
            }
            tips?.takeIf { it.isNotBlank() }?.let { note ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("招募说明与要点", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(note, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            supportRule?.takeIf { it.isNotBlank() }?.let { rule ->
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("支援等级降门槛机制", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Text(rule, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
        }
    }
}

@Composable
private fun GamePackSupportDialogueCard(
    entryId: String,
    conversations: List<SupportRankConversation>,
) {
    var selectedRank by rememberSaveable(entryId) { mutableStateOf(conversations.first().rank) }
    val active = conversations.firstOrNull { it.rank == selectedRank } ?: conversations.first()
    val speakers = remember(active) {
        active.lines.map { it.speaker }.filter { it.isNotBlank() && it != "选项" }.distinct()
    }
    val primarySpeaker = speakers.firstOrNull()

    EditorialCard(modifier = Modifier.fillMaxWidth().testTag("support-dialogue-card")) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("支援对话原文", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                EditorialBadge(text = "${active.lines.size} 句")
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                conversations.forEach { conv ->
                    val sel = conv.rank == active.rank
                    Surface(
                        onClick = { selectedRank = conv.rank },
                        modifier = Modifier.heightIn(min = 42.dp).semantics { selected = sel }.testTag("support-rank:${conv.rank}"),
                        shape = RoundedCornerShape(12.dp),
                        color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLow,
                        contentColor = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        border = BorderStroke(
                            1.dp,
                            if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        ),
                    ) {
                        Box(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), contentAlignment = Alignment.Center) {
                            Text("${conv.rank} 级支援", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                active.lines.forEach { line ->
                    val isChoice = line.speaker == "选项"
                    val isFirst = line.speaker == primarySpeaker
                    Surface(
                        color = when {
                            isChoice -> MaterialTheme.colorScheme.primaryContainer
                            isFirst -> MaterialTheme.colorScheme.surfaceContainerLow
                            else -> MaterialTheme.colorScheme.surfaceContainer
                        },
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(
                            1.dp,
                            if (isChoice) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            if (line.speaker.isNotBlank()) {
                                Text(
                                    line.speaker,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = when {
                                        isChoice -> MaterialTheme.colorScheme.onPrimaryContainer
                                        isFirst -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.secondary
                                    },
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Text(
                                line.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isChoice) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GamePackRelationsCard(current: GamePackEntryDetail, onOpenEntry: (String) -> Unit) {
    var expanded by rememberSaveable(current.summary.id) { mutableStateOf(current.relations.size <= 5) }
    CollapsibleEditorialCard(
        title = "关联条目",
        badge = "${current.relations.size} 项",
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        current.relations.forEach { rel ->
            OutlinedButton(
                onClick = { onOpenEntry(rel.targetId) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    rel.targetImageAsset?.let { iconPath ->
                        FramedGamePackImage(iconPath, rel.targetTitle, 34.dp)
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                        Text("${rel.relationType} · ${rel.targetTitle}", fontWeight = FontWeight.Medium)
                        if (rel.evidence.isNotBlank()) {
                            Text(
                                rel.evidence,
                                style = MaterialTheme.typography.bodySmall,
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

private fun splitMetaAndStats(fields: List<GamePackDetailField>): Pair<List<Pair<String, String>>, List<StatTileEntry>> {
    val meta = mutableListOf<Pair<String, String>>()
    val stats = mutableListOf<StatTileEntry>()
    fields.forEach { f ->
        if (f.label in META_STAT_LABELS) {
            meta += f.label to f.value
        } else {
            stats += StatTileEntry(f.label, f.value, f.value)
        }
    }
    return meta to stats
}

private val META_STAT_LABELS = setOf("初始等级", "内置等级", "移动力", "初始SP", "等级", "SP")
private val NUMERIC_COMBAT_LABELS = setOf(
    "射程", "等级", "所需武器等级", "使用次数", "耐久消耗", "威力", "命中", "必杀", "回避", "必避", "重量", "耐久", "价格", "售出价格",
    "移动力", "继承 SP", "所需金钱", "铁晶石", "钢晶石", "银晶石",
    "HP", "力量", "魔力", "技巧", "速度", "幸运", "防守", "魔防", "魅力", "体格",
)
private val FLEX_COMBAT_LABELS = setOf("射程", "等级", "所需武器等级", "使用次数", "耐久", "耐久消耗")

@Composable
internal fun GamePackGuidesScreen(
    game: CompanionGame,
    packState: GamePackLibraryState,
    onRetry: () -> Unit,
    onOpenCategory: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(Modifier.height(6.dp)) }
        item {
            ScreenHeading("${game.shortTitle} 攻略专辑", "按游戏特色机制与路线系统分组查阅。")
        }
        when (packState) {
            GamePackLibraryState.Idle, GamePackLibraryState.Loading -> item { LoadingPackage() }
            is GamePackLibraryState.Failed -> item { FailedPackage(packState.message, onRetry) }
            is GamePackLibraryState.Ready -> {
                val categoriesById = packState.repository.catalog.categories.associateBy { it.id }
                val sections = guideSectionsFor(game)
                sections.forEach { (heading, ids) ->
                    val cats = ids.mapNotNull(categoriesById::get)
                    if (cats.isNotEmpty()) {
                        item {
                            EditorialSectionHeader(title = heading)
                        }
                        items(cats.chunked(2), key = { "$heading:${it.first().id}" }) { rowCats ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                rowCats.forEach { cat ->
                                    GamePackCategoryTile(cat, Modifier.weight(1f)) { onOpenCategory(cat.id) }
                                }
                                if (rowCats.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun GamePackCategoryTile(
    category: GamePackCategory,
    modifier: Modifier = Modifier,
    tagPrefix: String = "game-pack-cat",
    onClick: () -> Unit,
) {
    EditorialCard(
        onClick = onClick,
        modifier = modifier.heightIn(min = 104.dp).testTag("$tagPrefix:${category.id}"),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                val catIcon = category.iconAsset
                if (catIcon != null) {
                    FramedGamePackImage(
                        assetPath = catIcon,
                        contentDescription = category.title,
                        size = 36.dp,
                    )
                } else {
                    Icon(iconForCategory(category.id), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
                EditorialBadge(text = "${category.recordCount} 条")
            }
            Text(category.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (category.description.isNotBlank()) {
                Text(category.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun GamePackEntryRowCard(
    game: CompanionGame,
    entry: GamePackEntrySummary,
    onClick: () -> Unit,
) {
    val isChar = entry.categoryId == "characters"
    val subParts = if (isChar && " · " in entry.subtitle) entry.subtitle.split(" · ", limit = 2) else null
    val factionTag = subParts?.firstOrNull()?.takeIf { it.isNotBlank() }
    val restSubtitle = subParts?.getOrNull(1) ?: entry.subtitle

    EditorialCard(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag("game-pack-entry:${entry.id}")) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val imgPath = entry.imageAsset
            if (imgPath != null) {
                FramedGamePackImage(
                    assetPath = imgPath,
                    contentDescription = entry.title,
                    size = 58.dp,
                )
            } else {
                GamePackSemanticBadgeTile(
                    gameId = game.id,
                    categoryId = entry.categoryId,
                    title = entry.title,
                    subtitle = entry.subtitle,
                    size = 58.dp,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(entry.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (factionTag != null) {
                    val v = factionVisualFor(game.id, factionTag)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Surface(
                            color = v.container,
                            contentColor = v.content,
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Row(
                                Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    painter = painterResource(v.iconRes),
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = v.accent,
                                )
                                Text(factionTag, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        if (restSubtitle.isNotBlank()) {
                            Text(
                                restSubtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                } else if (entry.subtitle.isNotBlank()) {
                    Text(entry.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Text("查看", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun GamePackPageBar(
    page: GamePackPage,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = onPrev, enabled = page.hasPrevious, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("上一页")
        }
        val from = page.offset + 1
        val to = (page.offset + page.entries.size).coerceAtLeast(from)
        Text(
            "第 $from–$to / 共 ${page.totalMatches} 条",
            style = MaterialTheme.typography.labelLarge.tabularNumbers(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onNext, enabled = page.hasNext, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("下一页")
        }
    }
}

@Composable
private fun FramedGamePackImage(
    assetPath: String,
    contentDescription: String,
    size: androidx.compose.ui.unit.Dp,
) {
    Surface(
        modifier = Modifier.size(size),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        GamePackAssetImage(
            assetPath = assetPath,
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize().padding(4.dp),
        )
    }
}

@Composable
private fun GamePackAssetImageOrFallback(
    assetPath: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    fallback: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, assetPath, context) {
        value = withContext(Dispatchers.IO) {
            try {
                context.assets.open(assetPath).use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }
    val img = bitmap
    if (img != null) {
        Image(img, contentDescription = contentDescription, modifier = modifier, contentScale = ContentScale.Fit)
    } else {
        fallback()
    }
}

@Composable
private fun GamePackAssetImage(assetPath: String, contentDescription: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, assetPath, context) {
        value = withContext(Dispatchers.IO) {
            try {
                context.assets.open(assetPath).use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }
    bitmap?.let { img ->
        Image(img, contentDescription = contentDescription, modifier = modifier, contentScale = ContentScale.Fit)
    } ?: Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(10.dp),
    ) {}
}

private fun featuredCategoryIdsFor(game: CompanionGame): List<String> = when (game) {
    CompanionGame.ENGAGE -> listOf("emblems", "bond-rings", "engravings", "characters", "classes", "community-skill-ratings")
    CompanionGame.THREE_HOUSES -> listOf("characters", "recruitment", "classes", "supports", "battalions", "chapters")
    CompanionGame.FORTUNES_WEAVE -> emptyList()
}

private fun guideSectionsFor(game: CompanionGame): List<Pair<String, List<String>>> = when (game) {
    CompanionGame.ENGAGE -> listOf(
        "纹章士与戒指系统" to listOf("emblems", "bond-rings", "engravings", "skills", "community-skill-ratings"),
        "角色、职业与关卡" to listOf("characters", "classes", "chapters", "paralogues", "dlc-chapters", "dlc-characters"),
        "索拉涅尔与数值规则" to listOf("country-investment", "gifts", "meals", "well-drops", "animal-drops", "game-mechanics", "growth-mechanics"),
    )
    CompanionGame.THREE_HOUSES -> listOf(
        "学级、路线与招募" to listOf("routes", "characters", "recruitment", "chapters", "dlc"),
        "培养、职业与战斗" to listOf("classes", "crests", "skills", "battalions", "equipment"),
        "修道院与支援系统" to listOf("supports", "social", "monastery", "mechanics"),
    )
    CompanionGame.FORTUNES_WEAVE -> emptyList()
}

private fun iconForCategory(categoryId: String): ImageVector = when {
    "emblem" in categoryId || "crest" in categoryId -> Icons.Outlined.WorkspacePremium
    "bond" in categoryId || "support" in categoryId -> Icons.Outlined.Groups
    "char" in categoryId || "recruit" in categoryId -> Icons.Outlined.Person
    "chapter" in categoryId || "route" in categoryId || "battle" in categoryId || "paralogue" in categoryId -> Icons.Outlined.Map
    "skill" in categoryId || "engraving" in categoryId || "mechanic" in categoryId || "growth" in categoryId -> Icons.Outlined.AutoFixHigh
    "rating" in categoryId || "achieve" in categoryId -> Icons.Outlined.MilitaryTech
    "item" in categoryId || "weapon" in categoryId || "equip" in categoryId || "gift" in categoryId -> Icons.Outlined.Inventory2
    else -> Icons.Outlined.Book
}
