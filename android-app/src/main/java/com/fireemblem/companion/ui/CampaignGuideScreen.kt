package com.fireemblem.companion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fireemblem.companion.campaign.CampaignGuideProjector
import com.fireemblem.companion.campaign.CampaignGuideTask
import com.fireemblem.companion.campaign.CampaignTaskKind
import com.fireemblem.companion.campaign.CampaignTaskFacet
import com.fireemblem.companion.campaign.CampaignTaskProgress
import com.fireemblem.companion.campaign.CampaignTaskStatus
import com.fireemblem.companion.content.WikiEntry
import com.fireemblem.companion.content.WikiPageRequest
import com.fireemblem.companion.content.WikiRepository
import com.fireemblem.companion.engine.BuddyCampaignLine
import com.fireemblem.companion.personal.PersonalRevision
import com.fireemblem.companion.personal.PersonalStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface CampaignGuideLoadState {
    data object Loading : CampaignGuideLoadState
    data class Ready(
        val lineId: String,
        val tasks: List<CampaignGuideTask>,
        val progressHeads: Map<String, List<PersonalRevision>>,
    ) : CampaignGuideLoadState
    data class Failed(val message: String) : CampaignGuideLoadState
}

private enum class CampaignTaskFilter(val label: String) {
    ALL("全部"),
    TODO("待办 / 未招募"),
    COMPLETED("已完成 / 已招募"),
    MISSED("已错过"),
}

private enum class CampaignTaskKindFilter(val label: String, val kind: CampaignTaskKind?) {
    ALL("全部任务", null),
    RECRUITMENT("角色招募", CampaignTaskKind.RECRUITMENT),
    SIDE_QUEST("支线任务", CampaignTaskKind.SIDE_QUEST),
    PARALOGUE("外传", CampaignTaskKind.PARALOGUE),
    TIMELINE("事件时间轴", CampaignTaskKind.TIMELINE),
    CHALLENGE("迪篇挑战书", CampaignTaskKind.CHALLENGE),
}

