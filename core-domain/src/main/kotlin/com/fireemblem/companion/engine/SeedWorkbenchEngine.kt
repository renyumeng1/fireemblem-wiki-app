package com.fireemblem.companion.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * Interactive RNG Preview & Cultivate Plan Workbench for Fire Emblem: Fortune's Weave.
 *
 * Parity + Android enhancements over `tools/excel-planner`:
 * - Continuous level-up preview all the way to Lv.99 (up to [MAX_PREVIEW_ROWS])
 * - Planning start overrides (missed-level auto-advance, extra exam/skip RNG advances, profession, ride)
 * - Per-row interactive skip/exam toggle (advances seed without leveling, shifting downstream Lv.35/45 tiers)
 * - Per-row trial profession override with unlock status & automatic ride compatibility fallback
 * - 9-stat user preference weights (0..5) + weighted score
 * - Sequential committed plan (UPGRADE / SKIP / CLASS_CHANGE / EXAM) + cumulative gains + execution cursor
 * - Android-exclusive: same-seed multi-class comparison across all allowed classes & smart preview filters
 * - Android-exclusive: quick manual observation builder for reverse seed search without Buddy JSON
 */
data class SeedWorkbenchStart(
    val characterId: String,
    val line: BuddyCampaignLine = BuddyCampaignLine.KAI,
    val part3: Boolean = false,
    val baseSeed: Long,
    val inferredLevel: Int,
    val startLevel: Int = inferredLevel,
    val extraAdvances: Int = 0,
    val profession: String,
    val ride: BuddyRideSelection? = null,
    val unlockedProfessions: Set<String> = setOf(profession),
    val baseStats: List<Int>? = null,
) {
    val missedLevels: Int
        get() = (startLevel - inferredLevel).coerceAtLeast(0)

    val totalPreAdvances: Int
        get() = missedLevels + extraAdvances.coerceAtLeast(0)
}

enum class SeedPlanActionKind(val id: String, val label: String) {
    UPGRADE("upgrade", "升级"),
    SKIP("skip", "跳过 / 考试"),
    CLASS_CHANGE("class_change", "转职"),
    EXAM("exam", "考试");

    companion object {
        fun fromId(id: String): SeedPlanActionKind =
            entries.firstOrNull { it.id == id } ?: UPGRADE
    }
}

data class SeedWorkbenchPlanStep(
    val kind: SeedPlanActionKind,
    val targetProfession: String = "",
    val examProbability: Double? = null,
)

data class SeedEvaluatedPlanRow(
    val stepIndex: Int, // 1-based
    val kind: SeedPlanActionKind,
    val fromLevel: Int,
    val nextLevel: Int,
    val professionBefore: String,
    val professionAfter: String,
    val effectiveRide: BuddyRideSelection?,
    val seedBefore: Long,
    val seedAfter: Long,
    val gains: List<Int>,
    val totalGain: Int,
    val weightedScore: Int,
    val examMinimumProbability: Int?,
    val examPassed: Boolean?,
    val cumulativeGains: List<Int>,
    val statusNote: String = "",
    val error: String? = null,
)

data class SeedPreviewOverride(
    val skipOrExam: Boolean = false,
    val trialProfession: String? = null,
) {
    val isDefault: Boolean get() = !skipOrExam && trialProfession == null
}

enum class TrialProfessionStatus(val label: String) {
    CURRENT("当前职业"),
    UNLOCKED_PLANABLE("已解锁 · 可入计划"),
    LOCKED_TRIAL_ONLY("未解锁 · 仅试算"),
}

data class SeedWorkbenchPreviewRow(
    val stepIndex: Int, // 1-based index in preview window
    val fromLevel: Int,
    val nextLevel: Int,
    val actionIsSkip: Boolean,
    val profession: String,
    val trialStatus: TrialProfessionStatus,
    val effectiveRide: BuddyRideSelection?,
    val rideDisabledByTrialClass: Boolean,
    val seed: Long,
    val nextSeed: Long,
    val gains: List<Int>,
    val totalGain: Int,
    val weightedScore: Int,
    val examMinimumProbability: Int,
    val cumulativeGainsFromStart: List<Int>,
) {
    val hasOverride: Boolean
        get() = actionIsSkip || trialStatus != TrialProfessionStatus.CURRENT
}

