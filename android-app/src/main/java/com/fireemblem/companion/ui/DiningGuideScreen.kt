package com.fireemblem.companion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Search
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fireemblem.companion.content.CharacterMealGuideData
import com.fireemblem.companion.content.DiningTrioRecord
import com.fireemblem.companion.content.MealPreferenceLevel
import com.fireemblem.companion.content.MealPreferenceRecord
import com.fireemblem.companion.content.MealRecommendation
import com.fireemblem.companion.content.MealRecommendationProjector
import com.fireemblem.companion.content.WikiEntry
import com.fireemblem.companion.content.WikiEntryPage
import com.fireemblem.companion.content.WikiPageRequest
import com.fireemblem.companion.content.WikiRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class DiningMode(val label: String, val categoryId: String) {
    CHARACTER("按角色", "characters"),
    MEAL("按菜品", "meals"),
    TRIO("三人搭配", "characters"),
}

private sealed interface DiningDetail {
    data class Character(val entry: WikiEntry, val data: CharacterMealGuideData) : DiningDetail
    data class Meal(val entry: WikiEntry, val preferences: List<MealPreferenceRecord>) : DiningDetail
}

private data class DiningRecommendationData(
    val members: List<WikiEntry>,
    val sources: List<CharacterMealGuideData>,
    val recommendations: List<MealRecommendation>,
    val trio: DiningTrioRecord?,
)

private sealed interface DiningLoadState {
    data object Loading : DiningLoadState
    data object Unavailable : DiningLoadState
    data class Ready(
        val page: WikiEntryPage? = null,
        val detail: DiningDetail? = null,
        val selectedMembers: List<WikiEntry> = emptyList(),
        val recommendation: DiningRecommendationData? = null,
    ) : DiningLoadState
    data class Failed(val message: String) : DiningLoadState
}

