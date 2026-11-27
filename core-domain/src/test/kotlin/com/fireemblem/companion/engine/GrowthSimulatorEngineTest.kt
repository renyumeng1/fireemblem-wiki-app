package com.fireemblem.companion.engine

import com.fireemblem.companion.content.GrowthSimCharacter
import com.fireemblem.companion.content.GrowthSimClass
import com.fireemblem.companion.content.GrowthSimMount
import com.fireemblem.companion.content.GrowthSimulatorCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GrowthSimulatorEngineTest {
    private val sampleCatalog = GrowthSimulatorCatalog(
        characters = listOf(
            GrowthSimCharacter(
                nativeId = "PID_カイ",
                code = "0",
                recordId = 1,
                name = "凯伊",
                faction = "测试",
                gender = "男",
                startClass = "平民",
                startLevel = 1,
                base = listOf(27, 10, 7, 8, 8, 6, 5, 6, 5),
                growth = listOf(45, 45, 40, 45, 45, 40, 35, 35, 40),
                caps = List(9) { 99 },
                guaranteePre = 2,
                guaranteePart3 = 0,
            ),
        ),
        classes = listOf(
            GrowthSimClass(
                id = "JID_無職",
                recordId = 1,
                name = "平民",
                tier = "基础",
                base = List(9) { 0 },
                growthBonus = List(9) { 0 },
                baseGrowth = listOf(40, 40, 40, 40, 40, 40, 40, 40, 40),
                modifier = List(9) { 0 },
                caps = List(9) { 99 },
                mountType = "",
                mountMultiplier = 1,
                allowedRecords = emptyList(),
                forbiddenPeople = emptyList(),
            ),
            GrowthSimClass(
                id = "JID_騎兵",
                recordId = 2,
                name = "骑兵",
                tier = "初级",
                base = List(9) { 0 },
                growthBonus = listOf(10, 15, 0, 5, 5, 0, 10, 0, 0),
                baseGrowth = listOf(50, 50, 20, 40, 40, 40, 40, 20, 20),
                modifier = List(9) { 0 },
                caps = List(9) { 99 },
                mountType = "马",
                mountMultiplier = 2,
                allowedRecords = emptyList(),
                forbiddenPeople = emptyList(),
            ),
        ),
        mounts = listOf(
            GrowthSimMount(
                id = "RID_HORSE",
                type = "马",
                name = "战马",
                growth = listOf(5, 5, 0, 0, 0, 0, 0, 0, 0),
                attrBonus = List(9) { 0 },
                effect = "",
                location = "",
            ),
        ),
        sourceUri = "https://fireemblem.top/tools/growth-simulator",
        sourceRevision = "a".repeat(64),
    )

    @Test
    fun `fixed random and linear modes run separate calculations and compare cleanly`() {
        val planA = GrowthSimulatorPlan(
            profileId = "global",
            name = "方案A",
            characterId = "PID_カイ",
            startLevel = 1,
            part3 = false,
            stages = listOf(GrowthPlanStage("JID_無職", levels = 5)),
        )
        val planB = GrowthSimulatorPlan(
            profileId = "global",
            name = "方案B",
            characterId = "PID_カイ",
            startLevel = 1,
            part3 = false,
            stages = listOf(
                GrowthPlanStage("JID_無職", levels = 2),
                GrowthPlanStage("JID_騎兵", mountId = "RID_HORSE", levels = 3),
            ),
        )

        val fixedA = GrowthSimulatorEngine.simulate(sampleCatalog, planA, GrowthSimulationMode.FIXED)
        val randomA = GrowthSimulatorEngine.simulate(sampleCatalog, planA, GrowthSimulationMode.RANDOM_EXPECTED)
        val linearA = GrowthSimulatorEngine.simulate(sampleCatalog, planA, GrowthSimulationMode.LINEAR_EXPECTED)
        val fixedB = GrowthSimulatorEngine.simulate(sampleCatalog, planB, GrowthSimulationMode.FIXED)

        assertNotNull(randomA.statBands)
        assertEquals(27.0 + 5 * 0.45, linearA.finalStats[0], 1e-6)
        assertTrue(fixedA.finalStats != linearA.finalStats)
        assertTrue(randomA.finalStats != linearA.finalStats)

        val deltas = GrowthSimulatorEngine.compare(fixedA, fixedB)
        // Mounted Cavalry adds +10 + 5*2 = +20% HP and +15 + 5*2 = +25% STR across 3 levels
        assertTrue(deltas[1] >= 0.0)
        assertEquals(2, fixedB.stageSummaries.size)
    }

    @Test
    fun `plan payload and export json round trip cleanly`() {
        val plan = GrowthSimulatorPlan(
            profileId = "profile-1",
            name = "主力规划",
            characterId = "PID_カイ",
            startLevel = 1,
            part3 = true,
            stages = listOf(GrowthPlanStage("JID_騎兵", "RID_HORSE", 10)),
        )
        val decodedPayload = GrowthSimulatorPlan.decode(plan.payload())
        val decodedExport = GrowthSimulatorPlan.decodeExport(plan.encodeExport(), "profile-2")
        assertEquals(plan, decodedPayload)
        assertEquals(plan.copy(profileId = "profile-2"), decodedExport)
    }
}
