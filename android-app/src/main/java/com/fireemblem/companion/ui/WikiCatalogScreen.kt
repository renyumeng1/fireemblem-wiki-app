package com.fireemblem.companion.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Hardware
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.LocalDrink
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MilitaryTech
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SportsMartialArts
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fireemblem.companion.content.BirdDialogueRecord
import com.fireemblem.companion.content.BirdDialogueRequest
import com.fireemblem.companion.content.CharacterGiftGuideData
import com.fireemblem.companion.content.CharacterMealGuideData
import com.fireemblem.companion.content.CharacterRouteSummary
import com.fireemblem.companion.content.GiftPreferenceLevel
import com.fireemblem.companion.content.MealPreferenceLevel
import com.fireemblem.companion.content.SkillMatrixItem
import com.fireemblem.companion.content.SkillMatrixTrack
import com.fireemblem.companion.content.StatMetricKind
import com.fireemblem.companion.content.StatRatingEngine
import com.fireemblem.companion.content.StatRatingTier
import com.fireemblem.companion.content.SupportPairRequest
import com.fireemblem.companion.content.SupportPairSummary
import com.fireemblem.companion.content.WikiCatalog
import com.fireemblem.companion.content.WikiCategorySummary
import com.fireemblem.companion.content.WikiRepository
import com.fireemblem.companion.engine.LevelJournal
import com.fireemblem.companion.engine.levelsUp
import com.fireemblem.companion.personal.PersonalStore
import com.fireemblem.companion.personal.PersonalTierList
import com.fireemblem.companion.personal.RouteMergeCharacter
import com.fireemblem.companion.ui.theme.tabularNumbers
import com.fireemblem.companion.content.WikiPageRequest
import com.fireemblem.companion.content.WikiEntryPage
import com.fireemblem.companion.content.WikiEntry
import com.fireemblem.companion.content.WikiField
import com.fireemblem.companion.content.WikiFieldOptions
import com.fireemblem.companion.content.WikiLink
import com.fireemblem.companion.content.WikiCharacterLearningData
import com.fireemblem.companion.content.WikiCharacterSkill
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface AtlasLibraryState {
    data object NotLoaded : AtlasLibraryState
    data object Loading : AtlasLibraryState
    data class Ready(val repository: WikiRepository) : AtlasLibraryState
    data class Failed(val message: String) : AtlasLibraryState
}

@Composable
internal fun LoadingPackage() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator()
            Text("正在打开离线资料…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun FailedPackage(message: String, onRetry: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("暂时无法打开本地资料", style = MaterialTheme.typography.titleMedium)
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text("重新检查")
            }
        }
    }
}

private data class WikiRequest(
    val repository: WikiRepository,
    val categoryId: String?,
    val query: String,
    val entryId: String?,
    val page: Int,
    val retryToken: Int,
    val filterField: String?,
    val filterValue: String?,
    val sortField: String?,
    val descending: Boolean,
    val routeKey: String? = null,
    val fameDescending: Boolean = false,
)

private fun WikiRequest.browseStateKey(): String = listOf(
    repository.catalog.sourceRevision,
    categoryId,
    query,
    page.toString(),
    filterField,
    filterValue,
    sortField,
    descending.toString(),
    routeKey,
    fameDescending.toString(),
).joinToString(separator = "\u001f") { value ->
    value?.let { "${it.length}:$it" } ?: "-1:"
}

private data class WikiCharacterExtras(
    val gifts: CharacterGiftGuideData? = null,
    val meals: CharacterMealGuideData? = null,
    val supports: List<SupportPairSummary> = emptyList(),
    val birds: List<BirdDialogueRecord> = emptyList(),
    val routeSummaries: List<CharacterRouteSummary> = emptyList(),
)

private sealed interface WikiQueryState {
    val request: WikiRequest
    data class Loading(override val request: WikiRequest) : WikiQueryState
    data class Ready(override val request: WikiRequest, val page: WikiEntryPage?, val entry: WikiEntry?,
        val links: List<WikiLink> = emptyList(),
        val characterLearning: WikiCharacterLearningData = WikiCharacterLearningData(),
        val characterExtras: WikiCharacterExtras = WikiCharacterExtras()) : WikiQueryState
    data class Failed(override val request: WikiRequest) : WikiQueryState
}

private data class WikiImageLoadState(val path: String?, val bitmap: ImageBitmap?, val loaded: Boolean)