data class SeedPreviewFilter(
    val minTotalGain: Int = 0,
    val requiredStats: Set<GrowthStat> = emptySet(),
    val maxExamMinimum: Int? = null,
    val onlyModifiedRows: Boolean = false,
) {
    val isActive: Boolean
        get() = minTotalGain > 0 || requiredStats.isNotEmpty() || maxExamMinimum != null || onlyModifiedRows
}

data class SameSeedClassOption(
    val profession: String,
    val unlocked: Boolean,
    val isCurrent: Boolean,
    val effectiveRide: BuddyRideSelection?,
    val gains: List<Int>,
    val totalGain: Int,
    val weightedScore: Int,
    val growthRates: List<Int>,
)

data class SeedWorkbenchEvaluation(
    val start: SeedWorkbenchStart,
    val effectiveStartSeed: Long,
    val planRows: List<SeedEvaluatedPlanRow>,
    val planTotals: List<Int>,
    val planEndSeed: Long,
    val planEndLevel: Int,
    val planEndProfession: String,
    val planEndUnlocked: Set<String>,
    val previewRows: List<SeedWorkbenchPreviewRow>,
    val warnings: List<String>,
    val planError: String? = null,
) {
    val hasPlanError: Boolean get() = planError != null
}

data class SeedWorkbenchCommitResult(
    val planSteps: List<SeedWorkbenchPlanStep>,
    val shiftedOverrides: Map<Int, SeedPreviewOverride>,
    val addedStepCount: Int = 0,
    val error: String? = null,
)

data class QuickManualObservation(
    val level: Int,
    val profession: String,
    val gains: List<Int> = List(GrowthStat.entries.size) { 0 },
    val skipped: Boolean = false,
    val examProbability: Double? = null,
    val examPassed: Boolean? = null,
    val ride: BuddyRideSelection? = null,
)

object SeedWorkbenchEngine {
    const val MAX_LEVEL = 99
    const val MAX_PREVIEW_ROWS = 200
    const val MAX_PRE_ADVANCES = 1_000
    val DEFAULT_WEIGHTS: List<Int> = List(GrowthStat.entries.size) { 0 }

    fun normalizeWeights(weights: List<Int>): List<Int> =
        List(GrowthStat.entries.size) { idx -> (weights.getOrElse(idx) { 0 }).coerceIn(0, 5) }

