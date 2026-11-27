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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fireemblem.companion.content.PlantingFertilizer
import com.fireemblem.companion.content.PlantingGuideData
import com.fireemblem.companion.content.PlantingLocation
import com.fireemblem.companion.content.PlantingMapMarker
import com.fireemblem.companion.content.PlantingPlant
import com.fireemblem.companion.content.PlantingVerificationStatus
import com.fireemblem.companion.content.WikiRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class PlantingBrowseMode(val label: String) {
    LOCATIONS("地点"),
    PLANTS("稀有植物"),
    FERTILIZERS("肥料"),
}

private sealed interface PlantingLoadState {
    data object Loading : PlantingLoadState
    data object Unavailable : PlantingLoadState
    data class Ready(val data: PlantingGuideData) : PlantingLoadState
    data class Failed(val message: String) : PlantingLoadState
}

@Composable
internal fun PlantingGuideScreen(
    repository: WikiRepository?,
    onBack: () -> Unit,
    onOpenMap: (chapterId: String, sourceMapId: String) -> Unit,
    onOpenWiki: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var modeKey by rememberSaveable { mutableStateOf(PlantingBrowseMode.LOCATIONS.name) }
    val mode = PlantingBrowseMode.entries.firstOrNull { it.name == modeKey } ?: PlantingBrowseMode.LOCATIONS
    var selectedPlantId by rememberSaveable { mutableStateOf<String?>(null) }
    var expandedFertilizerIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var retryToken by rememberSaveable { mutableIntStateOf(0) }
    val state by produceState<PlantingLoadState>(PlantingLoadState.Loading, repository, retryToken) {
        value = PlantingLoadState.Loading
        if (repository == null) {
            value = PlantingLoadState.Failed("离线资料暂不可用，请返回后重试。")
        } else {
            try {
                val guide = withContext(Dispatchers.IO) { repository.plantingGuide() }
                value = if (guide == null) PlantingLoadState.Unavailable else PlantingLoadState.Ready(guide)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                value = PlantingLoadState.Failed("种植资料读取失败，请重试。")
            }
        }
    }
    val ready = state is PlantingLoadState.Ready
    val readingKey = "guide/planting/${mode.name}/${query.trim()}/${selectedPlantId ?: "all"}"

    fun goBack() {
        if (mode == PlantingBrowseMode.LOCATIONS && selectedPlantId != null) {
            selectedPlantId = null
        } else {
            onBack()
        }
    }

    BackHandler { goBack() }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("planting-guide-screen")) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = if (maxWidth < 600.dp) 284.dp else 310.dp),
            state = rememberReadingGridState(readingKey, ready),
            modifier = Modifier.fillMaxSize().wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = 1040.dp).testTag("planting-guide-grid"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                PlantingHeader(onBack = ::goBack, isFilteringPlant = selectedPlantId != null)
            }
            when (val current = state) {
                PlantingLoadState.Loading -> item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator()
                        Text("正在读取离线种植资料…", Modifier.padding(start = 14.dp))
                    }
                }
                PlantingLoadState.Unavailable -> item(span = { GridItemSpan(maxLineSpan) }) {
                    PlantingMessage(
                        title = "当前资料版本尚未包含种植攻略",
                        description = "更新应用内离线资料后，即可查看地点、肥料和收获说明。",
                        retry = { retryToken++ },
                    )
                }
                is PlantingLoadState.Failed -> item(span = { GridItemSpan(maxLineSpan) }) {
                    PlantingMessage(current.message, "你的记录没有受到影响。", retry = { retryToken++ })
                }
                is PlantingLoadState.Ready -> {
                    val data = current.data
                    item(span = { GridItemSpan(maxLineSpan) }) { PlantingOverview(data) }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth().testTag("planting-guide-search"),
                            label = { Text("搜索地点、植物或肥料") },
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        )
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        PlantingModeSelector(mode) { next ->
                            modeKey = next.name
                            if (next != PlantingBrowseMode.LOCATIONS) selectedPlantId = null
                        }
                    }
                    if (mode == PlantingBrowseMode.LOCATIONS) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            PlantingPlantSelector(data.plants, selectedPlantId) { selectedPlantId = it }
                        }
                    }
                    val normalizedQuery = query.trim()
                    val filteredLocations = data.locations.filter { location ->
                        (selectedPlantId == null || location.plant.id == selectedPlantId) &&
                            (normalizedQuery.isEmpty() || listOf(
                                location.title, location.plant.title, location.plantDescription,
                            ).any { it.contains(normalizedQuery, ignoreCase = true) })
                    }
                    val filteredPlants = data.plants.filter { plant ->
                        normalizedQuery.isEmpty() || plant.title.contains(normalizedQuery, ignoreCase = true) ||
                            data.locations.any { it.plant.id == plant.id && it.title.contains(normalizedQuery, true) }
                    }
                    val filteredFertilizers = data.fertilizers.filter { fertilizer ->
                        normalizedQuery.isEmpty() || listOf(
                            fertilizer.title, fertilizer.description, fertilizer.acquisitionHint,
                        ).any { it.contains(normalizedQuery, ignoreCase = true) }
                    }
                    val resultCount = when (mode) {
                        PlantingBrowseMode.LOCATIONS -> filteredLocations.size
                        PlantingBrowseMode.PLANTS -> filteredPlants.size
                        PlantingBrowseMode.FERTILIZERS -> filteredFertilizers.size
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            when (mode) {
                                PlantingBrowseMode.LOCATIONS -> "$resultCount 个地点"
                                PlantingBrowseMode.PLANTS -> "$resultCount 种稀有植物"
                                PlantingBrowseMode.FERTILIZERS -> "$resultCount 种肥料"
                            },
                            modifier = Modifier.semantics { heading() },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    if (resultCount == 0) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            PlantingMessage("没有找到匹配的资料", "换个地点、植物或肥料名称试试。")
                        }
                    } else when (mode) {
                        PlantingBrowseMode.LOCATIONS -> items(filteredLocations, key = PlantingLocation::id) { location ->
                            PlantingLocationCard(location, onOpenMap, onOpenWiki)
                        }
                        PlantingBrowseMode.PLANTS -> items(filteredPlants, key = PlantingPlant::id) { plant ->
                            PlantingPlantCard(
                                plant = plant,
                                locationCount = data.locations.count { it.plant.id == plant.id },
                                onShowLocations = {
                                    selectedPlantId = plant.id
                                    modeKey = PlantingBrowseMode.LOCATIONS.name
                                },
                                onOpenWiki = onOpenWiki,
                            )
                        }
                        PlantingBrowseMode.FERTILIZERS -> items(filteredFertilizers, key = PlantingFertilizer::id) { fertilizer ->
                            PlantingFertilizerCard(
                                fertilizer = fertilizer,
                                expanded = fertilizer.id in expandedFertilizerIds,
                                onToggleLocations = {
                                    expandedFertilizerIds = if (fertilizer.id in expandedFertilizerIds) {
                                        expandedFertilizerIds - fertilizer.id
                                    } else {
                                        expandedFertilizerIds + fertilizer.id
                                    }
                                },
                                onOpenMap = onOpenMap,
                                onOpenWiki = onOpenWiki,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlantingHeader(onBack: () -> Unit, isFilteringPlant: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp).testTag("planting-guide-back")) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
            Text(if (isFilteringPlant) "查看全部地点" else "返回攻略", Modifier.padding(start = 8.dp))
        }
        ScreenHeading("种植与收获", "按地点找稀有植物，也可查看肥料参数与收获记录。")
    }
}

