package com.fireemblem.companion.personal

import com.fireemblem.companion.importer.FefwGrowthSeedSourceArchive
import java.util.UUID
import java.util.concurrent.CancellationException
import javax.crypto.AEADBadTagException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalSyncTest {
    private val password = "independent-test-password".toCharArray()
    private fun snapshot(text: String) = PersonalSnapshot(emptyList()).edit(UUID.randomUUID().toString(), "note",
        UUID.randomUUID().toString(), buildJsonObject { put("note", text) })

    private class Remote(var data: RemotePersonalBackup) : PersonalRemote {
        var writes = 0
        var concurrent: RemotePersonalBackup? = null
        override fun read() = data
        override fun put(bytes: ByteArray, expectedEtag: String?): Boolean {
            writes++
            concurrent?.let { data = it; concurrent = null; return false }
            if (expectedEtag != data.etag) return false
            data = RemotePersonalBackup(bytes, "\"$writes\"")
            return true
        }
    }

    @Test
    fun conditionalRetryMergesTheConcurrentDeviceBeforePublishing() {
        val local = snapshot("手机")
        val other = snapshot("平板")
        val remote = Remote(RemotePersonalBackup(null, null))
        remote.concurrent = RemotePersonalBackup(EncryptedBackup.encrypt(other, password), "\"first\"")
        val merged = PersonalSync.synchronize(local, remote, password)
        assertEquals(2, remote.writes)
        assertEquals(2, merged.heads().size)
        assertEquals(merged, EncryptedBackup.decrypt(remote.data.bytes!!, password))
        assertEquals(merged, PersonalSync.synchronize(local, remote, password))
    }

    @Test
    fun originalGrowthSeedFileSurvivesEncryptedDeviceSync() {
        val original = "{ \"format\": \"fefw-growth-seed\", \"version\": 1, \"records\": [] }\r\n".toByteArray()
        val archive = FefwGrowthSeedSourceArchive.fromBytes(original)
        val local = PersonalSnapshot(emptyList()).merge(PersonalSnapshot(listOf(archive.toRevision())))
        val remote = Remote(RemotePersonalBackup(EncryptedBackup.encrypt(snapshot("平板记录"), password), "\"device\""))

        val merged = PersonalSync.synchronize(local, remote, password)
        val restored = EncryptedBackup.decrypt(requireNotNull(remote.data.bytes), password)
        val restoredArchive = requireNotNull(FefwGrowthSeedSourceArchive.find(restored, archive.sourceSha256))

        assertEquals(2, merged.heads().size)
        assertArrayEquals(original, restoredArchive.restoreBytes())
        assertEquals(merged, restored)
    }

    @Test
    fun wrongKeysMissingEtagsAndCancellationNeverUpload() {
        val remote = Remote(RemotePersonalBackup(EncryptedBackup.encrypt(snapshot("远端"), password), null))
        assertThrows(IllegalArgumentException::class.java) { PersonalSync.synchronize(snapshot("本地"), remote, password) }
        remote.data = remote.data.copy(etag = "\"valid\"")
        assertThrows(AEADBadTagException::class.java) { PersonalSync.synchronize(snapshot("本地"), remote, "incorrect-password".toCharArray()) }
        assertThrows(CancellationException::class.java) { PersonalSync.synchronize(snapshot("本地"), remote, password) { true } }
        assertEquals(0, remote.writes)
    }

    @Test
    fun repeatedConflictsRemainAnErrorInsteadOfBlindOverwrite() {
        var writes = 0
        val remote = object : PersonalRemote {
            override fun read() = RemotePersonalBackup(null, null)
            override fun put(bytes: ByteArray, expectedEtag: String?): Boolean { writes++; return false }
        }
        assertThrows(IllegalStateException::class.java) { PersonalSync.synchronize(snapshot("记录"), remote, password) }
        assertEquals(3, writes)
        assertTrue(writes > 0)
    }
}
