package com.fireemblem.companion.engine

import com.fireemblem.companion.personal.PersonalSnapshot
import com.fireemblem.companion.personal.EncryptedBackup
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class LevelJournalTest {
    private val rules = BuddyRules.bundled()

    private fun journal(line: BuddyCampaignLine = BuddyCampaignLine.KAI): LevelJournal {
        val forecast = rules.forecast(42, BuddyGrowthInput("c044", "平民", 1, false, line.rulesRoute),
            count = 6, skippedIndices = setOf(1))
        return LevelJournal("一周目", "global", line, "c044", forecast.rows.map { row ->
            LevelObservation(level = row.fromLevel, profession = "平民", part3 = false,
                gains = GrowthStat.entries.map { row.gains.getValue(it) }, skipped = row.skipped)
        })
    }

    private fun importedPlan(actions: List<JournalImportedGrowthAction>): LevelJournal {
        val base = LevelJournal("已确认种子路线", "global", BuddyCampaignLine.SEO, "c012")
        val digest = base.historyDigest()
        val unlocked = setOf("士兵")
        val plan = JournalImportedGrowthPlan("a".repeat(64), 0, 0, "source-entry", "赛奥朵拉篇", 1,
            "力量优先", 42, 1, "JID_兵士", "士兵", false, List(9) { 0 }, List(9) { null }, unlocked,
            actions, historyDigest = digest)
        val checkpoint = JournalSeedCheckpoint(42, 0, 1, "士兵", false, List(9) { 0 }, List(9) { null },
            JournalSeedEvidence.USER_CONFIRMED_IMPORT, digest, sourceSha256 = "a".repeat(64), originSeed = 42,
            unlockedProfessions = unlocked)
        return base.copy(seedCheckpoint = checkpoint, unlockedProfessions = unlocked, importedGrowthPlan = plan)
    }

    @Test fun fourRoutesRoundTripAndRecoverKnownSeedWithSkippedAttempt() {
        BuddyCampaignLine.entries.forEach { line ->
            val journal = journal(line)
            val restored = LevelJournal.decode(journal.encode())
            assertEquals(journal, restored)
            assertEquals(line, restored.line)
            val history = restored.history(rules)
            assertTrue(history.errors.toString(), history.ready)
            assertEquals(5, history.observedLevels)
            assertEquals(1, history.skippedAttempts)
            val result = SeedEngine.searchRange(history.events, 42, 43)
            assertEquals(SeedSearchStatus.UNIQUE, result.status)
            assertEquals(listOf(42L), result.candidates.single().originSeeds)
        }
    }

    @Test fun theodoraFiveObservedUpgradesMatchKnownSeed() {
        val character = rules.characters.single { it.personId == "PID_セオドラ" }
        val growth = rules.growth(BuddyGrowthInput(character.id, "士兵", 10, false, BuddyRoute.OTHER))
        val expectedGrowth = listOf(80, 55, 30, 45, 35, 30, 50, 30, 50)
        val observedGains = listOf(
            listOf(1, 0, 1, 0, 0, 0, 0, 1, 0),
            listOf(1, 1, 1, 0, 0, 0, 0, 0, 1),
            listOf(1, 0, 1, 0, 0, 0, 1, 1, 1),
            listOf(1, 1, 0, 0, 1, 1, 1, 0, 0),
            listOf(1, 0, 1, 0, 1, 0, 1, 1, 0),
        )
        val journal = LevelJournal("种子验证", "global", BuddyCampaignLine.SEO, character.id,
            observedGains.mapIndexed { index, gains ->
                LevelObservation(level = 10 + index, profession = "士兵", part3 = false, gains = gains)
            })
        val history = journal.history(rules)

        assertEquals("c012", character.id)
        assertEquals(expectedGrowth, growth.parameters.growth)
        assertEquals(2, growth.parameters.guarantee)
        assertTrue(history.errors.toString(), history.ready)
        assertEquals(5, history.observedLevels)

        val result = SeedEngine.searchRange(history.events, 2_141_258_584L, 2_141_258_585L)

        assertEquals(SeedSearchStatus.UNIQUE, result.status)
        assertTrue(result.complete)
        assertEquals(1L, result.matches)
        assertEquals(listOf(2_141_258_584L), result.candidates.single().originSeeds)
        assertEquals(759_314_053L, result.candidates.single().currentSeed)
        assertEquals(List(5) { 1L }, result.prefixMatches)
    }

    @Test fun addingAndCorrectingObservationsRebuildsSearchFromActualGains() {
        val journal = journal()
        val original = journal.observations.last()
        val wrong = original.copy(gains = original.gains.toMutableList().also { it[0] = (it[0] + 1) % 3 })
        val changed = journal.copy(observations = journal.observations.dropLast(1) + wrong)
        assertTrue(SeedEngine.searchRange(changed.history(rules).events, 42, 43).candidates.isEmpty())
        assertEquals(SeedSearchStatus.UNIQUE, SeedEngine.searchRange(journal.history(rules).events, 42, 43).status)
        assertFalse(journal.copy(observations = journal.observations.take(3)).history(rules).ready)
    }

    @Test fun removingMiddleUpgradeBlocksSearchButRemovingLastKeepsValidSequence() {
        val journal = journal()
        assertFalse(journal.copy(observations = journal.observations.filterIndexed { index, _ -> index != 2 }).history(rules).ready)
        assertTrue(journal.copy(observations = journal.observations.dropLast(1)).history(rules).ready)
    }

    @Test fun batchRemovalAndReorderingInvalidateSeedEvidenceWithoutChangingObservationIds() {
        val source = journal()
        val checkpoint = JournalSeedCheckpoint(42, source.observations.size, 6, "平民", false,
            List(9) { 0 }, List(9) { null }, JournalSeedEvidence.MANUAL, source.historyDigest())
        val confirmed = source.copy(seedCheckpoint = checkpoint)
        assertTrue(confirmed.hasCurrentSeedCheckpoint())

        val moved = confirmed.moveObservation(source.observations.last().id, -1)
        assertEquals(source.observations.map { it.id }.dropLast(2) +
            listOf(source.observations.last().id, source.observations[source.observations.lastIndex - 1].id),
            moved.observations.map { it.id })
        assertFalse(moved.hasCurrentSeedCheckpoint())

        val removedIds = source.observations.take(2).map { it.id }.toSet()
        val removed = confirmed.removeObservations(removedIds)
        assertEquals(source.observations.drop(2).map { it.id }, removed.observations.map { it.id })
        assertFalse(removed.hasCurrentSeedCheckpoint())
        assertThrows(IllegalArgumentException::class.java) { confirmed.removeObservations(emptySet()) }
        assertThrows(IllegalArgumentException::class.java) { confirmed.removeObservations(setOf("missing")) }
        assertThrows(IllegalArgumentException::class.java) { confirmed.moveObservation(source.observations.first().id, -1) }
    }

    @Test fun invalidRouteVersionGainsAndDuplicateIdsAreRejected() {
        val journal = journal()
        assertThrows(IllegalArgumentException::class.java) { LevelJournal.decode(JsonObject(journal.encode() + ("line" to JsonPrimitive("unknown")))) }
        assertThrows(IllegalArgumentException::class.java) { LevelJournal.decode(JsonObject(journal.encode() + ("version" to JsonPrimitive(8)))) }
        assertThrows(IllegalArgumentException::class.java) { journal.copy(observations = listOf(journal.observations.first().copy(gains = listOf(1)))).encode() }
        assertThrows(IllegalArgumentException::class.java) { journal.copy(observations = List(2) { journal.observations.first() }).encode() }
        assertFalse(journal.copy(ruleRevision = "unavailable").history(rules).ready)
    }

    @Test fun batchMetadataSurvivesStorageAndOnlyCompleteConsistentBatchesCanBeSearched() {
        val batch = LevelJournal("连续升级", "global", BuddyCampaignLine.KAI, "c044", (34..35).map { level ->
            LevelObservation(level = level, profession = "平民", part3 = false, growthLevel = 35)
        })
        assertEquals(batch, LevelJournal.decode(batch.encode()))
        assertTrue(batch.history(rules).errors.toString(), batch.history(rules).errors.isEmpty())
        assertTrue(batch.copy(observations = batch.observations.dropLast(1)).history(rules).errors.any { "批次不完整" in it })
        val interrupted = batch.copy(observations = listOf(batch.observations.first(),
            LevelObservation(level = 35, profession = "平民", part3 = false, skipped = true)) + batch.observations.last())
        assertTrue(interrupted.history(rules).errors.any { "批次不完整" in it })
        val invalidRows = listOf(
            batch.observations.last().copy(profession = "斗士"),
            batch.observations.last().copy(part3 = true),
            batch.observations.last().copy(growthLevel = null),
        )
        invalidRows.forEach { changed ->
            assertTrue(batch.copy(observations = listOf(batch.observations.first(), changed)).history(rules).errors.isNotEmpty())
        }
        assertNotEquals(batch.historyDigest(), batch.copy(observations = batch.observations.map { it.copy(growthLevel = null) }).historyDigest())
        val snapshot = PersonalSnapshot(emptyList()).edit(UUID.randomUUID().toString(), LevelJournal.KIND,
            UUID.randomUUID().toString(), batch.encode())
        val password = "batch-backup-strong-password".toCharArray()
        val restored = EncryptedBackup.decrypt(EncryptedBackup.encrypt(snapshot, password), password)
        assertEquals(batch, LevelJournal.decode(restored.revisions.single().payload))
    }

    @Test fun batchGrowthLevelsRejectMalformedOrNonUpgradeValuesAndOlderJournalsRemainReadable() {
        val source = journal()
        listOf(3, 4).forEach { version ->
            val payload = JsonObject(source.encode() + mapOf("version" to JsonPrimitive(version), "observations" to
                JsonArray(source.encode().getValue("observations").jsonArray.map { JsonObject(it.jsonObject - "growthLevel") })))
            assertEquals(source, LevelJournal.decode(payload))
            assertEquals(source.historyDigest(), LevelJournal.decode(payload).historyDigest())
        }
        val row = source.observations.first()
        listOf(row.copy(growthLevel = 0), row.copy(growthLevel = 99), row.copy(skipped = true, growthLevel = 1),
            row.copy(exam = JournalExam("", null, false, null), gains = List(9) { 0 }, growthLevel = 1)).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { source.copy(observations = listOf(invalid)).validated() }
        }
        val encodedRow = source.encode().getValue("observations").jsonArray.first().jsonObject
        listOf(JsonPrimitive("1"), JsonPrimitive(1.5), JsonPrimitive(true)).forEach { invalid ->
            val payload = JsonObject(source.encode() + ("observations" to JsonArray(listOf(
                JsonObject(encodedRow + ("growthLevel" to invalid))))))
            assertThrows(IllegalArgumentException::class.java) { LevelJournal.decode(payload) }
        }
    }

    @Test fun impossibleChapterAndRideAreNotSilentlyChanged() {
        val journal = journal()
        val first = journal.observations.first()
        assertFalse(journal.copy(observations = listOf(first.copy(part3 = true)) + journal.observations.drop(1)).history(rules).ready)
        val unknownRide = first.copy(ride = BuddyRideSelection("unknown", 1))
        assertTrue(journal.copy(observations = listOf(unknownRide)).history(rules).errors.isNotEmpty())
    }

    @Test fun versionOneJournalWithoutNewerFieldsMigratesLosslessly() {
        val source = journal()
        val legacyPayload = legacyJournalPayload(source, version = 1)
        assertFalse("seedCheckpoint" in legacyPayload)
        assertFalse("unlockedProfessions" in legacyPayload)
        assertFalse("exam" in legacyPayload.getValue("observations").jsonArray.first().jsonObject)

        val migrated = LevelJournal.decode(legacyPayload)

        assertEquals(source, migrated)
        assertEquals(7, migrated.encode().getValue("version").jsonPrimitive.int)
    }

    @Test fun versionTwoJournalMigratesCheckpointAndPreservesUint32Seeds() {
        val source = journal()
        val checkpoint = JournalSeedCheckpoint(
            seed = 4_294_967_295L,
            eventIndex = source.observations.size,
            level = source.observations.last().level + 1,
            profession = source.observations.last().profession,
            part3 = source.observations.last().part3,
            stats = List(9) { 8 },
            caps = List(9) { null },
            evidence = JournalSeedEvidence.IMPORTED_REPLAY,
            historyDigest = source.historyDigest(),
            sourceSha256 = "a".repeat(64),
            originSeed = 4_294_967_295L,
            unlockedProfessions = setOf("平民"),
        )
        val v2Journal = source.copy(seedCheckpoint = checkpoint, unlockedProfessions = setOf("平民"))
        val v2Payload = legacyJournalPayload(v2Journal, version = 2)
        assertFalse("growthPlan" in v2Payload)

        val restored = LevelJournal.decode(v2Payload)

        assertEquals(v2Journal, restored)
        assertTrue(restored.hasCurrentSeedCheckpoint())
        assertEquals(4_294_967_295L, restored.seedCheckpoint?.seed)
        assertEquals(4_294_967_295L, restored.seedCheckpoint?.originSeed)
        assertFalse(restored.copy(observations = restored.observations.dropLast(1)).hasCurrentSeedCheckpoint())
        val oldRulesJournal = source.copy(ruleRevision = "older-rules")
        val oldRulesCheckpoint = checkpoint.copy(ruleRevision = "older-rules", historyDigest = oldRulesJournal.historyDigest())
        assertFalse(oldRulesJournal.copy(seedCheckpoint = oldRulesCheckpoint).hasCurrentSeedCheckpoint())
        assertEquals(7, restored.encode().getValue("version").jsonPrimitive.int)
    }

    @Test fun importedSourceReferenceRoundTripsWithoutChangingGrowthHistoryIdentity() {
        val source = journal()
        val imported = source.copy(importSourceSha256 = "b".repeat(64), importSourceRecordIndex = 2)
        val restored = LevelJournal.decode(imported.encode())
        assertEquals(imported, restored)
        assertEquals(source.historyDigest(), restored.historyDigest())
        assertThrows(IllegalArgumentException::class.java) { source.copy(importSourceRecordIndex = 0).encode() }
        assertThrows(IllegalArgumentException::class.java) { source.copy(importSourceSha256 = "not-a-sha").encode() }
    }

    @Test fun versionFiveJournalMigratesWithoutInventingSourceArchiveData() {
        val source = journal()
        val versionFive = JsonObject(source.encode() - "importSourceSha256" - "importSourceRecordIndex" +
            ("version" to JsonPrimitive(5)))
        val migrated = LevelJournal.decode(versionFive)
        assertEquals(source, migrated)
        assertEquals(null, migrated.importSourceSha256)
        assertEquals(null, migrated.importSourceRecordIndex)
        assertEquals(7, migrated.encode().getValue("version").jsonPrimitive.int)
    }

    @Test fun unknownExamDetailsRoundTripWithoutUnlockingAnUnknownProfession() {
        val source = LevelJournal("来源考试", "global", BuddyCampaignLine.KAI, "c044", listOf(
            LevelObservation(level = 10, profession = "平民", part3 = false,
                exam = JournalExam("", null, true, null)),
        ))
        val restored = LevelJournal.decode(source.encode())
        assertEquals(source, restored)
        assertEquals(setOf("平民"), restored.confirmedUnlockedProfessions())
        assertEquals(10, restored.currentLevel)
        val history = restored.history(rules)
        assertTrue(history.errors.toString(), history.errors.isEmpty())
        assertEquals(listOf(ObservedSeedEvent.Advance()), history.events)
        val encodedRow = source.encode().getValue("observations").jsonArray.single().jsonObject
        listOf("probability" to JsonPrimitive("bad"), "resourceCost" to JsonPrimitive(1.5),
            "targetProfession" to JsonPrimitive(12)).forEach { (key, invalid) ->
            val invalidExam = JsonObject(encodedRow.getValue("exam").jsonObject + (key to invalid))
            val payload = JsonObject(source.encode() + ("observations" to
                JsonArray(listOf(JsonObject(encodedRow + ("exam" to invalidExam))))))
            assertThrows(IllegalArgumentException::class.java) { LevelJournal.decode(payload) }
        }
    }

    @Test fun passedAndFailedExamsConsumeSeedsWithoutLevelsWhileSwitchesDoNot() {
        val examSeed = 42L
        val firstExam = SeedEngine.simulateExam(examSeed, ExamParameters(40.0))
        val secondExam = SeedEngine.simulateExam(firstExam.nextSeed, ExamParameters(100.0))
        val levelParameters = rules.growth(BuddyGrowthInput("c044", "斗士", 10, false, BuddyRoute.KAI)).parameters
        val upgrade = SeedEngine.simulateLevel(secondExam.nextSeed, levelParameters)
        val journal = LevelJournal("考试记录", "global", BuddyCampaignLine.KAI, "c044", listOf(
            LevelObservation(level = 10, profession = "平民", part3 = false,
                exam = JournalExam("斗士", 40.0, firstExam.passed)),
            LevelObservation(level = 10, profession = "平民", part3 = false,
                exam = JournalExam("斗士", 100.0, secondExam.passed)),
            LevelObservation(level = 10, profession = "斗士", part3 = false, professionChangeFrom = "平民"),
            LevelObservation(level = 10, profession = "斗士", part3 = false, gains = upgrade.gains),
        ))
        assertEquals(journal, LevelJournal.decode(journal.encode()))
        val history = journal.history(rules)
        assertTrue(history.errors.toString(), history.errors.isEmpty())
        assertEquals(1, history.observedLevels)
        assertEquals(3, history.events.size)
        val wrongTransition = journal.copy(observations = journal.observations.mapIndexed { index, row ->
            if (index == 2) row.copy(professionChangeFrom = "斗士") else row
        })
        assertTrue(wrongTransition.history(rules).errors.any { it.contains("职业切换记录与上一条职业不连续") })
        var seed = examSeed
        history.events.forEach { seed = (SeedEngine.matchEvent(seed, it) as EventMatchResult.Matched).nextSeed }
        assertEquals(upgrade.nextSeed, seed)
    }

    @Test fun recordedExamAdvancesCurrentCheckpointAndCareerChangeDoesNot() {
        val source = LevelJournal("记录考试", "global", BuddyCampaignLine.KAI, "c044")
        val checkpoint = JournalSeedCheckpoint(42, 0, 1, "平民", false, List(9) { 10 }, List(9) { null },
            JournalSeedEvidence.MANUAL, source.historyDigest(), unlockedProfessions = setOf("平民"))
        val withSeed = source.copy(seedCheckpoint = checkpoint, unlockedProfessions = setOf("平民"))
        val exam = SeedEngine.simulateExam(42, ExamParameters(100.0))

        val afterExam = withSeed.recordExam("斗士", 100.0, exam.passed, 1, rules)
        assertEquals(exam.nextSeed, afterExam.seedCheckpoint?.seed)
        assertEquals(1, afterExam.currentLevel)
        assertTrue(afterExam.hasCurrentSeedCheckpoint())
        assertTrue("斗士" in afterExam.confirmedUnlockedProfessions())

        val failedExam = SeedEngine.simulateExam(exam.nextSeed, ExamParameters(0.0))
        val afterFailedExam = afterExam.recordExam("斗士", 0.0, false, 2, rules)
        assertEquals(failedExam.nextSeed, afterFailedExam.seedCheckpoint?.seed)
        assertEquals(1, afterFailedExam.currentLevel)
        assertTrue(afterFailedExam.hasCurrentSeedCheckpoint())

        val afterSwitch = afterExam.recordProfessionChange("斗士", rules)
        assertEquals(exam.nextSeed, afterSwitch.seedCheckpoint?.seed)
        assertEquals("斗士", afterSwitch.seedCheckpoint?.profession)
        assertEquals(1, afterSwitch.currentLevel)
        assertTrue(afterSwitch.hasCurrentSeedCheckpoint())
    }

    @Test fun savedGrowthPlanRoundTripsAndConfirmedStepsAdvanceOrInvalidateSeed() {
        val source = LevelJournal("培养方案", "global", BuddyCampaignLine.KAI, "c044")
        val stats = List(9) { 0 }
        val request = BuddyGrowthPlanRequest(seed = 42, characterId = "c044", line = BuddyCampaignLine.KAI,
            currentLevel = 1, targetLevel = 3, part3StartLevel = null, currentPart3 = true, initialProfession = "平民",
            unlockedProfessions = setOf("平民"), professionWindows = listOf(BuddyProfessionWindow("平民", 1, 2)),
            weights = listOf(0, 5, 0, 0, 0, 0, 0, 0, 0), currentStats = stats, caps = List(9) { null })
        val result = BuddyGrowthPlanner.plan(request, rules)
        val checkpoint = JournalSeedCheckpoint(42, 0, 1, "平民", true, stats, List(9) { null },
            JournalSeedEvidence.MANUAL, source.historyDigest(), unlockedProfessions = setOf("平民"))
        val planned = source.copy(seedCheckpoint = checkpoint, unlockedProfessions = setOf("平民"),
            growthPlan = JournalGrowthPlan(request, result, source.historyDigest()))

        assertTrue(planned.hasCurrentGrowthPlan())
        assertTrue(planned.hasCurrentGrowthPlan(rules))
        assertEquals(planned, LevelJournal.decode(planned.encode()))

        val step = result.steps.first()
        val actual = LevelObservation(level = step.level, profession = step.profession, part3 = true,
            ride = step.ride, gains = step.gains)
        val recorded = planned.recordPlannedUpgrade(actual, rules)
        assertTrue(recorded.hasCurrentSeedCheckpoint())
        assertTrue(recorded.hasCurrentGrowthPlan())
        assertTrue(recorded.hasCurrentGrowthPlan(rules))
        assertEquals(step.nextSeed, recorded.seedCheckpoint?.seed)
        assertEquals(1, recorded.growthPlan?.nextStepIndex)
        assertEquals(2, recorded.currentLevel)

        val mismatch = planned.recordPlannedUpgrade(actual.copy(gains = actual.gains.toMutableList().also {
            it[0] = (it[0] + 1) % 3
        }), rules)
        assertFalse(mismatch.hasCurrentSeedCheckpoint())
        assertFalse(mismatch.hasCurrentGrowthPlan())
        assertEquals(1, mismatch.observations.count { it.levelsUp })

        val unusual = planned.recordPlannedUpgrade(actual.copy(gains = List(9) { 9 }), rules)
        assertEquals(List(9) { 9 }, unusual.observations.single { it.levelsUp }.gains)
        assertFalse(unusual.hasCurrentSeedCheckpoint())
        assertFalse(unusual.hasCurrentGrowthPlan())
        assertThrows(IllegalArgumentException::class.java) {
            planned.recordPlannedUpgrade(actual.copy(gains = List(9) { 10 }), rules)
        }

        val alteredFirst = step.copy(nextSeed = SeedEngine.advance(step.nextSeed))
        val alteredSecond = result.steps[1].copy(seed = alteredFirst.nextSeed)
        val alteredResult = result.copy(steps = listOf(alteredFirst, alteredSecond))
        val tampered = planned.copy(growthPlan = JournalGrowthPlan(request, alteredResult, source.historyDigest()))
        assertTrue(tampered.hasCurrentGrowthPlan())
        assertFalse(tampered.hasCurrentGrowthPlan(rules))
        val tamperedConfirmation = tampered.recordPlannedUpgrade(actual, rules)
        assertFalse(tamperedConfirmation.hasCurrentSeedCheckpoint())
        assertEquals(actual.gains, tamperedConfirmation.observations.single { it.levelsUp }.gains)
    }

    @Test fun laterPlannedProfessionSwitchUsesTheProfessionFromThePreviousStep() {
        val characterId = "c044"
        val eligible = rules.professions.filter {
            !rules.professionIsPart3Only(it.name) && rules.classAllowed(characterId, it.name, false)
        }.map { it.name }.take(3)
        assertEquals(3, eligible.size)
        val (initial, first, second) = eligible
        val source = LevelJournal("多次转职规划", "global", BuddyCampaignLine.KAI, characterId)
        val unlocked = eligible.toSet()
        val request = BuddyGrowthPlanRequest(
            seed = 42,
            characterId = characterId,
            line = BuddyCampaignLine.KAI,
            currentLevel = 1,
            targetLevel = 3,
            part3StartLevel = null,
            initialProfession = initial,
            unlockedProfessions = unlocked,
            professionWindows = listOf(
                BuddyProfessionWindow(first, 1, 1),
                BuddyProfessionWindow(second, 2, 2),
                BuddyProfessionWindow(initial, 99, 99),
            ),
            weights = List(9) { 1 },
            currentStats = List(9) { 0 },
            caps = List(9) { null },
            maxSwitches = 2,
        )
        val result = BuddyGrowthPlanner.plan(request, rules)
        assertEquals(listOf(first, second), result.steps.map { it.profession })
        val checkpoint = JournalSeedCheckpoint(42, 0, 1, initial, false, List(9) { 0 }, List(9) { null },
            JournalSeedEvidence.MANUAL, source.historyDigest(), unlockedProfessions = unlocked)
        var journal = source.copy(seedCheckpoint = checkpoint, unlockedProfessions = unlocked,
            growthPlan = JournalGrowthPlan(request, result, source.historyDigest()))

        result.steps.forEach { step ->
            val requestState = requireNotNull(journal.growthPlan).request
            val currentStats = requireNotNull(journal.seedCheckpoint).stats!!
            val room = requestState.caps.indices.map { index ->
                ((requestState.caps[index] ?: 32_767) - currentStats[index]).coerceAtLeast(0)
            }
            journal = journal.recordPlannedUpgrade(LevelObservation(level = step.level, profession = step.profession,
                part3 = false, ride = step.ride, gains = step.gains, room = room), rules)
            assertTrue(journal.hasCurrentGrowthPlan(rules))
        }

        assertEquals(2, journal.observations.count { it.professionChangeFrom != null })
        assertEquals(second, journal.seedCheckpoint?.profession)
        assertTrue(journal.hasCurrentGrowthPlan())
        assertTrue(journal.hasCurrentGrowthPlan(rules))

        val unperformed = source.copy(seedCheckpoint = checkpoint, unlockedProfessions = unlocked,
            growthPlan = JournalGrowthPlan(request, result, source.historyDigest()))
        val notSwitched = LevelObservation(level = result.steps.first().level, profession = initial, part3 = false,
            gains = result.steps.first().gains)
        val diverged = unperformed.recordPlannedUpgrade(notSwitched, rules)
        assertFalse(diverged.hasCurrentSeedCheckpoint())
        assertFalse(diverged.hasCurrentGrowthPlan())
        assertEquals(initial, diverged.observations.single { it.levelsUp }.profession)
    }

    @Test fun importedRouteConfirmsActualUpgradesAndExamsAndUndoRestoresEachPriorState() {
        val source = importedPlan(listOf(
            JournalImportedGrowthAction("JID_兵士", "士兵", "", ImportedGrowthActionKind.UPGRADE, 0),
            JournalImportedGrowthAction("JID_猟兵", "猎兵", "", ImportedGrowthActionKind.EXAM, 0),
            JournalImportedGrowthAction("JID_猟兵", "猎兵", "", ImportedGrowthActionKind.UPGRADE, 0),
        ))
        assertTrue(source.hasCurrentSeedCheckpoint())
        assertTrue(source.hasCurrentImportedGrowthPlan())
        assertEquals(JournalSeedEvidence.USER_CONFIRMED_IMPORT, source.seedCheckpoint?.evidence)

        val firstGrowth = rules.growth(BuddyGrowthInput("c012", "士兵", 1, false, BuddyRoute.OTHER)).parameters
        val firstResult = SeedEngine.simulateLevel(42, firstGrowth)
        var journal = source.confirmImportedGrowthUpgrade(LevelObservation(level = 1, profession = "士兵",
            part3 = false, gains = firstResult.gains), rules)
        assertEquals(1, journal.importedGrowthPlan?.nextStepIndex)
        assertEquals(firstResult.nextSeed, journal.seedCheckpoint?.seed)
        assertTrue(journal.hasCurrentImportedGrowthPlan())

        journal = journal.confirmImportedGrowthExam(true, rules)
        val afterExamSeed = SeedEngine.advance(firstResult.nextSeed)
        assertEquals(afterExamSeed, journal.seedCheckpoint?.seed)
        assertEquals(2, journal.currentLevel)
        assertTrue("猎兵" in journal.confirmedUnlockedProfessions())

        val secondGrowth = rules.growth(BuddyGrowthInput("c012", "猎兵", 2, false, BuddyRoute.OTHER)).parameters
        val secondResult = SeedEngine.simulateLevel(afterExamSeed, secondGrowth)
        journal = journal.confirmImportedGrowthUpgrade(LevelObservation(level = 2, profession = "猎兵",
            part3 = false, gains = secondResult.gains), rules)
        assertEquals(3, journal.importedGrowthPlan?.nextStepIndex)
        assertEquals(3, journal.currentLevel)
        assertEquals("猎兵", journal.currentProfession)
        assertTrue(journal.hasCurrentSeedCheckpoint())
        assertEquals(4, journal.observations.size)

        journal = journal.undoLatestImportedGrowthAction()
        assertEquals(2, journal.importedGrowthPlan?.nextStepIndex)
        assertEquals(2, journal.observations.size)
        assertEquals(2, journal.currentLevel)
        assertEquals("士兵", journal.currentProfession)
        assertEquals(afterExamSeed, journal.seedCheckpoint?.seed)
        assertTrue("猎兵" in journal.confirmedUnlockedProfessions())

        journal = journal.undoLatestImportedGrowthAction()
        assertEquals(1, journal.importedGrowthPlan?.nextStepIndex)
        assertEquals(1, journal.observations.size)
        assertEquals(firstResult.nextSeed, journal.seedCheckpoint?.seed)
        assertFalse("猎兵" in journal.confirmedUnlockedProfessions())

        journal = journal.undoLatestImportedGrowthAction()
        assertEquals(0, journal.importedGrowthPlan?.nextStepIndex)
        assertTrue(journal.observations.isEmpty())
        assertEquals(42L, journal.seedCheckpoint?.seed)
        assertEquals(JournalSeedEvidence.USER_CONFIRMED_IMPORT, journal.seedCheckpoint?.evidence)
        assertTrue(journal.hasCurrentImportedGrowthPlan())
        assertEquals(journal, LevelJournal.decode(journal.encode()))
    }

    @Test fun failedImportedExamAdvancesSeedWithoutLevelOrUnlockAndCanBeUndone() {
        val source = importedPlan(listOf(
            JournalImportedGrowthAction("JID_猟兵", "猎兵", "", ImportedGrowthActionKind.EXAM, 0),
            JournalImportedGrowthAction("JID_兵士", "士兵", "", ImportedGrowthActionKind.UPGRADE, 0),
        ))
        val failed = source.confirmImportedGrowthExam(false, rules)
        assertEquals(SeedEngine.advance(42), failed.seedCheckpoint?.seed)
        assertEquals(1, failed.currentLevel)
        assertFalse("猎兵" in failed.confirmedUnlockedProfessions())
        assertNull(failed.observations.single().exam?.probability)
        assertNull(failed.observations.single().exam?.resourceCost)
        assertFalse(failed.observations.single().exam!!.passed)

        val restored = failed.undoLatestImportedGrowthAction()
        assertEquals(42L, restored.seedCheckpoint?.seed)
        assertEquals(1, restored.currentLevel)
        assertTrue(restored.observations.isEmpty())
        assertFalse("猎兵" in restored.confirmedUnlockedProfessions())
    }

    @Test fun mismatchedImportedUpgradeIsRetainedButDoesNotInventASeedAndRouteCanContinue() {
        val source = importedPlan(List(2) {
            JournalImportedGrowthAction("JID_兵士", "士兵", "", ImportedGrowthActionKind.UPGRADE, 0)
        })
        val expected = SeedEngine.simulateLevel(42,
            rules.growth(BuddyGrowthInput("c012", "士兵", 1, false, BuddyRoute.OTHER)).parameters)
        val actualGains = expected.gains.toMutableList().also { it[0] = (it[0] + 1) % 10 }
        var journal = source.confirmImportedGrowthUpgrade(LevelObservation(level = 1, profession = "士兵",
            part3 = false, gains = actualGains), rules)
        assertEquals(actualGains, journal.observations.single().gains)
        assertEquals(1, journal.importedGrowthPlan?.nextStepIndex)
        assertNull(journal.seedCheckpoint)
        assertNull(journal.importedGrowthPlan?.currentSeed())
        assertTrue(journal.hasCurrentImportedGrowthPlan())

        journal = journal.confirmImportedGrowthUpgrade(LevelObservation(level = 2, profession = "士兵",
            part3 = false, gains = List(9) { 0 }), rules)
        assertEquals(2, journal.importedGrowthPlan?.nextStepIndex)
        assertEquals(3, journal.currentLevel)
        assertNull(journal.seedCheckpoint)
        assertTrue(journal.hasCurrentImportedGrowthPlan())
    }

    @Test fun confirmedUnlockChangesRemainPartOfImportedRouteAndExternalEditsDisableUndo() {
        val source = importedPlan(listOf(JournalImportedGrowthAction("JID_猟兵", "猎兵", "",
            ImportedGrowthActionKind.UPGRADE, 0)))
        val unlocked = source.withConfirmedUnlockedProfessions(setOf("士兵", "猎兵"))
        assertTrue(unlocked.hasCurrentImportedGrowthPlan())
        val growth = SeedEngine.simulateLevel(42,
            rules.growth(BuddyGrowthInput("c012", "猎兵", 1, false, BuddyRoute.OTHER)).parameters)
        val recorded = unlocked.confirmImportedGrowthUpgrade(LevelObservation(level = 1, profession = "猎兵",
            part3 = false, gains = growth.gains), rules)
        val edited = recorded.copy(observations = recorded.observations.map { row ->
            if (row.levelsUp) row.copy(gains = List(9) { 0 }) else row
        })
        assertFalse(edited.hasCurrentImportedGrowthPlan())
        assertThrows(IllegalArgumentException::class.java) { edited.undoLatestImportedGrowthAction() }
    }

    @Test fun cappedStatsAndPlusTwoSurviveStorageAndEnterEngineUnchanged() {
        val row = LevelObservation(level = 1, profession = "平民", part3 = false,
            gains = listOf(0, 2, 1, 0, 0, 0, 0, 0, 0), room = listOf(0, 2, 1, 32767, 32767, 32767, 32767, 32767, 32767))
        val journal = journal().copy(observations = listOf(row))
        assertEquals(journal, LevelJournal.decode(journal.encode()))
        assertEquals(ObservedSeedEvent.Level(rules.growth(BuddyGrowthInput("c044", "平民", 1, false, BuddyRoute.KAI)).parameters.copy(room = row.room), row.gains),
            journal.history(rules).events.single())
    }

    @Test fun independentRoutesSurvivePersonalBackupAndConcurrentEditsStayConflicted() {
        val device = UUID.randomUUID().toString()
        var snapshot = PersonalSnapshot(emptyList())
        BuddyCampaignLine.entries.forEach { line -> snapshot = snapshot.edit(UUID.randomUUID().toString(), LevelJournal.KIND, device, journal(line).encode()) }
        val restored = PersonalSnapshot.decode(snapshot.encode())
        val password = "journal-test-passphrase".toCharArray()
        assertEquals(restored, EncryptedBackup.decrypt(EncryptedBackup.encrypt(restored, password), password))
        assertEquals(BuddyCampaignLine.entries.toSet(), restored.heads().values.map { LevelJournal.decode(it.single().payload).line }.toSet())
        val selected = restored.revisions.first()
        val changed = restored.edit(selected.entityId, LevelJournal.KIND, device, journal().copy(name = "手机修改").encode())
        val other = restored.edit(selected.entityId, LevelJournal.KIND, UUID.randomUUID().toString(), journal().copy(name = "平板修改").encode())
        assertEquals(2, changed.merge(other).heads().getValue(selected.entityId).size)
        assertThrows(IllegalArgumentException::class.java) {
            changed.edit(selected.entityId, LevelJournal.KIND, device, journal().encode(), expectedParents = setOf(selected.revisionId))
        }
    }
}

