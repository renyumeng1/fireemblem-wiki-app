package com.fireemblem.companion.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.fireemblem.companion.content.WikiPageRequest
import com.fireemblem.companion.content.WikiRepository
import com.fireemblem.companion.engine.BuddyCampaignLine
import com.fireemblem.companion.personal.OcrCharacterRef
import com.fireemblem.companion.personal.OcrRecognizedImage
import com.fireemblem.companion.personal.OcrSlotText
import com.fireemblem.companion.personal.OcrTextBox
import com.fireemblem.companion.personal.OcrWikiDictionary
import java.io.File
import java.io.IOException
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pluggable screenshot OCR interface so Child 2's local ONNX engine and Child 3's optional
 * Vision LLM adapter share the exact same downstream parser & batch review UI.
 */
interface ScreenshotStatRecognizer : AutoCloseable {
    suspend fun recognize(uri: Uri): OcrRecognizedImage
}

object WikiOcrDictionaryLoader {
    fun load(repository: WikiRepository?): OcrWikiDictionary {
        if (repository == null) return OcrWikiDictionary(emptyList(), emptyList())
        val chars = buildList {
            var offset = 0
            while (true) {
                val page = runCatching {
                    repository.page(WikiPageRequest(categoryId = "characters", offset = offset, limit = 100))
                }.getOrNull() ?: break
                page.entries.forEach { entry ->
                    val routes = runCatching { repository.characterRoutes(entry.id) }.getOrDefault(emptyList())
                    val playableLines = routes
                        .filter { it.joinKind != "unrecruitable" }
                        .mapNotNull { r -> BuddyCampaignLine.entries.firstOrNull { it.id == r.routeKey } }
                        .distinct()
                    add(
                        OcrCharacterRef(
                            id = entry.id,
                            canonicalName = entry.title,
                            exclusiveRoute = playableLines.singleOrNull(),
                        ),
                    )
                }
                if (!page.hasNext || page.entries.isEmpty()) break
                offset += page.entries.size
            }
        }
        val profs = buildList {
            var offset = 0
            while (true) {
                val page = runCatching {
                    repository.page(WikiPageRequest(categoryId = "professions", offset = offset, limit = 100))
                }.getOrNull() ?: break
                page.entries.forEach { add(it.title) }
                if (!page.hasNext || page.entries.isEmpty()) break
                offset += page.entries.size
            }
        }
        return OcrWikiDictionary(chars, profs)
    }
}

