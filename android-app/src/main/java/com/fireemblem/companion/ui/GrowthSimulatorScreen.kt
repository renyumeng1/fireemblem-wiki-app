package com.fireemblem.companion.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CompareArrows
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fireemblem.companion.content.GrowthSimClass
import com.fireemblem.companion.content.GrowthSimulatorCatalog
import com.fireemblem.companion.content.WikiRepository
import com.fireemblem.companion.engine.GrowthPlanStage
import com.fireemblem.companion.engine.GrowthSimulationMode
import com.fireemblem.companion.engine.GrowthSimulationResult
import com.fireemblem.companion.engine.GrowthSimulatorEngine
import com.fireemblem.companion.engine.GrowthSimulatorPlan
import com.fireemblem.companion.engine.GrowthStat
import com.fireemblem.companion.personal.PersonalStore
import com.fireemblem.companion.ui.theme.tabularNumbers
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface GrowthSimLoadState {
    data object Loading : GrowthSimLoadState
    data class Ready(val catalog: GrowthSimulatorCatalog) : GrowthSimLoadState
    data class Failed(val message: String) : GrowthSimLoadState
}

@Composable
internal fun GrowthSimulatorScreen(
    repository: WikiRepository?,
    onBack: () -> Unit,
    onOpenFixedCalc: (() -> Unit)? = null,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { PersonalStore(context) }
    val profileId = remember(context) {
        context.getSharedPreferences("personal-view", 0).getString("profile", "global") ?: "global"
    }

    var retryToken by remember { mutableIntStateOf(0) }
    var modeId by rememberSaveable { mutableStateOf(GrowthSimulationMode.FIXED.id) }
    var compareEnabled by rememberSaveable { mutableStateOf(false) }
    var planName by rememberSaveable { mutableStateOf("主力培养方案 A") }
    var selectedCharId by rememberSaveable { mutableStateOf("PID_カイ") }
    var startLevel by rememberSaveable { mutableIntStateOf(1) }
    var part3 by rememberSaveable { mutableStateOf(false) }
    val stages = remember { mutableStateListOf<GrowthPlanStage>() }

    // Compare Plan B baseline (either loaded from saved plan or mirrored snapshot)
    var baselinePlan by remember { mutableStateOf<GrowthSimulatorPlan?>(null) }
    var savedPlanStatus by remember { mutableStateOf<String?>(null) }
    var pendingExportBytes by remember { mutableStateOf<ByteArray?>(null) }
    var pendingPngBytes by remember { mutableStateOf<ByteArray?>(null) }

    val loadState by produceState<GrowthSimLoadState>(GrowthSimLoadState.Loading, repository, retryToken) {
        value = GrowthSimLoadState.Loading
        if (repository == null) {
            value = GrowthSimLoadState.Failed("离线资料库尚未打开，请稍候。")
            return@produceState
        }
        value = try {
            val catalog = withContext(Dispatchers.IO) { repository.growthSimulatorCatalog() }
            if (catalog == null) GrowthSimLoadState.Failed("当前资料库未包含成长模拟器数据。")
            else GrowthSimLoadState.Ready(catalog)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            GrowthSimLoadState.Failed(error.message ?: "读取成长模拟器数据失败")
        }
    }

    val exportJsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        val bytes = pendingExportBytes
        pendingExportBytes = null
        if (uri != null && bytes != null) {
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { it.write(bytes) }
                    }
                }.onSuccess {
                    savedPlanStatus = "已导出多阶段规划 JSON。"
                }.onFailure { err ->
                    savedPlanStatus = "导出失败：${err.message ?: "无法写入文件"}"
                }
            }
        }
    }

    val exportPngLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png"),
    ) { uri: Uri? ->
        val bytes = pendingPngBytes
        pendingPngBytes = null
        if (uri != null && bytes != null) {
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { it.write(bytes) }
                    }
                }.onSuccess {
                    savedPlanStatus = "已导出成长结果 PNG 卡片。"
                }.onFailure { err ->
                    savedPlanStatus = "导出 PNG 失败：${err.message ?: "无法写入文件"}"
                }
            }
        }
    }

    val importJsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val imported = withContext(Dispatchers.IO) {
                        val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                            val out = ByteArrayOutputStream()
                            val buf = ByteArray(8 * 1024)
                            while (true) {
                                val read = input.read(buf)
                                if (read < 0) break
                                if (out.size() + read > GrowthSimulatorPlan.MAX_EXPORT_BYTES) {
                                    throw IOException("文件超过 512 KiB 上限")
                                }
                                out.write(buf, 0, read)
                            }
                            out.toByteArray()
                        }
                        GrowthSimulatorPlan.decodeExport(bytes, profileId)
                    }
                    planName = imported.name
                    selectedCharId = imported.characterId
                    startLevel = imported.startLevel
                    part3 = imported.part3
                    stages.clear()
                    stages.addAll(imported.stages)
                }.onSuccess {
                    savedPlanStatus = "已载入导入的成长规划。"
                }.onFailure { err ->
                    savedPlanStatus = "导入失败：${err.message ?: "文件无效"}"
                }
            }
        }
    }

    val currentLoadState = loadState
    val readyCatalog = (currentLoadState as? GrowthSimLoadState.Ready)?.catalog
    LaunchedEffect(readyCatalog) {
        val cat = readyCatalog ?: return@LaunchedEffect
        val character = cat.characters.firstOrNull { it.nativeId == selectedCharId }
            ?: cat.characters.firstOrNull()
            ?: return@LaunchedEffect
        selectedCharId = character.nativeId
        startLevel = character.startLevel
        if (stages.isEmpty()) {
            val defaultCls = cat.classes.firstOrNull { it.name == character.startClass && it.allowedFor(character) }
                ?: cat.classes.firstOrNull { it.allowedFor(character) }
                ?: return@LaunchedEffect
            stages.add(GrowthPlanStage(classId = defaultCls.id, mountId = null, levels = 10))
        }
    }

    val activeMode = GrowthSimulationMode.fromId(modeId)
    val readyCharacter = readyCatalog?.let { cat ->
        cat.characters.firstOrNull { it.nativeId == selectedCharId } ?: cat.characters.firstOrNull()
    }
    val allowedClasses = remember(readyCatalog, readyCharacter?.nativeId) {
        if (readyCatalog == null || readyCharacter == null) emptyList()
        else readyCatalog.classes.filter { it.allowedFor(readyCharacter) }
    }
    val currentPlan = remember(readyCharacter?.nativeId, allowedClasses, stages.toList(), profileId, planName, startLevel, part3) {
        val firstAllowed = allowedClasses.firstOrNull()
        if (readyCharacter == null || firstAllowed == null) null
        else {
            val activeStages = if (stages.isEmpty()) {
                listOf(GrowthPlanStage(firstAllowed.id, null, 10))
            } else stages.toList()
            GrowthSimulatorPlan(
                profileId = profileId,
                name = planName.trim().ifEmpty { "${readyCharacter.name}规划" },
                characterId = readyCharacter.nativeId,
                startLevel = startLevel.coerceIn(1, 98),
                part3 = part3,
                stages = activeStages,
            )
        }
    }
    val simResult = remember(readyCatalog, currentPlan, activeMode) {
        if (readyCatalog == null || currentPlan == null) null
        else runCatching { GrowthSimulatorEngine.simulate(readyCatalog, currentPlan, activeMode) }.getOrNull()
    }
    val compareResult = remember(readyCatalog, baselinePlan, activeMode, compareEnabled) {
        val baseline = baselinePlan
        if (readyCatalog != null && compareEnabled && baseline != null) {
            runCatching { GrowthSimulatorEngine.simulate(readyCatalog, baseline, activeMode) }.getOrNull()
        } else null
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val horizontalPadding = if (maxWidth < 600.dp) 16.dp else 24.dp
        LazyVerticalGrid(
            columns = GridCells.Fixed(1),
            modifier = Modifier
                .fillMaxSize()
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = 960.dp)
                .padding(horizontal = horizontalPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.size(8.dp)) }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = onBack,
                            modifier = Modifier.heightIn(min = 44.dp).testTag("growth-sim-back"),
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                            Text("返回工具")
                        }
                        if (onOpenFixedCalc != null) {
                            OutlinedButton(
                                onClick = onOpenFixedCalc,
                                modifier = Modifier.heightIn(min = 44.dp).testTag("growth-sim-open-fixed"),
                            ) {
                                Text("固定成长余数试算")
                            }
                        }
                    }
                    ScreenHeading("成长模拟与试算", "按转职路线与坐骑配置，推算固定成长、随机期望与线性期望。")
                }
            }

            when (currentLoadState) {
                GrowthSimLoadState.Loading -> item { LoadingPackage() }
                is GrowthSimLoadState.Failed -> item { FailedPackage(currentLoadState.message) { retryToken++ } }
                is GrowthSimLoadState.Ready -> {
                    val catalog = currentLoadState.catalog
                    val character = readyCharacter ?: catalog.characters.firstOrNull()
                    val firstAllowedClass = allowedClasses.firstOrNull()
                    if (character == null || firstAllowedClass == null) {
                        item { FailedPackage("成长模拟器资料暂不完整，请稍后重试。") { retryToken++ } }
                    } else {
                    val activePlan = currentPlan ?: GrowthSimulatorPlan(
                        profileId = profileId,
                        name = planName.trim().ifEmpty { "${character.name}规划" },
                        characterId = character.nativeId,
                        startLevel = startLevel.coerceIn(1, 98),
                        part3 = part3,
                        stages = listOf(GrowthPlanStage(firstAllowedClass.id, null, 10)),
                    )

                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(18.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        ) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("1. 选择计算模式", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    GrowthSimulationMode.entries.forEach { m ->
                                        FilterChip(
                                            selected = activeMode == m,
                                            onClick = { modeId = m.id },
                                            modifier = Modifier.heightIn(min = 48.dp).testTag("sim-mode:${m.id}"),
                                            label = { Text(m.label) },
                                        )
                                    }
                                }
                                Text(
                                    activeMode.note,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(18.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        ) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("2. 角色与初始状态", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                                OutlinedTextField(
                                    value = planName,
                                    onValueChange = { planName = it.take(80) },
                                    modifier = Modifier.fillMaxWidth().testTag("sim-plan-name"),
                                    singleLine = true,
                                    label = { Text("方案名称") },
                                )
                                Row(
                                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    catalog.characters.forEach { ch ->
                                        FilterChip(
                                            selected = ch.nativeId == character.nativeId,
                                            onClick = {
                                                selectedCharId = ch.nativeId
                                                startLevel = ch.startLevel
                                                val defCls = catalog.classes.firstOrNull {
                                                    it.name == ch.startClass && it.allowedFor(ch)
                                                } ?: catalog.classes.firstOrNull { it.allowedFor(ch) }
                                                stages.clear()
                                                if (defCls != null) {
                                                    stages.add(GrowthPlanStage(defCls.id, null, 10))
                                                }
                                            },
                                            modifier = Modifier.heightIn(min = 48.dp).testTag("sim-char:${ch.nativeId}"),
                                            label = { Text(ch.name) },
                                        )
                                    }
                                }
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    FilterChip(
                                        selected = !part3,
                                        onClick = { part3 = false },
                                        modifier = Modifier.heightIn(min = 48.dp),
                                        label = { Text("1-2 部（保底 ${character.guaranteePre}）") },
                                    )
                                    FilterChip(
                                        selected = part3,
                                        onClick = { part3 = true },
                                        modifier = Modifier.heightIn(min = 48.dp),
                                        label = { Text("第 3 部（保底 ${character.guaranteePart3}）") },
                                    )
                                    EditorialBadge(text = "起始 Lv.$startLevel · ${character.startClass}")
                                }
                            }
                        }
                    }

                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(18.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        ) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("3. 转职阶段路线", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                                    OutlinedButton(
                                        enabled = stages.size < 8,
                                        onClick = {
                                            val nextCls = allowedClasses.firstOrNull { it.tier == "中级" || it.tier == "上级" }
                                                ?: firstAllowedClass
                                            stages.add(GrowthPlanStage(nextCls.id, null, 10))
                                        },
                                        modifier = Modifier.heightIn(min = 48.dp).testTag("sim-add-stage"),
                                    ) {
                                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Text("添加阶段")
                                    }
                                }
                                stages.forEachIndexed { idx, stage ->
                                    val cls = allowedClasses.firstOrNull { it.id == stage.classId } ?: firstAllowedClass
                                    val compatibleMounts = catalog.mounts.filter {
                                        cls.mountType.isNotEmpty() && it.type == cls.mountType
                                    }
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                                        shape = RoundedCornerShape(14.dp),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    ) {
                                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Row(
                                                Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Text(
                                                    "阶段 ${idx + 1}：${cls.name}（${cls.tier}） · 升 ${stage.levels} 级",
                                                    style = MaterialTheme.typography.titleSmall.tabularNumbers(),
                                                    modifier = Modifier.weight(1f),
                                                )
                                                if (stages.size > 1) {
                                                    IconButton(onClick = { stages.removeAt(idx) }) {
                                                        Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除阶段")
                                                    }
                                                }
                                            }
                                            var stageTierFilter by remember(idx) { mutableStateOf<String?>(null) }
                                            val tierOrder = listOf("基础", "初级", "下级", "中级", "上级", "最上级", "神将")
                                            val stageTiers = remember(allowedClasses) {
                                                allowedClasses.map { it.tier.lineSequence().first().trim() }
                                                    .distinct()
                                                    .sortedBy { t -> tierOrder.indexOf(t).let { if (it < 0) 99 else it } }
                                            }
                                            if (stageTiers.size > 1) {
                                                Row(
                                                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                ) {
                                                    SelectablePill("全部", stageTierFilter == null) { stageTierFilter = null }
                                                    stageTiers.forEach { t ->
                                                        val disp = if (t == "初级") "下级" else t
                                                        SelectablePill(disp, stageTierFilter == t) {
                                                            stageTierFilter = if (stageTierFilter == t) null else t
                                                        }
                                                    }
                                                }
                                            }
                                            val visibleStageClasses = allowedClasses.filter { candidate ->
                                                stageTierFilter == null || candidate.tier.lineSequence().first().trim() == stageTierFilter
                                            }
                                            Row(
                                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            ) {
                                                visibleStageClasses.forEach { candidate ->
                                                    FilterChip(
                                                        selected = candidate.id == cls.id,
                                                        onClick = { stages[idx] = stage.copy(classId = candidate.id, mountId = null) },
                                                        modifier = Modifier.heightIn(min = 48.dp),
                                                        label = { Text(candidate.name) },
                                                    )
                                                }
                                            }
                                            if (compatibleMounts.isNotEmpty()) {
                                                Row(
                                                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                ) {
                                                    FilterChip(
                                                        selected = stage.mountId == null,
                                                        onClick = { stages[idx] = stage.copy(mountId = null) },
                                                        modifier = Modifier.heightIn(min = 48.dp),
                                                        label = { Text("无坐骑") },
                                                    )
                                                    compatibleMounts.take(8).forEach { m ->
                                                        FilterChip(
                                                            selected = stage.mountId == m.id,
                                                            onClick = { stages[idx] = stage.copy(mountId = m.id) },
                                                            modifier = Modifier.heightIn(min = 48.dp),
                                                            label = { Text(m.name) },
                                                        )
                                                    }
                                                }
                                            }
                                            FlowRow(
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                            ) {
                                                listOf(5, 10, 15, 20).forEach { count ->
                                                    FilterChip(
                                                        selected = stage.levels == count,
                                                        onClick = { stages[idx] = stage.copy(levels = count) },
                                                        modifier = Modifier.heightIn(min = 48.dp),
                                                        label = { Text("+$count 级") },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (simResult != null) {
                        item {
                            SimulationResultCard(
                                result = simResult,
                                compareResult = compareResult,
                                planName = activePlan.name,
                                baselineName = baselinePlan?.name,
                            )
                        }
                    }

                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(18.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        ) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("4. 方案对比与导出", style = MaterialTheme.typography.titleMedium)
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Button(
                                        onClick = {
                                            val entityId = GrowthSimulatorPlan.entityId(profileId, activePlan.name)
                                            scope.launch {
                                                runCatching {
                                                    withContext(Dispatchers.IO) {
                                                        val parents = store.snapshot().heads()[entityId]
                                                            .orEmpty().map { it.revisionId }.toSet()
                                                        store.edit(entityId, GrowthSimulatorPlan.KIND, activePlan.payload(), expectedParents = parents)
                                                    }
                                                }.onSuccess {
                                                    savedPlanStatus = "已保存“${activePlan.name}”到本地记录。"
                                                }.onFailure { err ->
                                                    savedPlanStatus = "保存失败：${err.message}"
                                                }
                                            }
                                        },
                                        modifier = Modifier.heightIn(min = 48.dp).testTag("sim-save-plan"),
                                    ) {
                                        Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Text("保存规划")
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            if (baselinePlan == null) {
                                                baselinePlan = activePlan.copy(name = "${activePlan.name}（基准）")
                                            }
                                            compareEnabled = !compareEnabled
                                        },
                                        modifier = Modifier.heightIn(min = 48.dp).testTag("sim-compare-toggle"),
                                    ) {
                                        Icon(Icons.Outlined.CompareArrows, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Text(if (compareEnabled) "收起对比" else "锁定基准并对比")
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            pendingExportBytes = activePlan.encodeExport()
                                            exportJsonLauncher.launch("growth-plan-${character.code}.json")
                                        },
                                        modifier = Modifier.heightIn(min = 48.dp).testTag("sim-export-json"),
                                    ) {
                                        Icon(Icons.Outlined.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Text("导出 JSON")
                                    }
                                    OutlinedButton(
                                        onClick = { importJsonLauncher.launch(arrayOf("application/json", "*/*")) },
                                        modifier = Modifier.heightIn(min = 48.dp),
                                    ) {
                                        Icon(Icons.Outlined.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Text("导入 JSON")
                                    }
                                    if (simResult != null) {
                                        OutlinedButton(
                                            onClick = {
                                                pendingPngBytes = renderSimulationCardPng(activePlan.name, simResult)
                                                exportPngLauncher.launch("growth-result-${character.code}.png")
                                            },
                                            modifier = Modifier.heightIn(min = 48.dp).testTag("sim-export-png"),
                                        ) {
                                            Icon(Icons.Outlined.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Text("导出 PNG")
                                        }
                                    }
                                }
                                savedPlanStatus?.let { msg ->
                                    Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                    }
                }
            }
            item { Spacer(Modifier.size(20.dp)) }
        }
    }
}

@Composable
private fun SimulationResultCard(
    result: GrowthSimulationResult,
    compareResult: GrowthSimulationResult?,
    planName: String,
    baselineName: String?,
) {
    val deltas = compareResult?.let { GrowthSimulatorEngine.compare(it, result) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth().testTag("sim-result-card"),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${result.characterName} · ${result.mode.label}结果",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (result.tieBreakStepsCount > 0) {
                    EditorialBadge(
                        text = "${result.tieBreakStepsCount} 级同余数提示",
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            if (deltas != null) {
                Text(
                    "与基准方案“${baselineName ?: "基准"}”对比当前“$planName”",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            GrowthStat.entries.chunked(3).forEach { rowStats ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowStats.forEach { stat ->
                        val idx = stat.ordinal
                        val finalVal = result.finalStats[idx]
                        val gainVal = result.gains[idx]
                        val band = result.statBands?.getOrNull(idx)
                        val delta = deltas?.getOrNull(idx)
                        Surface(
                            Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(stat.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                Text(
                                    formatStatNumber(finalVal),
                                    style = MaterialTheme.typography.titleMedium.tabularNumbers(),
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    "+${formatStatNumber(gainVal)}",
                                    style = MaterialTheme.typography.labelSmall.tabularNumbers(),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                band?.let {
                                    Text(
                                        "P10 ${it.p10.toInt()}–P90 ${it.p90.toInt()}",
                                        style = MaterialTheme.typography.labelSmall.tabularNumbers(),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (delta != null && kotlin.math.abs(delta) >= 0.05) {
                                    val sign = if (delta > 0) "+" else ""
                                    Text(
                                        "差值 $sign${formatStatNumber(delta)}",
                                        style = MaterialTheme.typography.labelSmall.tabularNumbers(),
                                        color = if (delta > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatStatNumber(value: Double): String {
    val rounded = value.toInt()
    return if (kotlin.math.abs(value - rounded) < 1e-4) rounded.toString() else "%.1f".format(value)
}

internal fun renderSimulationCardPng(planName: String, result: GrowthSimulationResult): ByteArray {
    val width = 920
    val height = 560
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(0xFFFAF9F5.toInt())

    val borderPaint = Paint().apply {
        color = 0xFFE8E6DC.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    canvas.drawRoundRect(24f, 24f, width - 24f, height - 24f, 24f, 24f, borderPaint)

    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF141413.toInt()
        textSize = 38f
        typeface = Typeface.DEFAULT_BOLD
    }
    val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB85A3A.toInt()
        textSize = 24f
    }
    val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF5F5D56.toInt()
        textSize = 22f
    }
    canvas.drawText("${result.characterName} · $planName", 52f, 84f, titlePaint)
    canvas.drawText("计算模式：${result.mode.label}", 52f, 124f, subPaint)

    GrowthStat.entries.forEachIndexed { idx, stat ->
        val col = idx % 3
        val row = idx / 3
        val x = 52f + col * 280f
        val y = 185f + row * 105f
        canvas.drawText(stat.label, x, y, subPaint)
        canvas.drawText(
            "${formatStatNumber(result.finalStats[idx])} (+${formatStatNumber(result.gains[idx])})",
            x,
            y + 38f,
            titlePaint.apply { textSize = 28f },
        )
    }
    canvas.drawText(
        result.stageSummaries.joinToString(" → ") { "${it.className}(Lv.${it.levelFrom}-${it.levelTo})" },
        52f,
        height - 48f,
        bodyPaint,
    )
    return ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray()
    }
}
