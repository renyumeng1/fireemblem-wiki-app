package com.fireemblem.companion.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FixedGrowthEngineTest {
    @Test
    fun `initial accumulator starts at 3 times personal growth mod 100`() {
        val personal = listOf(45, 45, 40, 45, 45, 40, 35, 35, 40)
        assertEquals(
            listOf(35, 35, 20, 35, 35, 20, 5, 5, 20),
            FixedGrowthEngine.initialAccumulators(personal),
        )
    }

    @Test
    fun `guarantee resets triggered accumulator to zero rather than keeping deficit`() {
        val personal = listOf(10, 0, 0, 0, 0, 0, 0, 0, 0)
        // Step 1: acc[0]=(3*10)%100=30 + 50 = 80; acc[1]=0 + 45 = 45 -> stat 0 wins guarantee, resets to 0.
        // Step 2: acc[0]=0 + 50 = 50; acc[1]=45 + 45 = 90 -> stat 1 wins guarantee, resets to 0.
        // Step 3: acc[0]=50 + 50 = 100 -> natural +1! (Under wrong negative deficit -20, step 3 would only reach 80.)
        val effective = listOf(50, 45, 0, 0, 0, 0, 0, 0, 0)
        val caps = List(9) { 99 }
        val start = List(9) { 10 }

        val steps = FixedGrowthEngine.simulate(
            startLevel = 1,
            personalGrowth = personal,
            startStats = start,
            ratesPerLevel = listOf(effective, effective, effective),
            capsPerLevel = listOf(caps, caps, caps),
            guaranteePoints = 1,
        )

        assertEquals(listOf(0), steps[0].guaranteedIndices)
        assertEquals(0, steps[0].stateAfter.accumulators[0])
        assertFalse(steps[0].hasTieBreakCaveat)

        assertEquals(listOf(1), steps[1].guaranteedIndices)
        assertEquals(50, steps[1].stateAfter.accumulators[0])
        assertEquals(0, steps[1].stateAfter.accumulators[1])

        assertEquals(1, steps[2].naturalGains[0])
        assertEquals(listOf(0), steps[2].guaranteedIndices.let { listOf(steps[2].totalGains[0] - 1) }.let { listOf(0) }.takeIf { steps[2].guaranteedIndices.isEmpty() } ?: steps[2].guaranteedIndices)
        assertTrue(steps[2].guaranteedIndices.isEmpty())
        assertEquals(12, steps[2].stateAfter.stats[0])
    }

    @Test
    fun `equal remainder candidates flag tie-break caveat`() {
        val personal = List(9) { 40 }
        val effective = List(9) { 40 }
        val caps = List(9) { 99 }
        val step = FixedGrowthEngine.step(
            levelFrom = 1,
            state = FixedGrowthEngine.initialState(personal, List(9) { 10 }),
            effectiveRates = effective,
            caps = caps,
            guaranteePoints = 2,
        )
        assertTrue(step.hasTieBreakCaveat)
        assertEquals(2, step.guaranteedIndices.size)
        assertEquals(2, step.totalGains.sum())
    }

    @Test
    fun `bundled Kai personal growth matches expected vector`() {
        val rules = BuddyRules.bundled()
        val kaiGrowth = rules.personalGrowth("c004")
        assertEquals(listOf(45, 45, 40, 45, 45, 40, 35, 35, 40), kaiGrowth)
        assertEquals(2, rules.characterGuarantee("c004", part3 = false))
        assertEquals(0, rules.characterGuarantee("c004", part3 = true))
    }
}
