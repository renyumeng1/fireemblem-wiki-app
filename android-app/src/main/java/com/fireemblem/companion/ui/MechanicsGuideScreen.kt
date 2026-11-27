package com.fireemblem.companion.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Search
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fireemblem.companion.content.GrowthMechanicsSection
import com.fireemblem.companion.content.MechanicsEntry
import com.fireemblem.companion.content.MechanicsGuideData
import com.fireemblem.companion.content.WikiRepository
import com.fireemblem.companion.engine.BuddyCampaignLine
import com.fireemblem.companion.engine.BuddyGrowthInput
import com.fireemblem.companion.engine.BuddyRules
import com.fireemblem.companion.engine.FixedGrowthEngine
import com.fireemblem.companion.engine.GrowthStat
import com.fireemblem.companion.ui.theme.tabularNumbers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class MechanicsTab(val id: String, val label: String) {
    DICTIONARY("dictionary", "机制词典"),
    GROWTH_RULES("growth-rules", "成长机制详解"),
    FIXED_CALC("fixed-calc", "固定成长试算器"),
}

private sealed interface MechanicsLoadState {
    data object Loading : MechanicsLoadState
    data class Ready(val guide: MechanicsGuideData) : MechanicsLoadState
    data class Failed(val message: String) : MechanicsLoadState
}

