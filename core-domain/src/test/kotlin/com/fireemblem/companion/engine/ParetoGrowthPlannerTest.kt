package com.fireemblem.companion.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParetoGrowthPlannerTest {
    private val rules = BuddyRules.bundled()

    @Test
    fun paretoFrontierIsStrictlyIncreasingAndMinimalSwitch() {
        val character = rules.characters.first { !it.part3Only }
        val legalClasses = rules.professions
            .filter { rules.classAllowed(character.id, it.name, part3 = false) }
            .map { it.name }
            .take(8)
        assertTrue("Expected at least 4 legal classes", legalClasses.size >= 4)

        val weights = List(GrowthStat.entries.size) { idx ->
            when (idx) {
                GrowthStat.STRENGTH.ordinal -> 5
                GrowthStat.SPEED.ordinal -> 3
                else -> 1
            }
        }

        val report = ParetoGrowthPlanner.solve(
            ParetoGrowthPlanRequest(
                seed = 123_456_789L,
                characterId = character.id,
                line = BuddyCampaignLine.KAI,
                currentLevel = 15,
                targetLevel = 30,
                initialProfession = legalClasses.first(),
                unlockedProfessions = legalClasses.toSet(),
                weights = weights,
                maxSwitchBudget = 6,
            ),
            rules,
        )

        assertTrue("At least baseline tier must exist", report.paretoTiers.isNotEmpty())
        assertEquals(0, report.baseline.switches)
        assertEquals(report.baseline.weightedScore, report.paretoTiers.first().weightedScore)

        for (i in 1 until report.paretoTiers.size) {
            val prev = report.paretoTiers[i - 1]
            val cur = report.paretoTiers[i]
            assertTrue("Switches must strictly increase: ${prev.switches} < ${cur.switches}", cur.switches > prev.switches)
            assertTrue("Score must strictly increase: ${prev.weightedScore} < ${cur.weightedScore}", cur.weightedScore > prev.weightedScore)
            assertEquals(cur.weightedScore - report.baseline.weightedScore, cur.scoreDeltaVsBaseline)
            assertEquals(cur.switches, cur.switchEvents.size)
        }
    }

    @Test
    fun minTotalGainFilterSkipsLowRollsAndReplaysExactlyInWorkbench() {
        val character = rules.characters.first { !it.part3Only }
        val legalClasses = rules.professions
            .filter { rules.classAllowed(character.id, it.name, part3 = false) }
            .map { it.name }
            .take(8)

        val weights = List(GrowthStat.entries.size) { idx ->
            if (idx == GrowthStat.STRENGTH.ordinal) 5 else 1
        }
        val startStats = listOf(30, 15, 10, 14, 16, 12, 13, 9, 11)

        val report = ParetoGrowthPlanner.solve(
            ParetoGrowthPlanRequest(
                seed = 1_090_197_388L, // user's actual Pietro seed!
                characterId = character.id,
                line = BuddyCampaignLine.KAI,
                currentLevel = 5,
                targetLevel = 20,
                initialProfession = legalClasses.first(),
                unlockedProfessions = legalClasses.toSet(),
                weights = weights,
                currentStats = startStats,
                maxSwitchBudget = 3,
                maxSkips = 20,
                minTotalGain = 3,
            ),
            rules,
        )

        assertTrue("Filtered Pareto plan should produce at least 1 tier", report.paretoTiers.isNotEmpty())
        val start = SeedWorkbenchStart(
            characterId = character.id,
            line = BuddyCampaignLine.KAI,
            part3 = false,
            baseSeed = 1_090_197_388L,
            inferredLevel = 5,
            startLevel = 5,
            profession = legalClasses.first(),
            unlockedProfessions = legalClasses.toSet(),
            baseStats = startStats,
        )

        for (tier in report.paretoTiers) {
            // Every single actual upgrade step MUST satisfy totalGain >= 3!
            assertTrue(
                "All upgrade steps must have total gain >= 3 for ${tier.label}",
                tier.steps.all { it.gains.sum() >= 3 },
            )
            // Replay in SeedWorkbenchEngine with SKIP + CLASS_CHANGE + UPGRADE must match 100%!
            val eval = SeedWorkbenchEngine.evaluate(
                rules = rules,
                start = start,
                planSteps = tier.workbenchSteps,
                weights = weights,
                maxPreviewSteps = 0,
            )
            assertEquals("Plan replay should have no error for ${tier.label}", null, eval.planError)
            assertEquals("Replay end level mismatch for ${tier.label}", 20, eval.planEndLevel)
            assertEquals("Replay cumulative gains mismatch for ${tier.label}", tier.statGains, eval.planTotals)
            assertEquals(
                "Skip count in workbench steps must match tier.skips",
                tier.skips,
                tier.workbenchSteps.count { it.kind == SeedPlanActionKind.SKIP },
            )
        }
    }

    @Test
    fun optimizeSkipsByWeightBeatsNoSkipPlanAndReplaysExactly() {
        val character = rules.characters.first { !it.part3Only }
        val legalClasses = rules.professions
            .filter { rules.classAllowed(character.id, it.name, part3 = false) }
            .map { it.name }
            .take(6)

        val strHeavyWeights = List(GrowthStat.entries.size) { idx ->
            if (idx == GrowthStat.STRENGTH.ordinal) 5 else 0
        }

        val noSkip = ParetoGrowthPlanner.solve(
            ParetoGrowthPlanRequest(
                seed = 987_654_321L,
                characterId = character.id,
                line = BuddyCampaignLine.SEO,
                currentLevel = 15,
                targetLevel = 25,
                initialProfession = legalClasses.first(),
                unlockedProfessions = legalClasses.toSet(),
                weights = strHeavyWeights,
                maxSwitchBudget = 2,
                maxSkips = 0,
            ),
            rules,
        )
        val withSkip = ParetoGrowthPlanner.solve(
            ParetoGrowthPlanRequest(
                seed = 987_654_321L,
                characterId = character.id,
                line = BuddyCampaignLine.SEO,
                currentLevel = 15,
                targetLevel = 25,
                initialProfession = legalClasses.first(),
                unlockedProfessions = legalClasses.toSet(),
                weights = strHeavyWeights,
                maxSwitchBudget = 2,
                maxSkips = 10,
                optimizeSkips = true,
            ),
            rules,
        )

        assertTrue(
            "Weight-optimized exam skips (${withSkip.recommendedTier.weightedScore}) must beat or match no-skip (${noSkip.recommendedTier.weightedScore})",
            withSkip.recommendedTier.weightedScore > noSkip.recommendedTier.weightedScore,
        )
        assertTrue("Should actually use exam skips", withSkip.recommendedTier.skips in 1..10)

        val eval = SeedWorkbenchEngine.evaluate(
            rules = rules,
            start = SeedWorkbenchStart(
                characterId = character.id,
                line = BuddyCampaignLine.SEO,
                part3 = false,
                baseSeed = 987_654_321L,
                inferredLevel = 15,
                startLevel = 15,
                profession = legalClasses.first(),
                unlockedProfessions = legalClasses.toSet(),
            ),
            planSteps = withSkip.recommendedTier.workbenchSteps,
            weights = strHeavyWeights,
            maxPreviewSteps = 0,
        )
        assertEquals(null, eval.planError)
        assertEquals(25, eval.planEndLevel)
        assertEquals(withSkip.recommendedTier.statGains, eval.planTotals)
    }

    @Test
    fun singleLevelStrengthSwitchBeatsBaselineWhenAlternativeClassProcsStrength() {
        val character = rules.characters.first { !it.part3Only }
        val legalClasses = rules.professions
            .filter { rules.classAllowed(character.id, it.name, part3 = false) }
            .map { it.name }

        val initialClass = legalClasses.first()
        var foundSeed: Long? = null
        var winningClass: String? = null
        val testLevel = 23
        for (candidateSeed in 1L..500L) {
            val baseGrowth = rules.growth(BuddyGrowthInput(character.id, initialClass, testLevel, false, BuddyRoute.KAI)).parameters
            val baseGains = SeedEngine.simulateLevel(candidateSeed, baseGrowth).gains
            if (baseGains[GrowthStat.STRENGTH.ordinal] == 0) {
                val alt = legalClasses.drop(1).firstOrNull { altProf ->
                    val g = rules.growth(BuddyGrowthInput(character.id, altProf, testLevel, false, BuddyRoute.KAI)).parameters
                    SeedEngine.simulateLevel(candidateSeed, g).gains[GrowthStat.STRENGTH.ordinal] > 0
                }
                if (alt != null) {
                    foundSeed = candidateSeed
                    winningClass = alt
                    break
                }
            }
        }
        assertTrue("Should find a concrete Lv.23 STR switch seed", foundSeed != null && winningClass != null)

        val strOnlyWeights = List(GrowthStat.entries.size) { idx ->
            if (idx == GrowthStat.STRENGTH.ordinal) 5 else 0
        }
        val report = ParetoGrowthPlanner.solve(
            ParetoGrowthPlanRequest(
                seed = foundSeed!!,
                characterId = character.id,
                line = BuddyCampaignLine.KAI,
                currentLevel = testLevel,
                targetLevel = testLevel + 1,
                initialProfession = initialClass,
                unlockedProfessions = setOf(initialClass, winningClass!!),
                weights = strOnlyWeights,
                maxSwitchBudget = 1,
            ),
            rules,
        )

        assertEquals(0, report.baseline.statGains[GrowthStat.STRENGTH.ordinal])
        assertEquals(2, report.paretoTiers.size)
        val best = report.recommendedTier
        assertEquals(1, best.switches)
        assertTrue("Switching at Lv.23 should gain STR > 0", best.statGains[GrowthStat.STRENGTH.ordinal] > 0)
        assertEquals(testLevel, best.switchEvents.single().atLevel)
        assertEquals(initialClass, best.switchEvents.single().fromProfession)
        assertEquals(winningClass, best.switchEvents.single().toProfession)
    }
}
