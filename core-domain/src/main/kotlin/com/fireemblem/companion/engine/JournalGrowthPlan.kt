package com.fireemblem.companion.engine

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

data class JournalGrowthPlan(
    val request: BuddyGrowthPlanRequest,
    val result: BuddyGrowthPlanResult,
    val historyDigest: String,
    val ruleRevision: String = BuddyRules.REVISION,
    val nextStepIndex: Int = 0,
) {
    fun validated(): JournalGrowthPlan {
        require(Regex("[0-9a-f]{64}").matches(historyDigest)) { "培养方案记录摘要无效" }
        require(ruleRevision.isNotBlank()) { "培养方案缺少规则版本" }
        require(request.seed in 0 until SeedEngine.UINT32_LIMIT) { "培养方案种子无效" }
        require(request.characterId.isNotBlank() && request.initialProfession.isNotBlank()) { "培养方案角色或职业无效" }
        require(request.currentLevel in 1..98 && request.targetLevel in (request.currentLevel + 1)..99) { "培养方案等级范围无效" }
        require(request.currentStats.size == 9 && request.weights.size == 9 && request.caps.size == 9 && request.minimumStats.size == 9)
        require(request.currentStats.all { it in 0..32_767 } && request.weights.all { it >= 0 } &&
            request.caps.indices.all { request.caps[it] == null || request.caps[it]!! in request.currentStats[it]..32_767 })
        require(request.minimumStats.all { it == null || it in 0..32_767 })
        require(request.part3StartLevel == null || request.part3StartLevel in 1..99)
        require(request.maxSwitches in 0..98 && request.maxStates > 0)
        require(request.initialProfession in request.unlockedProfessions && request.professionWindows.all { window ->
            window.profession in request.unlockedProfessions && window.fromLevel in 1..99 &&
                window.throughLevel in window.fromLevel..99
        })
        require(request.ride == null || request.ride.id.isNotBlank() && request.ride.level in 1..5)
        require(result.hasPlan && result.steps.isNotEmpty() && result.steps.size == request.targetLevel - request.currentLevel)
        require(nextStepIndex in 0..result.steps.size)
        require(result.steps.first().seed == request.seed && result.steps.first().level == request.currentLevel)
        var previousProfession = request.initialProfession
        result.steps.forEachIndexed { index, step ->
            require(step.level == request.currentLevel + index && step.seed in 0 until SeedEngine.UINT32_LIMIT &&
                step.nextSeed in 0 until SeedEngine.UINT32_LIMIT && step.profession.isNotBlank() &&
                step.profession in request.unlockedProfessions && step.gains.size == 9 && step.gains.all { it in 0..2 })
            require(step.switchFrom == if (step.profession == previousProfession) null else previousProfession)
            if (index > 0) require(result.steps[index - 1].nextSeed == step.seed)
            previousProfession = step.profession
        }
        require(when (result.status) {
            BuddyGrowthPlanStatus.EXACT -> result.complete && result.optimal
            BuddyGrowthPlanStatus.BEST_FOUND, BuddyGrowthPlanStatus.CANCELLED -> !result.complete && !result.optimal
            BuddyGrowthPlanStatus.NO_PATH -> false
        })
        require(result.exploredStates >= 0 && (result.weightedScore == null || result.weightedScore >= 0))
        require(result.terminalStats?.all { it in 0..32_767 } == true)
        val expectedTerminalStats = request.currentStats.indices.map { statIndex ->
            request.currentStats[statIndex] + result.steps.sumOf { step -> step.gains[statIndex] }
        }
        require(result.terminalStats == expectedTerminalStats) { "培养方案终点属性与步骤不一致" }
        val expectedWeightedScore = result.steps.sumOf { step ->
            request.weights.indices.sumOf { statIndex ->
                request.weights[statIndex].toLong() * step.gains[statIndex]
            }
        }
        require(result.weightedScore == expectedWeightedScore) { "培养方案收益与属性权重不一致" }
        return this
    }

    fun matchesCheckpoint(checkpoint: JournalSeedCheckpoint): Boolean {
        val completedSteps = result.steps.take(nextStepIndex)
        val previousStep = completedSteps.lastOrNull()
        val expectedStats = request.currentStats.indices.map { statIndex ->
            request.currentStats[statIndex] + completedSteps.sumOf { step -> step.gains[statIndex] }
        }
        val expectedPart3 = previousStep?.let { step ->
            request.currentPart3 || request.part3StartLevel?.let { step.level >= it } == true
        } ?: request.currentPart3
        return checkpoint.seed == (previousStep?.nextSeed ?: request.seed) &&
            checkpoint.level == request.currentLevel + nextStepIndex &&
            checkpoint.profession == (previousStep?.profession ?: request.initialProfession) &&
            checkpoint.part3 == expectedPart3 && checkpoint.stats == expectedStats &&
            checkpoint.caps == request.caps && checkpoint.unlockedProfessions.containsAll(request.unlockedProfessions)
    }

    fun encode(): JsonObject = buildJsonObject {
        validated()
        put("historyDigest", historyDigest)
        put("ruleRevision", ruleRevision)
        put("nextStepIndex", nextStepIndex)
        put("request", request.encodePlanRequest())
        put("result", result.encodePlanResult())
    }

    companion object {
        fun decode(value: JsonObject): JournalGrowthPlan = JournalGrowthPlan(
            decodePlanRequest(value.getValue("request").jsonObject),
            decodePlanResult(value.getValue("result").jsonObject),
            value.getValue("historyDigest").jsonPrimitive.content,
            value.getValue("ruleRevision").jsonPrimitive.content,
            value.getValue("nextStepIndex").jsonPrimitive.int,
        ).validated()
    }
}

