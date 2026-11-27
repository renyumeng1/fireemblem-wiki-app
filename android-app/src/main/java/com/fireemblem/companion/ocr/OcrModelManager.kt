package com.fireemblem.companion.ocr

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request

data class OcrModelFileSpec(
    val id: String,
    val fileName: String,
    val expectedBytes: Long,
    val expectedSha256: String,
    val urls: List<String>,
)

data class OcrDownloadProgress(
    val currentFileIndex: Int,
    val totalFiles: Int,
    val currentFileName: String,
    val bytesDownloaded: Long,
    val totalBytes: Long,
) {
    val fraction: Float
        get() = if (totalBytes <= 0L) 0f else (bytesDownloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
}

sealed interface OcrModelStatus {
    data object NotInstalled : OcrModelStatus
    data class Installed(
        val totalBytes: Long,
        val installedAtMillis: Long,
        val detFile: File,
        val recFile: File,
    ) : OcrModelStatus
}

class OcrModelManager(
    private val baseDir: File,
    private val specs: List<OcrModelFileSpec> = DEFAULT_SPECS,
    private val client: OkHttpClient = defaultHttpClient(),
) {
    constructor(context: Context) : this(
        baseDir = File(context.noBackupFilesDir, "ocr-models/ppocr-v4"),
    )

    val totalPackageBytes: Long
        get() = specs.sumOf { it.expectedBytes }

    fun status(): OcrModelStatus {
        val manifestFile = File(baseDir, MANIFEST_FILE)
        val detSpec = specs.firstOrNull { it.id == ID_DET } ?: return OcrModelStatus.NotInstalled
        val recSpec = specs.firstOrNull { it.id == ID_REC } ?: return OcrModelStatus.NotInstalled
        val detFile = File(baseDir, detSpec.fileName)
        val recFile = File(baseDir, recSpec.fileName)
        if (!manifestFile.isFile || !detFile.isFile || !recFile.isFile) return OcrModelStatus.NotInstalled
        if (detFile.length() != detSpec.expectedBytes || recFile.length() != recSpec.expectedBytes) {
            return OcrModelStatus.NotInstalled
        }
        val installedAt = runCatching {
            val obj = Json.parseToJsonElement(manifestFile.readText(Charsets.UTF_8)).jsonObject
            obj["installedAtMillis"]?.jsonPrimitive?.longOrNull ?: manifestFile.lastModified()
        }.getOrDefault(manifestFile.lastModified())
        return OcrModelStatus.Installed(
            totalBytes = detFile.length() + recFile.length(),
            installedAtMillis = installedAt,
            detFile = detFile,
            recFile = recFile,
        )
    }

    suspend fun download(
        onProgress: (OcrDownloadProgress) -> Unit = {},
    ): OcrModelStatus.Installed = withContext(Dispatchers.IO) {
        val tmpDir = File(baseDir, ".tmp")
        if (tmpDir.exists()) tmpDir.deleteRecursively()
        if (!tmpDir.mkdirs() && !tmpDir.isDirectory) {
            throw IOException("无法创建本地 OCR 模型缓存目录。")
        }

        val grandTotal = specs.sumOf { it.expectedBytes }
        var completedBytesBeforeCurrent = 0L
        val stagedFiles = mutableListOf<Pair<File, File>>()

        try {
            specs.forEachIndexed { idx, spec ->
                if (!currentCoroutineContext().isActive) throw CancellationException()
                val partFile = File(tmpDir, "${spec.fileName}.part")
                downloadSpecWithFallback(
                    spec = spec,
                    targetFile = partFile,
                ) { fileBytesRead ->
                    onProgress(
                        OcrDownloadProgress(
                            currentFileIndex = idx + 1,
                            totalFiles = specs.size,
                            currentFileName = spec.fileName,
                            bytesDownloaded = (completedBytesBeforeCurrent + fileBytesRead).coerceAtMost(grandTotal),
                            totalBytes = grandTotal,
                        ),
                    )
                }
                completedBytesBeforeCurrent += spec.expectedBytes
                stagedFiles += partFile to File(baseDir, spec.fileName)
            }

            if (!baseDir.exists()) baseDir.mkdirs()
            stagedFiles.forEach { (tmpFile, finalFile) ->
                atomicMoveOrReplace(tmpFile.toPath(), finalFile.toPath())
            }
            val now = System.currentTimeMillis()
            val manifestJson = buildJsonObject {
                put("packageId", PACKAGE_ID)
                put("version", 1)
                put("installedAtMillis", now)
                put("totalBytes", grandTotal)
            }.toString()
            val tmpManifest = File(tmpDir, MANIFEST_FILE)
            tmpManifest.writeText(manifestJson, Charsets.UTF_8)
            atomicMoveOrReplace(tmpManifest.toPath(), File(baseDir, MANIFEST_FILE).toPath())
            tmpDir.deleteRecursively()

            val verified = status() as? OcrModelStatus.Installed
                ?: throw IOException("模型文件写入校验失败，请重试。")
            verified
        } catch (cancelled: CancellationException) {
            tmpDir.deleteRecursively()
            throw cancelled
        } catch (error: Exception) {
            tmpDir.deleteRecursively()
            throw error
        }
    }

    fun deleteModels() {
        if (baseDir.exists()) {
            baseDir.deleteRecursively()
        }
    }

    private fun atomicMoveOrReplace(src: Path, dst: Path) {
        try {
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private suspend fun downloadSpecWithFallback(
        spec: OcrModelFileSpec,
        targetFile: File,
        onBytes: (Long) -> Unit,
    ) {
        var lastError: Exception? = null
        for (url in spec.urls) {
            if (!currentCoroutineContext().isActive) throw CancellationException()
            if (targetFile.exists()) targetFile.delete()
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "FireEmblemCompanion/1.0")
                    .build()
                val call = client.newCall(request)
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("服务器返回状态码 ${response.code}")
                    }
                    val body = response.body ?: throw IOException("模型响应为空")
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buf = ByteArray(16 * 1024)
                    var totalRead = 0L
                    var lastEmit = 0L
                    body.byteStream().use { input ->
                        FileOutputStream(targetFile).use { output ->
                            while (true) {
                                if (!currentCoroutineContext().isActive) {
                                    call.cancel()
                                    throw CancellationException()
                                }
                                val read = input.read(buf)
                                if (read < 0) break
                                output.write(buf, 0, read)
                                digest.update(buf, 0, read)
                                totalRead += read
                                if (totalRead - lastEmit >= 65_536L || totalRead == spec.expectedBytes) {
                                    lastEmit = totalRead
                                    onBytes(totalRead)
                                }
                            }
                            output.fd.sync()
                        }
                    }
                    if (totalRead != spec.expectedBytes) {
                        throw IOException("模型文件大小不匹配（实收 $totalRead 字节，期望 ${spec.expectedBytes} 字节）")
                    }
                    val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
                    if (!actualHash.equals(spec.expectedSha256, ignoreCase = true)) {
                        throw IOException("模型文件 SHA-256 校验未通过，已自动丢弃临时文件。")
                    }
                    onBytes(totalRead)
                    return
                }
            } catch (cancelled: CancellationException) {
                if (targetFile.exists()) targetFile.delete()
                throw cancelled
            } catch (e: Exception) {
                if (targetFile.exists()) targetFile.delete()
                lastError = e
            }
        }
        throw IOException("无法下载 ${spec.fileName}：${lastError?.message ?: "网络不可用"}", lastError)
    }

    companion object {
        const val PACKAGE_ID = "ppocr-v4-zh-mobile"
        const val ID_DET = "det"
        const val ID_REC = "rec"
        private const val MANIFEST_FILE = "manifest.json"

        val DEFAULT_SPECS: List<OcrModelFileSpec> = listOf(
            OcrModelFileSpec(
                id = ID_DET,
                fileName = "ch_PP-OCRv4_det_mobile.onnx",
                expectedBytes = 4_745_517L,
                expectedSha256 = "d2a7720d45a54257208b1e13e36a8479894cb74155a5efe29462512d42f49da9",
                urls = listOf(
                    "https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/master/onnx/PP-OCRv4/det/ch_PP-OCRv4_det_mobile.onnx",
                    "https://hf-mirror.com/RapidAI/RapidOCR/resolve/main/onnx/PP-OCRv4/det/ch_PP-OCRv4_det_mobile.onnx",
                    "https://huggingface.co/RapidAI/RapidOCR/resolve/main/onnx/PP-OCRv4/det/ch_PP-OCRv4_det_mobile.onnx",
                ),
            ),
            OcrModelFileSpec(
                id = ID_REC,
                fileName = "ch_PP-OCRv4_rec_mobile.onnx",
                expectedBytes = 10_857_958L,
                expectedSha256 = "48fc40f24f6d2a207a2b1091d3437eb3cc3eb6b676dc3ef9c37384005483683b",
                urls = listOf(
                    "https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/master/onnx/PP-OCRv4/rec/ch_PP-OCRv4_rec_mobile.onnx",
                    "https://hf-mirror.com/RapidAI/RapidOCR/resolve/main/onnx/PP-OCRv4/rec/ch_PP-OCRv4_rec_mobile.onnx",
                    "https://huggingface.co/RapidAI/RapidOCR/resolve/main/onnx/PP-OCRv4/rec/ch_PP-OCRv4_rec_mobile.onnx",
                ),
            ),
        )

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}
