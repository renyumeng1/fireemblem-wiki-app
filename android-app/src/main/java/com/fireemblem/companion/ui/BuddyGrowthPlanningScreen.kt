package com.fireemblem.companion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.fireemblem.companion.engine.BuddyGrowthInput
import com.fireemblem.companion.engine.BuddyGrowthPlanRequest
import com.fireemblem.companion.engine.BuddyGrowthPlanResult
import com.fireemblem.companion.engine.BuddyGrowthPlanStep
import com.fireemblem.companion.engine.BuddyGrowthPlanner
import com.fireemblem.companion.engine.BuddyProfessionWindow
import com.fireemblem.companion.engine.BuddyRideSelection
import com.fireemblem.companion.engine.BuddyRules
import com.fireemblem.companion.engine.GrowthStat
import com.fireemblem.companion.engine.JournalGrowthPlan
import com.fireemblem.companion.engine.JournalSeedCheckpoint
import com.fireemblem.companion.engine.LevelJournal
import com.fireemblem.companion.ui.theme.tabularNumbers
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Streamlined auto-planner screen: quick target level & switch pills, 1-tap visual weight selector,
 * searchable profession checklist, zero mandatory 9-stat text wall (optional real stats/caps/minimums
 * neatly tucked under collapsible Advanced Constraints).
 */
