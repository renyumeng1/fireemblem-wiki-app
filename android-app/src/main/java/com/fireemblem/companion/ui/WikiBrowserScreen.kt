package com.fireemblem.companion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fireemblem.companion.content.WikiCharacterLearningData
import com.fireemblem.companion.content.WikiEntry
import com.fireemblem.companion.content.WikiFieldOptions
import com.fireemblem.companion.content.WikiRepository
import com.fireemblem.companion.personal.PersonalStore
import com.fireemblem.companion.personal.PersonalTierList
import com.fireemblem.companion.ui.theme.tabularNumbers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

internal data class WikiCategoryBrowseState(
    val filterField: String? = null,
    val filterValue: String? = null,
    val sortField: String? = null,
    val descending: Boolean = false,
    val routeKey: String? = null,
    val fameDescending: Boolean = false,
    val pageIndex: Int = 0,
)

internal val wikiCategoryBrowseMapSaver = listSaver<SnapshotStateMap<String, WikiCategoryBrowseState>, String?>(
    save = { stateMap ->
        buildList {
            stateMap.forEach { (key, value) ->
                add(key)
                add(value.filterField)
                add(value.filterValue)
                add(value.sortField)
                add(value.descending.toString())
                add(value.routeKey)
                add(value.fameDescending.toString())
                add(value.pageIndex.toString())
            }
        }
    },
    restore = { flat ->
        mutableStateMapOf<String, WikiCategoryBrowseState>().apply {
            flat.chunked(8).forEach { chunk ->
                if (chunk.size == 8) {
                    val key = chunk[0] ?: return@forEach
                    put(
                        key,
                        WikiCategoryBrowseState(
                            filterField = chunk[1],
                            filterValue = chunk[2],
                            sortField = chunk[3],
                            descending = chunk[4]?.toBooleanStrictOrNull() ?: false,
                            routeKey = chunk[5],
                            fameDescending = chunk[6]?.toBooleanStrictOrNull() ?: false,
                            pageIndex = chunk[7]?.toIntOrNull() ?: 0,
                        ),
                    )
                }
            }
        }
    },
)