@Composable
internal fun CampaignGuideScreen(
    repository: WikiRepository?,
    onBack: () -> Unit,
    onOpenWiki: (String) -> Unit,
    initialKind: CampaignTaskKind? = null,
) {
    val context = LocalContext.current
    val store = remember(context) { PersonalStore(context) }
    val scope = rememberCoroutineScope()
    val profileId = remember(context) {
        context.getSharedPreferences("personal-view", 0).getString("profile", "global") ?: "global"
    }
    var selectedLineId by androidx.compose.runtime.saveable.rememberSaveable {
        mutableStateOf(BuddyCampaignLine.KAI.id)
    }
    var filterId by androidx.compose.runtime.saveable.rememberSaveable {
        mutableStateOf(CampaignTaskFilter.ALL.name)
    }
    var kindFilterId by androidx.compose.runtime.saveable.rememberSaveable(initialKind) {
        mutableStateOf(
            CampaignTaskKindFilter.entries.firstOrNull { it.kind == initialKind }?.name
                ?: CampaignTaskKindFilter.ALL.name,
        )
    }
    var facetId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var facetValue by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var reloadToken by remember { mutableIntStateOf(0) }
    var savingTaskIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var progressOverrides by remember(profileId) { mutableStateOf<Map<String, CampaignTaskStatus>>(emptyMap()) }
    var saveMessage by remember { mutableStateOf<String?>(null) }
    val selectedLine = BuddyCampaignLine.entries.firstOrNull { it.id == selectedLineId } ?: BuddyCampaignLine.KAI
    val loadState by produceState<CampaignGuideLoadState>(
        initialValue = CampaignGuideLoadState.Loading,
        repository,
        store,
        profileId,
        selectedLine,
        reloadToken,
    ) {
        value = CampaignGuideLoadState.Loading
        if (repository == null) {
            value = CampaignGuideLoadState.Failed("离线任务资料暂不可用，请返回图鉴确认资料库已打开。")
        } else {
            value = try {
                withContext(Dispatchers.IO) {
                    val tasks = loadTasks(repository, selectedLine)
                    val snapshot = store.snapshot()
                    val heads = snapshot.heads()
                    val progressHeads = tasks.associate { task ->
                        val entityId = CampaignTaskProgress.entityId(profileId, selectedLine.id, task.entryId)
                        entityId to heads[entityId].orEmpty()
                    }
                    CampaignGuideLoadState.Ready(selectedLine.id, tasks, progressHeads)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                CampaignGuideLoadState.Failed("任务资料读取失败；本机进度未更改，请返回后重试。")
            }
        }
    }
    val ready = (loadState as? CampaignGuideLoadState.Ready)?.takeIf { it.lineId == selectedLine.id }
    val taskStatuses = ready?.tasks?.associate { task ->
        val entityId = CampaignTaskProgress.entityId(profileId, selectedLine.id, task.entryId)
        val revisions = ready.progressHeads[entityId].orEmpty()
        val storedStatus = revisions.mapNotNull { CampaignTaskProgress.fromRevision(it) }
            .map { it.status }.distinct().singleOrNull() ?: CampaignTaskStatus.TODO
        val status = progressOverrides["${selectedLine.id}:${task.entryId}"] ?: storedStatus
        task.entryId to CampaignTaskProgressView(status, revisions.size > 1)
    }.orEmpty()
    val selectedFilter = CampaignTaskFilter.entries.firstOrNull { it.name == filterId } ?: CampaignTaskFilter.ALL
    val selectedKindFilter = CampaignTaskKindFilter.entries.firstOrNull { it.name == kindFilterId }
        ?: CampaignTaskKindFilter.ALL
    val selectedFacet = CampaignTaskFacet.entries.firstOrNull { it.name == facetId }
    val facetOptions = selectedFacet?.options(ready?.tasks.orEmpty().filter { task ->
        selectedKindFilter.kind == null || task.kind == selectedKindFilter.kind
    }).orEmpty()
    val selectedFacetValue = facetValue?.takeIf { it in facetOptions }
    val visibleTasks = ready?.tasks.orEmpty().filter { task ->
        val taskStatus = taskStatuses[task.entryId]?.status ?: CampaignTaskStatus.TODO
        val matchesKind = selectedKindFilter.kind == null || task.kind == selectedKindFilter.kind
        val matchesFilter = when (selectedFilter) {
            CampaignTaskFilter.ALL -> true
            CampaignTaskFilter.TODO -> taskStatus == CampaignTaskStatus.TODO
            CampaignTaskFilter.COMPLETED -> taskStatus == CampaignTaskStatus.COMPLETED
            CampaignTaskFilter.MISSED -> taskStatus == CampaignTaskStatus.MISSED
        }
        matchesKind && matchesFilter && selectedFacet?.matches(task, selectedFacetValue) != false &&
            (query.isBlank() || task.searchText().contains(query.trim(), ignoreCase = true))
    }

    fun changeProgress(task: CampaignGuideTask, status: CampaignTaskStatus) {
        if (ready == null) return
        val entityId = CampaignTaskProgress.entityId(profileId, selectedLine.id, task.entryId)
        val taskKey = "${selectedLine.id}:${task.entryId}"
        val lineLabel = selectedLine.label
        val expectedParents = ready.progressHeads[entityId].orEmpty().map(PersonalRevision::revisionId).toSet()
        val progress = CampaignTaskProgress(profileId, selectedLine.id, task.entryId, status)
        savingTaskIds = savingTaskIds + taskKey
        saveMessage = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    store.edit(entityId, "progress", progress.payload(), expectedParents = expectedParents)
                }
                progressOverrides = progressOverrides + (taskKey to status)
                saveMessage = "已保存到当前存档的${lineLabel}任务清单。"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                reloadToken++
                saveMessage = "进度未保存，请检查其他设备上的更新后重试。"
            } finally {
                savingTaskIds = savingTaskIds - taskKey
            }
        }
    }

    androidx.activity.compose.BackHandler(onBack = onBack)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val horizontalPadding = if (maxWidth < 600.dp) 16.dp else 24.dp
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyVerticalGrid(
                state = rememberReadingGridState("campaign:$profileId:$selectedLineId:$filterId:$kindFilterId:$facetId:$facetValue:$query", ready != null),
                columns = GridCells.Adaptive(minSize = 300.dp),
                modifier = Modifier.fillMaxSize().widthIn(max = 1040.dp).testTag("campaign-grid"),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = horizontalPadding,
                    end = horizontalPadding,
                    top = 18.dp,
                    bottom = 24.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "campaign-guide-header", span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        OutlinedButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
                            Icon(Icons.Outlined.Map, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("返回攻略")
                        }
                        ScreenHeading("路线任务", "查看本线支线与外传，按实际进度记录待办、完成或错过。")
                        CampaignRoutePicker(selectedLine, onSelect = {
                            selectedLineId = it.id
                            facetValue = null
                        })
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth().testTag("campaign-search"),
                            label = { Text("搜索任务、角色或地点") },
                            singleLine = true,
                        )
                        CampaignTaskKindFilters(selectedKindFilter, onSelect = {
                            kindFilterId = it.name
                            facetValue = null
                        })
                        CampaignTaskFilters(selectedFilter, onSelect = { filterId = it.name })
                        CampaignTaskFacetFilters(selectedFacet, onSelect = {
                            facetId = if (selectedFacet == it) null else it.name
                            facetValue = null
                        })
                        selectedFacet?.let { facet ->
                            CampaignFacetValueFilter(
                                facet = facet,
                                value = selectedFacetValue,
                                options = facetOptions,
                                onSelect = { facetValue = it },
                            )
                        }
                        ready?.let { data ->
                            val counts = CampaignTaskStatus.entries.associateWith { status ->
                                data.tasks.count { task -> taskStatuses[task.entryId]?.status == status }
                            }
                            Text(
                                "${selectedLine.label} · 招募 ${data.tasks.count { it.kind == CampaignTaskKind.RECRUITMENT }} · " +
                                    "支线 ${data.tasks.count { it.kind == CampaignTaskKind.SIDE_QUEST }} · " +
                                    "外传 ${data.tasks.count { it.kind == CampaignTaskKind.PARALOGUE }} · " +
                                "时间轴 ${data.tasks.count { it.kind == CampaignTaskKind.TIMELINE }} · " +
                                "挑战书 ${data.tasks.count { it.kind == CampaignTaskKind.CHALLENGE }} · " +
                                    "待办/未招募 ${counts[CampaignTaskStatus.TODO]} · 已完成/已招募 ${counts[CampaignTaskStatus.COMPLETED]} · " +
                                    "已错过 ${counts[CampaignTaskStatus.MISSED]}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.testTag("campaign-summary"),
                            )
                        }
                        saveMessage?.let { CampaignNotice(it, isError = it.startsWith("进度未保存")) }
                        when (val state = loadState) {
                            CampaignGuideLoadState.Loading -> Row(
                                Modifier.fillMaxWidth().padding(vertical = 18.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(Modifier.size(24.dp))
                                Spacer(Modifier.width(12.dp))
                                Text("正在整理本线任务…")
                            }
                            is CampaignGuideLoadState.Failed -> CampaignNotice(state.message, isError = true)
                            is CampaignGuideLoadState.Ready -> if (state.lineId != selectedLine.id) {
                                Text("正在切换路线资料…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else if (visibleTasks.isEmpty()) {
                                CampaignNotice(
                                    if (state.tasks.isEmpty()) "当前路线没有收录支线或外传。" else "没有符合条件的任务。",
                                    isError = false,
                                )
                            } else {
                                Text("共 ${visibleTasks.size} 项", style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.semantics { heading() })
                            }
                        }
                    }
                }
                if (ready != null) {
                    items(visibleTasks, key = CampaignGuideTask::entryId) { task ->
                        val progress = taskStatuses[task.entryId] ?: CampaignTaskProgressView(CampaignTaskStatus.TODO, false)
                        val taskKey = "${selectedLine.id}:${task.entryId}"
                        CampaignTaskCard(
                            task = task,
                            status = progress.status,
                            hasConflict = progress.hasConflict,
                            saving = taskKey in savingTaskIds,
                            onStatusChange = { changeProgress(task, it) },
                            onOpen = { onOpenWiki(task.entryId) },
                        )
                    }
                }
            }
        }
    }
}

