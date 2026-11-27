package com.fireemblem.companion.importer

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuddyBackupInspectorTest {
    @Test
    fun importsVersionTwoAndPreservesHighUnsignedSeedAndUnknownFields() {
        val input = """
            {
              "version":2,
              "roster":[{
                "id":"c001","initialLevel":1,"records":[
                  {"id":"event-1","seq":1,"stats":["HP"],"happy":true,"status":"normal"},
                  {"id":"event-2","seq":2,"stats":[],"happy":false,"status":"skipped"}
                ],
                "seed":{"value":4294967295},
                "futureFlag":{"kept":true}
              }],
              "selection":[],"initialLevels":{},"futureRoot":"kept"
            }
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)

        val preview = BuddyBackupInspector.inspect(input)

        assertEquals(2, preview.sourceVersion)
        assertEquals(1, preview.characterCount)
        assertEquals(2, preview.recordCount)
        assertEquals(1, preview.skippedRecordCount)
        assertEquals(1, preview.seedCount)
        assertEquals(1, preview.highSeedCount)
        assertTrue(preview.unknownFieldPaths.any { it.endsWith("futureRoot") })
        assertTrue(preview.unknownFieldPaths.any { it.endsWith("futureFlag") })
        assertTrue(preview.canonicalJson.contains("4294967295"))
        assertTrue(preview.canonicalJson.contains("futureFlag"))
    }

    @Test
    fun reportsUnknownFieldCountSeparatelyFromSamplePaths() {
        val extraFields = (1..25).joinToString(",") { "\"future$it\":$it" }
        val input = """{"version":2,"roster":[],$extraFields}""".toByteArray(StandardCharsets.UTF_8)

        val preview = BuddyBackupInspector.inspect(input)

        assertEquals(25, preview.unknownFieldCount)
        assertEquals(20, preview.unknownFieldPaths.size)
        assertTrue(preview.unknownFieldPaths.first().endsWith("future1"))
        assertFalse(preview.unknownFieldPaths.any { it.endsWith("future25") })
        assertTrue(preview.canonicalJson.contains("future25"))
    }

    @Test
    fun countsUnknownSeedFieldsOnceAcrossNormalizationAndValidation() {
        val input = """
            {"version":2,"roster":[{"id":"c001","initialLevel":1,"records":[],
              "seed":{"value":1,"futureSeed":true,"rows":{"0":{"futureRow":true}}}}]}
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)

        val preview = BuddyBackupInspector.inspect(input)

        assertEquals(2, preview.unknownFieldCount)
        assertEquals(2, preview.unknownFieldPaths.size)
        assertTrue(preview.canonicalJson.contains("futureSeed"))
        assertTrue(preview.canonicalJson.contains("futureRow"))
    }

    @Test
    fun acceptsOnlyBuddySupportedCalculatedFlagInCorrectedSeedInput() {
        val input = """
            {"version":2,"roster":[{"id":"c001","initialLevel":1,"records":[
              {"id":"event-1","seq":1,"stats":[],"happy":true,"status":"normal","corrected":true,
               "seedInput":{"profession":null,"ride":null,"stats":[],"plus2":[],"calculated":true}}
            ]}]}
        """.trimIndent()

        val preview = BuddyBackupInspector.inspect(input.toByteArray(StandardCharsets.UTF_8))
        val unsupportedFlag = input.replace("\"calculated\":true", "\"calculated\":false")

        assertTrue(preview.canonicalJson.contains("\"calculated\":true"))
        assertTrue(runCatching {
            BuddyBackupInspector.inspect(unsupportedFlag.toByteArray(StandardCharsets.UTF_8))
        }.exceptionOrNull() is BuddyBackupException)
    }

    @Test
    fun rejectsMountAssignedToTheWrongDraftKind() {
        val input = """
            {"version":2,"roster":[],"draftRides":{"c001":{"horse":{"id":"RID_ペガサス","level":1}}}}
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)

        assertTrue(runCatching { BuddyBackupInspector.inspect(input) }.exceptionOrNull() is BuddyBackupException)
    }

    @Test
    fun migratesUnversionedLegacyRecordsWithoutDroppingSkippedLevel() {
        val input = """
            {"roster":[{"id":"c001","initialLevel":4,"records":[
              {"level":5,"stats":["HP"],"happy":true},
              {"level":6,"stats":[],"happy":false,"skipped":true},
              {"level":6,"stats":["力量"],"happy":true,"upgraded":true}
            ]}]}
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)

        val preview = BuddyBackupInspector.inspect(input)

        assertEquals(1, preview.sourceVersion)
        assertEquals(3, preview.recordCount)
        assertEquals(1, preview.skippedRecordCount)
        assertEquals(1, preview.upgradedRecordCount)
        assertTrue(preview.canonicalJson.contains("\"version\":2"))
        assertTrue(preview.canonicalJson.contains("\"seq\":3"))
        assertFalse(preview.canonicalJson.contains("\"level\""))
    }

    @Test
    fun rejectsUnsupportedVersionAndMalformedJson() {
        val unsupported = """{"version":3,"roster":[]}""".toByteArray()
        val malformed = "{".toByteArray()

        assertTrue(runCatching { BuddyBackupInspector.inspect(unsupported) }.exceptionOrNull() is BuddyBackupException)
        assertTrue(runCatching { BuddyBackupInspector.inspect(malformed) }.exceptionOrNull() is BuddyBackupException)
    }

    @Test
    fun rejectsDuplicateEntitiesAndInvalidSeed() {
        val duplicate = """{"version":2,"roster":[{"id":"c001","records":[]},{"id":"c001","records":[]}] }""".toByteArray()
        val seed = """{"version":2,"roster":[{"id":"c001","records":[],"seed":{"value":4294967296}}]}""".toByteArray()

        assertTrue(runCatching { BuddyBackupInspector.inspect(duplicate) }.isFailure)
        assertTrue(runCatching { BuddyBackupInspector.inspect(seed) }.isFailure)
    }

    @Test
    fun migratesLegacyClassAndRideAliasesAcrossBackupFields() {
        val input = """
            {
              "version":2,
              "roster":[{
                "id":"c001","initialLevel":1,"initialProfession":"骑甲驼兵",
                "unlockedClasses":["骑甲驼兵"],
                "records":[{
                  "id":"event-1","seq":1,"stats":[],"happy":true,"status":"normal",
                  "profession":"骑甲驼兵","ride":{"id":"RID_ロシナンの仔","level":2},
                  "corrected":true,
                  "seedInput":{"profession":"骑甲驼兵","ride":{"id":"RID_ブーケパラスの仔","level":3},"stats":[],"plus2":[]}
                }],
                "seed":{"value":1,"rows":{"0":{"status":"skipped","profession":"骑甲驼兵","ride":{"id":"RID_ロシナンの仔","level":1}}}}
              }],
              "selection":[],"initialLevels":{},
              "draftRides":{"c001":{"pegasus":{"id":"RID_ブーケパラスの仔","level":2}}},
              "rideMemory":{"c001":{"horse":{"id":"RID_ロシナンの仔","level":1}}}
            }
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)

        val canonical = BuddyBackupInspector.inspect(input).canonicalJson

        assertTrue(canonical.contains("骑甲鸵兵"))
        assertTrue(canonical.contains("RID_ロシナン\""))
        assertTrue(canonical.contains("RID_ブーケパラス\""))
        assertFalse(canonical.contains("RID_ロシナンの仔"))
        assertFalse(canonical.contains("RID_ブーケパラスの仔"))
    }

    @Test
    fun rejectsMissingVersionTwoSequenceAndNullSeedFlags() {
        val missingSequence = """
            {"version":2,"roster":[{"id":"c001","initialLevel":1,"records":[
              {"id":"event-1","stats":[],"happy":true}
            ]}]}
        """.trimIndent().toByteArray()
        val nullSeedFlag = """
            {"version":2,"roster":[{"id":"c001","initialLevel":1,"records":[],
              "seed":{"value":1,"rows":{"0":{"skipped":null}}}}
            ]}
        """.trimIndent().toByteArray()

        assertTrue(runCatching { BuddyBackupInspector.inspect(missingSequence) }.isFailure)
        assertTrue(runCatching { BuddyBackupInspector.inspect(nullSeedFlag) }.isFailure)
    }

    @Test
    fun rejectsOversizedInputBeforeParsing() {
        val bytes = ByteArray(BuddyBackupInspector.MAX_FILE_BYTES + 1)
        val error = runCatching { BuddyBackupInspector.inspect(bytes) }.exceptionOrNull()

        assertTrue(error is BuddyBackupException)
        assertEquals("备份超过 10 MiB 上限", error?.message)
    }
}
