package com.fireemblem.companion.importer

import com.fireemblem.companion.engine.BuddyCampaignLine
import com.fireemblem.companion.engine.BuddyGrowthInput
import com.fireemblem.companion.engine.BuddyRoute
import com.fireemblem.companion.engine.BuddyRules
import com.fireemblem.companion.engine.SeedEngine
import com.fireemblem.companion.engine.LevelJournal
import com.fireemblem.companion.engine.ObservedSeedEvent
import com.fireemblem.companion.engine.EventMatchResult
import com.fireemblem.companion.engine.JournalSeedEvidence
import com.fireemblem.companion.engine.BuddyGrowthPlanner
import com.fireemblem.companion.engine.BuddyGrowthPlanRequest
import com.fireemblem.companion.engine.BuddyProfessionWindow
import com.fireemblem.companion.engine.JournalGrowthPlan
import com.fireemblem.companion.engine.LevelObservation
import com.fireemblem.companion.engine.levelsUp
import java.security.MessageDigest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class FefwGrowthSeedImporterTest {
    private val rules = BuddyRules.bundled()
    private val fixture = Json.parseToJsonElement("""
        {"pid":"PID_セオドラ","job":"JID_兵士","part":"0","level":10,"currentLevel":15,
         "observations":[[1,0,1,0,0,0,0,1,0],[1,1,1,0,0,0,0,0,1],[1,0,1,0,0,0,1,1,1],
                         [1,1,0,0,1,1,1,0,0],[1,0,1,0,1,0,1,1,0]],
         "observationRecord":{"version":1,"choices":[{"job":null,"mountId":""},{"job":null,"mountId":""},
             {"job":null,"mountId":""},{"job":null,"mountId":""},{"job":null,"mountId":""}],
             "counts":[0,0,0,0,0],"details":[[],[],[],[],[]],"trailing":[]},
         "passive":true,"currentStats":[30,6,6,6,6,6,7,5,8],"caps":[null,null,null,null,null,null,null,null,null],
         "mountId":"","obsMounts":["","","","",""],"minimum":2,"rates":[80,55,30,45,35,30,50,30,50],
         "dead":[false,false,false,false,false,false,false,false,false],"batches":"",
         "candidate":2141258584,"current":759314053,"fullSearch":true,"matchCount":1,"routeType":"AI 推演 2"}
    """).jsonObject

    private fun archive(record: JsonElement = fixture): ByteArray = buildJsonObject {
        put("format", "fefw-growth-seed"); put("version", 1); put("records", JsonArray(listOf(record)))
    }.toString().toByteArray(Charsets.UTF_8)

    private fun inspect(record: JsonElement = fixture): FefwGrowthSeedRecordPreview =
        FefwGrowthSeedImporter.inspect(archive(record), rules).entries.single()

    private fun changed(vararg values: Pair<String, JsonElement>): JsonObject = JsonObject(fixture + values)

    private fun changedObservationRecord(key: String, value: JsonElement): JsonObject =
        changed("observationRecord" to JsonObject(fixture.getValue("observationRecord").jsonObject + (key to value)))

    private fun rejected(record: JsonElement): FefwGrowthSeedRecordPreview = inspect(record).also {
        assertFalse(it.errors.toString(), it.ready)
        assertFalse(it.candidateReplayVerified)
        assertThrows(IllegalArgumentException::class.java) { it.toJournal("导入", "profile", BuddyCampaignLine.SEO) }
    }

    @Test fun replaysFiveTheodoraUpgradesAndHashesOriginalBytes() {
        val bytes = archive()
        val preview = FefwGrowthSeedImporter.inspect(bytes, rules)
        val entry = preview.entries.single()
        assertTrue(entry.errors.toString(), entry.ready)
        assertTrue(entry.candidateReplayVerified)
        assertEquals(0, entry.index)
        assertEquals("c012", entry.characterId)
        assertEquals("赛奥朵拉", entry.characterName)
        assertEquals("士兵", entry.profession)
        assertFalse(entry.part3)
        assertEquals(10, entry.startLevel)
        assertEquals(15, entry.currentLevel)
        assertEquals(2_141_258_584L, entry.candidateSeed)
        assertEquals(759_314_053L, entry.currentSeed)
        assertEquals(listOf(30, 6, 6, 6, 6, 6, 7, 5, 8), entry.currentStats)
        assertEquals(List<Int?>(9) { null }, entry.caps)
        assertEquals((10..14).toList(), entry.observations.map { it.level })
        assertEquals(fixture.getValue("observations").jsonArray.map { row -> row.jsonArray.map { it.jsonPrimitive.int } }, entry.observations.map { it.gains })
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals(digest, preview.sourceSha256)
        assertEquals(bytes.toList(), preview.sourceArchive.restoreBytes().toList())
        assertNotEquals(preview.sourceSha256, FefwGrowthSeedImporter.inspect(bytes + byteArrayOf(32), rules).sourceSha256)
        assertTrue(entry.warnings.any { "全局唯一性未在本地复核" in it })
        assertTrue(entry.toJournal("导入", "profile", BuddyCampaignLine.SEO).history(rules).ready)
    }

    @Test fun sourceArchiveRejectsTamperingAndFilesBeyondTheImportLimit() {
        val bytes = archive() + " \n".toByteArray(Charsets.UTF_8)
        val source = FefwGrowthSeedSourceArchive.fromBytes(bytes)
        assertEquals(bytes.toList(), source.restoreBytes().toList())
        val tamperedPayload = JsonObject(source.toPayload() + ("dataBase64" to JsonPrimitive(source.dataBase64.dropLast(4) + "AAAA")))
        assertThrows(IllegalArgumentException::class.java) { FefwGrowthSeedSourceArchive.fromPayload(tamperedPayload) }
        assertThrows(IllegalArgumentException::class.java) {
            FefwGrowthSeedSourceArchive.fromBytes(ByteArray(FefwGrowthSeedImporter.MAX_BYTES + 1))
        }
    }

    @Test fun sourceRouteLabelsNeverSelectTheCampaignLine() {
        val entry = inspect(changed("routeType" to JsonPrimitive("凯伊篇 / AI 推演 2")))
        BuddyCampaignLine.entries.forEach { line ->
            val journal = entry.toJournal("导入", "profile", line)
            assertEquals(line, journal.line)
            assertEquals(entry.observations, journal.observations)
        }
    }

    @Test fun selectedCampaignImportReplaysAndCreatesOnlyImportedEvidence() {
        val preview = FefwGrowthSeedImporter.inspect(archive(), rules)
        val entry = preview.entries.single()
        BuddyCampaignLine.entries.forEach { line ->
            val journal = entry.toJournal("可继续培养", "global", line, preview.sourceSha256, rules)
            assertTrue(journal.hasCurrentSeedCheckpoint())
            val checkpoint = requireNotNull(journal.seedCheckpoint)
            assertEquals(JournalSeedEvidence.IMPORTED_REPLAY, checkpoint.evidence)
            assertEquals(759_314_053L, checkpoint.seed)
            assertEquals(2_141_258_584L, checkpoint.originSeed)
            assertEquals(preview.sourceSha256, checkpoint.sourceSha256)
            assertEquals(5, checkpoint.eventIndex)
            assertEquals(15, checkpoint.level)
            assertNull(checkpoint.stats)
            assertNull(checkpoint.matchCount)
            assertEquals(journal, LevelJournal.decode(journal.encode()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            entry.copy(currentSeed = 1).toJournal("不一致种子", "global", BuddyCampaignLine.SEO, preview.sourceSha256, rules)
        }
        assertThrows(IllegalArgumentException::class.java) {
            entry.copy(candidateSeed = 1).toJournal("错误起点", "global", BuddyCampaignLine.SEO, preview.sourceSha256, rules)
        }
        assertNull(inspect(changed("candidate" to JsonNull)).toJournal("无种子", "global",
            BuddyCampaignLine.SEO, preview.sourceSha256, rules).seedCheckpoint)
    }

    @Test fun importedSeedContinuesThroughAConfirmedWeightedPlanWithoutTurningPredictionsIntoObservations() {
        val preview = FefwGrowthSeedImporter.inspect(archive(), rules)
        val imported = preview.entries.single().toJournal("联动培养", "global", BuddyCampaignLine.SEO, preview.sourceSha256, rules)
        val checkpoint = requireNotNull(imported.seedCheckpoint)
        val confirmedStats = List(9) { 20 }
        val unlocked = imported.confirmedUnlockedProfessions()
        val request = BuddyGrowthPlanRequest(checkpoint.seed, imported.characterId, imported.line, checkpoint.level,
            checkpoint.level + 2, part3StartLevel = null, initialProfession = checkpoint.profession,
            unlockedProfessions = unlocked, professionWindows = listOf(BuddyProfessionWindow(checkpoint.profession, 15, 16)),
            weights = listOf(1, 10, 1, 1, 1, 1, 1, 1, 1), currentStats = confirmedStats, caps = List(9) { null })
        val plan = JournalGrowthPlan(request, BuddyGrowthPlanner.plan(request, rules), imported.historyDigest())
        val planned = imported.copy(seedCheckpoint = checkpoint.copy(stats = confirmedStats), growthPlan = plan)
        assertTrue(planned.hasCurrentGrowthPlan(rules))
        assertEquals(5, planned.observations.size)
        val step = plan.result.steps.first()
        val recorded = planned.recordPlannedUpgrade(LevelObservation(level = step.level, profession = step.profession,
            part3 = false, gains = step.gains, room = confirmedStats.map { 32_767 - it }), rules)
        assertEquals(6, recorded.observations.size)
        assertEquals(16, recorded.currentLevel)
        assertEquals(1, recorded.growthPlan?.nextStepIndex)
        assertEquals(JournalSeedEvidence.PLAN_CONFIRMED, recorded.seedCheckpoint?.evidence)
        assertTrue(recorded.hasCurrentGrowthPlan(rules))
        val changed = recorded.copy(observations = recorded.observations.mapIndexed { index, row ->
            if (index == 0) row.copy(gains = List(9) { 0 }) else row
        })
        assertFalse(changed.hasCurrentSeedCheckpoint())
        assertFalse(changed.hasCurrentGrowthPlan())
    }

    @Test fun fullSearchAndMatchCountNeverEstablishOrSuppressReplayVerification() {
        val metadata = listOf(
            listOf("fullSearch" to JsonPrimitive(false), "matchCount" to JsonPrimitive(200)),
            listOf("fullSearch" to JsonNull, "matchCount" to JsonNull),
            listOf("fullSearch" to JsonPrimitive("invalid"), "matchCount" to JsonPrimitive(-1)),
        )
        metadata.forEach { assertTrue(inspect(JsonObject(fixture + it)).candidateReplayVerified) }
        val missingCandidate = inspect(changed("candidate" to JsonNull))
        assertTrue(missingCandidate.ready)
        assertFalse(missingCandidate.candidateReplayVerified)
        val missingCurrent = inspect(changed("current" to JsonNull))
        assertTrue(missingCurrent.ready)
        assertFalse(missingCurrent.candidateReplayVerified)
    }

    @Test fun rejectsWrongCandidateWrongCurrentAndAlteredGains() {
        rejected(changed("candidate" to JsonPrimitive(1))).also { assertTrue(it.errors.any { "candidate" in it }) }
        rejected(changed("current" to JsonPrimitive(1))).also { assertTrue(it.errors.any { "current" in it }) }
        val rows = fixture.getValue("observations").jsonArray.toMutableList()
        rows[0] = JsonArray(List(9) { JsonPrimitive(0) })
        rejected(changed("observations" to JsonArray(rows)))
    }

    @Test fun preservesHighUint32AndRejectsInvalidSeedRepresentations() {
        var seed = 0xffff_ffffL
        val rows = (10..14).map { level ->
            val result = SeedEngine.simulateLevel(seed, rules.growth(BuddyGrowthInput("c012", "士兵", level, false, BuddyRoute.OTHER)).parameters)
            seed = result.nextSeed
            JsonArray(result.gains.map(::JsonPrimitive))
        }
        val entry = inspect(changed("candidate" to JsonPrimitive(0xffff_ffffL), "current" to JsonPrimitive(seed), "observations" to JsonArray(rows)))
        assertTrue(entry.errors.toString(), entry.candidateReplayVerified)
        assertEquals(0xffff_ffffL, entry.candidateSeed)
        listOf(JsonPrimitive(-1), JsonPrimitive(0x1_0000_0000L), JsonPrimitive("2141258584"), JsonPrimitive(1.5)).forEach { invalid ->
            rejected(changed("candidate" to invalid))
            rejected(changed("current" to invalid))
        }
    }

    @Test fun rejectsUnknownPersonAndJobIdsWithoutNameGuessing() {
        rejected(changed("pid" to JsonPrimitive("赛奥朵拉")))
        rejected(changed("pid" to JsonPrimitive("PID_unknown")))
        rejected(changed("job" to JsonPrimitive("士兵")))
        rejected(changed("job" to JsonPrimitive("JID_unknown")))
    }

    @Test fun resolvesSharedBaseJobThroughVerifiedCharacterRules() {
        val entry = inspect(changed("job" to JsonPrimitive("JID_無職"), "candidate" to JsonNull,
            "current" to JsonNull, "rates" to JsonArray(rules.growth(BuddyGrowthInput("c012", "贵族", 10, false, BuddyRoute.OTHER)).parameters.growth.map(::JsonPrimitive))))
        assertTrue(entry.errors.toString(), entry.ready)
        assertEquals("贵族", entry.profession)
    }

    @Test fun usesExplicitPerUpgradeJobsAndInheritsEmptyChoices() {
        val inherited = inspect(changedObservationRecord("choices", JsonArray(emptyList())))
        assertTrue(inherited.candidateReplayVerified)
        val choices = JsonArray(listOf(buildJsonObject { put("job", "JID_猟兵"); put("mountId", "") }) + List(4) { JsonObject(emptyMap()) })
        val entry = inspect(JsonObject(changedObservationRecord("choices", choices) + mapOf("candidate" to JsonNull, "current" to JsonNull)))
        assertTrue(entry.errors.toString(), entry.ready)
        assertEquals(listOf("猎兵", "士兵", "士兵", "士兵", "士兵"), entry.observations.map { it.profession })
    }

    @Test fun rejectsUnsupportedExtraEventsAndMisalignedChoices() {
        rejected(changedObservationRecord("counts", JsonArray(listOf(JsonPrimitive(1)) + List(4) { JsonPrimitive(0) })))
        rejected(changedObservationRecord("details", JsonArray(listOf(JsonArray(listOf(JsonPrimitive("exam")))) + List(4) { JsonArray(emptyList()) })))
        rejected(changedObservationRecord("trailing", JsonArray(listOf(JsonPrimitive("exam")))))
        rejected(changedObservationRecord("counts", JsonArray(emptyList())))
        rejected(changedObservationRecord("details", JsonArray(emptyList())))
        rejected(changedObservationRecord("choices", JsonArray(listOf(JsonObject(emptyMap())))))
        rejected(changedObservationRecord("version", JsonPrimitive(2)))
        rejected(changed("batches" to JsonPrimitive("1")))
    }

    @Test fun importsBatchedGrowthAtTheOfficialBatchEndLevels() {
        val sourceSeed = 0xffff_ffffL
        val batchGrowthLevels = listOf(11, 11, 14, 14, 14)
        var currentSeed = sourceSeed
        val rows = (10..14).mapIndexed { index, _ ->
            val parameters = rules.growth(BuddyGrowthInput("c012", "士兵", batchGrowthLevels[index],
                false, BuddyRoute.OTHER)).parameters
            val result = SeedEngine.simulateLevel(currentSeed, parameters)
            currentSeed = result.nextSeed
            JsonArray(result.gains.map(::JsonPrimitive))
        }
        val entry = inspect(changed("observations" to JsonArray(rows), "batches" to JsonPrimitive("2, 3"),
            "candidate" to JsonPrimitive(sourceSeed), "current" to JsonPrimitive(currentSeed)))
        assertTrue(entry.errors.toString(), entry.candidateReplayVerified)
        assertEquals((10..14).toList(), entry.observations.filter { it.levelsUp }.map { it.level })
        assertEquals(batchGrowthLevels, entry.observations.filter { it.levelsUp }
            .map { it.growthLevel ?: it.level })
        val journal = entry.toJournal("批次导入", "profile", BuddyCampaignLine.SEO)
        assertEquals(15, journal.currentLevel)
        assertEquals(journal, LevelJournal.decode(journal.encode()))
        val history = journal.history(rules)
        assertTrue(history.errors.toString(), history.errors.isEmpty())
        assertEquals(5, history.observedLevels)
        var replaySeed = sourceSeed
        history.events.forEach { event ->
            replaySeed = (SeedEngine.matchEvent(replaySeed, event) as EventMatchResult.Matched).nextSeed
        }
        assertEquals(currentSeed, replaySeed)
        assertTrue(entry.warnings.any { it.contains("批次末级") })
    }

    @Test fun batchGrowthUsesEndLevelsAcrossThresholdsAndAcceptsOfficialSeparators() {
        listOf(34, 44).forEach { startLevel ->
            val batchEndLevels = listOf(startLevel + 1, startLevel + 1, startLevel + 4, startLevel + 4, startLevel + 4)
            val sourceSeed = 0xffff_fffeL
            var seed = sourceSeed
            val rows = batchEndLevels.map { growthLevel ->
                val result = SeedEngine.simulateLevel(seed,
                    rules.growth(BuddyGrowthInput("c012", "士兵", growthLevel, false, BuddyRoute.OTHER)).parameters)
                seed = result.nextSeed
                JsonArray(result.gains.map(::JsonPrimitive))
            }
            listOf("2,3", "2，3", "2 3", "2\n3").forEach { batchText ->
                val entry = inspect(changed("level" to JsonPrimitive(startLevel), "currentLevel" to JsonPrimitive(startLevel + 5),
                    "batches" to JsonPrimitive(batchText), "observations" to JsonArray(rows),
                    "candidate" to JsonPrimitive(sourceSeed), "current" to JsonPrimitive(seed),
                    "rates" to JsonArray(rules.growth(BuddyGrowthInput("c012", "士兵", startLevel,
                        false, BuddyRoute.OTHER)).parameters.growth.map(::JsonPrimitive))))
                assertTrue(entry.errors.toString(), entry.candidateReplayVerified)
                val history = entry.toJournal("跨门槛批次", "global", BuddyCampaignLine.SEO).history(rules)
                assertTrue(history.errors.toString(), history.ready)
                val result = SeedEngine.searchRange(history.events, sourceSeed, sourceSeed + 1)
                assertEquals(seed, result.candidates.single().currentSeed)
            }
        }
        assertTrue(inspect(changed("batches" to JsonPrimitive("1,1,1,1,1"))).observations.all { it.growthLevel == null })
    }

    @Test fun rejectsBatchCountsThatAreNotPositiveOrDoNotMatchObservationCount() {
        listOf("0,5", "2,2", "6", "one", "-1,6", "1.5,3.5").forEach { batch ->
            val entry = rejected(changed("batches" to JsonPrimitive(batch)))
            assertTrue(entry.errors.toString(), entry.errors.any { "batches" in it })
        }
        rejected(changed("batches" to JsonPrimitive(5)))
    }

    @Test fun rejectsJobChangesAndExamsInsideOneSettlementBatch() {
        val examDetails = JsonArray(listOf(
            JsonArray(emptyList()),
            JsonArray(listOf(buildJsonObject { put("success", false) })),
            JsonArray(emptyList()),
            JsonArray(emptyList()),
            JsonArray(emptyList()),
        ))
        val counts = JsonArray(listOf(0, 1, 0, 0, 0).map(::JsonPrimitive))
        val observationRecord = JsonObject(fixture.getValue("observationRecord").jsonObject + mapOf(
            "counts" to counts, "details" to examDetails,
        ))
        assertTrue(rejected(changed("batches" to JsonPrimitive("2,3"), "observationRecord" to observationRecord))
            .errors.any { "同次结算不能参加考试" in it })

        val choices = JsonArray((0 until 5).map { index -> buildJsonObject {
            put("job", if (index == 1) JsonPrimitive("JID_猟兵") else JsonNull)
            put("mountId", "")
        } })
        val changedRecord = JsonObject(fixture.getValue("observationRecord").jsonObject + ("choices" to choices))
        assertTrue(rejected(changed("batches" to JsonPrimitive("2,3"), "observationRecord" to changedRecord))
            .errors.any { "同次结算不能更换职业" in it })
    }

    @Test fun preservesWebsiteObservationValuesThroughNine() {
        val rows = fixture.getValue("observations").jsonArray.toMutableList()
        rows[0] = JsonArray(List(9) { index -> JsonPrimitive(if (index == 0) 9 else 0) })
        val entry = inspect(changed("observations" to JsonArray(rows), "candidate" to JsonNull, "current" to JsonNull))
        assertTrue(entry.errors.toString(), entry.ready)
        assertEquals(9, entry.observations.first().gains.first())
        assertEquals(9, entry.toJournal("高提升观测", "profile", BuddyCampaignLine.SEO).observations.first().gains.first())
        val outOfRange = rows.toMutableList().apply { this[0] = JsonArray(List(9) { JsonPrimitive(10) }) }
        rejected(changed("observations" to JsonArray(outOfRange)))
    }

    @Test fun importsExamsBeforeUpgradesAndAfterTheFinalUpgradeWithoutInventingProbabilities() {
        var seed = 0xffff_ffffL
        val counts = listOf(1, 0, 1, 0, 0)
        val rows = (10..14).mapIndexed { index, level ->
            repeat(counts[index]) { seed = SeedEngine.advance(seed) }
            val result = SeedEngine.simulateLevel(seed,
                rules.growth(BuddyGrowthInput("c012", "士兵", level, false, BuddyRoute.OTHER)).parameters)
            seed = result.nextSeed
            JsonArray(result.gains.map(::JsonPrimitive))
        }
        seed = SeedEngine.advance(seed)
        val before = buildJsonObject { put("family", "JID_猟兵"); put("success", true) }
        val failed = buildJsonObject { put("success", false) }
        val trailing = buildJsonObject {
            put("family", JsonNull); put("success", true)
            put("skippedUpgrade", fixture.getValue("observations").jsonArray.first())
        }
        val record = JsonObject(fixture.getValue("observationRecord").jsonObject + mapOf(
            "counts" to JsonArray(counts.map(::JsonPrimitive)),
            "details" to JsonArray(listOf(JsonArray(listOf(before)), JsonArray(emptyList()),
                JsonArray(listOf(failed)), JsonArray(emptyList()), JsonArray(emptyList()))),
            "trailing" to JsonArray(listOf(trailing)),
        ))
        val entry = inspect(changed("observations" to JsonArray(rows), "observationRecord" to record,
            "candidate" to JsonPrimitive(0xffff_ffffL), "current" to JsonPrimitive(seed),
            "examChance" to JsonPrimitive(100)))
        assertTrue(entry.errors.toString(), entry.candidateReplayVerified)
        assertEquals(listOf(10, 10, 11, 12, 12, 13, 14, 15), entry.observations.map { it.level })
        assertEquals(5, entry.observations.count { it.levelsUp })
        val exams = entry.observations.mapNotNull { it.exam }
        assertEquals(listOf(true, false, true), exams.map { it.passed })
        assertEquals(listOf("猎兵", "", ""), exams.map { it.targetProfession })
        assertTrue(exams.all { it.probability == null && it.resourceCost == null })
        val journal = entry.toJournal("含考试的记录", "profile", BuddyCampaignLine.SEO)
        assertEquals(journal, LevelJournal.decode(journal.encode()))
        assertEquals(15, journal.currentLevel)
        assertEquals(setOf("士兵", "猎兵"), journal.confirmedUnlockedProfessions())
        val history = journal.history(rules)
        assertTrue(history.errors.toString(), history.ready)
        assertEquals(3, history.events.count { it is ObservedSeedEvent.Advance })
        var replaySeed = requireNotNull(entry.candidateSeed)
        history.events.forEach { event ->
            replaySeed = (SeedEngine.matchEvent(replaySeed, event) as EventMatchResult.Matched).nextSeed
        }
        assertEquals(entry.currentSeed, replaySeed)
    }

    @Test fun rejectsIncompleteUnknownOrOversizedExamRecords() {
        val invalid = listOf(JsonObject(emptyMap()), buildJsonObject { put("success", JsonNull) },
            buildJsonObject { put("success", "true") },
            buildJsonObject { put("success", true); put("family", "JID_unknown") },
            buildJsonObject { put("success", true); put("probability", 90) })
        invalid.forEach { exam -> rejected(changedObservationRecord("trailing", JsonArray(listOf(exam)))) }
        val exam = buildJsonObject { put("success", true) }
        rejected(changedObservationRecord("trailing", JsonArray(List(996) { exam })))
    }

    @Test fun refusesUnsupportedMountAndFriendshipInformation() {
        val mounts = JsonArray(listOf(JsonPrimitive("RID_馬")) + List(4) { JsonPrimitive("") })
        rejected(changed("obsMounts" to mounts))
        rejected(changed("mountId" to JsonPrimitive("RID_馬")))
        rejected(changed("friendship" to JsonPrimitive(3)))
        listOf(buildJsonObject { put("job", "JID_兵士"); put("mountId", "RID_馬") },
            buildJsonObject { put("job", "JID_兵士"); put("friendship", 3) }).forEach { choice ->
            rejected(changedObservationRecord("choices", JsonArray(listOf(choice) + List(4) { JsonObject(emptyMap()) })))
        }
    }

    @Test fun validatesNineIntegerObservationVectors() {
        listOf(JsonArray(List(8) { JsonPrimitive(1) }), JsonArray(List(10) { JsonPrimitive(1) }),
            JsonArray(List(9) { JsonPrimitive(3) }), JsonArray(List(9) { JsonPrimitive(-1) }),
            JsonArray(List(9) { JsonPrimitive(1.5) }), JsonArray(List(9) { JsonPrimitive("1") })).forEach { row ->
            rejected(changed("observations" to JsonArray(listOf(row) + fixture.getValue("observations").jsonArray.drop(1))))
        }
    }

    @Test fun malformedStatsAndCapsRemainNullWithDiagnostics() {
        listOf(JsonPrimitive(1), JsonArray(List(8) { JsonPrimitive(0) }), JsonArray(List(9) { JsonPrimitive(-1) }),
            JsonArray(List(9) { JsonPrimitive(32768) }), JsonArray(List(9) { JsonPrimitive("1") })).forEach { invalid ->
            val stats = rejected(changed("currentStats" to invalid))
            assertNull(stats.currentStats)
            assertTrue(stats.errors.any { "currentStats" in it })
            val caps = rejected(changed("caps" to invalid))
            assertEquals(List<Int?>(9) { null }, caps.caps)
            assertTrue(caps.errors.any { "caps" in it })
        }
        val finiteCaps = rejected(changed("caps" to JsonArray(List(9) { JsonPrimitive(99) })))
        assertEquals(List(9) { 99 }, finiteCaps.caps)
    }

    @Test fun predictionsCursorsAndRevisionHistoriesAreNeverObservedEvents() {
        val unrelated = Json.parseToJsonElement("""[{"observations":[[2,2,2,2,2,2,2,2,2]],"candidate":99,"currentLevel":99}]""")
        val entry = inspect(changed("prediction" to unrelated, "plan" to unrelated, "routes" to unrelated,
            "revisions" to unrelated, "position" to JsonPrimitive(99), "recordPosition" to JsonPrimitive(99)))
        assertTrue(entry.errors.toString(), entry.candidateReplayVerified)
        assertEquals(5, entry.toJournal("导入", "profile", BuddyCampaignLine.SEO).observations.size)
        assertEquals(759_314_053L, entry.currentSeed)
    }

    @Test fun rejectsMalformedUtf8JsonVersionsAndOversizedInputs() {
        val invalid = listOf(byteArrayOf(0xc3.toByte(), 0x28), "{".toByteArray(), "[]".toByteArray(),
            "{\"format\":\"fefw-growth-seed\",\"version\":2,\"records\":[]}".toByteArray(),
            ByteArray(FefwGrowthSeedImporter.MAX_BYTES + 1))
        invalid.forEach { bytes -> assertThrows(IllegalArgumentException::class.java) { FefwGrowthSeedImporter.inspect(bytes, rules) } }
    }

    @Test fun boundsDepthAndRecordCountsBeforeUnboundedParsing() {
        val nested = ("[".repeat(10000) + "0" + "]".repeat(10000)).toByteArray()
        assertThrows(IllegalArgumentException::class.java) { FefwGrowthSeedImporter.inspect(nested, rules) }
        val tooMany = "{\"format\":\"fefw-growth-seed\",\"version\":1,\"records\":[${List(1001) { "{}" }.joinToString()}]}".toByteArray()
        assertThrows(IllegalArgumentException::class.java) { FefwGrowthSeedImporter.inspect(tooMany, rules) }
    }
}
