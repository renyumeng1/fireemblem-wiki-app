package com.fireemblem.companion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.TheaterComedy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.fireemblem.companion.content.TheaterCoverage
import com.fireemblem.companion.content.TheaterGuideData
import com.fireemblem.companion.content.TheaterGuideQuery
import com.fireemblem.companion.content.TheaterPlay
import com.fireemblem.companion.content.TheaterRating
import com.fireemblem.companion.content.WikiEntry
import com.fireemblem.companion.content.WikiEntryPage
import com.fireemblem.companion.content.WikiPageRequest
import com.fireemblem.companion.content.WikiRepository
import com.fireemblem.companion.personal.PersonalRevision
import com.fireemblem.companion.personal.PersonalSnapshot
import com.fireemblem.companion.personal.PersonalStore
import com.fireemblem.companion.personal.TheaterBookmark
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonPrimitive

private data class TheaterReady(val guide: TheaterGuideData, val snapshot: PersonalSnapshot?, val profileName: String)

@Composable
internal fun TheaterGuideScreen(repository: WikiRepository?, onBack: () -> Unit, onOpenWiki: (String) -> Unit, initialPlayId: String? = null) {
    val context = LocalContext.current
    val store = remember(context) { PersonalStore(context) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val profileId = remember(context) { context.getSharedPreferences("personal-view", 0).getString("profile", "global") ?: "global" }
    var ready by remember(repository, profileId) { mutableStateOf<TheaterReady?>(null) }
    var error by remember(repository) { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var mode by rememberSaveable { mutableStateOf("plays") }
    var query by rememberSaveable(mode) { mutableStateOf("") }
    var coverageName by rememberSaveable { mutableStateOf(TheaterCoverage.ALL.name) }
    var onlyFavorites by rememberSaveable { mutableStateOf(false) }
    var selectedPlayId by rememberSaveable { mutableStateOf(initialPlayId) }
    var selectedCharacterId by rememberSaveable { mutableStateOf<String?>(null) }
    var characterPageIndex by rememberSaveable(query) { mutableIntStateOf(0) }
    var sourceOpen by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(repository, profileId, retry) {
        ready = null; error = null
        try {
            ready = withContext(Dispatchers.IO) {
                val guide = requireNotNull(repository?.theaterGuide()) { "当前离线资料尚未包含剧场演出，请更新资料后重试。" }
                val snapshot = try { store.snapshot() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
                val name = if (profileId == "global") "通用记录" else snapshot?.heads()?.get(profileId)?.singleOrNull()
                    ?.takeUnless { it.deleted }?.payload?.get("name")?.jsonPrimitive?.content ?: "当前个人存档"
                TheaterReady(guide, snapshot, name)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "剧场资料读取失败，请重试。" }
    }
    val characterRequest = remember(mode, query, characterPageIndex, selectedCharacterId) {
        WikiPageRequest(categoryId = "characters", query = query, offset = characterPageIndex * WikiPageRequest.PAGE_SIZE)
    }
    var characterPage by remember(repository, characterRequest, mode, retry) { mutableStateOf<WikiEntryPage?>(null) }
    var character by remember(repository, selectedCharacterId, retry) { mutableStateOf<WikiEntry?>(null) }
    var characterLoaded by remember(repository, selectedCharacterId, retry) { mutableStateOf(false) }
    var characterError by remember(repository, characterRequest, retry) { mutableStateOf<String?>(null) }
    LaunchedEffect(repository, characterRequest, selectedCharacterId, mode, retry) {
        if (mode != "characters") return@LaunchedEffect
        characterError = null
        try {
            val requestedId = selectedCharacterId
            if (requestedId == null) characterPage = withContext(Dispatchers.IO) { repository?.page(characterRequest) }
            else {
                character = withContext(Dispatchers.IO) { repository?.entry(requestedId) }
                characterLoaded = true
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { characterError = "角色资料读取失败，请重试。" }
    }
    fun back() {
        when {
            initialPlayId != null && selectedPlayId == initialPlayId -> onBack()
            selectedPlayId != null -> selectedPlayId = null
            selectedCharacterId != null -> selectedCharacterId = null
            else -> onBack()
        }
    }
    BackHandler(onBack = ::back)
    val current = ready
    val guide = current?.guide
    val bookmarks = remember(current, profileId) {
        guide?.plays.orEmpty().associate { play -> play.id to runCatching {
            TheaterBookmark.read(requireNotNull(current?.snapshot) { "收藏无法读取，请到个人存档检查" }, profileId, play.id, play.title)
        } }
    }
    fun toggleFavorite(play: TheaterPlay) {
        val loaded = ready ?: return
        val bookmark = bookmarks[play.id]?.getOrNull() ?: return
        if (saving) return
        val entityId = TheaterBookmark.entityId(profileId, play.id)
        val parents = loaded.snapshot?.heads()?.get(entityId).orEmpty().map(PersonalRevision::revisionId).toSet()
        saving = true; message = null
        scope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) {
                    store.edit(entityId, "bookmark", bookmark.copy(name = play.title, favorite = !bookmark.favorite).payload(), expectedParents = parents)
                }
                ready = loaded.copy(snapshot = snapshot)
                message = "已保存到${loaded.profileName}。"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "未能保存，原记录仍保留。请重新读取后再试。"; retry++ }
            finally { saving = false }
        }
    }
    val play = guide?.plays?.singleOrNull { it.id == selectedPlayId }
    val detail = selectedPlayId != null || selectedCharacterId != null
    val listReady = current != null && (mode == "plays" || if (selectedCharacterId == null) characterPage != null else character != null)
    TheaterGuideGrid(
        state = rememberReadingGridState("theater/$profileId/$mode/$query/$coverageName/$onlyFavorites/$characterPageIndex/${selectedPlayId ?: selectedCharacterId ?: "list"}", listReady),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = ::back, modifier = Modifier.testTag("theater-back")) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, null)
                    Text(if (initialPlayId != null && selectedPlayId == initialPlayId) "返回个人记录" else if (detail) "返回剧场" else "返回攻略")
                }
                ScreenHeading(play?.title ?: character?.title ?: "剧场演出", if (detail) "选项、报酬与角色资料随时查阅" else "上台前看选项，把常演剧目收进口袋。")
            }
        }
        if (current == null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                if (error == null) CircularProgressIndicator() else Column { Text(requireNotNull(error)); TextButton(onClick = { retry++ }) { Text("重试") } }
            }
        } else if (selectedPlayId != null) {
            if (play == null) item(span = { GridItemSpan(maxLineSpan) }) { Text("这部剧目已不在当前资料中，请返回重新选择。") }
            else {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TheaterFavorite(play, bookmarks[play.id], saving, { toggleFavorite(play) })
                        message?.let { Text(it) }
                        Text("演技选项", style = MaterialTheme.typography.titleLarge)
                        Text("同一提示内的不同说法含义相近，请按游戏中的语义选择。未收录的选项以游戏内显示为准。", style = MaterialTheme.typography.bodySmall)
                    }
                }
                items(play.cues, key = { "cue:${it.position}" }) { cue ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("演技提示", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            cue.options.forEach { Text(it, style = MaterialTheme.typography.titleMedium) }
                            if (cue.sourceRow > 4) Text("对应幕次待核实", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            Text("来源 ${cue.cellAddress}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) { Text("评价报酬", style = MaterialTheme.typography.titleLarge) }
                items(play.rewards, key = { "reward:${it.rating}" }) { reward ->
                    val tone = theaterRatingColor(reward.rating)
                    Card(Modifier.fillMaxWidth().testTag("theater-reward:${reward.rating}"), colors = CardDefaults.cardColors(containerColor = tone.copy(alpha = 0.10f))) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(reward.rating.name, color = tone, style = MaterialTheme.typography.titleMedium)
                            Text(reward.text ?: "报酬未收录", style = MaterialTheme.typography.bodyLarge)
                            Text("来源 ${reward.cellAddress}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!play.hasRecordedCoverage) Text("部分选项或报酬尚未收录，遇到缺项请以游戏内显示为准。")
                        val preferences = TheaterGuideQuery.preferences(current.guide, playId = play.id)
                        Text(if (preferences.isEmpty()) "暂未收录这部剧目的角色偏好与擅长演员名单。" else "已收录角色偏好")
                        preferences.forEach { preference ->
                            TextButton(onClick = { onOpenWiki(preference.characterId) }) { Text("${preference.label} · ${preference.evidence}") }
                        }
                        current.guide.notes.forEach { Text(it.text, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
        } else if (selectedCharacterId != null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                if (character == null) {
                    Column {
                        Text(characterError ?: if (characterLoaded) "这条角色资料已不在当前版本中。" else "正在读取角色资料…")
                        if (characterError != null) TextButton(onClick = { retry++ }) { Text("重试") }
                    }
                } else Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("theater-character-detail")) {
                    WikiImage(character?.imageAsset, "${character?.title}头像", Modifier.size(104.dp), crop = true)
                    val preferences = TheaterGuideQuery.preferences(current.guide, characterId = selectedCharacterId)
                    if (preferences.isEmpty()) Text("暂未收录该角色的剧场偏好与擅长剧目，不代表没有偏好。")
                    preferences.forEach { preference ->
                        TextButton(onClick = { selectedPlayId = preference.playId }) {
                            Text("${current.guide.plays.single { it.id == preference.playId }.title} · ${preference.label}")
                        }
                    }
                    OutlinedButton(onClick = { onOpenWiki(requireNotNull(selectedCharacterId)) }, modifier = Modifier.testTag("theater-open-character")) { Text("查看角色图鉴") }
                }
            }
        } else {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.large) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Outlined.TheaterComedy, null)
                                Text(current.guide.weeklyResetNote, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                TextButton(onClick = { sourceOpen = !sourceOpen }, modifier = Modifier.testTag("theater-source")) { Text(if (sourceOpen) "收起规则" else "规则与来源") }
                            }
                            Text(current.guide.activities.firstOrNull()?.note.orEmpty())
                            Text("${current.guide.plays.size} 部剧目 · 收藏保存到${current.profileName}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (sourceOpen) {
                        current.guide.activities.forEach { activity ->
                            Text("${activity.label} · ${activity.participants}人", style = MaterialTheme.typography.titleSmall)
                            Text(activity.effect)
                            Text("${current.guide.activitySheetName} ${activity.cellAddress}", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("来源记录情境：${current.guide.contextNote}（不作为全路线解锁条件）")
                        current.guide.notes.forEach { Text(it.text) }
                        Text("${current.guide.sourceSheetName} · 修订 ${current.guide.sourceRevision}", style = MaterialTheme.typography.bodySmall)
                        current.guide.relatedSidequestId?.let { questId -> TextButton(onClick = { onOpenWiki(questId) }) { Text(current.guide.relatedSidequestTitle) } }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(mode == "plays", { mode = "plays" }, label = { Text("剧目与选项") }, modifier = Modifier.testTag("theater-mode-plays"), leadingIcon = { Icon(Icons.Outlined.TheaterComedy, null) })
                        FilterChip(mode == "characters", { mode = "characters" }, label = { Text("角色偏好") }, modifier = Modifier.testTag("theater-mode-characters"), leadingIcon = { Icon(Icons.Outlined.Person, null) })
                    }
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().testTag("theater-search"), singleLine = true, label = { Text(if (mode == "plays") "搜索剧目、选项或报酬" else "搜索角色") })
                    if (mode == "plays") FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(onlyFavorites, { onlyFavorites = !onlyFavorites }, enabled = current.snapshot != null, label = { Text("只看收藏") }, modifier = Modifier.testTag("theater-only-favorites"))
                        FilterChip(coverageName == TheaterCoverage.GAPS.name, { coverageName = if (coverageName == TheaterCoverage.GAPS.name) TheaterCoverage.ALL.name else TheaterCoverage.GAPS.name }, label = { Text("仍有缺项") }, modifier = Modifier.testTag("theater-only-gaps"))
                    }
                    if (mode == "characters" && current.guide.preferenceStatus == "unrecorded_in_source") Text("当前来源未提供角色剧场偏好及擅长演员名单。可查看角色资料，不会用礼物或料理偏好猜补。", modifier = Modifier.testTag("theater-preference-gap"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (current.snapshot == null) Text("收藏无法读取，暂不能修改；剧场资料仍可查阅。", color = MaterialTheme.colorScheme.error)
                    else if (onlyFavorites && bookmarks.values.any { it.isFailure }) Text("部分收藏有冲突或无法识别，尚未计入筛选结果，请到个人存档检查。", color = MaterialTheme.colorScheme.error)
                    message?.let { Text(it) }
                }
            }
            if (mode == "plays") {
                val plays = TheaterGuideQuery.plays(current.guide, query, TheaterCoverage.valueOf(coverageName),
                    if (onlyFavorites) bookmarks.filterValues { it.getOrNull()?.favorite == true }.keys else null)
                if (plays.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Text("没有匹配的剧目。试试其他关键词或取消筛选。") }
                items(plays, key = { it.id }) { summary ->
                    Card(onClick = { focusManager.clearFocus(); selectedPlayId = summary.id }, modifier = Modifier.fillMaxWidth().testTag("theater-play:${summary.id}")) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                                Icon(Icons.Outlined.TheaterComedy, null, Modifier.padding(16.dp).size(36.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                            Text(summary.title, style = MaterialTheme.typography.titleMedium)
                            Text("${summary.cues.size} 条选项 · ${summary.rewards.count { it.text != null }}/4 档报酬", style = MaterialTheme.typography.bodySmall)
                            if (!summary.hasRecordedCoverage) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Icons.Outlined.Info, null, Modifier.size(18.dp)); Text("仍有缺项", style = MaterialTheme.typography.labelMedium)
                            }
                            TheaterFavorite(summary, bookmarks[summary.id], saving, { toggleFavorite(summary) })
                        }
                    }
                }
            } else {
                if (characterError != null) item(span = { GridItemSpan(maxLineSpan) }) {
                    Column { Text(requireNotNull(characterError)); TextButton(onClick = { retry++ }) { Text("重试") } }
                } else if (characterPage == null) item(span = { GridItemSpan(maxLineSpan) }) { CircularProgressIndicator() }
                else {
                    if (characterPage!!.entries.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Text("没有匹配的角色。") }
                    items(characterPage!!.entries, key = { it.id }) { entry ->
                        Card(onClick = { focusManager.clearFocus(); selectedCharacterId = entry.id }, modifier = Modifier.fillMaxWidth().testTag("theater-character:${entry.id}")) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                WikiImage(entry.imageAsset, "${entry.title}头像", Modifier.size(84.dp), crop = true)
                                Text(entry.title, style = MaterialTheme.typography.titleMedium)
                                val preferenceCount = TheaterGuideQuery.preferences(current.guide, characterId = entry.id).size
                                Text(if (preferenceCount == 0) "偏好未收录" else "$preferenceCount 部已录偏好", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { characterPageIndex-- }, enabled = characterPage!!.hasPrevious) { Text("上一页") }
                            OutlinedButton(onClick = { characterPageIndex++ }, enabled = characterPage!!.hasNext) { Text("下一页") }
                        }
                    }
                }
            }
        }
        if (detail && current != null) item(span = { GridItemSpan(maxLineSpan) }) {
            Text("${current.guide.sourceSheetName} · 修订 ${current.guide.sourceRevision}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TheaterGuideGrid(state: LazyGridState, content: LazyGridScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 1040.dp)) {
        val columns = if (maxWidth < 332.dp) 1 else if (maxWidth < 600.dp) 2 else if (maxWidth < 900.dp) 3 else 4
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns), state = state, modifier = Modifier.fillMaxSize().testTag("theater-grid"),
            contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content,
        )
    }
}

