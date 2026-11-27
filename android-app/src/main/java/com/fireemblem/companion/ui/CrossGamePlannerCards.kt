package com.fireemblem.companion.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fireemblem.companion.agent.AgentEngageProposal
import com.fireemblem.companion.agent.AgentThreeHousesProposal
import com.fireemblem.companion.content.FuzzyMatcher
import com.fireemblem.companion.content.FuzzySuggestion
import com.fireemblem.companion.content.StatRatingTier
import com.fireemblem.companion.engine.EngageCharacterData
import com.fireemblem.companion.engine.EngageClassData
import com.fireemblem.companion.engine.EngageFixedGrowthPlanner
import com.fireemblem.companion.engine.EngageFixedGrowthRequest
import com.fireemblem.companion.engine.EngageRules
import com.fireemblem.companion.engine.EngageStageSpec
import com.fireemblem.companion.engine.EngageStat
import com.fireemblem.companion.engine.ThreeHousesCertDipSpec
import com.fireemblem.companion.engine.ThreeHousesCharacterData
import com.fireemblem.companion.engine.ThreeHousesClassData
import com.fireemblem.companion.engine.ThreeHousesGender
import com.fireemblem.companion.engine.ThreeHousesGrowthPlanner
import com.fireemblem.companion.engine.ThreeHousesPlanRequest
import com.fireemblem.companion.engine.ThreeHousesStat
import com.fireemblem.companion.ui.theme.tabularNumbers
import java.util.Locale

