package com.fireemblem.companion.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MilitaryTech
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fireemblem.companion.R
import com.fireemblem.companion.content.GameStatBenchmarks
import com.fireemblem.companion.content.SkillMatrixItem
import com.fireemblem.companion.content.SkillMatrixTrack
import com.fireemblem.companion.content.SkillProficiency
import com.fireemblem.companion.content.StatMetricKind
import com.fireemblem.companion.content.StatRatingTier
import com.fireemblem.companion.ui.theme.tabularNumbers

internal data class FactionVisual(
    @DrawableRes val iconRes: Int,
    val container: Color,
    val content: Color,
    val accent: Color,
)

@Composable
internal fun factionVisualFor(gameId: String, label: String): FactionVisual {
    val dark = isSystemInDarkTheme()
    return when {
        // Three Houses Houses & Factions
        label.contains("黑鹫") -> if (dark) {
            FactionVisual(R.drawable.ic_house_black_eagles, Color(0xFF4A2524), Color(0xFFFFDAD6), Color(0xFFE8857D))
        } else {
            FactionVisual(R.drawable.ic_house_black_eagles, Color(0xFFF9E2E0), Color(0xFF7D241E), Color(0xFFA8332B))
        }
        label.contains("青狮子") -> if (dark) {
            FactionVisual(R.drawable.ic_house_blue_lions, Color(0xFF20354A), Color(0xFFD6E9FF), Color(0xFF7CB0E4))
        } else {
            FactionVisual(R.drawable.ic_house_blue_lions, Color(0xFFE0ECF8), Color(0xFF1F4770), Color(0xFF2E5E91))
        }
        label.contains("金鹿") -> if (dark) {
            FactionVisual(R.drawable.ic_house_golden_deer, Color(0xFF45371B), Color(0xFFFFE5A6), Color(0xFFE0B44F))
        } else {
            FactionVisual(R.drawable.ic_house_golden_deer, Color(0xFFF9EED2), Color(0xFF6B4C0B), Color(0xFF9C7214))
        }
        label.contains("灰狼") -> if (dark) {
            FactionVisual(R.drawable.ic_house_ashen_wolves, Color(0xFF352C44), Color(0xFFE6DCFA), Color(0xFFB5A0DF))
        } else {
            FactionVisual(R.drawable.ic_house_ashen_wolves, Color(0xFFECE6F6), Color(0xFF483668), Color(0xFF674E91))
        }
        label.contains("教会") || label.contains("骑士团") -> if (dark) {
            FactionVisual(R.drawable.ic_house_church, Color(0xFF243A2A), Color(0xFFD4EED9), Color(0xFF84B88F))
        } else {
            FactionVisual(R.drawable.ic_house_church, Color(0xFFE2EFE5), Color(0xFF275031), Color(0xFF3E704A))
        }
        // Engage Regions
        label.contains("里特司") -> if (dark) {
            FactionVisual(R.drawable.ic_region_lythos, Color(0xFF26384B), Color(0xFFDDEEFF), Color(0xFF8CC0F0))
        } else {
            FactionVisual(R.drawable.ic_region_lythos, Color(0xFFE5F0FA), Color(0xFF234B72), Color(0xFF386B9C))
        }
        label.contains("费列聂") -> if (dark) {
            FactionVisual(R.drawable.ic_region_firene, Color(0xFF243C31), Color(0xFFD3F1E2), Color(0xFF7AC29E))
        } else {
            FactionVisual(R.drawable.ic_region_firene, Color(0xFFE1F2E9), Color(0xFF1E573B), Color(0xFF347C57))
        }
        label.contains("布罗帝亚") -> if (dark) {
            FactionVisual(R.drawable.ic_region_brodia, Color(0xFF4B2823), Color(0xFFFFDBD4), Color(0xFFE48877))
        } else {
            FactionVisual(R.drawable.ic_region_brodia, Color(0xFFF8E3DF), Color(0xFF78281D), Color(0xFFA83E2F))
        }
        label.contains("伊尔席翁") -> if (dark) {
            FactionVisual(R.drawable.ic_region_elusia, Color(0xFF302E49), Color(0xFFE2E0FF), Color(0xFFA4A0E6))
        } else {
            FactionVisual(R.drawable.ic_region_elusia, Color(0xFFEAEAF8), Color(0xFF3A3770), Color(0xFF55519B))
        }
        label.contains("索尔姆") -> if (dark) {
            FactionVisual(R.drawable.ic_region_solm, Color(0xFF4A351C), Color(0xFFFFE2B5), Color(0xFFE3AA59))
        } else {
            FactionVisual(R.drawable.ic_region_solm, Color(0xFFF9EBDA), Color(0xFF704512), Color(0xFFA86A1E))
        }
        label.contains("邪龙") || label.contains("其他") -> if (dark) {
            FactionVisual(R.drawable.ic_region_fell, Color(0xFF3A283C), Color(0xFFF1D9F5), Color(0xFFC492CC))
        } else {
            FactionVisual(R.drawable.ic_region_fell, Color(0xFFF2E6F4), Color(0xFF582E60), Color(0xFF7A4485))
        }
        gameId == "three-houses" -> if (dark) {
            FactionVisual(R.drawable.ic_house_other, Color(0xFF3B3429), Color(0xFFF1E6D2), Color(0xFFCBB68E))
        } else {
            FactionVisual(R.drawable.ic_house_other, Color(0xFFF1ECE2), Color(0xFF564830), Color(0xFF7D6846))
        }
        else -> if (dark) {
            FactionVisual(R.drawable.ic_house_other, Color(0xFF2E2D2A), Color(0xFFE8E6DC), Color(0xFFD97757))
        } else {
            FactionVisual(R.drawable.ic_house_other, Color(0xFFF3F1E8), Color(0xFF423E38), Color(0xFFB85A3A))
        }
    }
}

