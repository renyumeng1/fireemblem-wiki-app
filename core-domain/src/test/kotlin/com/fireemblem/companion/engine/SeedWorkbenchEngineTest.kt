package com.fireemblem.companion.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeedWorkbenchEngineTest {
    private val rules = BuddyRules.bundled()

    @Test
    fun previewsContinuouslyUpToLevel99AndAppliesPreAdvances() {
        val baseSeed = 42L
        val start = SeedWorkbenchStart(
            characterId = "c044",
            line = BuddyCampaignLine.KAI,
            part3 = false,
            baseSeed = baseSeed,
            inferredLevel = 1,
            startLevel = 3, // 2 missed upgrades
            extraAdvances = 1, // +1 extra exam/skip advance
            profession = "平民",
        )
        val eval = SeedWorkbenchEngine.evaluate(rules, start, maxPreviewSteps = 120)

        val expectedSeed = SeedEngine.advance(SeedEngine.advance(SeedEngine.advance(baseSeed)))
        assertEquals(expectedSeed, eval.effectiveStartSeed)
        // From Lv.3 to Lv.99 is exactly 96 level-ups
        assertEquals(96, eval.previewRows.size)
        assertEquals(3, eval.previewRows.first().fromLevel)
        assertEquals(4, eval.previewRows.first().nextLevel)
        assertEquals(98, eval.previewRows.last().fromLevel)
        assertEquals(99, eval.previewRows.last().nextLevel)
        assertTrue(eval.warnings.any { "预推进 3 步" in it })
    }

    @Test
    fun skipOverrideAdvancesSeedWithoutLevelingAndShiftsDownstreamRows() {
        val start = SeedWorkbenchStart(
            characterId = "c044",
            line = BuddyCampaignLine.KAI,
            part3 = false,
            baseSeed = 100L,
            inferredLevel = 10,
            startLevel = 10,
            profession = "平民",
        )
        val baseEval = SeedWorkbenchEngine.evaluate(rules, start, maxPreviewSteps = 5)
        val skippedEval = SeedWorkbenchEngine.evaluate(
            rules,
            start,
            previewOverrides = mapOf(2 to SeedPreviewOverride(skipOrExam = true)),
            maxPreviewSteps = 5,
        )

        assertEquals(11, skippedEval.previewRows[1].fromLevel)
        assertEquals(11, skippedEval.previewRows[1].nextLevel)
        assertTrue(skippedEval.previewRows[1].actionIsSkip)
        // Row 3 stays at Lv.11 instead of Lv.12, while using the same advanced seed!
        assertEquals(11, skippedEval.previewRows[2].fromLevel)
        assertEquals(baseEval.previewRows[2].seed, skippedEval.previewRows[2].seed)
        // Cumulative gains on row 2 equal row 1 because row 2 was skipped
        assertEquals(skippedEval.previewRows[0].cumulativeGainsFromStart, skippedEval.previewRows[1].cumulativeGainsFromStart)
    }

    @Test
    fun commitPreviewAutoInsertsClassChangeShiftsOverridesAndBlocksLockedTrialClass() {
        val allowed = rules.professions.filter { rules.classAllowed("c044", it.name, false) }.map { it.name }
        val altClass = allowed.first { it != "平民" }
        val lockedStart = SeedWorkbenchStart(
            characterId = "c044",
            line = BuddyCampaignLine.KAI,
            part3 = false,
            baseSeed = 42L,
            inferredLevel = 1,
            profession = "平民",
            unlockedProfessions = setOf("平民"),
        )
        val lockedOverrides = mapOf(
            2 to SeedPreviewOverride(trialProfession = altClass),
            4 to SeedPreviewOverride(skipOrExam = true),
        )
        val lockedEval = SeedWorkbenchEngine.evaluate(rules, lockedStart, previewOverrides = lockedOverrides, maxPreviewSteps = 6)
        assertEquals(TrialProfessionStatus.LOCKED_TRIAL_ONLY, lockedEval.previewRows[1].trialStatus)

        // Attempting to commit a locked trial class fails cleanly
        val blocked = SeedWorkbenchEngine.commitPreviewUpTo(lockedEval, emptyList(), lockedOverrides, targetStepIndex = 2)
        assertNotNull(blocked.error)
        assertTrue(blocked.planSteps.isEmpty())

        // Once unlocked, commit succeeds, inserts CLASS_CHANGE, and shifts step 4 override -> step 2!
        val unlockedStart = lockedStart.copy(unlockedProfessions = setOf("平民", altClass))
        val unlockedEval = SeedWorkbenchEngine.evaluate(rules, unlockedStart, previewOverrides = lockedOverrides, maxPreviewSteps = 6)
        assertEquals(TrialProfessionStatus.UNLOCKED_PLANABLE, unlockedEval.previewRows[1].trialStatus)
        val committed = SeedWorkbenchEngine.commitPreviewUpTo(unlockedEval, emptyList(), lockedOverrides, targetStepIndex = 2)
        assertNull(committed.error)
        assertEquals(
            listOf(
                SeedWorkbenchPlanStep(SeedPlanActionKind.UPGRADE),
                SeedWorkbenchPlanStep(SeedPlanActionKind.CLASS_CHANGE, targetProfession = altClass),
                SeedWorkbenchPlanStep(SeedPlanActionKind.UPGRADE),
            ),
            committed.planSteps,
        )
        assertEquals(mapOf(2 to SeedPreviewOverride(skipOrExam = true)), committed.shiftedOverrides)

        // Evaluating the committed plan continues seamlessly from step 3 onward
        val afterEval = SeedWorkbenchEngine.evaluate(rules, unlockedStart, committed.planSteps, committed.shiftedOverrides, maxPreviewSteps = 6)
        assertNull(afterEval.planError)
        assertEquals(3, afterEval.planEndLevel)
        assertEquals(altClass, afterEval.planEndProfession)
        assertEquals(unlockedEval.previewRows[2].seed, afterEval.planEndSeed)
    }

    @Test
    fun compareProfessionsAndSmartFilterFindOptimalRolls() {
        val start = SeedWorkbenchStart(
            characterId = "c044",
            line = BuddyCampaignLine.KAI,
            part3 = false,
            baseSeed = 42L,
            inferredLevel = 1,
            profession = "平民",
            unlockedProfessions = setOf("平民"),
        )
        val weights = List(9) { if (it == GrowthStat.STRENGTH.ordinal || it == GrowthStat.SPEED.ordinal) 5 else 0 }
        val eval = SeedWorkbenchEngine.evaluate(rules, start, weights = weights, maxPreviewSteps = 30)

        val options = SeedWorkbenchEngine.compareProfessionsAtRow(
            rules = rules,
            start = start,
            rowSeed = eval.previewRows.first().seed,
            rowLevel = eval.previewRows.first().fromLevel,
            currentProfession = "平民",
            unlockedProfessions = setOf("平民"),
            weights = weights,
        )
        assertTrue(options.size > 1)
        assertTrue(options.zipWithNext().all { (a, b) ->
            a.weightedScore > b.weightedScore ||
                (a.weightedScore == b.weightedScore && a.totalGain >= b.totalGain)
        })

        val filtered = SeedWorkbenchEngine.filterPreview(
            eval.previewRows,
            SeedPreviewFilter(minTotalGain = 4, requiredStats = setOf(GrowthStat.STRENGTH)),
        )
        assertTrue(filtered.all { !it.actionIsSkip && it.totalGain >= 4 && it.gains[GrowthStat.STRENGTH.ordinal] > 0 })
    }

    @Test
    fun manualObservationsBuildReadyHistoryWithEndStateAndRecoverSeed() {
        val origin = 7_777L
        val forecast = rules.forecast(origin, BuddyGrowthInput("c044", "平民", 5, false, BuddyRoute.KAI), count = 4)
        val manual = forecast.rows.map { row ->
            QuickManualObservation(
                level = row.fromLevel,
                profession = "平民",
                gains = GrowthStat.entries.map { row.gains.getValue(it) },
            )
        }
        val history = SeedWorkbenchEngine.manualObservationsToHistory(rules, "c044", BuddyCampaignLine.KAI, false, manual)
        assertTrue(history.ready)
        assertEquals(9, history.endState?.level)
        assertEquals("平民", history.endState?.profession)
        val search = SeedEngine.searchRangeParallel(history.events, 0L, 10_000L, parallelism = 2)
        assertEquals(SeedSearchStatus.UNIQUE, search.status)
        assertEquals(forecast.rows.last().nextSeed, search.candidates.single().currentSeed)
    }

    @Test
    fun workbenchRecordRoundTripsThroughJson() {
        val record = SeedWorkbenchRecord(
            profileId = "global",
            characterId = "c044",
            line = BuddyCampaignLine.LEDA,
            part3 = false,
            baseSeed = 999L,
            inferredLevel = 5,
            startLevel = 6,
            extraAdvances = 2,
            profession = "平民",
            ride = null,
            unlockedProfessions = setOf("平民", "士兵"),
            weights = listOf(1, 5, 0, 3, 5, 0, 0, 0, 0),
            planSteps = listOf(
                SeedWorkbenchPlanStep(SeedPlanActionKind.UPGRADE),
                SeedWorkbenchPlanStep(SeedPlanActionKind.SKIP),
                SeedWorkbenchPlanStep(SeedPlanActionKind.EXAM, "士兵", 80.0),
                SeedWorkbenchPlanStep(SeedPlanActionKind.CLASS_CHANGE, "士兵"),
            ),
            previewOverrides = mapOf(3 to SeedPreviewOverride(skipOrExam = true, trialProfession = "士兵")),
            completedSteps = 2,
        )
        val decoded = SeedWorkbenchRecord.decode(record.encode())
        assertEquals(record, decoded)
        assertFalse(SeedWorkbenchRecord.entityId("global", "c044", BuddyCampaignLine.LEDA).isBlank())
    }
}
