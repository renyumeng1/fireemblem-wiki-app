package com.fireemblem.companion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.runtime.remember
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
import androidx.compose.ui.window.Dialog
import com.fireemblem.companion.campaign.EndingGuideProjector
import com.fireemblem.companion.campaign.EndingGuideRecord
import com.fireemblem.companion.campaign.EndingGuideSummary
import com.fireemblem.companion.campaign.EndingKind
import com.fireemblem.companion.content.WikiEntry
import com.fireemblem.companion.content.WikiLink
import com.fireemblem.companion.content.WikiPageRequest
import com.fireemblem.companion.content.WikiRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface EndingsLoadState {
    data object Loading : EndingsLoadState
    data class Ready(val entries: List<WikiEntry>, val total: Int) : EndingsLoadState
    data class Failed(val message: String) : EndingsLoadState
}

private sealed interface EndingDetailState {
    data object Closed : EndingDetailState
    data object Loading : EndingDetailState
    data class Ready(val record: EndingGuideRecord, val links: List<WikiLink>) : EndingDetailState
    data object Missing : EndingDetailState
    data object Failed : EndingDetailState
}

@Composable
internal fun EndingsGuideScreen(
    repository: WikiRepository?,
    onBack: () -> Unit,
    onOpenWiki: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selectedKindId by rememberSaveable { mutableStateOf("all") }
    var selectedCharacter by rememberSaveable { mutableStateOf<String?>(null) }
    var reloadToken by remember { mutableIntStateOf(0) }
    var selectedEntryId by rememberSaveable { mutableStateOf<String?>(null) }
    val endingsState by produceState<EndingsLoadState>(EndingsLoadState.Loading, repository, query, reloadToken) {
        value = EndingsLoadState.Loading
        if (repository == null) {
            value = EndingsLoadState.Failed("结局资料暂不可用，请返回攻略后重试。")
        } else {
            value = try {
                withContext(Dispatchers.IO) { loadEndings(repository, query.trim()) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                EndingsLoadState.Failed("结局资料读取失败；本机记录未更改，请重试。")
            }
        }
    }
    val characterEntries by produceState<List<WikiEntry>>(emptyList(), repository, reloadToken) {
        value = if (repository == null) emptyList() else try {
            withContext(Dispatchers.IO) { loadCharacterEntries(repository) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
    }
    val detailState by produceState<EndingDetailState>(EndingDetailState.Closed, repository, selectedEntryId) {
        val entryId = selectedEntryId
        if (entryId == null) {
            value = EndingDetailState.Closed
        } else if (repository == null) {
            value = EndingDetailState.Failed
        } else {
            value = EndingDetailState.Loading
            value = try {
                withContext(Dispatchers.IO) {
                    repository.entry(entryId)?.let { entry ->
                        EndingGuideProjector.project(entry)?.let { record ->
                            EndingDetailState.Ready(record, repository.links(entryId))
                        }
                    } ?: EndingDetailState.Missing
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                EndingDetailState.Failed
            }
        }
    }

    val sourceEntries = (endingsState as? EndingsLoadState.Ready)?.entries.orEmpty()
    val summaries = sourceEntries.mapNotNull(EndingGuideProjector::projectSummary)
    val selectedKind = when (selectedKindId) {
        "single" -> EndingKind.SINGLE
        "pair" -> EndingKind.PAIR
        else -> null
    }
    val counts = summaries.groupingBy(EndingGuideSummary::kind).eachCount()
    val characterNames = remember(characterEntries) { characterEntries.map(WikiEntry::title).distinct().sorted() }
    val visibleEndings = summaries.filter { summary ->
        (selectedKind == null || summary.kind == selectedKind) &&
            (selectedCharacter == null || selectedCharacter in summary.characterNames)
    }
    val portraits = remember(characterEntries) {
        characterEntries.groupBy(WikiEntry::title).mapValues { (_, entries) -> entries.singleOrNull()?.imageAsset }
    }

    BackHandler {
        if (selectedEntryId != null) selectedEntryId = null else onBack()
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val horizontalPadding = if (maxWidth < 600.dp) 16.dp else 24.dp
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyVerticalGrid(
                state = rememberReadingGridState("endings:$query:$selectedKindId:$selectedCharacter", endingsState is EndingsLoadState.Ready),
                columns = GridCells.Adaptive(minSize = 300.dp),
                modifier = Modifier.fillMaxSize().widthIn(max = 1080.dp).testTag("endings-guide-grid"),
                contentPadding = PaddingValues(start = horizontalPadding, end = horizontalPadding, top = 18.dp, bottom = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "endings-heading", span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("返回攻略")
                        }
                        Text(
                            "结局与后日谈",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.semantics { heading() },
                        )
                        Text(
                            "先查看角色组合与结局标签；正文默认收起，点开后再阅读。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth().testTag("ending-search"),
                            label = { Text("搜索角色、条件或后日谈正文") },
                            supportingText = { Text("搜索会匹配隐藏正文，但列表不会显示正文摘要。") },
                            singleLine = true,
                        )
                        when (val state = endingsState) {
                            EndingsLoadState.Loading -> Row(
                                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(Modifier.size(24.dp))
                                Spacer(Modifier.width(12.dp))
                                Text("正在读取离线结局资料…")
                            }
                            is EndingsLoadState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(state.message, color = MaterialTheme.colorScheme.error)
                                Button(onClick = { reloadToken++ }, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text("重试")
                                }
                            }
                            is EndingsLoadState.Ready -> Text(
                                if (visibleEndings.isEmpty()) "没有找到符合条件的结局。"
                                else "显示 ${visibleEndings.size}/${state.total} 条 · 双人 ${counts[EndingKind.PAIR] ?: 0} · 单人 ${counts[EndingKind.SINGLE] ?: 0}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("ending-result-count"),
                            )
                        }
                    }
                }
                item(key = "endings-kind-filter", span = { GridItemSpan(maxLineSpan) }) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(
                            selected = selectedKindId == "all",
                            onClick = { selectedKindId = "all" },
                            label = { Text("全部 ${counts.values.sum()}") },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("ending-kind-all"),
                        )
                        FilterChip(
                            selected = selectedKindId == "pair",
                            onClick = { selectedKindId = "pair" },
                            label = { Text("双人 ${counts[EndingKind.PAIR] ?: 0}") },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("ending-kind-pair"),
                        )
                        FilterChip(
                            selected = selectedKindId == "single",
                            onClick = { selectedKindId = "single" },
                            label = { Text("单人 ${counts[EndingKind.SINGLE] ?: 0}") },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("ending-kind-single"),
                        )
                    }
                }
                item(key = "endings-character-filter", span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("按角色查看", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(vertical = 2.dp),
                            modifier = Modifier.fillMaxWidth().testTag("ending-character-list"),
                        ) {
                            item(key = "all-ending-characters") {
                                FilterChip(
                                    selected = selectedCharacter == null,
                                    onClick = { selectedCharacter = null },
                                    label = { Text("全部角色") },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                )
                            }
                            items(characterNames, key = { it }) { name ->
                                FilterChip(
                                    selected = selectedCharacter == name,
                                    onClick = { selectedCharacter = if (selectedCharacter == name) null else name },
                                    label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    leadingIcon = {
                                        val image = portraits[name]
                                        if (image != null) WikiImage(image, "${name}头像", Modifier.size(24.dp), crop = true)
                                    },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                )
                            }
                        }
                    }
                }
                item(key = "endings-source", span = { GridItemSpan(maxLineSpan) }) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                        modifier = Modifier.testTag("ending-source-note"),
                    ) {
                        Text(
                            "资料来源：fireemblem.top「结局一览」；页面注明双人结局资料整理：機動戰士杰。条件未确认的条目保留来源标记。",
                            modifier = Modifier.padding(14.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (endingsState is EndingsLoadState.Ready) {
                    if (visibleEndings.isEmpty()) {
                        item(key = "endings-empty", span = { GridItemSpan(maxLineSpan) }) {
                            Text("换个角色或关键词再试试。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        items(visibleEndings, key = EndingGuideSummary::entryId) { ending ->
                            EndingCard(
                                ending = ending,
                                portraits = portraits,
                                bodySearchActive = query.isNotBlank(),
                                onOpen = { selectedEntryId = ending.entryId },
                            )
                        }
                    }
                }
            }
        }
    }

    when (val state = detailState) {
        EndingDetailState.Closed -> Unit
        EndingDetailState.Loading -> EndingDialog(onDismiss = { selectedEntryId = null }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Text("正在读取结局正文…")
            }
        }
        EndingDetailState.Missing -> EndingDialog(onDismiss = { selectedEntryId = null }) {
            Text("这条结局资料暂不可用，请返回列表重新选择。")
        }
        EndingDetailState.Failed -> EndingDialog(onDismiss = { selectedEntryId = null }) {
            Text("结局正文读取失败；资料列表仍可继续浏览。", color = MaterialTheme.colorScheme.error)
        }
        is EndingDetailState.Ready -> EndingContentDialog(
            record = state.record,
            links = state.links,
            portraits = portraits,
            onDismiss = { selectedEntryId = null },
            onOpenWiki = onOpenWiki,
        )
    }
}