private fun BuddyGrowthPlanRequest.encodePlanRequest(): JsonObject = buildJsonObject {
    put("seed", seed); put("characterId", characterId); put("line", line.id)
    put("currentLevel", currentLevel); put("targetLevel", targetLevel)
    put("part3StartLevel", part3StartLevel?.let(::JsonPrimitive) ?: JsonNull)
    put("currentPart3", currentPart3)
    put("initialProfession", initialProfession)
    put("unlockedProfessions", JsonArray(unlockedProfessions.sorted().map(::JsonPrimitive)))
    put("professionWindows", JsonArray(professionWindows.map { window -> buildJsonObject {
        put("profession", window.profession); put("fromLevel", window.fromLevel); put("throughLevel", window.throughLevel)
    } }))
    put("weights", JsonArray(weights.map(::JsonPrimitive)))
    put("currentStats", JsonArray(currentStats.map(::JsonPrimitive)))
    put("caps", JsonArray(caps.map { it?.let(::JsonPrimitive) ?: JsonNull }))
    put("minimumStats", JsonArray(minimumStats.map { it?.let(::JsonPrimitive) ?: JsonNull }))
    put("maxSwitches", maxSwitches); put("maxStates", maxStates)
    put("ride", ride?.let { buildJsonObject { put("id", it.id); put("level", it.level) } } ?: JsonNull)
}

