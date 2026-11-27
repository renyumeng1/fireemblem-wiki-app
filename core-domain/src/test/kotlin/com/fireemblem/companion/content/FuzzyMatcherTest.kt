package com.fireemblem.companion.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzyMatcherTest {
    @Test
    fun ranksExactPrefixSubstringSubsequenceAndTypoInOrder() {
        val exact = FuzzyMatcher.score("皮特鲁", "皮特鲁")
        val subseq = FuzzyMatcher.score("皮鲁", "皮特鲁")
        val typo = FuzzyMatcher.score("皮特路", "皮特鲁")
        val unrelated = FuzzyMatcher.score("皮特鲁", "赛奥朵拉")

        assertTrue("Exact must outrank subsequence: $exact > $subseq", exact > subseq)
        assertTrue("Subsequence must match: $subseq > 0", subseq > 0)
        assertTrue("Single-char typo must match: $typo > 0", typo > 0)
        assertEquals(0, unrelated)
    }

    @Test
    fun supportsMultiTokenSubsequenceAndTypoForClasses() {
        assertTrue(FuzzyMatcher.matches("天马骑", "圣天马骑士"))
        assertTrue(FuzzyMatcher.matches("圣天吗", "圣天马骑士"))
        assertTrue(FuzzyMatcher.matches("凯伊 勇士", "凯伊", subtitle = "初始职业 平民", searchText = "凯伊 勇士 斗士"))
        assertFalse(FuzzyMatcher.matches("完全无关词", "圣天马骑士"))
    }

    @Test
    fun highlightsMatchedCharactersCleanly() {
        val direct = FuzzyMatcher.highlightIndices("天马", "圣天马骑士")
        assertEquals(setOf(1, 2), direct)

        val subseq = FuzzyMatcher.highlightIndices("皮鲁", "皮特鲁")
        assertEquals(setOf(0, 2), subseq)
    }

    @Test
    fun sqlSubsequencePatternEscapesWildcards() {
        assertEquals("%皮%鲁%", FuzzyMatcher.sqlSubsequenceLikePattern("皮鲁"))
        assertEquals("%1%0%0%\\%%", FuzzyMatcher.sqlSubsequenceLikePattern("100%"))
    }
}