    fun evaluate(
        rules: BuddyRules,
        start: SeedWorkbenchStart,
        planSteps: List<SeedWorkbenchPlanStep> = emptyList(),
        previewOverrides: Map<Int, SeedPreviewOverride> = emptyMap(),
        weights: List<Int> = DEFAULT_WEIGHTS,
        maxPreviewSteps: Int = 98,
    ): SeedWorkbenchEvaluation {
        require(start.baseSeed in 0 until SeedEngine.UINT32_LIMIT) { "种子应为 0 至 4,294,967,295 的整数" }
        require(start.inferredLevel in 1..MAX_LEVEL) { "推算等级应为 1 至 99" }
        require(start.startLevel in 1..MAX_LEVEL) { "起始等级应为 1 至 99" }
        require(start.extraAdvances in 0..MAX_PRE_ADVANCES) { "额外推进次数应为 0 至 $MAX_PRE_ADVANCES" }
        val normalizedWeights = normalizeWeights(weights)
        val character = requireNotNull(rules.characters.firstOrNull { it.id == start.characterId }) {
            "该角色暂不支持成长计算"
        }
        val effectivePart3 = start.part3 || character.part3Only
        require(rules.classAllowed(start.characterId, start.profession, effectivePart3)) {
            "该角色在当前篇章不能选择「${start.profession}」"
        }

        val warnings = mutableListOf<String>()
        if (start.startLevel < start.inferredLevel) {
            warnings += "起始等级（Lv.${start.startLevel}）低于记录推算等级（Lv.${start.inferredLevel}），未自动回退种子。"
        }
        val preAdvances = start.totalPreAdvances
        var currentSeed = start.baseSeed
        repeat(preAdvances) { currentSeed = SeedEngine.advance(currentSeed) }
        val effectiveStartSeed = currentSeed
        if (preAdvances > 0) {
            val parts = buildList {
                if (start.missedLevels > 0) add("漏记升级 ${start.missedLevels} 级")
                if (start.extraAdvances > 0) add("额外考试/跳过 ${start.extraAdvances} 次")
            }
            warnings += "已自动预推进 $preAdvances 步随机序列（${parts.joinToString(" + ")}）。"
        }

        var currentLevel = start.startLevel
        var currentProfession = start.profession
        val currentUnlocked = (start.unlockedProfessions + start.profession).toMutableSet()
        val runningTotals = IntArray(GrowthStat.entries.size)
        val evaluatedPlan = mutableListOf<SeedEvaluatedPlanRow>()
        var planError: String? = null

        val initialGrowth = rules.growth(
            BuddyGrowthInput(start.characterId, currentProfession, currentLevel.coerceAtMost(98), effectivePart3, start.line.rulesRoute, start.ride),
        )
        if (start.ride != null && initialGrowth.effectiveRide == null) {
            warnings += "当前坐骑不适用于「$currentProfession」，将按不骑乘计算（更换兼容职业后自动生效）。"
        }

        for ((index, step) in planSteps.withIndex()) {
            val stepNum = index + 1
            val seedBefore = currentSeed
            val levelBefore = currentLevel
            val profBefore = currentProfession
            when (step.kind) {
                SeedPlanActionKind.UPGRADE -> {
                    if (currentLevel >= MAX_LEVEL) {
                        planError = "第 $stepNum 步无法继续升级：已达到 Lv.$MAX_LEVEL。"
                        evaluatedPlan += SeedEvaluatedPlanRow(
                            stepIndex = stepNum,
                            kind = step.kind,
                            fromLevel = levelBefore,
                            nextLevel = levelBefore,
                            professionBefore = profBefore,
                            professionAfter = profBefore,
                            effectiveRide = null,
                            seedBefore = seedBefore,
                            seedAfter = seedBefore,
                            gains = List(9) { 0 },
                            totalGain = 0,
                            weightedScore = 0,
                            examMinimumProbability = null,
                            examPassed = null,
                            cumulativeGains = runningTotals.toList(),
                            error = planError,
                        )
                        break
                    }
                    val input = BuddyGrowthInput(
                        characterId = start.characterId,
                        profession = currentProfession,
                        level = currentLevel,
                        part3 = effectivePart3,
                        route = start.line.rulesRoute,
                        ride = start.ride,
                    )
                    val growth = rules.growth(input)
                    val sim = SeedEngine.simulateLevel(currentSeed, growth.parameters)
                    val examMin = SeedEngine.simulateExam(currentSeed, ExamParameters(100.0)).minimumProbability
                    for (i in 0 until 9) runningTotals[i] += sim.gains[i]
                    currentSeed = sim.nextSeed
                    currentLevel += 1
                    evaluatedPlan += SeedEvaluatedPlanRow(
                        stepIndex = stepNum,
                        kind = step.kind,
                        fromLevel = levelBefore,
                        nextLevel = currentLevel,
                        professionBefore = profBefore,
                        professionAfter = currentProfession,
                        effectiveRide = growth.effectiveRide,
                        seedBefore = seedBefore,
                        seedAfter = currentSeed,
                        gains = sim.gains,
                        totalGain = sim.gains.sum(),
                        weightedScore = score(sim.gains, normalizedWeights),
                        examMinimumProbability = examMin,
                        examPassed = null,
                        cumulativeGains = runningTotals.toList(),
                        statusNote = "Lv.$levelBefore → Lv.$currentLevel",
                    )
                }

                SeedPlanActionKind.SKIP -> {
                    val exam = SeedEngine.simulateExam(currentSeed, ExamParameters(100.0))
                    currentSeed = exam.nextSeed
                    evaluatedPlan += SeedEvaluatedPlanRow(
                        stepIndex = stepNum,
                        kind = step.kind,
                        fromLevel = levelBefore,
                        nextLevel = levelBefore,
                        professionBefore = profBefore,
                        professionAfter = currentProfession,
                        effectiveRide = null,
                        seedBefore = seedBefore,
                        seedAfter = currentSeed,
                        gains = List(9) { 0 },
                        totalGain = 0,
                        weightedScore = 0,
                        examMinimumProbability = exam.minimumProbability,
                        examPassed = null,
                        cumulativeGains = runningTotals.toList(),
                        statusNote = "跳过 / 考试推进种子（考试门槛 ≥ ${exam.minimumProbability}%）",
                    )
                }

                SeedPlanActionKind.CLASS_CHANGE -> {
                    val target = step.targetProfession.trim()
                    val allowed = target.isNotEmpty() && rules.classAllowed(start.characterId, target, effectivePart3)
                    val unlocked = target in currentUnlocked
                    if (!allowed || !unlocked) {
                        planError = if (!allowed) "第 $stepNum 步转职目标「$target」不适用于当前角色或篇章。"
                        else "第 $stepNum 步转职目标「$target」尚未解锁。"
                        evaluatedPlan += SeedEvaluatedPlanRow(
                            stepIndex = stepNum,
                            kind = step.kind,
                            fromLevel = levelBefore,
                            nextLevel = levelBefore,
                            professionBefore = profBefore,
                            professionAfter = profBefore,
                            effectiveRide = null,
                            seedBefore = seedBefore,
                            seedAfter = seedBefore,
                            gains = List(9) { 0 },
                            totalGain = 0,
                            weightedScore = 0,
                            examMinimumProbability = null,
                            examPassed = null,
                            cumulativeGains = runningTotals.toList(),
                            error = planError,
                        )
                        break
                    }
                    currentProfession = target
                    val checkGrowth = rules.growth(
                        BuddyGrowthInput(start.characterId, currentProfession, currentLevel.coerceAtMost(98), effectivePart3, start.line.rulesRoute, start.ride),
                    )
                    evaluatedPlan += SeedEvaluatedPlanRow(
                        stepIndex = stepNum,
                        kind = step.kind,
                        fromLevel = levelBefore,
                        nextLevel = levelBefore,
                        professionBefore = profBefore,
                        professionAfter = currentProfession,
                        effectiveRide = checkGrowth.effectiveRide,
                        seedBefore = seedBefore,
                        seedAfter = seedBefore,
                        gains = List(9) { 0 },
                        totalGain = 0,
                        weightedScore = 0,
                        examMinimumProbability = null,
                        examPassed = null,
                        cumulativeGains = runningTotals.toList(),
                        statusNote = "转职：$profBefore → $currentProfession（不推进种子）",
                    )
                }

                SeedPlanActionKind.EXAM -> {
                    val target = step.targetProfession.trim()
                    val prob = (step.examProbability ?: 100.0).coerceIn(0.0, 100.0)
                    val exam = SeedEngine.simulateExam(currentSeed, ExamParameters(prob))
                    currentSeed = exam.nextSeed
                    if (exam.passed && target.isNotEmpty() && rules.classAllowed(start.characterId, target, effectivePart3)) {
                        currentUnlocked += target
                    }
                    evaluatedPlan += SeedEvaluatedPlanRow(
                        stepIndex = stepNum,
                        kind = step.kind,
                        fromLevel = levelBefore,
                        nextLevel = levelBefore,
                        professionBefore = profBefore,
                        professionAfter = currentProfession,
                        effectiveRide = null,
                        seedBefore = seedBefore,
                        seedAfter = currentSeed,
                        gains = List(9) { 0 },
                        totalGain = 0,
                        weightedScore = 0,
                        examMinimumProbability = exam.minimumProbability,
                        examPassed = exam.passed,
                        cumulativeGains = runningTotals.toList(),
                        statusNote = buildString {
                            append("考试")
                            if (target.isNotEmpty()) append("「$target」")
                            append("（成功率 ${prob.toInt()}%，门槛 ≥ ${exam.minimumProbability}%）：")
                            append(if (exam.passed) "通过并解锁" else "未通过")
                        },
                    )
                }
            }
        }

        val planEndSeed = currentSeed
        val planEndLevel = currentLevel
        val planEndProfession = currentProfession
        val planEndUnlocked = currentUnlocked.toSet()
        val planTotals = runningTotals.toList()

        // Continuous interactive preview from end of plan up to Lv.99
        val previewRows = mutableListOf<SeedWorkbenchPreviewRow>()
        val cappedSteps = maxPreviewSteps.coerceIn(1, MAX_PREVIEW_ROWS)
        var previewSeed = planEndSeed
        var previewLevel = planEndLevel
        val previewCumulative = runningTotals.copyOf()
        var stepCounter = 0

        while (previewLevel < MAX_LEVEL && stepCounter < cappedSteps) {
            stepCounter++
            val override = previewOverrides[stepCounter]
            val isSkip = override?.skipOrExam == true
            val rawTrial = override?.trialProfession?.takeIf {
                it.isNotBlank() && rules.classAllowed(start.characterId, it, effectivePart3)
            }
            val rowProfession = rawTrial ?: planEndProfession
            val trialStatus = when {
                rawTrial == null || rawTrial == planEndProfession -> TrialProfessionStatus.CURRENT
                rawTrial in planEndUnlocked -> TrialProfessionStatus.UNLOCKED_PLANABLE
                else -> TrialProfessionStatus.LOCKED_TRIAL_ONLY
            }

            val growth = rules.growth(
                BuddyGrowthInput(
                    characterId = start.characterId,
                    profession = rowProfession,
                    level = previewLevel,
                    part3 = effectivePart3,
                    route = start.line.rulesRoute,
                    ride = start.ride,
                ),
            )
            val sim = SeedEngine.simulateLevel(previewSeed, growth.parameters)
            val examMin = SeedEngine.simulateExam(previewSeed, ExamParameters(100.0)).minimumProbability
            val nextLv = if (isSkip) previewLevel else previewLevel + 1
            if (!isSkip) {
                for (i in 0 until 9) previewCumulative[i] += sim.gains[i]
            }

            previewRows += SeedWorkbenchPreviewRow(
                stepIndex = stepCounter,
                fromLevel = previewLevel,
                nextLevel = nextLv,
                actionIsSkip = isSkip,
                profession = rowProfession,
                trialStatus = trialStatus,
                effectiveRide = growth.effectiveRide,
                rideDisabledByTrialClass = start.ride != null && growth.effectiveRide == null,
                seed = previewSeed,
                nextSeed = sim.nextSeed,
                gains = sim.gains,
                totalGain = sim.gains.sum(),
                weightedScore = score(sim.gains, normalizedWeights),
                examMinimumProbability = examMin,
                cumulativeGainsFromStart = previewCumulative.toList(),
            )
            previewSeed = sim.nextSeed
            previewLevel = nextLv
        }

        return SeedWorkbenchEvaluation(
            start = start.copy(part3 = effectivePart3),
            effectiveStartSeed = effectiveStartSeed,
            planRows = evaluatedPlan,
            planTotals = planTotals,
            planEndSeed = planEndSeed,
            planEndLevel = planEndLevel,
            planEndProfession = planEndProfession,
            planEndUnlocked = planEndUnlocked,
            previewRows = previewRows,
            warnings = warnings,
            planError = planError,
        )
    }

