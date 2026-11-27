package com.fireemblem.companion.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.fireemblem.companion.maps.MapChapter
import com.fireemblem.companion.maps.MapGraph
import com.fireemblem.companion.maps.MapImageExporter
import com.fireemblem.companion.maps.MapPoint
import com.fireemblem.companion.maps.MapRepository
import com.fireemblem.companion.maps.MapRoute
import com.fireemblem.companion.maps.MapRoutes
import com.fireemblem.companion.personal.PersonalRevision
import com.fireemblem.companion.personal.PersonalStore
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.min
import kotlin.math.roundToInt

private data class LoadedMap(val chapterId: String, val chapters: List<MapChapter>, val points: List<MapPoint>,
    val graph: MapGraph, val types: List<Pair<String, String>>, val width: Double, val height: Double,
    val terrainSha256: String)

private data class PendingMapExport(
    val image: ImageBitmap,
    val data: LoadedMap,
    val points: List<MapPoint>,
    val route: MapRoute?,
    val visited: Set<String>,
)

private data class LoadedTerrain(val sha256: String, val attempt: Int, val image: ImageBitmap?)

@Composable
internal fun WorldMapScreen(
    onBack: () -> Unit,
    initialChapterId: String? = null,
    initialPointId: String? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val personal = remember(context) { PersonalStore(context) }
    val profile = context.getSharedPreferences("personal-view", 0).getString("profile", "global") ?: "global"
    var chapter by rememberSaveable(initialChapterId) { mutableStateOf(initialChapterId ?: "kai") }
    var query by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable(chapter) { mutableStateOf<String?>(null) }
    var selected by rememberSaveable(chapter) { mutableStateOf<String?>(null) }
    var pointStatus by remember(selected, profile) { mutableStateOf<String?>(null) }
    var progressBusy by remember { mutableStateOf(false) }
    var start by rememberSaveable { mutableStateOf<String?>(null) }
    var end by rememberSaveable { mutableStateOf<String?>(null) }
    var fame by rememberSaveable { mutableStateOf("1") }
    var personalRecords by remember { mutableStateOf<Map<String, List<PersonalRevision>>>(emptyMap()) }
    var status by remember { mutableStateOf<String?>(null) }
    var routeStatus by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableStateOf(0) }
    var pendingExport by remember { mutableStateOf<PendingMapExport?>(null) }
    var endpointPicker by rememberSaveable(chapter) { mutableStateOf<String?>(null) }
    var phonePanel by rememberSaveable(chapter) { mutableStateOf("map") }
    var savedRoutesExpanded by rememberSaveable { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val export = pendingExport
        pendingExport = null
        if (uri != null && export != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val bitmap = MapImageExporter.render(
                        export.image.asAndroidBitmap(), export.data.width, export.data.height,
                        export.points, export.data.graph, export.route, export.visited,
                    )
                    try {
                        val output = context.contentResolver.openOutputStream(uri)
                            ?: throw java.io.IOException("无法创建图片文件")
                        output.use { stream ->
                            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "图片编码失败" }
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
                routeStatus = "地图图片已保存。"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                routeStatus = "地图图片未能保存，请换一个位置后重试。"
            }
        }
    }
    val loaded by produceState<Pair<String, Result<LoadedMap>>?>(null, chapter, retry) {
        value = chapter to try {
            Result.success(withContext(Dispatchers.IO) {
                MapRepository.open(context).use { repository -> LoadedMap(chapter, repository.chapters, repository.points(chapter),
                    repository.graph(chapter), repository.types(), repository.width, repository.height, repository.terrainSha256) }
            })
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Result.failure(error) }
    }
    val expectedTerrainHash = loaded?.takeIf { it.first == chapter }?.second?.getOrNull()?.terrainSha256
    val terrain by produceState<LoadedTerrain?>(null, context, expectedTerrainHash, retry) {
        if (expectedTerrainHash == null) return@produceState
        if (value?.sha256 == expectedTerrainHash && value?.attempt == retry && value?.image != null) return@produceState
        val decoded = withContext(Dispatchers.IO) {
            try {
                val bytes = context.assets.open("world-map/terrain.webp").use { it.readBytes() }
                val checksum = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it) }
                check(checksum == expectedTerrainHash) { "地图底图与地点坐标不匹配" }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = 2 })?.asImageBitmap()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }
        value = LoadedTerrain(expectedTerrainHash, retry, decoded)
    }
    val currentTerrain = terrain?.takeIf { it.sha256 == expectedTerrainHash && it.attempt == retry }
    val image = currentTerrain?.image
    val terrainLoading = currentTerrain == null
    LaunchedEffect(profile, personal, retry) {
        try { personalRecords = withContext(Dispatchers.IO) { personal.snapshot().heads() } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { status = "无法读取探索记录，请先到“我的”检查。" }
    }
    val data = loaded?.takeIf { it.first == chapter }?.second?.getOrNull()
    LaunchedEffect(data?.chapterId, initialChapterId, initialPointId) {
        if (data != null && data.chapterId == initialChapterId && initialPointId != null) {
            query = ""
            phonePanel = "map"
            if (data.points.any { it.id == initialPointId }) {
                selected = initialPointId
            } else {
                status = "当前离线地图没有找到这条来源标记。"
            }
        }
    }
    val roadLayer by produceState<Pair<String, Result<ImageBitmap>>?>(null, data?.chapterId, data?.graph, image) {
        val currentData = data
        val currentImage = image
        value = if (currentData == null || currentImage == null) {
            null
        } else {
            currentData.chapterId to try {
                Result.success(withContext(Dispatchers.Default) {
                    MapImageExporter.renderRoadLayer(
                        currentData.graph,
                        currentData.width,
                        currentData.height,
                        currentImage.width,
                        currentImage.height,
                    ).asImageBitmap()
                })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
        }
    }
    val currentRoadLayer = roadLayer?.takeIf { it.first == data?.chapterId }?.second
    val visited = personalRecords.values.mapNotNull { it.singleOrNull() }.filter {
        !it.deleted && it.kind == "progress" && it.payload["profileId"]?.jsonPrimitive?.content == profile &&
            it.payload["completed"]?.jsonPrimitive?.booleanOrNull == true
    }.mapNotNull { it.payload["pointId"]?.jsonPrimitive?.content }.toSet()
    val discoveries = data?.points.orEmpty().filter { it.id in visited }.map(MapPoint::nativeName).toSet()
    val routeKey = listOf(chapter, start, end, fame, discoveries.sorted().joinToString("|"))
    val route by produceState<Pair<List<String?>, Result<MapRoute?>>?>(null, data, routeKey) {
        value = null
        if (data != null && start != null && end != null) {
            val from = data.points.firstOrNull { it.id == start }?.node
            val to = data.points.firstOrNull { it.id == end }?.node
            val level = fame.toIntOrNull()
            if (from != null && to != null && level != null && level in 0..99) {
                value = routeKey to try {
                    val job = currentCoroutineContext()
                    Result.success(withContext(Dispatchers.Default) { MapRoutes.shortest(data.graph, from, to, level, discoveries,
                        isCancelled = { !job.isActive }) })
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { Result.failure(error) }
            }
        }
    }
    val currentRoute = route?.takeIf { it.first == routeKey }?.second?.getOrNull()
    val visible by produceState(data?.points.orEmpty(), data, query, type) {
        val current = data ?: return@produceState
        val term = query.trim()
        if (term.isEmpty() && type == null) {
            value = current.points
        } else {
            delay(180)
            try {
                value = withContext(Dispatchers.IO) {
                    MapRepository.open(context).use { it.points(current.chapterId, term, type) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { value = emptyList(); status = "地点搜索失败，请重试。" }
        }
    }
    val savedRoutes = personalRecords.values.mapNotNull { it.singleOrNull() }.filter {
        !it.deleted && it.kind == "map-route" && it.payload["profileId"]?.jsonPrimitive?.content == profile
    }.sortedByDescending(PersonalRevision::modifiedAt)
    fun selectChapter(chapterId: String) {
        chapter = chapterId
        start = null
        end = null
        selected = null
        type = null
        fame = "1"
        routeStatus = null
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val expandedLayout = maxWidth >= 840.dp
        val phoneSearchOnly = maxWidth < 840.dp && phonePanel == "search"
        val mapHeight = when {
            expandedLayout -> 460.dp
            maxWidth >= 600.dp -> 400.dp
            else -> 320.dp
        }
        val pointColumns = when {
            maxWidth >= 960.dp -> 3
            maxWidth >= 640.dp -> 2
            else -> 1
        }
        LazyColumn(
            Modifier.fillMaxSize().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 1120.dp),
            state = rememberReadingListState("world-map:$profile:$chapter:$phonePanel:$query:$type", data != null),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = if (maxWidth < 600.dp) 16.dp else 24.dp,
                top = 16.dp,
                end = if (maxWidth < 600.dp) 16.dp else 24.dp,
                bottom = 112.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
        item { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("返回攻略") }
            ScreenHeading("世界地图", "离线查看篇章地点、探索进度与可行路线。")
            if (data != null) {
                Text("选择篇章", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    data.chapters.forEach { item ->
                        FilterChip(selected = chapter == item.id, onClick = { selectChapter(item.id) },
                            modifier = Modifier.heightIn(min = 48.dp), label = { Text(item.title) })
                    }
                }
            }
            if (!expandedLayout) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(selected = phonePanel == "map", onClick = { phonePanel = "map" },
                    modifier = Modifier.heightIn(min = 48.dp), label = { Text("地图与路线") })
                FilterChip(selected = phonePanel == "search", onClick = { phonePanel = "search" },
                    modifier = Modifier.heightIn(min = 48.dp), label = { Text("地点搜索") })
            }
            OutlinedTextField(
                query,
                { query = it },
                Modifier.fillMaxWidth(),
                label = { Text("搜索地点、任务或物品") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = if (query.isNotEmpty()) ({
                    IconButton(onClick = { query = "" }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.Close, contentDescription = "清除搜索")
                    }
                }) else null,
            )
            if (data != null) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = type == null, onClick = { type = null },
                        modifier = Modifier.heightIn(min = 48.dp), label = { Text("全部地点") })
                    data.types.filter { option -> data.points.any { it.type == option.first } }.forEach { option ->
                        FilterChip(selected = type == option.first, onClick = { type = option.first },
                            modifier = Modifier.heightIn(min = 48.dp), label = { Text(option.second) })
                    }
                }
            }
            status?.let { message ->
                Surface(color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) {
                    Text(message, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        } }
        when {
            loaded?.first != chapter -> item { LoadingPackage() }
            data == null -> item { FailedPackage("地图资料暂时无法读取。", onRetry = { retry++ }) }
            else -> {
                if (!phoneSearchOnly) item {
                    val mapContent: @Composable (Modifier) -> Unit = { modifier ->
                        Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("${data.chapters.first { it.id == chapter }.title} · 探索进度",
                                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                Text("已探索 ${data.points.count { it.id in visited }}/${data.points.size}",
                                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
                            }
                            LinearProgressIndicator(
                                progress = { if (data.points.isEmpty()) 0f else data.points.count { it.id in visited }.toFloat() / data.points.size },
                                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "本篇章探索进度" },
                            )
                            if (terrainLoading) {
                                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("正在读取地图底图…", style = MaterialTheme.typography.bodyMedium)
                                    LinearProgressIndicator(Modifier.fillMaxWidth())
                                }
                            } else if (image == null) {
                                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Outlined.Map, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                            Text("底图暂时无法显示，仍可使用下方地点列表。", style = MaterialTheme.typography.bodyMedium)
                                        }
                                        OutlinedButton(onClick = { retry++ }, modifier = Modifier.heightIn(min = 48.dp)) { Text("重新加载底图") }
                                    }
                                }
                            } else InteractiveMap(
                                image = image,
                                roadLayer = currentRoadLayer?.getOrNull(),
                                data = data,
                                points = visible,
                                route = currentRoute,
                                selected = selected,
                                visited = visited,
                                mapHeight = mapHeight,
                                roadLayerError = currentRoadLayer?.isFailure == true,
                                onSelect = { selected = it },
                            )
                        }
                    }
                    val routeContent: @Composable (Modifier) -> Unit = { modifier ->
                    Card(
                        modifier = modifier,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Outlined.Route, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Text("路线规划", style = MaterialTheme.typography.titleMedium)
                            }
                            Text("选择起终点即可预览行程，也可点开地图上的地点设置。",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            RouteEndpoint("起点", data.points.firstOrNull { it.id == start }?.title,
                                Modifier.fillMaxWidth()) { endpointPicker = "start" }
                            RouteEndpoint("终点", data.points.firstOrNull { it.id == end }?.title,
                                Modifier.fillMaxWidth()) { endpointPicker = "end" }
                            OutlinedTextField(fame, { if (it.length <= 2 && it.all(Char::isDigit)) fame = it },
                                modifier = Modifier.fillMaxWidth(), label = { Text("当前名声（0–99）") }, singleLine = true,
                                isError = fame.toIntOrNull()?.let { it !in 0..99 } != false,
                                supportingText = if (fame.toIntOrNull()?.let { it in 0..99 } != true) ({ Text("请填写 0–99 的名声。") }) else null,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                            Text("路线预览参考名声、道路方向与已探索地点；剧情封锁和临时事件可能影响实际行程。",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (currentRoute != null) {
                                Surface(color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)) {
                                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text("路线已计算", style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer)
                                        Text("约 %.1f 格 · %d 回合 · %.0f 小时".format(currentRoute.cells, currentRoute.turns, currentRoute.hours),
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer)
                                    }
                                }
                            } else if (start != null && end != null) {
                                val routeResult = route?.takeIf { it.first == routeKey }?.second
                                Text(when {
                                    fame.toIntOrNull()?.let { it in 0..99 } != true -> "填写有效名声后计算路线。"
                                    data.points.none { it.id == start && it.node != null } ||
                                        data.points.none { it.id == end && it.node != null } -> "端点没有可用路网，请重新选择。"
                                    routeResult == null -> "正在计算路线…"
                                    routeResult.isFailure -> "路线计算失败，请重新选择端点后重试。"
                                    else -> "当前条件下未找到路线，可调整名声或起终点。"
                                }, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (currentRoute != null) OutlinedButton(onClick = {
                                    val from = requireNotNull(start); val to = requireNotNull(end)
                                    scope.launch {
                                        try {
                                            personalRecords = withContext(Dispatchers.IO) { personal.edit(UUID.randomUUID().toString(), "map-route", buildJsonObject {
                                                put("name", "${data.points.first { it.id == from }.title} → ${data.points.first { it.id == to }.title}")
                                                put("profileId", profile); put("chapter", chapter); put("startPoint", from); put("endPoint", to); put("fame", fame)
                                            }, expectedParents = emptySet()).heads() }
                                            routeStatus = "路线端点已保存，可随个人备份同步。"
                                        } catch (cancelled: CancellationException) { throw cancelled }
                                        catch (_: Exception) { routeStatus = "路线未能保存，请重试。" }
                                    }
                                }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("保存路线") }
                                if (image != null) OutlinedButton(onClick = {
                                    pendingExport = PendingMapExport(image, data, visible, currentRoute, visited)
                                    exportLauncher.launch("火纹地图-${data.chapters.first { it.id == chapter }.title}.png")
                                }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("导出地图") }
                            }
                            routeStatus?.let { message ->
                                Text(message, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (start != null || end != null) TextButton(onClick = { start = null; end = null },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("清除起终点") }
                            if (savedRoutes.isNotEmpty()) {
                                TextButton(onClick = { savedRoutesExpanded = !savedRoutesExpanded },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                    Text("${if (savedRoutesExpanded) "收起" else "查看"}已保存路线（${savedRoutes.size}）")
                                }
                                if (savedRoutesExpanded) savedRoutes.forEach { saved ->
                                    val routeChapter = saved.payload["chapter"]?.jsonPrimitive?.content
                                    val routeStart = saved.payload["startPoint"]?.jsonPrimitive?.content
                                    val routeEnd = saved.payload["endPoint"]?.jsonPrimitive?.content
                                    OutlinedButton(
                                        enabled = routeChapter != null && data.chapters.any { it.id == routeChapter } &&
                                            routeStart != null && routeEnd != null,
                                        onClick = {
                                            selectChapter(requireNotNull(routeChapter))
                                            start = routeStart; end = routeEnd
                                            fame = saved.payload["fame"]?.jsonPrimitive?.content ?: "1"
                                            query = ""
                                        }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                    ) { Text(saved.payload["name"]?.jsonPrimitive?.content ?: "恢复路线") }
                                }
                            }
                        }
                    }
                    }
                    if (expandedLayout) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        mapContent(Modifier.weight(1.6f))
                        routeContent(Modifier.weight(1f))
                    } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        mapContent(Modifier.fillMaxWidth())
                        routeContent(Modifier.fillMaxWidth())
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("地点列表 · ${visible.size}", style = MaterialTheme.typography.titleMedium)
                        Text("点开地点查看任务、设置路线或标记探索进度。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (visible.isEmpty()) item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Text("没有找到符合条件的地点。", Modifier.fillMaxWidth().padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(visible.chunked(pointColumns), key = { row -> row.first().id }) { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { point ->
                            MapPointCard(point, point.id in visited, Modifier.weight(1f)) { selected = point.id }
                        }
                        repeat(pointColumns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        }
    }
    endpointPicker?.let { endpoint ->
        data?.let { current ->
            MapEndpointPicker(if (endpoint == "start") "选择起点" else "选择终点", current.points,
                onDismiss = { endpointPicker = null }, onSelect = { point ->
                    if (endpoint == "start") start = point.id else end = point.id
                    endpointPicker = null
                })
        }
    }
    data?.points?.firstOrNull { it.id == selected }?.let { point ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text(point.title) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) {
                    Text(point.typeTitle, Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(if (point.id in visited) "探索进度：已探索" else "探索进度：未标记",
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
                if (point.node == null) Text("此地点尚未关联路网，暂不能用作路线端点。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = point.node != null, onClick = { start = point.id; selected = null },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("设为起点") }
                    OutlinedButton(enabled = point.node != null, onClick = { end = point.id; selected = null },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("设为终点") }
                }
                Button(enabled = !progressBusy, onClick = {
                    val entity = UUID.nameUUIDFromBytes("map:$profile:${point.id}".toByteArray()).toString()
                    val parents = personalRecords[entity].orEmpty().map(PersonalRevision::revisionId).toSet()
                    if (parents.size > 1) {
                        pointStatus = "探索记录存在冲突，请到“我的”选择保留版本。"
                        status = pointStatus
                    } else {
                        progressBusy = true
                        scope.launch {
                        try {
                            personalRecords = withContext(Dispatchers.IO) { personal.edit(entity, "progress", buildJsonObject {
                                put("profileId", profile); put("pointId", point.id); put("name", point.title); put("completed", point.id !in visited)
                            }, expectedParents = parents).heads() }
                            pointStatus = "探索标记已保存。"
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) {
                            pointStatus = "标记未能保存，原记录仍保留。请重新打开地图后重试。"
                            status = pointStatus
                        } finally { progressBusy = false }
                        }
                    }
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(if (point.id in visited) "取消探索标记" else "标记已探索")
                }
                pointStatus?.let { message -> Text(message, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text("地点说明", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(point.description.ifBlank { "暂无已核实的地点说明。" })
                if (point.quests.isNotBlank()) {
                    Text("相关任务", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(point.quests)
                }
                Text("资料来源：fireemblem.top", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }, confirmButton = { TextButton(onClick = { selected = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("关闭") } })
    }
}

@Composable
private fun InteractiveMap(image: ImageBitmap, roadLayer: ImageBitmap?, data: LoadedMap,
    points: List<MapPoint>, route: MapRoute?, selected: String?, visited: Set<String>, mapHeight: Dp,
    roadLayerError: Boolean, onSelect: (String) -> Unit,
) {
    var zoom by rememberSaveable(data.chapterId) { mutableStateOf(1f) }
    var pan by remember(data.chapterId) { mutableStateOf(Offset.Zero) }
    val routeColor = MaterialTheme.colorScheme.secondary
    val roadNetworkColor = Color(0xFF314055)
    val exploredColor = MaterialTheme.colorScheme.tertiary
    val locationColor = MaterialTheme.colorScheme.primary
    val selectedPointColor = MaterialTheme.colorScheme.onSurface
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)) {
        Column {
            Box(Modifier.fillMaxWidth().height(mapHeight).clipToBounds()) {
                Canvas(Modifier.fillMaxSize().semantics {
                    contentDescription = if (roadLayer == null) "世界地图，正在加载游戏道路，可拖动缩放，地点也可从下方列表选择"
                        else "世界地图，已显示游戏道路，可拖动缩放，地点也可从下方列表选择"
                }.pointerInput(data.chapterId) {
                    detectTransformGestures { center, change, scale, _ ->
                        val next = (zoom * scale).coerceIn(1f, 10f)
                        val relative = center - Offset(size.width / 2f, size.height / 2f)
                        pan = relative - (relative - pan) * (next / zoom) + change
                        zoom = next
                    }
                }.pointerInput(points, zoom, pan) {
                    detectTapGestures { tap ->
                        val scale = min(size.width / data.width, size.height / data.height).toFloat() * zoom
                        val origin = Offset((size.width - data.width.toFloat() * scale) / 2, (size.height - data.height.toFloat() * scale) / 2) + pan
                        points.minByOrNull { (Offset(it.position.x.toFloat() * scale, it.position.y.toFloat() * scale) + origin - tap).getDistance() }
                            ?.takeIf { (Offset(it.position.x.toFloat() * scale, it.position.y.toFloat() * scale) + origin - tap).getDistance() <= 32.dp.toPx() }
                            ?.let { onSelect(it.id) }
                    }
                }) {
                    val scale = min(size.width / data.width, size.height / data.height).toFloat() * zoom
                    val origin = Offset((size.width - data.width.toFloat() * scale) / 2, (size.height - data.height.toFloat() * scale) / 2) + pan
                    drawImage(image, dstOffset = IntOffset(origin.x.roundToInt(), origin.y.roundToInt()),
                        dstSize = IntSize((data.width * scale).roundToInt().coerceAtLeast(1), (data.height * scale).roundToInt().coerceAtLeast(1)))
                    roadLayer?.let { roads ->
                        drawImage(roads, dstOffset = IntOffset(origin.x.roundToInt(), origin.y.roundToInt()),
                            dstSize = IntSize((data.width * scale).roundToInt().coerceAtLeast(1),
                                (data.height * scale).roundToInt().coerceAtLeast(1)))
                    }
                    route?.nodes?.zipWithNext()?.forEach { (from, to) ->
                        val first = data.graph.nodes[from]; val second = data.graph.nodes[to]
                        val start = origin + Offset(first.x.toFloat() * scale, first.y.toFloat() * scale)
                        val end = origin + Offset(second.x.toFloat() * scale, second.y.toFloat() * scale)
                        drawLine(Color.White.copy(alpha = 0.92f), start, end, strokeWidth = 8.dp.toPx())
                        drawLine(routeColor, start, end, strokeWidth = 4.dp.toPx())
                    }
                    points.forEach { point ->
                        val at = origin + Offset(point.position.x.toFloat() * scale, point.position.y.toFloat() * scale)
                        val radius = when {
                            point.id == selected -> 8.dp.toPx()
                            point.id in visited -> 6.dp.toPx()
                            else -> 4.dp.toPx()
                        }
                        drawCircle(Color.White, radius + 2.dp.toPx(), at)
                        drawCircle(if (point.id in visited) exploredColor else locationColor, radius, at)
                        if (point.id in visited) drawCircle(Color.White, 2.dp.toPx(), at)
                        if (point.id == selected) drawCircle(selectedPointColor, 2.dp.toPx(), at)
                    }
                }
            }
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("拖动浏览 · 双指缩放 · 点按地点查看详情", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (roadLayerError) Text("道路网络暂时无法显示，地点与路线规划仍可使用。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                else if (roadLayer == null) Text("正在绘制游戏道路…", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(enabled = zoom > 1f, onClick = { zoom = (zoom / 1.5f).coerceAtLeast(1f) },
                        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "缩小地图" }) {
                        Icon(Icons.Outlined.ZoomOut, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("缩小")
                    }
                    OutlinedButton(enabled = zoom < 10f, onClick = { zoom = (zoom * 1.5f).coerceAtMost(10f) },
                        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "放大地图" }) {
                        Icon(Icons.Outlined.ZoomIn, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("放大")
                    }
                    TextButton(onClick = { zoom = 1f; pan = Offset.Zero },
                        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "重置地图位置和缩放" }) {
                        Icon(Icons.Outlined.CenterFocusStrong, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("复位")
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MapLegendItem(roadNetworkColor, "游戏路网")
                    MapLegendItem(routeColor, "规划路线")
                    MapLegendItem(exploredColor, "已探索")
                    MapLegendItem(locationColor, "未标记地点")
                }
            }
        }
    }
}

@Composable
private fun MapLegendItem(color: Color, label: String, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(Modifier.size(10.dp), color = color, shape = androidx.compose.foundation.shape.CircleShape) {}
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MapPointCard(point: MapPoint, explored: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier.heightIn(min = 100.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp))
                Text(point.title, style = MaterialTheme.typography.titleSmall, maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            Text(point.typeTitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(if (explored) "已探索 · 查看详情" else "未标记 · 查看详情",
                style = MaterialTheme.typography.labelMedium,
                color = if (explored) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun RouteEndpoint(label: String, value: String?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier.heightIn(min = 56.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(value ?: "选择$label", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text("选择", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun MapEndpointPicker(title: String, points: List<MapPoint>, onDismiss: () -> Unit, onSelect: (MapPoint) -> Unit) {
    var query by rememberSaveable(title) { mutableStateOf("") }
    val matches = remember(points, query) {
        points.filter { it.node != null && (query.isBlank() || it.title.contains(query.trim(), ignoreCase = true)) }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("搜索可通行地点") }, singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) })
            Text("仅列出已关联路网的地点。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (matches.isEmpty()) Text("没有找到可用地点，请换个名称搜索。")
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(matches, key = MapPoint::id) { point ->
                    TextButton(onClick = { onSelect(point) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(point.title, style = MaterialTheme.typography.titleSmall)
                            Text(point.typeTitle, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } })
}
