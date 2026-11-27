package com.fireemblem.companion.campaign

import com.fireemblem.companion.content.WikiEntry
import com.fireemblem.companion.content.WikiField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EndingsGuideTest {
    @Test fun pairEndingPreservesNamesConditionsAndParagraphs() {
        val entry = WikiEntry(
            id = "endings:pair-one",
            title = "角色甲 × 角色乙",
            subtitle = "双人结局 · 后日谈 01",
            imageAsset = null,
            fields = listOf(
                WikiField("结局类型", "双人结局", null),
                WikiField("相关角色", "角色甲、角色乙", null),
                WikiField("角色称号", "剑士、歌者", null),
                WikiField("结局标签", "后日谈 01", null),
                WikiField("触发条件", "条件未确认", null),
                WikiField("后日谈正文", "第一段。\n\n第二段。", null),
            ),
            sourceSheetName = "fireemblem.top · 结局一览",
            sourceRowNumber = 1,
        )

        val ending = EndingGuideProjector.project(entry)!!

        assertEquals(EndingKind.PAIR, ending.kind)
        assertEquals(listOf(EndingCharacter("角色甲", "剑士"), EndingCharacter("角色乙", "歌者")), ending.characters)
        assertEquals("条件未确认", ending.condition)
        assertEquals(listOf("第一段。", "第二段。"), ending.paragraphs)
        assertTrue(ending.hasStory)
    }

    @Test fun singleEndingWithoutBodyRemainsAnExplicitUnavailableRecord() {
        val entry = WikiEntry(
            "endings:single-one", "角色甲", "单人结局 · 存活结局", null,
            listOf(
                WikiField("结局类型", "单人结局", null),
                WikiField("相关角色", "角色甲", null),
                WikiField("角色称号", "剑士", null),
                WikiField("结局标签", "存活结局", null),
                WikiField("触发条件", "存活", null),
                WikiField("后日谈正文", "", null),
            ),
            "fireemblem.top · 结局一览", 125,
        )

        val ending = EndingGuideProjector.project(entry)!!

        assertEquals(EndingKind.SINGLE, ending.kind)
        assertFalse(ending.hasStory)
        assertTrue(ending.paragraphs.isEmpty())
    }

    @Test fun projectorDoesNotGuessUnknownTypesOrCharacterCount() {
        val base = WikiEntry(
            "endings:unknown", "角色甲", "", null,
            listOf(WikiField("结局类型", "未知类型", null), WikiField("相关角色", "角色甲", null)),
            "来源", 1,
        )

        assertNull(EndingGuideProjector.project(base))
        assertNull(EndingGuideProjector.project(base.copy(fields = listOf(
            WikiField("结局类型", "双人结局", null),
            WikiField("相关角色", "角色甲", null),
        ))))
        assertNull(EndingGuideProjector.project(base.copy(id = "characters:other")))
    }
}