@Composable
internal fun MechanicsGuideScreen(
    repository: WikiRepository?,
    onBack: () -> Unit,
    initialTabId: String = MechanicsTab.DICTIONARY.id,
) {
    BackHandler(onBack = onBack)
    var activeTabId by rememberSaveable(initialTabId) { mutableStateOf(initialTabId) }
    var selectedGroupId by rememberSaveable { mutableStateOf<String?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var retryToken by remember { mutableIntStateOf(0) }

    val loadState by produceState<MechanicsLoadState>(MechanicsLoadState.Loading, repository, retryToken) {
        value = MechanicsLoadState.Loading
        if (repository == null) {
            value = MechanicsLoadState.Failed("离线机制资料库尚未加载。")
            return@produceState
        }
        value = try {
            val loaded = withContext(Dispatchers.IO) { repository.mechanicsGuide() }
            if (loaded == null) {
                MechanicsLoadState.Failed("当前资料库版本暂未包含机制说明。")
            } else {
                MechanicsLoadState.Ready(loaded)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            MechanicsLoadState.Failed(error.message ?: "读取机制说明失败")
        }
    }

    val activeTab = MechanicsTab.entries.firstOrNull { it.id == activeTabId } ?: MechanicsTab.DICTIONARY

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val horizontalPadding = if (maxWidth < 600.dp) 16.dp else 24.dp
        val columns = if (maxWidth >= 780.dp && activeTab == MechanicsTab.DICTIONARY) 2 else 1

        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier
                .fillMaxSize()
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = 960.dp)
                .padding(horizontal = horizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Spacer(Modifier.size(8.dp))
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onBack,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("mechanics-back"),
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                        Text("返回")
                    }
                    ScreenHeading("机制与成长公式", "查阅 19 类系统机制词典、7 节成长公式及固定成长累积试算。")
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    MechanicsTab.entries.forEach { tab ->
                        FilterChip(
                            selected = activeTab == tab,
                            onClick = { activeTabId = tab.id },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("mechanics-tab:${tab.id}"),
                            leadingIcon = {
                                Icon(
                                    if (tab == MechanicsTab.FIXED_CALC) Icons.Outlined.Calculate else Icons.Outlined.MenuBook,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }

            if (activeTab == MechanicsTab.FIXED_CALC) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    FixedGrowthCalculatorSection()
                }
            } else {
                when (val current = loadState) {
                    MechanicsLoadState.Loading -> item(span = { GridItemSpan(maxLineSpan) }) {
                        LoadingPackage()
                    }
                    is MechanicsLoadState.Failed -> item(span = { GridItemSpan(maxLineSpan) }) {
                        FailedPackage(current.message) { retryToken++ }
                    }
                    is MechanicsLoadState.Ready -> {
                        val guide = current.guide
                        if (activeTab == MechanicsTab.DICTIONARY) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    modifier = Modifier.fillMaxWidth().testTag("mechanics-search"),
                                    placeholder = { Text("搜索机制名称、术语或正文…") },
                                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                                    trailingIcon = {
                                        if (searchQuery.isNotEmpty()) {
                                            TextButton(onClick = { searchQuery = "" }) { Text("清空") }
                                        }
                                    },
                                    singleLine = true,
                                    shape = RoundedCornerShape(16.dp),
                                )
                            }
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Row(
                                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    FilterChip(
                                        selected = selectedGroupId == null,
                                        onClick = { selectedGroupId = null },
                                        modifier = Modifier.heightIn(min = 48.dp).testTag("mechanics-group:all"),
                                        label = { Text("全部（${guide.entries.size}）") },
                                    )
                                    guide.groups.forEach { group ->
                                        FilterChip(
                                            selected = selectedGroupId == group.id,
                                            onClick = { selectedGroupId = group.id },
                                            modifier = Modifier.heightIn(min = 48.dp).testTag("mechanics-group:${group.id}"),
                                            label = { Text("${group.title}（${group.count}）") },
                                        )
                                    }
                                }
                            }
                            val needle = searchQuery.trim().lowercase()
                            val filtered = guide.entries.filter { entry ->
                                (selectedGroupId == null || entry.groupId == selectedGroupId) &&
                                    (needle.isEmpty() ||
                                        entry.title.lowercase().contains(needle) ||
                                        entry.kind.lowercase().contains(needle) ||
                                        entry.paragraphs.any { it.lowercase().contains(needle) })
                            }
                            val groupsById = guide.groups.associateBy { it.id }
                            if (filtered.isEmpty()) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                        shape = RoundedCornerShape(18.dp),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                                    ) {
                                        Text(
                                            "没有找到匹配的机制词条，请尝试更换分组或搜索词。",
                                            Modifier.fillMaxWidth().padding(20.dp),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            } else {
                                items(filtered, key = { it.id }) { entry ->
                                    MechanicsEntryCard(
                                        entry = entry,
                                        groupTitle = groupsById[entry.groupId]?.title ?: entry.groupId,
                                    )
                                }
                            }
                        } else {
                            items(guide.growthSections, key = { it.id }) { section ->
                                GrowthSectionCard(section)
                            }
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Text(
                                    "来源：${guide.growthSourceUri} · 修订 ${guide.sourceRevision.take(12)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Spacer(Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun MechanicsEntryCard(entry: MechanicsEntry, groupTitle: String) {
    var expanded by rememberSaveable(entry.id) { mutableStateOf(entry.paragraphs.size <= 2) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth().testTag("mechanics-entry:${entry.id}"),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    entry.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                EditorialBadge(text = "$groupTitle · ${entry.kind}")
            }
            val visibleParagraphs = if (expanded) entry.paragraphs else entry.paragraphs.take(2)
            visibleParagraphs.forEach { paragraph ->
                Text(
                    paragraph,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (entry.paragraphs.size > 2) {
                TextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(if (expanded) "收起正文" else "展开其余 ${entry.paragraphs.size - 2} 段")
                }
            }
        }
    }
}

@Composable
private fun GrowthSectionCard(section: GrowthMechanicsSection) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth().testTag("growth-section:${section.id}"),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                section.title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            section.blocks.forEach { block ->
                when (block.kind) {
                    "heading" -> Text(
                        block.text,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    "bullet" -> Text(
                        "• ${block.text}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    "table" -> Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            block.text.lines().forEachIndexed { idx, line ->
                                Text(
                                    line,
                                    style = (if (idx == 0) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodySmall)
                                        .tabularNumbers(),
                                    color = if (idx == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    else -> Text(
                        block.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun FixedGrowthCalculatorSection() {
    val rules = remember { runCatching { BuddyRules.bundled() }.getOrNull() }
    if (rules == null) {
        FailedPackage("固定成长规则未能加载。") {}
        return
    }
    var selectedCharId by rememberSaveable { mutableStateOf(rules.characters.firstOrNull { it.id == "c004" }?.id ?: rules.characters.first().id) }
    var selectedProfession by rememberSaveable(selectedCharId) {
        mutableStateOf(
            rules.professions.firstOrNull { rules.classAllowed(selectedCharId, it.name, part3 = false) }?.name
                ?: rules.professions.first().name,
        )
    }
    var part3 by rememberSaveable { mutableStateOf(false) }
    var levelsCount by rememberSaveable { mutableIntStateOf(5) }

    val availableProfessions = remember(selectedCharId, part3) {
        rules.professions.filter { rules.classAllowed(selectedCharId, it.name, part3) }
    }
    val effectiveProfession = if (availableProfessions.any { it.name == selectedProfession }) {
        selectedProfession
    } else {
        availableProfessions.firstOrNull()?.name ?: rules.professions.first().name
    }

    val personal = remember(selectedCharId) { rules.personalGrowth(selectedCharId) }
    val growthParams = remember(selectedCharId, effectiveProfession, part3) {
        rules.growth(
            BuddyGrowthInput(
                characterId = selectedCharId,
                profession = effectiveProfession,
                level = 1,
                part3 = part3,
                route = BuddyCampaignLine.KAI.rulesRoute,
            ),
        )
    }
    val guarantee = remember(selectedCharId, part3) { rules.characterGuarantee(selectedCharId, part3) }
    val caps = List(GrowthStat.entries.size) { 99 }
    val startStats = List(GrowthStat.entries.size) { 10 }
    val steps = remember(selectedCharId, effectiveProfession, part3, levelsCount) {
        FixedGrowthEngine.simulate(
            startLevel = 1,
            personalGrowth = personal,
            startStats = startStats,
            ratesPerLevel = List(levelsCount) { growthParams.parameters.growth },
            capsPerLevel = List(levelsCount) { caps },
            guaranteePoints = guarantee,
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("角色与职业条件", style = MaterialTheme.typography.titleMedium)
                Text("选择角色", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rules.characters.forEach { character ->
                        FilterChip(
                            selected = character.id == selectedCharId,
                            onClick = { selectedCharId = character.id },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("fixed-char:${character.id}"),
                            label = { Text(character.name) },
                        )
                    }
                }
                Text("选择兵种", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    availableProfessions.take(18).forEach { prof ->
                        FilterChip(
                            selected = prof.name == effectiveProfession,
                            onClick = { selectedProfession = prof.name },
                            modifier = Modifier.heightIn(min = 48.dp),
                            label = { Text(prof.name) },
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
                        label = { Text("1-2 部（角色保底 $guarantee）") },
                    )
                    FilterChip(
                        selected = part3,
                        onClick = { part3 = true },
                        modifier = Modifier.heightIn(min = 48.dp),
                        label = { Text("第 3 部") },
                    )
                    listOf(5, 10, 15).forEach { count ->
                        FilterChip(
                            selected = levelsCount == count,
                            onClick = { levelsCount = count },
                            modifier = Modifier.heightIn(min = 48.dp),
                            label = { Text("推算 $count 级") },
                        )
                    }
                }
                Text(
                    "初始累积余数：(3 × 个人成长)%100 = ${FixedGrowthEngine.initialAccumulators(personal).joinToString(" / ")}",
                    style = MaterialTheme.typography.bodySmall.tabularNumbers(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Text(
                "说明：按实测核对规则，固定成长累积初始值来自 (3 × 个人成长)%100；触发最低成长保障并获得 +1 的属性，其累积值直接清零（不扣负）。当多个候选属性余数相同时，游戏内部会使用确定性打乱序列决定补点顺序，此处已标出“同余数提示”。",
                Modifier.fillMaxWidth().padding(14.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        steps.forEach { step ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier.fillMaxWidth().testTag("fixed-step:${step.levelFrom}"),
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Lv.${step.levelFrom} → Lv.${step.levelTo}  总提升 +${step.totalGains.sum()}",
                            style = MaterialTheme.typography.titleSmall.tabularNumbers(),
                            fontWeight = FontWeight.SemiBold,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (step.guaranteedIndices.isNotEmpty()) {
                                EditorialBadge(
                                    text = "保底补点 +${step.guaranteedIndices.size}",
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                            if (step.hasTieBreakCaveat) {
                                EditorialBadge(
                                    text = "同余数提示",
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                        }
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        GrowthStat.entries.forEachIndexed { idx, stat ->
                            val gain = step.totalGains[idx]
                            val rem = step.stateAfter.accumulators[idx]
                            val isGuarantee = idx in step.guaranteedIndices
                            EditorialBadge(
                                text = "${stat.label} +$gain (余$rem)${if (isGuarantee) "*" else ""}",
                                containerColor = if (gain > 0) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerLow
                                },
                                contentColor = if (gain > 0) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