@DrawableRes
internal fun skillTrackDrawable(trackKeyOrLabel: String): Int {
    val k = trackKeyOrLabel.lowercase()
    return when {
        k == "sword" || "剑" in k -> R.drawable.ic_skill_sword
        k == "lance" || "枪" in k -> R.drawable.ic_skill_lance
        k == "axe" || "斧" in k -> R.drawable.ic_skill_axe
        k == "bow" || "弓" in k || "箭" in k -> R.drawable.ic_skill_bow
        k == "brawl" || "格斗" in k || "体术" in k -> R.drawable.ic_skill_brawl
        k == "reason" || "理学" in k || "黑魔" in k || "魔法" in k || "魔道" in k -> R.drawable.ic_skill_reason
        k == "faith" || "信仰" in k || "白魔" in k || "杖" in k -> R.drawable.ic_skill_faith
        k == "authority" || "指挥" in k || "圣焰" in k -> R.drawable.ic_skill_authority
        k == "armor" || "重装" in k -> R.drawable.ic_skill_armor
        k == "riding" || "马术" in k -> R.drawable.ic_skill_riding
        k == "flying" || "飞行" in k -> R.drawable.ic_skill_flying
        else -> R.drawable.ic_skill_dagger
    }
}

internal data class StatTierPalette(
    val container: Color,
    val content: Color,
    val bar: Color,
    val badgeContainer: Color,
)

@Composable
internal fun statTierPalette(tier: StatRatingTier): StatTierPalette {
    val dark = isSystemInDarkTheme()
    return when (tier) {
        StatRatingTier.LOW -> if (dark) {
            StatTierPalette(
                container = Color(0xFF3E2422),
                content = Color(0xFFFFDAD6),
                bar = Color(0xFFE58377),
                badgeContainer = Color(0xFF5C2F2B),
            )
        } else {
            StatTierPalette(
                container = Color(0xFFF9EAE7),
                content = Color(0xFF7D2A23),
                bar = Color(0xFFC25348),
                badgeContainer = Color(0xFFF2D4CE),
            )
        }
        StatRatingTier.MEDIUM -> if (dark) {
            StatTierPalette(
                container = Color(0xFF382E1B),
                content = Color(0xFFFFE4B0),
                bar = Color(0xFFD8AB57),
                badgeContainer = Color(0xFF524224),
            )
        } else {
            StatTierPalette(
                container = Color(0xFFF8F0DF),
                content = Color(0xFF6B480C),
                bar = Color(0xFFBF8C2E),
                badgeContainer = Color(0xFFEEE0BE),
            )
        }
        StatRatingTier.HIGH -> if (dark) {
            StatTierPalette(
                container = Color(0xFF1E3729),
                content = Color(0xFFC5F0D2),
                bar = Color(0xFF70BA88),
                badgeContainer = Color(0xFF2B523B),
            )
        } else {
            StatTierPalette(
                container = Color(0xFFE5F2E8),
                content = Color(0xFF1D5236),
                bar = Color(0xFF397E56),
                badgeContainer = Color(0xFFCBE5D2),
            )
        }
    }
}

