package com.fireemblem.companion.content

import com.fireemblem.companion.personal.PersonalSnapshot
import com.fireemblem.companion.personal.EncryptedBackup
import com.fireemblem.companion.personal.RestorationProgress
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RestorationGuideTest {
    private val materialId = "restoration-material:herb"
    private val firstId = "restoration-location:first"
    private val secondId = "restoration-location:second"
    private val deviceId = "11111111-1111-1111-1111-111111111111"
    private val guide = RestorationGuideData(
        listOf(RestorationMaterial(materialId, "材料", null, null, "missing")),
        listOf(
            RestorationLocation(firstId, "东站", "州", 3, "材料×5", listOf(RestorationRequirement(materialId, 5))),
            RestorationLocation(secondId, "西站", "州", 4, "材料×5", listOf(RestorationRequirement(materialId, 5))),
        ), "test", "测试", "1", "", "",
    )

    @Test fun aggregatesSharedStockOnceAndClampsExcess() {
        assertEquals(10L, RestorationPlanner.summarize(guide, emptyMap(), emptySet()).single().missing)
        assertEquals(3L, RestorationPlanner.summarize(guide, mapOf(materialId to 7), emptySet()).single().missing)
        assertEquals(0L, RestorationPlanner.summarize(guide, mapOf(materialId to 20), emptySet()).single().missing)
    }

    @Test fun completionExcludesDemandWithoutMutatingStockAndCanBeReverted() {
        val inventory = mapOf(materialId to 3)
        assertEquals(2L, RestorationPlanner.summarize(guide, inventory, setOf(firstId)).single().missing)
        assertEquals(0L, RestorationPlanner.summarize(guide, inventory, setOf(firstId, secondId)).single().missing)
        assertEquals(7L, RestorationPlanner.summarize(guide, inventory, emptySet()).single().missing)
        assertEquals(3, inventory[materialId])
    }

    @Test fun personalProgressRoundTripsAndIsolatesProfiles() {
        val progress = RestorationProgress("first", mapOf(materialId to 7), setOf(firstId))
        assertEquals(progress, RestorationProgress.decode(progress.payload()))
        assertNotEquals(RestorationProgress.entityId("first"), RestorationProgress.entityId("second"))
        val snapshot = PersonalSnapshot(emptyList()).edit(RestorationProgress.entityId("first"), "progress", deviceId, progress.payload())
        val restored = PersonalSnapshot.decode(snapshot.encode())
        assertEquals(progress, RestorationProgress.read(restored, "first"))
        assertEquals(RestorationProgress("second"), RestorationProgress.read(restored, "second"))
        val password = "restoration-test-passphrase".toCharArray()
        val decrypted = EncryptedBackup.decrypt(EncryptedBackup.encrypt(snapshot, password), password)
        assertEquals(progress, RestorationProgress.read(decrypted, "first"))
        password.fill('\u0000')
    }

    @Test fun missingPersistentReferencesDoNotSilentlyDisappearFromTotals() {
        assertThrows(IllegalArgumentException::class.java) {
            RestorationPlanner.summarize(guide, mapOf("restoration-material:removed" to 5), emptySet())
        }
        assertThrows(IllegalArgumentException::class.java) {
            RestorationPlanner.summarize(guide, emptyMap(), setOf("restoration-location:removed"))
        }
    }

    @Test fun rejectsNegativeStockUnknownVersionsAndConflicts() {
        assertThrows(IllegalArgumentException::class.java) { RestorationProgress("first", mapOf(materialId to -1)) }
        val progress = RestorationProgress("first")
        val malformed = buildJsonObject { progress.payload().forEach { (key, value) -> put(key, value) }; put("version", JsonPrimitive(2)) }
        assertThrows(IllegalArgumentException::class.java) { RestorationProgress.decode(malformed) }
        val base = PersonalSnapshot(emptyList())
        val entityId = RestorationProgress.entityId("first")
        val left = base.edit(entityId, "progress", deviceId, progress.payload())
        val right = base.edit(entityId, "progress", deviceId, progress.copy(inventory = mapOf(materialId to 5)).payload())
        assertThrows(IllegalArgumentException::class.java) { RestorationProgress.read(left.merge(right), "first") }
        assertThrows(IllegalArgumentException::class.java) {
            left.edit(entityId, "progress", deviceId, progress.payload(), expectedParents = emptySet())
        }
    }
}