class LocalOnnxOcrRecognizer(
    private val context: Context,
    private val detModelFile: File,
    private val recModelFile: File,
) : ScreenshotStatRecognizer {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var detSession: OrtSession? = null
    private var recSession: OrtSession? = null
    private var charDict: List<String> = emptyList()

    private fun ensureInitialized() {
        if (detSession != null && recSession != null) return
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 4))
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        detSession = env.createSession(detModelFile.absolutePath, opts)
        val rec = env.createSession(recModelFile.absolutePath, opts)
        recSession = rec
        val metaChars = rec.metadata.customMetadata["character"].orEmpty()
        charDict = buildList {
            add("") // index 0 = CTC blank
            metaChars.lineSequence().forEach { add(it) }
            add(" ") // final space class
        }
    }

    override suspend fun recognize(uri: Uri): OcrRecognizedImage = withContext(Dispatchers.Default) {
        ensureInitialized()
        val bitmap = decodeBitmapBounded(uri)
            ?: throw IOException("无法解码截图文件，请确认图片格式有效。")
        try {
            recognizeBitmap(uri.toString(), bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    internal fun recognizeBitmap(sourceUri: String, bitmap: Bitmap): OcrRecognizedImage {
        ensureInitialized()
        val w = bitmap.width
        val h = bitmap.height
        val aspect = w.toFloat() / h.toFloat().coerceAtLeast(1f)

        // Stage 1: Fast surgical ROI path for 16:9 Switch 《万缕千丝》 "确认过关数据 / 确认状态" panel
        if (aspect in 1.65f..1.90f && w >= 640 && h >= 360) {
            val headerSlot = recognizeCrop(bitmap, 0.015f, 0.014f, 0.280f, 0.048f)
            val basicSlot = recognizeCrop(bitmap, 0.365f, 0.165f, 0.435f, 0.200f)
            val looksLikeSwitchConfirm =
                headerSlot.text.contains("确认") ||
                    headerSlot.text.contains("状态") ||
                    headerSlot.text.contains("过关") ||
                    basicSlot.text.contains("基本能力")
            if (looksLikeSwitchConfirm) {
                val slots = linkedMapOf<String, OcrSlotText>()
                slots["header"] = headerSlot
                SWITCH_CONFIRM_SLOTS.forEach { (key, rect) ->
                    slots[key] = recognizeCrop(bitmap, rect[0], rect[1], rect[2], rect[3])
                }
                val (crestLine, crestReason) = detectRouteCrest(bitmap)
                return OcrRecognizedImage(
                    sourceUri = sourceUri,
                    width = w,
                    height = h,
                    boxes = emptyList(),
                    detectedCrestLine = crestLine,
                    crestReason = crestReason,
                    templateSlots = slots,
                )
            }
        }

        // Stage 2: General DBNet full-frame detection + CRNN recognition fallback for any cropped/alternate screen
        val detectedRects = detectTextBoxes(bitmap)
        val recognizedBoxes = detectedRects.mapNotNull { rect ->
            val slot = recognizeCrop(bitmap, rect[0], rect[1], rect[2], rect[3])
            if (slot.text.isNotBlank() && slot.confidence >= 0.45f) {
                OcrTextBox(
                    text = slot.text,
                    left = rect[0],
                    top = rect[1],
                    right = rect[2],
                    bottom = rect[3],
                    confidence = slot.confidence,
                )
            } else null
        }
        return OcrRecognizedImage(
            sourceUri = sourceUri,
            width = w,
            height = h,
            boxes = recognizedBoxes,
        )
    }

    private fun detectRouteCrest(bitmap: Bitmap): Pair<BuddyCampaignLine?, String?> {
        val w = bitmap.width
        val h = bitmap.height
        val x1 = (w * 0.185f).roundToInt().coerceIn(0, w - 1)
        val y1 = (h * 0.180f).roundToInt().coerceIn(0, h - 1)
        val x2 = (w * 0.235f).roundToInt().coerceIn(x1 + 1, w)
        val y2 = (h * 0.340f).roundToInt().coerceIn(y1 + 1, h)
        val rw = x2 - x1
        val rh = y2 - y1
        val pixels = IntArray(rw * rh)
        bitmap.getPixels(pixels, 0, rw, x1, y1, rw, rh)
        var rSum = 0.0
        var gSum = 0.0
        var bSum = 0.0
        for (px in pixels) {
            rSum += (px shr 16) and 0xFF
            gSum += (px shr 8) and 0xFF
            bSum += px and 0xFF
        }
        val n = pixels.size.coerceAtLeast(1).toDouble()
        val r = (rSum / n).toFloat()
        val g = (gSum / n).toFloat()
        val b = (bSum / n).toFloat()

        // Calibrated from real 1920x1080 Switch screenshots (16/16 exact match, max deviation < 0.5 RGB):
        // SEO golden crest: (53.6, 49.1, 27.5)
        // KAI blue crest:   (41.7, 51.1, 69.4)
        // LEDA rose crest:  (53.2, 29.7, 33.3)
        val refs = listOf(
            BuddyCampaignLine.SEO to floatArrayOf(53.6f, 49.1f, 27.5f),
            BuddyCampaignLine.KAI to floatArrayOf(41.7f, 51.1f, 69.4f),
            BuddyCampaignLine.LEDA to floatArrayOf(53.2f, 29.7f, 33.3f),
        )
        var bestLine: BuddyCampaignLine? = null
        var bestDist = Float.MAX_VALUE
        for ((line, ref) in refs) {
            val dr = r - ref[0]
            val dg = g - ref[1]
            val db = b - ref[2]
            val dist = sqrt(dr * dr + dg * dg + db * db)
            if (dist < bestDist) {
                bestDist = dist
                bestLine = line
            }
        }
        return if (bestLine != null && bestDist < 14.0f) {
            bestLine to "画面路线徽章识别：${bestLine.label}"
        } else {
            null to null
        }
    }

    private fun detectTextBoxes(bitmap: Bitmap): List<FloatArray> {
        val sess = detSession ?: return emptyList()
        val w = bitmap.width
        val h = bitmap.height
        val scale = 960f / max(w, h).toFloat()
        val rw = max(32, ((w * scale) / 32f).roundToInt() * 32)
        val rh = max(32, ((h * scale) / 32f).roundToInt() * 32)

        val resized = Bitmap.createScaledBitmap(bitmap, rw, rh, true)
        val pixels = IntArray(rw * rh)
        resized.getPixels(pixels, 0, rw, 0, 0, rw, rh)
        if (resized !== bitmap) resized.recycle()

        val plane = rw * rh
        val input = FloatArray(3 * plane)
        val inv255 = 1f / 255f
        for (i in 0 until plane) {
            val px = pixels[i]
            val r = ((px shr 16) and 0xFF) * inv255
            val g = ((px shr 8) and 0xFF) * inv255
            val b = (px and 0xFF) * inv255
            // PaddleOCR DBNet expects BGR order normalized with ImageNet mean/std
            input[i] = (b - 0.485f) / 0.229f
            input[plane + i] = (g - 0.456f) / 0.224f
            input[2 * plane + i] = (r - 0.406f) / 0.225f
        }

        val inputName = sess.inputNames.first()
        val probMap = FloatArray(plane)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1L, 3L, rh.toLong(), rw.toLong())).use { tensor ->
            sess.run(mapOf(inputName to tensor)).use { result ->
                val outVal = result[0].value
                @Suppress("UNCHECKED_CAST")
                val nested = outVal as Array<Array<Array<FloatArray>>>
                val rows = nested[0][0]
                for (y in 0 until rh) {
                    System.arraycopy(rows[y], 0, probMap, y * rw, rw)
                }
            }
        }

        // Pure-Kotlin BFS connected components over probMap > 0.30
        val visited = BooleanArray(plane)
        val queue = IntArray(plane)
        val boxes = mutableListOf<FloatArray>()
        for (idx in 0 until plane) {
            if (visited[idx] || probMap[idx] <= 0.30f) continue
            var head = 0
            var tail = 0
            queue[tail++] = idx
            visited[idx] = true
            var minX = idx % rw
            var maxX = minX
            var minY = idx / rw
            var maxY = minY
            var sumProb = 0f
            var count = 0

            while (head < tail) {
                val cur = queue[head++]
                val cy = cur / rw
                val cx = cur % rw
                sumProb += probMap[cur]
                count++
                if (cx < minX) minX = cx
                if (cx > maxX) maxX = cx
                if (cy < minY) minY = cy
                if (cy > maxY) maxY = cy

                if (cy > 0) {
                    val n = cur - rw
                    if (!visited[n] && probMap[n] > 0.30f) {
                        visited[n] = true
                        queue[tail++] = n
                    }
                }
                if (cy + 1 < rh) {
                    val n = cur + rw
                    if (!visited[n] && probMap[n] > 0.30f) {
                        visited[n] = true
                        queue[tail++] = n
                    }
                }
                if (cx > 0) {
                    val n = cur - 1
                    if (!visited[n] && probMap[n] > 0.30f) {
                        visited[n] = true
                        queue[tail++] = n
                    }
                }
                if (cx + 1 < rw) {
                    val n = cur + 1
                    if (!visited[n] && probMap[n] > 0.30f) {
                        visited[n] = true
                        queue[tail++] = n
                    }
                }
            }

            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            if (bw < 3 || bh < 3 || count < 5) continue
            if (sumProb / count < 0.48f) continue

            val padY = max(2, (bh * 0.45f).toInt())
            val padX = max(3, (bh * 0.50f).toInt())
            val nx1 = ((minX - padX).coerceAtLeast(0)).toFloat() / rw
            val ny1 = ((minY - padY).coerceAtLeast(0)).toFloat() / rh
            val nx2 = ((maxX + 1 + padX).coerceAtMost(rw)).toFloat() / rw
            val ny2 = ((maxY + 1 + padY).coerceAtMost(rh)).toFloat() / rh
            boxes += floatArrayOf(nx1, ny1, nx2, ny2)
        }

        return boxes.sortedWith(compareBy<FloatArray> { (it[1] * 40f).toInt() }.thenBy { it[0] })
    }

    private fun recognizeCrop(
        bitmap: Bitmap,
        normLeft: Float,
        normTop: Float,
        normRight: Float,
        normBottom: Float,
    ): OcrSlotText {
        val sess = recSession ?: return OcrSlotText("", 0f)
        val w = bitmap.width
        val h = bitmap.height
        val x1 = (normLeft * w).roundToInt().coerceIn(0, w - 1)
        val y1 = (normTop * h).roundToInt().coerceIn(0, h - 1)
        val x2 = (normRight * w).roundToInt().coerceIn(x1 + 1, w)
        val y2 = (normBottom * h).roundToInt().coerceIn(y1 + 1, h)
        val cw = x2 - x1
        val ch = y2 - y1
        if (cw < 2 || ch < 2) return OcrSlotText("", 0f)

        val targetH = 48
        val targetW = ((cw.toFloat() * targetH / ch.toFloat()).roundToInt()).coerceIn(16, 640)
        val cropped = Bitmap.createBitmap(bitmap, x1, y1, cw, ch)
        val scaled = Bitmap.createScaledBitmap(cropped, targetW, targetH, true)
        if (cropped !== bitmap) cropped.recycle()

        val pixels = IntArray(targetW * targetH)
        scaled.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)
        scaled.recycle()

        val plane = targetW * targetH
        val input = FloatArray(3 * plane)
        val inv255 = 1f / 255f
        for (i in 0 until plane) {
            val px = pixels[i]
            val r = ((px shr 16) and 0xFF) * inv255
            val g = ((px shr 8) and 0xFF) * inv255
            val b = (px and 0xFF) * inv255
            input[i] = (b - 0.5f) / 0.5f
            input[plane + i] = (g - 0.5f) / 0.5f
            input[2 * plane + i] = (r - 0.5f) / 0.5f
        }

        val inputName = sess.inputNames.first()
        val sb = StringBuilder()
        var confSum = 0f
        var confCount = 0
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1L, 3L, targetH.toLong(), targetW.toLong())).use { tensor ->
            sess.run(mapOf(inputName to tensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val logits = (result[0].value as Array<Array<FloatArray>>)[0] // [T, C]
                var prevIdx = 0
                for (step in logits) {
                    var bestIdx = 0
                    var bestProb = step[0]
                    for (c in 1 until step.size) {
                        if (step[c] > bestProb) {
                            bestProb = step[c]
                            bestIdx = c
                        }
                    }
                    if (bestIdx != 0 && bestIdx != prevIdx && bestIdx < charDict.size) {
                        sb.append(charDict[bestIdx])
                        confSum += bestProb
                        confCount++
                    }
                    prevIdx = bestIdx
                }
            }
        }
        val text = sb.toString().trim()
        val avgConf = if (confCount > 0) confSum / confCount else 0f
        return OcrSlotText(text = text, confidence = avgConf)
    }

    private fun decodeBitmapBounded(uri: Uri): Bitmap? {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openUriStream(uri)?.use { BitmapFactory.decodeStream(it, null, boundsOpts) }
        val maxDim = max(boundsOpts.outWidth, boundsOpts.outHeight)
        if (maxDim <= 0) return null
        var sample = 1
        while (maxDim / sample > 2048) sample *= 2
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return openUriStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOpts) }
    }

    private fun openUriStream(uri: Uri) = when (uri.scheme) {
        "file" -> uri.path?.let { File(it).inputStream() }
        else -> context.contentResolver.openInputStream(uri)
    }

    override fun close() {
        runCatching { detSession?.close() }
        runCatching { recSession?.close() }
        detSession = null
        recSession = null
    }

    companion object {
        private val SWITCH_CONFIRM_SLOTS: Map<String, FloatArray> = linkedMapOf(
            "name" to floatArrayOf(0.080f, 0.160f, 0.215f, 0.205f),
            "lv" to floatArrayOf(0.114f, 0.216f, 0.155f, 0.249f),
            "hp" to floatArrayOf(0.114f, 0.264f, 0.155f, 0.299f),
            "prof" to floatArrayOf(0.106f, 0.402f, 0.220f, 0.438f),
            "sum8" to floatArrayOf(0.525f, 0.165f, 0.575f, 0.200f),
            "STR" to floatArrayOf(0.395f, 0.216f, 0.435f, 0.249f),
            "MAG" to floatArrayOf(0.395f, 0.264f, 0.435f, 0.299f),
            "SPD" to floatArrayOf(0.395f, 0.314f, 0.435f, 0.348f),
            "DEX" to floatArrayOf(0.395f, 0.364f, 0.435f, 0.398f),
            "DEF" to floatArrayOf(0.532f, 0.216f, 0.572f, 0.249f),
            "RES" to floatArrayOf(0.532f, 0.264f, 0.572f, 0.299f),
            "LCK" to floatArrayOf(0.532f, 0.314f, 0.572f, 0.348f),
            "CHM" to floatArrayOf(0.532f, 0.364f, 0.572f, 0.398f),
        )
    }
}