private data class CampaignTaskProgressView(val status: CampaignTaskStatus, val hasConflict: Boolean)

private suspend fun loadTasks(repository: WikiRepository, line: BuddyCampaignLine): List<CampaignGuideTask> {
    val entries = buildList {
            listOf(
                CampaignTaskKind.SIDE_QUEST.categoryId,
                CampaignTaskKind.PARALOGUE.categoryId,
                CampaignTaskKind.RECRUITMENT.categoryId,
                CampaignTaskKind.TIMELINE.categoryId,
                CampaignTaskKind.CHALLENGE.categoryId,
            ).forEach { categoryId ->
            var offset = 0
            var hasNext: Boolean
            do {
                val page = repository.page(WikiPageRequest(categoryId = categoryId, offset = offset, limit = 100))
                page.entries.forEach { summary -> repository.entry(summary.id)?.let { add(it) } }
                offset += page.entries.size
                hasNext = page.hasNext
            } while (hasNext)
        }
    }
    return CampaignGuideProjector.project(line, entries)
}

private fun CampaignGuideTask.searchText(): String = listOfNotNull(
    title, availability, deadline, timing, characters, location, completion, conditions, rewards, hints, victoryCondition,
).joinToString(" ")

@Composable
private fun CampaignRoutePicker(selected: BuddyCampaignLine, onSelect: (BuddyCampaignLine) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("选择路线", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BuddyCampaignLine.entries.forEach { line ->
                FilterChip(
                    selected = selected == line,
                    onClick = { onSelect(line) },
                    label = { Text(line.label) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("campaign-route-${line.id}"),
                )
            }
        }
    }
}