@Composable
private fun TheaterFavorite(play: TheaterPlay, result: Result<TheaterBookmark>?, saving: Boolean, onClick: () -> Unit) {
    val favorite = result?.getOrNull()?.favorite == true
    Column {
        FilterChip(favorite, onClick, enabled = result?.isSuccess == true && !saving,
            label = { Text(if (favorite) "已收藏" else "收藏") }, modifier = Modifier.heightIn(min = 48.dp).testTag("theater-favorite:${play.id}"),
            leadingIcon = { Icon(if (favorite) Icons.Outlined.Star else Icons.Outlined.StarBorder, null, Modifier.size(18.dp)) })
        if (result?.isFailure == true) Text(if (result.exceptionOrNull()?.message?.contains("冲突") == true) "收藏存在设备间冲突，请到个人存档处理" else "收藏记录无法识别，请到个人存档检查", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun theaterRatingColor(rating: TheaterRating): Color {
    val dark = isSystemInDarkTheme()
    return when (rating) {
        TheaterRating.PERFECT -> if (dark) Color(0xFFDFC591) else Color(0xFF79551B)
        TheaterRating.GREAT -> if (dark) Color(0xFFA9D5B0) else Color(0xFF35643F)
        TheaterRating.GOOD -> if (dark) Color(0xFFAFCDEB) else Color(0xFF3D6082)
        TheaterRating.BAD -> if (dark) Color(0xFFF3B4AA) else Color(0xFFA14237)
    }
}