private fun legacyJournalPayload(journal: LevelJournal, version: Int): JsonObject = buildJsonObject {
    require(version in 1..2)
    put("version", version)
    put("name", journal.name)
    put("profileId", journal.profile)
    put("line", journal.line.id)
    put("characterId", journal.characterId)
    put("ruleRevision", journal.ruleRevision)
    put("observations", JsonArray(journal.observations.map { row -> buildJsonObject {
        put("id", row.id)
        put("level", row.level)
        put("profession", row.profession)
        put("part3", row.part3)
        put("skipped", row.skipped)
        put("gains", JsonArray(row.gains.map(::JsonPrimitive)))
        put("room", JsonArray(row.room.map(::JsonPrimitive)))
        put("ride", row.ride?.let { ride -> buildJsonObject {
            put("id", ride.id)
            put("level", ride.level)
        } } ?: JsonNull)
        if (version >= 2) {
            put("exam", row.exam?.let { exam -> buildJsonObject {
                put("targetProfession", exam.targetProfession)
                put("probability", exam.probability)
                put("passed", exam.passed)
                put("resourceCost", exam.resourceCost)
            } } ?: JsonNull)
            put("professionChangeFrom", row.professionChangeFrom?.let(::JsonPrimitive) ?: JsonNull)
        }
    } }))
    if (version >= 2) {
        put("seedCheckpoint", journal.seedCheckpoint?.let { checkpoint -> buildJsonObject {
            put("seed", checkpoint.seed)
            put("eventIndex", checkpoint.eventIndex)
            put("level", checkpoint.level)
            put("profession", checkpoint.profession)
            put("part3", checkpoint.part3)
            put("stats", checkpoint.stats?.let { JsonArray(it.map(::JsonPrimitive)) } ?: JsonNull)
            put("caps", checkpoint.caps?.let { caps -> JsonArray(caps.map { it?.let(::JsonPrimitive) ?: JsonNull }) } ?: JsonNull)
            put("unlockedProfessions", JsonArray(checkpoint.unlockedProfessions.sorted().map(::JsonPrimitive)))
            put("evidence", checkpoint.evidence.id)
            put("historyDigest", checkpoint.historyDigest)
            put("ruleRevision", checkpoint.ruleRevision)
            put("sourceSha256", checkpoint.sourceSha256?.let(::JsonPrimitive) ?: JsonNull)
            put("originSeed", checkpoint.originSeed?.let(::JsonPrimitive) ?: JsonNull)
            put("matchCount", checkpoint.matchCount?.let(::JsonPrimitive) ?: JsonNull)
        } } ?: JsonNull)
        put("unlockedProfessions", JsonArray(journal.unlockedProfessions.sorted().map(::JsonPrimitive)))
    }
}
