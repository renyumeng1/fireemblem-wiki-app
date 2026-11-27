package com.fireemblem.companion.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fireemblem.companion.content.StatRatingTier
import com.fireemblem.companion.content.WikiEntry
import com.fireemblem.companion.content.WikiRepository
import com.fireemblem.companion.engine.BuddyCampaignLine
import com.fireemblem.companion.engine.GrowthStat
import com.fireemblem.companion.ocr.LocalOnnxOcrRecognizer
import com.fireemblem.companion.ocr.OcrDownloadProgress
import com.fireemblem.companion.ocr.OcrModelManager
import com.fireemblem.companion.ocr.OcrModelStatus
import com.fireemblem.companion.ocr.ScreenshotStatRecognizer
import com.fireemblem.companion.ocr.WikiOcrDictionaryLoader
import com.fireemblem.companion.personal.FieldConfidence
import com.fireemblem.companion.personal.NsScreenshotStatParser
import com.fireemblem.companion.personal.ParsedRouteMergeDraft
import com.fireemblem.companion.personal.RouteMergeSnapshot
import com.fireemblem.companion.personal.RouteMergeStoredRecord
import com.fireemblem.companion.ui.theme.tabularNumbers
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class OcrBatchReviewItem(
    val uri: Uri,
    val draft: ParsedRouteMergeDraft,
    val selected: Boolean,
    val characterId: String?,
    val characterName: String,
    val chosenLine: BuddyCampaignLine?,
    val levelText: String,
    val professionText: String,
    val statTexts: List<String>,
    val parseError: String? = null,
) {
    fun parsedLevel(): Int? = levelText.trim().toIntOrNull()?.takeIf { it in 1..99 }

    fun parsedStats(): List<Int>? {
        val parsed = statTexts.map { it.trim().toIntOrNull() }
        return if (parsed.size == GrowthStat.entries.size && parsed.all { it != null && it in 0..999 }) {
            parsed.filterNotNull()
        } else null
    }

    fun isCommitValid(): Boolean =
        !characterId.isNullOrBlank() &&
            chosenLine != null &&
            parsedLevel() != null &&
            parsedStats() != null

    fun toSnapshot(existingNote: String): RouteMergeSnapshot? {
        val line = chosenLine ?: return null
        val lv = parsedLevel() ?: return null
        val stats = parsedStats() ?: return null
        return RouteMergeSnapshot(
            line = line,
            level = lv,
            profession = professionText.trim(),
            stats = stats,
            note = existingNote,
        )
    }
}

internal data class OcrBatchCommitEntry(
    val characterId: String,
    val characterName: String,
    val snapshot: RouteMergeSnapshot,
)