@Composable
private fun CampaignTaskFilters(selected: CampaignTaskFilter, onSelect: (CampaignTaskFilter) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CampaignTaskFilter.entries.forEach { filter ->
            FilterChip(
                selected = filter == selected,
                onClick = { onSelect(filter) },
                label = { Text(filter.label) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("campaign-filter-${filter.name.lowercase()}"),
            )
        }
    }
}

@Composable
private fun CampaignTaskKindFilters(selected: CampaignTaskKindFilter, onSelect: (CampaignTaskKindFilter) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CampaignTaskKindFilter.entries.forEach { filter ->
            FilterChip(
                selected = filter == selected,
                onClick = { onSelect(filter) },
                label = { Text(filter.label) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("campaign-kind-${filter.name.lowercase()}"),
            )
        }
    }
}

@Composable
private fun CampaignTaskFacetFilters(selected: CampaignTaskFacet?, onSelect: (CampaignTaskFacet) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("按章节、期限、条件或奖励筛选", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CampaignTaskFacet.entries.forEach { facet ->
                FilterChip(
                    selected = selected == facet,
                    onClick = { onSelect(facet) },
                    label = { Text(facet.label) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("campaign-facet-${facet.name.lowercase()}"),
                )
            }
        }
    }
}

@Composable
private fun CampaignFacetValueFilter(
    facet: CampaignTaskFacet,
    value: String?,
    options: List<String>,
    onSelect: (String?) -> Unit,
) {
    var expanded by androidx.compose.runtime.remember(facet) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${facet.label} · ${options.size} 项", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(
                enabled = options.isNotEmpty(),
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("campaign-facet-value"),
            ) {
                Text(value ?: "不限", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.heightIn(max = 360.dp),
            ) {
                DropdownMenuItem(
                    text = { Text("不限") },
                    onClick = { onSelect(null); expanded = false },
                    modifier = Modifier.testTag("campaign-facet-option-all"),
                )
                options.forEachIndexed { index, option ->
                    DropdownMenuItem(
                        text = { Text(option, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        onClick = { onSelect(option); expanded = false },
                        modifier = Modifier.testTag("campaign-facet-option-$index"),
                    )
                }
            }
        }
    }
}

