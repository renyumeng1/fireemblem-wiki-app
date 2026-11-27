package com.fireemblem.companion.importer

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AtomicBuddyArchiveStoreTest {
    @Test
    fun savesAtomicallyListsAndDetectsDuplicateBackups() {
        val root = Files.createTempDirectory("buddy-archives")
        try {
            val store = AtomicBuddyArchiveStore(root, Clock.fixed(Instant.parse("2026-11-15T00:00:00Z"), ZoneOffset.UTC))
            val bytes = """{"version":2,"roster":[{"id":"c001","initialLevel":null,"records":[]}]}""".toByteArray(StandardCharsets.UTF_8)
            val preview = BuddyBackupInspector.inspect(bytes)

            val saved = store.save(preview) as ArchiveSaveResult.Saved
            val duplicate = store.save(preview)

            assertEquals(1, store.list().size)
            assertEquals(saved.summary, store.list().single())
            assertEquals(preview.canonicalJson, store.readPayload(saved.summary.archiveId))
            assertTrue(duplicate is ArchiveSaveResult.Duplicate)
            assertFalse(Files.list(root).use { paths -> paths.anyMatch { it.fileName.toString().startsWith(".stage-") } })
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