@Composable
internal fun LocalOcrModelSettingsCard(
    managerOverride: OcrModelManager? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember(context, managerOverride) { managerOverride ?: OcrModelManager(context) }
    var status by remember(manager) { mutableStateOf(manager.status()) }
    var progress by remember { mutableStateOf<OcrDownloadProgress?>(null) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    val totalMb = "%.1f".format(manager.totalPackageBytes / (1024.0 * 1024.0))

    EditorialCard(modifier = Modifier.fillMaxWidth().testTag("ocr-settings-card")) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.DocumentScanner, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("本地 OCR 模型", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "按需下载 PP-OCRv4 中文端侧模型（约 $totalMb MB），用于离线批量识别 Switch 状态截图；不下载不占空间。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            when (val current = status) {
                OcrModelStatus.NotInstalled -> {
                    val dl = progress
                    if (downloadJob != null && dl != null) {
                        val doneMb = "%.1f".format(dl.bytesDownloaded / (1024.0 * 1024.0))
                        Text(
                            "正在下载 (${dl.currentFileIndex}/${dl.totalFiles})：$doneMb / $totalMb MB (${(dl.fraction * 100).toInt()}%)",
                            style = MaterialTheme.typography.bodySmall.tabularNumbers(),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        LinearProgressIndicator(progress = { dl.fraction }, modifier = Modifier.fillMaxWidth())
                        OutlinedButton(
                            onClick = {
                                downloadJob?.cancel()
                                downloadJob = null
                                progress = null
                                message = "已取消下载，未残留临时文件。"
                                isError = false
                            },
                            modifier = Modifier.heightIn(min = 44.dp).testTag("ocr-settings-cancel"),
                        ) {
                            Text("取消下载")
                        }
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(
                                onClick = {
                                    message = null
                                    isError = false
                                    downloadJob = scope.launch {
                                        try {
                                            val installed = manager.download { p -> progress = p }
                                            status = installed
                                            message = "本地 OCR 模型已下载并校验通过，可完全离线批量识别 Switch 截图。"
                                            isError = false
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (e: Exception) {
                                            message = "下载失败：${e.message ?: "请检查网络连接"}"
                                            isError = true
                                        } finally {
                                            progress = null
                                            downloadJob = null
                                        }
                                    }
                                },
                                modifier = Modifier.heightIn(min = 48.dp).testTag("ocr-settings-download"),
                            ) {
                                Icon(Icons.Outlined.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("下载本地 OCR 模型（约 $totalMb MB）")
                            }
                        }
                    }
                }
                is OcrModelStatus.Installed -> {
                    val sizeMb = "%.1f".format(current.totalBytes / (1024.0 * 1024.0))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.CheckCircleOutline, contentDescription = null, tint = statTierPalette(StatRatingTier.HIGH).content)
                        Text(
                            "已就绪 · 占用 $sizeMb MB · 本地完全离线可用",
                            style = MaterialTheme.typography.bodyMedium.tabularNumbers(),
                            color = statTierPalette(StatRatingTier.HIGH).content,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { manager.deleteModels() }
                                status = manager.status()
                                message = "已删除本地 OCR 模型并释放 $sizeMb MB 空间。"
                                isError = false
                            }
                        },
                        modifier = Modifier.heightIn(min = 44.dp).testTag("ocr-settings-delete"),
                    ) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("删除模型释放空间")
                    }
                }
            }

            message?.let { msg ->
                Surface(
                    color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().testTag("ocr-settings-status"),
                ) {
                    Text(msg, Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
internal fun OcrModelDownloadPromptDialog(
    manager: OcrModelManager,
    onDismiss: () -> Unit,
    onReady: (OcrModelStatus.Installed) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<OcrDownloadProgress?>(null) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val totalMb = "%.1f".format(manager.totalPackageBytes / (1024.0 * 1024.0))

    AlertDialog(
        onDismissRequest = {
            if (!downloading) onDismiss()
        },
        title = { Text("下载本地 OCR 识别模型") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "批量识别 Switch 截图需要先下载 PP-OCRv4 中文端侧模型（约 $totalMb MB，国内源直连，校验通过后完全离线运行，随时可在「我的」中删除）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (downloading) {
                    val p = progress
                    val pct = ((p?.fraction ?: 0f) * 100).toInt()
                    val doneMb = "%.1f".format((p?.bytesDownloaded ?: 0L) / (1024.0 * 1024.0))
                    Text(
                        "正在下载：$doneMb / $totalMb MB ($pct%)",
                        style = MaterialTheme.typography.bodySmall.tabularNumbers(),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    LinearProgressIndicator(
                        progress = { p?.fraction ?: 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                errorMsg?.let { err ->
                    Text(err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            if (!downloading) {
                Button(
                    onClick = {
                        errorMsg = null
                        downloading = true
                        downloadJob = scope.launch {
                            try {
                                val installed = manager.download { p -> progress = p }
                                downloading = false
                                onReady(installed)
                            } catch (cancelled: CancellationException) {
                                downloading = false
                                throw cancelled
                            } catch (e: Exception) {
                                downloading = false
                                errorMsg = "下载失败：${e.message ?: "请检查网络后重试"}"
                            }
                        }
                    },
                    modifier = Modifier.testTag("ocr-prompt-download"),
                ) {
                    Text("立即下载（$totalMb MB）")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    downloadJob?.cancel()
                    downloadJob = null
                    downloading = false
                    onDismiss()
                },
            ) {
                Text(if (downloading) "取消下载" else "稍后再说")
            }
        },
    )
}

@Composable
internal fun RouteMergeOcrImportSheet(
    uris: List<Uri>,
    repository: WikiRepository?,
    characters: List<WikiEntry>,
    recordByCharId: Map<String, RouteMergeStoredRecord>,
    modelStatus: OcrModelStatus.Installed?,
    modelManager: OcrModelManager? = null,
    recognizerFactory: (() -> ScreenshotStatRecognizer)?,
    onCancel: () -> Unit,
    onCommitBatch: (List<OcrBatchCommitEntry>) -> Unit,
) {
    BackHandler(onBack = onCancel)
    val context = LocalContext.current
    val items = remember { mutableStateListOf<OcrBatchReviewItem>() }
    var processing by remember { mutableStateOf(true) }
    var processedCount by remember { mutableIntStateOf(0) }
    var fatalError by remember { mutableStateOf<String?>(null) }
    var pickingCharForIndex by remember { mutableStateOf<Int?>(null) }
    var zoomingUri by remember { mutableStateOf<Uri?>(null) }

    LaunchedEffect(uris, modelStatus, modelManager, recognizerFactory) {
        items.clear()
        processing = true
        processedCount = 0
        fatalError = null
        try {
            val dictionary = withContext(Dispatchers.IO) { WikiOcrDictionaryLoader.load(repository) }
            val recognizer: ScreenshotStatRecognizer = recognizerFactory?.invoke()
                ?: run {
                    val installed = modelStatus
                        ?: withContext(Dispatchers.IO) { modelManager?.status() as? OcrModelStatus.Installed }
                        ?: throw IllegalStateException("本地 OCR 模型未就绪，请先在「我的」中下载。")
                    LocalOnnxOcrRecognizer(context, installed.detFile, installed.recFile)
                }
            try {
                val parsedList = mutableListOf<OcrBatchReviewItem>()
                for (uri in uris) {
                    if (!isActive) break
                    val item = runCatching {
                        val recognized = recognizer.recognize(uri)
                        val draft = NsScreenshotStatParser.parse(recognized, dictionary)
                        OcrBatchReviewItem(
                            uri = uri,
                            draft = draft,
                            selected = draft.isHighConfidenceReady,
                            characterId = draft.characterId,
                            characterName = draft.characterName,
                            chosenLine = draft.detectedLine,
                            levelText = draft.level?.toString().orEmpty(),
                            professionText = draft.profession,
                            statTexts = draft.stats.map { it?.toString().orEmpty() },
                        )
                    }.getOrElse { err ->
                        val emptyDraft = ParsedRouteMergeDraft(
                            sourceUri = uri.toString(),
                            characterId = null,
                            characterName = "",
                            characterConfidence = FieldConfidence.MISSING,
                            detectedLine = null,
                            lineReason = null,
                            level = null,
                            levelConfidence = FieldConfidence.MISSING,
                            profession = "",
                            professionConfidence = FieldConfidence.MISSING,
                            stats = List(GrowthStat.entries.size) { null },
                            statConfidences = List(GrowthStat.entries.size) { FieldConfidence.MISSING },
                            warnings = listOf("识别失败：${err.message ?: "图片无法解析"}"),
                        )
                        OcrBatchReviewItem(
                            uri = uri,
                            draft = emptyDraft,
                            selected = false,
                            characterId = null,
                            characterName = "",
                            chosenLine = null,
                            levelText = "",
                            professionText = "",
                            statTexts = List(GrowthStat.entries.size) { "" },
                            parseError = err.message ?: "无法解析图片",
                        )
                    }
                    parsedList += item
                    processedCount++
                }
                // Auto-uncheck exact (characterId, route) duplicates within the batch
                val dupMap = NsScreenshotStatParser.findBatchDuplicates(
                    parsedList.map { it.characterId to it.chosenLine },
                )
                parsedList.forEachIndexed { idx, it ->
                    items += if (idx in dupMap) it.copy(selected = false) else it
                }
            } finally {
                recognizer.close()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (err: Exception) {
            fatalError = err.message ?: "OCR 识别初始化失败"
        } finally {
            processing = false
        }
    }

    val duplicateIndexMap = remember(items.toList()) {
        val checkedKeys = items.map { if (it.selected) it.characterId to it.chosenLine else null to null }
        NsScreenshotStatParser.findBatchDuplicates(checkedKeys)
    }
    val allDupIndexMap = remember(items.toList()) {
        NsScreenshotStatParser.findBatchDuplicates(items.map { it.characterId to it.chosenLine })
    }

    val readyCount = items.count { it.selected && it.isCommitValid() }
    val missingLineCount = items.count { it.chosenLine == null }
    val needReviewCount = items.count { !it.isCommitValid() || it.draft.checksumMatched == false }

    if (pickingCharForIndex != null) {
        val targetIdx = pickingCharForIndex!!
        var query by remember { mutableStateOf("") }
        val filtered = remember(characters, query) {
            val q = query.trim()
            if (q.isEmpty()) characters else characters.filter { it.title.contains(q, ignoreCase = true) }
        }
        AlertDialog(
            onDismissRequest = { pickingCharForIndex = null },
            title = { Text("选择对应角色") },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("搜索角色名…") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        itemsIndexed(filtered.take(40)) { _, entry ->
                            Surface(
                                onClick = {
                                    val cur = items[targetIdx]
                                    val updated = cur.copy(
                                        characterId = entry.id,
                                        characterName = entry.title,
                                    )
                                    items[targetIdx] = updated.copy(selected = updated.isCommitValid())
                                    pickingCharForIndex = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                            ) {
                                Text(entry.title, Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { pickingCharForIndex = null }) { Text("取消") } },
        )
    }

    if (zoomingUri != null) {
        val fullBmp by produceState<ImageBitmap?>(null, zoomingUri) {
            value = withContext(Dispatchers.IO) { decodeThumbnail(context, zoomingUri!!, 1280) }
        }
        AlertDialog(
            onDismissRequest = { zoomingUri = null },
            title = { Text("原截图预览") },
            text = {
                if (fullBmp != null) {
                    Image(
                        bitmap = fullBmp!!,
                        contentDescription = "原截图",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(RoundedCornerShape(10.dp)),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Text("正在载入截图…")
                }
            },
            confirmButton = { TextButton(onClick = { zoomingUri = null }) { Text("关闭") } },
        )
    }

    val highPal = statTierPalette(StatRatingTier.HIGH)
    val lowPal = statTierPalette(StatRatingTier.LOW)
    val medPal = statTierPalette(StatRatingTier.MEDIUM)

    Column(Modifier.fillMaxSize().testTag("ocr-batch-sheet")) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Spacer(Modifier.height(6.dp)) }
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                        Text("取消返回")
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            "NS 截图批量识别",
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.semantics { heading() },
                        )
                        Text(
                            if (processing) "正在识别第 $processedCount / ${uris.size} 张…"
                            else "共 ${items.size} 张 · 可写入 $readyCount 张" +
                                if (missingLineCount > 0) " · 待选路线 $missingLineCount 张" else "",
                            style = MaterialTheme.typography.bodySmall.tabularNumbers(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (processing) {
                item {
                    EditorialCard {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                "本地离线模型识别中（$processedCount / ${uris.size}）…",
                                style = MaterialTheme.typography.titleSmall.tabularNumbers(),
                                color = MaterialTheme.colorScheme.primary,
                            )
                            val frac = if (uris.isEmpty()) 0f else processedCount.toFloat() / uris.size.toFloat()
                            LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }

            fatalError?.let { err ->
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(err, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            // Bulk default route setter for any undetermined screenshots
            if (!processing && items.isNotEmpty() && missingLineCount > 0) {
                item {
                    EditorialCard(containerColor = medPal.container) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "有 $missingLineCount 张截图未能自动判线，可一键批量指定：",
                                style = MaterialTheme.typography.titleSmall,
                                color = medPal.content,
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                BuddyCampaignLine.entries.forEach { line ->
                                    val tone = routeLineTone(line)
                                    Surface(
                                        onClick = {
                                            for (i in items.indices) {
                                                val cur = items[i]
                                                if (cur.chosenLine == null) {
                                                    val next = cur.copy(chosenLine = line)
                                                    items[i] = next.copy(selected = next.isCommitValid())
                                                }
                                            }
                                            // Re-apply batch duplicate auto-uncheck after bulk assign
                                            val dup = NsScreenshotStatParser.findBatchDuplicates(
                                                items.map { if (it.selected) it.characterId to it.chosenLine else null to null },
                                            )
                                            dup.keys.forEach { dupIdx ->
                                                items[dupIdx] = items[dupIdx].copy(selected = false)
                                            }
                                        },
                                        modifier = Modifier.heightIn(min = 42.dp).testTag("ocr-bulk-line:${line.id}"),
                                        shape = RoundedCornerShape(10.dp),
                                        color = tone.first,
                                        contentColor = tone.second,
                                        border = BorderStroke(1.dp, tone.second.copy(alpha = 0.5f)),
                                    ) {
                                        Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                                            Text("未判线设为${line.shortTag()}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (!processing && duplicateIndexMap.isNotEmpty()) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().testTag("ocr-duplicate-warning"),
                    ) {
                        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.WarningAmber, contentDescription = null)
                            Text(
                                "已勾选列表中存在同角色同路线的重复项，请取消勾选重复截图或修改其路线后再写入。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            itemsIndexed(items, key = { idx, it -> "$idx:${it.uri}" }) { idx, item ->
                val dupOf = allDupIndexMap[idx]
                val existingSnap = item.characterId?.let { cid ->
                    item.chosenLine?.let { ln -> recordByCharId[cid]?.character?.snapshots?.get(ln) }
                }
                val thumb by produceState<ImageBitmap?>(null, item.uri) {
                    value = withContext(Dispatchers.IO) { decodeThumbnail(context, item.uri, 240) }
                }
                EditorialCard(
                    modifier = Modifier.fillMaxWidth().testTag("ocr-import-item:$idx"),
                    containerColor = if (item.selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        // Top bar: Checkbox + Thumbnail + Character + Checksum Badge
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Checkbox(
                                checked = item.selected,
                                onCheckedChange = { checked -> items[idx] = item.copy(selected = checked) },
                                modifier = Modifier.testTag("ocr-item-check:$idx"),
                            )
                            Surface(
                                modifier = Modifier.size(width = 76.dp, height = 44.dp).clickable { zoomingUri = item.uri },
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ) {
                                if (thumb != null) {
                                    Image(
                                        bitmap = thumb!!,
                                        contentDescription = "截图缩略图",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                } else {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text("#${idx + 1}", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        item.characterName.ifBlank { "未识别角色（点此选择）" },
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (item.characterId == null) lowPal.content else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.clickable { pickingCharForIndex = idx },
                                    )
                                    TextButton(
                                        onClick = { pickingCharForIndex = idx },
                                        modifier = Modifier.heightIn(min = 32.dp),
                                    ) {
                                        Text("换人", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                                item.draft.lineReason?.let { reason ->
                                    Text(reason, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            when (item.draft.checksumMatched) {
                                true -> EditorialBadge(
                                    text = "校验通过 ${item.draft.checksumDeclared}",
                                    containerColor = highPal.badgeContainer,
                                    contentColor = highPal.content,
                                )
                                false -> EditorialBadge(
                                    text = "校验异常",
                                    containerColor = lowPal.badgeContainer,
                                    contentColor = lowPal.content,
                                )
                                null -> {}
                            }
                        }

                        if (dupOf != null) {
                            Surface(
                                color = medPal.container,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    "与第 ${dupOf + 1} 张为同角色同路线重复截图（已默认取消勾选）",
                                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = medPal.content,
                                )
                            }
                        }

                        // Route selection pills
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            BuddyCampaignLine.entries.forEach { line ->
                                val sel = item.chosenLine == line
                                val tone = routeLineTone(line)
                                Surface(
                                    onClick = {
                                        val next = item.copy(chosenLine = line)
                                        items[idx] = next.copy(selected = next.isCommitValid())
                                    },
                                    modifier = Modifier
                                        .heightIn(min = 38.dp)
                                        .semantics { selected = sel }
                                        .testTag("ocr-item-line:$idx:${line.id}"),
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (sel) tone.first else MaterialTheme.colorScheme.surfaceContainerLow,
                                    contentColor = if (sel) tone.second else MaterialTheme.colorScheme.onSurfaceVariant,
                                    border = BorderStroke(
                                        1.dp,
                                        when {
                                            sel -> tone.second
                                            item.chosenLine == null -> medPal.content.copy(alpha = 0.7f)
                                            else -> MaterialTheme.colorScheme.outlineVariant
                                        },
                                    ),
                                ) {
                                    Box(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
                                        Text(
                                            if (sel) "✓ ${line.shortTag()}" else line.shortTag(),
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                        )
                                    }
                                }
                            }
                        }

                        // Existing record diff banner
                        if (existingSnap != null) {
                            val newTotal = item.parsedStats()?.sum()
                            val diffTxt = if (newTotal != null) {
                                val d = newTotal - existingSnap.totalStats
                                val sign = if (d >= 0) "+" else ""
                                "（总和 ${existingSnap.totalStats} → $newTotal，$sign$d，保留原备注）"
                            } else "（将覆盖更新并保留原备注）"
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth().testTag("ocr-item-diff:$idx"),
                            ) {
                                Text(
                                    "更新已有记录：Lv.${existingSnap.level} → Lv.${item.levelText.ifBlank { "?" }}$diffTxt",
                                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.labelSmall.tabularNumbers(),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }

                        // Editable Level + Profession
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = item.levelText,
                                onValueChange = { v ->
                                    if (v.length <= 2 && v.all(Char::isDigit)) {
                                        items[idx] = item.copy(levelText = v)
                                    }
                                },
                                label = { Text("等级") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                isError = item.parsedLevel() == null,
                                modifier = Modifier.weight(0.32f).testTag("ocr-item-level:$idx"),
                                shape = RoundedCornerShape(10.dp),
                            )
                            OutlinedTextField(
                                value = item.professionText,
                                onValueChange = { v ->
                                    if (v.length <= 48) items[idx] = item.copy(professionText = v)
                                },
                                label = { Text("职业") },
                                singleLine = true,
                                modifier = Modifier.weight(0.68f).testTag("ocr-item-prof:$idx"),
                                shape = RoundedCornerShape(10.dp),
                            )
                        }

                        // Compact 9-stat 3x3 editable grid
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            GrowthStat.entries.chunked(3).forEach { statRow ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    statRow.forEach { stat ->
                                        val sIdx = stat.ordinal
                                        val conf = item.draft.statConfidences.getOrElse(sIdx) { FieldConfidence.MISSING }
                                        val edited = item.statTexts[sIdx].trim() != item.draft.stats.getOrNull(sIdx)?.toString().orEmpty()
                                        val parsedNum = item.statTexts[sIdx].trim().toIntOrNull()
                                        val bad = parsedNum == null || (!edited && conf != FieldConfidence.HIGH)
                                        OutlinedTextField(
                                            value = item.statTexts[sIdx],
                                            onValueChange = { v ->
                                                if (v.length <= 3 && v.all(Char::isDigit)) {
                                                    val nextStats = item.statTexts.toMutableList().also { it[sIdx] = v }
                                                    items[idx] = item.copy(statTexts = nextStats)
                                                }
                                            },
                                            label = { Text(stat.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                            singleLine = true,
                                            isError = bad,
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            textStyle = MaterialTheme.typography.bodyMedium.tabularNumbers(),
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(10.dp),
                                        )
                                    }
                                }
                            }
                        }

                        if (item.draft.warnings.isNotEmpty() && !item.isCommitValid()) {
                            Text(
                                item.draft.warnings.joinToString(" "),
                                style = MaterialTheme.typography.labelSmall,
                                color = lowPal.content,
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(12.dp)) }
        }

        // Bottom Sticky Commit Bar
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "已选 $readyCount / ${items.size} 条有效记录",
                        style = MaterialTheme.typography.labelLarge.tabularNumbers(),
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (needReviewCount > 0) {
                        Text(
                            "$needReviewCount 条需核对或补全",
                            style = MaterialTheme.typography.labelSmall,
                            color = medPal.content,
                        )
                    }
                }
                Button(
                    enabled = !processing && readyCount > 0 && duplicateIndexMap.isEmpty(),
                    onClick = {
                        val entries = items.mapNotNull { item ->
                            if (!item.selected || !item.isCommitValid()) return@mapNotNull null
                            val cid = item.characterId ?: return@mapNotNull null
                            val existingNote = recordByCharId[cid]?.character?.snapshots?.get(item.chosenLine)?.note.orEmpty()
                            val snap = item.toSnapshot(existingNote) ?: return@mapNotNull null
                            OcrBatchCommitEntry(cid, item.characterName, snap)
                        }
                        if (entries.isNotEmpty()) onCommitBatch(entries)
                    },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("ocr-batch-commit"),
                ) {
                    Text("批量写入（$readyCount）")
                }
            }
        }
    }
}

private fun decodeThumbnail(context: Context, uri: Uri, maxDim: Int): ImageBitmap? = runCatching {
    val open = {
        when (uri.scheme) {
            "file" -> uri.path?.let { File(it).inputStream() }
            else -> context.contentResolver.openInputStream(uri)
        }
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
    val largest = maxOf(bounds.outWidth, bounds.outHeight)
    if (largest <= 0) return@runCatching null
    var sample = 1
    while (largest / sample > maxDim) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    open()?.use { BitmapFactory.decodeStream(it, null, opts)?.asImageBitmap() }
}.getOrNull()

private fun BuddyCampaignLine.shortTag(): String = when (this) {
    BuddyCampaignLine.KAI -> "凯伊线"
    BuddyCampaignLine.DIT -> "迪托线"
    BuddyCampaignLine.SEO -> "赛奥线"
    BuddyCampaignLine.LEDA -> "蕾达线"
}

@Composable
private fun routeLineTone(line: BuddyCampaignLine): Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color> {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    return when (line) {
        BuddyCampaignLine.KAI -> if (dark) androidx.compose.ui.graphics.Color(0xFF4A2524) to androidx.compose.ui.graphics.Color(0xFFFFDAD6)
            else androidx.compose.ui.graphics.Color(0xFFF9E2E0) to androidx.compose.ui.graphics.Color(0xFF7D241E)
        BuddyCampaignLine.DIT -> if (dark) androidx.compose.ui.graphics.Color(0xFF20354A) to androidx.compose.ui.graphics.Color(0xFFD6E9FF)
            else androidx.compose.ui.graphics.Color(0xFFE0ECF8) to androidx.compose.ui.graphics.Color(0xFF1F4770)
        BuddyCampaignLine.SEO -> if (dark) androidx.compose.ui.graphics.Color(0xFF45371B) to androidx.compose.ui.graphics.Color(0xFFFFE5A6)
            else androidx.compose.ui.graphics.Color(0xFFF9EED2) to androidx.compose.ui.graphics.Color(0xFF6B4C0B)
        BuddyCampaignLine.LEDA -> if (dark) androidx.compose.ui.graphics.Color(0xFF352C44) to androidx.compose.ui.graphics.Color(0xFFE6DCFA)
            else androidx.compose.ui.graphics.Color(0xFFECE6F6) to androidx.compose.ui.graphics.Color(0xFF483668)
    }
}
