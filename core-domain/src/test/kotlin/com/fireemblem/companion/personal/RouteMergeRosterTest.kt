package com.fireemblem.companion.personal

import com.fireemblem.companion.engine.BuddyCampaignLine
import com.fireemblem.companion.engine.GrowthStat
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteMergeRosterTest {
    @Test fun roundTripsFourLineSnapshotsAndComputesMergeComparison() {
        val kaiSnap = RouteMergeSnapshot(
            line = BuddyCampaignLine.KAI,
            level = 18,
            profession = "枪骑士",
            stats = listOf(34, 19, 8, 17, 21, 14, 15, 11, 16),
            note = "凯伊线主力前排",
        )
        val ledaSnap = RouteMergeSnapshot(
            line = BuddyCampaignLine.LEDA,
            level = 20,
            profession = "圣骑士",
            stats = listOf(36, 17, 12, 20, 19, 14, 18, 13, 15),
            note = "蕾达线偏坦",
        )
        val record = RouteMergeCharacter(
            profileId = "global",
            characterId = "characters:test-hero",
            characterName = "测试角色",
        ).withSnapshot(kaiSnap).withSnapshot(ledaSnap)

        val decoded = RouteMergeCharacter.decode(record.payload())
        assertEquals(record, decoded)
        assertEquals(listOf(BuddyCampaignLine.KAI, BuddyCampaignLine.LEDA), decoded.recordedLines)

        val comparison = decoded.compare()
        assertEquals(20, comparison.highestLevel)
        assertEquals(setOf(BuddyCampaignLine.LEDA), comparison.levelLeaders)
        assertEquals(setOf(BuddyCampaignLine.KAI), comparison.statLeaders[GrowthStat.STRENGTH])
        assertEquals(setOf(BuddyCampaignLine.KAI), comparison.statLeaders[GrowthStat.SPEED])
        assertEquals(setOf(BuddyCampaignLine.LEDA), comparison.statLeaders[GrowthStat.HP])
        assertEquals(setOf(BuddyCampaignLine.KAI, BuddyCampaignLine.LEDA), comparison.statLeaders[GrowthStat.LUCK])
        assertEquals(listOf(36, 19, 12, 20, 21, 14, 18, 13, 16), comparison.mergedMaxStats)
        assertEquals(169, comparison.mergedMaxTotal)
        assertEquals(setOf(BuddyCampaignLine.LEDA), comparison.totalLeaders)
    }

    @Test fun storesAndReadsProfileIsolatedRecordsInPersonalSnapshot() {
        val deviceId = UUID.randomUUID().toString()
        val idA = RouteMergeCharacter.entityId("profile-a", "characters:c1")
        val idB = RouteMergeCharacter.entityId("profile-b", "characters:c1")
        val charA = RouteMergeCharacter(
            profileId = "profile-a",
            characterId = "characters:c1",
            characterName = "凯伊",
            snapshots = mapOf(
                BuddyCampaignLine.KAI to RouteMergeSnapshot(
                    line = BuddyCampaignLine.KAI,
                    level = 15,
                    profession = "平民",
                    stats = listOf(30, 14, 9, 13, 16, 12, 11, 8, 14),
                ),
            ),
        )
        val charB = charA.copy(profileId = "profile-b")

        var snap = PersonalSnapshot(emptyList())
        snap = snap.edit(idA, RouteMergeCharacter.KIND, deviceId, charA.payload())
        snap = snap.edit(idB, RouteMergeCharacter.KIND, deviceId, charB.payload())
        val redecoded = PersonalSnapshot.decode(snap.encode())

        val colA = RouteMergeCharacter.readProfile(redecoded, "profile-a")
        assertEquals(1, colA.records.size)
        assertEquals("凯伊", colA.records.single().character.characterName)
        assertTrue(colA.conflictEntityIds.isEmpty())
        assertTrue(colA.corruptedEntityIds.isEmpty())

        val removed = charA.withoutLine(BuddyCampaignLine.KAI)
        assertTrue(removed.recordedLines.isEmpty())
    }

    @Test fun computesSubsetComparisonDeltasAndPersistsBackwardCompatibleSelectedLines() {
        val kaiSnap = RouteMergeSnapshot(
            line = BuddyCampaignLine.KAI,
            level = 20,
            profession = "剑客",
            stats = listOf(34, 22, 8, 17, 21, 14, 15, 11, 16), // Str=22, Spd=21
        )
        val ditSnap = RouteMergeSnapshot(
            line = BuddyCampaignLine.DIT,
            level = 22,
            profession = "侍道",
            stats = listOf(38, 19, 10, 23, 18, 16, 13, 12, 15), // HP=38, Skill=23, Def=16
        )
        val ledaSnap = RouteMergeSnapshot(
            line = BuddyCampaignLine.LEDA,
            level = 19,
            profession = "主教",
            stats = listOf(32, 16, 20, 15, 17, 13, 21, 14, 18), // Mag=20, Res=21, Lck=14, Cha=18
        )
        val fullChar = RouteMergeCharacter(
            profileId = "global",
            characterId = "characters:multi",
            characterName = "多线角色",
        ).withSnapshot(kaiSnap).withSnapshot(ditSnap).withSnapshot(ledaSnap)

        // Legacy v1 payload without selectedLines decodes with selectedLines == null
        val legacyPayload = fullChar.payload()
        assertTrue("selectedLines" !in legacyPayload)
        val decodedLegacy = RouteMergeCharacter.decode(legacyPayload)
        assertEquals(null, decodedLegacy.selectedLines)

        // Save custom subset {KAI, LEDA} (excluding DIT)
        val customChar = fullChar.withSelectedLines(setOf(BuddyCampaignLine.KAI, BuddyCampaignLine.LEDA))
        val decodedCustom = RouteMergeCharacter.decode(customChar.payload())
        assertEquals(setOf(BuddyCampaignLine.KAI, BuddyCampaignLine.LEDA), decodedCustom.selectedLines)

        val subsetComp = decodedCustom.compare()
        // GrowthStat order: HP(0), STR(1), MAG(2), DEX(3), SPD(4), LCK(5), DEF(6), RES(7), CHM(8)
        assertEquals(listOf(38, 22, 20, 23, 21, 16, 21, 14, 18), subsetComp.fullMergedMaxStats)
        assertEquals(193, subsetComp.fullMergedMaxTotal)
        assertEquals(22, subsetComp.fullHighestLevel)

        // Selected {KAI, LEDA}: HP=34 (-4), Str=22 (0), Mag=20 (0), Dex=17 (-6), Spd=21 (0), Lck=14 (-2), Def=21 (0), Res=14 (0), Chm=18 (0)
        assertEquals(listOf(34, 22, 20, 17, 21, 14, 21, 14, 18), subsetComp.mergedMaxStats)
        assertEquals(181, subsetComp.mergedMaxTotal)
        assertEquals(-12, subsetComp.totalDelta)
        assertEquals(listOf(-4, 0, 0, -6, 0, -2, 0, 0, 0), subsetComp.statDeltas)
        assertEquals(listOf(GrowthStat.HP, GrowthStat.DEXTERITY, GrowthStat.LUCK), subsetComp.droppedStats)
        assertTrue(subsetComp.isSubsetMode)
        assertTrue(subsetComp.hasSelectedSnapshot)
        assertEquals(20, subsetComp.highestLevel)

        // Empty intersection (selecting SEO only when only KAI/DIT/LEDA recorded)
        val emptyComp = decodedCustom.compareForOverview(
            globalLines = setOf(BuddyCampaignLine.SEO),
            respectPerCharacterOverride = false,
        )
        assertEquals(false, emptyComp.hasSelectedSnapshot)
        assertEquals(0, emptyComp.mergedMaxTotal)
        assertEquals(-193, emptyComp.totalDelta)
        assertEquals(null, emptyComp.highestLevel)

        // Respecting per-character override uses {KAI, LEDA} even when global is {SEO}
        val respected = decodedCustom.compareForOverview(
            globalLines = setOf(BuddyCampaignLine.SEO),
            respectPerCharacterOverride = true,
        )
        assertTrue(respected.hasSelectedSnapshot)
        assertEquals(181, respected.mergedMaxTotal)

        // Removing lines sanitizes selectedLines cleanly
        val afterRemoveKai = decodedCustom.withoutLine(BuddyCampaignLine.KAI)
        assertEquals(setOf(BuddyCampaignLine.LEDA), afterRemoveKai.selectedLines)
        val afterRemoveLeda = afterRemoveKai.withoutLine(BuddyCampaignLine.LEDA)
        assertEquals(null, afterRemoveLeda.selectedLines)
    }

    @Test fun projectsAndSortsOverviewRowsByTotalLossAndStat() {
        val strongKaiOnly = RouteMergeCharacter(
            profileId = "global",
            characterId = "characters:a",
            characterName = "甲角色",
        ).withSnapshot(
            RouteMergeSnapshot(BuddyCampaignLine.KAI, 20, "勇士", listOf(40, 30, 5, 20, 20, 20, 10, 10, 10)),
        )
        val balancedTwoLine = RouteMergeCharacter(
            profileId = "global",
            characterId = "characters:b",
            characterName = "乙角色",
        ).withSnapshot(
            RouteMergeSnapshot(BuddyCampaignLine.KAI, 20, "剑客", listOf(30, 18, 12, 22, 18, 14, 14, 12, 14)),
        ).withSnapshot(
            RouteMergeSnapshot(BuddyCampaignLine.DIT, 21, "圣骑士", listOf(36, 25, 12, 22, 20, 18, 14, 12, 14)),
        )
        val records = listOf(
            RouteMergeStoredRecord("e1", "r1", strongKaiOnly),
            RouteMergeStoredRecord("e2", "r2", balancedTwoLine),
        )

        // Global only KAI -> 乙角色 loses DIT's HP+6, Str+7, Skl+2, Def+4 = -19; 甲角色 loses 0
        val byLossDesc = RouteMergeOverviewProjector.project(
            records = records,
            globalLines = setOf(BuddyCampaignLine.KAI),
            respectPerCharacterOverride = true,
            sort = RouteMergeOverviewSort.LossLargestFirst,
        )
        assertEquals("乙角色", byLossDesc[0].record.character.characterName)
        assertEquals(-19, byLossDesc[0].comparison.totalDelta)
        assertEquals("甲角色", byLossDesc[1].record.character.characterName)
        assertEquals(0, byLossDesc[1].comparison.totalDelta)

        val byStr = RouteMergeOverviewProjector.project(
            records = records,
            globalLines = setOf(BuddyCampaignLine.KAI, BuddyCampaignLine.DIT),
            respectPerCharacterOverride = true,
            sort = RouteMergeOverviewSort.StatDesc(GrowthStat.STRENGTH),
        )
        assertEquals("甲角色", byStr[0].record.character.characterName)
        assertEquals(30, byStr[0].comparison.mergedMaxStats[GrowthStat.STRENGTH.ordinal])

        val byFullTotal = RouteMergeOverviewProjector.project(
            records = records,
            globalLines = setOf(BuddyCampaignLine.KAI, BuddyCampaignLine.DIT),
            respectPerCharacterOverride = true,
            sort = RouteMergeOverviewSort.FullTotalDesc,
        )
        assertEquals("乙角色", byFullTotal[0].record.character.characterName)
        assertEquals(173, byFullTotal[0].comparison.fullMergedMaxTotal)
    }
}