@Composable
private fun EndingCard(
    ending: EndingGuideSummary,
    portraits: Map<String, String?>,
    bodySearchActive: Boolean,
    onOpen: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("ending-card-${ending.entryId}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = if (ending.kind == EndingKind.PAIR) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.tertiaryContainer,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                ) {
                    Text(
                        ending.kind.label,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                ending.label?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
                Spacer(Modifier.weight(1f))
                Text("剧透保护", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ending.characterNames.forEach { name ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WikiImage(portraits[name], "${name}头像", Modifier.size(48.dp), crop = true)
                        Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Text(
                if (bodySearchActive) "匹配结果仅显示角色与标签；后日谈正文保持收起。"
                else "后日谈正文已收起。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onOpen,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ending-read-${ending.entryId}"),
            ) {
                Text("查看结局内容")
            }
        }
    }
}

@Composable
private fun EndingContentDialog(
    record: EndingGuideRecord,
    links: List<WikiLink>,
    portraits: Map<String, String?>,
    onDismiss: () -> Unit,
    onOpenWiki: (String) -> Unit,
) {
    EndingDialog(onDismiss = onDismiss) {
        Column(
            Modifier.fillMaxWidth().widthIn(max = 840.dp).heightIn(max = 760.dp).testTag("ending-story-dialog"),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    record.kind.label + record.label?.let { " · $it" }.orEmpty(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
                Text(record.title, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(
                Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberReadingScrollState("ending-detail:${record.entryId}")),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    record.characters.forEach { character ->
                        val target = links.firstOrNull { it.label == "相关角色" && it.title == character.name }
                        OutlinedButton(
                            onClick = { target?.let { onOpenWiki(it.targetId) } },
                            enabled = target != null,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            WikiImage(portraits[character.name], "${character.name}头像", Modifier.size(28.dp), crop = true)
                            Spacer(Modifier.width(8.dp))
                            Text(character.name + character.epithet?.let { " · $it" }.orEmpty())
                        }
                    }
                }
                record.condition?.let { condition ->
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("触发条件", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                            Text(condition)
                        }
                    }
                }
                if (record.hasStory) {
                    record.paragraphs.forEachIndexed { index, paragraph ->
                        Text(
                            paragraph,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth().testTag("ending-story-paragraph-$index"),
                        )
                    }
                } else {
                    Text("正文资料暂不可用；未根据角色或标题补写内容。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    "来源：fireemblem.top「结局一览」 · 序号 ${record.sourceRowNumber}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ending-close-dialog")) {
                Text("收起内容")
            }
        }
    }
}

@Composable
private fun EndingDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().widthIn(max = 880.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                content()
            }
        }
    }
}

private fun loadEndings(repository: WikiRepository, query: String): EndingsLoadState.Ready {
    val entries = mutableListOf<WikiEntry>()
    var offset = 0
    var total = 0
    var hasNext: Boolean
    do {
        val page = repository.page(WikiPageRequest(categoryId = EndingGuideProjector.CATEGORY_ID, query = query, offset = offset))
        entries += page.entries
        total = page.totalMatches
        offset += page.entries.size
        hasNext = page.hasNext
    } while (hasNext)
    return EndingsLoadState.Ready(entries, total)
}

private fun loadCharacterEntries(repository: WikiRepository): List<WikiEntry> {
    val entries = mutableListOf<WikiEntry>()
    var offset = 0
    var hasNext: Boolean
    do {
        val page = repository.page(WikiPageRequest(categoryId = "characters", offset = offset, limit = 100))
        entries += page.entries
        offset += page.entries.size
        hasNext = page.hasNext
    } while (hasNext)
    return entries
}