internal data class StatTileEntry(
    val label: String,
    val displayValue: String,
    val rawValue: String = displayValue,
)

@Composable
internal fun RatedStatGridCard(
    title: String,
    gameShortTitle: String,
    metricKind: StatMetricKind,
    benchmarks: GameStatBenchmarks,
    stats: List<StatTileEntry>,
    metaBadges: List<Pair<String, String>> = emptyList(),
    summaryBadge: Pair<String, StatRatingTier?>? = null,
    modifier: Modifier = Modifier,
) {
    if (stats.isEmpty() && metaBadges.isEmpty()) return
    EditorialCard(modifier = modifier.fillMaxWidth().testTag("rated-stat-card:$title")) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                summaryBadge?.let { (text, tier) ->
                    val pal = tier?.let { statTierPalette(it) }
                    EditorialBadge(
                        text = text,
                        containerColor = pal?.badgeContainer ?: MaterialTheme.colorScheme.primaryContainer,
                        contentColor = pal?.content ?: MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            if (metaBadges.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    metaBadges.forEach { (k, v) ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            Row(
                                Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(k, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(v, style = MaterialTheme.typography.labelMedium.tabularNumbers(), fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            // Explicit 3-tier color + text legend (WCAG non-color-only)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatRatingTier.entries.forEach { tier ->
                    val pal = statTierPalette(tier)
                    Surface(
                        modifier = Modifier.weight(1f),
                        color = pal.container,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, pal.bar.copy(alpha = 0.32f)),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(pal.bar))
                            Spacer(Modifier.size(5.dp))
                            Text(
                                "${tier.shortLabel} · ${tier.label}",
                                style = MaterialTheme.typography.labelSmall,
                                color = pal.content,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val cols = if (maxWidth >= 560.dp) 5 else 3
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    stats.chunked(cols).forEach { rowItems ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowItems.forEach { item ->
                                val rated = benchmarks.evaluate(metricKind, item.label, item.rawValue)
                                val pal = rated?.tier?.let { statTierPalette(it) }
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("rated-stat-tile:$title:${item.label}"),
                                    color = pal?.container ?: MaterialTheme.colorScheme.surfaceContainerLow,
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(
                                        1.dp,
                                        pal?.bar?.copy(alpha = 0.28f) ?: MaterialTheme.colorScheme.outlineVariant,
                                    ),
                                ) {
                                    Column(
                                        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
                                        verticalArrangement = Arrangement.spacedBy(5.dp),
                                    ) {
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                item.label,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = pal?.content?.copy(alpha = 0.85f) ?: MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            rated?.let {
                                                Surface(
                                                    color = pal!!.badgeContainer,
                                                    shape = RoundedCornerShape(6.dp),
                                                ) {
                                                    Text(
                                                        it.tier.shortLabel,
                                                        Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = pal.content,
                                                        fontWeight = FontWeight.SemiBold,
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            item.displayValue,
                                            style = MaterialTheme.typography.titleMedium.tabularNumbers(),
                                            color = pal?.content ?: MaterialTheme.colorScheme.onSurface,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                        )
                                        rated?.let {
                                            Box(
                                                Modifier
                                                    .fillMaxWidth()
                                                    .height(4.dp)
                                                    .clip(RoundedCornerShape(2.dp))
                                                    .background(pal!!.badgeContainer),
                                            ) {
                                                Box(
                                                    Modifier
                                                        .fillMaxWidth(it.progress)
                                                        .height(4.dp)
                                                        .clip(RoundedCornerShape(2.dp))
                                                        .background(pal.bar),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            repeat(cols - rowItems.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }

            Text(
                benchmarks.legendSummary(metricKind, gameShortTitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun CompactNumericGridCard(
    title: String,
    items: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    EditorialCard(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val cols = if (maxWidth >= 560.dp) 4 else 3
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items.chunked(cols).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { (label, value) ->
                                Surface(
                                    modifier = Modifier.weight(1f),
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                ) {
                                    Column(
                                        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(3.dp),
                                    ) {
                                        Text(
                                            label,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            value,
                                            style = MaterialTheme.typography.titleSmall.tabularNumbers(),
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                            repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

internal fun isUniversalSkillName(name: String): Boolean {
    val clean = name.trim()
    if (clean.isEmpty()) return false
    val upper = clean.uppercase()
    if (Regex("LV\\.?[1-5]$").containsMatchIn(upper)) return true
    if (clean.endsWith("的奥妙") || clean.endsWith("专家") || clean.endsWith("达人") || clean.endsWith("必杀+10") || clean.endsWith("必杀＋10")) return true
    if (clean.endsWith("特效无效") || clean.startsWith("减轻重量") || clean.startsWith("负重")) return true
    return clean in UNIVERSAL_SKILL_NAMES
}

private val UNIVERSAL_SKILL_NAMES = setOf(
    "步行移动术", "步行移动术+", "步行移动术＋",
    "短兵相接", "短兵相接+", "短兵相接＋",
    "闪身", "闪身+", "闪身＋",
    "骑士的感觉", "骑士先锋", "骑士先锋+", "骑士先锋＋",
    "骑兵移动术", "骑兵移动术+", "骑兵移动术＋",
    "飞行移动术", "飞行移动术+", "飞行移动术＋",
    "警戒姿态", "警戒姿态+", "警戒姿态＋",
    "黑魔法射程+1", "黑魔法射程＋1", "白魔法射程+1", "白魔法射程＋1",
    "技巧 +4", "技巧+4", "技巧＋4", "移动力 +1", "移动力+1", "移动力＋1",
)

@Composable
internal fun GamePackSemanticBadgeTile(
    gameId: String,
    categoryId: String,
    title: String,
    subtitle: String,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val dark = isSystemInDarkTheme()
    val combined = "$title $subtitle"
    val faction = when {
        categoryId == "country-investment" || categoryId == "routes" -> factionVisualFor(gameId, combined)
        combined.contains("黑鹫") || combined.contains("红花") -> factionVisualFor("three-houses", "黑鹫")
        combined.contains("青狮子") || combined.contains("苍月") -> factionVisualFor("three-houses", "青狮子")
        combined.contains("金鹿") || combined.contains("翠风") -> factionVisualFor("three-houses", "金鹿")
        combined.contains("灰狼") || combined.contains("煤暗") -> factionVisualFor("three-houses", "灰狼")
        combined.contains("银雪") || combined.contains("教会") -> factionVisualFor("three-houses", "教会")
        combined.contains("费列聂") -> factionVisualFor("engage", "费列聂")
        combined.contains("布罗帝亚") -> factionVisualFor("engage", "布罗帝亚")
        combined.contains("伊尔席翁") -> factionVisualFor("engage", "伊尔席翁")
        combined.contains("索尔姆") -> factionVisualFor("engage", "索尔姆")
        combined.contains("邪龙") -> factionVisualFor("engage", "邪龙")
        else -> null
    }

    val containerBg = faction?.container ?: when (categoryId) {
        "chapters", "battles", "paralogues", "dlc", "dlc-chapters" ->
            if (dark) Color(0xFF283442) else Color(0xFFE7EFF7)
        "classes", "growth", "weapon-proficiency" ->
            if (dark) Color(0xFF3A2C22) else Color(0xFFF6ECE3)
        "skills", "engravings", "growth-mechanics", "game-mechanics", "mechanics" ->
            if (dark) Color(0xFF2A362C) else Color(0xFFE6F1E8)
        "crests", "emblems", "battalions" ->
            if (dark) Color(0xFF392B3E) else Color(0xFFF1E8F5)
        "equipment", "weapons", "items", "well-drops", "monastery" ->
            if (dark) Color(0xFF3B3222) else Color(0xFFF7EFE0)
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }
    val tintColor = faction?.accent ?: when (categoryId) {
        "chapters", "battles", "paralogues", "dlc", "dlc-chapters" ->
            if (dark) Color(0xFF8ABAF0) else Color(0xFF295687)
        "classes", "growth", "weapon-proficiency" ->
            if (dark) Color(0xFFE4A076) else Color(0xFF9C4A22)
        "skills", "engravings", "growth-mechanics", "game-mechanics", "mechanics" ->
            if (dark) Color(0xFF82C496) else Color(0xFF25613D)
        "crests", "emblems", "battalions" ->
            if (dark) Color(0xFFC89EE0) else Color(0xFF6B3985)
        "equipment", "weapons", "items", "well-drops", "monastery" ->
            if (dark) Color(0xFFE0BA6E) else Color(0xFF855E17)
        else -> MaterialTheme.colorScheme.primary
    }

    val chapterCode = if (categoryId in setOf("chapters", "battles", "paralogues", "dlc", "dlc-chapters")) {
        Regex("(序章|终章|第\\s*\\d+\\s*章|外传\\s*\\d*|#\\d+)").find(combined)?.value?.replace(" ", "")
    } else null

    val microTag = when {
        chapterCode != null -> chapterCode
        categoryId == "country-investment" -> "投资"
        categoryId == "classes" || categoryId == "growth" -> when {
            "最高级" in combined -> "最高"
            "高级" in combined -> "高级"
            "中级" in combined -> "中级"
            "初级" in combined -> "初级"
            "基础" in combined -> "基础"
            "专属" in combined -> "专属"
            "特殊" in combined -> "特殊"
            else -> "职业"
        }
        categoryId == "weapon-proficiency" -> "资质"
        categoryId == "growth-mechanics" -> "成长"
        categoryId == "game-mechanics" || categoryId == "mechanics" -> "机制"
        categoryId == "crests" -> "纹章"
        categoryId == "battalions" -> "军团"
        categoryId == "routes" -> "路线"
        categoryId == "skills" -> when {
            "战技" in combined -> "战技"
            "魔法" in combined -> "魔法"
            else -> "特技"
        }
        else -> null
    }

    val customDrawable: Int? = when {
        faction != null && (categoryId in setOf("country-investment", "routes", "chapters", "battles", "dlc")) -> faction.iconRes
        categoryId in setOf("weapon-proficiency", "classes", "growth", "skills", "equipment", "weapons") -> {
            val matchText = combined
            when {
                anyIn(matchText, "飞行", "天马", "飞龙", "鹰狮", "翼") -> R.drawable.ic_skill_flying
                anyIn(matchText, "骑马", "骑兵", "轻骑", "圣骑", "弓骑", "狼骑", "马术", "骑士") -> R.drawable.ic_skill_riding
                anyIn(matchText, "重甲", "重装", "堡垒", "将领", "盾") -> R.drawable.ic_skill_armor
                anyIn(matchText, "魔道", "理学", "黑魔", "暗魔", "魔法", "术士", "贤者", "圣者", "主教") -> R.drawable.ic_skill_reason
                anyIn(matchText, "信仰", "白魔", "杖", "僧侣", "修道", "回复", "圣疗") -> R.drawable.ic_skill_faith
                anyIn(matchText, "弓", "箭", "狙击") -> R.drawable.ic_skill_bow
                anyIn(matchText, "斧", "狂战", "勇士", "土匪") -> R.drawable.ic_skill_axe
                anyIn(matchText, "枪", "矛", "戟", "士兵") -> R.drawable.ic_skill_lance
                anyIn(matchText, "体术", "格斗", "气功", "拳", "武僧", "舞者", "舞蹈") -> R.drawable.ic_skill_brawl
                anyIn(matchText, "短剑", "盗贼", "刺客", "隐密", "匕首") -> R.drawable.ic_skill_dagger
                anyIn(matchText, "指挥", "计策", "神龙", "领主", "皇帝") -> R.drawable.ic_skill_authority
                else -> R.drawable.ic_skill_sword
            }
        }
        else -> null
    }

    val fallbackVector = when (categoryId) {
        "chapters", "battles", "paralogues", "routes", "dlc", "dlc-chapters" -> Icons.Outlined.Map
        "crests", "emblems" -> Icons.Outlined.WorkspacePremium
        "battalions", "supports", "social" -> Icons.Outlined.Groups
        "growth-mechanics", "game-mechanics", "mechanics", "skills" -> Icons.Outlined.AutoFixHigh
        "achievements", "relay-trials" -> Icons.Outlined.MilitaryTech
        "items", "well-drops", "animal-drops", "monastery" -> Icons.Outlined.Inventory2
        else -> Icons.Outlined.Book
    }

    val isLarge = size >= 76.dp
    Surface(
        modifier = modifier.size(size),
        shape = RoundedCornerShape(if (isLarge) 16.dp else 13.dp),
        color = containerBg,
        border = BorderStroke(1.dp, tintColor.copy(alpha = 0.30f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (customDrawable != null) {
                Icon(
                    painter = painterResource(customDrawable),
                    contentDescription = null,
                    tint = tintColor,
                    modifier = Modifier.size(if (isLarge) 34.dp else 24.dp),
                )
            } else {
                Icon(
                    imageVector = fallbackVector,
                    contentDescription = null,
                    tint = tintColor,
                    modifier = Modifier.size(if (isLarge) 32.dp else 22.dp),
                )
            }
            if (!microTag.isNullOrBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = microTag,
                    style = MaterialTheme.typography.labelSmall.tabularNumbers(),
                    color = faction?.content ?: tintColor,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun anyIn(text: String, vararg keys: String): Boolean = keys.any { text.contains(it) }

@Composable
internal fun StructuredSkillMatrixCard(
    tracks: List<SkillMatrixTrack>,
    onOpenEntry: (String) -> Unit,
    universalFilterLabel: String = "通用技能",
    strengthLabel: String = "擅长",
    skillIconContent: (@Composable (SkillMatrixItem) -> Unit)? = null,
    trackIconContent: (@Composable (SkillMatrixTrack) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var filterMode by rememberSaveable { mutableStateOf("all") } // all | char | universal
    var hideUniversal by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    val hasUniversal = tracks.any { t -> t.items.any { it.isUniversal } }
    val hasCharSpecific = tracks.any { t -> t.items.any { !it.isUniversal } }

    val filteredTracks = tracks.mapNotNull { track ->
        val matchingItems = track.items.filter { item ->
            val passHide = !hideUniversal || !item.isUniversal
            val passMode = when (filterMode) {
                "char" -> !item.isUniversal
                "universal" -> item.isUniversal
                else -> true
            }
            val q = search.trim()
            val passQuery = q.isEmpty() ||
                item.name.contains(q, ignoreCase = true) ||
                item.requirement.contains(q, ignoreCase = true) ||
                track.trackLabel.contains(q, ignoreCase = true)
            passHide && passMode && passQuery
        }
        val keepEmptyTrack = search.isBlank() && filterMode != "universal" &&
            (track.proficiency != SkillProficiency.NEUTRAL || (track.initialRank != "—" && track.initialRank != "E"))
        if (matchingItems.isEmpty() && !keepEmptyTrack) null
        else track.copy(items = matchingItems)
    }
    val totalItemCount = tracks.sumOf { it.items.size }
    val visibleItemCount = filteredTracks.sumOf { it.items.size }

    EditorialCard(modifier = modifier.fillMaxWidth().testTag("structured-skill-matrix")) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("可学技能矩阵", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                EditorialBadge(text = "显示 $visibleItemCount / $totalItemCount 项")
            }

            if (hasUniversal) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    if (hasCharSpecific) {
                        listOf(
                            "all" to "全部",
                            "char" to "角色专属",
                            "universal" to universalFilterLabel,
                        ).forEach { (key, label) ->
                            val isSel = filterMode == key
                            Surface(
                                onClick = {
                                    filterMode = key
                                    if (key == "universal") hideUniversal = false
                                    else if (key == "char") hideUniversal = true
                                    else hideUniversal = false
                                },
                                modifier = Modifier
                                    .heightIn(min = 40.dp)
                                    .semantics { selected = isSel }
                                    .testTag("skill-matrix-filter:$key"),
                                shape = RoundedCornerShape(100.dp),
                                color = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLow,
                                contentColor = if (isSel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                border = BorderStroke(
                                    1.dp,
                                    if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                ),
                            ) {
                                Box(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                                    Text(label, style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }
                    Surface(
                        modifier = Modifier
                            .heightIn(min = 44.dp)
                            .clip(RoundedCornerShape(100.dp))
                            .clickable(role = Role.Switch) {
                                val next = !hideUniversal
                                hideUniversal = next
                                if (next && filterMode == "universal") filterMode = "all"
                                if (!next && filterMode == "char") filterMode = "all"
                            }
                            .testTag("skill-matrix-hide-universal"),
                        shape = RoundedCornerShape(100.dp),
                        color = if (hideUniversal) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(
                            1.dp,
                            if (hideUniversal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        ),
                    ) {
                        Row(
                            Modifier.padding(start = 12.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "隐藏$universalFilterLabel",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (hideUniversal) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (hideUniversal) FontWeight.SemiBold else FontWeight.Medium,
                            )
                            Switch(
                                checked = hideUniversal,
                                onCheckedChange = { next ->
                                    hideUniversal = next
                                    if (next && filterMode == "universal") filterMode = "all"
                                    if (!next && filterMode == "char") filterMode = "all"
                                },
                                colors = SwitchDefaults.colors(
                                    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    uncheckedBorderColor = MaterialTheme.colorScheme.outline,
                                ),
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索技能名称、武器类型或等级（如 专注一击 / C+）…") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                trailingIcon = {
                    if (search.isNotEmpty()) TextButton(onClick = { search = "" }) { Text("清空") }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )

            if (filteredTracks.isEmpty()) {
                Text(
                    "没有符合筛选条件的技能条目。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    filteredTracks.forEachIndexed { idx, track ->
                        if (idx > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 10.dp),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                        SkillMatrixTrackRow(
                            track = track,
                            strengthLabel = strengthLabel,
                            onOpenEntry = onOpenEntry,
                            skillIconContent = skillIconContent,
                            trackIconContent = trackIconContent,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SkillMatrixTrackRow(
    track: SkillMatrixTrack,
    strengthLabel: String,
    onOpenEntry: (String) -> Unit,
    skillIconContent: (@Composable (SkillMatrixItem) -> Unit)?,
    trackIconContent: (@Composable (SkillMatrixTrack) -> Unit)?,
) {
    val dark = isSystemInDarkTheme()
    Column(
        modifier = Modifier.fillMaxWidth().testTag("skill-track-row:${track.trackKey}"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val isPersonalTrack = track.trackKey.equals("personal", ignoreCase = true) || track.trackLabel.contains("个人")
            if (!isPersonalTrack && trackIconContent != null) {
                trackIconContent(track)
            } else {
                Surface(
                    modifier = Modifier.size(30.dp),
                    shape = RoundedCornerShape(9.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.primary,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (isPersonalTrack) {
                            Icon(
                                imageVector = Icons.Outlined.WorkspacePremium,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        } else {
                            Icon(
                                painter = painterResource(skillTrackDrawable(track.trackKey.ifBlank { track.trackLabel })),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
            Text(
                track.trackLabel,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (track.initialRank != "—" && track.initialRank.isNotBlank()) {
                val initTone = wikiLearningRankTone(track.initialRank, dark)
                Surface(
                    color = initTone?.container ?: MaterialTheme.colorScheme.surfaceContainerLow,
                    contentColor = initTone?.content ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    shape = RoundedCornerShape(7.dp),
                    border = BorderStroke(1.dp, (initTone?.content ?: MaterialTheme.colorScheme.outline).copy(alpha = 0.25f)),
                ) {
                    Text(
                        "初始 ${track.initialRank}",
                        Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall.tabularNumbers(),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            when (track.proficiency) {
                SkillProficiency.STRENGTH -> {
                    val pal = statTierPalette(StatRatingTier.HIGH)
                    EditorialBadge(text = "★ $strengthLabel", containerColor = pal.badgeContainer, contentColor = pal.content)
                }
                SkillProficiency.WEAKNESS -> {
                    val pal = statTierPalette(StatRatingTier.LOW)
                    EditorialBadge(text = "▼ 弱项", containerColor = pal.badgeContainer, contentColor = pal.content)
                }
                SkillProficiency.BUDDING -> {
                    EditorialBadge(
                        text = "✦ 才能绽放",
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                SkillProficiency.NEUTRAL -> Unit
            }
        }

        if (track.items.isEmpty()) {
            Text(
                "暂无专属可学技能记录",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                track.items.forEach { item ->
                    SkillMatrixPill(
                        item = item,
                        trackKey = track.trackKey.ifBlank { track.trackLabel },
                        onOpenEntry = onOpenEntry,
                        skillIconContent = skillIconContent,
                    )
                }
            }
        }
    }
}

@Composable
private fun SkillMatrixPill(
    item: SkillMatrixItem,
    trackKey: String,
    onOpenEntry: (String) -> Unit,
    skillIconContent: (@Composable (SkillMatrixItem) -> Unit)?,
) {
    val dark = isSystemInDarkTheme()
    val rankTone = wikiLearningRankTone(item.requirement, dark)
    val badgeBg = when {
        rankTone != null -> rankTone.container
        item.requirement == "绽放" -> MaterialTheme.colorScheme.secondaryContainer
        item.requirement == "固有" || item.requirement == "天生" -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val badgeFg = when {
        rankTone != null -> rankTone.content
        item.requirement == "绽放" -> MaterialTheme.colorScheme.onSecondaryContainer
        item.requirement == "固有" || item.requirement == "天生" -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val clickable = item.targetEntryId != null
    val cardBg = if (item.isUniversal) {
        MaterialTheme.colorScheme.surfaceContainerLow
    } else {
        MaterialTheme.colorScheme.surface
    }
    val borderColor = if (clickable) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }

    Surface(
        onClick = { item.targetEntryId?.let(onOpenEntry) },
        enabled = clickable,
        shape = RoundedCornerShape(11.dp),
        color = cardBg,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier
            .heightIn(min = 36.dp)
            .let { base -> item.itemTag?.let { base.testTag(it) } ?: base },
    ) {
        Row(
            Modifier.padding(start = 5.dp, end = 9.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (item.requirement.isNotBlank()) {
                Surface(
                    modifier = item.rankTag?.let { Modifier.testTag(it) } ?: Modifier,
                    color = badgeBg,
                    contentColor = badgeFg,
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(
                        item.requirement,
                        Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall.tabularNumbers(),
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            if (item.imageAsset != null && skillIconContent != null) {
                skillIconContent(item)
            } else {
                val isPersonal = trackKey.equals("personal", ignoreCase = true) || item.kind.contains("个人")
                val isBudding = item.kind.contains("绽放") || item.requirement == "绽放"
                when {
                    isPersonal -> Icon(
                        imageVector = Icons.Outlined.WorkspacePremium,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    isBudding -> Icon(
                        imageVector = Icons.Outlined.AutoFixHigh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(16.dp),
                    )
                    else -> Icon(
                        painter = painterResource(skillTrackDrawable(trackKey)),
                        contentDescription = null,
                        tint = if (item.isUniversal) {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
            Text(
                item.name,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (item.isUniversal) FontWeight.Normal else FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            item.range?.takeIf { it.isNotBlank() }?.let { rng ->
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(
                        "射程 $rng",
                        Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                        style = MaterialTheme.typography.labelSmall.tabularNumbers(),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            }
            if (clickable) {
                Text(
                    "›",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
internal fun WikiSectionTabBar(
    tabs: List<Pair<String, String>>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { (key, label) ->
            val isSelected = key == selectedKey
            Surface(
                onClick = { onSelect(key) },
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .semantics { selected = isSelected }
                    .testTag("wiki-hub-tab:$key"),
                shape = RoundedCornerShape(100.dp),
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                border = BorderStroke(
                    1.dp,
                    if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                ),
            ) {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    )
                }
            }
        }
    }
}
