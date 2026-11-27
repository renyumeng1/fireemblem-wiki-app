package com.fireemblem.companion.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BuddyRulesTest {
    private val rules = BuddyRules.bundled()
    private val input = BuddyGrowthInput("c044", "战车兵", 34, true, BuddyRoute.OTHER, BuddyRideSelection("RID_ロシナン", 5))

    @Test
    fun `bundled rules reproduce pinned JavaScript growth and simulations`() {
        val fixture = javaClass.getResourceAsStream("/buddy/baseline-v1.json")!!.use {
            Json.parseToJsonElement(it.readBytes().toString(Charsets.UTF_8)).jsonObject
        }
        assertEquals(BuddyRules.REVISION, fixture.getValue("revision").jsonPrimitive.content)
        val cases = fixture.getValue("cases").jsonArray
        assertEquals(1032, cases.size)
        for ((index, item) in cases.withIndex()) {
            val expected = item.jsonObject
            val source = expected.getValue("input").jsonObject
            val ride = source["ride"]?.takeUnless { it is JsonNull }?.jsonObject?.let {
                BuddyRideSelection(it.getValue("id").jsonPrimitive.content, it.getValue("level").jsonPrimitive.int)
            }
            val context = BuddyGrowthInput(
                source.getValue("characterId").jsonPrimitive.content,
                source.getValue("profession").jsonPrimitive.content,
                source.getValue("level").jsonPrimitive.int,
                source.getValue("part3").jsonPrimitive.boolean,
                if (source.getValue("route").jsonPrimitive.content == "other") BuddyRoute.OTHER else BuddyRoute.KAI,
                ride,
            )
            val actual = rules.growth(context)
            val label = "case $index: $context"
            assertEquals(label, expected.getValue("growth").jsonArray.map { it.jsonPrimitive.int }, actual.parameters.growth)
            assertEquals(label, expected.getValue("guarantee").jsonPrimitive.int, actual.parameters.guarantee)
            assertEquals(label, expected.getValue("allowed").jsonPrimitive.boolean, actual.classAllowed)
            assertEquals(label, if (expected["effectiveRide"] is JsonNull) null else ride, actual.effectiveRide)
            val simulation = SeedEngine.simulateLevel(source.getValue("seed").jsonPrimitive.long, actual.parameters)
            assertEquals(label, expected.getValue("gains").jsonArray.map { it.jsonPrimitive.int }, simulation.gains)
            assertEquals(label, expected.getValue("nextSeed").jsonPrimitive.long, simulation.nextSeed)
        }
    }

    @Test
    fun `rules verify integrity and never replace a different revision`() {
        assertEquals(74, rules.characters.size)
        assertEquals(64, rules.professions.size)
        assertThrows(IllegalArgumentException::class.java) { BuddyRules.load("{}".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { rules.growth(input.copy(ruleRevision = "future")) }
        assertThrows(IllegalArgumentException::class.java) { rules.growth(input.copy(characterId = "c065")) }
        assertThrows(IllegalArgumentException::class.java) { rules.growth(input.copy(profession = "unknown")) }
        assertThrows(IllegalArgumentException::class.java) { rules.growth(input.copy(ride = BuddyRideSelection("unknown", 1))) }
        assertThrows(IllegalArgumentException::class.java) { BuddyRideSelection("RID_ロシナン", 6) }
    }

    @Test
    fun `four campaign lines retain distinct selections and map to verified legacy rules`() {
        assertEquals(listOf("kai", "dit", "seo", "leda"), BuddyCampaignLine.entries.map { it.id })
        assertEquals(listOf("凯伊篇", "迪托利希篇", "赛奥朵拉篇", "蕾达篇"),
            BuddyCampaignLine.entries.map { it.label })
        assertEquals(BuddyRoute.KAI, BuddyCampaignLine.KAI.rulesRoute)
        assertEquals(listOf(BuddyRoute.OTHER, BuddyRoute.OTHER, BuddyRoute.OTHER),
            BuddyCampaignLine.entries.drop(1).map { it.rulesRoute })
    }

    @Test
    fun `upstream person and profession identifiers require exact contextual mapping`() {
        val theodora = rules.characters.single { it.personId == "PID_セオドラ" }
        assertEquals("c012", theodora.id)
        assertEquals("赛奥朵拉", theodora.name)

        assertEquals("士兵", rules.professionForJobId("JID_兵士", theodora.id, false)?.name)
        assertEquals("贵族", rules.professionForJobId("JID_無職", theodora.id, false)?.name)
        assertEquals(2, rules.professionsForJobId("JID_無職").size)
        assertEquals(null, rules.professionForJobId("JID_unknown", theodora.id, false))

        val part3Profession = rules.professions.single { it.name == "战象兵" }
        assertEquals("JID_戦象兵", part3Profession.jobId)
        assertTrue(part3Profession.part3Only)
        assertEquals("elephant", part3Profession.kind)
    }

    @Test
    fun `forecasts replay upstream queues including skipped attempts`() {
        val fixture = javaClass.getResourceAsStream("/buddy/baseline-v1.json")!!.use {
            Json.parseToJsonElement(it.readBytes().toString(Charsets.UTF_8)).jsonObject
        }
        val forecasts = fixture.getValue("forecasts").jsonArray
        assertEquals(12, forecasts.size)
        for (entry in forecasts) {
            val expected = entry.jsonObject
            val skipped = expected.getValue("skippedIndices").jsonArray.map { it.jsonPrimitive.int }.toSet()
            val result = rules.forecast(expected.getValue("seed").jsonPrimitive.long, input, skippedIndices = skipped)
            val rows = expected.getValue("rows").jsonArray
            assertEquals(rows.size, result.rows.size)
            for ((index, row) in result.rows.withIndex()) {
                val source = rows[index].jsonObject
                assertEquals(source.getValue("seed").jsonPrimitive.long, row.seed)
                assertEquals(source.getValue("level").jsonPrimitive.int, row.fromLevel)
                assertEquals(source.getValue("examMinimum").jsonPrimitive.int, row.examMinimum)
                assertEquals(source.getValue("skipped").jsonPrimitive.boolean, row.skipped)
                assertEquals(source.getValue("gains").jsonArray.map { it.jsonPrimitive.int }, GrowthStat.entries.map { row.gains.getValue(it) })
            }
        }
    }

    @Test
    fun `mount options respect route ownership and story part`() {
        assertEquals(listOf("RID_ロシナン"), rules.availableRides(input.copy(part3 = false)).map { it.id })
        assertTrue(rules.availableRides(input.copy(characterId = "c017", part3 = false)).isEmpty())
        assertFalse(rules.availableRides(input.copy(part3 = false, route = BuddyRoute.KAI)).any { it.id == "RID_黒馬" })
        assertTrue(rules.availableRides(input).any { it.id == "RID_黒馬" })
    }

    @Test
    fun `skipped forecast consumes a seed without increasing level`() {
        val forecast = rules.forecast(4294967295, input, count = 4, skippedIndices = setOf(0, 2))
        assertEquals(listOf(34, 34, 35, 35), forecast.rows.map { it.fromLevel })
        assertEquals(listOf(true, false, true, false), forecast.rows.map { it.skipped })
        assertEquals(4294967295L, forecast.rows.first().seed)
        for ((previous, next) in forecast.rows.zipWithNext()) assertEquals(previous.nextSeed, next.seed)
        assertEquals(GrowthStat.entries.toSet(), forecast.rows.first().gains.keys)
        assertEquals(input, forecast.input)
    }

    @Test
    fun `forecast is capped and explains unavailable rides`() {
        assertEquals(1, rules.forecast(1, input.copy(level = 98)).rows.size)
        assertTrue(rules.forecast(1, input.copy(level = 99)).rows.isEmpty())
        val unavailable = input.copy(characterId = "c017", part3 = false)
        assertTrue(rules.forecast(1, unavailable).ignoredRide)
        assertThrows(IllegalArgumentException::class.java) { rules.forecast(-1, input) }
        assertThrows(IllegalArgumentException::class.java) { rules.forecast(SeedEngine.UINT32_LIMIT, input) }
        assertThrows(IllegalArgumentException::class.java) { rules.forecast(1, input, count = 101) }
        assertThrows(IllegalArgumentException::class.java) { rules.forecast(1, input, skippedIndices = setOf(-1)) }
        assertThrows(IllegalArgumentException::class.java) { rules.forecast(1, input.copy(profession = "舞者", part3 = false)) }
    }
}
