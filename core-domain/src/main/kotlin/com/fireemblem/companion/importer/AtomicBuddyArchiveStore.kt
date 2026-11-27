package com.fireemblem.companion.importer

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

data class BuddyArchiveSummary(
    val archiveId: String,
    val sourceVersion: Int,
    val sourceSha256: String,
    val importedAt: Instant,
    val characterCount: Int,
    val recordCount: Int,
    val seedCount: Int,
)

sealed interface ArchiveSaveResult {
    data class Saved(val summary: BuddyArchiveSummary) : ArchiveSaveResult
    data class Duplicate(val existing: BuddyArchiveSummary) : ArchiveSaveResult
}

class AtomicBuddyArchiveStore(
    private val root: Path,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun list(): List<BuddyArchiveSummary> {
        if (!Files.exists(root)) return emptyList()
        return Files.list(root).use { paths ->
            paths.iterator().asSequence()
                .filter { Files.isDirectory(it) && it.fileName.toString().matches(UUID_PATTERN) }
                .map(::readManifest)
                .sortedWith(compareByDescending<BuddyArchiveSummary> { it.importedAt }.thenBy { it.archiveId })
                .toList()
        }
    }

    @Throws(IOException::class)
    fun save(preview: BuddyBackupPreview, allowDuplicateCopy: Boolean = false): ArchiveSaveResult {
        Files.createDirectories(root)
        val existing = list().firstOrNull { it.sourceSha256 == preview.sourceSha256 }
        if (existing != null && !allowDuplicateCopy) return ArchiveSaveResult.Duplicate(existing)

        val archiveId = UUID.randomUUID().toString()
        val importedAt = clock.instant()
        val summary = BuddyArchiveSummary(
            archiveId = archiveId,
            sourceVersion = preview.sourceVersion,
            sourceSha256 = preview.sourceSha256,
            importedAt = importedAt,
            characterCount = preview.characterCount,
            recordCount = preview.recordCount,
            seedCount = preview.seedCount,
        )
        val staging = root.resolve(".stage-$archiveId")
        val destination = root.resolve(archiveId)
        try {
            Files.createDirectory(staging)
            writeSynced(staging.resolve(PAYLOAD_FILE), preview.canonicalJson.toByteArray(StandardCharsets.UTF_8))
            writeSynced(staging.resolve(MANIFEST_FILE), manifest(summary).toByteArray(StandardCharsets.UTF_8))
            Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE)
            return ArchiveSaveResult.Saved(summary)
        } catch (error: Exception) {
            deleteTree(staging)
            if (error is IOException) throw error
            throw IOException("无法安全保存该存档", error)
        }
    }

    fun readPayload(archiveId: String): String {
        require(archiveId.matches(UUID_PATTERN)) { "存档编号不正确" }
        val directory = root.resolve(archiveId).normalize()
        require(directory.parent == root.normalize()) { "存档路径不正确" }
        return String(Files.readAllBytes(directory.resolve(PAYLOAD_FILE)), StandardCharsets.UTF_8)
    }

    private fun readManifest(directory: Path): BuddyArchiveSummary {
        val manifest = try {
            Json.parseToJsonElement(String(Files.readAllBytes(directory.resolve(MANIFEST_FILE)), StandardCharsets.UTF_8)).jsonObject
        } catch (error: Exception) {
            throw IOException("本机存档索引损坏：${directory.fileName}", error)
        }
        val archiveId = manifest.string("archiveId")
        require(archiveId == directory.fileName.toString() && archiveId.matches(UUID_PATTERN)) { "存档索引路径不匹配" }
        return BuddyArchiveSummary(
            archiveId = archiveId,
            sourceVersion = manifest.int("sourceVersion"),
            sourceSha256 = manifest.string("sourceSha256"),
            importedAt = Instant.parse(manifest.string("importedAt")),
            characterCount = manifest.int("characterCount"),
            recordCount = manifest.int("recordCount"),
            seedCount = manifest.int("seedCount"),
        )
    }

    private fun manifest(summary: BuddyArchiveSummary): String = buildJsonObject {
        put("archiveId", summary.archiveId)
        put("sourceVersion", summary.sourceVersion)
        put("sourceSha256", summary.sourceSha256)
        put("importedAt", summary.importedAt.toString())
        put("characterCount", summary.characterCount)
        put("recordCount", summary.recordCount)
        put("seedCount", summary.seedCount)
    }.toString()

    private fun writeSynced(path: Path, bytes: ByteArray) {
        FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
            var buffer = java.nio.ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }

    private fun deleteTree(path: Path) {
        if (!Files.exists(path) || path.parent != root) return
        Files.walk(path).use { entries ->
            entries.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private fun JsonObject.string(key: String): String =
        this[key]?.let { (it as? JsonPrimitive)?.content } ?: throw IOException("存档索引缺少 $key")

    private fun JsonObject.int(key: String): Int = string(key).toIntOrNull()
        ?: throw IOException("存档索引字段 $key 不正确")

    companion object {
        private const val PAYLOAD_FILE = "state.json"
        private const val MANIFEST_FILE = "manifest.json"
        private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")
    }
}