    fun commitPreviewUpTo(
        evaluation: SeedWorkbenchEvaluation,
        currentPlan: List<SeedWorkbenchPlanStep>,
        currentOverrides: Map<Int, SeedPreviewOverride>,
        targetStepIndex: Int,
    ): SeedWorkbenchCommitResult {
        if (evaluation.hasPlanError) {
            return SeedWorkbenchCommitResult(currentPlan, currentOverrides, error = "现有培养计划含错误，请先修正后再加入新步骤。")
        }
        val slice = evaluation.previewRows.filter { it.stepIndex <= targetStepIndex }
        if (slice.isEmpty()) {
            return SeedWorkbenchCommitResult(currentPlan, currentOverrides, error = "没有可加入计划的预览步骤。")
        }
        val lockedRow = slice.firstOrNull { !it.actionIsSkip && it.trialStatus == TrialProfessionStatus.LOCKED_TRIAL_ONLY }
        if (lockedRow != null) {
            return SeedWorkbenchCommitResult(
                currentPlan,
                currentOverrides,
                error = "第 ${lockedRow.stepIndex} 步试算职业「${lockedRow.profession}」尚未解锁；请先在已解锁职业中勾选，或先安排考试步骤。",
            )
        }

        val added = mutableListOf<SeedWorkbenchPlanStep>()
        var runningProfession = evaluation.planEndProfession
        for (row in slice) {
            if (row.actionIsSkip) {
                added += SeedWorkbenchPlanStep(SeedPlanActionKind.SKIP)
            } else {
                if (row.profession != runningProfession) {
                    added += SeedWorkbenchPlanStep(SeedPlanActionKind.CLASS_CHANGE, targetProfession = row.profession)
                    runningProfession = row.profession
                }
                added += SeedWorkbenchPlanStep(SeedPlanActionKind.UPGRADE)
            }
        }
        val shifted = buildMap {
            currentOverrides.forEach { (step, ov) ->
                if (step > targetStepIndex && !ov.isDefault) {
                    put(step - targetStepIndex, ov)
                }
            }
        }
        return SeedWorkbenchCommitResult(
            planSteps = currentPlan + added,
            shiftedOverrides = shifted,
            addedStepCount = added.size,
        )
    }

