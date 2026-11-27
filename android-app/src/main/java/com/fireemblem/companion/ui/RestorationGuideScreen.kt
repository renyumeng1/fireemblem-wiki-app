package com.fireemblem.companion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.fireemblem.companion.content.RestorationGuideData
import com.fireemblem.companion.content.RestorationMaterial
import com.fireemblem.companion.content.RestorationPlanner
import com.fireemblem.companion.content.WikiRepository
import com.fireemblem.companion.personal.PersonalRevision
import com.fireemblem.companion.personal.PersonalSnapshot
import com.fireemblem.companion.personal.PersonalStore
import com.fireemblem.companion.personal.RestorationProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonPrimitive

private data class RestorationReady(
    val guide: RestorationGuideData,
    val snapshot: PersonalSnapshot,
    val progress: RestorationProgress?,
    val progressError: String?,
    val profileName: String,
)

@Composable
internal fun RestorationGuideScreen(repository: WikiRepository?, onBack: () -> Unit, onOpenWiki: (String) -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { PersonalStore(context) }
    val scope = rememberCoroutineScope()
    val profileId = remember(context) { context.getSharedPreferences("personal-view", 0).getString("profile", "global") ?: "global" }
    var loaded by remember(repository, profileId) { mutableStateOf<RestorationReady?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var mode by rememberSaveable { mutableStateOf("locations") }
    var query by rememberSaveable { mutableStateOf("") }
    var region by rememberSaveable { mutableStateOf<String?>(null) }
    var onlyPending by rememberSaveable { mutableStateOf(false) }
    var editingMaterialId by rememberSaveable { mutableStateOf<String?>(null) }
    var stockText by rememberSaveable { mutableStateOf("") }
    var showSource by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(repository, profileId, retry) {
        loaded = null
        loadError = null
        try {
            loaded = withContext(Dispatchers.IO) {
                val guide = requireNotNull(repository?.restorationGuide()) { "当前离线资料尚未包含复兴清单，请更新资料后重试。" }
                val snapshot = store.snapshot()
                val personal = runCatching {
                    RestorationProgress.read(snapshot, profileId).also {
                        RestorationPlanner.summarize(guide, it.inventory, it.completedLocations)
                    }
                }
                val profileName = if (profileId == "global") "通用记录" else snapshot.heads()[profileId]?.singleOrNull()
                    ?.takeUnless { it.deleted }?.payload?.get("name")?.jsonPrimitive?.content ?: "当前个人存档"
                RestorationReady(guide, snapshot, personal.getOrNull(), personal.exceptionOrNull()?.message, profileName)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            loadError = error.message ?: "复兴资料读取失败，请重试。"
        }
    }

    fun save(progress: RestorationProgress) {
        val current = loaded ?: return
        if (saving || current.progress == null) return
        val entityId = RestorationProgress.entityId(profileId)
        val parents = current.snapshot.heads()[entityId].orEmpty().map(PersonalRevision::revisionId).toSet()
        saving = true
        message = null
        scope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) {
                    store.edit(entityId, "progress", progress.payload(), expectedParents = parents)
                }
                loaded = current.copy(snapshot = snapshot, progress = progress)
                editingMaterialId = null
                message = "已保存到${current.profileName}。"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message = "未能保存，原记录仍保留。请重新读取后再试。"
                retry++
            } finally {
                saving = false
            }
        }
    }

    BackHandler(onBack = onBack)
    val current = loaded
    val progress = current?.progress
    val balances = remember(current?.guide, progress) {
        if (current != null && progress != null) RestorationPlanner.summarize(current.guide, progress.inventory, progress.completedLocations)
        else emptyList()
    }
    val materialById = remember(current?.guide) { current?.guide?.materials.orEmpty().associateBy { it.id } }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (mode == "materials") 170.dp else 300.dp),
        state = rememberReadingGridState("restoration/$profileId/$mode/$query/$region/$onlyPending", current != null),
        modifier = Modifier.fillMaxSize().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 1040.dp)
            .testTag("restoration-grid"),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, null)
                    Text("返回攻略")
                }
                ScreenHeading("第3部复兴", "按地点备齐材料，记录完成进度。")
            }
        }
        if (current == null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    if (loadError == null) CircularProgressIndicator() else {
                        Text(requireNotNull(loadError))
                        OutlinedButton(onClick = { retry++ }) { Text("重试") }
                    }
                }
            }
        } else {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(current.profileName, style = MaterialTheme.typography.titleMedium)
                        if (progress != null) {
                            val completed = current.guide.locations.count { it.id in progress.completedLocations }
                            Text("已完成 $completed / ${current.guide.locations.size} · ${balances.count { it.missing > 0 }} 种材料待备齐",
                                modifier = Modifier.testTag("restoration-summary"))
                        } else Text(current.progressError ?: "清单无法读取，请到个人存档检查；暂不计算缺口。", color = MaterialTheme.colorScheme.error)
                        Text("库存填写当前持有量。标记完成不自动扣库存，请按游戏实际数量更新。", style = MaterialTheme.typography.bodySmall)
                        message?.let { Text(it, modifier = Modifier.testTag("restoration-message")) }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(mode == "locations", { mode = "locations" }, modifier = Modifier.testTag("restoration-mode-locations"), label = { Text("复兴地点") },
                            leadingIcon = { Icon(Icons.Outlined.LocationOn, null) })
                        FilterChip(mode == "materials", { mode = "materials" }, modifier = Modifier.testTag("restoration-mode-materials"), label = { Text("材料缺口") },
                            leadingIcon = { Icon(Icons.Outlined.Inventory2, null) })
                    }
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().testTag("restoration-search"),
                        label = { Text("搜索地点或材料") }, singleLine = true)
                    FilterChip(onlyPending, { onlyPending = !onlyPending }, enabled = progress != null,
                        label = { Text(if (mode == "locations") "只看未完成" else "只看缺少材料") })
                    if (mode == "locations") Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(region == null, { region = null }, label = { Text("全部地区") })
                        current.guide.locations.map { it.region }.distinct().forEach { name ->
                            FilterChip(region == name, { region = name }, label = { Text(name) })
                        }
                    }
                }
            }
            if (mode == "locations") {
                val locations = current.guide.locations.filter { location ->
                    (region == null || location.region == region) && (!onlyPending || progress == null || location.id !in progress.completedLocations) &&
                        (query.isBlank() || listOf(location.title, location.region, location.evidence).any { it.contains(query.trim(), true) })
                }
                if (locations.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Text("没有匹配的复兴地点。") }
                items(locations, key = { it.id }) { location ->
                    val completed = progress?.completedLocations?.contains(location.id)
                    Card(Modifier.fillMaxWidth().testTag("restoration-location:${location.id}")) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(location.title, style = MaterialTheme.typography.titleMedium)
                            Text(location.region, style = MaterialTheme.typography.labelMedium)
                            location.requirements.forEach { requirement ->
                                val material = materialById.getValue(requirement.materialId)
                                RestorationMaterialLink(material, onOpenWiki) {
                                    Text("需要 ${requirement.quantity}")
                                }
                            }
                            OutlinedButton(
                                onClick = {
                                    if (progress != null) save(progress.copy(completedLocations = if (completed == true)
                                        progress.completedLocations - location.id else progress.completedLocations + location.id))
                                }, enabled = progress != null && !saving, modifier = Modifier.fillMaxWidth().testTag("restoration-complete:${location.id}"),
                            ) {
                                Icon(Icons.Outlined.CheckCircleOutline, null, Modifier.size(20.dp))
                                Text(if (completed == true) "已完成 · 撤回" else "标记完成")
                            }
                            Text("来源第${location.sourceRow}行", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            } else {
                val materials = current.guide.materials.filter { material ->
                    (query.isBlank() || material.title.contains(query.trim(), true)) &&
                        (!onlyPending || progress == null || balances.any { it.material.id == material.id && it.missing > 0 })
                }
                if (materials.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Text("没有匹配的材料。") }
                items(materials, key = { it.id }) { material ->
                    val balance = balances.firstOrNull { it.material.id == material.id }
                    Card(Modifier.fillMaxWidth().testTag("restoration-material:${material.id}")) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            RestorationMaterialLink(material, onOpenWiki)
                            if (balance != null) {
                                Text("待用 ${balance.required} · 持有 ${balance.owned}", style = MaterialTheme.typography.bodySmall)
                                Text(if (balance.missing > 0) "还缺 ${balance.missing}" else "已备齐",
                                    color = if (balance.missing > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                            } else Text("缺口暂不可计算")
                            OutlinedButton(onClick = {
                                editingMaterialId = material.id
                                stockText = (progress?.inventory?.get(material.id) ?: 0).toString()
                            }, enabled = progress != null && !saving, modifier = Modifier.fillMaxWidth().testTag("restoration-edit:${material.id}")) { Text("填写库存") }
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    TextButton(onClick = { showSource = !showSource }) { Text("来源与地图访问说明") }
                    if (showSource) {
                        Text("${current.guide.sourceSheetName} · 修订 ${current.guide.sourceRevision}")
                        Text(current.guide.accessNote)
                        Text("访问说明不代表每个复兴地点的解锁条件。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    materialById[editingMaterialId]?.let { material ->
        val quantity = stockText.toIntOrNull()?.takeIf { it in 0..1_000_000 }
        AlertDialog(onDismissRequest = { if (!saving) editingMaterialId = null },
            title = { Text("${material.title} · 当前库存") },
            text = {
                OutlinedTextField(stockText, { stockText = it }, singleLine = true,
                    label = { Text("持有数量") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = quantity == null, supportingText = { Text("输入0至1000000；不会修改游戏内物品。") },
                    modifier = Modifier.testTag("restoration-stock-input"))
            }, confirmButton = {
                TextButton(enabled = quantity != null && progress != null && !saving, onClick = {
                    if (quantity != null && progress != null) save(progress.copy(inventory = progress.inventory + (material.id to quantity)))
                }) { Text("保存库存") }
            }, dismissButton = { TextButton(onClick = { editingMaterialId = null }, enabled = !saving) { Text("取消") } })
    }
}

@Composable
private fun RestorationMaterialLink(material: RestorationMaterial, onOpenWiki: (String) -> Unit, content: @Composable () -> Unit = {}) {
    val itemEntryId = material.itemEntryId
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WikiImage(material.imageAsset, "${material.title}图标", Modifier.size(40.dp), crop = false)
        Column(Modifier.weight(1f)) {
            if (itemEntryId != null) TextButton(onClick = { onOpenWiki(itemEntryId) },
                contentPadding = PaddingValues(horizontal = 0.dp), modifier = Modifier.heightIn(min = 48.dp)) { Text(material.title) }
            else {
                Text(material.title)
                Text("道具资料待核实", style = MaterialTheme.typography.labelSmall)
            }
            content()
        }
    }
}
