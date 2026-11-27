package com.fireemblem.companion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
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
import com.fireemblem.companion.content.SupportBonusProjector
import com.fireemblem.companion.content.SupportBonusVector
import com.fireemblem.companion.content.SupportDialogueCondition
import com.fireemblem.companion.content.SupportGuideData
import com.fireemblem.companion.content.SupportJob
import com.fireemblem.companion.content.SupportPairDetail
import com.fireemblem.companion.content.SupportPairPage
import com.fireemblem.companion.content.SupportPairRequest
import com.fireemblem.companion.content.SupportPairSummary
import com.fireemblem.companion.content.SupportPersonSummary
import com.fireemblem.companion.content.SupportRelationFilter
import com.fireemblem.companion.content.SupportWikiMatchStatus
import com.fireemblem.companion.content.WikiRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface SupportGuideLoadState {
    data object Loading : SupportGuideLoadState
    data object Unavailable : SupportGuideLoadState
    data class Ready(
        val request: SupportPairRequest,
        val pairId: String?,
        val guide: SupportGuideData,
        val page: SupportPairPage,
        val detail: SupportPairDetail?,
    ) : SupportGuideLoadState
    data class Failed(val message: String) : SupportGuideLoadState
}

@Composable
internal fun SupportGuideScreen(
    repository: WikiRepository?,
    onBack: () -> Unit,
    onOpenWiki: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var characterName by rememberSaveable { mutableStateOf<String?>(null) }
    var filterId by rememberSaveable { mutableStateOf(SupportRelationFilter.ALL.name) }
    val filter = SupportRelationFilter.entries.firstOrNull { it.name == filterId } ?: SupportRelationFilter.ALL
    var pageIndex by rememberSaveable(query, characterName, filterId) { mutableIntStateOf(0) }
    var selectedPairId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedSupportLevel by rememberSaveable { mutableIntStateOf(1) }
    var selectedJobId by rememberSaveable { mutableStateOf<String?>(null) }
    var showCharacterPicker by rememberSaveable { mutableStateOf(false) }
    var showJobPicker by rememberSaveable { mutableStateOf(false) }
    var retryToken by rememberSaveable { mutableIntStateOf(0) }
    val requestedPairId = selectedPairId
    val request = SupportPairRequest(
        query = query.trim(),
        characterName = characterName,
        filter = filter,
        offset = pageIndex.coerceAtLeast(0) * SupportPairRequest.PAGE_SIZE,
        limit = SupportPairRequest.PAGE_SIZE,
    )

    val state by produceState<SupportGuideLoadState>(
        initialValue = SupportGuideLoadState.Loading,
        repository,
        request,
        requestedPairId,
        retryToken,
    ) {
        value = SupportGuideLoadState.Loading
        if (repository == null) {
            value = SupportGuideLoadState.Failed("离线资料暂不可用，请返回后重试。")
        } else {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val guide = repository.supportGuide() ?: return@withContext null
                    val page = repository.supportPairs(request) ?: return@withContext null
                    val detail = requestedPairId?.let(repository::supportPair)
                    Triple(guide, page, detail)
                }
                value = when {
                    loaded == null -> SupportGuideLoadState.Unavailable
                    requestedPairId != null && loaded.third == null ->
                        SupportGuideLoadState.Failed("这组支援资料已不在当前版本中，请返回重新选择。")
                    else -> SupportGuideLoadState.Ready(
                        request = request,
                        pairId = requestedPairId,
                        guide = loaded.first,
                        page = loaded.second,
                        detail = loaded.third,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                value = SupportGuideLoadState.Failed("支援资料读取失败，请重试。")
            }
        }
    }
    val visibleState = when (val current = state) {
        is SupportGuideLoadState.Ready -> if (current.request == request && current.pairId == requestedPairId) {
            current
        } else {
            SupportGuideLoadState.Loading
        }
        else -> current
    }

    fun backFromDetail() {
        if (selectedPairId != null) selectedPairId = null else onBack()
    }

    BackHandler { backFromDetail() }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("support-guide-screen")) {
        val ready = visibleState is SupportGuideLoadState.Ready
        val detailIdentity = requestedPairId ?: "results"
        val readingKey = "guide/supports/${query.trim()}/${characterName ?: "all"}/" +
            "${filter.name}/$pageIndex/$detailIdentity"
        val columns = if (maxWidth < 600.dp) 292.dp else 310.dp
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = columns),
            state = rememberReadingGridState(readingKey, ready),
            modifier = Modifier.fillMaxSize()
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = 1040.dp)
                .testTag("support-guide-grid"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SupportGuideHeader(detail = requestedPairId != null, onBack = ::backFromDetail)
            }
            when (val current = visibleState) {
                SupportGuideLoadState.Loading -> item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator()
                        Text("正在读取离线支援资料…", Modifier.padding(start = 14.dp))
                    }
                }
                SupportGuideLoadState.Unavailable -> item(span = { GridItemSpan(maxLineSpan) }) {
                    SupportMessage(
                        title = "当前离线资料尚未包含支援关系",
                        description = "更新应用内资料后，可查询组合加成、职业补正和对话条件。",
                    )
                }
                is SupportGuideLoadState.Failed -> item(span = { GridItemSpan(maxLineSpan) }) {
                    SupportMessage(current.message, "已记录的游戏进度不会受到影响。") { retryToken++ }
                }
                is SupportGuideLoadState.Ready -> {
                    val detail = current.detail
                    if (detail == null) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            SupportOverview(current.guide)
                        }
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it; pageIndex = 0 },
                                modifier = Modifier.fillMaxWidth().testTag("support-search"),
                                label = { Text("搜索角色") },
                                placeholder = { Text("输入角色名称或关键词") },
                                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                                singleLine = true,
                            )
                        }
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            SupportBrowseFilters(
                                guide = current.guide,
                                selectedCharacter = characterName,
                                filter = filter,
                                onOpenCharacterPicker = { showCharacterPicker = true },
                                onSelectFilter = { filterId = it.name; pageIndex = 0 },
                                onClearCharacter = { characterName = null; pageIndex = 0 },
                            )
                        }
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                "${current.page.totalMatches} 组关系",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.semantics { heading() },
                            )
                        }
                        if (current.page.entries.isEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                SupportMessage("没有找到匹配的关系", "试试其他角色名称或筛选条件。")
                            }
                        } else {
                            items(current.page.entries, key = SupportPairSummary::pairId) { pair ->
                                SupportPairCard(pair) {
                                    selectedSupportLevel = pair.initialLevel.coerceIn(1, 5)
                                    selectedJobId = null
                                    selectedPairId = pair.pairId
                                }
                            }
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                SupportPageControls(current.page, pageIndex) { pageIndex = it }
                            }
                        }
                    } else {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            SupportPairPeopleCard(detail, onOpenWiki)
                        }
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            SupportLevelCard(
                                detail = detail,
                                guide = current.guide,
                                supportLevel = selectedSupportLevel,
                                onSelectLevel = { selectedSupportLevel = it },
                            )
                        }
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            SupportJobBonusCard(
                                detail = detail,
                                guide = current.guide,
                                supportLevel = selectedSupportLevel,
                                selectedJobId = selectedJobId,
                                onOpenJobPicker = { showJobPicker = true },
                            )
                        }
                        if (detail.dialogues.isEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                SupportMessage(
                                    "来源未收录这组角色的支援对话",
                                    "这只说明当前资料没有对话条件，不代表游戏中没有支援关系。",
                                )
                            }
                        } else {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Text(
                                    "支援对话 · ${detail.dialogues.size} 条条件",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.semantics { heading() }.testTag("support-dialogues-heading"),
                                )
                            }
                            items(
                                items = detail.dialogues,
                                key = SupportDialogueCondition::dialogueId,
                                span = { GridItemSpan(maxLineSpan) },
                            ) { dialogue ->
                                SupportDialogueCard(dialogue)
                            }
                        }
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                "来源：fireemblem.top 支援资料 · 修订 ${detail.sourceRevision.take(12)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        if (showCharacterPicker) {
            val guide = (visibleState as? SupportGuideLoadState.Ready)?.guide
            if (guide != null) {
                SupportCharacterPickerDialog(
                    people = guide.people,
                    onDismiss = { showCharacterPicker = false },
                    onSelect = { selected ->
                        characterName = selected
                        pageIndex = 0
                        showCharacterPicker = false
                    },
                )
            }
        }
        if (showJobPicker) {
            val guide = (visibleState as? SupportGuideLoadState.Ready)?.guide
            if (guide != null) {
                SupportJobPickerDialog(
                    jobs = guide.jobs,
                    onDismiss = { showJobPicker = false },
                    onSelect = { selectedJobId = it; showJobPicker = false },
                )
            }
        }
    }
}

