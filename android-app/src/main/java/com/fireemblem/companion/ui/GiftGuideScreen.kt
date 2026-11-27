package com.fireemblem.companion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.fireemblem.companion.content.CharacterGiftGuideData
import com.fireemblem.companion.content.GiftPreferenceLevel
import com.fireemblem.companion.content.GiftPreferenceRecord
import com.fireemblem.companion.content.WikiEntry
import com.fireemblem.companion.content.WikiEntryPage
import com.fireemblem.companion.content.WikiPageRequest
import com.fireemblem.companion.content.WikiRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class GiftLookupMode(val label: String, val categoryId: String) {
    CHARACTER("按角色", "characters"),
    GIFT("按礼物", "gifts"),
}

private sealed interface GiftGuideDetail {
    data class Character(val entry: WikiEntry, val data: CharacterGiftGuideData) : GiftGuideDetail
    data class Gift(val entry: WikiEntry, val preferences: List<GiftPreferenceRecord>) : GiftGuideDetail
}

private sealed interface GiftGuideLoadState {
    data object Loading : GiftGuideLoadState
    data object Unavailable : GiftGuideLoadState
    data class Ready(val page: WikiEntryPage? = null, val detail: GiftGuideDetail? = null) : GiftGuideLoadState
    data class Failed(val message: String) : GiftGuideLoadState
}