@Composable
internal fun DiningGuideScreen(repository: WikiRepository?, onBack: () -> Unit) {
    var modeId by rememberSaveable { mutableStateOf(DiningMode.CHARACTER.name) }
    val mode = DiningMode.entries.firstOrNull { it.name == modeId } ?: DiningMode.CHARACTER
    var query by rememberSaveable { mutableStateOf("") }
    var pageIndex by rememberSaveable(modeId, query) { mutableIntStateOf(0) }
    var selectedCharacterId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedMealId by rememberSaveable { mutableStateOf<String?>(null) }
    var returnCharacterId by rememberSaveable { mutableStateOf<String?>(null) }
    var returnMealId by rememberSaveable { mutableStateOf<String?>(null) }
    var returnModeId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedCharacter0 by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedCharacter1 by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedCharacter2 by rememberSaveable { mutableStateOf<String?>(null) }
    var activeSlot by rememberSaveable { mutableIntStateOf(0) }
    var rosterExpanded by rememberSaveable { mutableStateOf(false) }
    var retryToken by rememberSaveable { mutableIntStateOf(0) }
    val selectedCharacterIds = listOf(selectedCharacter0, selectedCharacter1, selectedCharacter2)
    val requestedCharacterId = selectedCharacterId
    val requestedMealId = selectedMealId
    val requestedCharacters = selectedCharacterIds.filterNotNull()

    fun updateSelectedCharacters(ids: List<String?>) {
        selectedCharacter0 = ids.getOrNull(0)
        selectedCharacter1 = ids.getOrNull(1)
        selectedCharacter2 = ids.getOrNull(2)
    }

    fun backFromDetail() {
        when {
            selectedMealId != null && returnCharacterId != null -> {
                selectedMealId = null
                selectedCharacterId = returnCharacterId
                returnCharacterId = null
                modeId = returnModeId ?: DiningMode.CHARACTER.name
                returnModeId = null
            }
            selectedCharacterId != null && returnMealId != null -> {
                selectedCharacterId = null
                selectedMealId = returnMealId
                returnMealId = null
                modeId = returnModeId ?: DiningMode.MEAL.name
                returnModeId = null
            }
            selectedMealId != null -> selectedMealId = null
            selectedCharacterId != null -> selectedCharacterId = null
            else -> onBack()
        }
    }

    val state by produceState<DiningLoadState>(
        initialValue = DiningLoadState.Loading,
        repository,
        mode,
        query,
        pageIndex,
        requestedCharacterId,
        requestedMealId,
        requestedCharacters,
        rosterExpanded,
        retryToken,
    ) {
        value = DiningLoadState.Loading
        if (repository == null) {
            value = DiningLoadState.Failed("离线资料暂不可用，请返回后重试。")
        } else {
            try {
                if (repository.catalog.categories.none { it.id == "meals" }) {
                    value = DiningLoadState.Unavailable
                } else if (requestedMealId != null) {
                    value = withContext(Dispatchers.IO) {
                        val entry = repository.entry(requestedMealId)
                            ?: return@withContext DiningLoadState.Failed("这道料理已不在当前资料版本中，请返回重新选择。")
                        val preferences = repository.mealPreferencesForMeal(requestedMealId)
                            ?: return@withContext DiningLoadState.Unavailable
                        DiningLoadState.Ready(detail = DiningDetail.Meal(entry, preferences))
                    }
                } else if (requestedCharacterId != null) {
                    value = withContext(Dispatchers.IO) {
                        val entry = repository.entry(requestedCharacterId)
                            ?: return@withContext DiningLoadState.Failed("该角色已不在当前资料版本中，请返回重新选择。")
                        val data = repository.mealPreferencesForCharacter(requestedCharacterId)
                            ?: return@withContext DiningLoadState.Unavailable
                        DiningLoadState.Ready(detail = DiningDetail.Character(entry, data))
                    }
                } else if (mode == DiningMode.TRIO && requestedCharacters.size == 3) {
                    val loaded = withContext(Dispatchers.IO) {
                        val members = requestedCharacters.mapNotNull(repository::entry)
                        if (members.size != 3) return@withContext null
                        val sources = requestedCharacters.map { id ->
                            repository.mealPreferencesForCharacter(id) ?: return@withContext null
                        }
                        val mealEntries = mutableListOf<WikiEntry>()
                        var offset = 0
                        var total = 0
                        do {
                            val page = repository.page(
                                WikiPageRequest(categoryId = "meals", offset = offset, limit = WikiPageRequest.MAX_PAGE_SIZE),
                            )
                            total = page.totalMatches
                            if (page.entries.isEmpty()) break
                            mealEntries.addAll(page.entries)
                            offset += page.entries.size
                        } while (offset < total)
                        if (mealEntries.size != total) return@withContext null
                        val preferencesByCharacter = requestedCharacters.zip(sources)
                            .associate { (id, source) -> id to source.preferences }
                        Triple(members, sources, mealEntries.toList() to repository.diningTrio(requestedCharacters)) to
                            preferencesByCharacter
                    }
                    if (loaded == null) {
                        value = DiningLoadState.Unavailable
                    } else {
                        val (payload, preferencesByCharacter) = loaded
                        val (members, sources, mealData) = payload
                        val recommendations = withContext(Dispatchers.Default) {
                            MealRecommendationProjector.project(requestedCharacters, mealData.first, preferencesByCharacter)
                        }
                        val page = if (rosterExpanded) {
                            withContext(Dispatchers.IO) {
                                repository.page(
                                    WikiPageRequest(
                                        categoryId = "characters",
                                        query = query.trim(),
                                        offset = pageIndex.coerceAtLeast(0) * WikiPageRequest.PAGE_SIZE,
                                        limit = WikiPageRequest.PAGE_SIZE,
                                    ),
                                )
                            }
                        } else {
                            null
                        }
                        value = DiningLoadState.Ready(
                            page = page,
                            selectedMembers = members,
                            recommendation = DiningRecommendationData(members, sources, recommendations, mealData.second),
                        )
                    }
                } else {
                    value = withContext(Dispatchers.IO) {
                        val page = repository.page(
                                WikiPageRequest(
                                    categoryId = mode.categoryId,
                                    query = query.trim(),
                                    offset = pageIndex.coerceAtLeast(0) * WikiPageRequest.PAGE_SIZE,
                                    limit = WikiPageRequest.PAGE_SIZE,
                                ),
                            )
                        val selectedMembers = if (mode == DiningMode.TRIO) {
                            requestedCharacters.mapNotNull(repository::entry)
                        } else {
                            emptyList()
                        }
                        DiningLoadState.Ready(page = page, selectedMembers = selectedMembers)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                value = DiningLoadState.Failed("用餐资料读取失败；请重试。")
            }
        }
    }

    BackHandler { backFromDetail() }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("dining-guide-screen")) {
        val ready = state is DiningLoadState.Ready
        val activeIdentity = when {
            requestedMealId != null -> "meal:$requestedMealId"
            requestedCharacterId != null -> "character:$requestedCharacterId"
            mode == DiningMode.TRIO && requestedCharacters.size == 3 -> "trio:${requestedCharacters.joinToString()}"
            else -> "${mode.name}:$pageIndex"
        }
        val gridState = rememberReadingGridState("guide/dining/${mode.name}/${query.trim()}/$activeIdentity", ready)
        val horizontalPadding = if (maxWidth < 600.dp) 16.dp else 24.dp
        val page = (state as? DiningLoadState.Ready)?.page
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = if (maxWidth < 600.dp) 148.dp else 190.dp),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = horizontalPadding, end = horizontalPadding, top = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                DiningHeader(
                    detail = (state as? DiningLoadState.Ready)?.detail,
                    mode = mode,
                    returnCharacterId = returnCharacterId,
                    returnMealId = returnMealId,
                    onBack = ::backFromDetail,
                )
            }

            when (val current = state) {
                DiningLoadState.Loading -> item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator()
                        Text("正在读取离线料理资料…", Modifier.padding(start = 14.dp))
                    }
                }
                DiningLoadState.Unavailable -> item(span = { GridItemSpan(maxLineSpan) }) {
                    DiningMessage(
                        "当前资料版本尚未包含料理攻略",
                        "请更新应用内离线资料后再试。",
                        Icons.Outlined.ErrorOutline,
                    )
                }
                is DiningLoadState.Failed -> item(span = { GridItemSpan(maxLineSpan) }) {
                    DiningMessage(current.message, "料理资料没有加载成功。", Icons.Outlined.ErrorOutline) { retryToken++ }
                }
                is DiningLoadState.Ready -> {
                    when (val detail = current.detail) {
                        is DiningDetail.Character -> {
                            item(span = { GridItemSpan(maxLineSpan) }) { CharacterMealSummary(detail.entry, detail.data) }
                            if (!detail.data.hasSourceData) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    DiningMessage(
                                        "暂未收录该角色的料理偏好",
                                        "没有来源记录时，不会把未列出的料理当作普通或不喜欢。",
                                        Icons.Outlined.Person,
                                    )
                                }
                            } else if (detail.data.preferences.isEmpty()) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    DiningMessage(
                                        "来源记录没有列出具体料理",
                                        "未列出的料理不代表普通或不喜欢。",
                                        Icons.Outlined.Restaurant,
                                    )
                                }
                            } else {
                                MealPreferenceLevel.entries.forEach { level ->
                                    val meals = detail.data.preferences.filter { it.level == level }
                                    if (meals.isNotEmpty()) {
                                        item(span = { GridItemSpan(maxLineSpan) }) { MealLevelHeading(level, meals.size) }
                                        items(meals, key = { "${it.characterId}:${it.mealEntryId}" }) { preference ->
                                            MealPreferenceCard(preference, showingCharacter = false) {
                                                returnCharacterId = detail.entry.id
                                                returnMealId = null
                                                returnModeId = modeId
                                                selectedCharacterId = null
                                                selectedMealId = preference.mealEntryId
                                                modeId = DiningMode.MEAL.name
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        is DiningDetail.Meal -> {
                            item(span = { GridItemSpan(maxLineSpan) }) { MealSummary(detail.entry) }
                            if (detail.preferences.isEmpty()) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    DiningMessage(
                                        "没有查到明确标注的角色偏好",
                                        "资料未列出不代表普通或不喜欢。",
                                        Icons.Outlined.Person,
                                    )
                                }
                            } else {
                                MealPreferenceLevel.entries.forEach { level ->
                                    val characters = detail.preferences.filter { it.level == level }
                                    if (characters.isNotEmpty()) {
                                        item(span = { GridItemSpan(maxLineSpan) }) { MealLevelHeading(level, characters.size) }
                                        items(characters, key = { "${it.mealEntryId}:${it.characterId}" }) { preference ->
                                            MealPreferenceCard(preference, showingCharacter = true) {
                                                returnMealId = detail.entry.id
                                                returnCharacterId = null
                                                returnModeId = modeId
                                                selectedMealId = null
                                                selectedCharacterId = preference.characterId
                                                modeId = DiningMode.CHARACTER.name
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        null -> {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                DiningModeControls(
                                    mode = mode,
                                    query = query,
                                    onModeChange = { newMode ->
                                        modeId = newMode.name
                                        query = ""
                                        pageIndex = 0
                                        selectedCharacterId = null
                                        selectedMealId = null
                                        returnCharacterId = null
                                        returnMealId = null
                                        returnModeId = null
                                        rosterExpanded = false
                                    },
                                    onQueryChange = {
                                        query = it
                                        pageIndex = 0
                                    },
                                )
                            }
                            if (mode == DiningMode.TRIO) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    DiningTrioPicker(
                                        selectedIds = selectedCharacterIds,
                                        activeSlot = activeSlot,
                                        rosterExpanded = rosterExpanded,
                                        members = current.recommendation?.members ?: current.selectedMembers,
                                        onSelectSlot = { index ->
                                            activeSlot = index
                                            rosterExpanded = requestedCharacters.size == 3
                                        },
                                        onClearSlot = { index ->
                                            val ids = selectedCharacterIds.toMutableList()
                                            ids[index] = null
                                            updateSelectedCharacters(ids)
                                            activeSlot = index
                                            rosterExpanded = true
                                        },
                                        onToggleRoster = { rosterExpanded = !rosterExpanded },
                                        onFinishChanging = { rosterExpanded = false },
                                    )
                                }
                                if (requestedCharacters.size < 3 || rosterExpanded) {
                                    item(span = { GridItemSpan(maxLineSpan) }) {
                                        DiningSearch(query, "搜索角色", onQueryChange = {
                                            query = it
                                            pageIndex = 0
                                        })
                                    }
                                    when {
                                        page == null -> item(span = { GridItemSpan(maxLineSpan) }) {
                                            DiningMessage("没有找到匹配资料", "试试角色名。", Icons.Outlined.Search)
                                        }
                                        page.entries.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                                            DiningMessage("没有找到匹配的角色", "换一个关键词再试试。", Icons.Outlined.Search)
                                        }
                                        else -> {
                                            items(page.entries, key = WikiEntry::id) { entry ->
                                                val selected = requestedCharacters.contains(entry.id)
                                                DiningEntityCard(entry, isCharacter = true, selected = selected, picker = true, onClick = {
                                                    val ids = selectedCharacterIds.toMutableList()
                                                    val existingSlot = ids.indexOf(entry.id)
                                                    if (existingSlot >= 0) {
                                                        activeSlot = existingSlot
                                                    } else {
                                                        ids[activeSlot] = entry.id
                                                        updateSelectedCharacters(ids)
                                                        val nextSlot = ids.indexOfFirst { it == null }
                                                        if (nextSlot >= 0) activeSlot = nextSlot else rosterExpanded = false
                                                    }
                                                })
                                            }
                                            item(span = { GridItemSpan(maxLineSpan) }) {
                                                DiningPageControls(page, pageIndex) { pageIndex = it }
                                            }
                                        }
                                    }
                                }
                                val recommendation = current.recommendation
                                if (recommendation != null) {
                                    item(span = { GridItemSpan(maxLineSpan) }) {
                                        DiningTrioSummary(recommendation.trio)
                                    }
                                    item(span = { GridItemSpan(maxLineSpan) }) {
                                        DiningRecommendationHeading(recommendation.sources)
                                    }
                                    items(recommendation.recommendations, key = { it.meal.id }) { item ->
                                        DiningRecommendationCard(item, recommendation.members) {
                                            returnCharacterId = null
                                            returnMealId = null
                                            returnModeId = null
                                            selectedMealId = item.meal.id
                                        }
                                    }
                                } else if (requestedCharacters.size < 3) {
                                    item(span = { GridItemSpan(maxLineSpan) }) {
                                        DiningMessage(
                                            "先选三位不同角色",
                                            "每个位置都可以单独更换；推荐会保留未收录状态。",
                                            Icons.Outlined.Groups,
                                        )
                                    }
                                }
                            } else {
                                if (page == null) {
                                    item(span = { GridItemSpan(maxLineSpan) }) {
                                        DiningMessage("没有找到匹配资料", "试试角色名或菜品名。", Icons.Outlined.Search)
                                    }
                                } else if (page.entries.isEmpty()) {
                                    item(span = { GridItemSpan(maxLineSpan) }) {
                                        DiningMessage(
                                            if (mode == DiningMode.CHARACTER) "没有找到匹配的角色" else "没有找到匹配的菜品",
                                            "换一个关键词再试试。",
                                            Icons.Outlined.Search,
                                        )
                                    }
                                } else {
                                    items(page.entries, key = WikiEntry::id) { entry ->
                                        DiningEntityCard(entry, isCharacter = mode == DiningMode.CHARACTER) {
                                            if (mode == DiningMode.CHARACTER) selectedCharacterId = entry.id
                                            else selectedMealId = entry.id
                                        }
                                    }
                                    item(span = { GridItemSpan(maxLineSpan) }) {
                                        DiningPageControls(page, pageIndex) { pageIndex = it }
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
private fun DiningHeader(
    detail: DiningDetail?,
    mode: DiningMode,
    returnCharacterId: String?,
    returnMealId: String?,
    onBack: () -> Unit,
) {
    val backLabel = when {
        detail == null -> "返回攻略"
        detail is DiningDetail.Meal && returnCharacterId != null -> "返回角色偏好"
        detail is DiningDetail.Meal && mode == DiningMode.TRIO -> "返回三人推荐"
        detail is DiningDetail.Meal -> "返回菜品查询"
        detail is DiningDetail.Character && returnMealId != null -> "返回料理资料"
        else -> "返回角色查询"
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp).testTag("dining-guide-back")) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
            Text(backLabel, Modifier.padding(start = 8.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "用餐攻略",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            Text("按角色查偏好、按菜品反查，或组合三位角色挑选料理。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DiningModeControls(
    mode: DiningMode,
    query: String,
    onModeChange: (DiningMode) -> Unit,
    onQueryChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            DiningMode.entries.forEach { option ->
                FilterChip(
                    selected = mode == option,
                    onClick = { if (mode != option) onModeChange(option) },
                    label = { Text(option.label) },
                    leadingIcon = {
                        Icon(
                            if (option == DiningMode.TRIO) Icons.Outlined.Groups
                            else if (option == DiningMode.CHARACTER) Icons.Outlined.Person
                            else Icons.Outlined.Restaurant,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    modifier = Modifier.heightIn(min = 48.dp).testTag(
                        when (option) {
                            DiningMode.CHARACTER -> "dining-mode-character"
                            DiningMode.MEAL -> "dining-mode-meal"
                            DiningMode.TRIO -> "dining-mode-trio"
                        },
                    ),
                )
            }
        }
        if (mode != DiningMode.TRIO) {
            DiningSearch(query, if (mode == DiningMode.CHARACTER) "搜索角色" else "搜索菜品", onQueryChange)
        }
    }
}

@Composable
private fun DiningSearch(query: String, label: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth().testTag("dining-search"),
        placeholder = { Text(label) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        singleLine = true,
        label = { Text(label) },
    )
}

@Composable
private fun DiningEntityCard(
    entry: WikiEntry,
    isCharacter: Boolean,
    selected: Boolean = false,
    picker: Boolean = false,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp)
            .testTag(if (picker) "dining-picker-${entry.id}" else "dining-entry-${entry.id}"),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        ),
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WikiImage(entry.imageAsset, entry.title, Modifier.size(60.dp), crop = isCharacter)
            Text(entry.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            when {
                selected -> Text("已加入搭配", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer)
                entry.subtitle.isNotBlank() -> Text(entry.subtitle, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun DiningTrioPicker(
    selectedIds: List<String?>,
    activeSlot: Int,
    rosterExpanded: Boolean,
    members: List<WikiEntry>,
    onSelectSlot: (Int) -> Unit,
    onClearSlot: (Int) -> Unit,
    onToggleRoster: () -> Unit,
    onFinishChanging: () -> Unit,
) {
    val membersById = members.associateBy(WikiEntry::id)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("选择三位角色", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            selectedIds.forEachIndexed { index, id ->
                DiningCharacterSlot(
                    index = index,
                    entry = id?.let(membersById::get),
                    isActive = index == activeSlot,
                    onClick = { onSelectSlot(index) },
                    onClear = { onClearSlot(index) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        when {
            selectedIds.all { it != null } && !rosterExpanded -> {
                OutlinedButton(onClick = onToggleRoster, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Outlined.Person, contentDescription = null)
                    Text("更换角色", Modifier.padding(start = 8.dp))
                }
            }
            selectedIds.all { it != null } && rosterExpanded -> {
                OutlinedButton(onClick = onFinishChanging, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Outlined.CheckCircle, contentDescription = null)
                    Text("完成更换", Modifier.padding(start = 8.dp))
                }
            }
            else -> Text(
                "点选一个位置，再从下方角色卡片中选择；已选角色不会重复。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DiningCharacterSlot(
    index: Int,
    entry: WikiEntry?,
    isActive: Boolean,
    onClick: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.heightIn(min = 132.dp).testTag("dining-slot-$index"),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(
            if (isActive) 2.dp else 1.dp,
            if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Box(Modifier.fillMaxWidth().heightIn(min = 40.dp), contentAlignment = Alignment.CenterEnd) {
                Text("${index + 1}", Modifier.align(Alignment.CenterStart).padding(start = 6.dp),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (entry != null) {
                    IconButton(onClick = onClear, modifier = Modifier.size(40.dp).testTag("dining-clear-slot-$index")) {
                        Icon(Icons.Outlined.Close, contentDescription = "移除第${index + 1}位角色")
                    }
                }
            }
            if (entry == null) {
                Icon(Icons.Outlined.Person, contentDescription = null, modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary)
                Text("选择角色", style = MaterialTheme.typography.labelMedium, maxLines = 1)
            } else {
                WikiImage(entry.imageAsset, entry.title, Modifier.size(48.dp), crop = true)
                Text(entry.title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun CharacterMealSummary(entry: WikiEntry, data: CharacterMealGuideData) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                WikiImage(entry.imageAsset, entry.title, Modifier.size(68.dp), crop = true)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(entry.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("${data.preferences.size} 项明确记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            data.sourceRowNumber?.let { DiningProvenance(data.sourceSheetName.orEmpty(), it) }
        }
    }
}

@Composable
private fun MealSummary(entry: WikiEntry) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                WikiImage(entry.imageAsset, entry.title, Modifier.size(76.dp), crop = false)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(entry.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("角色用餐偏好", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            DiningProvenance(entry.sourceSheetName, entry.sourceRowNumber)
        }
    }
}

@Composable
private fun MealPreferenceCard(preference: MealPreferenceRecord, showingCharacter: Boolean, onClick: () -> Unit) {
    val title = if (showingCharacter) preference.characterName else preference.mealName
    val image = if (showingCharacter) preference.characterImageAsset else preference.mealImageAsset
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 148.dp)
            .testTag("dining-preference-${if (showingCharacter) preference.characterId else preference.mealEntryId}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WikiImage(image, title, Modifier.size(56.dp), crop = showingCharacter)
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            MealPreferenceBadge(preference.level)
            Text("来源第${preference.sourceRowNumber}行", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MealLevelHeading(level: MealPreferenceLevel, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MealPreferenceBadge(level)
        Text("$count 项已记录", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MealPreferenceBadge(level: MealPreferenceLevel) {
    val (container, content) = when (level) {
        MealPreferenceLevel.VERY_LIKED -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        MealPreferenceLevel.LIKED -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        MealPreferenceLevel.ORDINARY -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        MealPreferenceLevel.DISLIKED -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small) {
        Text(level.label, Modifier.padding(horizontal = 9.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun MealNotRecordedBadge() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text("未记录", Modifier.padding(horizontal = 8.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun DiningTrioSummary(trio: DiningTrioRecord?) {
    if (trio == null) {
        DiningMessage(
            "资料暂未收录这组三人组合",
            "这不代表游戏中不存在该组合或支援关系。",
            Icons.Outlined.Groups,
        )
        return
    }
    val relationships = listOf(
        Triple("${trio.first.name} · ${trio.second.name}", "关系1-2", trio.relationship12),
        Triple("${trio.first.name} · ${trio.third.name}", "关系1-3", trio.relationship13),
        Triple("${trio.second.name} · ${trio.third.name}", "关系2-3", trio.relationship23),
    )
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("资料记录的三人组合", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onTertiaryContainer)
            relationships.forEach { (pair, label, grade) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(pair, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(label, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f))
                    }
                    Text(grade ?: "未记录", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.18f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("组合最低等级", color = MaterialTheme.colorScheme.onTertiaryContainer)
                Text(trio.minimumLevel ?: "未记录", fontWeight = FontWeight.Bold)
            }
            DiningProvenance(trio.sourceSheetName, trio.sourceRowNumber)
        }
    }
}

@Composable
private fun DiningRecommendationHeading(sources: List<CharacterMealGuideData>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("共同偏好料理", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() })
        Text("优先显示更多人喜欢的菜品；这是便于挑选的排序，不是游戏内支援计算。",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (sources.any { !it.hasSourceData }) {
            Text("所选角色中有偏好资料未收录者；对应项目会标为“未记录”。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DiningRecommendationCard(
    recommendation: MealRecommendation,
    members: List<WikiEntry>,
    onClick: () -> Unit,
) {
    val meal = recommendation.meal
    val unknownCount = recommendation.preferences.count { it == null }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag("dining-recommendation-${meal.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                WikiImage(meal.imageAsset, meal.title, Modifier.size(68.dp), crop = false)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(meal.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "${recommendation.likedCount} 人喜欢 · ${recommendation.ordinaryCount} 人普通 · " +
                            "${recommendation.dislikedCount} 人不喜欢 · $unknownCount 人未记录",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                recommendation.preferences.forEachIndexed { index, level ->
                    val member = members.getOrNull(index)
                    Column(
                        Modifier.weight(1f).padding(vertical = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        WikiImage(member?.imageAsset, member?.title ?: "角色", Modifier.size(38.dp), crop = true)
                        Text(member?.title.orEmpty(), style = MaterialTheme.typography.labelSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (level == null) MealNotRecordedBadge() else MealPreferenceBadge(level)
                    }
                }
            }
            Text("查看这道料理的角色偏好", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.End))
        }
    }
}

@Composable
private fun DiningProvenance(sheetName: String, rowNumber: Int) {
    Text(
        "资料来源：$sheetName · 第${rowNumber}行",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DiningPageControls(page: WikiEntryPage, pageIndex: Int, onPageChange: (Int) -> Unit) {
    val pageCount = (page.totalMatches + page.limit - 1) / page.limit
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("第${pageIndex + 1} / ${pageCount.coerceAtLeast(1)}页 · 共${page.totalMatches}项",
            Modifier.align(Alignment.CenterHorizontally), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { onPageChange((pageIndex - 1).coerceAtLeast(0)) },
                enabled = page.hasPrevious,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) { Text("上一页") }
            OutlinedButton(
                onClick = { onPageChange(pageIndex + 1) },
                enabled = page.hasNext,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) { Text("下一页") }
        }
    }
}

@Composable
private fun DiningMessage(
    title: String,
    message: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onRetry: (() -> Unit)? = null,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(
            Modifier.fillMaxWidth().padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onRetry != null) Button(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("重试") }
        }
    }
}