@Composable
internal fun WikiCatalogScreen(
    repository: WikiRepository,
    query: String,
    selectedCategoryId: String?,
    selectedEntryId: String?,
    onQueryChange: (String) -> Unit,
    onOpenCategory: (String) -> Unit,
    onOpenEntry: (String) -> Unit,
    onBackToCategories: () -> Unit,
    onCloseEntry: () -> Unit,
    navigationSession: Int = 0,
    externalBackLabel: String? = null,
    categoryBackLabel: String = "全部分类",
    onOpenJournal: () -> Unit = {},
    onOpenTierList: () -> Unit = {},
    onOpenRouteMerge: (String) -> Unit = {},
) {
    var compared by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var history by rememberSaveable(selectedCategoryId, query, navigationSession) { mutableStateOf(arrayListOf<String>()) }
    var compareOpen by rememberSaveable { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val detailStates = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val browseStates = rememberSaveable(saver = wikiCategoryBrowseMapSaver) { mutableStateMapOf() }
    val browseKey = selectedCategoryId ?: "__search__"
    val currentBrowseState = browseStates[browseKey] ?: WikiCategoryBrowseState()
    val updateBrowseState: (WikiCategoryBrowseState) -> Unit = { updated ->
        browseStates[browseKey] = updated
    }
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
    val close: () -> Unit = {
        if (history.isEmpty()) onCloseEntry()
        else {
            val previous = history.last()
            history = ArrayList(history.dropLast(1))
            onOpenEntry(previous)
        }
    }
    val addComparison: (String) -> Unit = { id ->
        when {
            id in compared -> message = "已加入比较，可查看比较或继续添加（最多 3 项）。"
            compared.isNotEmpty() && compared.first().substringBefore(':') != id.substringBefore(':') ->
                message = "请选择同一分类的条目进行比较，或先清空比较。"
            compared.size >= 3 -> compareOpen = true
            else -> {
                compared = ArrayList(compared + id)
                compareOpen = compared.size >= 2
            }
        }
    }
    val entryBackLabel = when {
        history.isNotEmpty() -> "返回上一级"
        externalBackLabel != null -> externalBackLabel
        selectedCategoryId != null -> "返回${repository.catalog.categories.firstOrNull { it.id == selectedCategoryId }?.title ?: "图鉴列表"}"
        else -> "返回搜索结果"
    }
    Column(Modifier.fillMaxSize()) {
        if (compared.isNotEmpty()) Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.large,
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text("已选 ${compared.size}/3 条", Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    style = MaterialTheme.typography.labelLarge.tabularNumbers())
                TextButton(onClick = { compareOpen = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("查看比较")
                }
                TextButton(onClick = { compared = arrayListOf(); compareOpen = false },
                    modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("清空")
                }
            }
        }
        message?.let { text ->
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                TextButton(onClick = { message = null }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(text, color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
        }
        BoxWithConstraints(Modifier.weight(1f)) {
            val wide = maxWidth >= 720.dp
            val catalogWeight = if (wide && selectedEntryId == null) 0.62f else 0.46f
            Row(Modifier.fillMaxSize()) {
                WikiCatalogPane(repository, query, selectedCategoryId, if (wide) null else selectedEntryId,
                    onQueryChange = {
                        history = arrayListOf()
                        browseStates[browseKey] = currentBrowseState.copy(pageIndex = 0)
                        onQueryChange(it)
                    },
                    onOpenCategory = { history = arrayListOf(); onOpenCategory(it) },
                    onOpenEntry = if (wide) openFromList else openLinked,
                    onBackToCategories = { history = arrayListOf(); onBackToCategories() },
                    onCloseEntry = close, onCompare = addComparison,
                    modifier = Modifier.weight(if (wide) catalogWeight else 1f),
                    handleBack = !wide || selectedEntryId == null,
                    entryBackLabel = entryBackLabel,
                    detailStateHolder = detailStates,
                    categoryBackLabel = categoryBackLabel,
                    onOpenJournal = onOpenJournal,
                    onOpenTierList = onOpenTierList,
                    onOpenRouteMerge = onOpenRouteMerge,
                    externalBrowseState = currentBrowseState,
                    onBrowseStateChange = updateBrowseState,
                )
                if (wide) {
                    Box(Modifier.fillMaxHeight().width(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                    if (selectedEntryId == null) Column(
                        Modifier.weight(1f - catalogWeight).fillMaxHeight().padding(28.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        ) {
                            Column(Modifier.fillMaxWidth().padding(24.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("离线图鉴", style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.primary)
                                Text("打开任一条资料，查看图片、关键属性与相关内容。",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else WikiCatalogPane(repository, query, selectedCategoryId, selectedEntryId,
                        onQueryChange, onOpenCategory, openLinked, onBackToCategories, close, addComparison,
                        modifier = Modifier.weight(1f - catalogWeight), showHeading = false,
                        entryBackLabel = entryBackLabel, detailStateHolder = detailStates,
                        categoryBackLabel = categoryBackLabel,
                        onOpenJournal = onOpenJournal,
                        onOpenTierList = onOpenTierList,
                        onOpenRouteMerge = onOpenRouteMerge,
                        externalBrowseState = currentBrowseState,
                        onBrowseStateChange = updateBrowseState)
                }
            }
        }
    }
    if (compareOpen) WikiComparison(repository, compared, onDismiss = { compareOpen = false })
}

@Composable
internal fun WikiQueryControls(
    options: List<WikiFieldOptions>,
    filterField: String?, filterValue: String?, sortField: String?, descending: Boolean,
    onFilter: (String?, String?) -> Unit, onSort: (String?, Boolean) -> Unit,
) {
    var fieldMenu by remember { mutableStateOf(false) }
    var valueMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var pendingField by remember(options) { mutableStateOf<WikiFieldOptions?>(null) }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            OutlinedButton(onClick = { fieldMenu = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                val activeField = options.firstOrNull { it.key == filterField }?.label
                Text(filterValue?.let { value ->
                    if (activeField == null) "筛选：$value" else "$activeField · $value"
                } ?: "筛选条件")
            }
            DropdownMenu(expanded = fieldMenu, onDismissRequest = { fieldMenu = false }) {
                DropdownMenuItem(text = { Text("全部") }, onClick = { onFilter(null, null); fieldMenu = false })
                options.filter { it.values.isNotEmpty() }.forEach { option ->
                    DropdownMenuItem(text = { Text(option.label) }, onClick = {
                        pendingField = option; fieldMenu = false; valueMenu = true
                    })
                }
            }
            DropdownMenu(expanded = valueMenu, onDismissRequest = { valueMenu = false }) {
                pendingField?.let { option -> option.values.forEach { value ->
                    DropdownMenuItem(text = { Text(value) }, onClick = { onFilter(option.key, value); valueMenu = false })
                } }
            }
        }
        Box {
            OutlinedButton(onClick = { sortMenu = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(options.firstOrNull { it.key == sortField }?.let {
                    "${it.label} · ${if (descending) "从高到低" else "从低到高"}"
                } ?: "名称排序")
            }
            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                DropdownMenuItem(text = { Text("名称排序") }, onClick = { onSort(null, false); sortMenu = false })
                options.filter(WikiFieldOptions::numeric).forEach { option ->
                    DropdownMenuItem(text = { Text("${option.label} · 从高到低") }, onClick = { onSort(option.key, true); sortMenu = false })
                    DropdownMenuItem(text = { Text("${option.label} · 从低到高") }, onClick = { onSort(option.key, false); sortMenu = false })
                }
            }
        }
        if (filterField != null) TextButton(onClick = { onFilter(null, null) }) { Text("取消筛选") }
    }
}

private data class ComparisonBundle(
    val entries: List<WikiEntry>,
    val learningById: Map<String, WikiCharacterLearningData>,
    val tierById: Map<String, String>,
)

private fun parseNumericValue(raw: String): Pair<Double, String>? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val suffix = if (trimmed.endsWith("%")) "%" else ""
    val numberPart = trimmed.removeSuffix("%").trim().removePrefix("+")
    val parsed = numberPart.toDoubleOrNull() ?: return null
    return parsed to suffix
}

private fun formatDelta(delta: Double, suffix: String): String {
    if (kotlin.math.abs(delta) < 1e-6) return "="
    val rounded = delta.roundToInt()
    val formatted = if (kotlin.math.abs(delta - rounded) < 1e-6) {
        if (rounded > 0) "+$rounded" else "$rounded"
    } else {
        if (delta > 0) "+${"%.1f".format(delta)}" else "${"%.1f".format(delta)}"
    }
    return "$formatted$suffix"
}

@Composable
private fun WikiComparison(repository: WikiRepository, ids: List<String>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val result by produceState<Pair<List<String>, Result<ComparisonBundle>>?>(null, repository, ids, context) {
        value = ids to try {
            Result.success(withContext(Dispatchers.IO) {
                val entries = ids.mapNotNull(repository::entry)
                val learning = entries.filter { it.id.startsWith("characters:") }
                    .associate { it.id to repository.characterLearning(it.id) }
                val profileId = context.getSharedPreferences("personal-view", 0)
                    .getString("profile", "global") ?: "global"
                val tierList = runCatching {
                    PersonalTierList.readProfile(PersonalStore(context).snapshot(), profileId).lists.firstOrNull()?.list
                }.getOrNull()
                val tierMap = if (tierList == null) emptyMap() else entries.mapNotNull { entry ->
                    val tierEntry = tierList.entryFor(entry.id) ?: return@mapNotNull null
                    val label = tierList.tiers.firstOrNull { it.id == tierEntry.tierId }?.label ?: return@mapNotNull null
                    entry.id to label
                }.toMap()
                ComparisonBundle(entries, learning, tierMap)
            })
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Result.failure(error) }
    }
    val current = result?.takeIf { it.first == ids }?.second
    AlertDialog(onDismissRequest = onDismiss, title = { Text("并排对比") }, confirmButton = {
        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("返回浏览") }
    }, text = {
        when {
            current == null -> Text("正在读取…")
            current.isFailure -> Text("暂时无法读取比较资料，请关闭后重试。")
            else -> {
                val bundle = current.getOrThrow()
                val entries = bundle.entries
                if (entries.size < 2) Text("再选择一条同类资料，即可并排查看属性差异。")
                else {
                    val allLabels = entries.flatMap { it.fields.map { field -> field.label } }.distinct()
                    val numericLabels = allLabels.filter { label ->
                        entries.count { entry ->
                            val raw = entry.fields.firstOrNull { it.label == label }?.value.orEmpty()
                            parseNumericValue(raw) != null
                        } >= 2
                    }
                    val otherLabels = allLabels.filterNot { it in numericLabels }
                    val skillNamesById = entries.associate { entry ->
                        entry.id to bundle.learningById[entry.id]?.skills.orEmpty().map { it.name }.toSet()
                    }
                    val sharedSkills = if (skillNamesById.size >= 2 && skillNamesById.values.all { it.isNotEmpty() }) {
                        skillNamesById.values.reduce { acc, set -> acc intersect set }
                    } else emptySet()

                    LazyColumn(
                        modifier = Modifier.heightIn(max = 460.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                entries.forEach { entry ->
                                    Surface(
                                        Modifier.weight(1f),
                                        color = MaterialTheme.colorScheme.secondaryContainer,
                                        shape = RoundedCornerShape(14.dp),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    ) {
                                        Column(
                                            Modifier.padding(10.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                        ) {
                                            WikiImage(entry.imageAsset, entry.title, Modifier.size(48.dp), crop = true)
                                            Text(
                                                entry.title,
                                                style = MaterialTheme.typography.titleSmall,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                maxLines = 2,
                                            )
                                            bundle.tierById[entry.id]?.let { tierLabel ->
                                                EditorialBadge(
                                                    text = "梯度 $tierLabel",
                                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (numericLabels.isNotEmpty()) {
                            item {
                                Text(
                                    "数值与成长差值（以首列为基准）",
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.semantics { heading() },
                                )
                            }
                            numericLabels.forEach { label ->
                                item(key = "num:$label") {
                                    val baseParsed = parseNumericValue(
                                        entries.first().fields.firstOrNull { it.label == label }?.value.orEmpty(),
                                    )
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(label, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            entries.forEachIndexed { index, entry ->
                                                val raw = entry.fields.firstOrNull { it.label == label }?.value?.ifBlank { "—" } ?: "—"
                                                val parsed = parseNumericValue(raw)
                                                val deltaText = if (index > 0 && baseParsed != null && parsed != null) {
                                                    formatDelta(parsed.first - baseParsed.first, parsed.second)
                                                } else null
                                                Surface(
                                                    Modifier.weight(1f),
                                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                                    shape = RoundedCornerShape(10.dp),
                                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                                ) {
                                                    Column(Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                        Text(
                                                            raw,
                                                            style = MaterialTheme.typography.bodyMedium.tabularNumbers(),
                                                            fontWeight = FontWeight.SemiBold,
                                                        )
                                                        if (deltaText != null) {
                                                            val deltaColor = when {
                                                                deltaText.startsWith("+") -> MaterialTheme.colorScheme.primary
                                                                deltaText.startsWith("-") -> MaterialTheme.colorScheme.error
                                                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                                                            }
                                                            Text(
                                                                deltaText,
                                                                style = MaterialTheme.typography.labelSmall.tabularNumbers(),
                                                                color = deltaColor,
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                }
                            }
                        }
                        if (skillNamesById.values.any { it.isNotEmpty() }) {
                            item(key = "skills-diff") {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        "可习得特技 / 战技差集",
                                        color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.semantics { heading() },
                                    )
                                    if (sharedSkills.isNotEmpty()) {
                                        Text(
                                            "共有：${sharedSkills.sorted().joinToString("、")}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    entries.forEach { entry ->
                                        val unique = skillNamesById[entry.id].orEmpty() - sharedSkills
                                        Text(
                                            "${entry.title} 独有：${if (unique.isEmpty()) "无" else unique.sorted().joinToString("、")}",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                }
                            }
                        }
                        otherLabels.forEach { label ->
                            item(key = "txt:$label") {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(label, color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.semantics { heading() },
                                        style = MaterialTheme.typography.labelLarge)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        entries.forEach { entry ->
                                            Surface(
                                                Modifier.weight(1f),
                                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                                shape = RoundedCornerShape(10.dp),
                                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                            ) {
                                                Text(
                                                    entry.fields.firstOrNull { it.label == label }?.value?.ifBlank { "—" } ?: "—",
                                                    Modifier.padding(9.dp),
                                                    style = MaterialTheme.typography.bodySmall,
                                                )
                                            }
                                        }
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                }
            }
        }
    })
}