@Composable
internal fun GiftGuideScreen(repository: WikiRepository?, onBack: () -> Unit) {
    var modeId by rememberSaveable { mutableStateOf(GiftLookupMode.CHARACTER.name) }
    val mode = GiftLookupMode.entries.firstOrNull { it.name == modeId } ?: GiftLookupMode.CHARACTER
    var query by rememberSaveable { mutableStateOf("") }
    var pageIndex by rememberSaveable(modeId, query) { mutableIntStateOf(0) }
    var selectedCharacterId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedGiftId by rememberSaveable { mutableStateOf<String?>(null) }
    var detailReturnCharacterId by rememberSaveable { mutableStateOf<String?>(null) }
    var detailReturnGiftId by rememberSaveable { mutableStateOf<String?>(null) }
    var retryToken by rememberSaveable { mutableIntStateOf(0) }
    val requestedCharacterId = selectedCharacterId
    val requestedGiftId = selectedGiftId

    fun backFromDetail() {
        when {
            selectedGiftId != null && detailReturnCharacterId != null -> {
                selectedGiftId = null
                selectedCharacterId = detailReturnCharacterId
                detailReturnCharacterId = null
            }
            selectedCharacterId != null && detailReturnGiftId != null -> {
                selectedCharacterId = null
                selectedGiftId = detailReturnGiftId
                detailReturnGiftId = null
                modeId = GiftLookupMode.GIFT.name
            }
            selectedGiftId != null -> selectedGiftId = null
            selectedCharacterId != null -> selectedCharacterId = null
            else -> onBack()
        }
    }

    val state by produceState<GiftGuideLoadState>(
        initialValue = GiftGuideLoadState.Loading,
        repository,
        mode,
        query,
        pageIndex,
        requestedCharacterId,
        requestedGiftId,
        retryToken,
    ) {
        value = GiftGuideLoadState.Loading
        if (repository == null) {
            value = GiftGuideLoadState.Failed("离线资料暂不可用，请返回后重试。")
        } else {
            try {
                value = withContext(Dispatchers.IO) {
                    if (repository.catalog.categories.none { it.id == "gifts" }) {
                        GiftGuideLoadState.Unavailable
                    } else if (requestedGiftId != null) {
                        val entry = repository.entry(requestedGiftId)
                            ?: return@withContext GiftGuideLoadState.Failed("这件礼物已不在当前资料版本中，请返回重新选择。")
                        val preferences = repository.giftPreferencesForGift(requestedGiftId)
                            ?: return@withContext GiftGuideLoadState.Unavailable
                        GiftGuideLoadState.Ready(detail = GiftGuideDetail.Gift(entry, preferences))
                    } else if (requestedCharacterId != null) {
                        val entry = repository.entry(requestedCharacterId)
                            ?: return@withContext GiftGuideLoadState.Failed("该角色已不在当前资料版本中，请返回重新选择。")
                        val data = repository.giftPreferencesForCharacter(requestedCharacterId)
                            ?: return@withContext GiftGuideLoadState.Unavailable
                        GiftGuideLoadState.Ready(detail = GiftGuideDetail.Character(entry, data))
                    } else {
                        val page = repository.page(
                            WikiPageRequest(
                                categoryId = mode.categoryId,
                                query = query.trim(),
                                offset = pageIndex.coerceAtLeast(0) * WikiPageRequest.PAGE_SIZE,
                                limit = WikiPageRequest.PAGE_SIZE,
                            ),
                        )
                        GiftGuideLoadState.Ready(page = page)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                value = GiftGuideLoadState.Failed("礼物资料读取失败；请重试，已有记录未更改。")
            }
        }
    }

    BackHandler { backFromDetail() }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("gift-guide-screen")) {
        val ready = state is GiftGuideLoadState.Ready
        val gridState = rememberReadingGridState("guide/gifts/${mode.name}/${query.trim()}", ready)
        val horizontalPadding = if (maxWidth < 600.dp) 16.dp else 24.dp
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = if (maxWidth < 600.dp) 148.dp else 190.dp),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = horizontalPadding, end = horizontalPadding, top = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                GiftGuideHeader(
                    detail = (state as? GiftGuideLoadState.Ready)?.detail,
                    detailReturnCharacterId = detailReturnCharacterId,
                    detailReturnGiftId = detailReturnGiftId,
                    onBack = ::backFromDetail,
                )
            }

            when (val current = state) {
                GiftGuideLoadState.Loading -> item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator()
                        Text("正在读取离线礼物资料…", Modifier.padding(start = 14.dp))
                    }
                }
                GiftGuideLoadState.Unavailable -> item(span = { GridItemSpan(maxLineSpan) }) {
                    GiftGuideMessage(
                        title = "当前资料版本尚未包含礼物偏好",
                        message = "请更新应用内离线资料后再试。",
                        icon = Icons.Outlined.ErrorOutline,
                    )
                }
                is GiftGuideLoadState.Failed -> item(span = { GridItemSpan(maxLineSpan) }) {
                    GiftGuideMessage(current.message, "资料没有加载成功。", Icons.Outlined.ErrorOutline) {
                        retryToken++
                    }
                }
                is GiftGuideLoadState.Ready -> {
                    when (val detail = current.detail) {
                        is GiftGuideDetail.Character -> {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                CharacterGiftSummary(detail.entry, detail.data)
                            }
                            if (!detail.data.hasSourceData) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    GiftGuideMessage(
                                        "暂未收录该角色的礼物偏好",
                                        "没有来源记录时，不会把任何礼物解释为普通或讨厌。",
                                        Icons.Outlined.Person,
                                    )
                                }
                            } else if (detail.data.preferences.isEmpty()) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    GiftGuideMessage(
                                        "来源记录没有列出具体礼物",
                                        "类别偏好仅按资料原意展示；未列出的礼物不视为普通或讨厌。",
                                        Icons.Outlined.Inventory2,
                                    )
                                }
                            } else {
                                GiftPreferenceLevel.entries.forEach { level ->
                                    val gifts = detail.data.preferences.filter { it.level == level }
                                    if (gifts.isNotEmpty()) {
                                        item(span = { GridItemSpan(maxLineSpan) }) {
                                            GiftLevelHeading(level, gifts.size)
                                        }
                                        items(gifts, key = { "${it.characterId}:${it.giftEntryId}" }) { preference ->
                                            GiftPreferenceCard(preference, showingCharacter = false) {
                                                detailReturnCharacterId = detail.entry.id
                                                detailReturnGiftId = null
                                                selectedCharacterId = null
                                                selectedGiftId = preference.giftEntryId
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        is GiftGuideDetail.Gift -> {
                            item(span = { GridItemSpan(maxLineSpan) }) { GiftItemSummary(detail.entry) }
                            if (detail.preferences.isEmpty()) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    GiftGuideMessage(
                                        "没有查到明确标注的角色偏好",
                                        "未列出不代表普通或讨厌。",
                                        Icons.Outlined.Person,
                                    )
                                }
                            } else {
                                GiftPreferenceLevel.entries.forEach { level ->
                                    val characters = detail.preferences.filter { it.level == level }
                                    if (characters.isNotEmpty()) {
                                        item(span = { GridItemSpan(maxLineSpan) }) {
                                            GiftLevelHeading(level, characters.size)
                                        }
                                        items(characters, key = { "${it.giftEntryId}:${it.characterId}" }) { preference ->
                                            GiftPreferenceCard(preference, showingCharacter = true) {
                                                detailReturnGiftId = detail.entry.id
                                                detailReturnCharacterId = null
                                                selectedGiftId = null
                                                selectedCharacterId = preference.characterId
                                                modeId = GiftLookupMode.CHARACTER.name
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        null -> {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                GiftLookupControls(
                                    mode = mode,
                                    query = query,
                                    onModeChange = { newMode ->
                                        modeId = newMode.name
                                        query = ""
                                        pageIndex = 0
                                        selectedCharacterId = null
                                        selectedGiftId = null
                                        detailReturnCharacterId = null
                                        detailReturnGiftId = null
                                    },
                                    onQueryChange = { newQuery ->
                                        query = newQuery
                                        pageIndex = 0
                                    },
                                )
                            }
                            val page = current.page
                            if (page == null) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    GiftGuideMessage("没有找到匹配资料", "试试角色名或礼物名。", Icons.Outlined.Search)
                                }
                            } else if (page.entries.isEmpty()) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    GiftGuideMessage(
                                        if (mode == GiftLookupMode.CHARACTER) "没有找到匹配的角色" else "没有找到匹配的礼物",
                                        "换一个关键词再试试。",
                                        Icons.Outlined.Search,
                                    )
                                }
                            } else {
                                items(page.entries, key = WikiEntry::id) { entry ->
                                    GiftEntityCard(entry, mode) {
                                        if (mode == GiftLookupMode.CHARACTER) selectedCharacterId = entry.id
                                        else selectedGiftId = entry.id
                                    }
                                }
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    GiftPageControls(page, pageIndex, onPageChange = { pageIndex = it })
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
private fun GiftGuideHeader(
    detail: GiftGuideDetail?,
    detailReturnCharacterId: String?,
    detailReturnGiftId: String?,
    onBack: () -> Unit,
) {
    val backLabel = when {
        detail == null -> "返回攻略"
        detail is GiftGuideDetail.Gift && detailReturnCharacterId != null -> "返回角色偏好"
        detail is GiftGuideDetail.Gift -> "返回查询结果"
        detail is GiftGuideDetail.Character && detailReturnGiftId != null -> "返回礼物资料"
        else -> "返回查询结果"
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp).testTag("gift-guide-back")) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
            Text(backLabel, Modifier.padding(start = 8.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("礼物查询", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() })
            Text("按角色找合适的礼物，或按礼物查看已记录的偏好。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun GiftLookupControls(
    mode: GiftLookupMode,
    query: String,
    onModeChange: (GiftLookupMode) -> Unit,
    onQueryChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GiftLookupMode.entries.forEach { option ->
                FilterChip(
                    selected = mode == option,
                    onClick = { if (mode != option) onModeChange(option) },
                    label = { Text(option.label) },
                    leadingIcon = {
                        Icon(
                            if (option == GiftLookupMode.CHARACTER) Icons.Outlined.Person else Icons.Outlined.Inventory2,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    modifier = Modifier.heightIn(min = 48.dp).testTag(
                        if (option == GiftLookupMode.CHARACTER) "gift-mode-character" else "gift-mode-item",
                    ),
                )
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth().testTag("gift-search"),
            placeholder = { Text(if (mode == GiftLookupMode.CHARACTER) "搜索角色" else "搜索礼物") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            singleLine = true,
            label = { Text(if (mode == GiftLookupMode.CHARACTER) "角色名称" else "礼物名称") },
        )
    }
}

@Composable
private fun GiftEntityCard(entry: WikiEntry, mode: GiftLookupMode, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 138.dp).testTag("gift-entity-${entry.id}"),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WikiImage(entry.imageAsset, entry.title, Modifier.size(58.dp), crop = mode == GiftLookupMode.CHARACTER)
            Text(entry.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (entry.subtitle.isNotBlank()) {
                Text(entry.subtitle, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun CharacterGiftSummary(entry: WikiEntry, data: CharacterGiftGuideData) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                WikiImage(entry.imageAsset, entry.title, Modifier.size(68.dp), crop = true)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(entry.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("角色礼物偏好", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            data.categoryPreferenceText?.takeIf(String::isNotBlank)?.let { categoryText ->
                HorizontalDivider()
                Text("类别偏好", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(categoryText, color = MaterialTheme.colorScheme.onSurface)
            }
            if (data.hasSourceData) GiftProvenance(data.sourceSheetName.orEmpty(), data.sourceRowNumber)
        }
    }
}

@Composable
private fun GiftItemSummary(entry: WikiEntry) {
    val description = entry.fields.firstOrNull { it.label == "简介" }?.value
    val details = entry.fields.filter { it.label != "简介" && it.value.isNotBlank() }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                WikiImage(entry.imageAsset, entry.title, Modifier.size(64.dp), crop = false)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(entry.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    entry.subtitle.takeIf(String::isNotBlank)?.let {
                        Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            description?.takeIf(String::isNotBlank)?.let { Text(it, color = MaterialTheme.colorScheme.onSurface) }
            details.forEach { field ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(field.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(field.value, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            GiftProvenance(entry.sourceSheetName, entry.sourceRowNumber)
        }
    }
}

@Composable
private fun GiftPreferenceCard(preference: GiftPreferenceRecord, showingCharacter: Boolean, onClick: () -> Unit) {
    val title = if (showingCharacter) preference.characterName else preference.giftName
    val image = if (showingCharacter) preference.characterImageAsset else preference.giftImageAsset
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 126.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WikiImage(image, title, Modifier.size(52.dp), crop = showingCharacter)
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            GiftLevelBadge(preference.level)
        }
    }
}

@Composable
private fun GiftLevelHeading(level: GiftPreferenceLevel, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        GiftLevelBadge(level)
        Text("$count 项已记录", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun GiftLevelBadge(level: GiftPreferenceLevel) {
    val (container, content) = when (level) {
        GiftPreferenceLevel.VERY_LIKED -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        GiftPreferenceLevel.LIKED -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        GiftPreferenceLevel.SOMEWHAT_LIKED -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        GiftPreferenceLevel.DISLIKED -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small) {
        Text(level.label, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun GiftProvenance(sheetName: String, rowNumber: Int?) {
    Text(
        "资料来源：$sheetName${rowNumber?.let { " · 第${it}行" }.orEmpty()}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun GiftPageControls(page: WikiEntryPage, pageIndex: Int, onPageChange: (Int) -> Unit) {
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
private fun GiftGuideMessage(
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