    fun compareProfessionsAtRow(
        rules: BuddyRules,
        start: SeedWorkbenchStart,
        rowSeed: Long,
        rowLevel: Int,
        currentProfession: String,
        unlockedProfessions: Set<String>,
        weights: List<Int> = DEFAULT_WEIGHTS,
    ): List<SameSeedClassOption> {
        val character = rules.characters.firstOrNull { it.id == start.characterId } ?: return emptyList()
        val effectivePart3 = start.part3 || character.part3Only
        val normalizedWeights = normalizeWeights(weights)
        val level = rowLevel.coerceIn(1, 98)

        return rules.professions
            .filter { rules.classAllowed(start.characterId, it.name, effectivePart3) }
            .map { prof ->
                val growth = rules.growth(
                    BuddyGrowthInput(
                        characterId = start.characterId,
                        profession = prof.name,
                        level = level,
                        part3 = effectivePart3,
                        route = start.line.rulesRoute,
                        ride = start.ride,
                    ),
                )
                val sim = SeedEngine.simulateLevel(rowSeed, growth.parameters)
                SameSeedClassOption(
                    profession = prof.name,
                    unlocked = prof.name in unlockedProfessions || prof.name == currentProfession,
                    isCurrent = prof.name == currentProfession,
                    effectiveRide = growth.effectiveRide,
                    gains = sim.gains,
                    totalGain = sim.gains.sum(),
                    weightedScore = score(sim.gains, normalizedWeights),
                    growthRates = growth.parameters.growth,
                )
            }
            .sortedWith(
                compareByDescending<SameSeedClassOption> { it.weightedScore }
                    .thenByDescending { it.totalGain }
                    .thenByDescending { it.unlocked }
                    .thenByDescending { it.isCurrent }
                    .thenBy { it.profession },
            )
    }

