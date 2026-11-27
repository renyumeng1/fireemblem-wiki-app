package com.fireemblem.companion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import com.fireemblem.companion.content.BirdDialoguePage
import com.fireemblem.companion.content.BirdDialogueRecord
import com.fireemblem.companion.content.BirdDialogueRequest
import com.fireemblem.companion.content.BirdDialogueReviewFilter
import com.fireemblem.companion.content.WikiRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface BirdDialogueLoadState {
    data object Loading : BirdDialogueLoadState
    data object Unavailable : BirdDialogueLoadState
    data class Ready(val page: BirdDialoguePage, val detail: BirdDialogueRecord?) : BirdDialogueLoadState
    data class Failed(val message: String) : BirdDialogueLoadState
}

@Composable
internal fun BirdDialoguesGuideScreen(repository: WikiRepository?, onBack: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var characterGroup by rememberSaveable { mutableStateOf<String?>(null) }
    var reviewFilterId by rememberSaveable { mutableStateOf(BirdDialogueReviewFilter.ALL.name) }
    val reviewFilter = BirdDialogueReviewFilter.entries.firstOrNull { it.name == reviewFilterId }
        ?: BirdDialogueReviewFilter.ALL
    var pageIndex by rememberSaveable(query, characterGroup, reviewFilterId) { mutableIntStateOf(0) }
    var selectedDialogueId by rememberSaveable { mutableStateOf<String?>(null) }
    var retryToken by rememberSaveable { mutableIntStateOf(0) }
    val requestedDialogueId = selectedDialogueId
    val request = BirdDialogueRequest(
        query = query.trim(),
        characterGroup = characterGroup,
        reviewFilter = reviewFilter,
        offset = pageIndex * BirdDialogueRequest.PAGE_SIZE,
        limit = BirdDialogueRequest.PAGE_SIZE,
    )

    val state by produceState<BirdDialogueLoadState>(
        initialValue = BirdDialogueLoadState.Loading,
        repository,
        request,
        requestedDialogueId,
        retryToken,
    ) {
        value = BirdDialogueLoadState.Loading
        if (repository == null) {
            value = BirdDialogueLoadState.Failed("离线资料暂不可用，请返回后重试。")
        } else {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val page = repository.birdDialogues(request) ?: return@withContext null
                    val detail = requestedDialogueId?.let(repository::birdDialogue)
                    page to detail
                }
                value = when {
                    loaded == null -> BirdDialogueLoadState.Unavailable
                    requestedDialogueId != null && loaded.second == null ->
                        BirdDialogueLoadState.Failed("这条对话已不在当前资料版本中，请返回重新选择。")
                    else -> BirdDialogueLoadState.Ready(loaded.first, loaded.second)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                value = BirdDialogueLoadState.Failed("逗鸟资料读取失败，请重试。")
            }
        }
    }

    fun backFromDetail() {
        if (selectedDialogueId != null) selectedDialogueId = null else onBack()
    }

    BackHandler { backFromDetail() }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("bird-dialogues-screen")) {
        val ready = state is BirdDialogueLoadState.Ready
        val readingKey = "guide/bird-dialogues/${query.trim()}/${characterGroup ?: "all"}/" +
            "${reviewFilter.name}/${selectedDialogueId ?: "results"}"
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = if (maxWidth < 600.dp) 300.dp else 320.dp),
            state = rememberReadingGridState(readingKey, ready),
            modifier = Modifier.fillMaxSize()
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = 1040.dp)
                .testTag("bird-dialogues-grid"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                BirdDialogueHeader(
                    detail = (state as? BirdDialogueLoadState.Ready)?.detail,
                    onBack = ::backFromDetail,
                )
            }
            when (val current = state) {
                BirdDialogueLoadState.Loading -> item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator()
                        Text("正在读取离线逗鸟资料…", Modifier.padding(start = 14.dp))
                    }
                }
                BirdDialogueLoadState.Unavailable -> item(span = { GridItemSpan(maxLineSpan) }) {
                    BirdDialogueMessage(
                        "当前资料版本尚未包含逗鸟问答",
                        "请更新应用内离线资料后再试。",
                        Icons.Outlined.ErrorOutline,
                    )
                }
                is BirdDialogueLoadState.Failed -> item(span = { GridItemSpan(maxLineSpan) }) {
                    BirdDialogueMessage(current.message, "原有个人记录不会受到影响。", Icons.Outlined.ErrorOutline) {
                        retryToken++
                    }
                }
                is BirdDialogueLoadState.Ready -> {
                    val detail = current.detail
                    if (detail != null) {
                        item(span = { GridItemSpan(maxLineSpan) }) { BirdDialogueDetail(detail) }
                        item(span = { GridItemSpan(maxLineSpan) }) { BirdDialogueSourceNote(current.page.sourceNote) }
                    } else {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            BirdDialogueFilters(
                                query = query,
                                characterGroup = characterGroup,
                                reviewFilter = reviewFilter,
                                onQueryChange = { query = it; pageIndex = 0 },
                                onGroupChange = { characterGroup = it; pageIndex = 0 },
                                onReviewChange = { reviewFilterId = it.name; pageIndex = 0 },
                            )
                        }
                        item(span = { GridItemSpan(maxLineSpan) }) { BirdDialogueSourceNote(current.page.sourceNote) }
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                "${current.page.totalMatches} 条对话",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.semantics { heading() },
                            )
                        }
                        if (current.page.entries.isEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                BirdDialogueMessage("没有找到匹配的对话", "试试角色名、台词关键词或反应。", Icons.Outlined.Search)
                            }
                        } else {
                            items(current.page.entries, key = BirdDialogueRecord::entryId) { entry ->
                                BirdDialogueCard(entry) { selectedDialogueId = entry.entryId }
                            }
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                BirdDialoguePageControls(current.page, pageIndex) { pageIndex = it }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BirdDialogueHeader(detail: BirdDialogueRecord?, onBack: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.heightIn(min = 48.dp).testTag("bird-dialogue-detail-back"),
        ) {
            androidx.compose.material3.Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
            Text(if (detail == null) "返回攻略" else "返回对话列表", Modifier.padding(start = 8.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "逗鸟问答",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                if (detail == null) "按角色或关键词查找对话和推荐反应。"
                else "查看完整对话、推荐反应与来源记录。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BirdDialogueFilters(
    query: String,
    characterGroup: String?,
    reviewFilter: BirdDialogueReviewFilter,
    onQueryChange: (String) -> Unit,
    onGroupChange: (String?) -> Unit,
    onReviewChange: (BirdDialogueReviewFilter) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth().testTag("bird-dialogue-search"),
            placeholder = { Text("搜索角色、对话或反应") },
            leadingIcon = { androidx.compose.material3.Icon(Icons.Outlined.Search, contentDescription = null) },
            singleLine = true,
            label = { Text("搜索问答") },
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("角色范围", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(null to "全部角色", "主角" to "主角", "可招募角色" to "可招募角色").forEach { (group, label) ->
                    FilterChip(
                        selected = characterGroup == group,
                        onClick = { onGroupChange(group) },
                        label = { Text(label) },
                        modifier = Modifier.heightIn(min = 48.dp).testTag(
                            when (group) {
                                null -> "bird-dialogue-group-all"
                                "主角" -> "bird-dialogue-group-protagonist"
                                else -> "bird-dialogue-group-recruitable"
                            },
                        ),
                    )
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("校订标记", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BirdDialogueReviewFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = reviewFilter == filter,
                        onClick = { onReviewChange(filter) },
                        label = { Text(filter.label) },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("bird-dialogue-review-${filter.name.lowercase()}"),
                    )
                }
            }
        }
    }
}

@Composable
private fun BirdDialogueSourceNote(note: String) {
    val attribution = note.substringBefore("如有错误记得反馈").trim().removePrefix("说明：").trim().removeSuffix("。")
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("资料说明", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(attribution, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "校订状态仅反映来源表标记，不代表官方认证；缺失英文原文或角色范围会明确留空。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BirdDialogueCard(entry: BirdDialogueRecord, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag("bird-dialogue-card-${entry.entryId}"),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WikiImage(entry.characterImageAsset, entry.characterName, Modifier.size(50.dp), crop = true)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(entry.characterName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        entry.characterGroup ?: "角色范围未标注",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BirdDialogueCorrectionBadge(entry.markedCorrected)
            }
            Text(entry.translatedPrompt, style = MaterialTheme.typography.bodyMedium, maxLines = 4,
                overflow = TextOverflow.Ellipsis)
            BirdDialogueReactionBadge(entry.recommendedReaction)
            Text("查看详情 · 来源第${entry.sourceRowNumber}行", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BirdDialogueDetail(entry: BirdDialogueRecord) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                WikiImage(entry.characterImageAsset, entry.characterName, Modifier.size(72.dp), crop = true)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(entry.characterName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        listOfNotNull(entry.characterNameEnglish, entry.characterGroup ?: "角色范围未标注")
                            .joinToString(" · "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BirdDialogueCorrectionBadge(entry.markedCorrected)
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("对话", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text("“${entry.translatedPrompt}”", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium)
                Text(
                    entry.originalPrompt ?: "来源未记录英文对话原文",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("推荐反应", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(entry.recommendedReaction, style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                entry.originalReaction?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f))
                }
            }
        }
        Text("来源：${entry.sourceSheetName} · 第${entry.sourceRowNumber}行 · ${entry.sourceSheetId}",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BirdDialogueCorrectionBadge(markedCorrected: Boolean) {
    val label = if (markedCorrected) "已标记校订" else "待校订"
    Surface(
        color = if (markedCorrected) MaterialTheme.colorScheme.tertiaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (markedCorrected) {
                androidx.compose.material3.Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = if (markedCorrected) MaterialTheme.colorScheme.onTertiaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BirdDialogueReactionBadge(reaction: String) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
        Text(
            "推荐反应  ·  $reaction",
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun BirdDialoguePageControls(page: BirdDialoguePage, pageIndex: Int, onPageChange: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = { onPageChange(pageIndex - 1) },
            enabled = page.hasPrevious,
            modifier = Modifier.heightIn(min = 48.dp).testTag("bird-dialogue-previous"),
        ) { Text("上一页") }
        Text("第${pageIndex + 1}页", style = MaterialTheme.typography.labelLarge)
        OutlinedButton(
            onClick = { onPageChange(pageIndex + 1) },
            enabled = page.hasNext,
            modifier = Modifier.heightIn(min = 48.dp).testTag("bird-dialogue-next"),
        ) { Text("下一页") }
    }
}

@Composable
private fun BirdDialogueMessage(title: String, description: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
                                onRetry: (() -> Unit)? = null) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
            onRetry?.let { retry ->
                OutlinedButton(onClick = retry, modifier = Modifier.heightIn(min = 48.dp)) { Text("重新读取") }
            }
        }
    }
}
