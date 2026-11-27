package com.fireemblem.companion.importer

import com.fireemblem.companion.engine.BuddyCampaignLine
import com.fireemblem.companion.engine.BuddyRules
import com.fireemblem.companion.engine.ImportedGrowthActionKind
import com.fireemblem.companion.engine.JournalSeedEvidence
import com.fireemblem.companion.engine.LevelJournal
import com.fireemblem.companion.engine.SeedEngine
import java.security.MessageDigest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class FefwGrowthChaptersImporterTest {
    private val rules = BuddyRules.bundled()

    private fun action(job: String, kind: String = "level") = buildJsonObject {
        put("job", job); put("mountId", ""); put("exams", 0); put("kind", kind)
    }

    private fun route(name: String, seed: Long, actions: JsonArray = JsonArray(listOf(action("JID_兵士")))) = buildJsonObject {
        put("name", name)
        put("forecast", buildJsonObject {
            put("seed", seed); put("level", 1); put("currentStats", JsonArray(List(9) { JsonPrimitive(0) }))
            put("caps", JsonArray(List(9) { JsonNull }))
        })
        put("plan", actions); put("position", 0); put("recordPosition", 0)
    }

    private fun archive(routes: JsonArray = JsonArray(listOf(route("优先力量", 42)))): ByteArray = buildJsonObject {
        put("format", "fefw-growth-chapters"); put("version", 1); put("revision", 0)
        put("entries", JsonArray(listOf(buildJsonObject {
            put("id", "source-entry"); put("chapter", "赛奥朵拉篇"); put("round", 1); put("name", "已有方案")
            put("snapshot", buildJsonObject {
                put("activeRoute", 0); put("position", 0); put("recordPosition", 0)
                put("inputs", buildJsonObject {
                    put("pid", "PID_セオドラ"); put("job", "JID_兵士"); put("part", "0"); put("level", 1)
                })
                put("routes", routes)
            })
            put("execution", buildJsonObject {
                put("seed", 42); put("level", 1); put("currentStats", JsonArray(List(9) { JsonPrimitive(0) }))
                put("job", "JID_兵士"); put("cursor", 0)
            })
        })))
    }.toString().toByteArray(Charsets.UTF_8)

    private fun inspect(bytes: ByteArray = archive()) = FefwGrowthChaptersImporter.inspect(bytes, rules)

    @Test
    fun importsSelectedRouteWithExactMappingsAndUserConfirmedSeed() {
        val bytes = archive(JsonArray(listOf(
            route("分支一", 42),
            route("分支二", 0xffff_ffffL),
        )))
        val preview = inspect(bytes)
        val entry = preview.entries.single()

        assertTrue(entry.errors.toString(), entry.ready)
        assertEquals("c012", entry.characterId)
        assertEquals("赛奥朵拉", entry.characterName)
        assertEquals(2, entry.routes.size)
        assertTrue(entry.routes.all { it.ready })
        assertArrayEquals(bytes, preview.sourceArchive.restoreBytes())
        assertEquals(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
            preview.sourceSha256)

        val journal = preview.toJournal(0, 1, "手推方案", "global", BuddyCampaignLine.SEO, rules)
        assertEquals(0xffff_ffffL, journal.seedCheckpoint?.seed)
        assertEquals(1, journal.seedCheckpoint?.level)
        assertEquals("士兵", journal.seedCheckpoint?.profession)
        assertEquals(JournalSeedEvidence.USER_CONFIRMED_IMPORT, journal.seedCheckpoint?.evidence)
        assertEquals("source-entry", journal.importedGrowthPlan?.entryId)
        assertEquals("分支二", journal.importedGrowthPlan?.routeName)
        assertEquals(emptyList<Any>(), journal.observations)
        assertEquals(journal, LevelJournal.decode(journal.encode()))
    }

    @Test
    fun preservesExamAndUpgradeActionsWithoutInventingOutcomes() {
        val actionValues = JsonArray(listOf(action("JID_兵士"), action("JID_猟兵", "exam"), action("JID_猟兵")))
        val preview = inspect(archive(JsonArray(listOf(route("混合方案", 42, actionValues)))))
        val actions = preview.entries.single().routes.single().actions
        assertEquals(listOf(ImportedGrowthActionKind.UPGRADE, ImportedGrowthActionKind.EXAM,
            ImportedGrowthActionKind.UPGRADE), actions.map { it.kind })
        assertEquals(listOf("士兵", "猎兵", "猎兵"), actions.map { it.profession })
        assertTrue(preview.entries.single().routes.single().ready)
    }

    @Test
    fun rejectsUnknownIdsUnsupportedActionFieldsAndNonzeroCursors() {
        val unknownJob = inspect(archive(JsonArray(listOf(route("未知职业", 42,
            JsonArray(listOf(action("JID_unknown"))))))))
        assertFalse(unknownJob.entries.single().routes.single().ready)
        assertTrue(unknownJob.entries.single().routes.single().errors.any { "JID_unknown" in it })

        val unknownAction = JsonObject(action("JID_兵士") + ("resourceCost" to JsonPrimitive(1)))
        val unsupported = inspect(archive(JsonArray(listOf(route("未知字段", 42, JsonArray(listOf(unknownAction)))))))
        assertFalse(unsupported.entries.single().routes.single().ready)

        val progressed = JsonObject(route("已推进", 42) + ("recordPosition" to JsonPrimitive(1)))
        val cursor = inspect(archive(JsonArray(listOf(progressed))))
        assertFalse(cursor.entries.single().routes.single().ready)
        assertTrue(cursor.entries.single().routes.single().errors.any { "recordPosition=1" in it })
    }

    @Test
    fun rejectsUnsupportedMountExamCountKindsAndMalformedDocuments() {
        listOf(
            JsonObject(action("JID_兵士") + ("mountId" to JsonPrimitive("RID_馬"))),
            JsonObject(action("JID_兵士") + ("exams" to JsonPrimitive(1))),
            JsonObject(action("JID_兵士") + ("kind" to JsonPrimitive("unknown"))),
        ).forEach { invalid ->
            val preview = inspect(archive(JsonArray(listOf(route("不支持", 42, JsonArray(listOf(invalid)))))))
            assertFalse(preview.entries.single().routes.single().ready)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FefwGrowthChaptersImporter.inspect("{".toByteArray(), rules)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FefwGrowthChaptersImporter.inspect(byteArrayOf(0xc3.toByte(), 0x28), rules)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FefwGrowthChaptersImporter.inspect(ByteArray(FefwGrowthChaptersImporter.MAX_BYTES + 1), rules)
        }
    }
}