@Composable
internal fun WikiCatalogPane(
    repository: WikiRepository,
    query: String,
    selectedCategoryId: String?,
    selectedEntryId: String?,
    onQueryChange: (String) -> Unit,
    onOpenCategory: (String) -> Unit,
    onOpenEntry: (String) -> Unit,
    onBackToCategories: () -> Unit,
    onCloseEntry: () -> Unit,
    onCompare: (String) -> Unit,
    modifier: Modifier = Modifier,
    showHeading: Boolean = true,
    handleBack: Boolean = true,
    entryBackLabel: String? = null,
    detailStateHolder: androidx.compose.runtime.saveable.SaveableStateHolder? = null,
    categoryBackLabel: String = "全部分类",
    onOpenJournal: () -> Unit = {},
    onOpenTierList: () -> Unit = {},
    onOpenRouteMerge: (String) -> Unit = {},
    externalBrowseState: WikiCategoryBrowseState? = null,
    onBrowseStateChange: ((WikiCategoryBrowseState) -> Unit)? = null,
) {
    val catalog = repository.catalog
    val categories = catalog.categories
    val category = categories.firstOrNull { it.id == selectedCategoryId }
    val localBrowseStates = rememberSaveable(saver = wikiCategoryBrowseMapSaver) { mutableStateMapOf() }
    val browseKey = selectedCategoryId ?: "__search__"
    val activeBrowseState = externalBrowseState ?: (localBrowseStates[browseKey] ?: WikiCategoryBrowseState())
    val updateBrowseState: ((WikiCategoryBrowseState) -> WikiCategoryBrowseState) -> Unit = { transform ->
        val next = transform(activeBrowseState)
        if (onBrowseStateChange != null) onBrowseStateChange(next) else localBrowseStates[browseKey] = next
    }
    val filterField = activeBrowseState.filterField
    val filterValue = activeBrowseState.filterValue
    val sortField = activeBrowseState.sortField
    val descending = activeBrowseState.descending
    val routeKey = if (selectedCategoryId == "characters") activeBrowseState.routeKey else null
    val fameDescending = if (selectedCategoryId == "characters") activeBrowseState.fameDescending else false
    val pageIndex = activeBrowseState.pageIndex
    val options by produceState<Pair<String?, List<WikiFieldOptions>>>(
        null to emptyList(), selectedCategoryId, selectedEntryId, repository,
    ) {
        value = selectedCategoryId to withContext(Dispatchers.IO) {
            if (selectedEntryId == null) selectedCategoryId?.let(repository::options).orEmpty() else emptyList()
        }
    }
    val affiliationPortraits by produceState<Map<String, String>>(
        emptyMap(), selectedCategoryId, repository,
    ) {
        value = if (selectedCategoryId == "characters") {
            withContext(Dispatchers.IO) { runCatching { repository.affiliationPortraits() }.getOrDefault(emptyMap()) }
        } else emptyMap()
    }
    var retryToken by rememberSaveable { mutableIntStateOf(0) }
    val request = WikiRequest(
        repository,
        category?.id,
        query.trim(),
        selectedEntryId,
        pageIndex.coerceAtLeast(0),
        retryToken,
        filterField,
        filterValue,
        sortField,
        descending,
        routeKey,
        fameDescending,
    )
    val queryState by produceState<WikiQueryState>(WikiQueryState.Loading(request), request) {
        value = WikiQueryState.Loading(request)
        value = try {
            val result = withContext(Dispatchers.IO) {
                if (request.entryId != null) {
                    val entry = request.repository.entry(request.entryId)
                    val isChar = request.entryId.startsWith("characters:")
                    val extras = if (isChar && entry != null) {
                        WikiCharacterExtras(
                            gifts = runCatching { request.repository.giftPreferencesForCharacter(request.entryId) }.getOrNull(),
                            meals = runCatching { request.repository.mealPreferencesForCharacter(request.entryId) }.getOrNull(),
                            supports = runCatching {
                                request.repository.supportPairs(SupportPairRequest(characterName = entry.title, limit = 100))?.entries.orEmpty()
                            }.getOrDefault(emptyList()),
                            birds = runCatching {
                                request.repository.birdDialogues(BirdDialogueRequest(query = entry.title, limit = 40))?.entries.orEmpty()
                                    .filter { it.characterEntryId == request.entryId || it.characterName == entry.title }
                            }.getOrDefault(emptyList()),
                            routeSummaries = runCatching {
                                request.repository.characterRoutes(request.entryId)
                            }.getOrDefault(emptyList()),
                        )
                    } else WikiCharacterExtras()
                    WikiQueryState.Ready(
                        request, null, entry, request.repository.links(request.entryId),
                        if (isChar) request.repository.characterLearning(request.entryId) else WikiCharacterLearningData(),
                        extras,
                    )
                } else if (request.categoryId != null || request.query.isNotEmpty()) {
                    val page = request.repository.page(WikiPageRequest(
                        categoryId = request.categoryId,
                        query = request.query,
                        filterField = request.filterField,
                        filterValue = request.filterValue,
                        sortField = request.sortField,
                        descending = request.descending,
                        routeKey = request.routeKey,
                        fameDescending = request.fameDescending,
                        offset = (request.page.toLong() * WikiPageRequest.PAGE_SIZE)
                            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    ))
                    WikiQueryState.Ready(request, page, null)
                } else WikiQueryState.Ready(request, null, null)
            }
            val page = result.page
            if (page != null) {
                val lastPage = (page.totalMatches.coerceAtLeast(1) - 1) / WikiPageRequest.PAGE_SIZE
                if (request.page > lastPage) {
                    updateBrowseState { it.copy(pageIndex = lastPage) }
                    WikiQueryState.Loading(request)
                } else result
            } else result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            WikiQueryState.Failed(request)
        }
    }
    val state = queryState.takeIf { it.request == request } ?: WikiQueryState.Loading(request)
    val listState = rememberReadingListState(
        "wiki:${catalog.contentVersion}:" + (selectedEntryId ?: request.browseStateKey()),
        ready = state is WikiQueryState.Ready,
    )
    val detailStates = detailStateHolder ?: androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val openEntry = onOpenEntry
    BackHandler(enabled = handleBack && (selectedEntryId != null || selectedCategoryId != null)) {
        if (selectedEntryId != null) onCloseEntry() else onBackToCategories()
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val contentWidth = if (maxWidth > 880.dp) 880.dp else maxWidth
        val columns = when {
            contentWidth >= 780.dp -> 4
            contentWidth >= 560.dp -> 3
            contentWidth >= 360.dp -> 2
            else -> 1
        }
        val entryColumns = if (contentWidth >= 600.dp) 2 else 1
        val horizontalPadding = if (contentWidth < 600.dp) 16.dp else 24.dp
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 880.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = horizontalPadding, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                if (showHeading && selectedEntryId == null) {
                    ScreenHeading("图鉴", "按角色、职业和装备浏览，查看图片、效果与获取方式。")
                }
                if (showHeading) catalog.updateNotice?.let { notice ->
                    Surface(color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = RoundedCornerShape(16.dp)) {
                        Text(notice, Modifier.fillMaxWidth().padding(14.dp),
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (showHeading && selectedEntryId == null) FuzzySearchBox(
                    query = query,
                    onQueryChange = {
                        updateBrowseState { st -> st.copy(pageIndex = 0) }
                        onQueryChange(it)
                    },
                    label = "搜索图鉴",
                    placeholder = "模糊搜角色、职业、技能、道具…",
                    suggestionsProvider = { q -> repository.suggest(q, categoryId = selectedCategoryId, limit = 7) },
                    onSelectSuggestion = { item -> onOpenEntry(item.id) },
                    modifier = Modifier.fillMaxWidth(),
                    fieldTestTag = "wiki-atlas-search",
                    suggestionKey = selectedCategoryId,
                )
                if (selectedEntryId != null) {
                    TextButton(onClick = onCloseEntry, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(entryBackLabel ?: "返回${category?.title ?: "搜索结果"}")
                    }
                } else if (category != null) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(category.title, style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.semantics { heading() })
                            Text(category.description, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("${category.entryCount} 条", color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelLarge)
                            TextButton(onClick = onBackToCategories, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(categoryBackLabel)
                            }
                        }
                    }
                    val activeOptions = options.takeIf { it.first == selectedCategoryId }?.second.orEmpty()
                    WikiQueryControls(activeOptions,
                        filterField, filterValue, sortField, descending,
                        onFilter = { field, value ->
                            updateBrowseState { it.copy(filterField = field, filterValue = value, pageIndex = 0) }
                        },
                        onSort = { field, reverse ->
                            updateBrowseState { it.copy(sortField = field, descending = reverse, pageIndex = 0) }
                        })
                    if (selectedCategoryId == "characters") {
                        val quickOption = activeOptions.firstOrNull { it.key == "affiliation" && it.values.isNotEmpty() }
                            ?: activeOptions.firstOrNull { it.key == "initial_profession" && it.values.isNotEmpty() }
                        FortunesWeaveCharacterFilterBar(
                            routeKey = routeKey,
                            fameDescending = fameDescending,
                            filterField = filterField,
                            filterValue = filterValue,
                            quickOption = quickOption,
                            affiliationPortraits = affiliationPortraits,
                            onSelectRoute = { rk -> updateBrowseState { it.copy(routeKey = rk, pageIndex = 0) } },
                            onSelectFameDescending = { desc -> updateBrowseState { it.copy(fameDescending = desc, pageIndex = 0) } },
                            onSelectQuickFilter = { f, v -> updateBrowseState { it.copy(filterField = f, filterValue = v, pageIndex = 0) } },
                        )
                    }
                }
            }
        }
        when (state) {
            is WikiQueryState.Loading -> item { LoadingPackage() }
            is WikiQueryState.Failed -> item {
                FailedPackage("暂时无法读取这部分资料，请重试。", onRetry = { retryToken++ })
            }
            is WikiQueryState.Ready -> when {
                selectedEntryId != null -> item {
                    if (state.entry != null) {
                        detailStates.SaveableStateProvider(state.entry.id) {
                        WikiEntryDetail(
                            state.entry,
                            catalog.sourceRevision,
                            state.links,
                            state.characterLearning,
                            state.characterExtras,
                            openEntry,
                            onCompare = { onCompare(state.entry.id) },
                            onOpenJournal = onOpenJournal,
                            onOpenTierList = onOpenTierList,
                            onOpenRouteMerge = onOpenRouteMerge,
                        )
                        }
                    }
                    else Text("这条资料已不在当前版本中，请返回重新选择。")
                }
                state.page != null -> {
                    val page = state.page
                    item {
                        if (page.totalMatches <= 0) {
                            Text("没有找到匹配的图鉴条目。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else WikiPageControls(
                            page,
                            onPrevious = {
                                updateBrowseState { it.copy(pageIndex = (request.page - 1).coerceAtLeast(0)) }
                            },
                            onNext = {
                                if (page.hasNext) updateBrowseState { it.copy(pageIndex = request.page + 1) }
                            },
                        )
                    }
                    items(page.entries.chunked(entryColumns), key = { it.first().id }) { entryRow ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            entryRow.forEach { entry ->
                                WikiEntryCard(
                                    entry,
                                    openEntry,
                                    showCategoryLabel = category == null,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            repeat(entryColumns - entryRow.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    if (page.totalMatches > 0) item {
                        WikiPageControls(
                            page,
                            onPrevious = {
                                updateBrowseState { it.copy(pageIndex = (request.page - 1).coerceAtLeast(0)) }
                            },
                            onNext = {
                                if (page.hasNext) updateBrowseState { it.copy(pageIndex = request.page + 1) }
                            },
                        )
                    }
                }
                else -> {
                    val coreIds = setOf("characters", "professions", "crests", "mounts", "battalions")
                    val combatIds = setOf("skills", "combat-arts", "magic", "weapons", "accessories", "items", "drinks")
                    val groupedSections = listOf(
                        "核心角色与养成" to categories.filter { it.id in coreIds },
                        "战斗、技能与装备" to categories.filter { it.id in combatIds },
                        "剧情、任务与探索" to categories.filterNot { it.id in coreIds || it.id in combatIds },
                    ).filter { it.second.isNotEmpty() }

                    groupedSections.forEach { (sectionTitle, sectionCategories) ->
                        item(key = "wiki-cat-sec-$sectionTitle") {
                            Row(
                                Modifier.fillMaxWidth().padding(top = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    sectionTitle,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.semantics { heading() },
                                )
                                Text(
                                    "${sectionCategories.sumOf(WikiCategorySummary::entryCount)} 条",
                                    style = MaterialTheme.typography.labelMedium.tabularNumbers(),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        items(items = sectionCategories.chunked(columns), key = { it.first().id }) { categoryRow ->
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                categoryRow.forEach { categoryItem ->
                                    WikiCategoryCard(
                                        category = categoryItem,
                                        modifier = Modifier.weight(1f),
                                        onOpen = { onOpenCategory(categoryItem.id) },
                                    )
                                }
                                repeat(columns - categoryRow.size) { Spacer(Modifier.weight(1f)) }
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
private fun WikiPageControls(page: WikiEntryPage, onPrevious: () -> Unit, onNext: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("第 ${page.offset + 1}–${page.offset + page.entries.size} 条 · 共 ${page.totalMatches} 条",
            style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onPrevious, enabled = page.hasPrevious, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("上一页")
            }
            OutlinedButton(onClick = onNext, enabled = page.hasNext, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("下一页")
            }
        }
    }
}

@Composable
private fun WikiEntryCard(
    entry: WikiEntry,
    onOpenEntry: (String) -> Unit,
    showCategoryLabel: Boolean,
    modifier: Modifier = Modifier,
) {
    val catId = entry.id.substringBefore(':')
    val catLabel = wikiCategoryLabel(catId)
    Card(
        onClick = { onOpenEntry(entry.id) },
        modifier = modifier.fillMaxWidth().heightIn(min = 92.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (entry.imageAsset != null) {
                WikiImage(entry.imageAsset, entry.title, Modifier.size(width = 62.dp, height = 74.dp), crop = false)
            } else {
                WikiSemanticCategoryTile(catId, catLabel, Modifier.size(56.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (showCategoryLabel) {
                    EditorialBadge(
                        text = catLabel,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Text(
                    entry.title,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (entry.subtitle.isNotBlank()) {
                    Text(
                        entry.subtitle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                "›",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
}

@Composable
private fun WikiSemanticCategoryTile(categoryId: String, categoryLabel: String, modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    val (bg, fg) = when (categoryId) {
        "characters", "professions" -> if (dark) Color(0xFF422820) to Color(0xFFFFB59D) else Color(0xFFF6E3DC) to Color(0xFF934227)
        "skills", "combat-arts", "magic" -> if (dark) Color(0xFF282D49) to Color(0xFFC5D0FF) else Color(0xFFE4E8F8) to Color(0xFF334382)
        "weapons", "accessories", "items", "drinks" -> if (dark) Color(0xFF3D341E) to Color(0xFFF9DFA3) else Color(0xFFF7EED6) to Color(0xFF6B4F0E)
        "paralogues", "side-quests", "campaign-timeline", "challenge-book", "endings" ->
            if (dark) Color(0xFF203A2E) to Color(0xFFB5E6C9) else Color(0xFFDFEFE6) to Color(0xFF1E5739)
        else -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.primary
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = bg,
        border = BorderStroke(1.dp, fg.copy(alpha = 0.24f)),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = categoryIcon(categoryLabel),
                contentDescription = categoryLabel,
                tint = fg,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

@Composable
private fun WikiCategoryCard(
    category: WikiCategorySummary,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
) {
    Card(
        onClick = onOpen,
        modifier = modifier.heightIn(min = 140.dp).testTag("wiki-category-${category.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                if (category.iconAsset != null) {
                    Surface(
                        modifier = Modifier.size(42.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        WikiImage(
                            category.iconAsset,
                            category.title,
                            Modifier.fillMaxSize().padding(3.dp),
                            crop = false,
                        )
                    }
                } else {
                    Surface(
                        modifier = Modifier.size(38.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.primary,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(categoryIcon(category.title), contentDescription = null, modifier = Modifier.size(20.dp))
                        }
                    }
                }
                Text("${category.entryCount} 条", color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium)
            }
            Text(category.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() })
            Text(category.description, color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

internal fun categoryIcon(title: String): ImageVector = when {
    title.contains("角色") -> Icons.Outlined.Person
    title.contains("血印") -> Icons.Outlined.WorkspacePremium
    title.contains("职业") -> Icons.Outlined.MilitaryTech
    title.contains("战技") -> Icons.Outlined.SportsMartialArts
    title.contains("魔法") -> Icons.Outlined.Bolt
    title.contains("特技") -> Icons.Outlined.AutoFixHigh
    title.contains("技能") -> Icons.Outlined.Lightbulb
    title.contains("武器") -> Icons.Outlined.Hardware
    title.contains("防具") || title.contains("装备") -> Icons.Outlined.Shield
    title.contains("道具") -> Icons.Outlined.Inventory2
    title.contains("外传") -> Icons.Outlined.AutoStories
    title.contains("事件") -> Icons.Outlined.History
    title.contains("挑战") -> Icons.Outlined.AutoStories
    title.contains("任务") -> Icons.Outlined.History
    title.contains("坐骑") -> Icons.Outlined.Pets
    title.contains("军团") || title.contains("骑士团") -> Icons.Outlined.Groups
    title.contains("饮料") || title.contains("饮品") -> Icons.Outlined.LocalDrink
    title.contains("地图") -> Icons.Outlined.Map
    else -> Icons.Outlined.Book
}

@Composable
private fun WikiEntryDetail(
    entry: WikiEntry,
    revision: String,
    links: List<WikiLink>,
    characterLearning: WikiCharacterLearningData,
    characterExtras: WikiCharacterExtras = WikiCharacterExtras(),
    onOpenEntry: (String) -> Unit,
    onCompare: () -> Unit,
    onOpenJournal: () -> Unit = {},
    onOpenTierList: () -> Unit = {},
    onOpenRouteMerge: (String) -> Unit = {},
) {
    val categoryId = entry.id.substringBefore(':')
    val pairedIconAssets = buildMap {
        entry.fields.forEach { f ->
            if (f.label.endsWith("图标") && f.imageAsset != null) {
                put(f.label.removeSuffix("图标"), f.imageAsset)
            }
        }
    }
    val enrichedFields = entry.fields.map { f ->
        if (f.imageAsset == null) {
            pairedIconAssets[f.label]?.let { icon -> f.copy(imageAsset = icon) } ?: f
        } else f
    }
    val growthFields = enrichedFields.filter { it.label.endsWith("成长率") }
    val startingFields = enrichedFields.filter(::isStartingStatField)
    val growthModifierFields = enrichedFields.filter { categoryId == "professions" && it.label.endsWith("成长修正") }
    val baseFields = enrichedFields.filter { categoryId == "professions" && it.label.startsWith("基础") }
    val headerFacts = enrichedFields.filter {
        it.label in WIKI_HEADER_FACT_LABELS && (it.value.isNotBlank() || it.imageAsset != null)
    }
    val linkedFields = enrichedFields.filter { field ->
        field.value.isNotBlank() && field.imageAsset == null && !isNarrativeWikiField(field) && links.any { link ->
            val relationField = link.label.removePrefix("相关条目 · ")
            val evidence = link.evidence.trim()
            field.label == relationField && field.value.trim() == evidence
        }
    }
    val visibleHeaderFacts = headerFacts.take(3)
    val excludedFields = growthFields + startingFields + growthModifierFields + baseFields + visibleHeaderFacts + linkedFields
    val detailFields = enrichedFields.filter { field ->
        excludedFields.none { it === field } && !field.label.endsWith("图标") &&
            (field.value.isNotBlank() || field.imageAsset != null)
    }
    val combatFields = detailFields.filter { field ->
        field.label in WIKI_COMBAT_STAT_LABELS && (
            field.value.toDoubleOrNull() != null ||
                (field.label in WIKI_FLEX_COMBAT_STAT_LABELS && field.value.isNotBlank() && field.value.length <= 16)
            )
    }
    val categorizedFields = detailFields.filterNot { it in combatFields }
    val primaryFields = categorizedFields.filter { isPrimaryWikiField(categoryId, it) }
    val additionalFields = categorizedFields.filterNot { it in primaryFields }
    val learnedTargets = characterLearning.skills.mapNotNull(WikiCharacterSkill::targetId).toSet()
    val endingLinks = if (categoryId == "characters") links.filter { it.targetId.startsWith("endings:") } else emptyList()
    val relatedLinks = if (categoryId == "characters") {
        links.filterNot { it.targetId in learnedTargets || it.targetId.startsWith("endings:") }
    } else links
    var showAdditional by rememberSaveable(entry.id) { mutableStateOf(false) }

    val hasSocialExtras = categoryId == "characters" && (
        characterExtras.supports.isNotEmpty() ||
            characterExtras.gifts?.preferences?.isNotEmpty() == true ||
            characterExtras.meals?.preferences?.isNotEmpty() == true
        )
    val hasBirdOrEndingExtras = categoryId == "characters" && (characterExtras.birds.isNotEmpty() || endingLinks.isNotEmpty())
    var selectedHubTab by rememberSaveable(entry.id) { mutableStateOf("all") }

    val recruitmentLabels = setOf("最早加入章节", "加入条件", "挖角条件")
    val rawRecruitmentFields = if (categoryId == "characters") {
        primaryFields.filter { it.label in recruitmentLabels }
    } else emptyList()
    val nonRecruitmentPrimaryFields = if (categoryId == "characters") {
        primaryFields.filterNot { it.label in recruitmentLabels }
    } else primaryFields
    val routeSummaries = characterExtras.routeSummaries
    val hasCustomRecruitmentFields = rawRecruitmentFields.any { f ->
        routeSummaries.isEmpty() || (
            !f.value.contains("第1部") &&
                !f.value.contains("序幕") &&
                !f.value.contains("第2部") &&
                !f.value.contains("第3部") &&
                !f.value.contains("无缘") &&
                !f.value.contains("临时加入") &&
                !f.value.contains("不可挖角") &&
                !f.value.contains("自动加入") &&
                !f.value.contains("挖角条件") &&
                !f.value.contains("凯伊：") &&
                !f.value.contains("迪托利希：") &&
                !f.value.contains("赛奥朵拉：") &&
                !f.value.contains("蕾达：")
            )
    }

    var recruitmentExpanded by rememberSaveable(entry.id, selectedHubTab) {
        mutableStateOf(selectedHubTab == "overview" || hasCustomRecruitmentFields)
    }
    var learningExpanded by rememberSaveable(entry.id) { mutableStateOf(true) }
    var socialExpanded by rememberSaveable(entry.id, selectedHubTab) {
        mutableStateOf(selectedHubTab == "social")
    }
    var storiesExpanded by rememberSaveable(entry.id, selectedHubTab) {
        mutableStateOf(selectedHubTab == "stories")
    }
    var cultivationExpanded by rememberSaveable(entry.id) { mutableStateOf(false) }
    var linksExpanded by rememberSaveable(entry.id) {
        mutableStateOf(relatedLinks.size <= 3)
    }
    var additionalExpanded by rememberSaveable(entry.id) { mutableStateOf(false) }
    var forcePrimaryExpanded by rememberSaveable(entry.id) { mutableStateOf<Boolean?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        WikiEntryHero(entry, visibleHeaderFacts, wikiCategoryLabel(categoryId))
        WikiEntryActions(entry, onCompare)
        if (categoryId == "characters") {
            val charTabs = buildList {
                add("all" to "全部总览")
                add("overview" to "基础与属性")
                add("skills" to "可学技能")
                if (hasSocialExtras) add("social" to "好感与支援")
                if (hasBirdOrEndingExtras) add("stories" to "逗鸟与结局")
            }
            if (charTabs.none { it.first == selectedHubTab }) selectedHubTab = "all"
            WikiSectionTabBar(
                tabs = charTabs,
                selectedKey = selectedHubTab,
                onSelect = { selectedHubTab = it },
            )
        }
        val showOverview = categoryId != "characters" || selectedHubTab == "all" || selectedHubTab == "overview"
        val showSkills = categoryId == "characters" && (selectedHubTab == "all" || selectedHubTab == "skills")
        val showSocial = hasSocialExtras && (selectedHubTab == "all" || selectedHubTab == "social")
        val showStories = hasBirdOrEndingExtras && (selectedHubTab == "all" || selectedHubTab == "stories")

        if ((categoryId == "characters" && selectedHubTab == "all") ||
            (categoryId != "characters" && (relatedLinks.isNotEmpty() || additionalFields.isNotEmpty() || primaryFields.size >= 4))
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "长项已默认折叠，可按需展开",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = {
                        recruitmentExpanded = true
                        learningExpanded = true
                        socialExpanded = true
                        storiesExpanded = true
                        cultivationExpanded = true
                        linksExpanded = true
                        additionalExpanded = true
                        forcePrimaryExpanded = true
                    },
                    modifier = Modifier.heightIn(min = 36.dp).testTag("wiki-detail-expand-all"),
                ) {
                    Text("全部展开", style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(
                    onClick = {
                        recruitmentExpanded = false
                        learningExpanded = true
                        socialExpanded = false
                        storiesExpanded = false
                        cultivationExpanded = false
                        linksExpanded = false
                        additionalExpanded = false
                        forcePrimaryExpanded = if (categoryId == "professions") true else false
                    },
                    modifier = Modifier.heightIn(min = 36.dp).testTag("wiki-detail-collapse-all"),
                ) {
                    Text("收起长项", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        if (entry.fields.isEmpty()) {
            Text("目前没有更多已整理的条目资料。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (showOverview) {
            if (growthFields.isNotEmpty()) WikiStatGrid(
                title = "成长率",
                fields = growthFields,
                display = { field -> field.label.removeSuffix("成长率") to field.value.removeSuffix("%") + "%" },
                rateBand = { field -> growthRateBand(field.value) },
            )
            if (startingFields.isNotEmpty()) {
                val initLevel = startingFields.firstOrNull { it.label == "初始等级" }
                val combatInit = startingFields.filterNot { it.label == "初始等级" }
                RatedStatGridCard(
                    title = "初始能力",
                    gameShortTitle = "万缕千丝",
                    metricKind = StatMetricKind.BASE_STAT,
                    benchmarks = StatRatingEngine.FORTUNES_WEAVE_BENCHMARKS,
                    stats = combatInit.map { f ->
                        val short = f.label.removePrefix("初始")
                        StatTileEntry(short, f.value, f.value)
                    },
                    metaBadges = initLevel?.let { listOf("初始等级" to it.value) }.orEmpty(),
                )
            }
            if (growthModifierFields.isNotEmpty()) WikiStatGrid(
                title = "成长修正",
                fields = growthModifierFields,
                modifierBand = { field -> growthModifierBand(field.value) },
                display = { field -> field.label.removeSuffix("成长修正") to field.value },
            )
            if (baseFields.isNotEmpty()) {
                RatedStatGridCard(
                    title = "基础能力",
                    gameShortTitle = "万缕千丝",
                    metricKind = StatMetricKind.CLASS_BASE_STAT,
                    benchmarks = StatRatingEngine.FORTUNES_WEAVE_BENCHMARKS,
                    stats = baseFields.map { f ->
                        val short = f.label.removePrefix("基础")
                        val cleanVal = f.value.trim().let { v ->
                            if (v.endsWith(".0") && v.dropLast(2).removePrefix("-").removePrefix("+").all(Char::isDigit)) {
                                v.dropLast(2)
                            } else v
                        }
                        StatTileEntry(short, cleanVal, cleanVal)
                    },
                )
            }
            if (combatFields.isNotEmpty()) WikiStatGrid("战斗数据", combatFields) { field -> field.label to field.value }
            WikiFieldSections(
                categoryId = categoryId,
                fields = nonRecruitmentPrimaryFields,
                defaultExpanded = forcePrimaryExpanded ?: true,
            )
            if (categoryId == "characters" && (routeSummaries.isNotEmpty() || rawRecruitmentFields.isNotEmpty())) {
                val recruitBadge = when {
                    routeSummaries.any { it.routeKey != "other" } -> {
                        val recruitableCount = routeSummaries.count { it.joinKind != "unrecruitable" && it.routeKey != "other" }
                        "四线可加入 $recruitableCount/4"
                    }
                    routeSummaries.isNotEmpty() -> routeSummaries.first().joinChapter.take(14)
                    else -> "${rawRecruitmentFields.size} 项"
                }
                val recruitSubtitle = if (routeSummaries.any { it.routeKey != "other" }) {
                    routeSummaries.joinToString(" · ") { rs ->
                        val tag = when (rs.joinKind) {
                            "story" -> "剧情"
                            "free" -> "免费"
                            "unrecruitable" -> "不可"
                            else -> "名声${rs.fameLevel}"
                        }
                        "${rs.routeLabel.removeSuffix("篇")}:$tag"
                    }
                } else null
                CollapsibleEditorialCard(
                    title = "加入与招募",
                    expanded = recruitmentExpanded,
                    onToggle = { recruitmentExpanded = !recruitmentExpanded },
                    badge = recruitBadge,
                    subtitle = recruitSubtitle,
                    headerTag = "wiki-collapse-recruitment",
                ) {
                    WikiCharacterRecruitmentMatrix(
                        routeSummaries = routeSummaries,
                        fallbackFields = if (hasCustomRecruitmentFields || routeSummaries.isEmpty()) rawRecruitmentFields else emptyList(),
                    )
                }
            }
        }
        if (showSkills) {
            CollapsibleEditorialCard(
                title = "可学技能",
                expanded = learningExpanded,
                onToggle = { learningExpanded = !learningExpanded },
                badge = if (characterLearning.hasSourceData) "${characterLearning.skills.size} 项" else null,
                headerTag = "wiki-collapse-learning",
            ) {
                WikiCharacterLearningSection(characterLearning, onOpenEntry, showHeader = false)
            }
        }
        if (showSocial) {
            val socialCount = characterExtras.supports.size +
                (characterExtras.gifts?.preferences?.size ?: 0) +
                (characterExtras.meals?.preferences?.size ?: 0)
            CollapsibleEditorialCard(
                title = "好感与支援（礼物·餐饮·支援）",
                expanded = socialExpanded,
                onToggle = { socialExpanded = !socialExpanded },
                badge = "$socialCount 项",
                headerTag = "wiki-collapse-social",
            ) {
                WikiCharacterSocialSection(entry.title, characterExtras, onOpenEntry)
            }
        }
        if (showStories) {
            CollapsibleEditorialCard(
                title = "逗鸟问答与结局",
                expanded = storiesExpanded,
                onToggle = { storiesExpanded = !storiesExpanded },
                badge = "${characterExtras.birds.size + endingLinks.size} 条",
                headerTag = "wiki-collapse-stories",
            ) {
                WikiCharacterBirdAndEndingsSection(characterExtras.birds, endingLinks, onOpenEntry)
            }
        }
        if (showOverview && categoryId == "characters") {
            CollapsibleEditorialCard(
                title = "养成与个人记录摘要",
                expanded = cultivationExpanded,
                onToggle = { cultivationExpanded = !cultivationExpanded },
                headerTag = "wiki-collapse-cultivation",
            ) {
                WikiCharacterCultivationSummary(entry, onOpenJournal, onOpenTierList, onOpenRouteMerge)
            }
        }
        if (showOverview && relatedLinks.isNotEmpty()) {
            CollapsibleEditorialCard(
                title = "相关资料",
                expanded = linksExpanded,
                onToggle = { linksExpanded = !linksExpanded },
                badge = "${relatedLinks.size} 条",
                headerTag = "wiki-collapse-links",
            ) {
                WikiRelatedEntries(entry, relatedLinks, onOpenEntry, showHeading = false)
            }
        }
        if (showOverview && additionalFields.isNotEmpty()) {
            CollapsibleEditorialCard(
                title = "更多资料 · ${additionalFields.size} 项",
                expanded = additionalExpanded,
                onToggle = { additionalExpanded = !additionalExpanded },
                headerTag = "wiki-collapse-additional",
            ) {
                WikiFieldSections("other", additionalFields, defaultTitle = "补充信息")
            }
        }
        Text(
            "资料来源：${entry.sourceSheetName} · 第 ${entry.sourceRowNumber} 行 · 修订 $revision",
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun WikiRelatedEntries(
    entry: WikiEntry,
    links: List<WikiLink>,
    onOpenEntry: (String) -> Unit,
    showHeading: Boolean = true,
) {
    var expanded by rememberSaveable(entry.id) { mutableStateOf(false) }
    val visibleLinks = if (expanded) links else links.take(4)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showHeading) WikiDetailSectionHeading("相关资料")
        visibleLinks.forEach { link ->
            val relation = link.label.removePrefix("相关条目 · ")
            Card(
                onClick = { onOpenEntry(link.targetId) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (link.targetImageAsset != null) {
                        Surface(
                            modifier = Modifier.size(42.dp),
                            shape = RoundedCornerShape(11.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            WikiImage(
                                link.targetImageAsset,
                                link.title,
                                Modifier.fillMaxSize().padding(3.dp),
                                crop = false,
                            )
                        }
                    } else {
                        Surface(
                            modifier = Modifier.size(38.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.primary,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(categoryIcon(wikiCategoryLabel(link.targetId.substringBefore(':'))),
                                    contentDescription = null, modifier = Modifier.size(19.dp))
                            }
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(relation, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)
                        Text(link.title, style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold)
                        link.evidence.takeIf { it.isNotBlank() && it != entry.title && it != link.title }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Text("打开", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        if (links.size > 4) {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (expanded) "收起关联" else "查看全部 ${links.size} 项关联")
            }
        }
    }
}

@Composable
private fun WikiCharacterLearningSection(
    data: WikiCharacterLearningData,
    onOpenEntry: (String) -> Unit,
    showHeader: Boolean = true,
) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag("wiki-character-learning"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (showHeader) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.AutoFixHigh, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp))
            WikiDetailSectionHeading("可学技能")
            Spacer(Modifier.weight(1f))
            if (data.hasSourceData && data.skills.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = CircleShape) {
                    Text("${data.skills.size} 项", Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (!data.hasSourceData) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("暂未收录该角色的可学技能资料。", style = MaterialTheme.typography.titleSmall)
                    Text("这不代表该角色没有可学技能。", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        } else if (data.skills.isEmpty()) {
            Text("当前来源没有列出该角色的具体技能条目。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val allGroups = data.skills
                .groupBy(WikiCharacterSkill::groupPosition)
                .toSortedMap()
            val tracks = allGroups.entries.map { (_, group) ->
                val label = group.first().groupLabel
                SkillMatrixTrack(
                    trackKey = label,
                    trackLabel = label,
                    initialRank = "—",
                    items = group.sortedBy(WikiCharacterSkill::position).map { sk ->
                        val rank = wikiLearningRank(sk.requirement)
                        SkillMatrixItem(
                            name = sk.name,
                            requirement = sk.requirement,
                            targetEntryId = sk.targetId,
                            isUniversal = isUniversalSkillName(sk.name),
                            imageAsset = sk.imageAsset,
                            range = sk.range,
                            itemTag = "wiki-character-learning-${sk.groupPosition}-${sk.position}",
                            rankTag = rank?.let {
                                "wiki-learning-rank-$it-${sk.groupPosition}-${sk.position}"
                            } ?: "wiki-learning-requirement-other-${sk.groupPosition}-${sk.position}",
                        )
                    },
                )
            }
            StructuredSkillMatrixCard(
                tracks = tracks,
                onOpenEntry = onOpenEntry,
                skillIconContent = { item ->
                    WikiImage(item.imageAsset, "${item.name}图标", Modifier.size(22.dp), crop = false)
                },
            )
        }
    }
}

internal fun wikiLearningRank(requirement: String): String? = requirement.trim()
    .uppercase()
    .replace('＋', '+')
    .takeIf { it in WIKI_LEARNING_RANKS }

internal data class WikiLearningRankTone(val container: Color, val content: Color)

internal fun wikiLearningRankTone(rank: String, darkTheme: Boolean): WikiLearningRankTone? {
    val normalizedRank = wikiLearningRank(rank) ?: return null
    return when (normalizedRank) {
        "E" -> if (darkTheme) WikiLearningRankTone(Color(0xFF4B5563), Color(0xFFF3F4F6))
            else WikiLearningRankTone(Color(0xFFC7D0DB), Color(0xFF243244))
        "E+" -> if (darkTheme) WikiLearningRankTone(Color(0xFF475569), Color(0xFFF1F5F9))
            else WikiLearningRankTone(Color(0xFF9AA9BB), Color(0xFF172538))
        "D" -> if (darkTheme) WikiLearningRankTone(Color(0xFF3730A3), Color(0xFFE0E7FF))
            else WikiLearningRankTone(Color(0xFFA5B4FC), Color(0xFF312E81))
        "D+" -> if (darkTheme) WikiLearningRankTone(Color(0xFF075985), Color(0xFFE0F2FE))
            else WikiLearningRankTone(Color(0xFF7DD3FC), Color(0xFF075985))
        "C" -> if (darkTheme) WikiLearningRankTone(Color(0xFF0E7490), Color(0xFFCFFAFE))
            else WikiLearningRankTone(Color(0xFF67E8F9), Color(0xFF155E75))
        "C+" -> if (darkTheme) WikiLearningRankTone(Color(0xFF0F766E), Color(0xFFCCFBF1))
            else WikiLearningRankTone(Color(0xFF5EEAD4), Color(0xFF115E59))
        "B" -> if (darkTheme) WikiLearningRankTone(Color(0xFF15803D), Color(0xFFDCFCE7))
            else WikiLearningRankTone(Color(0xFF86EFAC), Color(0xFF166534))
        "B+" -> if (darkTheme) WikiLearningRankTone(Color(0xFF4D7C0F), Color(0xFFECFCCB))
            else WikiLearningRankTone(Color(0xFFBEF264), Color(0xFF3F6212))
        "A" -> if (darkTheme) WikiLearningRankTone(Color(0xFFA16207), Color(0xFFFEF3C7))
            else WikiLearningRankTone(Color(0xFFFDE047), Color(0xFF713F12))
        "A+" -> if (darkTheme) WikiLearningRankTone(Color(0xFFC2410C), Color(0xFFFFEDD5))
            else WikiLearningRankTone(Color(0xFFFDBA74), Color(0xFF7C2D12))
        "S" -> if (darkTheme) WikiLearningRankTone(Color(0xFFBE123C), Color(0xFFFFE4E6))
            else WikiLearningRankTone(Color(0xFFFDA4AF), Color(0xFF881337))
        "S+" -> if (darkTheme) WikiLearningRankTone(Color(0xFF7E22CE), Color(0xFFF3E8FF))
            else WikiLearningRankTone(Color(0xFFD8B4FE), Color(0xFF581C87))
        else -> null
    }
}

private val WIKI_LEARNING_RANKS = setOf(
    "E", "E+", "D", "D+", "C", "C+", "B", "B+", "A", "A+", "S", "S+",
)

@Composable
private fun WikiFieldSections(
    categoryId: String,
    fields: List<WikiField>,
    defaultTitle: String? = null,
    defaultExpanded: Boolean = true,
) {
    fields.groupBy { wikiFieldSection(categoryId, it.label, defaultTitle) }.forEach { (section, sectionFields) ->
        if (categoryId == "other" || categoryId == "characters") {
            WikiDetailSectionHeading(section)
            WikiCompactFieldGrid(sectionFields)
        } else {
            var sectionExpanded by rememberSaveable(categoryId, section, defaultExpanded) {
                mutableStateOf(defaultExpanded)
            }
            CollapsibleEditorialCard(
                title = section,
                expanded = sectionExpanded,
                onToggle = { sectionExpanded = !sectionExpanded },
                badge = "${sectionFields.size} 项",
            ) {
                WikiCompactFieldGrid(sectionFields)
            }
        }
    }
}

@Composable
private fun WikiCharacterRecruitmentMatrix(
    routeSummaries: List<CharacterRouteSummary>,
    fallbackFields: List<WikiField>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (routeSummaries.isNotEmpty()) {
            routeSummaries.forEach { route ->
                val (badgeBg, badgeFg) = when (route.routeKey) {
                    "kai" -> androidx.compose.ui.graphics.Color(0xFFF9E2E0) to androidx.compose.ui.graphics.Color(0xFF7D241E)
                    "dit" -> androidx.compose.ui.graphics.Color(0xFFE0ECF8) to androidx.compose.ui.graphics.Color(0xFF1F4770)
                    "seo" -> androidx.compose.ui.graphics.Color(0xFFF9EED2) to androidx.compose.ui.graphics.Color(0xFF6B4C0B)
                    "leda" -> androidx.compose.ui.graphics.Color(0xFFECE6F6) to androidx.compose.ui.graphics.Color(0xFF483668)
                    else -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
                }
                val isUnrecruitable = route.joinKind == "unrecruitable"
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = if (isUnrecruitable) {
                        MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.65f)
                    } else MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(
                        1.dp,
                        if (route.recommended) badgeFg.copy(alpha = 0.35f)
                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                    ),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            EditorialBadge(route.routeLabel, containerColor = badgeBg, contentColor = badgeFg)
                            val fameLabel = when (route.joinKind) {
                                "story" -> "剧情加入"
                                "free" -> "免费招募（名声2）"
                                "fame" -> "名声 Lv.${route.fameLevel}"
                                "unrecruitable" -> "本线不可挖角"
                                "prologue" -> "序幕加入"
                                "part2" -> "第2部加入"
                                "part3" -> "第3部加入"
                                else -> "剧情 / 临时"
                            }
                            EditorialBadge(
                                fameLabel,
                                containerColor = if (isUnrecruitable) MaterialTheme.colorScheme.surfaceVariant
                                else MaterialTheme.colorScheme.primaryContainer,
                                contentColor = if (isUnrecruitable) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            if (route.joinChapter.isNotBlank() && !isUnrecruitable) {
                                EditorialBadge(
                                    route.joinChapter + (route.initialLevel?.let { " · Lv.$it" } ?: ""),
                                )
                            }
                            if (route.supportReq.isNotBlank()) {
                                EditorialBadge(
                                    "需 ${route.supportReq}",
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                            }
                            if (route.recommended) {
                                EditorialBadge(
                                    "推荐培养 ✅",
                                    containerColor = androidx.compose.ui.graphics.Color(0xFFDCFCE7),
                                    contentColor = androidx.compose.ui.graphics.Color(0xFF14532D),
                                )
                            }
                        }
                        if (route.poachCondition.isNotBlank()) {
                            val parts = route.poachCondition.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                            if (parts.size >= 2) {
                                Text(parts[0], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Surface(
                                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f),
                                    shape = RoundedCornerShape(8.dp),
                                ) {
                                    Text(
                                        "交涉 / 条件：${parts.drop(1).joinToString(" · ")}",
                                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            } else {
                                Text(route.poachCondition, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                        if (route.contactLocation.isNotBlank() && !isUnrecruitable) {
                            Text(
                                "接触时期/地点：${route.contactLocation}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        if (fallbackFields.isNotEmpty()) {
            WikiCompactFieldGrid(fallbackFields)
        }
    }
}

private fun isPrimaryWikiField(categoryId: String, field: WikiField): Boolean {
    val label = field.label
    return when (categoryId) {
        "characters" -> label in setOf("个人特技", "等级特技", "特技效果", "等级特技效果", "血印", "最早加入章节", "加入条件", "挖角条件")
        "professions" -> label in setOf("职业级别", "考试要求", "技能经验加成", "移动力", "兵种特性", "固有特技", "精通所需经验", "精通特技")
        "crests" -> label in setOf("血印效果", "持有者", "对应纹章", "纹章描述")
        "skills" -> label in setOf("特技效果", "习得条件")
        "combat-arts" -> label in setOf("使用武器", "射程", "战技描述", "有效", "特效", "习得条件")
        "magic" -> label in setOf("魔术类型", "射程", "魔法描述", "有效", "特效", "习得条件")
        "weapons" -> label in setOf("武器类型", "射程", "详细说明", "有效", "特效", "获取方法", "修理材料")
        "accessories" -> label in setOf("装备类型", "详细说明", "其他效果", "获取方法")
        "items" -> label in setOf("详细说明", "获得方法", "地图参考", "购买价格", "出售价格")
        "paralogues" -> label in setOf("相关角色", "触发时期", "触发条件与特殊奖励", "报酬奖励", "胜利条件") || label.endsWith("期限")
        "side-quests" -> label in setOf("任务类型", "任务NPC", "报酬奖励", "触发时期与地点", "达成条件/期限", "提示与备注") || label in setOf("凯篇", "迪篇", "赛篇", "蕾篇")
        "mounts" -> label in setOf("种类", "描述", "口味", "食材", "诱捕素材", "喜好蔬菜", "捕捉地点", "满级放生装备", "放生装备效果") || label.startsWith("骑乘特技")
        "battalions" -> label in setOf("分类", "指挥要求", "计策", "计策范围", "计策效果", "获取方法")
        "drinks" -> label in setOf("调配材料", "效果", "说明")
        "campaign-timeline" -> label in setOf("事件类别", "事件名称", "章节", "开始时间", "截止时间", "触发方式", "奖励", "备注")
        "challenge-book" -> label in setOf("对应战技", "战技效果", "接取条件", "任务截止时间", "任务地点", "任务奖励")
        else -> field.value.isNotBlank()
    }
}

internal fun wikiCategoryLabel(categoryId: String): String = when (categoryId) {
    "characters" -> "角色"
    "professions" -> "职业"
    "crests" -> "血印"
    "skills" -> "特技"
    "combat-arts" -> "战技"
    "magic" -> "魔法"
    "weapons" -> "武器"
    "accessories" -> "装备"
    "items" -> "道具"
    "paralogues" -> "外传"
    "side-quests" -> "支线任务"
    "mounts" -> "坐骑"
    "battalions" -> "骑士团"
    "drinks" -> "饮品"
    "campaign-timeline" -> "时间轴"
    "challenge-book" -> "挑战书"
    "beginner-guide" -> "新手指南"
    "beginner-routes" -> "路线选择"
    "beginner-map-access" -> "地图开放条件"
    else -> "图鉴"
}

private fun wikiFieldSection(categoryId: String, label: String, defaultTitle: String?): String {
    if (defaultTitle != null) return defaultTitle
    return when (categoryId) {
        "characters" -> if (label.contains("加入") || label.contains("章节")) "加入与招募" else "特技与血印"
        "professions" -> when {
            label.contains("考试") || label.contains("级別") || label.contains("级别") -> "职业要求"
            label.contains("精通") || label.contains("固有特技") -> "特技与精通"
            else -> "职业特点"
        }
        "crests" -> "血印效果与持有者"
        "skills" -> "效果与习得条件"
        "combat-arts", "magic" -> if (label.contains("习得")) "习得条件" else "效果与使用方式"
        "weapons", "accessories", "items" -> if (label.contains("获取") || label.contains("获得") || label.contains("修理")) "获取与维护" else "效果与说明"
        "paralogues", "side-quests" -> when {
            label.contains("期限") || label.contains("篇") || label.contains("时期") || label.contains("地点") -> "篇章与触发时间"
            label.contains("报酬") || label.contains("奖励") || label.contains("胜利") -> "目标与奖励"
            else -> "触发条件"
        }
        "mounts" -> "坐骑与捕捉"
        "battalions" -> "战术与效果"
        "drinks" -> "调配与效果"
        "campaign-timeline" -> when {
            label in setOf("章节", "开始时间", "截止时间", "路线范围") -> "篇章与时间"
            label in setOf("触发方式", "相关人物", "事件类别", "事件名称") -> "事件详情"
            else -> "奖励与备注"
        }
        "challenge-book" -> when {
            label in setOf("接取条件", "任务截止时间", "任务地点") -> "挑战条件"
            label in setOf("对应战技", "耐久消耗", "威力", "命中", "必杀", "射程", "战技效果") -> "战技资料"
            else -> "完成奖励"
        }
        "beginner-routes" -> when (label) {
            "原资料主观推荐", "可挖角人数" -> "路线概览"
            "游玩注意", "可活动范围" -> "游玩提示"
            else -> "玩法特色"
        }
        "beginner-map-access" -> "区域开放条件"
        "beginner-guide" -> "常见问题"
        else -> "主要信息"
    }
}

private val WIKI_COMBAT_STAT_LABELS = setOf(
    "等级", "耐久消耗", "使用次数", "耐久", "威力", "威力/愈力", "命中", "必杀", "回避", "必避", "射程", "重量",
    "购买价格", "出售价格", "次数", "力量", "魔力", "速度", "技巧", "防守", "魔防", "幸运", "魅力", "指挥要求",
)

private val WIKI_FLEX_COMBAT_STAT_LABELS = setOf(
    "射程", "等级", "威力/愈力", "耐久", "使用次数", "耐久消耗", "指挥要求",
)

@Composable
private fun WikiEntryHero(entry: WikiEntry, facts: List<WikiField>, categoryLabel: String) {
    val catId = entry.id.substringBefore(':')
    EditorialCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (entry.imageAsset != null) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    WikiImage(entry.imageAsset, entry.title, Modifier.size(width = 100.dp, height = 126.dp), crop = false)
                }
            } else {
                WikiSemanticCategoryTile(catId, categoryLabel, Modifier.size(72.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                EditorialBadge(
                    text = categoryLabel,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    entry.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { heading() },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (entry.subtitle.isNotBlank() && facts.none { it.value == entry.subtitle }) {
                    Text(
                        entry.subtitle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (facts.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        facts.take(3).forEach { field ->
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .padding(horizontal = 9.dp, vertical = 5.dp)
                                        .testTag("wiki-hero-fact-${field.label}"),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                ) {
                                    field.imageAsset?.let { iconPath ->
                                        WikiImage(iconPath, field.label, Modifier.size(18.dp), crop = false)
                                    }
                                    val factText = if (field.value.isNotBlank()) "${field.label} · ${field.value}" else field.label
                                    Text(
                                        factText,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
}

@Composable
private fun WikiStatGrid(
    title: String,
    fields: List<WikiField>,
    rateBand: ((WikiField) -> GrowthRateBand?)? = null,
    modifierBand: ((WikiField) -> GrowthModifierBand?)? = null,
    display: (WikiField) -> Pair<String, String>,
) {
    EditorialCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            WikiDetailSectionHeading(title)
            if (rateBand != null) WikiGrowthRateLegend()
            if (modifierBand != null) WikiGrowthModifierLegend()
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val cols = if (maxWidth >= 560.dp) 5 else 3
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    fields.chunked(cols).forEach { rowFields ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowFields.forEach { field ->
                                val (label, value) = display(field)
                                val rate = rateBand?.invoke(field)
                                val modifier = modifierBand?.invoke(field)
                                val tone = rate?.let { growthRateTone(it) } ?: modifier?.let { growthModifierTone(it) }
                                val numericVal = value.trim().removeSuffix("%").trim().toDoubleOrNull()
                                val isRangeHighlight = tone == null && label == "射程"
                                val barFraction = when {
                                    label in setOf("射程", "等级", "指挥要求") -> null
                                    rate != null && numericVal != null -> (numericVal / 70.0).toFloat().coerceIn(0.08f, 1f)
                                    modifier != null && numericVal != null -> ((numericVal + 15.0) / 35.0).toFloat().coerceIn(0.08f, 1f)
                                    numericVal != null && numericVal >= 0 -> (numericVal / 30.0).toFloat().coerceIn(0.08f, 1f)
                                    else -> null
                                }
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 78.dp)
                                        .testTag("wiki-stat-tone-$title-$label"),
                                    color = tone?.container ?: if (isRangeHighlight) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerLow
                                    },
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(
                                        1.dp,
                                        tone?.content?.copy(alpha = 0.24f) ?: if (isRangeHighlight) {
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                                        } else {
                                            MaterialTheme.colorScheme.outlineVariant
                                        },
                                    ),
                                ) {
                                    Column(
                                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 9.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text(
                                            label,
                                            modifier = Modifier.testTag("wiki-stat-$title-$label"),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = tone?.content?.copy(alpha = 0.85f) ?: if (isRangeHighlight) {
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            value,
                                            style = MaterialTheme.typography.titleMedium.tabularNumbers(),
                                            color = tone?.content ?: if (isRangeHighlight) {
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (barFraction != null) {
                                            Box(
                                                Modifier
                                                    .fillMaxWidth()
                                                    .height(4.dp)
                                                    .clip(RoundedCornerShape(100.dp))
                                                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                                            ) {
                                                Box(
                                                    Modifier
                                                        .fillMaxWidth(barFraction)
                                                        .height(4.dp)
                                                        .clip(RoundedCornerShape(100.dp))
                                                        .background(tone?.content?.copy(alpha = 0.72f) ?: MaterialTheme.colorScheme.primary),
                                                )
                                            }
                                        }
                                        (rate?.label ?: modifier?.label)?.let { bandLabel ->
                                            Text(
                                                bandLabel,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = tone?.content ?: MaterialTheme.colorScheme.onSurface,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                        }
                                    }
                                }
                            }
                            repeat(cols - rowFields.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WikiGrowthRateLegend() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GrowthRateBand.entries.forEach { band ->
                val tone = growthRateTone(band)
                Surface(modifier = Modifier.weight(1f), color = tone.container,
                    shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(vertical = 7.dp, horizontal = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(band.label, style = MaterialTheme.typography.labelMedium,
                            color = tone.content, fontWeight = FontWeight.Medium)
                        Text(band.range, style = MaterialTheme.typography.labelSmall,
                            color = tone.content)
                    }
                }
            }
        }
        Text("分档依据：68 名角色的 612 项成长率，Q1 30% · 中位数 40% · Q3 45%",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WikiGrowthModifierLegend() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GrowthModifierBand.entries.forEach { band ->
                val tone = growthModifierTone(band)
                Surface(modifier = Modifier.weight(1f), color = tone.container,
                    shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(vertical = 7.dp, horizontal = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(band.label, style = MaterialTheme.typography.labelMedium,
                            color = tone.content, fontWeight = FontWeight.Medium)
                        Text(band.range, style = MaterialTheme.typography.labelSmall,
                            color = tone.content)
                    }
                }
            }
        }
        Text(
            "分档依据：63 个职业的 567 项成长修正，Q1 0% · 中位数 5% · Q3 10%；0%表示没有职业加减。",
            modifier = Modifier.testTag("wiki-growth-modifier-distribution"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal enum class GrowthRateBand(val label: String, val range: String) {
    LOW("偏低", "≤30%"),
    MEDIUM("中等", "31–44%"),
    HIGH("偏高", "≥45%"),
}

internal enum class GrowthModifierBand(val label: String, val range: String) {
    LOW("偏低", "<0%"),
    MEDIUM("中等", "0–5%"),
    HIGH("偏高", "≥10%"),
}

internal fun growthRateBand(value: String): GrowthRateBand? {
    val rate = value.trim().removeSuffix("%").trim().toDoubleOrNull()
        ?.takeIf { it.isFinite() } ?: return null
    return when {
        rate <= 30.0 -> GrowthRateBand.LOW
        rate < 45.0 -> GrowthRateBand.MEDIUM
        else -> GrowthRateBand.HIGH
    }
}

internal fun growthModifierBand(value: String): GrowthModifierBand? {
    val modifier = value.trim().removeSuffix("%").trim().toDoubleOrNull()
        ?.takeIf { it.isFinite() } ?: return null
    return when {
        modifier < 0.0 -> GrowthModifierBand.LOW
        modifier < 10.0 -> GrowthModifierBand.MEDIUM
        else -> GrowthModifierBand.HIGH
    }
}

private data class GrowthRateTone(val container: Color, val content: Color)

@Composable
private fun growthRateTone(band: GrowthRateBand): GrowthRateTone = when (band) {
    GrowthRateBand.LOW -> if (isSystemInDarkTheme()) {
        GrowthRateTone(Color(0xFF542F2C), Color(0xFFFFDAD6))
    } else {
        GrowthRateTone(Color(0xFFF6DEDC), Color(0xFF712B27))
    }
    GrowthRateBand.MEDIUM -> if (isSystemInDarkTheme()) {
        GrowthRateTone(Color(0xFF4B3B1F), Color(0xFFFFE2A8))
    } else {
        GrowthRateTone(Color(0xFFF8EBCB), Color(0xFF674300))
    }
    GrowthRateBand.HIGH -> if (isSystemInDarkTheme()) {
        GrowthRateTone(Color(0xFF244433), Color(0xFFC2F0D0))
    } else {
        GrowthRateTone(Color(0xFFDDECE0), Color(0xFF20543A))
    }
}

@Composable
private fun growthModifierTone(band: GrowthModifierBand): GrowthRateTone = when (band) {
    GrowthModifierBand.LOW -> growthRateTone(GrowthRateBand.LOW)
    GrowthModifierBand.MEDIUM -> growthRateTone(GrowthRateBand.MEDIUM)
    GrowthModifierBand.HIGH -> growthRateTone(GrowthRateBand.HIGH)
}

@Composable
private fun WikiCompactFieldGrid(fields: List<WikiField>) {
    val shortFields = fields.filterNot(::isNarrativeWikiField)
    val narrativeFields = fields.filter(::isNarrativeWikiField)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (shortFields.isNotEmpty()) {
            EditorialCard(modifier = Modifier.fillMaxWidth()) {
                BoxWithConstraints(Modifier.fillMaxWidth().padding(14.dp)) {
                    val cols = if (maxWidth >= 560.dp) 3 else 2
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        shortFields.chunked(cols).forEach { rowFields ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                rowFields.forEach { field ->
                                    WikiCompactField(field, Modifier.weight(1f))
                                }
                                repeat(cols - rowFields.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        }
        narrativeFields.forEach { field ->
            WikiNarrativeField(field)
        }
    }
}

@Composable
private fun WikiCompactField(field: WikiField, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.heightIn(min = 58.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                field.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                field.imageAsset?.let {
                    val imgSize = if (field.label == "计策范围") 88.dp else 34.dp
                    WikiImage(it, field.label, Modifier.size(imgSize), crop = false)
                }
                if (field.value.isNotBlank()) Text(
                    field.value,
                    Modifier.weight(1f).testTag("wiki-compact-fact-${field.label}"),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun WikiNarrativeField(field: WikiField) {
    EditorialCard(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier
                    .size(width = 3.dp, height = 22.dp)
                    .clip(RoundedCornerShape(100.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    field.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    field.imageAsset?.let {
                        val imgSize = if (field.label == "计策范围") 92.dp else 40.dp
                        WikiImage(it, field.label, Modifier.size(imgSize), crop = false)
                    }
                    if (field.value.isNotBlank()) {
                        val lines = field.value.lines().map { it.trim() }.filter { it.isNotEmpty() }
                        val looksLikeRoutes = lines.size >= 2 && lines.any {
                            it.contains("凯伊篇") || it.contains("迪托利希篇") || it.contains("赛奥朵拉篇") ||
                                it.contains("蕾达篇") || it.contains("凯篇") || it.contains("迪篇") ||
                                it.contains("赛篇") || it.contains("蕾篇")
                        }
                        if (field.label.contains("路线") && !field.value.contains("\n") &&
                            (field.value.contains("、") || field.value.contains("/"))
                        ) {
                            Box(Modifier.weight(1f)) { RouteAvailabilityBadgesRow(field.value) }
                        } else if (looksLikeRoutes) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                lines.forEach { ln ->
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                                        shape = RoundedCornerShape(10.dp),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                                    ) {
                                        Text(
                                            ln,
                                            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            }
                        } else {
                            Text(field.value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

private data class CharacterCultivationSummary(
    val tierLabel: String?,
    val tierNote: String?,
    val journalCount: Int,
    val latestLineLabel: String?,
    val latestLevel: Int?,
    val levelUpsCount: Int,
    val mergeLineCount: Int = 0,
)

@Composable
private fun WikiCharacterCultivationSummary(
    entry: WikiEntry,
    onOpenJournal: () -> Unit,
    onOpenTierList: () -> Unit,
    onOpenRouteMerge: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val summary by produceState<CharacterCultivationSummary?>(null, context, entry.id, entry.title) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val profileId = context.getSharedPreferences("personal-view", 0)
                    .getString("profile", "global") ?: "global"
                val snapshot = PersonalStore(context).snapshot()
                val tierList = PersonalTierList.readProfile(snapshot, profileId).lists.firstOrNull()?.list
                val tierEntry = tierList?.entryFor(entry.id)
                val tierLabel = tierEntry?.let { te -> tierList.tiers.firstOrNull { it.id == te.tierId }?.label }
                val journals = snapshot.heads().values.mapNotNull { it.singleOrNull() }
                    .filter { !it.deleted && it.kind == LevelJournal.KIND }
                    .mapNotNull { rev -> runCatching { LevelJournal.decode(rev.payload) }.getOrNull() }
                    .filter { journal ->
                        journal.profile == profileId && (
                            journal.characterId == entry.id ||
                                journal.characterId == entry.title ||
                                journal.characterId == entry.id.substringAfter(':')
                            )
                    }
                val mergeCount = RouteMergeCharacter.readProfile(snapshot, profileId)
                    .byCharacterId(entry.id)?.character?.recordedLines?.size ?: 0
                val latest = journals.lastOrNull()
                CharacterCultivationSummary(
                    tierLabel = tierLabel,
                    tierNote = tierEntry?.note?.takeIf { it.isNotBlank() },
                    journalCount = journals.size,
                    latestLineLabel = latest?.line?.label,
                    latestLevel = latest?.currentLevel,
                    levelUpsCount = latest?.observations?.count { it.levelsUp } ?: 0,
                    mergeLineCount = mergeCount,
                )
            }.getOrNull()
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth().testTag("character-cultivation-summary"),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            WikiDetailSectionHeading("个人培养摘要")
            val current = summary
            if (current == null) {
                Text(
                    "可从此处快速联动你的四线融合面板、私人梯度榜与升级记录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    EditorialBadge(
                        text = if (current.mergeLineCount > 0) "多线融合：已录 ${current.mergeLineCount} 线" else "未录四线属性",
                        containerColor = if (current.mergeLineCount > 0) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        },
                        contentColor = if (current.mergeLineCount > 0) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    EditorialBadge(
                        text = if (current.tierLabel != null) "梯度：${current.tierLabel}" else "未入梯度榜",
                        containerColor = if (current.tierLabel != null) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        },
                        contentColor = if (current.tierLabel != null) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    EditorialBadge(
                        text = if (current.journalCount > 0) {
                            buildString {
                                append("升级记录 ${current.journalCount} 册")
                                current.latestLineLabel?.let { append(" · $it") }
                                current.latestLevel?.let { append(" Lv.$it") }
                                if (current.levelUpsCount > 0) append("（+${current.levelUpsCount}）")
                            }
                        } else {
                            "暂无升级记录"
                        },
                    )
                }
                current.tierNote?.let { note ->
                    Text(
                        "评价：$note",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Button(
                    onClick = { onOpenRouteMerge(entry.id) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("character-open-route-merge"),
                ) { Text("多线融合面板") }
                OutlinedButton(
                    onClick = onOpenJournal,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("去升级记录") }
                OutlinedButton(
                    onClick = onOpenTierList,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("去私人梯度榜") }
            }
        }
    }
}

@Composable
private fun WikiCharacterSocialSection(
    characterName: String,
    extras: WikiCharacterExtras,
    onOpenEntry: (String) -> Unit,
) {
    val highPal = statTierPalette(StatRatingTier.HIGH)
    val midPal = statTierPalette(StatRatingTier.MEDIUM)
    val lowPal = statTierPalette(StatRatingTier.LOW)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (extras.supports.isNotEmpty()) {
            EditorialCard(modifier = Modifier.fillMaxWidth().testTag("wiki-character-supports")) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("支援对象", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        EditorialBadge(text = "${extras.supports.size} 位")
                    }
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val cols = if (maxWidth >= 560.dp) 3 else 2
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            extras.supports.chunked(cols).forEach { row ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    row.forEach { pair ->
                                        val partner = if (pair.first.displayName == characterName || pair.first.sourceName == characterName) {
                                            pair.second
                                        } else pair.first
                                        Surface(
                                            onClick = { partner.wikiCharacterId?.let(onOpenEntry) },
                                            enabled = partner.wikiCharacterId != null,
                                            modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                                            shape = RoundedCornerShape(14.dp),
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                        ) {
                                            Row(
                                                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            ) {
                                                WikiImage(partner.imageAsset, partner.displayName, Modifier.size(36.dp), crop = true)
                                                Column(Modifier.weight(1f)) {
                                                    Text(partner.displayName, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                    val meta = buildList {
                                                        if (pair.hasCombinationBonus) add("组合加成")
                                                        if (pair.dialogueCount > 0) add("${pair.dialogueCount}段对话")
                                                    }.joinToString(" · ")
                                                    if (meta.isNotEmpty()) {
                                                        Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                                    }
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
        val giftPrefs = extras.gifts?.preferences.orEmpty()
        if (giftPrefs.isNotEmpty()) {
            EditorialCard(modifier = Modifier.fillMaxWidth().testTag("wiki-character-gifts")) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("礼物偏好", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    GiftPreferenceLevel.entries.forEach { level ->
                        val items = giftPrefs.filter { it.level == level }
                        if (items.isNotEmpty()) {
                            val pal = when (level) {
                                GiftPreferenceLevel.VERY_LIKED, GiftPreferenceLevel.LIKED -> highPal
                                GiftPreferenceLevel.SOMEWHAT_LIKED -> midPal
                                GiftPreferenceLevel.DISLIKED -> lowPal
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("${level.label}（${items.size}）", style = MaterialTheme.typography.labelLarge, color = pal.content)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                    items.forEach { rec ->
                                        Surface(
                                            onClick = { onOpenEntry(rec.giftEntryId) },
                                            shape = RoundedCornerShape(100.dp),
                                            color = pal.container,
                                            contentColor = pal.content,
                                            border = BorderStroke(1.dp, pal.bar.copy(alpha = 0.35f)),
                                        ) {
                                            Text(rec.giftName, Modifier.padding(horizontal = 11.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        val mealPrefs = extras.meals?.preferences.orEmpty().filterNot { it.level == MealPreferenceLevel.ORDINARY }
        if (mealPrefs.isNotEmpty()) {
            EditorialCard(modifier = Modifier.fillMaxWidth().testTag("wiki-character-meals")) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("料理偏好", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    listOf(MealPreferenceLevel.VERY_LIKED, MealPreferenceLevel.LIKED, MealPreferenceLevel.DISLIKED).forEach { level ->
                        val items = mealPrefs.filter { it.level == level }
                        if (items.isNotEmpty()) {
                            val pal = if (level == MealPreferenceLevel.DISLIKED) lowPal else highPal
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("${level.label}（${items.size}）", style = MaterialTheme.typography.labelLarge, color = pal.content)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                    items.forEach { rec ->
                                        Surface(
                                            onClick = { onOpenEntry(rec.mealEntryId) },
                                            shape = RoundedCornerShape(100.dp),
                                            color = pal.container,
                                            contentColor = pal.content,
                                            border = BorderStroke(1.dp, pal.bar.copy(alpha = 0.35f)),
                                        ) {
                                            Text(rec.mealName, Modifier.padding(horizontal = 11.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
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
}

@Composable
private fun WikiCharacterBirdAndEndingsSection(
    birds: List<BirdDialogueRecord>,
    endings: List<WikiLink>,
    onOpenEntry: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (birds.isNotEmpty()) {
            var expanded by rememberSaveable(birds.first().entryId) { mutableStateOf(false) }
            val shown = if (expanded) birds else birds.take(4)
            EditorialCard(modifier = Modifier.fillMaxWidth().testTag("wiki-character-birds")) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("逗鸟问答", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        EditorialBadge(text = "${birds.size} 条")
                    }
                    shown.forEach { b ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(b.translatedPrompt, style = MaterialTheme.typography.bodyMedium)
                                EditorialBadge(
                                    text = "推荐反应：${b.recommendedReaction}",
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                    }
                    if (birds.size > 4) {
                        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(if (expanded) "收起逗鸟问答" else "展开全部 ${birds.size} 条问答")
                        }
                    }
                }
            }
        }
        if (endings.isNotEmpty()) {
            EditorialCard(modifier = Modifier.fillMaxWidth().testTag("wiki-character-endings")) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("角色结局", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        EditorialBadge(text = "${endings.size} 篇")
                    }
                    endings.forEach { link ->
                        OutlinedButton(
                            onClick = { onOpenEntry(link.targetId) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Text(link.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("阅读 ›", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WikiDetailSectionHeading(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.semantics { heading() })
}

private val WIKI_HEADER_FACT_LABELS = setOf(
    "性别", "初始兵种", "所属势力", "类型", "类别", "职业", "职业级别", "武器类型", "稀有度", "种类", "任务类型", "分类",
)
private val WIKI_STAT_NAMES = setOf("等级", "HP", "力量", "魔力", "速度", "技巧", "防守", "魔防", "幸运", "魅力")

private fun isStartingStatField(field: WikiField): Boolean =
    field.label.startsWith("初始") && field.label.removePrefix("初始") in WIKI_STAT_NAMES

private fun isNarrativeWikiField(field: WikiField): Boolean =
    field.value.length > 30 || '\n' in field.value ||
        listOf("条件", "效果", "说明", "加入", "章节", "获取", "来源").any { field.label.contains(it) }

@Composable
internal fun WikiImage(path: String?, description: String, modifier: Modifier, crop: Boolean) {
    val context = LocalContext.current
    val imageState by produceState(WikiImageLoadState(path, null, path == null), path) {
        val bitmap = withContext(Dispatchers.IO) {
            if (path == null) null
            else try {
                context.assets.open("wiki/$path").use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
        value = WikiImageLoadState(path, bitmap, loaded = true)
    }
    val currentState = imageState.takeIf { it.path == path }
    val bitmap = currentState?.bitmap
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = description, modifier = modifier,
            contentScale = if (crop) ContentScale.Crop else ContentScale.Fit)
    } else {
        val fallbackLabel = when {
            path == null -> "暂无图片"
            currentState?.loaded == true -> "图片暂不可用"
            else -> "正在加载图片"
        }
        Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text(fallbackLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
