package com.fireemblem.companion.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fireemblem.companion.content.WikiDatabaseRepository
import com.fireemblem.companion.content.WikiEntry
import com.fireemblem.companion.content.WikiPageRequest
import com.fireemblem.companion.engine.BuddyRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BuddyVisuals(
    val images: Map<String, Map<String, String>> = emptyMap(),
    val professionTiers: Map<String, Int> = emptyMap(),
) {
    fun image(category: String, name: String): String? = images[category]?.get(name)
    fun tierOf(profession: String): Int = professionTiers[profession] ?: 0
    fun tierLabelOf(profession: String): String = BuddyRules.tierLabel(tierOf(profession))
}

internal fun characterLabel(id: String, name: String): String = when (id) {
    "c001" -> "$name（男）"
    "c078" -> "$name（女）"
    else -> name
}

@Composable
internal fun rememberBuddyVisuals(): BuddyVisuals {
    val context = LocalContext.current
    val visuals by produceState(BuddyVisuals(), context) {
        value = withContext(Dispatchers.IO) {
            try {
                val tiers = runCatching {
                    BuddyRules.bundled().professions.associate { it.name to it.tier }
                }.getOrDefault(emptyMap())
                WikiDatabaseRepository.open(context).use { repository ->
                    val pinned = context.assets.open("wiki/buddy/index.json").bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
                    require(pinned.getValue("revision").jsonPrimitive.content == BuddyRules.REVISION)
                    val images = pinned.getValue("images").jsonObject.mapValues { (_, group) ->
                        group.jsonObject.mapValues { (_, path) -> path.jsonPrimitive.content }
                    }
                    BuddyVisuals(
                        images = images + listOf("mounts").associateWith { category ->
                            val entries = mutableListOf<WikiEntry>()
                            var offset = 0
                            do {
                                val page = repository.page(WikiPageRequest(categoryId = category, offset = offset, limit = 100))
                                entries += page.entries
                                offset += page.entries.size
                            } while (page.hasNext && page.entries.isNotEmpty())
                            entries.groupBy { it.title }.mapNotNull { (name, matches) ->
                                matches.singleOrNull()?.imageAsset?.let { name to it }
                            }.toMap()
                        },
                        professionTiers = tiers,
                    )
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                val tiers = runCatching {
                    BuddyRules.bundled().professions.associate { it.name to it.tier }
                }.getOrDefault(emptyMap())
                BuddyVisuals(professionTiers = tiers)
            }
        }
    }
    return visuals
}

@Composable
internal fun BuddyPortrait(characterId: String, name: String, visuals: BuddyVisuals, modifier: Modifier = Modifier.size(76.dp), part3: Boolean = false) {
    Surface(modifier, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        WikiImage(visuals.image(if (part3) "charactersPart3" else "characters", characterId), "${name}头像", Modifier.fillMaxSize().padding(4.dp), crop = false)
    }
}

/**
 * Reusable horizontal tier filter bar for Fortunes Weave profession pickers:
 * 全部 / 基础 / 下级 / 中级 / 上级 / 最上级 / 神将.
 */
@Composable
internal fun BuddyProfessionTierFilterBar(
    availableTiers: List<Int>,
    selectedTier: Int?,
    onSelectTier: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val distinctTiers = remember(availableTiers) { availableTiers.distinct().sorted() }
    if (distinctTiers.size <= 1) return
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SelectablePill(
            label = "全部",
            selected = selectedTier == null,
            onClick = { onSelectTier(null) },
        )
        distinctTiers.forEach { tier ->
            val minLv = BuddyRules.defaultUnlockLevelForTier(tier)
            val label = if (minLv > 1) "${BuddyRules.tierLabel(tier)}" else BuddyRules.tierLabel(tier)
            SelectablePill(
                label = label,
                selected = selectedTier == tier,
                onClick = { onSelectTier(if (selectedTier == tier) null else tier) },
            )
        }
    }
}

@Composable
internal fun IllustratedChoice(
    label: String,
    selectedId: String,
    selectedName: String,
    choices: List<Pair<String, String>>,
    category: String,
    visuals: BuddyVisuals,
    onSelect: (String) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedTier by rememberSaveable { mutableStateOf<Int?>(null) }
    val isProfession = category == "professions"

    OutlinedCard(onClick = { open = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (selectedId.isNotEmpty()) WikiImage(
                visuals.image(category, if (category == "characters") selectedId else selectedName),
                "$selectedName$label",
                Modifier.size(if (category == "characters") 56.dp else 40.dp),
                crop = false,
            )
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(selectedName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (isProfession && selectedName in visuals.professionTiers) {
                        Text(
                            visuals.tierLabelOf(selectedName),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            Text("更换", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("选择$label") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(query, { query = it }, label = { Text("搜索$label") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (isProfession && visuals.professionTiers.isNotEmpty()) {
                    val tiersPresent = remember(choices, visuals.professionTiers) {
                        choices.mapNotNull { visuals.professionTiers[it.second] }
                    }
                    BuddyProfessionTierFilterBar(
                        availableTiers = tiersPresent,
                        selectedTier = selectedTier,
                        onSelectTier = { selectedTier = it },
                    )
                }
                val qTrim = query.trim()
                val filtered = choices.mapNotNull { choice ->
                    val matchesTier = !isProfession || selectedTier == null || visuals.professionTiers[choice.second] == selectedTier
                    if (!matchesTier) return@mapNotNull null
                    val sc = if (qTrim.isEmpty()) 1 else com.fireemblem.companion.content.FuzzyMatcher.score(qTrim, choice.second, choice.first)
                    if (sc > 0) sc to choice else null
                }.let { list ->
                    if (qTrim.isEmpty()) list.map { it.second }
                    else list.sortedByDescending { it.first }.map { it.second }
                }
                if (filtered.isEmpty()) Text("没有找到匹配的$label")
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(104.dp),
                    modifier = Modifier.heightIn(max = 380.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filtered, key = { it.first }) { choice ->
                        val selected = selectedId == choice.first
                        Card(
                            onClick = { onSelect(choice.first); open = false; query = "" },
                            border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                            colors = CardDefaults.cardColors(
                                containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                            ),
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(9.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                if (choice.first.isNotEmpty()) WikiImage(
                                    visuals.image(category, if (category == "characters") choice.first else choice.second),
                                    "${choice.second}$label",
                                    Modifier.size(if (category == "characters") 64.dp else 40.dp),
                                    crop = false,
                                )
                                Text(choice.second, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (isProfession && choice.second in visuals.professionTiers) {
                                    val t = visuals.tierOf(choice.second)
                                    val minLv = BuddyRules.defaultUnlockLevelForTier(t)
                                    Text(
                                        if (minLv > 1) "${BuddyRules.tierLabel(t)}·Lv.$minLv" else BuddyRules.tierLabel(t),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else if (selected) {
                                    Text("已选择", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text("关闭") } },
    )
}
