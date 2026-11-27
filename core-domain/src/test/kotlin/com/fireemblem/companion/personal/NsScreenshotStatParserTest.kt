package com.fireemblem.companion.personal

import com.fireemblem.companion.engine.BuddyCampaignLine
import com.fireemblem.companion.engine.GrowthStat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NsScreenshotStatParserTest {
    private val sampleDictionary = OcrWikiDictionary(
        characters = listOf(
            OcrCharacterRef("characters:alexandra", "亚历珊德拉"),
            OcrCharacterRef("characters:dante", "丹提"),
            OcrCharacterRef("characters:ninne", "妮涅"),
            OcrCharacterRef("characters:lilian", "莉利安"),
            OcrCharacterRef("characters:mu", "穆", exclusiveRoute = BuddyCampaignLine.KAI),
        ),
        professions = listOf("勇士", "先知", "巨型重甲兵", "游侠", "天翼兵", "战车兵", "侍道", "卫士"),
    )

    @Test
    fun parsesSwitchTemplateSlotsWithChecksumVerificationAndCrestRoute() {
        // Real Switch screenshot golden fixture: 0AB9D0BA (亚历珊德拉 · 赛奥朵拉篇)
        val image = OcrRecognizedImage(
            sourceUri = "content://media/0AB9D0BA.jpg",
            detectedCrestLine = BuddyCampaignLine.SEO,
            crestReason = "画面路线徽章识别：赛奥朵拉篇",
            templateSlots = mapOf(
                "name" to OcrSlotText("亚历珊德拉", 0.99f),
                "lv" to OcrSlotText("39", 0.99f),
                "hp" to OcrSlotText("44", 0.98f),
                "prof" to OcrSlotText("勇士", 0.97f),
                "sum8" to OcrSlotText("193", 0.99f),
                "STR" to OcrSlotText("23", 0.99f),
                "MAG" to OcrSlotText("20", 0.99f),
                "SPD" to OcrSlotText("35", 0.99f),
                "DEX" to OcrSlotText("20", 0.99f),
                "DEF" to OcrSlotText("13", 0.99f),
                "RES" to OcrSlotText("22", 0.99f),
                "LCK" to OcrSlotText("27", 0.99f),
                "CHM" to OcrSlotText("33", 0.99f),
            ),
        )

        val draft = NsScreenshotStatParser.parse(image, sampleDictionary)
        assertEquals("characters:alexandra", draft.characterId)
        assertEquals("亚历珊德拉", draft.characterName)
        assertEquals(BuddyCampaignLine.SEO, draft.detectedLine)
        assertEquals(39, draft.level)
        assertEquals("勇士", draft.profession)
        // GrowthStat order: HP, STR, MAG, DEX, SPD, LCK, DEF, RES, CHM
        assertEquals(listOf(44, 23, 20, 20, 35, 27, 13, 22, 33), draft.stats)
        assertEquals(193, draft.checksumDeclared)
        assertEquals(193, draft.checksumComputed)
        assertEquals(true, draft.checksumMatched)
        assertTrue(draft.isHighConfidenceReady)
    }

    @Test
    fun spatialFallbackPrefersRightNumberBoxOverTruncatedTrailingDigitInLabel() {
        // Real PP-OCRv4 DBNet edge case seen on 0AB9D0BA: ("魔力2", 0.364..0.408) adjacent to ("20", 0.401..0.423)
        val boxes = listOf(
            OcrTextBox("亞歷珊德拉", 0.087f, 0.170f, 0.164f, 0.196f, 0.98f), // Traditional Chinese normalization
            OcrTextBox("综合能力193", 0.482f, 0.172f, 0.559f, 0.196f, 0.99f),
            OcrTextBox("LV", 0.086f, 0.222f, 0.102f, 0.244f, 0.99f),
            OcrTextBox("39", 0.122f, 0.220f, 0.143f, 0.244f, 0.99f),
            OcrTextBox("HP", 0.086f, 0.271f, 0.102f, 0.294f, 0.99f),
            OcrTextBox("44", 0.122f, 0.271f, 0.143f, 0.294f, 0.99f),
            OcrTextBox("力量23", 0.365f, 0.220f, 0.423f, 0.244f, 0.99f),
            OcrTextBox("魔力2", 0.364f, 0.268f, 0.408f, 0.294f, 0.98f),
            OcrTextBox("20", 0.401f, 0.269f, 0.423f, 0.294f, 0.99f),
            OcrTextBox("速度", 0.366f, 0.319f, 0.402f, 0.344f, 0.99f),
            OcrTextBox("35", 0.401f, 0.319f, 0.423f, 0.344f, 0.99f),
            OcrTextBox("技巧", 0.365f, 0.371f, 0.396f, 0.394f, 0.99f),
            OcrTextBox("20", 0.401f, 0.369f, 0.423f, 0.393f, 0.99f),
            OcrTextBox("防守", 0.500f, 0.215f, 0.531f, 0.246f, 0.98f),
            OcrTextBox("13", 0.540f, 0.220f, 0.559f, 0.244f, 0.99f),
            OcrTextBox("魔防", 0.502f, 0.269f, 0.530f, 0.294f, 0.99f),
            OcrTextBox("22", 0.540f, 0.269f, 0.559f, 0.294f, 0.99f),
            OcrTextBox("幸運", 0.504f, 0.321f, 0.530f, 0.342f, 0.99f),
            OcrTextBox("27", 0.539f, 0.319f, 0.558f, 0.342f, 0.99f),
            OcrTextBox("魅力", 0.498f, 0.368f, 0.532f, 0.393f, 0.99f),
            OcrTextBox("33", 0.539f, 0.371f, 0.559f, 0.393f, 0.99f),
            OcrTextBox("勇士", 0.112f, 0.409f, 0.148f, 0.431f, 0.99f),
        )
        val draft = NsScreenshotStatParser.parse(OcrRecognizedImage("content://spatial", boxes = boxes), sampleDictionary)
        assertEquals("characters:alexandra", draft.characterId)
        assertEquals(20, draft.stats[GrowthStat.MAGIC.ordinal]) // 20, NOT 2!
        assertEquals(true, draft.checksumMatched)
        assertNull(draft.detectedLine) // No crest or route text -> requires user choice
        assertFalse(draft.isHighConfidenceReady)
        assertTrue(draft.hasValidCoreFields)
    }

    @Test
    fun flagsChecksumMismatchAndInfersExclusiveRouteAndBatchDuplicates() {
        val mismatchImage = OcrRecognizedImage(
            sourceUri = "content://mu.jpg",
            templateSlots = mapOf(
                "name" to OcrSlotText("穆", 0.99f),
                "lv" to OcrSlotText("41", 0.99f),
                "hp" to OcrSlotText("46", 0.99f),
                "prof" to OcrSlotText("战车兵", 0.95f),
                "sum8" to OcrSlotText("178", 0.99f),
                "STR" to OcrSlotText("31", 0.99f),
                "MAG" to OcrSlotText("10", 0.99f),
                "SPD" to OcrSlotText("23", 0.99f),
                "DEX" to OcrSlotText("32", 0.99f),
                "DEF" to OcrSlotText("26", 0.99f),
                "RES" to OcrSlotText("17", 0.99f),
                "LCK" to OcrSlotText("16", 0.99f),
                "CHM" to OcrSlotText("28", 0.99f), // typo: 28 instead of 23 -> sum 183 != 178
            ),
        )
        val draft = NsScreenshotStatParser.parse(mismatchImage, sampleDictionary)
        assertEquals(BuddyCampaignLine.KAI, draft.detectedLine) // exclusiveRoute inference!
        assertEquals(false, draft.checksumMatched)
        assertFalse(draft.isHighConfidenceReady)
        assertTrue(draft.warnings.any { it.contains("综合能力") })

        val dupes = NsScreenshotStatParser.findBatchDuplicates(
            listOf(
                "characters:ninne" to BuddyCampaignLine.KAI,
                "characters:ninne" to BuddyCampaignLine.SEO,
                "characters:ninne" to BuddyCampaignLine.KAI,
                null to BuddyCampaignLine.KAI,
            ),
        )
        assertEquals(mapOf(2 to 0), dupes)
    }
}