@Composable
internal fun BuddyGrowthPlanningScreen(
    journal: LevelJournal,
    checkpoint: JournalSeedCheckpoint,
    rules: BuddyRules,
    saving: Boolean,
    saveError: String?,
    onBack: () -> Unit,
    readingPositionKey: String = "growth-planning",
    onSave: (JournalGrowthPlan, List<Int>, List<Int?>, Set<String>) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val visuals = rememberBuddyVisuals()
    val darkTheme = isSystemInDarkTheme()
    val availableProfessions = remember(rules, journal.characterId) {
        rules.professions.filter {
            rules.classAllowed(journal.characterId, it.name, false) ||
                rules.classAllowed(journal.characterId, it.name, true)
        }
    }
    val initialUnlocked = remember(journal, checkpoint) {
        (journal.confirmedUnlockedProfessions() + checkpoint.profession).toSet()
    }
    var unlocked by remember { mutableStateOf(initialUnlocked) }
    var professionDialogOpen by remember { mutableStateOf(false) }
    var targetText by rememberSaveable { mutableStateOf((checkpoint.level + 10).coerceAtMost(99).toString()) }
    var maxSwitches by rememberSaveable { mutableIntStateOf(98) }
    var respectTierLevel by rememberSaveable { mutableStateOf(true) }
    var weights by remember { mutableStateOf(listOf(1, 5, 0, 2, 3, 0, 1, 0, 0)) }
    var part3StartText by rememberSaveable { mutableStateOf(if (checkpoint.part3) "1" else "") }
    var statsText by rememberSaveable { mutableStateOf(checkpoint.stats?.joinToString(",") ?: List(9) { "" }.joinToString(",")) }
    var capsText by rememberSaveable { mutableStateOf(checkpoint.caps?.joinToString(",") { it?.toString().orEmpty() } ?: List(9) { "" }.joinToString(",")) }
    var minimumText by rememberSaveable { mutableStateOf(List(9) { "" }.joinToString(",")) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var rideId by rememberSaveable { mutableStateOf("") }
    var rideLevelText by rememberSaveable { mutableStateOf("1") }
    var result by remember { mutableStateOf<BuddyGrowthPlanResult?>(null) }
    var plannedRequest by remember { mutableStateOf<BuddyGrowthPlanRequest?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saveActionError by remember { mutableStateOf<String?>(null) }
    var cancellation by remember { mutableStateOf<AtomicBoolean?>(null) }
    var inputRevision by remember { mutableIntStateOf(0) }

    fun invalidate() {
        inputRevision++
        cancellation?.set(true)
        result = null
        plannedRequest = null
        error = null
        saveActionError = null
    }

    fun setDelimited(current: String, index: Int, value: String): String {
        val items = current.split(",").toMutableList().also { while (it.size < 9) it += "" }
        items[index] = value
        return items.take(9).joinToString(",")
    }

    fun createRequest(): BuddyGrowthPlanRequest {
        val target = requireNotNull(targetText.toIntOrNull()) { "请输入目标等级" }
        require(target > checkpoint.level && target <= 99) { "目标等级应在 ${checkpoint.level + 1} 至 99 之间" }
        val activeWeights = if (weights.any { it > 0 }) weights else List(9) { 1 }
        val caps = capsText.split(",").map { it.toIntOrNull() }
        val minimum = minimumText.split(",").map { it.toIntOrNull() }
        val rawStats = statsText.split(",").map { it.toIntOrNull() }
        val hasCapsOrMin = caps.any { it != null } || minimum.any { it != null }
        val stats = if (hasCapsOrMin) {
            rawStats.map { requireNotNull(it) { "设置了上限或最低目标时，请填写当前实测属性" } }
        } else {
            rawStats.map { it ?: 0 }
        }
        val activeUnlocked = unlocked + checkpoint.profession
        val windows = activeUnlocked.map { prof ->
            val minLv = if (prof == checkpoint.profession || !respectTierLevel) {
                checkpoint.level
            } else {
                maxOf(checkpoint.level, BuddyRules.defaultUnlockLevelForTier(rules.professionTier(prof)))
            }
            BuddyProfessionWindow(prof, minLv.coerceIn(1, 99), 99)
        }
        val ride = if (rideId.isBlank()) null else BuddyRideSelection(
            rideId,
            (rideLevelText.toIntOrNull() ?: 1).coerceIn(1, 5),
        )
        return BuddyGrowthPlanRequest(
            seed = checkpoint.seed,
            characterId = journal.characterId,
            line = journal.line,
            currentLevel = checkpoint.level,
            targetLevel = target,
            part3StartLevel = part3StartText.toIntOrNull() ?: if (checkpoint.part3) 1 else null,
            currentPart3 = checkpoint.part3,
            initialProfession = checkpoint.profession,
            unlockedProfessions = activeUnlocked,
            professionWindows = windows,
            weights = activeWeights,
            currentStats = stats,
            caps = caps,
            minimumStats = minimum,
            maxSwitches = maxSwitches,
            ride = ride,
        )
    }

    BackHandler { if (busy) cancellation?.set(true) else onBack() }
    DisposableEffect(Unit) { onDispose { cancellation?.set(true) } }

    val availableRides = remember(rules, journal, checkpoint, availableProfessions) {
        availableProfessions.flatMap { profession ->
            listOf(checkpoint.part3, true).distinct().flatMap { part3 ->
                rules.availableRides(
                    BuddyGrowthInput(journal.characterId, profession.name, checkpoint.level, part3, journal.line.rulesRoute),
                )
            }
        }.distinctBy { it.id }.sortedBy { it.name }
    }

    Box(Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 880.dp)) {
        val characterName = rules.characters.firstOrNull { it.id == journal.characterId }?.name ?: journal.characterId
        val unlockedList = (unlocked + checkpoint.profession).sorted()
        LazyColumn(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
            state = rememberReadingListState(readingPositionKey),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                OutlinedButton(
                    onClick = { if (busy) cancellation?.set(true) else onBack() },
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = 44.dp),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("返回记录册")
                }
            }
            item {
                EditorialCard {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        BuddyPortrait(journal.characterId, characterName, visuals, Modifier.size(56.dp), part3 = checkpoint.part3)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("自动规划转职路线", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${characterLabel(journal.characterId, characterName)} · Lv.${checkpoint.level} · ${checkpoint.profession} · 种子 ${checkpoint.seed}",
                                style = MaterialTheme.typography.bodySmall.tabularNumbers(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            item {
                EditorialCard {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Target Level Presets
                        Text("目标等级", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                (checkpoint.level + 5).coerceAtMost(99) to "+5 级",
                                (checkpoint.level + 10).coerceAtMost(99) to "+10 级",
                                (checkpoint.level + 20).coerceAtMost(99) to "+20 级",
                                40 to "Lv.40",
                            ).filter { it.first > checkpoint.level }.distinctBy { it.first }.forEach { (lv, lbl) ->
                                SelectablePill("$lbl (Lv.$lv)", targetText == lv.toString()) {
                                    targetText = lv.toString(); invalidate()
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = targetText,
                                onValueChange = { targetText = it.filter(Char::isDigit).take(2); invalidate() },
                                label = { Text("自定义目标等级") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f).semantics { contentDescription = "目标等级" },
                            )
                            Column(Modifier.weight(1.3f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("最多转职", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    listOf(98 to "不限", 1 to "1次", 2 to "2次", 3 to "3次").forEach { (cnt, lbl) ->
                                        SelectablePill(lbl, maxSwitches == cnt) { maxSwitches = cnt; invalidate() }
                                    }
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        // Professions
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("已解锁职业（${unlockedList.size} 个）", style = MaterialTheme.typography.labelLarge)
                            TextButton(onClick = { professionDialogOpen = true }, modifier = Modifier.heightIn(min = 36.dp)) {
                                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Text("勾选职业")
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            unlockedList.forEach { profName ->
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (profName == checkpoint.profession) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                ) {
                                    Row(Modifier.padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                        WikiImage(visuals.image("professions", profName), "$profName", Modifier.size(20.dp), crop = false)
                                        val minLv = if (profName == checkpoint.profession || !respectTierLevel) checkpoint.level
                                        else maxOf(checkpoint.level, BuddyRules.defaultUnlockLevelForTier(rules.professionTier(profName)))
                                        Text(
                                            if (profName == checkpoint.profession) "$profName · 当前"
                                            else if (minLv > checkpoint.level) "$profName · Lv.$minLv+"
                                            else profName,
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                    }
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "按阶位等级门槛转职（下级5 / 中级20 / 上级35 / 最上级·神将45）",
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            androidx.compose.material3.Switch(
                                checked = respectTierLevel,
                                onCheckedChange = { respectTierLevel = it; invalidate() },
                                colors = androidx.compose.material3.SwitchDefaults.colors(
                                    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    uncheckedBorderColor = MaterialTheme.colorScheme.outline,
                                ),
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        // Visual 1-tap weights
                        WeightSelectorPanel(weights = weights, darkTheme = darkTheme) {
                            weights = it; invalidate()
                        }

                        // Collapsible Advanced Constraints
                        TextButton(
                            onClick = { showAdvanced = !showAdvanced },
                            modifier = Modifier.heightIn(min = 36.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp),
                        ) {
                            Icon(Icons.Outlined.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(4.dp))
                            Text(if (showAdvanced) "收起高级约束" else "高级约束（坐骑 / 实测属性 / 上限 / 最低目标）")
                        }

                        if (showAdvanced) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                val rideChoices = listOf("" to "不骑乘") + availableRides.map { it.id to it.name }
                                IllustratedChoice(
                                    "坐骑", rideId,
                                    if (rideId.isBlank()) "不骑乘" else availableRides.firstOrNull { it.id == rideId }?.name ?: "已保存坐骑",
                                    rideChoices, "mounts", visuals,
                                ) { rideId = it; invalidate() }
                                if (rideId.isNotBlank()) {
                                    ChoiceField("坐骑友好", rideLevelText, (1..5).map { it.toString() to "Lv.$it" }) {
                                        rideLevelText = it; invalidate()
                                    }
                                }
                                if (!checkpoint.part3) {
                                    OutlinedTextField(
                                        part3StartText, { part3StartText = it.filter(Char::isDigit).take(2); invalidate() },
                                        label = { Text("预计第三部起始等级（留空表示前篇）") },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                                GrowthStat.entries.forEachIndexed { idx, stat ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(stat.label, Modifier.widthIn(min = 36.dp), style = MaterialTheme.typography.labelMedium)
                                        OutlinedTextField(
                                            statsText.split(",").getOrElse(idx) { "" },
                                            { statsText = setDelimited(statsText, idx, it.filter(Char::isDigit).take(3)); invalidate() },
                                            label = { Text("当前") },
                                            singleLine = true,
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            modifier = Modifier.weight(1f).semantics { contentDescription = "${stat.label}当前实测" },
                                        )
                                        OutlinedTextField(
                                            capsText.split(",").getOrElse(idx) { "" },
                                            { capsText = setDelimited(capsText, idx, it.filter(Char::isDigit).take(3)); invalidate() },
                                            label = { Text("上限") },
                                            singleLine = true,
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            modifier = Modifier.weight(1f).semantics { contentDescription = "${stat.label}属性上限" },
                                        )
                                        OutlinedTextField(
                                            minimumText.split(",").getOrElse(idx) { "" },
                                            { minimumText = setDelimited(minimumText, idx, it.filter(Char::isDigit).take(3)); invalidate() },
                                            label = { Text("最低") },
                                            singleLine = true,
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            modifier = Modifier.weight(1f).semantics { contentDescription = "${stat.label}最低目标" },
                                        )
                                    }
                                }
                            }
                        }

                        // Generate CTA
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    val request = runCatching(::createRequest).getOrElse {
                                        error = it.message ?: "请检查规划条件"
                                        return@Button
                                    }
                                    val token = AtomicBoolean(false)
                                    val revision = inputRevision
                                    cancellation = token
                                    busy = true
                                    error = null
                                    saveActionError = null
                                    result = null
                                    plannedRequest = null
                                    scope.launch {
                                        try {
                                            val computed = withContext(Dispatchers.Default) {
                                                BuddyGrowthPlanner.plan(request, rules, token::get)
                                            }
                                            if (revision == inputRevision) {
                                                result = computed
                                                plannedRequest = request
                                            }
                                        } catch (cancelledError: CancellationException) {
                                            throw cancelledError
                                        } catch (failure: Exception) {
                                            if (revision == inputRevision) error = failure.message ?: "无法生成培养方案"
                                        } finally {
                                            busy = false; cancellation = null
                                        }
                                    }
                                },
                                enabled = !busy,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            ) {
                                if (busy) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                    Spacer(Modifier.size(8.dp))
                                    Text("正在规划…")
                                } else {
                                    Text(if (result != null) "重新生成培养方案" else "生成培养方案")
                                }
                            }
                            if (busy) {
                                OutlinedButton(onClick = { cancellation?.set(true) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") }
                            }
                        }
                        error?.let {
                            Text(
                                it,
                                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            result?.let { planResult ->
                item {
                    val totalGains = List(9) { i -> planResult.steps.sumOf { it.gains.getOrElse(i) { 0 } } }
                    EditorialCard {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(planResult.reason, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                                EditorialBadge(
                                    text = "总涨 +${totalGains.sum()}",
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                            Text(
                                "已探索 ${planResult.exploredStates} 个状态 · 加权收益 ${planResult.weightedScore ?: "—"}",
                                style = MaterialTheme.typography.bodySmall.tabularNumbers(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            NineStatStrip(values = totalGains, weights = weights, darkTheme = darkTheme, showPlusSign = true)
                            (saveError ?: saveActionError)?.let {
                                Text(
                                    it,
                                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Button(
                                onClick = {
                                    saveActionError = null
                                    val request = plannedRequest
                                    if (request != null) runCatching {
                                        onSave(
                                            JournalGrowthPlan(request, planResult, journal.historyDigest(), journal.ruleRevision).validated(),
                                            request.currentStats,
                                            request.caps,
                                            request.unlockedProfessions,
                                        )
                                    }.onFailure { saveActionError = it.message ?: "保存方案失败" }
                                },
                                enabled = plannedRequest != null && planResult.hasPlan && !busy && !saving,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            ) { Text(if (saving) "正在保存…" else "保存并关联到记录册") }
                        }
                    }
                }
                itemsIndexed(planResult.steps, key = { _, step -> step.level }) { index, step ->
                    GrowthPlanStepCard(index + 1, step, weights, visuals, darkTheme)
                }
            }
        }
    }

    if (professionDialogOpen) {
        var query by remember { mutableStateOf("") }
        var selectedTier by remember { mutableStateOf<Int?>(null) }
        AlertDialog(
            onDismissRequest = { professionDialogOpen = false },
            title = { Text("勾选已解锁职业") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(query, { query = it }, label = { Text("搜索职业") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    BuddyProfessionTierFilterBar(
                        availableTiers = availableProfessions.map { it.tier },
                        selectedTier = selectedTier,
                        onSelectTier = { selectedTier = it },
                    )
                    LazyColumn(Modifier.heightIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(
                            availableProfessions.filter {
                                (selectedTier == null || it.tier == selectedTier) &&
                                    it.name.contains(query.trim(), ignoreCase = true)
                            },
                            key = { it.name },
                        ) { prof ->
                            val isChecked = prof.name in unlocked || prof.name == checkpoint.profession
                            val minLv = BuddyRules.defaultUnlockLevelForTier(prof.tier)
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Checkbox(
                                    checked = isChecked,
                                    enabled = prof.name != checkpoint.profession,
                                    onCheckedChange = { checked ->
                                        unlocked = if (checked) unlocked + prof.name else unlocked - prof.name
                                        invalidate()
                                    },
                                )
                                WikiImage(visuals.image("professions", prof.name), "${prof.name}图标", Modifier.size(30.dp), crop = false)
                                Column(Modifier.weight(1f)) {
                                    Text(prof.name + if (prof.name == checkpoint.profession) "（当前）" else "")
                                    Text(
                                        if (minLv > 1) "${BuddyRules.tierLabel(prof.tier)} · 推荐 Lv.$minLv 起" else BuddyRules.tierLabel(prof.tier),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { professionDialogOpen = false }) { Text("完成") } },
        )
    }
}

@Composable
private fun GrowthPlanStepCard(
    index: Int,
    step: BuddyGrowthPlanStep,
    weights: List<Int>,
    visuals: BuddyVisuals,
    darkTheme: Boolean,
) {
    EditorialCard {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Text(
                        "#$index",
                        Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.labelMedium.tabularNumbers(),
                        fontWeight = FontWeight.Bold,
                    )
                }
                WikiImage(visuals.image("professions", step.profession), "${step.profession}图标", Modifier.size(32.dp), crop = false)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text("Lv.${step.level} → Lv.${step.level + 1}", style = MaterialTheme.typography.titleSmall.tabularNumbers(), fontWeight = FontWeight.SemiBold)
                    Text(
                        step.profession + (step.switchFrom?.let { "（从 $it 转入）" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (step.switchFrom != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                EditorialBadge(
                    text = "+${step.gains.sum()}",
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            NineStatStrip(values = step.gains, weights = weights, darkTheme = darkTheme, showPlusSign = true)
        }
    }
}