    fun filterPreview(
        rows: List<SeedWorkbenchPreviewRow>,
        filter: SeedPreviewFilter,
    ): List<SeedWorkbenchPreviewRow> {
        if (!filter.isActive) return rows
        return rows.filter { row ->
            val passTotal = filter.minTotalGain <= 0 || (!row.actionIsSkip && row.totalGain >= filter.minTotalGain)
            val passStats = filter.requiredStats.isEmpty() ||
                (!row.actionIsSkip && filter.requiredStats.all { stat -> row.gains[stat.ordinal] > 0 })
            val passExam = filter.maxExamMinimum == null || row.examMinimumProbability <= filter.maxExamMinimum
            val passModified = !filter.onlyModifiedRows || row.hasOverride
            passTotal && passStats && passExam && passModified
        }
    }

    fun manualObservationsToHistory(
        rules: BuddyRules,
        characterId: String,
        line: BuddyCampaignLine,
        part3: Boolean,
        observations: List<QuickManualObservation>,
    ): BuddySeedHistory {
        val character = rules.characters.firstOrNull { it.id == characterId }
            ?: return BuddySeedHistory(characterId, emptyList(), 0, 0, emptyList(), listOf("该角色暂不支持成长计算。"))
        val effectivePart3 = part3 || character.part3Only
        val events = mutableListOf<ObservedSeedEvent>()
        val errors = mutableListOf<String>()
        val seenProfessions = linkedSetOf<String>()
        var levelCount = 0
        var skipCount = 0
        var lastLevel: Int? = null
        var lastProfession: String? = null
        var lastRide: BuddyRideSelection? = null

        observations.forEachIndexed { idx, row ->
            val prefix = "第 ${idx + 1} 条："
            if (row.level !in 1..98) {
                errors += "${prefix}等级应在 1 至 98 之间。"
                return@forEachIndexed
            }
            if (!rules.classAllowed(characterId, row.profession, effectivePart3)) {
                errors += "${prefix}职业「${row.profession}」不适用于当前角色或篇章。"
                return@forEachIndexed
            }
            seenProfessions += row.profession
            lastProfession = row.profession
            lastRide = row.ride
            when {
                row.examProbability != null && row.examPassed != null -> {
                    events += ObservedSeedEvent.Exam(ExamParameters(row.examProbability), row.examPassed)
                    lastLevel = row.level
                }
                row.skipped -> {
                    events += ObservedSeedEvent.Advance()
                    skipCount++
                    lastLevel = row.level
                }
                else -> {
                    val growth = runCatching {
                        rules.growth(
                            BuddyGrowthInput(characterId, row.profession, row.level, effectivePart3, line.rulesRoute, row.ride),
                        )
                    }.getOrElse {
                        errors += "${prefix}${it.message ?: "成长参数无效"}"
                        return@forEachIndexed
                    }
                    events += ObservedSeedEvent.Level(growth.parameters, row.gains)
                    levelCount++
                    lastLevel = (row.level + 1).coerceAtMost(MAX_LEVEL)
                }
            }
        }
        val warnings = if (levelCount < BuddySeedHistory.MIN_OBSERVED_LEVELS) {
            listOf("至少录入 ${BuddySeedHistory.MIN_OBSERVED_LEVELS} 次实测升级后才能反推；当前 $levelCount 次。")
        } else emptyList()
        val endState = if (lastLevel != null && lastProfession != null) {
            BuddySeedHistoryEndState(
                level = lastLevel,
                profession = lastProfession,
                part3 = effectivePart3,
                line = line,
                ride = lastRide,
                unlockedProfessions = seenProfessions.toSet(),
            )
        } else null
        return BuddySeedHistory(characterId, events, levelCount, skipCount, warnings, errors, endState)
    }

