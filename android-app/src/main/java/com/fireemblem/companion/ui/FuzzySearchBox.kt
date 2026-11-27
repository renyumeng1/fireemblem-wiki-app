package com.fireemblem.companion.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.NorthWest
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fireemblem.companion.content.CompanionGame
import com.fireemblem.companion.content.FuzzyMatcher
import com.fireemblem.companion.content.FuzzySuggestion
import com.fireemblem.companion.content.WikiFieldOptions
import com.fireemblem.companion.ui.theme.tabularNumbers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Reusable app-wide fuzzy search input with live selectable dropdown panel.
 * Renders suggestions inline below the field (never stealing IME focus) with
 * highlighted fuzzy-matched characters, category/game badges, and optional portrait thumbnails.
 */
@Composable
internal fun FuzzySearchBox(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    suggestionsProvider: suspend (String) -> List<FuzzySuggestion>,
    onSelectSuggestion: (FuzzySuggestion) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    fieldTestTag: String? = null,
    dropdownTestTag: String = "fuzzy-search-dropdown",
    onSubmitSearch: ((String) -> Unit)? = null,
    suggestionKey: Any? = Unit,
    shapeRadiusDp: Int = 16,
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    var dismissedForQuery by remember { mutableStateOf<String?>(null) }
    var suggestions by remember { mutableStateOf<List<FuzzySuggestion>>(emptyList()) }

    val trimmed = query.trim()
    LaunchedEffect(trimmed, suggestionKey) {
        if (trimmed.isEmpty()) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        delay(90)
        suggestions = withContext(Dispatchers.IO) {
            runCatching { suggestionsProvider(trimmed) }.getOrDefault(emptyList())
        }
    }

    val showDropdown = trimmed.isNotEmpty() &&
        dismissedForQuery != trimmed &&
        (focused || suggestions.isNotEmpty()) &&
        suggestions.isNotEmpty()

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { next ->
                dismissedForQuery = null
                onQueryChange(next)
            },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { st -> focused = st.isFocused }
                .let { base -> if (fieldTestTag != null) base.testTag(fieldTestTag) else base },
            label = label?.let { l -> { Text(l) } },
            placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (query.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                dismissedForQuery = null
                                suggestions = emptyList()
                                onQueryChange("")
                            },
                        ) {
                            Icon(Icons.Outlined.Clear, contentDescription = "清空搜索")
                        }
                    }
                    if (onSubmitSearch != null) {
                        IconButton(
                            onClick = {
                                dismissedForQuery = trimmed
                                focusManager.clearFocus()
                                keyboard?.hide()
                                onSubmitSearch(trimmed)
                            },
                        ) {
                            Icon(Icons.Outlined.Search, contentDescription = "执行搜索")
                        }
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(shapeRadiusDp.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    dismissedForQuery = trimmed
                    focusManager.clearFocus()
                    keyboard?.hide()
                    if (onSubmitSearch != null) {
                        onSubmitSearch(trimmed)
                    } else if (suggestions.isNotEmpty()) {
                        onSelectSuggestion(suggestions.first())
                    }
                },
            ),
        )

        if (showDropdown) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                shadowElevation = 4.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)),
                modifier = Modifier.fillMaxWidth().testTag(dropdownTestTag),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "模糊匹配候选（${suggestions.size}）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Surface(
                            onClick = { dismissedForQuery = trimmed },
                            shape = RoundedCornerShape(100.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            Text(
                                "收起",
                                Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                    suggestions.forEachIndexed { idx, item ->
                        if (idx > 0) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                        }
                        Surface(
                            onClick = {
                                dismissedForQuery = item.title
                                focusManager.clearFocus()
                                keyboard?.hide()
                                onSelectSuggestion(item)
                            },
                            color = Color.Transparent,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("fuzzy-suggestion:${item.id}"),
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                item.imageAsset?.let { path ->
                                    FuzzySuggestionThumb(
                                        assetPath = path,
                                        contentDescription = item.title,
                                        modifier = Modifier.size(34.dp),
                                    )
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(
                                            text = buildHighlightedTitle(trimmed, item.title, MaterialTheme.colorScheme.primary),
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false),
                                        )
                                        if (item.badge.isNotBlank()) {
                                            EditorialBadge(text = item.badge)
                                        }
                                    }
                                    if (item.subtitle.isNotBlank()) {
                                        Text(
                                            text = item.subtitle,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                Icon(
                                    Icons.Outlined.NorthWest,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (onSubmitSearch != null) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        Surface(
                            onClick = {
                                dismissedForQuery = trimmed
                                focusManager.clearFocus()
                                keyboard?.hide()
                                onSubmitSearch(trimmed)
                            },
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth().testTag("fuzzy-search-submit-all"),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                Text(
                                    "查看「$trimmed」全部搜索结果",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun buildHighlightedTitle(query: String, title: String, accentColor: Color) = buildAnnotatedString {
    val hits = FuzzyMatcher.highlightIndices(query, title)
    for (i in title.indices) {
        if (i in hits) {
            pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = accentColor))
            append(title[i])
            pop()
        } else {
            append(title[i])
        }
    }
}

@Composable
internal fun FuzzySuggestionThumb(
    assetPath: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val resolvedPath = remember(assetPath) {
        if (assetPath.startsWith("games/") || assetPath.startsWith("wiki/")) assetPath else "wiki/$assetPath"
    }
    val bitmap by produceState<ImageBitmap?>(null, resolvedPath, context) {
        value = withContext(Dispatchers.IO) {
            try {
                context.assets.open(resolvedPath).use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }
    Surface(
        modifier = modifier.clip(RoundedCornerShape(10.dp)),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        bitmap?.let { img ->
            Image(
                bitmap = img,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

private data class FwRouteCardSpec(
    val routeKey: String?,
    val label: String,
    val portraitAsset: String?,
    val lightContainer: Color,
    val lightContent: Color,
    val lightAccent: Color,
    val darkContainer: Color,
    val darkContent: Color,
    val darkAccent: Color,
)

private val FW_ROUTE_SPECS = listOf(
    FwRouteCardSpec(
        routeKey = null,
        label = "全部角色",
        portraitAsset = null,
        lightContainer = Color(0xFFF6EFE6),
        lightContent = Color(0xFF5A3918),
        lightAccent = Color(0xFF9E522B),
        darkContainer = Color(0xFF3E3024),
        darkContent = Color(0xFFF4DEC4),
        darkAccent = Color(0xFFE0986D),
    ),
    FwRouteCardSpec(
        routeKey = "kai",
        label = "凯伊篇",
        portraitAsset = "buddy/assets/portraits/before-part3/grid/cai.webp",
        lightContainer = Color(0xFFF9E5DF),
        lightContent = Color(0xFF7D2719),
        lightAccent = Color(0xFFB13D2B),
        darkContainer = Color(0xFF4B2822),
        darkContent = Color(0xFFFFDBD3),
        darkAccent = Color(0xFFE68674),
    ),
    FwRouteCardSpec(
        routeKey = "dit",
        label = "迪托利希篇",
        portraitAsset = "buddy/assets/portraits/before-part3/grid/dietrich.webp",
        lightContainer = Color(0xFFE0ECF8),
        lightContent = Color(0xFF1F4770),
        lightAccent = Color(0xFF2E5E91),
        darkContainer = Color(0xFF20354A),
        darkContent = Color(0xFFD6E9FF),
        darkAccent = Color(0xFF7CB0E4),
    ),
    FwRouteCardSpec(
        routeKey = "seo",
        label = "赛奥朵拉篇",
        portraitAsset = "buddy/assets/portraits/before-part3/grid/theodora.webp",
        lightContainer = Color(0xFFF9EED2),
        lightContent = Color(0xFF6B4C0B),
        lightAccent = Color(0xFF9C7214),
        darkContainer = Color(0xFF45371B),
        darkContent = Color(0xFFFFE5A6),
        darkAccent = Color(0xFFE0B44F),
    ),
    FwRouteCardSpec(
        routeKey = "leda",
        label = "蕾达篇",
        portraitAsset = "buddy/assets/portraits/before-part3/grid/leda.webp",
        lightContainer = Color(0xFFE1F2E9),
        lightContent = Color(0xFF1E573B),
        lightAccent = Color(0xFF2E7A55),
        darkContainer = Color(0xFF223C30),
        darkContent = Color(0xFFD3F1E2),
        darkAccent = Color(0xFF7AC29E),
    ),
    FwRouteCardSpec(
        routeKey = "other",
        label = "序幕/二·三部/NPC",
        portraitAsset = "buddy/assets/portraits/protagonist/grid/eshmel-male.webp",
        lightContainer = Color(0xFFECE6F6),
        lightContent = Color(0xFF483668),
        lightAccent = Color(0xFF674E91),
        darkContainer = Color(0xFF352C44),
        darkContent = Color(0xFFE6DCFA),
        darkAccent = Color(0xFFB5A0DF),
    ),
)

private val FW_FACTION_PALETTES_LIGHT = listOf(
    Triple(Color(0xFFE1F2E9), Color(0xFF1E573B), Color(0xFF2E7A55)),
    Triple(Color(0xFFE0ECF8), Color(0xFF1F4770), Color(0xFF2E5E91)),
    Triple(Color(0xFFF9EED2), Color(0xFF6B4C0B), Color(0xFF9C7214)),
    Triple(Color(0xFFF9E2E0), Color(0xFF7D241E), Color(0xFFA8332B)),
    Triple(Color(0xFFECE6F6), Color(0xFF483668), Color(0xFF674E91)),
    Triple(Color(0xFFF9EBDA), Color(0xFF704512), Color(0xFFA86A1E)),
)

private val FW_FACTION_PALETTES_DARK = listOf(
    Triple(Color(0xFF223C30), Color(0xFFD3F1E2), Color(0xFF7AC29E)),
    Triple(Color(0xFF20354A), Color(0xFFD6E9FF), Color(0xFF7CB0E4)),
    Triple(Color(0xFF45371B), Color(0xFFFFE5A6), Color(0xFFE0B44F)),
    Triple(Color(0xFF4A2524), Color(0xFFFFDAD6), Color(0xFFE8857D)),
    Triple(Color(0xFF352C44), Color(0xFFE6DCFA), Color(0xFFB5A0DF)),
    Triple(Color(0xFF4A351C), Color(0xFFFFE2B5), Color(0xFFE3AA59)),
)

/**
 * Portrait-and-palette visual filter bar for 《万缕千丝》 characters,
 * matching the visual richness of 《风花雪月》 & 《Engage》's `GamePackFilterBar`.
 */
@Composable
internal fun FortunesWeaveCharacterFilterBar(
    routeKey: String?,
    fameDescending: Boolean,
    filterField: String?,
    filterValue: String?,
    quickOption: WikiFieldOptions?,
    affiliationPortraits: Map<String, String>,
    onSelectRoute: (String?) -> Unit,
    onSelectFameDescending: (Boolean) -> Unit,
    onSelectQuickFilter: (field: String?, value: String?) -> Unit,
) {
    val dark = isSystemInDarkTheme()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // 1. Visual Route Cards with Lord Portraits
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "按主角路线筛选",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .testTag("fw-route-filter-bar"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FW_ROUTE_SPECS.forEach { spec ->
                    val isSel = routeKey == spec.routeKey
                    val accent = if (dark) spec.darkAccent else spec.lightAccent
                    val container = if (isSel) accent else if (dark) spec.darkContainer else spec.lightContainer
                    val content = if (isSel) MaterialTheme.colorScheme.onPrimary else if (dark) spec.darkContent else spec.lightContent
                    val border = if (isSel) accent else accent.copy(alpha = 0.35f)
                    Surface(
                        onClick = { onSelectRoute(spec.routeKey) },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .semantics { selected = isSel }
                            .testTag("wiki-route-chip-${spec.routeKey ?: "all"}"),
                        shape = RoundedCornerShape(14.dp),
                        color = container,
                        contentColor = content,
                        border = BorderStroke(if (isSel) 1.5.dp else 1.dp, border),
                    ) {
                        Row(
                            Modifier.padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (spec.portraitAsset != null) {
                                Surface(
                                    modifier = Modifier.size(30.dp),
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                    border = BorderStroke(1.dp, if (isSel) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.6f) else accent.copy(alpha = 0.45f)),
                                ) {
                                    FuzzySuggestionThumb(
                                        assetPath = spec.portraitAsset,
                                        contentDescription = spec.label,
                                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                                    )
                                }
                            } else {
                                Surface(
                                    modifier = Modifier.size(30.dp),
                                    shape = CircleShape,
                                    color = if (isSel) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.18f) else accent.copy(alpha = 0.15f),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        GameEmblemIcon(
                                            game = CompanionGame.FORTUNES_WEAVE,
                                            modifier = Modifier.size(18.dp),
                                            tint = if (isSel) MaterialTheme.colorScheme.onPrimary else accent,
                                        )
                                    }
                                }
                            }
                            Text(
                                spec.label,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }

        // 2. Fame Order Toggle when browsing one of the 4 routes
        if (routeKey in setOf("kai", "dit", "seo", "leda")) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf(
                    false to "声望：低→高（加入顺序）",
                    true to "声望：高→低",
                ).forEach { (desc, label) ->
                    val isSel = fameDescending == desc
                    Surface(
                        onClick = { onSelectFameDescending(desc) },
                        modifier = Modifier
                            .heightIn(min = 38.dp)
                            .semantics { selected = isSel }
                            .testTag(if (desc) "wiki-route-fame-desc" else "wiki-route-fame-asc"),
                        shape = RoundedCornerShape(100.dp),
                        color = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLow,
                        contentColor = if (isSel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        border = BorderStroke(1.dp, if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Box(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
                            Text(label, style = MaterialTheme.typography.labelMedium.tabularNumbers())
                        }
                    }
                }
            }
        }

        // 3. Visual Faction / Affiliation Filter Cards with Lead Character Portraits
        quickOption?.let { opt ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "按${opt.label.removePrefix("所属")}筛选",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val preferredFactions = listOf(
                    "利贝拉之风", "拉弥努家族", "美盖拉的灯火", "蔷薇风暴",
                    "救世主军", "炎之鬣", "姬金鱼草的朝露", "月之谎言",
                    "白鸦新娘团", "无所属",
                )
                val orderedValues = if (opt.key == "affiliation") {
                    opt.values.sortedWith(
                        compareBy<String> { val idx = preferredFactions.indexOf(it.replace("\n", "")); if (idx >= 0) idx else 100 }
                            .thenBy { it },
                    )
                } else opt.values

                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .testTag("fw-faction-filter-bar"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val allFactionSel = filterField != opt.key || filterValue == null
                    Surface(
                        onClick = { onSelectQuickFilter(null, null) },
                        modifier = Modifier
                            .heightIn(min = 44.dp)
                            .semantics { selected = allFactionSel }
                            .testTag("fw-faction:all"),
                        shape = RoundedCornerShape(14.dp),
                        color = if (allFactionSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                        contentColor = if (allFactionSel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        border = BorderStroke(1.dp, if (allFactionSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Box(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), contentAlignment = Alignment.Center) {
                            Text(
                                "全部${opt.label.removePrefix("所属")}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (allFactionSel) FontWeight.Bold else FontWeight.Medium,
                            )
                        }
                    }

                    val palettes = if (dark) FW_FACTION_PALETTES_DARK else FW_FACTION_PALETTES_LIGHT
                    orderedValues.take(20).forEachIndexed { idx, value ->
                        val cleanLabel = value.replace("\n", "")
                        val isSel = filterField == opt.key && filterValue == value
                        val (bg, fg, accent) = palettes[idx % palettes.size]
                        val portrait = affiliationPortraits[value] ?: affiliationPortraits[cleanLabel]
                        Surface(
                            onClick = {
                                if (isSel) onSelectQuickFilter(null, null)
                                else onSelectQuickFilter(opt.key, value)
                            },
                            modifier = Modifier
                                .heightIn(min = 44.dp)
                                .semantics { selected = isSel }
                                .testTag("fw-faction:$cleanLabel"),
                            shape = RoundedCornerShape(14.dp),
                            color = if (isSel) accent else bg,
                            contentColor = if (isSel) MaterialTheme.colorScheme.onPrimary else fg,
                            border = BorderStroke(if (isSel) 1.5.dp else 1.dp, if (isSel) accent else accent.copy(alpha = 0.35f)),
                        ) {
                            Row(
                                Modifier.padding(
                                    start = if (portrait != null) 7.dp else 12.dp,
                                    end = 12.dp,
                                    top = 6.dp,
                                    bottom = 6.dp,
                                ),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                portrait?.let { imgPath ->
                                    Surface(
                                        modifier = Modifier.size(26.dp),
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                        border = BorderStroke(1.dp, if (isSel) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.6f) else accent.copy(alpha = 0.4f)),
                                    ) {
                                        FuzzySuggestionThumb(
                                            assetPath = imgPath,
                                            contentDescription = cleanLabel,
                                            modifier = Modifier.fillMaxSize().clip(CircleShape),
                                        )
                                    }
                                }
                                Text(
                                    cleanLabel,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
