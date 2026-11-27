package com.fireemblem.companion.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class BuddyGrowthPlannerTest {
    private val rules = BuddyRules.bundled()

    private data class Setup(val characterId: String, val professions: List<String>)
    private data class OraclePlan(val sequence: List<String>, val stats: List<Int>, val score: Long, val switches: Int)

    @Test
    fun exactSmallHorizonMatchesIndependentBruteForceEnumeration() {
        val setup = twoLegalProfessions()
        val request = request(setup, 8, 11).copy(
            weights = GrowthStat.entries.indices.map { if (it == GrowthStat.STRENGTH.ordinal) 17 else 2 },
            currentStats = listOf(17, 8, 12, 3, 14, 6, 11, 4, 2),
        )

        val expected = bruteForce(request)
        val actual = BuddyGrowthPlanner.plan(request, rules)

        assertNotNull(expected)
        assertEquals(BuddyGrowthPlanStatus.EXACT, actual.status)
        assertTrue(actual.complete)
        assertTrue(actual.optimal)
        assertTrue(actual.exploredStates > 1)
        assertEquals(expected!!.score, actual.weightedScore)
        assertEquals(expected.stats, actual.terminalStats)
        assertEquals(expected.sequence, actual.steps.map { it.profession })
    }

    @Test
    fun scoreTiesPreferFewerSwitchesThenStableProfessionNameOrder() {
        val setup = twoLegalProfessions()
        val noSwitch = BuddyGrowthPlanner.plan(
            request(setup, 4, 5).copy(weights = List(GrowthStat.entries.size) { 0 }, maxSwitches = 0),
            rules,
        )
        val initial = rules.professions.first { it.name !in setup.professions }.name
        val stableOrder = BuddyGrowthPlanner.plan(
            request(setup, 4, 5).copy(
                initialProfession = initial,
                weights = List(GrowthStat.entries.size) { 0 },
                maxSwitches = 1,
            ),
            rules,
        )

        assertEquals(BuddyGrowthPlanStatus.EXACT, noSwitch.status)
        assertEquals(setup.professions.first(), noSwitch.steps.single().profession)
        assertNull(noSwitch.steps.single().switchFrom)
        assertEquals(setup.professions.minOrNull(), stableOrder.steps.single().profession)
        assertEquals(initial, stableOrder.steps.single().switchFrom)
    }

    @Test
    fun eachGainUsesCurrentCapRoomAndReturnsActualCappedGains() {
        val setup = twoLegalProfessions()
        val stats = List(GrowthStat.entries.size) { 10 }
        val caps = List<Int?>(GrowthStat.entries.size) { 10 }.toMutableList().also { it[0] = 11 }
        val profession = setup.professions.first()
        val request = request(setup, 8, 9).copy(
            unlockedProfessions = setOf(profession),
            professionWindows = listOf(window(profession)),
            currentStats = stats,
            caps = caps,
        )

        val result = BuddyGrowthPlanner.plan(request, rules)

        assertEquals(BuddyGrowthPlanStatus.EXACT, result.status)
        assertTrue(result.terminalStats!![0] in stats[0]..(stats[0] + 1))
        assertTrue(result.steps.single().gains[0] in 0..1)
        assertTrue(result.steps.single().gains.drop(1).all { it == 0 })
        assertEquals(result.terminalStats, stats.indices.map { stats[it] + result.steps.single().gains[it] })
    }

    @Test
    fun professionSwitchIsRecordedButDoesNotAdvanceSeedSeparately() {
        val setup = twoLegalProfessions()
        val chosen = setup.professions.first()
        val initial = setup.professions.last()
        val request = request(setup, 12, 13).copy(
            initialProfession = initial,
            unlockedProfessions = setOf(chosen),
            professionWindows = listOf(window(chosen)),
        )

        val step = BuddyGrowthPlanner.plan(request, rules).steps.single()
        val parameters = rules.growth(
            BuddyGrowthInput(request.characterId, chosen, step.level, false, request.line.rulesRoute),
        ).parameters

        assertEquals(initial, step.switchFrom)
        assertEquals(request.seed, step.seed)
        assertEquals(SeedEngine.advance(request.seed), step.nextSeed)
        assertEquals(SeedEngine.simulateLevel(request.seed, parameters).nextSeed, step.nextSeed)
    }

    @Test
    fun lockedPartThreeProfessionIsUnavailableUntilItsEligibleChapterLevel() {
        val profession = rules.professions.first { rules.professionIsPart3Only(it.name) }.name
        val character = rules.characters.firstOrNull {
            !it.part3Only && rules.classAllowed(it.id, profession, part3 = true)
        }
        assertNotNull("A Part 3 profession should have an eligible character", character)
        val setup = Setup(character!!.id, listOf(profession))
        val early = request(setup, 1, 3).copy(part3StartLevel = 2)
        val eligible = request(setup, 2, 3).copy(part3StartLevel = 2)
        val omittedUnlock = eligible.copy(unlockedProfessions = emptySet())

        val earlyResult = BuddyGrowthPlanner.plan(early, rules)
        val eligibleResult = BuddyGrowthPlanner.plan(eligible, rules)
        val omittedUnlockResult = BuddyGrowthPlanner.plan(omittedUnlock, rules)

        assertEquals(BuddyGrowthPlanStatus.NO_PATH, earlyResult.status)
        assertTrue(earlyResult.complete)
        assertEquals(BuddyGrowthPlanStatus.EXACT, eligibleResult.status)
        assertEquals(BuddyGrowthPlanStatus.NO_PATH, omittedUnlockResult.status)
        assertTrue(omittedUnlockResult.warnings.any { it.contains("未标记为已解锁") })
    }

    @Test
    fun characterProfessionRestrictionsApplyAtEveryPlannedLevel() {
        val restricted = rules.characters.asSequence()
            .filterNot { it.part3Only }
            .mapNotNull { character ->
                val profession = rules.professions.firstOrNull {
                    !rules.professionIsPart3Only(it.name) && !rules.classAllowed(character.id, it.name, part3 = true)
                }?.name
                profession?.let { character.id to it }
            }
            .firstOrNull()
        assertNotNull("A character-specific profession restriction should exist", restricted)

        val (characterId, profession) = restricted!!
        val request = request(Setup(characterId, listOf(profession)), 20, 21).copy(part3StartLevel = 1)
        val result = BuddyGrowthPlanner.plan(request, rules)

        assertEquals(BuddyGrowthPlanStatus.NO_PATH, result.status)
        assertTrue(result.complete)
    }

    @Test
    fun currentlyEnteredPartThreeOverridesALaterConfiguredStartLevel() {
        val request = BuddyGrowthPlanRequest(
            seed = 200,
            characterId = "c012",
            line = BuddyCampaignLine.SEO,
            currentLevel = 15,
            targetLevel = 16,
            part3StartLevel = 20,
            currentPart3 = true,
            initialProfession = "贵族",
            unlockedProfessions = setOf("贵族"),
            professionWindows = listOf(BuddyProfessionWindow("贵族", 15, 15)),
            weights = List(GrowthStat.entries.size) { 1 },
            currentStats = List(GrowthStat.entries.size) { 0 },
            caps = List<Int?>(GrowthStat.entries.size) { null },
        )

        val result = BuddyGrowthPlanner.plan(request, rules)
        val expected = SeedEngine.simulateLevel(200, rules.growth(
            BuddyGrowthInput("c012", "贵族", 15, true, BuddyRoute.OTHER),
        ).parameters)

        assertEquals(BuddyGrowthPlanStatus.EXACT, result.status)
        assertEquals(expected.gains, result.steps.single().gains)
        assertEquals(expected.nextSeed, result.steps.single().nextSeed)
    }

    @Test
    fun rideCanBeSelectedForAProfessionReachedByTheFirstSwitch() {
        val setup = rules.characters.asSequence().filterNot { it.part3Only }.mapNotNull { character ->
            val ridesByProfession = rules.professions.asSequence()
                .filterNot { rules.professionIsPart3Only(it.name) }
                .filter { rules.classAllowed(character.id, it.name, false) }
                .associate { profession ->
                    profession.name to rules.availableRides(BuddyGrowthInput(character.id, profession.name, 10,
                        false, BuddyRoute.OTHER)).map { it.id }.toSet()
                }
            val target = ridesByProfession.asSequence().flatMap { (profession, rideIds) ->
                rideIds.asSequence().map { profession to it }
            }.firstOrNull { (profession, rideId) ->
                ridesByProfession.any { (startingProfession, rideIds) ->
                    startingProfession != profession && rideId !in rideIds
                }
            } ?: return@mapNotNull null
            val starting = ridesByProfession.entries.first { (profession, rideIds) ->
                profession != target.first && target.second !in rideIds
            }.key
            Triple(character.id, starting, target)
        }.firstOrNull()
        assertNotNull("Expected a ride-compatible profession that can be entered from another class", setup)

        val (characterId, initialProfession, target) = setup!!
        val unlocked = setOf(initialProfession, target.first)
        val request = BuddyGrowthPlanRequest(
            seed = 42,
            characterId = characterId,
            line = BuddyCampaignLine.SEO,
            currentLevel = 10,
            targetLevel = 11,
            part3StartLevel = null,
            initialProfession = initialProfession,
            unlockedProfessions = unlocked,
            professionWindows = unlocked.map(::window),
            weights = List(GrowthStat.entries.size) { 1 },
            currentStats = List(GrowthStat.entries.size) { 0 },
            caps = List<Int?>(GrowthStat.entries.size) { null },
            maxSwitches = 1,
            ride = BuddyRideSelection(target.second, 1),
        )

        val result = BuddyGrowthPlanner.plan(request, rules)

        assertEquals(BuddyGrowthPlanStatus.EXACT, result.status)
        assertEquals(target.first, result.steps.single().profession)
        assertEquals(target.second, result.steps.single().ride?.id)
        assertEquals(initialProfession, result.steps.single().switchFrom)
    }

    @Test
    fun minimumStatsAreHardTerminalConstraints() {
        val setup = twoLegalProfessions()
        val profession = setup.professions.first()
        val stats = List(GrowthStat.entries.size) { 10 }
        val minimums = List<Int?>(GrowthStat.entries.size) { null }.toMutableList().also { it[0] = 11 }
        val request = request(setup, 8, 10).copy(
            unlockedProfessions = setOf(profession),
            professionWindows = listOf(window(profession)),
            currentStats = stats,
            caps = List(GrowthStat.entries.size) { stats[it] },
            minimumStats = minimums,
        )

        val result = BuddyGrowthPlanner.plan(request, rules)

        assertEquals(BuddyGrowthPlanStatus.NO_PATH, result.status)
        assertTrue(result.complete)
        assertFalse(result.optimal)
        assertFalse(result.hasPlan)
        assertNull(result.weightedScore)
    }

    @Test
    fun boundedAndCancelledSearchesNeverClaimOptimality() {
        val request = request(twoLegalProfessions(), 1, 2)
        val bounded = BuddyGrowthPlanner.plan(request.copy(maxStates = 2), rules)
        val boundedWithoutIncumbent = BuddyGrowthPlanner.plan(request.copy(maxStates = 1), rules)
        var cancellationChecks = 0
        val cancelledWithIncumbent = BuddyGrowthPlanner.plan(request, rules) {
            cancellationChecks++ >= 2
        }
        val cancelledImmediately = BuddyGrowthPlanner.plan(request, rules) { true }

        assertEquals(BuddyGrowthPlanStatus.BEST_FOUND, bounded.status)
        assertFalse(bounded.complete)
        assertFalse(bounded.optimal)
        assertTrue(bounded.hasPlan)
        assertEquals(BuddyGrowthPlanStatus.BEST_FOUND, boundedWithoutIncumbent.status)
        assertFalse(boundedWithoutIncumbent.hasPlan)
        assertEquals(BuddyGrowthPlanStatus.CANCELLED, cancelledWithIncumbent.status)
        assertFalse(cancelledWithIncumbent.complete)
        assertFalse(cancelledWithIncumbent.optimal)
        assertTrue(cancelledWithIncumbent.hasPlan)
        assertEquals(BuddyGrowthPlanStatus.CANCELLED, cancelledImmediately.status)
        assertFalse(cancelledImmediately.hasPlan)
    }

    @Test
    fun invalidInputsAreRejectedBeforeSearching() {
        val request = request(twoLegalProfessions(), 1, 2)

        assertThrows(IllegalArgumentException::class.java) {
            BuddyGrowthPlanner.plan(request.copy(seed = SeedEngine.UINT32_LIMIT), rules)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BuddyGrowthPlanner.plan(request.copy(weights = listOf(1)), rules)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BuddyGrowthPlanner.plan(request.copy(maxStates = 0), rules)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BuddyGrowthPlanner.plan(request.copy(targetLevel = 100), rules)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BuddyGrowthPlanner.plan(request.copy(targetLevel = request.currentLevel), rules)
        }
    }

    private fun twoLegalProfessions(): Setup {
        for (character in rules.characters) {
            if (character.part3Only) continue
            val eligible = rules.professions.asSequence()
                .filterNot { rules.professionIsPart3Only(it.name) }
                .filter { rules.classAllowed(character.id, it.name, part3 = false) }
                .map { it.name }
                .take(2)
                .toList()
            if (eligible.size == 2) return Setup(character.id, eligible)
        }
        throw AssertionError("Expected one character with two pre-Part-3 professions")
    }

    private fun request(setup: Setup, currentLevel: Int, targetLevel: Int) = BuddyGrowthPlanRequest(
        seed = 2_141_258_584L,
        characterId = setup.characterId,
        line = BuddyCampaignLine.SEO,
        currentLevel = currentLevel,
        targetLevel = targetLevel,
        part3StartLevel = null,
        initialProfession = setup.professions.first(),
        unlockedProfessions = setup.professions.toSet(),
        professionWindows = setup.professions.map(::window),
        weights = List(GrowthStat.entries.size) { 1 },
        currentStats = List(GrowthStat.entries.size) { 0 },
        caps = List<Int?>(GrowthStat.entries.size) { null },
    )

    private fun window(profession: String) = BuddyProfessionWindow(profession, 1, 99)

    private fun bruteForce(request: BuddyGrowthPlanRequest): OraclePlan? {
        val character = rules.characters.single { it.id == request.characterId }
        val known = rules.professions.mapTo(mutableSetOf()) { it.name }
        val professions = request.unlockedProfessions.filter { it in known }.sorted()
        var best: OraclePlan? = null

        fun visit(
            level: Int,
            seed: Long,
            stats: List<Int>,
            lastProfession: String,
            switches: Int,
            score: Long,
            sequence: List<String>,
        ) {
            if (level == request.targetLevel) {
                if (request.minimumStats.indices.any { index ->
                        request.minimumStats[index]?.let { stats[index] < it } ?: false
                    }
                ) return
                val candidate = OraclePlan(sequence, stats, score, switches)
                if (isBetter(candidate, best)) best = candidate
                return
            }

            val part3 = request.part3StartLevel?.let { level >= it } ?: false
            for (profession in professions) {
                if (request.professionWindows.none {
                        it.profession == profession && level in it.fromLevel..it.throughLevel
                    }
                ) continue
                if (character.part3Only && !part3) continue
                if (rules.professionIsPart3Only(profession) && !part3) continue
                if (!rules.classAllowed(request.characterId, profession, part3)) continue
                val nextSwitches = switches + if (profession == lastProfession) 0 else 1
                if (nextSwitches > request.maxSwitches) continue
                val parameters = rules.growth(
                    BuddyGrowthInput(request.characterId, profession, level, part3, request.line.rulesRoute),
                ).parameters
                val room = stats.indices.map { index ->
                    ((request.caps[index] ?: 32_767) - stats[index]).coerceAtLeast(0)
                }
                val simulation = SeedEngine.simulateLevel(seed, parameters.copy(room = room))
                val nextScore = request.weights.indices.fold(score) { total, index ->
                    total + request.weights[index].toLong() * simulation.gains[index]
                }
                visit(
                    level + 1,
                    simulation.nextSeed,
                    stats.indices.map { stats[it] + simulation.gains[it] },
                    profession,
                    nextSwitches,
                    nextScore,
                    sequence + profession,
                )
            }
        }

        visit(
            request.currentLevel,
            request.seed,
            request.currentStats,
            request.initialProfession,
            0,
            0L,
            emptyList(),
        )
        return best
    }

    private fun isBetter(candidate: OraclePlan, incumbent: OraclePlan?): Boolean {
        if (incumbent == null) return true
        if (candidate.score != incumbent.score) return candidate.score > incumbent.score
        if (candidate.switches != incumbent.switches) return candidate.switches < incumbent.switches
        for (index in 0 until minOf(candidate.sequence.size, incumbent.sequence.size)) {
            val comparison = candidate.sequence[index].compareTo(incumbent.sequence[index])
            if (comparison != 0) return comparison < 0
        }
        return candidate.sequence.size < incumbent.sequence.size
    }
}