@Composable
internal fun ThreeHousesPlanProposalCard(
    proposal: AgentThreeHousesProposal,
    stepsExpanded: Boolean = false,
    onToggleStepsExpanded: (() -> Unit)? = null,
) {
    val report = proposal.report
    var localExpanded by remember(proposal) { mutableStateOf(stepsExpanded) }
    val expanded = if (onToggleStepsExpanded != null) stepsExpanded else localExpanded
    val toggle: () -> Unit = {
        if (onToggleStepsExpanded != null) onToggleStepsExpanded() else localExpanded = !localExpanded
    }
    val highPal = statTierPalette(StatRatingTier.HIGH)
    val lowPal = statTierPalette(StatRatingTier.LOW)

    EditorialCard(
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().testTag("th-proposal-card"),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${report.characterName} · Lv.${report.fromLevel} → Lv.${report.targetLevel} 期望转职规划",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        listOfNotNull(
                            "风花雪月",
                            report.affiliation.takeIf { it.isNotBlank() },
                            report.gender.label,
                            if (report.usedTwoStatReroll) "含 2 点保底重抽精确期望" else "纯线性期望",
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                EditorialBadge(
                    text = "${report.stages.size} 阶段",
                    containerColor = highPal.badgeContainer,
                    contentColor = highPal.content,
                )
            }

            // Stage Progression Roadmap
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("推荐职业路线与考试要求：", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                report.stages.forEach { st ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(
                                    "Lv.${st.fromLevel} → Lv.${st.toLevel}：${st.className}（${st.tier}）",
                                    style = MaterialTheme.typography.bodySmall.tabularNumbers(),
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            if (st.requirement.isNotBlank()) {
                                Text(
                                    "条件：${st.requirement}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            // Certification Stat Floor Bumps
            val meaningfulCerts = report.certEvents.filter { it.totalFloorGain >= 0.05 || it.isCertDipOnly }
            if (meaningfulCerts.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("转职保底属性补点：", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.secondary)
                    meaningfulCerts.forEach { ev ->
                        val bumps = ThreeHousesStat.entries.mapNotNull { st ->
                            val b = ev.floorGains[st.ordinal]
                            if (b >= 0.05) "${st.label}+${String.format(Locale.US, "%.1f", b)}" else null
                        }.joinToString("、")
                        val prefix = if (ev.isCertDipOnly) "考证蹭保底" else "转职保底"
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "Lv.${ev.atLevel} $prefix「${ev.className}」：" + bumps.ifBlank { "属性已达标，无额外补点" },
                                Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                style = MaterialTheme.typography.bodySmall.tabularNumbers(),
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
            }

            // Final 9-Stat Expected Displayed Panel
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    "Lv.${report.targetLevel} 期望显示面板（含终职能力补正）",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                ThreeHousesStat.entries.chunked(3).forEach { rowStats ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        rowStats.forEach { st ->
                            val idx = st.ordinal
                            val finalVal = report.finalDisplayedStats[idx]
                            val gainVal = report.totalExpectedGains[idx]
                            val bonus = report.finalClassBonuses[idx]
                            Surface(
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                                color = when {
                                    bonus > 0 -> highPal.container
                                    bonus < 0 -> lowPal.container
                                    else -> MaterialTheme.colorScheme.surfaceContainerLow
                                },
                            ) {
                                Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(st.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(
                                            String.format(Locale.US, "%.1f", finalVal),
                                            style = MaterialTheme.typography.labelMedium.tabularNumbers(),
                                            fontWeight = FontWeight.Bold,
                                            color = if (bonus > 0) highPal.content else MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                    val bonusTag = if (bonus != 0) " 职${if (bonus > 0) "+$bonus" else "$bonus"}" else ""
                                    Text(
                                        "+${String.format(Locale.US, "%.1f", gainVal)}$bonusTag",
                                        style = MaterialTheme.typography.labelSmall.tabularNumbers(),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (report.alternativeFinalClasses.isNotEmpty()) {
                Text(
                    "同权重备选终职：${report.alternativeFinalClasses.joinToString(" · ") { it.first }}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            report.warnings.forEach { w ->
                Text("提示：$w", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // Expandable Level-by-Level Expected Panel Table
            if (report.levelRows.isNotEmpty()) {
                Surface(
                    onClick = toggle,
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth().testTag("th-toggle-steps"),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (expanded) "收起逐级期望面板预览"
                                else "展开逐级期望面板预览（Lv.${report.fromLevel} → Lv.${report.targetLevel} · 共 ${report.levelRows.size} 级）",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (!expanded) {
                                Text(
                                    "提前查看每一级职业、转职保底提点与 9 项期望属性变化",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Icon(
                            if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (expanded) {
                    ThreeHousesLevelPreviewTable(
                        proposal = proposal,
                        highPal = highPal,
                        modifier = Modifier.fillMaxWidth().testTag("th-steps-table"),
                    )
                }
            }
        }
    }
}

@Composable
private fun ThreeHousesLevelPreviewTable(
    proposal: AgentThreeHousesProposal,
    highPal: StatTierPalette,
    modifier: Modifier = Modifier,
) {
    val report = proposal.report
    val outline = MaterialTheme.colorScheme.outlineVariant
    val headerBg = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
    val headerFg = MaterialTheme.colorScheme.onPrimaryContainer
    val altRowBg = MaterialTheme.colorScheme.surfaceContainerLow

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, outline),
        modifier = modifier,
    ) {
        Column(Modifier.horizontalScroll(rememberScrollState()).width(IntrinsicSize.Max)) {
            Row(
                Modifier.fillMaxWidth().background(headerBg).padding(horizontal = 8.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("等级", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = headerFg, modifier = Modifier.width(56.dp))
                Text("职业", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = headerFg, modifier = Modifier.width(92.dp))
                ThreeHousesStat.entries.forEach { st ->
                    Text(st.shortLabel, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = headerFg, modifier = Modifier.width(34.dp))
                }
            }
            report.levelRows.forEachIndexed { idx, row ->
                HorizontalDivider(color = outline.copy(alpha = 0.45f))
                val hasFloor = row.floorGainsAtLevel.any { it >= 0.05 }
                val bg = when {
                    row.switchedToClass || hasFloor -> highPal.container.copy(alpha = 0.45f)
                    idx % 2 == 1 -> altRowBg
                    else -> MaterialTheme.colorScheme.surface
                }
                Row(
                    Modifier.fillMaxWidth().background(bg).padding(horizontal = 8.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Lv.${row.levelTo}",
                        style = MaterialTheme.typography.labelSmall.tabularNumbers(),
                        fontWeight = if (row.switchedToClass) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.width(56.dp),
                    )
                    Text(
                        (if (row.switchedToClass) "转·" else "") + row.className,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (row.switchedToClass) FontWeight.Bold else FontWeight.Normal,
                        color = if (row.switchedToClass) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(92.dp),
                    )
                    row.displayedStatsAfter.forEachIndexed { sIdx, v ->
                        val floored = row.floorGainsAtLevel[sIdx] >= 0.05
                        Text(
                            String.format(Locale.US, "%.1f", v),
                            style = MaterialTheme.typography.labelSmall.tabularNumbers(),
                            fontWeight = if (floored) FontWeight.Bold else FontWeight.Normal,
                            color = if (floored) highPal.content else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.width(34.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun EngagePlanProposalCard(
    proposal: AgentEngageProposal,
    stepsExpanded: Boolean = false,
    onToggleStepsExpanded: (() -> Unit)? = null,
) {
    val report = proposal.report
    var localExpanded by remember(proposal) { mutableStateOf(stepsExpanded) }
    val expanded = if (onToggleStepsExpanded != null) stepsExpanded else localExpanded
    val toggle: () -> Unit = {
        if (onToggleStepsExpanded != null) onToggleStepsExpanded() else localExpanded = !localExpanded
    }
    var showAccumulatorsInTable by remember(proposal) { mutableStateOf(false) }
    val highPal = statTierPalette(StatRatingTier.HIGH)
    val lowPal = statTierPalette(StatRatingTier.LOW)

    EditorialCard(
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().testTag("engage-proposal-card"),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${report.characterName} · 固定成长精确推算（+${report.totalLevelUps} 级）",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Engage · ${report.initialClassName} → ${report.finalClassName} · 累计内置等级 Lv.${report.finalTotalInternalLevel}",
                        style = MaterialTheme.typography.labelSmall.tabularNumbers(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (report.usedStarsphere) {
                        EditorialBadge(
                            text = "星玉+15%",
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    if (report.hasExpertise) {
                        EditorialBadge(text = "双倍职业成长")
                    }
                    EditorialBadge(
                        text = "100%确定性",
                        containerColor = highPal.badgeContainer,
                        contentColor = highPal.content,
                    )
                }
            }

            // Stage & Class Change Summary
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("阶段路线与转职补正：", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                report.stages.forEach { st ->
                    val starTxt = if (st.starsphere) " · 星玉" else ""
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "${st.className}（${st.tier}）：Lv.${st.levelFromInClass} → Lv.${st.levelToInClass}（+${st.levelsGained} 级$starTxt）",
                                style = MaterialTheme.typography.bodySmall.tabularNumbers(),
                                fontWeight = FontWeight.SemiBold,
                            )
                            val meta = listOfNotNull(
                                st.weaponRequirement.takeIf { it.isNotBlank() }?.let { "武器：$it" },
                                st.classSkill.takeIf { it.isNotBlank() && it != "--" }?.let { "兵种技：$it" },
                            ).joinToString(" · ")
                            if (meta.isNotBlank()) {
                                Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                report.classChanges.forEach { cc ->
                    val diffs = EngageStat.entries.mapNotNull { st ->
                        val d = cc.statDiffs[st.ordinal]
                        if (d != 0) "${st.label}${if (d > 0) "+$d" else "$d"}" else null
                    }.joinToString("、")
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "${cc.sealLabel}（${cc.fromClass} → ${cc.toClass}）：" + diffs.ifBlank { "面板无变化" },
                            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.bodySmall.tabularNumbers(),
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }

            // Exact Final 9-Stat Integer Panel + Remaining Accumulator
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    "终态精确面板（固定成长满 100 进 1 · 含余数与上限）",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                EngageStat.entries.chunked(3).forEach { rowStats ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        rowStats.forEach { st ->
                            val idx = st.ordinal
                            val finalV = report.finalStats[idx]
                            val netGain = report.totalNetGains[idx]
                            val cap = report.finalCaps[idx]
                            val rem = report.finalAccumulators[idx]
                            val capped = finalV >= cap
                            Surface(
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                                color = when {
                                    capped -> highPal.container
                                    netGain < 0 -> lowPal.container
                                    else -> MaterialTheme.colorScheme.surfaceContainerLow
                                },
                            ) {
                                Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(st.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(
                                            "$finalV" + if (capped) "满" else "",
                                            style = MaterialTheme.typography.labelMedium.tabularNumbers(),
                                            fontWeight = FontWeight.Bold,
                                            color = if (capped) highPal.content else MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                    val gainStr = if (netGain >= 0) "+$netGain" else "$netGain"
                                    Text(
                                        "$gainStr · 余$rem",
                                        style = MaterialTheme.typography.labelSmall.tabularNumbers(),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (report.alternativeAdvancedClasses.isNotEmpty()) {
                Text(
                    "同权重高分备选高级职业：${report.alternativeAdvancedClasses.joinToString(" · ") { it.first }}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            report.warnings.forEach { w ->
                Text("提示：$w", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // Expandable Level-by-Level Fixed Growth Table
            if (report.levelSteps.isNotEmpty()) {
                Surface(
                    onClick = toggle,
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth().testTag("engage-toggle-steps"),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (expanded) "收起逐级固定成长明细"
                                else "展开逐级固定成长明细（共 ${report.levelSteps.size} 级 · 含每级跳点与余数）",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (!expanded) {
                                Text(
                                    "查看每一级确定性加点（+1/+2）、当前面板与满 100 进 1 剩余积分",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Icon(
                            if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                if (expanded) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        listOf(false to "显示每级跳点", true to "显示面板(余数)").forEach { (isAccMode, label) ->
                            val sel = showAccumulatorsInTable == isAccMode
                            Surface(
                                onClick = { showAccumulatorsInTable = isAccMode },
                                shape = RoundedCornerShape(100.dp),
                                color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLow,
                                contentColor = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            ) {
                                Text(label, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    EngageLevelPreviewTable(
                        proposal = proposal,
                        showStatsAndRemainder = showAccumulatorsInTable,
                        highPal = highPal,
                        modifier = Modifier.fillMaxWidth().testTag("engage-steps-table"),
                    )
                }
            }
        }
    }
}

@Composable
private fun EngageLevelPreviewTable(
    proposal: AgentEngageProposal,
    showStatsAndRemainder: Boolean,
    highPal: StatTierPalette,
    modifier: Modifier = Modifier,
) {
    val report = proposal.report
    val outline = MaterialTheme.colorScheme.outlineVariant
    val headerBg = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
    val headerFg = MaterialTheme.colorScheme.onPrimaryContainer
    val altRowBg = MaterialTheme.colorScheme.surfaceContainerLow
    val colWidth = if (showStatsAndRemainder) 42.dp else 28.dp

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, outline),
        modifier = modifier,
    ) {
        Column(Modifier.horizontalScroll(rememberScrollState()).width(IntrinsicSize.Max)) {
            Row(
                Modifier.fillMaxWidth().background(headerBg).padding(horizontal = 8.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("等级", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = headerFg, modifier = Modifier.width(56.dp))
                Text("职业", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = headerFg, modifier = Modifier.width(92.dp))
                Text("跳点", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = headerFg, modifier = Modifier.width(34.dp))
                EngageStat.entries.forEach { st ->
                    Text(st.shortLabel, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = headerFg, modifier = Modifier.width(colWidth))
                }
            }
            report.levelSteps.forEachIndexed { idx, step ->
                HorizontalDivider(color = outline.copy(alpha = 0.45f))
                val switched = step.switchedFromClass != null
                val rowTotal = step.gains.sum()
                val bg = when {
                    switched -> highPal.container.copy(alpha = 0.48f)
                    idx % 2 == 1 -> altRowBg
                    else -> MaterialTheme.colorScheme.surface
                }
                Row(
                    Modifier.fillMaxWidth().background(bg).padding(horizontal = 8.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Lv.${step.levelToInClass}",
                        style = MaterialTheme.typography.labelSmall.tabularNumbers(),
                        fontWeight = if (switched) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.width(56.dp),
                    )
                    Text(
                        (if (switched) "转·" else "") + step.className,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (switched) FontWeight.Bold else FontWeight.Normal,
                        color = if (switched) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(92.dp),
                    )
                    Text(
                        "+$rowTotal",
                        style = MaterialTheme.typography.labelSmall.tabularNumbers(),
                        fontWeight = FontWeight.Bold,
                        color = if (rowTotal >= 5) highPal.content else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.width(34.dp),
                    )
                    EngageStat.entries.forEachIndexed { sIdx, _ ->
                        val g = step.gains[sIdx]
                        val cellText = if (showStatsAndRemainder) {
                            "${step.statsAfter[sIdx]}(${step.accumulatorsAfter[sIdx]})"
                        } else {
                            if (g > 0) "+$g" else "·"
                        }
                        Text(
                            cellText,
                            style = MaterialTheme.typography.labelSmall.tabularNumbers(),
                            fontWeight = if (g > 0) FontWeight.Bold else FontWeight.Normal,
                            color = if (g > 0) highPal.content else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                            modifier = Modifier.width(colWidth),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun OfflineThreeHousesPlannerCard(
    characters: List<ThreeHousesCharacterData>,
    classes: List<ThreeHousesClassData>,
    onSolved: (AgentThreeHousesProposal) -> Unit,
) {
    var selectedChar by remember(characters) {
        mutableStateOf(
            characters.firstOrNull { it.name.contains("英谷莉特") }
                ?: characters.firstOrNull { it.name.contains("艾黛尔贾特") }
                ?: characters.firstOrNull(),
        )
    }
    var charQuery by remember(selectedChar) { mutableStateOf(selectedChar?.name.orEmpty()) }
    var targetLvText by remember { mutableStateOf("40") }
    var armorDipLv10 by remember { mutableStateOf(true) }
    var includeDlc by remember { mutableStateOf(true) }
    var weights by remember { mutableStateOf(listOf(1, 5, 0, 2, 4, 1, 2, 0, 1)) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    EditorialCard {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("《风花雪月》期望成长与转职规划", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text(
                "基于离线 40 名角色与全部可用兵种，精确计算 2 点保底重抽期望、转职保底补点与职业能力补正。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            FuzzySearchBox(
                query = charQuery,
                onQueryChange = { charQuery = it },
                placeholder = "模糊搜角色（如 英谷莉特 / 菲力克斯 / 贝雷丝）",
                suggestionsProvider = { q ->
                    characters.mapNotNull { ch ->
                        val sc = maxOf(
                            FuzzyMatcher.score(q, ch.name, ch.affiliation),
                            FuzzyMatcher.score(q, ch.englishName, ch.affiliation),
                        )
                        if (sc > 0) FuzzySuggestion(ch.entryId, ch.name, "初始 Lv.${ch.startLevel} · ${ch.startClassName}", ch.affiliation, score = sc)
                        else null
                    }.sortedByDescending { it.score }.take(7)
                },
                onSelectSuggestion = { sug ->
                    characters.firstOrNull { it.entryId == sug.id }?.let { ch ->
                        selectedChar = ch
                        charQuery = ch.name
                    }
                },
                onSubmitSearch = { q ->
                    ThreeHousesGrowthPlanner.resolveCharacter(q, characters)?.let { ch ->
                        selectedChar = ch
                        charQuery = ch.name
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                fieldTestTag = "th-planner-char-search",
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = targetLvText,
                    onValueChange = { v -> if (v.length <= 2 && v.all(Char::isDigit)) targetLvText = v },
                    label = { Text("目标等级（2~99）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f).testTag("th-planner-target-lv"),
                    shape = RoundedCornerShape(12.dp),
                )
                Column(Modifier.weight(1.4f)) {
                    Text("Lv.10 考证蹭重骑士防守 12 保底", style = MaterialTheme.typography.labelMedium)
                    Switch(checked = armorDipLv10, onCheckedChange = { armorDipLv10 = it }, modifier = Modifier.testTag("th-planner-armor-dip"))
                }
            }

            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    "物理飞兵" to listOf(1, 5, 0, 2, 4, 1, 2, 0, 1),
                    "纯力输出" to listOf(1, 5, 0, 3, 2, 1, 1, 0, 1),
                    "法系爆发" to listOf(1, 0, 5, 2, 3, 1, 0, 2, 1),
                    "前排重甲" to listOf(3, 3, 0, 1, 0, 1, 5, 2, 1),
                ).forEach { (label, vec) ->
                    Surface(
                        onClick = { weights = vec },
                        shape = RoundedCornerShape(100.dp),
                        color = if (weights == vec) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Text(label, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("包含煤暗之章 DLC 特殊职业", style = MaterialTheme.typography.bodySmall)
                Switch(checked = includeDlc, onCheckedChange = { includeDlc = it })
            }

            errorMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

            Button(
                enabled = selectedChar != null && classes.isNotEmpty(),
                onClick = {
                    val ch = selectedChar ?: return@Button
                    val targetLv = (targetLvText.toIntOrNull() ?: 40).coerceIn(ch.startLevel + 1, 99)
                    val dips = if (armorDipLv10 && ch.startLevel <= 10 && targetLv >= 10) {
                        listOf(ThreeHousesCertDipSpec(atLevel = 10, className = "重骑士"))
                    } else emptyList()
                    runCatching {
                        val report = ThreeHousesGrowthPlanner.plan(
                            ThreeHousesPlanRequest(
                                characterName = ch.name,
                                targetLevel = targetLv,
                                weights = weights,
                                extraCertDips = dips,
                                includeDlcClasses = includeDlc,
                            ),
                            characters = characters,
                            classes = classes,
                        )
                        AgentThreeHousesProposal(report = report, extraCertDips = dips, includeDlcClasses = includeDlc)
                    }.onSuccess { prop ->
                        errorMsg = null
                        onSolved(prop)
                    }.onFailure { err ->
                        errorMsg = err.message ?: "规划失败"
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("th-planner-solve"),
            ) {
                Text("生成《风花雪月》期望转职规划")
            }
        }
    }
}

@Composable
internal fun OfflineEngagePlannerCard(
    characters: List<EngageCharacterData>,
    classes: List<EngageClassData>,
    onSolved: (AgentEngageProposal) -> Unit,
) {
    var selectedChar by remember(characters) {
        mutableStateOf(
            characters.firstOrNull { it.name.startsWith("琉尔") }
                ?: characters.firstOrNull(),
        )
    }
    var charQuery by remember(selectedChar) { mutableStateOf(selectedChar?.name.orEmpty()) }
    var totalLevelsText by remember { mutableStateOf("25") }
    var starsphere by remember { mutableStateOf(false) }
    var selectedTargetClass by remember(selectedChar) { mutableStateOf<String?>(null) }
    var weights by remember { mutableStateOf(listOf(1, 5, 0, 2, 5, 1, 1, 0, 2)) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val legalAdvClasses = remember(selectedChar, classes) {
        val ch = selectedChar ?: return@remember emptyList()
        classes.filter { it.tier in setOf("高级", "特殊") && EngageRules.isClassAllowed(ch, it) }
    }

    EditorialCard {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("《Engage》固定成长确定性推算器", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text(
                "100% 还原超难/固定成长积分制（初始积分 = 个人成长 % 100，满 100 进 1，转职无缝继承积分，自动生效贾恩双倍职业成长）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            FuzzySearchBox(
                query = charQuery,
                onQueryChange = { charQuery = it },
                placeholder = "模糊搜角色（如 琉尔 / 克萝艾 / 安娜 / 贾恩）",
                suggestionsProvider = { q ->
                    characters.mapNotNull { ch ->
                        val sc = FuzzyMatcher.score(q, ch.name, ch.affiliation, ch.initialClassName)
                        if (sc > 0) FuzzySuggestion(ch.entryId, ch.name, "初始 Lv.${ch.initialLevel} · ${ch.initialClassName}", ch.affiliation, score = sc)
                        else null
                    }.sortedByDescending { it.score }.take(7)
                },
                onSelectSuggestion = { sug ->
                    characters.firstOrNull { it.entryId == sug.id }?.let { ch ->
                        selectedChar = ch
                        charQuery = ch.name
                    }
                },
                onSubmitSearch = { q ->
                    EngageFixedGrowthPlanner.resolveCharacter(q, characters)?.let { ch ->
                        selectedChar = ch
                        charQuery = ch.name
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                fieldTestTag = "engage-planner-char-search",
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = totalLevelsText,
                    onValueChange = { v -> if (v.length <= 2 && v.all(Char::isDigit)) totalLevelsText = v },
                    label = { Text("升级总次数（1~60）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f).testTag("engage-planner-levels"),
                    shape = RoundedCornerShape(12.dp),
                )
                Column(Modifier.weight(1.2f)) {
                    Text("琪姬·星玉加护（全+15%）", style = MaterialTheme.typography.labelMedium)
                    Switch(checked = starsphere, onCheckedChange = { starsphere = it }, modifier = Modifier.testTag("engage-planner-starsphere"))
                }
            }

            // Target Advanced Class Quick Picker (or Auto Best)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("目标高级职业（可自动优选或指定）：", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val autoSel = selectedTargetClass == null
                    Surface(
                        onClick = { selectedTargetClass = null },
                        shape = RoundedCornerShape(100.dp),
                        color = if (autoSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLow,
                        contentColor = if (autoSel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    ) {
                        Text("按权重自动优选", Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall)
                    }
                    legalAdvClasses.take(14).forEach { cls ->
                        val sel = selectedTargetClass == cls.name
                        Surface(
                            onClick = { selectedTargetClass = cls.name },
                            shape = RoundedCornerShape(100.dp),
                            color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLow,
                            contentColor = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        ) {
                            Text(cls.name, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    "力速物攻" to listOf(1, 5, 0, 2, 5, 1, 1, 0, 2),
                    "魔速法师" to listOf(1, 0, 5, 2, 5, 1, 0, 2, 2),
                    "高防坦克" to listOf(3, 3, 0, 1, 0, 1, 5, 2, 3),
                    "均衡培养" to listOf(2, 2, 2, 2, 2, 2, 2, 2, 2),
                ).forEach { (label, vec) ->
                    Surface(
                        onClick = { weights = vec },
                        shape = RoundedCornerShape(100.dp),
                        color = if (weights == vec) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Text(label, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            errorMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

            Button(
                enabled = selectedChar != null && classes.isNotEmpty(),
                onClick = {
                    val ch = selectedChar ?: return@Button
                    val totalUps = (totalLevelsText.toIntOrNull() ?: 25).coerceIn(1, 60)
                    val customStages = selectedTargetClass?.let { targetName ->
                        val startCls = EngageFixedGrowthPlanner.resolveStartingClass(ch, classes)
                        val beforePromo = if (startCls?.tier == "基础" && ch.initialLevel < 10) {
                            minOf(10 - ch.initialLevel, totalUps)
                        } else 0
                        val afterPromo = (totalUps - beforePromo).coerceAtLeast(0)
                        buildList {
                            if (beforePromo > 0 && startCls != null) add(EngageStageSpec(startCls.name, beforePromo, starsphere))
                            if (afterPromo > 0 || isEmpty()) add(EngageStageSpec(targetName, afterPromo.coerceAtLeast(1), starsphere))
                        }
                    }.orEmpty()
                    runCatching {
                        val report = EngageFixedGrowthPlanner.solve(
                            EngageFixedGrowthRequest(
                                characterName = ch.name,
                                stages = customStages,
                                totalLevelUps = totalUps,
                                starsphere = starsphere,
                                weights = weights,
                            ),
                            characters = characters,
                            classes = classes,
                        )
                        AgentEngageProposal(
                            report = report,
                            requestedStages = customStages,
                            requestedTotalLevels = totalUps,
                            starsphere = starsphere,
                        )
                    }.onSuccess { prop ->
                        errorMsg = null
                        onSolved(prop)
                    }.onFailure { err ->
                        errorMsg = err.message ?: "推算失败"
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("engage-planner-solve"),
            ) {
                Text("精确推算《Engage》固定成长")
            }
        }
    }
}
