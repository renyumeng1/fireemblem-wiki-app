package com.fireemblem.companion.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fireemblem.companion.R
import com.fireemblem.companion.content.CompanionGame
import com.fireemblem.companion.ui.theme.tabularNumbers

@Composable
internal fun GameEmblemIcon(
    game: CompanionGame,
    modifier: Modifier = Modifier.size(20.dp),
    tint: Color = LocalContentColor.current,
) {
    val drawableId = when (game) {
        CompanionGame.FORTUNES_WEAVE -> R.drawable.ic_game_fortunes_weave
        CompanionGame.ENGAGE -> R.drawable.ic_game_engage
        CompanionGame.THREE_HOUSES -> R.drawable.ic_game_three_houses
    }
    Icon(
        painter = painterResource(drawableId),
        contentDescription = null,
        modifier = modifier,
        tint = tint,
    )
}

@Composable
internal fun GameSwitcherBar(
    activeGame: CompanionGame,
    onSelectGame: (CompanionGame) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CompanionGame.entries.forEach { game ->
                val isSelected = game == activeGame
                Surface(
                    onClick = { onSelectGame(game) },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics { selected = isSelected }
                        .testTag("game-switcher:${game.id}"),
                    shape = RoundedCornerShape(13.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent,
                    contentColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)) else null,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        GameEmblemIcon(
                            game = game,
                            modifier = Modifier.size(20.dp),
                            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            game.shortTitle,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun EditorialCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    val colors = CardDefaults.cardColors(containerColor = containerColor)
    val elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    val border = BorderStroke(1.dp, borderColor)
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            colors = colors,
            elevation = elevation,
            border = border,
            content = content,
        )
    } else {
        Card(
            modifier = modifier,
            shape = shape,
            colors = colors,
            elevation = elevation,
            border = border,
            content = content,
        )
    }
}

@Composable
internal fun EditorialSectionHeader(
    title: String,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.semantics { heading() },
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
internal fun EditorialBadge(
    text: String,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(100.dp),
        border = BorderStroke(1.dp, contentColor.copy(alpha = 0.18f)),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium.tabularNumbers(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun CollapsibleEditorialCard(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
    subtitle: String? = null,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    headerTag: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    EditorialCard(
        modifier = modifier.fillMaxWidth(),
        containerColor = containerColor,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Surface(
                onClick = onToggle,
                color = Color.Transparent,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .then(if (headerTag != null) Modifier.testTag(headerTag) else Modifier),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.semantics { heading() },
                        )
                        if (!subtitle.isNullOrBlank()) {
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (!badge.isNullOrBlank()) {
                        EditorialBadge(text = badge)
                    }
                    Text(
                        if (expanded) "收起 ▲" else "展开 ▼",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            if (expanded) {
                androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = content,
                )
            }
        }
    }
}

internal fun splitRouteTokens(raw: String): List<String> =
    raw.split("/", "／", "、", "\n").map { it.trim() }.filter { it.isNotEmpty() }.distinct()

@Composable
internal fun RouteAvailabilityBadgesRow(rawRoutes: String) {
    val tokens = splitRouteTokens(rawRoutes)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        tokens.forEach { token ->
            val (bg, fg) = when {
                "红花" in token || "凯伊" in token || "凯篇" in token ->
                    Color(0xFFF9E2E0) to Color(0xFF7D241E)
                "苍月" in token || "青狮子" in token || "迪托" in token || "迪篇" in token ->
                    Color(0xFFE0ECF8) to Color(0xFF1F4770)
                "翠风" in token || "金鹿" in token || "赛奥" in token || "赛篇" in token ->
                    Color(0xFFF9EED2) to Color(0xFF6B4C0B)
                "银雪" in token || "教会" in token ->
                    Color(0xFFECEFF4) to Color(0xFF334155)
                "蕾达" in token || "蕾篇" in token || "灰狼" in token || "DLC" in token ->
                    Color(0xFFECE6F6) to Color(0xFF483668)
                else -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
            }
            EditorialBadge(text = token, containerColor = bg, contentColor = fg)
        }
    }
}
