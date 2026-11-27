package com.fireemblem.companion.personal

import com.fireemblem.companion.importer.FefwGrowthSeedSourceArchive
import java.util.UUID
import javax.crypto.AEADBadTagException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalSnapshotTest {
    private val device = UUID.randomUUID().toString()
    private val entity = UUID.randomUUID().toString()
    private fun payload(text: String) = buildJsonObject { put("note", text) }

    @Test
    fun offlineBranchesAndDeleteConflictsSurviveRepeatedMergeAndResolveExplicitly() {
        val base = PersonalSnapshot(emptyList()).edit(entity, "note", device, payload("原文"))
        val left = base.edit(entity, "note", device, payload("手机修改"))
        val right = base.edit(entity, "note", UUID.randomUUID().toString(), payload("原文"), deleted = true)
        val merged = left.merge(right)
        assertEquals(2, merged.heads().getValue(entity).size)
        assertEquals(merged, merged.merge(base).merge(right).merge(left))
        val resolved = merged.edit(entity, "note", device, payload("保留手机修改"))
        assertEquals(1, resolved.heads().getValue(entity).size)
        assertFalse(resolved.heads().getValue(entity).single().deleted)
        assertEquals(resolved, PersonalSnapshot.decode(resolved.encode()))
        assertEquals(1, resolved.merge(right).heads().getValue(entity).size)
    }

    @Test
    fun staleEditsAndMalformedHistoriesAreRejected() {
        val base = PersonalSnapshot(emptyList()).edit(entity, "note", device, payload("原文"))
        assertThrows(IllegalArgumentException::class.java) { base.edit(entity, "note", device, payload("覆盖"), expectedParents = emptySet()) }
        val revision = base.revisions.single()
        assertThrows(IllegalArgumentException::class.java) { PersonalSnapshot(listOf(revision.copy(parents = listOf(revision.revisionId)))).validated() }
        assertThrows(IllegalArgumentException::class.java) { base.merge(PersonalSnapshot(listOf(revision.copy(payload = payload("篡改"))))) }
        assertThrows(IllegalArgumentException::class.java) { PersonalSnapshot(listOf(revision.copy(parents = listOf(UUID.randomUUID().toString())))).validated() }
    }

    @Test
    fun previousVersionCanBeRestoredAsANewRevisionAndRejectsStaleHeads() {
        val original = PersonalSnapshot(emptyList()).edit(entity, "note", device, payload("原文"))
        val firstHead = original.heads().getValue(entity).single()
        val changed = original.edit(entity, "note", device, payload("修改后"))
        val changedHead = changed.heads().getValue(entity).single()

        assertEquals(firstHead, changed.previousVersion(entity, changedHead.revisionId))
        assertEquals(null, changed.previousVersion(entity, firstHead.revisionId))

        val restored = changed.edit(entity, "note", device,
            requireNotNull(changed.previousVersion(entity, changedHead.revisionId)).payload,
            expectedParents = setOf(changedHead.revisionId))
        val restoredHead = restored.heads().getValue(entity).single()
        assertEquals("\"原文\"", restoredHead.payload.getValue("note").toString())
        assertEquals(setOf(changedHead.revisionId), restoredHead.parents.toSet())
        assertEquals(restored, PersonalSnapshot.decode(restored.encode()))
    }

    @Test
    fun authenticatedEncryptionRejectsWrongPasswordAndTampering() {
        val snapshot = PersonalSnapshot(emptyList()).edit(entity, "note", device, payload("仅自己可见"))
        val password = "a-long-separate-password".toCharArray()
        val first = EncryptedBackup.encrypt(snapshot, password)
        val second = EncryptedBackup.encrypt(snapshot, password)
        assertFalse(first.contentEquals(second))
        assertEquals(snapshot, EncryptedBackup.decrypt(first, password))
        assertThrows(AEADBadTagException::class.java) { EncryptedBackup.decrypt(first, "incorrect-password".toCharArray()) }
        first[first.lastIndex] = (first.last().toInt() xor 1).toByte()
        assertThrows(AEADBadTagException::class.java) { EncryptedBackup.decrypt(first, password) }
        assertTrue(snapshot.revisions.single().payload.toString().contains("仅自己可见"))
    }

    @Test
    fun sourceArchiveIsDeduplicatedByContentAndSurvivesEncryptedBackup() {
        val original = "{\"format\":\"fefw-growth-seed\",\"version\":1,\"records\":[]}\n".toByteArray()
        val archive = FefwGrowthSeedSourceArchive.fromBytes(original)
        val revision = archive.toRevision()
        assertEquals(revision, FefwGrowthSeedSourceArchive.fromBytes(original).toRevision())

        val snapshot = PersonalSnapshot(emptyList()).merge(PersonalSnapshot(listOf(revision)))
        val merged = snapshot.merge(PersonalSnapshot(listOf(archive.toRevision())))
        assertEquals(1, merged.revisions.size)
        val password = "source-archive-test-passphrase".toCharArray()
        val restored = EncryptedBackup.decrypt(EncryptedBackup.encrypt(merged, password), password)
        assertArrayEquals(original, requireNotNull(FefwGrowthSeedSourceArchive.find(restored, archive.sourceSha256)).restoreBytes())

        val tampered = JsonObject(archive.toPayload() + ("dataBase64" to JsonPrimitive(archive.dataBase64.dropLast(4) + "AAAA")))
        assertThrows(IllegalArgumentException::class.java) { FefwGrowthSeedSourceArchive.fromPayload(tampered) }
    }
}
