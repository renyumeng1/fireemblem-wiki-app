package com.fireemblem.companion.engine

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuddySeedHistoryTest {
    private val rules = BuddyRules.bundled()

    @Test
    fun reconstructsExactEventsAndKeepsSkippedAttemptsAsSeedAdvances() {
        val forecast = rules.forecast(0L, BuddyGrowthInput("c001", "平民", 1, false, BuddyRoute.KAI),
            count = 5, skippedIndices = setOf(1))
        val payload = buildJsonObject {
            put("version", 2)
            put("route", "kai")
            put("part3", false)
            put("roster", buildJsonArray {
                add(buildJsonObject {
                    put("id", "c001")
                    put("initialLevel", 1)
                    put("initialProfession", "平民")
                    put("records", buildJsonArray {
                        forecast.rows.forEachIndexed { index, row ->
                            add(buildJsonObject {
                                put("id", "record-$index")
                                put("seq", index + 1)
                                put("profession", "平民")
                                put("part3", false)
                                put("ride", JsonNull)
                                put("status", if (row.skipped) "skipped" else "normal")
                                put("happy", true)
                                put("stats", buildJsonArray {
                                    row.gains.filterValues { it > 0 }.keys.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.label)) }
                                })
                                put("plus2", buildJsonArray {
                                    row.gains.filterValues { it > 1 }.keys.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.label)) }
                                })
                            })
                        }
                    })
                })
            })
        }.toString()

        val history = BuddySeedHistory.fromBackup(payload, "c001", rules)
        val search = SeedEngine.searchRange(history.events, 0, 1)

        assertTrue(history.ready)
        assertEquals(4, history.observedLevels)
        assertEquals(1, history.skippedAttempts)
        assertEquals(SeedSearchStatus.UNIQUE, search.status)
        assertEquals(listOf(0L), search.candidates.single().originSeeds)
    }

    @Test
    fun refusesToGuessMissingHistoricalChapterSettings() {
        val payload = """
            {"version":2,"route":"kai","roster":[{"id":"c044","initialLevel":1,
            "initialProfession":"平民","records":[{"id":"r1","seq":1,"status":"normal",
            "profession":"平民","stats":[],"happy":true,"ride":null}]}]}
        """.trimIndent()

        val history = BuddySeedHistory.fromBackup(payload, "c044", rules)

        assertFalse(history.ready)
        assertTrue(history.errors.any { it.contains("篇章标记") })
    }
}
