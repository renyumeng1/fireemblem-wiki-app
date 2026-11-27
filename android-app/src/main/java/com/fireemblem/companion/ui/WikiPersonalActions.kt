package com.fireemblem.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CompareArrows
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fireemblem.companion.content.WikiEntry
import com.fireemblem.companion.personal.PersonalRevision
import com.fireemblem.companion.personal.PersonalStore
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Composable
internal fun WikiEntryActions(entry: WikiEntry, onCompare: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { PersonalStore(context) }
    val scope = rememberCoroutineScope()
    val profile = context.getSharedPreferences("personal-view", 0).getString("profile", "global") ?: "global"
    val entityId = remember(profile, entry.id) { UUID.nameUUIDFromBytes("bookmark:$profile:${entry.id}".toByteArray()).toString() }
    var heads by remember(entityId) { mutableStateOf<List<PersonalRevision>?>(null) }
    var busy by remember(entityId) { mutableStateOf(false) }
    var error by remember(entityId) { mutableStateOf<String?>(null) }
    var editing by remember(entityId) { mutableStateOf(false) }
    var note by remember(entityId) { mutableStateOf("") }
    LaunchedEffect(entityId) {
        try { heads = withContext(Dispatchers.IO) { store.snapshot().heads()[entityId].orEmpty() } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "无法读取个人记录，请到“我的”检查。" }
    }
    val current = heads?.singleOrNull()
    val favorite = current?.takeUnless { it.deleted }?.payload?.get("favorite")?.jsonPrimitive?.booleanOrNull == true
    val completed = current?.takeUnless { it.deleted }?.payload?.get("completed")?.jsonPrimitive?.booleanOrNull == true
    val isCampaignTask = entry.id.substringBefore(':') in setOf("side-quests", "paralogues", "characters")
    fun save(marked: Boolean, done: Boolean, text: String) {
        val parents = heads?.map(PersonalRevision::revisionId)?.toSet() ?: return
        busy = true
        scope.launch {
            try {
                val payload = buildJsonObject {
                    put("profileId", profile); put("wikiId", entry.id); put("name", entry.title)
                    put("favorite", marked); put("completed", done); put("note", text)
                }
                heads = withContext(Dispatchers.IO) { store.edit(entityId, "bookmark", payload, expectedParents = parents).heads()[entityId].orEmpty() }
                editing = false; error = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "保存未成功，原记录仍保留；请重新打开资料后重试。" }
            finally { busy = false }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val savedNote = current?.payload?.get("note")?.jsonPrimitive?.content.orEmpty()
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OutlinedButton(onClick = onCompare, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.AutoMirrored.Outlined.CompareArrows, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("加入比较")
            }
            FilterChip(
                selected = favorite,
                enabled = heads != null && heads!!.size <= 1 && !busy,
                onClick = { save(!favorite, completed, savedNote) },
                label = { Text(if (favorite) "已收藏" else "收藏") },
                leadingIcon = {
                    Icon(if (favorite) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                        contentDescription = null, modifier = Modifier.size(18.dp))
                },
                modifier = Modifier.heightIn(min = 48.dp),
            )
            OutlinedButton(enabled = heads != null && heads!!.size <= 1 && !busy,
                onClick = { note = savedNote; editing = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Outlined.EditNote, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("笔记")
            }
        }
        if (isCampaignTask) Text("请在攻略的路线任务清单中分别记录每条路线的进度。",
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
        if (savedNote.isNotBlank()) Text(savedNote)
        if ((heads?.size ?: 0) > 1) Text("此条记录有设备间冲突，请到“我的”选择保留的版本。")
        error?.let { Text(it) }
    }
    if (editing) AlertDialog(onDismissRequest = { if (!busy) editing = false }, title = { Text("${entry.title} · 笔记") },
        text = { OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), minLines = 3, label = { Text("写下心得或待办") }) },
        confirmButton = { TextButton(enabled = !busy, onClick = { save(favorite, completed, note) }) { Text("保存") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { editing = false }) { Text("取消") } })
}
