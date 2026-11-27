package com.fireemblem.companion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as itemsInRow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fireemblem.companion.campaign.BeginnerGuideProjector
import com.fireemblem.companion.campaign.BeginnerGuideQuestion
import com.fireemblem.companion.campaign.BeginnerMapAccessRule
import com.fireemblem.companion.campaign.BeginnerRouteSummary
import com.fireemblem.companion.content.WikiPageRequest
import com.fireemblem.companion.content.WikiRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface BeginnerGuideLoadState {
    data object Loading : BeginnerGuideLoadState
    data class Ready(
        val questions: List<BeginnerGuideQuestion>,
        val routes: List<BeginnerRouteSummary>,
        val mapAccess: List<BeginnerMapAccessRule>,
    ) : BeginnerGuideLoadState
    data class Failed(val message: String) : BeginnerGuideLoadState
}

@Composable
internal fun BeginnerGuideScreen(repository: WikiRepository?, onBack: () -> Unit, onOpenWorldMap: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var expandedQuestionId by rememberSaveable { mutableStateOf<String?>(null) }
    var expandedRouteId by rememberSaveable { mutableStateOf<String?>(null) }
    var retryToken by rememberSaveable { mutableIntStateOf(0) }
    val loadState by produceState<BeginnerGuideLoadState>(
        initialValue = BeginnerGuideLoadState.Loading,
        repository,
        retryToken,
    ) {
        value = BeginnerGuideLoadState.Loading
        if (repository == null) {
            value = BeginnerGuideLoadState.Failed("离线新手指南暂不可用，请返回后重试。")
        } else {
            value = try {
                withContext(Dispatchers.IO) { loadBeginnerGuide(repository) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                BeginnerGuideLoadState.Failed("新手指南读取失败；本机记录未受影响，请重试。")
            }
        }
    }
    val ready = loadState as? BeginnerGuideLoadState.Ready
    val normalizedQuery = query.trim()
    val visibleQuestions = ready?.questions.orEmpty().filter { question ->
        normalizedQuery.isBlank() || question.question.contains(normalizedQuery, ignoreCase = true) ||
            question.answer.orEmpty().contains(normalizedQuery, ignoreCase = true)
    }
    val visibleRoutes = ready?.routes.orEmpty().filter { route ->
        normalizedQuery.isBlank() || listOfNotNull(
            route.title, route.recommendation, route.features, route.considerations, route.worldCoverage,
        ).any { it.contains(normalizedQuery, ignoreCase = true) }
    }
    val visibleMapAccess = ready?.mapAccess.orEmpty().filter { rule ->
        normalizedQuery.isBlank() || rule.requirement.contains(normalizedQuery, ignoreCase = true) ||
            rule.locations.contains(normalizedQuery, ignoreCase = true)
    }

    BackHandler {
        when {
            expandedQuestionId != null -> expandedQuestionId = null
            expandedRouteId != null -> expandedRouteId = null
            else -> onBack()
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val horizontalPadding = if (maxWidth < 600.dp) 16.dp else 24.dp
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyVerticalGrid(
                state = rememberReadingGridState("beginner:$query", ready != null),
                columns = GridCells.Adaptive(minSize = 320.dp),
                modifier = Modifier.fillMaxSize().widthIn(max = 1040.dp).testTag("beginner-guide-grid"),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = horizontalPadding,
                    end = horizontalPadding,
                    top = 18.dp,
                    bottom = 24.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "beginner-guide-header", span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("返回攻略")
                        }
                        Text(
                            "新手指南",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.semantics { heading() },
                        )
                        Text(
                            "从路线结构、队伍培养到时间管理，按问题查找离线解答。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth().testTag("beginner-guide-search"),
                            label = { Text("搜索问题或答案") },
                            singleLine = true,
                        )
                        when (val state = loadState) {
                            BeginnerGuideLoadState.Loading -> Row(
                                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(Modifier.size(24.dp))
                                Spacer(Modifier.width(12.dp))
                                Text("正在读取离线指南…")
                            }
                            is BeginnerGuideLoadState.Failed -> Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(state.message, color = MaterialTheme.colorScheme.error)
                                Button(onClick = { retryToken++ }, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text("重试")
                                }
                            }
                            is BeginnerGuideLoadState.Ready -> Text(
                                if (visibleQuestions.isEmpty() && visibleRoutes.isEmpty() && visibleMapAccess.isEmpty()) {
                                    "没有找到相关内容。"
                                } else {
                                    "路线 ${visibleRoutes.size} · 区域 ${visibleMapAccess.size} · 问答 ${visibleQuestions.size}"
                                },
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("beginner-guide-count"),
                            )
                        }
                    }
                }
                if (ready != null) {
                    item(key = "beginner-routes-heading", span = { GridItemSpan(maxLineSpan) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "先看看各条路线的玩法",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.semantics { heading() },
                            )
                            Text(
                                "推荐等级为原资料作者的主观评价，仅供参考。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    item(key = "beginner-routes-list", span = { GridItemSpan(maxLineSpan) }) {
                        if (visibleRoutes.isEmpty()) {
                            Text("没有匹配的路线介绍。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            LazyRow(
                                modifier = Modifier.fillMaxWidth().testTag("beginner-routes-row"),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                itemsInRow(visibleRoutes, key = BeginnerRouteSummary::entryId) { route ->
                                    BeginnerRouteCard(
                                        route = route,
                                        expanded = expandedRouteId == route.entryId,
                                        onClick = {
                                            expandedRouteId = if (expandedRouteId == route.entryId) null else route.entryId
                                        },
                                    )
                                }
                            }
                        }
                    }
                    item(key = "beginner-map-access-heading", span = { GridItemSpan(maxLineSpan) }) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    "世界地图开放条件",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.semantics { heading() },
                                )
                                Text(
                                    "最低名声要求之外，仍需满足路线与流程条件。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            OutlinedButton(
                                onClick = onOpenWorldMap,
                                modifier = Modifier.heightIn(min = 48.dp).testTag("beginner-open-world-map"),
                            ) {
                                Text("打开地图")
                            }
                        }
                    }
                    item(key = "beginner-map-access-list", span = { GridItemSpan(maxLineSpan) }) {
                        if (visibleMapAccess.isEmpty()) {
                            Text("没有匹配的开放条件。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                visibleMapAccess.forEach { rule -> BeginnerMapAccessCard(rule) }
                            }
                        }
                    }
                    item(key = "beginner-faq-heading", span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            "常见问题",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.semantics { heading() },
                        )
                    }
                    items(visibleQuestions, key = BeginnerGuideQuestion::entryId) { question ->
                        BeginnerQuestionCard(
                            question = question,
                            expanded = expandedQuestionId == question.entryId,
                            onClick = {
                                expandedQuestionId = if (expandedQuestionId == question.entryId) null else question.entryId
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BeginnerQuestionCard(
    question: BeginnerGuideQuestion,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)
            .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
            .testTag("beginner-question-${question.sourceRowNumber}"),
        colors = CardDefaults.cardColors(
            containerColor = if (expanded) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                Text(
                    "问",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    question.question,
                    modifier = Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (expanded) {
                HorizontalDivider()
                Text(
                    question.answer ?: "这条问题目前没有可用解答。",
                    modifier = Modifier.testTag("beginner-answer-${question.sourceRowNumber}"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "来源：新手初次游玩资料",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BeginnerRouteCard(route: BeginnerRouteSummary, expanded: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.width(252.dp)
            .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
            .testTag("beginner-route-${route.sourceRowNumber}"),
        colors = CardDefaults.cardColors(
            containerColor = if (expanded) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                WikiImage(route.imageAsset, "${route.title}人物图", Modifier.size(68.dp), crop = true)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(route.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    route.recommendation?.let {
                        Text("主观推荐 $it", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    route.recruitableCount?.let {
                        Text("$it 人可挖角", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            route.features?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = if (expanded) Int.MAX_VALUE else 2)
            }
            if (expanded) {
                HorizontalDivider()
                route.considerations?.let { BeginnerRouteFact("游玩注意", it) }
                route.worldCoverage?.let { BeginnerRouteFact("可活动范围", it) }
            }
        }
    }
}

@Composable
private fun BeginnerRouteFact(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun BeginnerMapAccessCard(rule: BeginnerMapAccessRule) {
    Card(
        modifier = Modifier.widthIn(min = 148.dp, max = 320.dp)
            .testTag("beginner-map-access-${rule.entryId.substringAfter('#')}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                rule.requirement,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(rule.locations, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun loadBeginnerGuide(repository: WikiRepository): BeginnerGuideLoadState.Ready {
    val entries = buildList {
        listOf(
            BeginnerGuideProjector.ROUTES_CATEGORY_ID,
            BeginnerGuideProjector.MAP_ACCESS_CATEGORY_ID,
            BeginnerGuideProjector.CATEGORY_ID,
        ).forEach { categoryId ->
            var offset = 0
            var hasNext: Boolean
            do {
                val page = repository.page(WikiPageRequest(categoryId = categoryId, offset = offset, limit = 100))
                page.entries.forEach { summary -> repository.entry(summary.id)?.let(::add) }
                offset += page.entries.size
                hasNext = page.hasNext
            } while (hasNext)
        }
    }
    return BeginnerGuideLoadState.Ready(
        questions = BeginnerGuideProjector.project(entries),
        routes = BeginnerGuideProjector.projectRoutes(entries),
        mapAccess = BeginnerGuideProjector.projectMapAccess(entries),
    )
}