@Composable
private fun PlantingOverview(data: PlantingGuideData) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PlantingMetric("收获周期", "${data.harvest.cycleTurns} 回合", Modifier.weight(1f))
                    val yieldStatus = when (data.harvest.yieldCountStatus) {
                        PlantingVerificationStatus.UNVERIFIED -> "待确认"
                        PlantingVerificationStatus.SOURCE_RECORDED -> "来源记录"
                    }
                    PlantingMetric("收获数量", "${data.harvest.yieldCount} 个 · $yieldStatus", Modifier.weight(1f))
                }
                Text("收获次数对应概率（来源数据，判定规则待核实）", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    data.harvestProbabilities.forEach { probability ->
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("第${probability.harvestNumber}次", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer)
                                Text(probability.probabilityText, style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onTertiaryContainer)
                            }
                        }
                    }
                }
            }
        }
        PlantingSourceNote("收获说明", data.harvest.probabilityNote, Icons.Outlined.Info)
        PlantingSourceNote("肥料参数", data.harvest.fertilizerNote, Icons.Outlined.Info)
        Text(data.harvest.usageNote, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PlantingMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun PlantingSourceNote(title: String, text: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(text, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}

@Composable
private fun PlantingModeSelector(selected: PlantingBrowseMode, onSelect: (PlantingBrowseMode) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PlantingBrowseMode.entries.forEach { mode ->
            FilterChip(
                selected = selected == mode,
                onClick = { onSelect(mode) },
                label = { Text(mode.label) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("planting-mode-${mode.name.lowercase()}"),
            )
        }
    }
}

@Composable
private fun PlantingPlantSelector(plants: List<PlantingPlant>, selectedPlantId: String?, onSelect: (String?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("按植物筛选地点", style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = selectedPlantId == null,
                onClick = { onSelect(null) },
                label = { Text("全部植物") },
                modifier = Modifier.heightIn(min = 48.dp).testTag("planting-filter-plant-all"),
            )
            plants.forEach { plant ->
                FilterChip(
                    selected = selectedPlantId == plant.id,
                    onClick = { onSelect(plant.id.takeUnless { selectedPlantId == it }) },
                    label = { Text(plant.title) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("planting-filter-plant-${plant.id}"),
                )
            }
        }
    }
}

@Composable
private fun PlantingLocationCard(
    location: PlantingLocation,
    onOpenMap: (String, String) -> Unit,
    onOpenWiki: (String) -> Unit,
) {
    val plantEntryId = location.plant.itemEntryId
    Card(
        modifier = Modifier.fillMaxWidth().testTag("planting-location-card-${location.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WikiImage(location.plant.imageAsset, "${location.plant.title}图标", Modifier.size(58.dp), crop = false)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(location.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(location.plant.title, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary)
                }
                PlantingProbabilityBadge(location.plantProbabilityText, "地点标注")
            }
            Text(location.plantDescription, style = MaterialTheme.typography.bodyMedium)
            if (plantEntryId != null) {
                TextButton(onClick = { onOpenWiki(plantEntryId) },
                    modifier = Modifier.align(Alignment.Start).heightIn(min = 44.dp)) {
                    Text("查看植物图鉴")
                }
            }
            PlantingMapMarkerButtons(location.mapMarkers, onOpenMap)
            Text("来源：fireemblem.top · 地点第${location.sourceRowNumber}项",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PlantingPlantCard(
    plant: PlantingPlant,
    locationCount: Int,
    onShowLocations: () -> Unit,
    onOpenWiki: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("planting-plant-card-${plant.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                WikiImage(plant.imageAsset, "${plant.title}图标", Modifier.size(62.dp), crop = false)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(plant.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("出现在 $locationCount 个地点", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onShowLocations, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Icon(Icons.Outlined.LocationOn, contentDescription = null)
                    Text("查看地点", Modifier.padding(start = 6.dp))
                }
                plant.itemEntryId?.let { entryId ->
                    OutlinedButton(onClick = { onOpenWiki(entryId) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text("打开图鉴")
                    }
                }
            }
        }
    }
}

@Composable
private fun PlantingFertilizerCard(
    fertilizer: PlantingFertilizer,
    expanded: Boolean,
    onToggleLocations: () -> Unit,
    onOpenMap: (String, String) -> Unit,
    onOpenWiki: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("planting-fertilizer-card-${fertilizer.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WikiImage(fertilizer.imageAsset, "${fertilizer.title}图标", Modifier.size(58.dp), crop = false)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(fertilizer.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("稀有植物参数", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                PlantingProbabilityBadge(fertilizer.parameterText, "肥料参数")
            }
            if (fertilizer.description.isNotBlank()) Text(fertilizer.description, style = MaterialTheme.typography.bodyMedium)
            if (fertilizer.acquisitionHint.isNotBlank()) {
                Text(fertilizer.acquisitionHint, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onToggleLocations, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Icon(Icons.Outlined.Map, contentDescription = null)
                    Text("${if (expanded) "收起" else "查看"}获取地点（${fertilizer.mapMarkers.size}）",
                        Modifier.padding(start = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                fertilizer.itemEntryId?.let { entryId ->
                    OutlinedButton(onClick = { onOpenWiki(entryId) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("图鉴")
                    }
                }
            }
            if (expanded) PlantingMapMarkerButtons(fertilizer.mapMarkers, onOpenMap)
            Text("来源：fireemblem.top · 肥料第${fertilizer.sourceRowNumber}项",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PlantingProbabilityBadge(value: String, label: String) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(horizontal = 9.dp, vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer)
            Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onTertiaryContainer)
        }
    }
}

@Composable
private fun PlantingMapMarkerButtons(markers: List<PlantingMapMarker>, onOpenMap: (String, String) -> Unit) {
    markers.chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { marker ->
                val chapterName = when (marker.chapterId) {
                    "kai" -> "凯伊篇"
                    "salvation" -> "救世篇"
                    else -> "未知篇章"
                }
                OutlinedButton(
                    onClick = { onOpenMap(marker.chapterId, marker.sourceMapId) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        .testTag("planting-map-marker-${marker.sourceMapId}"),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.Outlined.LocationOn, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        "${marker.locationName} · $chapterName",
                        Modifier.padding(start = 5.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun PlantingMessage(title: String, description: String, retry: (() -> Unit)? = null) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
            retry?.let {
                OutlinedButton(onClick = it, modifier = Modifier.heightIn(min = 48.dp)) { Text("重新读取") }
            }
        }
    }
}
