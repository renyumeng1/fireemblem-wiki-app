package com.fireemblem.companion.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportGuideTest {
    @Test
    fun projectsJobAndCombinationBonusesWithPerStatLimits() {
        val job = SupportJob(
            sourceId = "JID_test",
            wikiEntryId = "professions:test",
            name = "测试职业",
            displayName = "测试职业",
            supportTypeId = "攻勢",
            supportTypeLabel = "攻势",
            tiers = listOf("初级"),
            imageAsset = "assets/job.webp",
            matchStatus = SupportWikiMatchStatus.MATCHED,
        )
        val type = SupportTypeBonus(
            id = "攻勢",
            label = "攻势",
            levels = listOf(
                SupportTypeLevelBonus(3, SupportBonusVector(listOf(20, -20, 10, -10, 0, 0, 0, 0, 0))),
            ),
        )
        val combination = SupportBonusVector(listOf(90, -90, 100, -100, 0, 0, 0, 0, 0))

        val result = SupportBonusProjector.project(combination, job, 3, listOf(type))

        assertEquals(listOf(20, -20, 10, -10, 0, 0, 0, 0, 0), result?.jobBonus?.values)
        assertEquals(combination, result?.combinationBonus)
        assertEquals(listOf(100, -100, 100, -100, 0, 0, 0, 0, 0), result?.totalBonus?.values)
    }

    @Test
    fun doesNotClaimATotalWithoutASelectedOrSupportedJob() {
        val combination = SupportBonusVector(List(9) { 1 })
        val type = SupportTypeBonus("連携", "连携", listOf(
            SupportTypeLevelBonus(1, SupportBonusVector(List(9) { 0 })),
        ))

        assertNull(SupportBonusProjector.project(combination, null, 1, listOf(type)))
        val unknownType = SupportJob(
            "JID_unknown", null, "未匹配职业", "未匹配职业", "未知", "未知", emptyList(), null,
            SupportWikiMatchStatus.MISSING,
        )
        assertNull(SupportBonusProjector.project(combination, unknownType, 1, listOf(type)))
        assertThrows(IllegalArgumentException::class.java) {
            SupportBonusProjector.project(combination, unknownType, 0, listOf(type))
        }
    }

    @Test
    fun mapsDialogueRanksToTheirMinimumSupportLevels() {
        assertEquals(2, SupportDialogueRank.fromSourceRank(1)?.minimumSupportLevel)
        assertEquals(3, SupportDialogueRank.fromSourceRank(2)?.minimumSupportLevel)
        assertEquals(4, SupportDialogueRank.fromSourceRank(3)?.minimumSupportLevel)
        assertEquals(5, SupportDialogueRank.fromSourceRank(4)?.minimumSupportLevel)
        assertNull(SupportDialogueRank.fromSourceRank(0))
    }

    @Test
    fun validatesSearchAndBonusShapesAndPagination() {
        assertThrows(IllegalArgumentException::class.java) { SupportPairRequest(offset = -1) }
        assertThrows(IllegalArgumentException::class.java) { SupportPairRequest(limit = 0) }
        assertThrows(IllegalArgumentException::class.java) { SupportPairRequest(limit = 101) }
        assertThrows(IllegalArgumentException::class.java) { SupportPairRequest(characterName = " ") }
        assertThrows(IllegalArgumentException::class.java) { SupportBonusVector(List(8) { 0 }) }
        assertThrows(IllegalArgumentException::class.java) { SupportBonusVector(List(9) { 101 }) }
        val page = SupportPairPage(emptyList(), 1, 0, 1)
        assertFalse(page.hasPrevious)
        assertTrue(page.hasNext)
    }
}