    private fun score(gains: List<Int>, weights: List<Int>): Int {
        var total = 0
        for (i in gains.indices) total += gains[i] * weights[i]
        return total
    }
}

/**
 * Serializable snapshot of an interactive workbench plan stored in `PersonalStore` under kind `"seed-workbench"`.
 */
data class SeedWorkbenchRecord(
    val profileId: String,
    val characterId: String,
    val line: BuddyCampaignLine,
    val part3: Boolean,
    val baseSeed: Long,
    val inferredLevel: Int,
    val startLevel: Int,
    val extraAdvances: Int,
    val profession: String,
    val ride: BuddyRideSelection?,
    val unlockedProfessions: Set<String>,
    val weights: List<Int>,
    val planSteps: List<SeedWorkbenchPlanStep>,
    val previewOverrides: Map<Int, SeedPreviewOverride>,
    val completedSteps: Int,
) {
    fun toStart(): SeedWorkbenchStart = SeedWorkbenchStart(
        characterId = characterId,
        line = line,
        part3 = part3,
        baseSeed = baseSeed,
        inferredLevel = inferredLevel,
        startLevel = startLevel,
        extraAdvances = extraAdvances,
        profession = profession,
        ride = ride,
        unlockedProfessions = unlockedProfessions + profession,
    )

    fun encode(): JsonObject = buildJsonObject {
        put("version", 1)
        put("profileId", profileId)
        put("characterId", characterId)
        put("line", line.id)
        put("part3", part3)
        put("baseSeed", baseSeed)
        put("inferredLevel", inferredLevel)
        put("startLevel", startLevel)
        put("extraAdvances", extraAdvances)
        put("profession", profession)
        put("ride", ride?.let { r -> buildJsonObject { put("id", r.id); put("level", r.level) } } ?: JsonNull)
        put("unlockedProfessions", JsonArray(unlockedProfessions.sorted().map(::JsonPrimitive)))
        put("weights", JsonArray(SeedWorkbenchEngine.normalizeWeights(weights).map(::JsonPrimitive)))
        put("planSteps", JsonArray(planSteps.map { step ->
            buildJsonObject {
                put("kind", step.kind.id)
                put("targetProfession", step.targetProfession)
                step.examProbability?.let { put("examProbability", it) }
            }
        }))
        put("previewOverrides", JsonArray(previewOverrides.entries.sortedBy { it.key }.map { (idx, ov) ->
            buildJsonObject {
                put("step", idx)
                put("skipOrExam", ov.skipOrExam)
                ov.trialProfession?.let { put("trialProfession", it) }
            }
        }))
        put("completedSteps", completedSteps.coerceIn(0, planSteps.size))
    }

    companion object {
        const val KIND = "seed-workbench"

        fun entityId(profileId: String, characterId: String, line: BuddyCampaignLine): String =
            java.util.UUID.nameUUIDFromBytes("seed-workbench|$profileId|$characterId|${line.id}".toByteArray(Charsets.UTF_8)).toString()

        fun decode(payload: JsonObject): SeedWorkbenchRecord {
            val lineId = payload["line"]?.jsonPrimitive?.content ?: BuddyCampaignLine.KAI.id
            val line = BuddyCampaignLine.entries.firstOrNull { it.id == lineId } ?: BuddyCampaignLine.KAI
            val rideObj = payload["ride"] as? JsonObject
            val ride = rideObj?.let {
                val id = it["id"]?.jsonPrimitive?.content ?: return@let null
                val lv = it["level"]?.jsonPrimitive?.intOrNull ?: 1
                runCatching { BuddyRideSelection(id, lv) }.getOrNull()
            }
            val steps = (payload["planSteps"] as? JsonArray).orEmpty().mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                SeedWorkbenchPlanStep(
                    kind = SeedPlanActionKind.fromId(obj["kind"]?.jsonPrimitive?.content.orEmpty()),
                    targetProfession = obj["targetProfession"]?.jsonPrimitive?.content.orEmpty(),
                    examProbability = obj["examProbability"]?.jsonPrimitive?.doubleOrNull,
                )
            }
            val overrides = buildMap {
                (payload["previewOverrides"] as? JsonArray).orEmpty().forEach { el ->
                    val obj = el as? JsonObject ?: return@forEach
                    val step = obj["step"]?.jsonPrimitive?.intOrNull ?: return@forEach
                    val skip = obj["skipOrExam"]?.jsonPrimitive?.booleanOrNull == true
                    val trial = obj["trialProfession"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
                    val ov = SeedPreviewOverride(skip, trial)
                    if (!ov.isDefault) put(step, ov)
                }
            }
            val profession = payload["profession"]?.jsonPrimitive?.content.orEmpty()
            val unlocked = (payload["unlockedProfessions"] as? JsonArray).orEmpty()
                .mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
                .filter(String::isNotBlank)
                .toSet() + profession
            val weights = (payload["weights"] as? JsonArray).orEmpty()
                .mapNotNull { it.jsonPrimitive.intOrNull }
            return SeedWorkbenchRecord(
                profileId = payload["profileId"]?.jsonPrimitive?.content ?: "global",
                characterId = payload["characterId"]?.jsonPrimitive?.content.orEmpty(),
                line = line,
                part3 = payload["part3"]?.jsonPrimitive?.boolean == true,
                baseSeed = payload["baseSeed"]?.jsonPrimitive?.long ?: 0L,
                inferredLevel = payload["inferredLevel"]?.jsonPrimitive?.int ?: 1,
                startLevel = payload["startLevel"]?.jsonPrimitive?.int ?: 1,
                extraAdvances = payload["extraAdvances"]?.jsonPrimitive?.int ?: 0,
                profession = profession,
                ride = ride,
                unlockedProfessions = unlocked,
                weights = SeedWorkbenchEngine.normalizeWeights(weights),
                planSteps = steps,
                previewOverrides = overrides,
                completedSteps = (payload["completedSteps"]?.jsonPrimitive?.int ?: 0).coerceIn(0, steps.size),
            )
        }
    }
}