private fun decodePlanRequest(value: JsonObject): BuddyGrowthPlanRequest {
    val line = requireNotNull(BuddyCampaignLine.entries.find { it.id == value.getValue("line").jsonPrimitive.content })
    val ride = value["ride"]?.takeUnless { it is JsonNull }?.jsonObject?.let {
        BuddyRideSelection(it.getValue("id").jsonPrimitive.content, it.getValue("level").jsonPrimitive.int)
    }
    return BuddyGrowthPlanRequest(
        seed = value.getValue("seed").jsonPrimitive.long,
        characterId = value.getValue("characterId").jsonPrimitive.content,
        line = line,
        currentLevel = value.getValue("currentLevel").jsonPrimitive.int,
        targetLevel = value.getValue("targetLevel").jsonPrimitive.int,
        part3StartLevel = value["part3StartLevel"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.int,
        currentPart3 = value["currentPart3"]?.jsonPrimitive?.boolean ?: false,
        initialProfession = value.getValue("initialProfession").jsonPrimitive.content,
        unlockedProfessions = value.getValue("unlockedProfessions").jsonArray.map { it.jsonPrimitive.content }.toSet(),
        professionWindows = value.getValue("professionWindows").jsonArray.map { item ->
            val window = item.jsonObject
            BuddyProfessionWindow(window.getValue("profession").jsonPrimitive.content,
                window.getValue("fromLevel").jsonPrimitive.int, window.getValue("throughLevel").jsonPrimitive.int)
        },
        weights = value.getValue("weights").jsonArray.map { it.jsonPrimitive.int },
        currentStats = value.getValue("currentStats").jsonArray.map { it.jsonPrimitive.int },
        caps = value.getValue("caps").jsonArray.map { it.takeUnless { element -> element is JsonNull }?.jsonPrimitive?.int },
        minimumStats = value.getValue("minimumStats").jsonArray.map { it.takeUnless { element -> element is JsonNull }?.jsonPrimitive?.int },
        maxSwitches = value.getValue("maxSwitches").jsonPrimitive.int,
        maxStates = value.getValue("maxStates").jsonPrimitive.int,
        ride = ride,
    )
}

private fun BuddyGrowthPlanResult.encodePlanResult(): JsonObject = buildJsonObject {
    put("status", status.name); put("complete", complete); put("optimal", optimal)
    put("steps", JsonArray(steps.map { step -> buildJsonObject {
        put("level", step.level); put("profession", step.profession); put("seed", step.seed); put("nextSeed", step.nextSeed)
        put("gains", JsonArray(step.gains.map(::JsonPrimitive)))
        put("switchFrom", step.switchFrom?.let(::JsonPrimitive) ?: JsonNull)
        put("ride", step.ride?.let { buildJsonObject { put("id", it.id); put("level", it.level) } } ?: JsonNull)
    } }))
    put("terminalStats", terminalStats?.let { JsonArray(it.map(::JsonPrimitive)) } ?: JsonNull)
    put("weightedScore", weightedScore?.let(::JsonPrimitive) ?: JsonNull)
    put("exploredStates", exploredStates); put("warnings", JsonArray(warnings.map(::JsonPrimitive))); put("reason", reason)
}

private fun decodePlanResult(value: JsonObject): BuddyGrowthPlanResult = BuddyGrowthPlanResult(
    status = requireNotNull(BuddyGrowthPlanStatus.entries.find { it.name == value.getValue("status").jsonPrimitive.content }),
    complete = value.getValue("complete").jsonPrimitive.boolean,
    optimal = value.getValue("optimal").jsonPrimitive.boolean,
    steps = value.getValue("steps").jsonArray.map { item ->
        val step = item.jsonObject
        val ride = step["ride"]?.takeUnless { it is JsonNull }?.jsonObject?.let {
            BuddyRideSelection(it.getValue("id").jsonPrimitive.content, it.getValue("level").jsonPrimitive.int)
        }
        BuddyGrowthPlanStep(step.getValue("level").jsonPrimitive.int, step.getValue("profession").jsonPrimitive.content,
            step.getValue("seed").jsonPrimitive.long, step.getValue("nextSeed").jsonPrimitive.long,
            step.getValue("gains").jsonArray.map { it.jsonPrimitive.int },
            step["switchFrom"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content, ride)
    },
    terminalStats = value["terminalStats"]?.takeUnless { it is JsonNull }?.jsonArray?.map { it.jsonPrimitive.int },
    weightedScore = value["weightedScore"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.long,
    exploredStates = value.getValue("exploredStates").jsonPrimitive.int,
    warnings = value.getValue("warnings").jsonArray.map { it.jsonPrimitive.content },
    reason = value.getValue("reason").jsonPrimitive.content,
)
