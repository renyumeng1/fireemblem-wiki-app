package com.fireemblem.companion.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreeHousesAndEngagePlannersTest {

    // ===== 1. Fortune's Weave Promotion-Open & Route Availability Tests =====

    @Test
    fun fwPlannerDoesNotSuggestLv5SwitchWhenOnlyBaseClassUnlocked() {
        val rules = BuddyRules.bundled()
        val pietro = rules.characters.first { it.name == "皮特鲁" }
        val strWeights = List(GrowthStat.entries.size) { idx -> if (idx == GrowthStat.STRENGTH.ordinal) 5 else 1 }

        val report = ParetoGrowthPlanner.solve(
            ParetoGrowthPlanRequest(
                seed = 1_090_197_388L,
                characterId = pietro.id,
                line = BuddyCampaignLine.KAI,
                currentLevel = 5,
                targetLevel = 25,
                initialProfession = "平民",
                unlockedProfessions = setOf("平民"),
                includeLockedPreview = true,
                weights = strWeights,
                maxSwitchBudget = 4,
            ),
            rules,
        )

        // Default safety gate prevents switching to locked Tier 1 classes before Lv.10 when only Tier 0 is unlocked!
        assertEquals(10, report.effectiveMinPromotionLevel)
        for (tier in report.paretoTiers) {
            assertTrue(
                "No locked class switch should occur before Lv.10 in ${tier.label}: ${tier.switchEvents}",
                tier.switchEvents.all { it.atLevel >= 10 },
            )
            // Every switch into a locked class must be explicitly marked requiresUnlockExam = true with requirement!
            tier.switchEvents.forEach { ev ->
                assertTrue("Switch to ${ev.toProfession} must flag requiresUnlockExam", ev.requiresUnlockExam)
                assertFalse("Unlock requirement must not be blank", ev.unlockRequirement.isNullOrBlank())
            }
            // Route-locked non-Kai Part 1 classes must NEVER appear!
            val forbiddenInKaiPart1 = setOf("锻造师", "铁甲骑兵", "卫士", "游侠", "舞者", "战象兵")
            assertTrue(
                "No other-route Part 1 exclusive classes allowed in Kai Part 1",
                tier.steps.none { it.profession in forbiddenInKaiPart1 },
            )
        }
        assertTrue(report.warnings.any { it.contains("Lv.10") })
    }

    @Test
    fun fwPlannerHonorsExplicitMinPromotionLevelOverride() {
        val rules = BuddyRules.bundled()
        val pietro = rules.characters.first { it.name == "皮特鲁" }
        val strWeights = List(GrowthStat.entries.size) { idx -> if (idx == GrowthStat.STRENGTH.ordinal) 5 else 1 }

        val report = ParetoGrowthPlanner.solve(
            ParetoGrowthPlanRequest(
                seed = 1_090_197_388L,
                characterId = pietro.id,
                line = BuddyCampaignLine.KAI,
                currentLevel = 5,
                targetLevel = 18,
                initialProfession = "平民",
                unlockedProfessions = setOf("平民"),
                includeLockedPreview = true,
                weights = strWeights,
                maxSwitchBudget = 2,
                minPromotionLevel = 15,
            ),
            rules,
        )

        assertEquals(15, report.effectiveMinPromotionLevel)
        report.paretoTiers.forEach { tier ->
            assertTrue(tier.switchEvents.all { it.atLevel >= 15 })
        }
    }

    // ===== 2. Three Houses Expected Growth & Certification Planner Tests =====

    @Test
    fun threeHousesPoissonBinomialTwoStatRerollBeatsPureLinear() {
        val lowRates = listOf(35, 35, 25, 45, 50, 40, 20, 35, 25)
        val linear = ThreeHousesGrowthPlanner.expectedStepGains(lowRates, guaranteeTwoStats = false)
        val reroll = ThreeHousesGrowthPlanner.expectedStepGains(lowRates, guaranteeTwoStats = true)

        assertTrue("2-stat reroll total (${reroll.sum()}) must strictly exceed pure linear (${linear.sum()})", reroll.sum() > linear.sum())
        assertTrue("2-stat reroll total must be >= 2.0", reroll.sum() >= 2.0)
        for (i in lowRates.indices) {
            assertTrue("Stat $i reroll expectation must be >= linear", reroll[i] >= linear[i] - 1e-9)
        }
    }

    @Test
    fun threeHousesCertFloorAndGenderRulesWorkCorrectly() {
        val ingrid = ThreeHousesCharacterData(
            entryId = "char:ingrid",
            name = "英谷莉特·布兰多尔·贾拉提雅",
            englishName = "Ingrid",
            affiliation = "青狮子学级",
            startLevel = 1,
            startClassName = "贵族",
            baseStats = listOf(27, 8, 6, 6, 8, 6, 5, 8, 8),
            personalGrowth = listOf(40, 35, 35, 40, 60, 45, 30, 40, 45),
        )
        val classes = listOf(
            ThreeHousesClassData("c0", "贵族", "Noble", "专属", 1, 4, "步兵", List(9) { 0 }, listOf(0, 0, 0, 0, 0, 0, 0, 0, 5)),
            ThreeHousesClassData("c1", "士兵", "Soldier", "初级", 5, 4, "步兵", listOf(20, 8, 3, 6, 6, 4, 5, 2, 0), listOf(10, 0, 0, 5, 0, 0, 0, -5, 5), listOf(0, 0, 0, 1, 0, 0, 0, 0, 0), "初级考试（Lv.5+） · 枪术 D"),
            ThreeHousesClassData("c2", "天马骑士", "Pegasus Knight", "中级", 10, 6, "飞行", listOf(25, 10, 6, 10, 11, 8, 7, 7, 0), listOf(15, 0, 0, 0, 10, 0, 0, 5, 10), listOf(0, 0, 0, 2, 3, 0, 0, 2, 0), "中级考试（Lv.10+） · 枪术 C、飞行 D"),
            ThreeHousesClassData("c3", "重甲骑士", "Armored Knight", "中级", 10, 4, "重装", listOf(30, 9, 6, 7, 4, 8, 12, 1, 0), listOf(20, 0, 0, 0, -10, 0, 10, -5, 5), listOf(1, 0, 0, 0, -2, 0, 4, -2, 0), "中级考试（Lv.10+） · 斧术 C、重装 D"),
            ThreeHousesClassData("c4", "战争将领", "War Master", "最高级", 30, 6, "步兵", listOf(35, 20, 10, 14, 16, 12, 15, 10, 0), listOf(40, 15, 0, 0, 10, 0, 0, 0, 5), listOf(3, 5, 0, 0, 2, 1, 1, 0, 0), "最高级考试（Lv.30+） · 斧术 A、格斗术 A"),
            ThreeHousesClassData("c5", "飞龙骑士", "Wyvern Rider", "高级", 20, 7, "飞行", listOf(32, 18, 8, 12, 14, 10, 12, 8, 0), listOf(30, 10, -5, 0, 0, 0, 5, 0, 0), listOf(1, 3, 0, 0, 2, 0, 2, 0, 0), "高级考试（Lv.20+） · 斧术 B、飞行 C"),
            ThreeHousesClassData("c6", "飞龙将领", "Wyvern Lord", "最高级", 30, 8, "飞行", listOf(34, 18, 10, 14, 20, 12, 15, 12, 0), listOf(30, 15, -5, 0, 10, 0, 5, 0, 5), listOf(2, 4, 0, 1, 4, 0, 3, 0, 0), "最高级考试（Lv.30+） · 枪术 C、斧术 A、飞行 A"),
        )

        // Female character cannot enter male-only War Master, CAN enter Pegasus Knight & Wyvern Lord
        assertFalse(ThreeHousesRules.isClassAllowed(ingrid, classes.first { it.name == "战争将领" }))
        assertTrue(ThreeHousesRules.isClassAllowed(ingrid, classes.first { it.name == "天马骑士" }))
        assertTrue(ThreeHousesRules.isClassAllowed(ingrid, classes.first { it.name == "飞龙将领" }))

        val report = ThreeHousesGrowthPlanner.plan(
            ThreeHousesPlanRequest(
                characterName = "英谷莉特",
                startLevel = 1,
                targetLevel = 35,
                weights = listOf(1, 5, 0, 2, 4, 0, 2, 0, 0),
                extraCertDips = listOf(ThreeHousesCertDipSpec(atLevel = 10, className = "重甲骑士")),
            ),
            characters = listOf(ingrid),
            classes = classes,
        )

        assertEquals(34, report.levelRows.size)
        assertTrue(report.usedTwoStatReroll)
        assertEquals("飞龙将领", report.stages.last().className)
        // Armored Knight dip at Lv.10 should bump Def up to 12 (>~7.8 expected at Lv.10)!
        val dipEv = report.certEvents.first { it.isCertDipOnly && it.className == "重甲骑士" }
        assertTrue("Armored Knight cert dip must grant positive Def floor bump", dipEv.floorGains[ThreeHousesStat.DEFENSE.ordinal] > 3.0)
        // Wyvern Rider at Lv.20 should also bump Ingrid's Str up to 18 floor!
        assertTrue("Final displayed Str should reflect floor + growth + class bonus", report.finalDisplayedStats[ThreeHousesStat.STRENGTH.ordinal] > 28.0)
    }

    // ===== 3. Engage Fixed Growth Exact Deterministic Calculator Tests =====

    @Test
    fun engageFixedGrowthReproducesAlearLv1ToLv5ExactAccumulatorTable() {
        // Verified directly against engage/pack.db growth-mechanics table for Alear!
        val alear = EngageCharacterData(
            entryId = "char:alear",
            name = "琉尔",
            affiliation = "里特司",
            initialLevel = 1,
            internalLevel = 0,
            initialClassName = "神龙之子",
            baseStats = listOf(22, 6, 0, 5, 7, 5, 5, 3, 4),
            personalGrowth = listOf(60, 35, 20, 45, 50, 25, 40, 25, 5),
            capModifiers = listOf(0, 1, 0, 1, 1, 0, 0, 0, 0),
        )
        val dragonChild = EngageClassData(
            entryId = "cls:dc",
            name = "神龙之子",
            tier = "基础",
            movement = 4,
            battleStyle = "龙族",
            baseStats = listOf(22, 6, 0, 5, 7, 5, 5, 3, 4),
            growth = listOf(10, 10, 0, 10, 15, 5, 10, 10, 5),
            caps = listOf(60, 36, 20, 31, 38, 30, 30, 20, 11),
            restrictionNote = "琉尔限定",
        )
        val divineDragon = EngageClassData(
            entryId = "cls:dd",
            name = "神龙之王",
            tier = "高级",
            movement = 5,
            battleStyle = "龙族",
            baseStats = listOf(24, 8, 1, 4, 8, 3, 6, 5, 7),
            growth = listOf(10, 10, 0, 10, 15, 10, 15, 15, 5),
            caps = listOf(68, 41, 25, 36, 43, 35, 35, 25, 13),
            weaponRequirement = "剑A、体术B",
            classSkill = "神龙气",
            restrictionNote = "琉尔限定",
        )

        val report = EngageFixedGrowthPlanner.solve(
            EngageFixedGrowthRequest(
                characterName = "琉尔",
                stages = listOf(
                    EngageStageSpec("神龙之子", levelsToGain = 9, starsphere = false),
                    EngageStageSpec("神龙之王", levelsToGain = 5, starsphere = false),
                ),
            ),
            characters = listOf(alear),
            classes = listOf(dragonChild, divineDragon),
        )

        assertEquals(14, report.levelSteps.size)
        // Step 1 (Lv.1 -> Lv.2 in 神龙之子):
        // Init acc: [60, 35, 20, 45, 50, 25, 40, 25, 5] + [70, 45, 20, 55, 65, 30, 50, 35, 10]
        // Sums:     [130, 80, 40, 100, 115, 55, 90, 60, 15] -> gains: HP+1, Dex+1, Spd+1!
        val step1 = report.levelSteps[0]
        assertEquals(listOf(1, 0, 0, 1, 1, 0, 0, 0, 0), step1.gains)
        assertEquals(listOf(30, 80, 40, 0, 15, 55, 90, 60, 15), step1.accumulatorsAfter)

        // Class change at Lv.10 -> Divine Dragon: exact class base swap [24-22, 8-6, 1-0, 4-5, 8-7, 3-5, 6-5, 5-3, 7-4]
        assertEquals(1, report.classChanges.size)
        val promo = report.classChanges.single()
        assertEquals("大师证晋升", promo.sealLabel)
        assertEquals(listOf(2, 2, 1, -1, 1, -2, 1, 2, 3), promo.statDiffs)
        // Accumulator carries over intact across Master Seal!
        val beforePromoAcc = report.levelSteps[8].accumulatorsAfter
        val afterPromoStep = report.levelSteps[9]
        val expectedRatesDd = listOf(70, 45, 20, 55, 65, 35, 55, 40, 10)
        for (i in 0 until 9) {
            assertEquals((beforePromoAcc[i] + expectedRatesDd[i]) / 100, afterPromoStep.gains[i])
            assertEquals((beforePromoAcc[i] + expectedRatesDd[i]) % 100, afterPromoStep.accumulatorsAfter[i])
        }
    }

    @Test
    fun engageFixedGrowthDoublesClassGrowthForJeanAndAppliesStarsphere() {
        val jean = EngageCharacterData(
            entryId = "char:jean",
            name = "贾恩",
            affiliation = "费列聂",
            initialLevel = 1,
            internalLevel = 0,
            initialClassName = "僧侣",
            baseStats = listOf(19, 4, 5, 10, 7, 3, 4, 8, 3),
            personalGrowth = listOf(50, 20, 20, 35, 40, 25, 25, 20, 5),
        )
        val warrior = EngageClassData(
            entryId = "cls:war",
            name = "勇士",
            tier = "高级",
            baseStats = listOf(27, 12, 1, 7, 7, 2, 4, 3, 8),
            growth = listOf(25, 20, 0, 10, 15, 0, 10, 5, 5),
            caps = listOf(94, 46, 20, 30, 33, 23, 27, 18, 20),
        )
        val ratesNoStar = EngageFixedGrowthPlanner.effectiveRates(jean, warrior, starsphere = false)
        // Jean Expertise doubles class growth: HP = 50 + 25*2 = 100, Str = 20 + 20*2 = 60, Spd = 40 + 15*2 = 70
        assertEquals(listOf(100, 60, 20, 55, 70, 25, 45, 30, 15), ratesNoStar)
        val ratesStar = EngageFixedGrowthPlanner.effectiveRates(jean, warrior, starsphere = true)
        assertEquals(ratesNoStar.map { it + 15 }, ratesStar)
    }
}