@Composable
private fun SupportGuideHeader(detail: Boolean, onBack: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.heightIn(min = 48.dp).testTag("support-guide-back"),
        ) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
            Text(if (detail) "返回关系列表" else "返回攻略", Modifier.padding(start = 8.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "支援关系",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                if (detail) "查看组合补正、职业加成和对话开放条件。"
                else "按角色查关系、战斗补正和来源收录的对话。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SupportOverview(guide: SupportGuideData) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${guide.people.size} 名角色 · ${guide.jobs.size} 个职业", fontWeight = FontWeight.SemiBold)
            Text(
                "等级门槛：${guide.supportThresholds.joinToString(" / ")} 支援值",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "未收录对话不代表没有支援关系；路线编号尚未与游戏路线对应。补正为战斗数值，不是成长率。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun SupportBrowseFilters(
    guide: SupportGuideData,
    selectedCharacter: String?,
    filter: SupportRelationFilter,
    onOpenCharacterPicker: () -> Unit,
    onSelectFilter: (SupportRelationFilter) -> Unit,
    onClearCharacter: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = onOpenCharacterPicker,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("support-character-picker"),
            ) {
                Icon(Icons.Outlined.Person, contentDescription = null)
                Text(
                    selectedCharacter?.let { name -> guide.people.firstOrNull { it.sourceName == name }?.displayName }
                        ?: "全部角色",
                    Modifier.padding(start = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (selectedCharacter != null) {
                TextButton(onClick = onClearCharacter, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("清除")
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SupportRelationFilter.entries.forEach { candidate ->
                FilterChip(
                    selected = filter == candidate,
                    onClick = { onSelectFilter(candidate) },
                    label = { Text(candidate.label) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("support-filter-${candidate.name.lowercase()}"),
                )
            }
        }
    }
}

@Composable
private fun SupportPairCard(pair: SupportPairSummary, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag("support-pair-${pair.pairId}"),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SupportPersonPortrait(
                    pair.first,
                    Modifier.weight(1f),
                    "support-pair-person-${pair.pairId}-first",
                )
                Text("↔", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                SupportPersonPortrait(
                    pair.second,
                    Modifier.weight(1f),
                    "support-pair-person-${pair.pairId}-second",
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SupportStatusBadge(
                    if (pair.hasCombinationBonus) "组合加成" else "无组合加成",
                    positive = pair.hasCombinationBonus,
                )
                SupportStatusBadge(
                    if (pair.dialogueCount > 0) "对话 ${pair.dialogueCount}" else "未收录对话",
                    positive = pair.dialogueCount > 0,
                )
            }
            Text("初始支援等级：${pair.initialLevel}", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SupportPersonPortrait(person: SupportPersonSummary, modifier: Modifier = Modifier, tag: String? = null) {
    Column(
        if (tag == null) modifier else modifier.testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        WikiImage(person.imageAsset, "${person.displayName}头像", Modifier.size(54.dp), crop = true)
        Text(
            person.displayName,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            minLines = 2,
        )
        if (person.matchStatus != SupportWikiMatchStatus.MATCHED) {
            Text("头像未关联", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SupportPairPeopleCard(
    detail: SupportPairDetail,
    onOpenWiki: (String) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SupportPersonPortrait(
                    detail.summary.first,
                    Modifier.weight(1f),
                    "support-detail-person-${detail.summary.pairId}-first",
                )
                Text("↔", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                SupportPersonPortrait(
                    detail.summary.second,
                    Modifier.weight(1f),
                    "support-detail-person-${detail.summary.pairId}-second",
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                detail.summary.first.wikiCharacterId?.let { id ->
                    OutlinedButton(onClick = { onOpenWiki(id) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text("查看${detail.summary.first.displayName}")
                    }
                }
                detail.summary.second.wikiCharacterId?.let { id ->
                    OutlinedButton(onClick = { onOpenWiki(id) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text("查看${detail.summary.second.displayName}")
                    }
                }
            }
        }
    }
}

@Composable
private fun SupportLevelCard(
    detail: SupportPairDetail,
    guide: SupportGuideData,
    supportLevel: Int,
    onSelectLevel: (Int) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("支援等级", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                (1..5).forEach { level ->
                    FilterChip(
                        selected = supportLevel == level,
                        onClick = { onSelectLevel(level) },
                        label = { Text("${level}级") },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("support-level-$level"),
                    )
                }
            }
            Text(
                "第${supportLevel}级 · 累积支援值 ${guide.supportThresholds[supportLevel - 1]}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "初始等级：${detail.summary.initialLevel}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SupportJobBonusCard(
    detail: SupportPairDetail,
    guide: SupportGuideData,
    supportLevel: Int,
    selectedJobId: String?,
    onOpenJobPicker: () -> Unit,
) {
    val selectedJob = guide.jobs.firstOrNull { it.sourceId == selectedJobId }
    val projection = SupportBonusProjector.project(
        combinationBonus = detail.combinationBonus,
        job = selectedJob,
        supportLevel = supportLevel,
        supportTypes = guide.supportTypes,
    )
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("选择职业", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            OutlinedButton(
                onClick = onOpenJobPicker,
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("support-job-picker"),
            ) {
                if (selectedJob == null) {
                    Text("选择职业以计算等级补正")
                } else {
                    WikiImage(selectedJob.imageAsset, "${selectedJob.displayName}职业图标", Modifier.size(42.dp), crop = false)
                    Column(Modifier.weight(1f).padding(start = 10.dp), horizontalAlignment = Alignment.Start) {
                        Text(selectedJob.displayName, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${selectedJob.supportTypeLabel} · ${selectedJob.tiers.joinToString(" / ").ifBlank { "级别未标注" }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            selectedJob?.let { job ->
                if (job.matchStatus != SupportWikiMatchStatus.MATCHED) {
                    Text(
                        "职业图鉴资料未能唯一关联；仍可按来源中的职业类型计算补正。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                "属性值沿用来源记录；加成是战斗补正，不是成长率。合计逐项限制在 −100 到 100。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SupportBonusGrid("组合加成", guide.statNames, detail.combinationBonus)
            if (projection != null) {
                SupportBonusGrid("职业等级补正", guide.statNames, projection.jobBonus)
                SupportBonusGrid("合计补正", guide.statNames, projection.totalBonus)
            }
        }
    }
}

@Composable
private fun SupportBonusGrid(title: String, statNames: List<String>, vector: SupportBonusVector) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        statNames.chunked(3).forEachIndexed { rowIndex, names ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                names.forEachIndexed { columnIndex, name ->
                    val value = vector[rowIndex * 3 + columnIndex]
                    SupportBonusCell(name, value, Modifier.weight(1f))
                }
                repeat(3 - names.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun SupportBonusCell(statName: String, value: Int, modifier: Modifier = Modifier) {
    val background = when {
        value > 0 -> MaterialTheme.colorScheme.tertiaryContainer
        value < 0 -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val foreground = when {
        value > 0 -> MaterialTheme.colorScheme.onTertiaryContainer
        value < 0 -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(modifier, color = background, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 9.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(statName, style = MaterialTheme.typography.labelSmall, color = foreground,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (value > 0) "+$value" else value.toString(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = foreground,
            )
        }
    }
}

@Composable
private fun SupportDialogueCard(dialogue: SupportDialogueCondition) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SupportStatusBadge("${dialogue.rank.label} 支援", positive = true)
                Text(
                    "支援等级 ${dialogue.rank.minimumSupportLevel} 起开放",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "开放时间：${formatSupportChapter(dialogue.startPart, dialogue.startChapter, isStart = true)}" +
                    " 至 ${formatSupportChapter(dialogue.endPart, dialogue.endChapter, isStart = false)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "路线编号 ${dialogue.routeId}（尚未与应用路线核实）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (dialogue.anyJoined.isNotEmpty()) {
                Text("至少一人加入：${dialogue.anyJoined.joinToString("、")}", style = MaterialTheme.typography.bodySmall)
            }
            dialogue.conditions.forEach { condition ->
                Text("• $condition", style = MaterialTheme.typography.bodySmall)
            }
            if (dialogue.pendingConditions > 0) {
                Text("另有 ${dialogue.pendingConditions} 项条件尚待确认", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (dialogue.datePending) {
                Text("日期限制尚待确认", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun formatSupportChapter(part: Int, chapter: Int, isStart: Boolean): String = when {
    part == 0 && chapter == 0 -> "无截止章节"
    chapter == 0 -> "第${part}部${if (isStart) "开始" else "结束"}"
    else -> "第${part}部 · 第${chapter}章"
}

@Composable
private fun SupportStatusBadge(label: String, positive: Boolean) {
    Surface(
        color = if (positive) MaterialTheme.colorScheme.tertiaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            label,
            Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (positive) MaterialTheme.colorScheme.onTertiaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SupportPageControls(page: SupportPairPage, pageIndex: Int, onPageChange: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = { onPageChange(pageIndex - 1) },
            enabled = page.hasPrevious,
            modifier = Modifier.heightIn(min = 48.dp).testTag("support-page-previous"),
        ) { Text("上一页") }
        Text("第 ${pageIndex + 1} 页 · 共 ${page.totalMatches} 组", style = MaterialTheme.typography.bodySmall)
        Button(
            onClick = { onPageChange(pageIndex + 1) },
            enabled = page.hasNext,
            modifier = Modifier.heightIn(min = 48.dp).testTag("support-page-next"),
        ) { Text("下一页") }
    }
}

@Composable
private fun SupportMessage(title: String, description: String, retry: (() -> Unit)? = null) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(
            Modifier.fillMaxWidth().padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Outlined.ErrorOutline, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
            retry?.let { TextButton(onClick = it) { Text("重试") } }
        }
    }
}

@Composable
private fun SupportCharacterPickerDialog(
    people: List<SupportPersonSummary>,
    onDismiss: () -> Unit,
    onSelect: (String?) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = people.filter { it.displayName.contains(query.trim(), ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择角色") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().testTag("support-character-search"),
                    label = { Text("搜索角色") },
                    singleLine = true,
                )
                LazyColumn(Modifier.heightIn(max = 440.dp)) {
                    item {
                        TextButton(onClick = { onSelect(null) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text("全部角色", Modifier.fillMaxWidth())
                        }
                    }
                    items(filtered, key = SupportPersonSummary::sourceName) { person ->
                        TextButton(
                            onClick = { onSelect(person.sourceName) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                .testTag("support-character-option-${person.sourceName}"),
                        ) {
                            WikiImage(person.imageAsset, "${person.displayName}头像", Modifier.size(38.dp), crop = true)
                            Text(person.displayName, Modifier.weight(1f).padding(start = 10.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun SupportJobPickerDialog(
    jobs: List<SupportJob>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = jobs.filter { job ->
        listOf(job.displayName, job.name, job.supportTypeLabel, job.tiers.joinToString())
            .any { it.contains(query.trim(), ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择职业") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().testTag("support-job-search"),
                    label = { Text("搜索职业或类型") },
                    singleLine = true,
                )
                LazyColumn(Modifier.heightIn(max = 440.dp)) {
                    items(filtered, key = SupportJob::sourceId) { job ->
                        TextButton(
                            onClick = { onSelect(job.sourceId) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 62.dp)
                                .testTag("support-job-option-${job.sourceId}"),
                        ) {
                            WikiImage(job.imageAsset, "${job.displayName}职业图标", Modifier.size(42.dp), crop = false)
                            Column(Modifier.weight(1f).padding(start = 10.dp), horizontalAlignment = Alignment.Start) {
                                Text(job.displayName, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${job.supportTypeLabel} · ${job.tiers.joinToString(" / ").ifBlank { "级别未标注" }}" +
                                        if (job.matchStatus == SupportWikiMatchStatus.MATCHED) "" else " · 图鉴未关联",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
