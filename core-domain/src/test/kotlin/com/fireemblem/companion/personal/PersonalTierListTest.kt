package com.fireemblem.companion.personal

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalTierListTest {
    private val deviceA = UUID.randomUUID().toString()
    private val deviceB = UUID.randomUUID().toString()
    private val kai = "characters:kai"
    private val dietrich = "characters:dietrich"
    private val leda = "characters:leda"

    @Test
    fun assignReorderAndTierManagementPreserveIntegrity() {
        var list = PersonalTierList(profileId = "global", name = "我的常用主力")
        list = list.assign(kai, "tier-s", "机动强")
            .assign(dietrich, "tier-s", "输出稳")
            .assign(leda, "tier-a", "辅助好用")

        assertEquals(listOf(kai, dietrich), list.entriesForTier("tier-s").map { it.characterId })
        list = list.moveWithinTier(dietrich, -1)
        assertEquals(listOf(dietrich, kai), list.entriesForTier("tier-s").map { it.characterId })
        assertEquals("输出稳", list.entryFor(dietrich)?.note)

        // Updating note within the same tier keeps rank order
        list = list.assign(dietrich, "tier-s", "第一顺位")
        assertEquals(listOf(dietrich, kai), list.entriesForTier("tier-s").map { it.characterId })
        assertEquals("第一顺位", list.entryFor(dietrich)?.note)

        // Moving to another tier preserves note when null and appends
        list = list.assign(dietrich, "tier-a")
        assertEquals(listOf(leda, dietrich), list.entriesForTier("tier-a").map { it.characterId })
        assertEquals("第一顺位", list.entryFor(dietrich)?.note)

        // Custom tier add, rename, delete unassigns entries without throwing
        list = list.addTier("T0", "最优先培养")
        val customId = list.tiers.last().id
        list = list.renameTier(customId, "核心", "重点投入").assign(kai, customId, "必带")
        assertEquals("核心", list.tiers.last().label)
        list = list.removeTier(customId)
        assertNull(list.entryFor(kai))
        assertTrue(list.tiers.none { it.id == customId })
    }

    @Test
    fun rejectsDuplicatesCorruptPayloadsAndUnknownVersions() {
        assertThrows(IllegalArgumentException::class.java) {
            PersonalTierList(
                profileId = "global",
                name = "重复角色",
                entries = listOf(
                    PersonalTierEntry(kai, "tier-s"),
                    PersonalTierEntry(kai, "tier-a"),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PersonalTierList(
                profileId = "global",
                name = "无效档位",
                entries = listOf(PersonalTierEntry(kai, "tier-missing")),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PersonalTierList(
                profileId = "global",
                name = "无效角色前缀",
                entries = listOf(PersonalTierEntry("weapons:sword", "tier-s")),
            )
        }

        val valid = PersonalTierList("global", "标准榜").assign(kai, "tier-s", "主线核心")
        val payload = valid.payload()
        val futureVersion = kotlinx.serialization.json.JsonObject(
            payload + ("version" to kotlinx.serialization.json.JsonPrimitive(2)),
        )
        assertThrows(IllegalArgumentException::class.java) { PersonalTierList.decode(futureVersion) }
        val wrongType = kotlinx.serialization.json.JsonObject(
            payload + ("recordType" to kotlinx.serialization.json.JsonPrimitive("other")),
        )
        assertThrows(IllegalArgumentException::class.java) { PersonalTierList.decode(wrongType) }
    }

    @Test
    fun separatesProfilesDetectsConflictsAndRoundTripsBackupAndExport() {
        val globalId = PersonalTierList.defaultEntityId("global")
        val kaiProfileId = PersonalTierList.defaultEntityId("profile-kai")
        val globalList = PersonalTierList("global", "通用档位").assign(kai, "tier-s", "通用评价")
        val kaiList = PersonalTierList("profile-kai", "凯伊路线档位").assign(dietrich, "tier-a", "线内主力")

        val base = PersonalSnapshot(emptyList())
            .edit(globalId, PersonalTierList.KIND, deviceA, globalList.payload())
            .edit(kaiProfileId, PersonalTierList.KIND, deviceA, kaiList.payload())

        val globalCollection = PersonalTierList.readProfile(base, "global")
        assertEquals(listOf("通用档位"), globalCollection.lists.map { it.list.name })
        assertTrue(globalCollection.conflictEntityIds.isEmpty())

        val kaiCollection = PersonalTierList.readProfile(base, "profile-kai")
        assertEquals(listOf("凯伊路线档位"), kaiCollection.lists.map { it.list.name })

        // Encrypted backup round-trip
        val password = "tier-list-sync-password".toCharArray()
        val encrypted = EncryptedBackup.encrypt(base, password)
        val restored = EncryptedBackup.decrypt(encrypted, password)
        assertEquals(globalCollection, PersonalTierList.readProfile(restored, "global"))

        // Multi-device conflict detection
        val branchA = base.edit(globalId, PersonalTierList.KIND, deviceA, globalList.assign(leda, "tier-b").payload())
        val branchB = base.edit(globalId, PersonalTierList.KIND, deviceB, globalList.assign(dietrich, "tier-a").payload())
        val conflicted = branchA.merge(branchB)
        val conflictedCollection = PersonalTierList.readProfile(conflicted, "global")
        assertEquals(listOf(globalId), conflictedCollection.conflictEntityIds)
        assertTrue(conflictedCollection.lists.isEmpty())

        // JSON export/import into another profile
        val exported = globalList.encodeExport()
        val imported = PersonalTierList.decodeExport(exported, targetProfileId = "profile-leda")
        assertEquals("profile-leda", imported.profileId)
        assertEquals(globalList.name, imported.name)
        assertEquals(globalList.tiers, imported.tiers)
        assertEquals(globalList.entries, imported.entries)
        assertThrows(IllegalArgumentException::class.java) {
            PersonalTierList.decodeExport("{\"format\":\"wrong\",\"version\":1}".toByteArray(), "global")
        }
    }
}