@Composable
private fun CampaignTaskCard(
    task: CampaignGuideTask,
    status: CampaignTaskStatus,
    hasConflict: Boolean,
    saving: Boolean,
    onStatusChange: (CampaignTaskStatus) -> Unit,
    onOpen: () -> Unit,
) {
    var expanded by androidx.compose.runtime.saveable.rememberSaveable(task.entryId) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().testTag("campaign-task-${task.entryId}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                if (task.imageAsset != null) {
                    WikiImage(task.imageAsset, "${task.title}图片", Modifier.size(56.dp), crop = true)
                } else {
                    Surface(
                        modifier = Modifier.size(48.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                when (task.kind) {
                                    CampaignTaskKind.PARALOGUE -> Icons.Outlined.History
                                    CampaignTaskKind.RECRUITMENT -> Icons.Outlined.Person
                                    CampaignTaskKind.SIDE_QUEST -> Icons.Outlined.EditNote
                                    CampaignTaskKind.TIMELINE -> Icons.Outlined.History
                                    CampaignTaskKind.CHALLENGE -> Icons.Outlined.EditNote
                                },
                                contentDescription = task.kind.label,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        task.kind.label + if (task.isCommon) " · 共通章节" else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
                    Text(task.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(status.label(task.kind), style = MaterialTheme.typography.labelLarge,
                        color = if (status == CampaignTaskStatus.COMPLETED) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            when (task.kind) {
                CampaignTaskKind.PARALOGUE -> {
                    CampaignFact("接取期限", task.availability)
                    CampaignFact("截止期限", task.deadline)
                }
                CampaignTaskKind.RECRUITMENT -> {
                    CampaignFact("最早加入", task.availability)
                    CampaignFact("加入条件", task.timing)
                }
                CampaignTaskKind.SIDE_QUEST -> {
                    CampaignFact("触发时期与地点", task.timing)
                    CampaignFact("任务NPC", task.characters)
                }
                CampaignTaskKind.TIMELINE -> {
                    CampaignFact(if (task.isCommon) "共通章节" else "章节", task.chapter)
                    CampaignFact("事件类型", task.conditions)
                    CampaignFact("开始时间", task.timing)
                    CampaignFact("截止时间", task.deadline)
                    CampaignFact("触发方式", task.location)
                }
                CampaignTaskKind.CHALLENGE -> {
                    CampaignFact("对应战技", task.timing)
                    CampaignFact("接取条件", task.availability)
                    CampaignFact("任务地点", task.location)
                    CampaignFact("截止时间", task.deadline)
                    CampaignFact("战技参数", task.conditions)
                }
            }
            CampaignFact(if (task.kind == CampaignTaskKind.TIMELINE) "奖励" else "报酬奖励", task.rewards)
            if (expanded) {
                when (task.kind) {
                    CampaignTaskKind.TIMELINE -> {
                        CampaignFact("相关人物", task.characters)
                        CampaignFact("备注", task.hints)
                        CampaignFact("胜利条件", task.victoryCondition)
                    }
                    CampaignTaskKind.CHALLENGE -> CampaignFact("战技效果", task.completion)
                    else -> {
                        CampaignFact("完成条件与期限", task.completion)
                        CampaignFact("特殊条件", task.conditions)
                        CampaignFact("相关角色", if (task.kind == CampaignTaskKind.PARALOGUE) task.characters else null)
                        CampaignFact("提示", task.hints)
                        CampaignFact("胜利条件", task.victoryCondition)
                    }
                }
            }
            if (hasConflict) Text("这项进度在设备间有多个版本；选择状态后可合并保存。",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                CampaignTaskStatus.entries.forEach { candidate ->
                    FilterChip(
                        selected = status == candidate,
                        enabled = !saving,
                        onClick = { if (candidate != status || hasConflict) onStatusChange(candidate) },
                        label = { Text(candidate.label(task.kind)) },
                        modifier = Modifier.heightIn(min = 48.dp)
                            .testTag("campaign-status-${task.entryId}-${candidate.id}"),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(enabled = !saving, onClick = onOpen, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("查看完整资料")
                }
                androidx.compose.material3.TextButton(
                    enabled = !saving,
                    onClick = { expanded = !expanded },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(if (expanded) "收起" else "更多信息")
                }
                if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
private fun CampaignFact(label: String, value: String?) {
    value?.takeIf(String::isNotBlank)?.let {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CampaignNotice(message: String, isError: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
    ) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (isError) Icons.Outlined.ErrorOutline else Icons.Outlined.Info,
                contentDescription = if (isError) "资料不可用" else null,
                tint = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(message, color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
