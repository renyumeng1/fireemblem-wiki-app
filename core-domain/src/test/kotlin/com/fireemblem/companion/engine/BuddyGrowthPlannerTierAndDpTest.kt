package com.fireemblem.companion.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuddyGrowthPlannerTierAndDpTest {
    @Test
    fun tierUnlockLevelsBlockPrematureMidTierPromotionBetweenLv10AndLv35() {
        val rules = BuddyRules.bundled()
        val unlocked = setOf("平民", "斗士", "土匪", "勇士")
        val currentLevel = 10
        val targetLevel = 35
        val windows = unlocked.map { prof ->
            val minLv = if (prof == "平民") currentLevel
            else maxOf(currentLevel, BuddyRules.defaultUnlockLevelForTier(rules.professionTier(prof)))
            BuddyProfessionWindow(prof, minLv, 99)
        }
        val request = BuddyGrowthPlanRequest(
            seed = 1_090_197_388L,
            characterId = "c006", // 皮特鲁
            line = BuddyCampaignLine.KAI,
            currentLevel = currentLevel,
            targetLevel = targetLevel,
            part3StartLevel = null,
            currentPart3 = false,
            initialProfession = "平民",
            unlockedProfessions = unlocked,
            professionWindows = windows,
            weights = listOf(1, 5, 0, 2, 3, 0, 1, 0, 0),
            currentStats = List(9) { 0 },
            caps = List(9) { null },
            maxSwitches = 98,
        )

        val started = System.nanoTime()
        val result = BuddyGrowthPlanner.plan(request, rules)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L

        assertEquals(BuddyGrowthPlanStatus.EXACT, result.status)
        assertTrue(result.optimal)
        assertEquals(25, result.steps.size)
        // CRITICAL: 土匪 (中级, tier 2) MUST NEVER appear before Lv.20!
        assertTrue(
            "Found premature 土匪 step: ${result.steps.filter { it.profession == "土匪" && it.level < 20 }}",
            result.steps.none { it.profession == "土匪" && it.level < 20 },
        )
        // CRITICAL: 勇士 (上级, tier 3) MUST NEVER appear before Lv.35!
        assertTrue(
            "Found premature 勇士 step: ${result.steps.filter { it.profession == "勇士" && it.level < 35 }}",
            result.steps.none { it.profession == "勇士" && it.level < 35 },
        )
        assertTrue("Exact DP for 25 levels should be fast (<2000ms), got ${elapsedMs}ms", elapsedMs < 2000L)
    }

    @Test
    fun exactDpMatchesCappedDfsReferenceOnShortWindow() {
        val rules = BuddyRules.bundled()
        val unlocked = setOf("平民", "斗士", "猎兵", "士兵")
        val windows = unlocked.map { BuddyProfessionWindow(it, 5, 99) }
        val baseRequest = BuddyGrowthPlanRequest(
            seed = 42L,
            characterId = "c044",
            line = BuddyCampaignLine.KAI,
            currentLevel = 5,
            targetLevel = 10,
            part3StartLevel = null,
            currentPart3 = false,
            initialProfession = "平民",
            unlockedProfessions = unlocked,
            professionWindows = windows,
            weights = listOf(1, 5, 0, 2, 3, 0, 1, 0, 0),
            currentStats = List(9) { 10 },
            caps = List(9) { null },
            maxSwitches = 2,
        )
        // DP path (caps all null)
        val dpResult = BuddyGrowthPlanner.plan(baseRequest, rules)
        // DFS path (high non-null cap 999 forces DFS without altering any gain!)
        val dfsResult = BuddyGrowthPlanner.plan(baseRequest.copy(caps = List(9) { 999 }), rules)

        assertEquals(BuddyGrowthPlanStatus.EXACT, dpResult.status)
        assertEquals(BuddyGrowthPlanStatus.EXACT, dfsResult.status)
        assertEquals(dfsResult.weightedScore, dpResult.weightedScore)
        assertEquals(dfsResult.steps.map { it.profession }, dpResult.steps.map { it.profession })
        assertEquals(dfsResult.terminalStats, dpResult.terminalStats)
    }
}
