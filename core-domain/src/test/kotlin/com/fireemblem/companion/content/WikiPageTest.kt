package com.fireemblem.companion.content

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WikiPageTest {
    @Test
    fun validatesPageBounds() {
        assertThrows(IllegalArgumentException::class.java) { WikiPageRequest(offset = -1) }
        assertThrows(IllegalArgumentException::class.java) { WikiPageRequest(limit = 0) }
        assertThrows(IllegalArgumentException::class.java) { WikiPageRequest(limit = 101) }
        WikiPageRequest(offset = 1000, limit = 100)
    }

    @Test
    fun knowsWhetherOtherPagesExistWithoutTruncatingTheCatalog() {
        val entry = WikiEntry("entry", "测试", "", null, emptyList(), "来源", 4)
        val first = WikiEntryPage(List(40) { entry }, 101, 0, 40)
        val last = WikiEntryPage(listOf(entry), 101, 100, 40)
        assertFalse(first.hasPrevious)
        assertTrue(first.hasNext)
        assertTrue(last.hasPrevious)
        assertFalse(last.hasNext)
    }

    @Test
    fun validatesBirdDialogueQueryBoundsAndTracksPagination() {
        assertThrows(IllegalArgumentException::class.java) { BirdDialogueRequest(offset = -1) }
        assertThrows(IllegalArgumentException::class.java) { BirdDialogueRequest(limit = 0) }
        assertThrows(IllegalArgumentException::class.java) { BirdDialogueRequest(limit = 101) }
        assertThrows(IllegalArgumentException::class.java) { BirdDialogueRequest(characterGroup = " ") }
        val first = BirdDialoguePage(emptyList(), 41, 0, 40, "source note")
        assertTrue(first.hasNext)
        assertFalse(first.hasPrevious)
        val last = BirdDialoguePage(listOf(
            BirdDialogueRecord(
                entryId = "entry",
                characterEntryId = null,
                characterName = "测试角色",
                characterNameEnglish = null,
                characterImageAsset = null,
                characterGroup = null,
                originalPrompt = null,
                translatedPrompt = "测试对话",
                originalReaction = null,
                recommendedReaction = "点头",
                markedCorrected = false,
                sourceSheetId = "source",
                sourceSheetName = "来源",
                sourceRowNumber = 4,
            ),
        ), 41, 40, 40, "source note")
        assertTrue(last.hasPrevious)
        assertFalse(last.hasNext)
    }
}
