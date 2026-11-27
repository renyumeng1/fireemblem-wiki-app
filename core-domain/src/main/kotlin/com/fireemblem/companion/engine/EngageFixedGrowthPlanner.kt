package com.fireemblem.companion.engine

enum class EngageStat(val label: String, val shortLabel: String) {
    HP("HP", "HP"),
    STRENGTH("力量", "力"),
    MAGIC("魔力", "魔"),
    DEXTERITY("技巧", "技"),
    SPEED("速度", "速"),
    LUCK("幸运", "幸"),
    DEFENSE("防守", "防"),
    RESISTANCE("魔防", "魔防"),
    BUILD("体格", "体"),
}

data class EngageCharacterData(
    val entryId: String,
    val name: String,
    val affiliation: String = "",
    val initialLevel: Int = 1,
    val internalLevel: Int = 0,
    val initialClassName: String = "",
    /** Displayed stats upon recruitment (personal + initialClass.baseStats). */
    val baseStats: List<Int>,
    /** Personal growth rates (%) in order of [EngageStat.entries]. */
    val personalGrowth: List<Int>,
    /** Personal stat cap modifiers added on top of class caps. */
    val capModifiers: List<Int> = List(EngageStat.entries.size) { 0 },
    val hasExpertise: Boolean = name.contains("贾恩"),
) {
    init {
        require(baseStats.size == EngageStat.entries.size) { "Engage 初始属性必须包含 9 项" }
        require(personalGrowth.size == EngageStat.entries.size) { "Engage 个人成长率必须包含 9 项" }
        require(capModifiers.size == EngageStat.entries.size) { "Engage 属性上限补正必须包含 9 项" }
    }
}

data class EngageClassData(
    val entryId: String,
    val name: String,
    /** 基础 / 高级 / 特殊 */
    val tier: String,
    val movement: Int = 4,
    val battleStyle: String = "",
    /** Class base stat contribution (`兵种基础能力修正`). */
    val baseStats: List<Int>,
    /** Class growth rates (%). */
    val growth: List<Int>,
    /** Class stat caps (`兵种上限数值`). */
    val caps: List<Int>,
    val weaponRequirement: String = "",
    val classSkill: String = "",
    val restrictionNote: String = "",
) {
    init {
        require(baseStats.size == EngageStat.entries.size) { "Engage 职业基础属性必须包含 9 项" }
        require(growth.size == EngageStat.entries.size) { "Engage 职业成长率必须包含 9 项" }
        require(caps.size == EngageStat.entries.size) { "Engage 职业属性上限必须包含 9 项" }
    }

    val maxLevel: Int
        get() = when (tier) {
            "特殊" -> 40
            else -> 20
        }
}

data class EngageStageSpec(
    val className: String,
    /** Number of level-ups to perform while in this class. */
    val levelsToGain: Int,
    /** Whether Tiki's Starsphere (+15% to all 9 growths) is equipped during this stage. */
    val starsphere: Boolean = false,
)

data class EngageFixedGrowthRequest(
    val characterName: String,
    /** Optional custom multi-stage class sequence; when empty, auto-plans optimal legal promotion path. */
    val stages: List<EngageStageSpec> = emptyList(),
    /** Used when [stages] is empty: total number of level-ups to simulate (default 25). */
    val totalLevelUps: Int = 25,
    /** Global Starsphere (+15% all growths) toggle used for auto-planning or fallback. */
    val starsphere: Boolean = false,
    /** Stat weights used for auto-class selection and alternative class ranking. */
    val weights: List<Int> = List(EngageStat.entries.size) { 1 },
    /** Optional starting displayed stats override (defaults to character recruitment stats). */
    val currentStatsOverride: List<Int>? = null,
)

data class EngageClassChangeEvent(
    val afterLevelUps: Int,
    val totalInternalLevel: Int,
    val fromClass: String,
    val toClass: String,
    val sealLabel: String,
    val statDiffs: List<Int>,
    val requirement: String,
    val classSkill: String,
)

data class EngageStageSummary(
    val className: String,
    val tier: String,
    val levelsGained: Int,
    val levelFromInClass: Int,
    val levelToInClass: Int,
    val starsphere: Boolean,
    val effectiveRates: List<Int>,
    val effectiveCaps: List<Int>,
    val weaponRequirement: String,
    val classSkill: String,
)

data class EngageLevelStep(
    val stepIndex: Int, // 1-based cumulative level-up count
    val className: String,
    val levelFromInClass: Int,
    val levelToInClass: Int,
    val totalInternalLevel: Int,
    val switchedFromClass: String?,
    val classChangeDiffs: List<Int>?,
    val gains: List<Int>,
    val statsAfter: List<Int>,
    val accumulatorsAfter: List<Int>,
    val cappedIndices: List<Int>,
)

data class EngageFixedGrowthReport(
    val characterName: String,
    val affiliation: String,
    val initialClassName: String,
    val initialLevel: Int,
    val initialInternalLevel: Int,
    val hasExpertise: Boolean,
    val usedStarsphere: Boolean,
    val totalLevelUps: Int,
    val finalClassName: String,
    val finalTotalInternalLevel: Int,
    val weights: List<Int>,
    val startStats: List<Int>,
    val initialAccumulators: List<Int>,
    val finalStats: List<Int>,
    val finalAccumulators: List<Int>,
    val finalCaps: List<Int>,
    val totalNetGains: List<Int>,
    val stages: List<EngageStageSummary>,
    val classChanges: List<EngageClassChangeEvent>,
    val levelSteps: List<EngageLevelStep>,
    val alternativeAdvancedClasses: List<Pair<String, Long>>,
    val warnings: List<String>,
)

object EngageRules {
    private val FEMALE_CHARACTERS = setOf(
        "芙岚", "锡莉奴", "克萝艾", "艾媞耶", "拉琵思", "希特丽妮卡", "尤娜卡",
        "艾比", "奥尔坦希亚", "蜜丝提拉", "梅琳", "帕涅托涅", "戈尔德玛莉",
        "罗萨德", "洁德", "萨菲尔", "安娜", "贝珥", "赛勒斯提雅", "玛德琳",
    )

    private val EXCLUSIVE_CLASS_OWNERS: Map<String, String> = mapOf(
        "神龙之子" to "琉尔",
        "神龙之王" to "琉尔",
        "王室贵族男" to "阿尔弗雷德",
        "王室贵族（阿尔弗雷德）" to "阿尔弗雷德",
        "未来之王" to "阿尔弗雷德",
        "王室贵族女" to "锡莉奴",
        "王室贵族（锡莉奴）" to "锡莉奴",
        "弗罗剌祝" to "锡莉奴",
        "弗罗刺祝" to "锡莉奴",
        "领主剑" to "帝亚曼德",
        "领主（帝亚曼德）" to "帝亚曼德",
        "继承者" to "帝亚曼德",
        "领主弓" to "史塔卢克",
        "领主（史塔卢克）" to "史塔卢克",
        "狙击兵" to "史塔卢克",
        "驯兽师" to "艾比",
        "驯兽师（艾比）" to "艾比",
        "林德沃姆" to "艾比",
        "驯兽师（奥尔坦希亚）" to "奥尔坦希亚",
        "斯雷普尼尔" to "奥尔坦希亚",
        "义警队" to "佛贾特",
        "义警队（佛贾特）" to "佛贾特",
        "邱比特" to "佛贾特",
        "义警队（蜜丝提拉）" to "蜜丝提拉",
        "草叉枪兵" to "蜜丝提拉",
        "舞蹈家" to "赛安达斯",
        "邪龙之女" to "贝珥",
        "邪龙之子" to "拉法尔",
        "美露莘" to "赛勒斯提雅",
    )

    fun isClassAllowed(character: EngageCharacterData, cls: EngageClassData): Boolean {
        if (cls.tier == "转职机制说明") return false
        val owner = EXCLUSIVE_CLASS_OWNERS[cls.name]
        if (owner != null && !character.name.contains(owner)) return false
        val note = cls.restrictionNote
        if (note.contains("限定")) {
            if (note.contains("女性限定")) {
                if (!character.name.contains("琉尔") && FEMALE_CHARACTERS.none { character.name.contains(it) }) return false
            } else {
                // Check explicit character lock in restrictionNote e.g. "琉尔限定"
                val lockOwner = note.substringBefore("限定").substringAfterLast("/").trim()
                if (lockOwner.isNotEmpty() && !character.name.contains(lockOwner)) return false
            }
        }
        return true
    }

    fun normalizeClassName(raw: String): String = raw
        .substringBefore("【")
        .replace("弗罗刺祝", "弗罗剌祝")
        .trim()
}

/**
 * 100% Deterministic Fire Emblem: Engage Fixed Growth Engine.
 *
 * Implements the exact Maddening / Fixed Growth accumulator formula:
 * - Initial hidden accumulator = personalGrowth[i] % 100
 * - Personal base stats = joinDisplayedStats - startClass.baseStats
 * - Per level-up: acc[i] += personalGrowth[i] + classGrowth[i] * (2 if Jean Expertise else 1) + (15 if Starsphere else 0)
 * - Stat gain = acc[i] / 100 (clamped by effectiveCap = classCap + personalCapMod), remainder = acc[i] % 100
 * - Class change swaps classBaseStats cleanly while preserving hidden accumulators intact.
 */
object EngageFixedGrowthPlanner {
    private const val STAT_COUNT = 9
    private const val STARSPHERE_BONUS = 15

    fun effectiveRates(
        character: EngageCharacterData,
        cls: EngageClassData,
        starsphere: Boolean,
    ): List<Int> {
        val classMult = if (character.hasExpertise) 2 else 1
        val starBonus = if (starsphere) STARSPHERE_BONUS else 0
        return List(STAT_COUNT) { i ->
            (character.personalGrowth[i] + cls.growth[i] * classMult + starBonus).coerceAtLeast(0)
        }
    }

    fun effectiveCaps(character: EngageCharacterData, cls: EngageClassData): List<Int> =
        List(STAT_COUNT) { i -> (cls.caps[i] + character.capModifiers[i]).coerceAtLeast(1) }

    fun solve(
        request: EngageFixedGrowthRequest,
        characters: List<EngageCharacterData>,
        classes: List<EngageClassData>,
    ): EngageFixedGrowthReport {
        val character = resolveCharacter(request.characterName, characters)
            ?: throw IllegalArgumentException("在《Engage》资料库中未找到角色「${request.characterName}」")
        val legalClasses = classes.filter { EngageRules.isClassAllowed(character, it) }
        require(legalClasses.isNotEmpty()) { "未找到角色「${character.name}」可用的《Engage》职业列表" }

        val startClass = resolveStartingClass(character, classes)
            ?: legalClasses.first()
        val weights = normalizeWeights(request.weights)
        val warnings = mutableListOf<String>()

        val startDisplayed = request.currentStatsOverride?.takeIf { it.size == STAT_COUNT } ?: character.baseStats
        // Personal stats separate from current class base stats
        val personalStats = IntArray(STAT_COUNT) { i -> startDisplayed[i] - startClass.baseStats[i] }
        val displayed = startDisplayed.toIntArray()
        val initAcc = character.personalGrowth.map { it.coerceAtLeast(0) % 100 }
        val acc = initAcc.toIntArray()

        val resolvedStages: List<Pair<EngageClassData, Pair<Int, Boolean>>> = if (request.stages.isNotEmpty()) {
            request.stages.map { spec ->
                val cls = resolveClass(spec.className, legalClasses)
                    ?: resolveClass(spec.className, classes)?.also {
                        warnings += "职业「${it.name}」存在专属/限定条件，已按用户指定强制试算。"
                    }
                    ?: throw IllegalArgumentException("在《Engage》职业库中未找到「${spec.className}」")
                cls to (spec.levelsToGain.coerceIn(0, 60) to spec.starsphere)
            }
        } else {
            autoPlanStages(character, startClass, legalClasses, request.totalLevelUps.coerceIn(1, 60), request.starsphere, weights)
        }

        // Enforce Engage Master Seal rule check for Base -> Advanced promotions before Lv.10
        val stageSummaries = mutableListOf<EngageStageSummary>()
        val classChanges = mutableListOf<EngageClassChangeEvent>()
        val levelSteps = mutableListOf<EngageLevelStep>()

        var currentClass = startClass
        var levelInClass = character.initialLevel.coerceAtLeast(1)
        var totalInternal = character.internalLevel + levelInClass
        var totalStepCounter = 0

        resolvedStages.forEachIndexed { stageIdx, (targetClass, cfg) ->
            val (levelsToGain, starsphere) = cfg
            var pendingSwitchFrom: String? = null
            var pendingSwitchDiffs: List<Int>? = null

            if (targetClass.name != currentClass.name) {
                val sealLabel = when {
                    currentClass.tier == "基础" && targetClass.tier == "高级" -> {
                        if (levelInClass < 10) {
                            warnings += "提示：「${currentClass.name}」需达到 Lv.10 才可使用大师证晋升至「${targetClass.name}」（当前试算在 Lv.$levelInClass 切入）。"
                        }
                        "大师证晋升"
                    }
                    targetClass.tier == "高级" && currentClass.tier == "高级" -> "转职证平转"
                    else -> "转职证转换"
                }
                val newCaps = effectiveCaps(character, targetClass)
                val diffs = IntArray(STAT_COUNT)
                for (i in 0 until STAT_COUNT) {
                    val prevVal = displayed[i]
                    val swapped = (personalStats[i] + targetClass.baseStats[i]).coerceIn(targetClass.baseStats[i], newCaps[i])
                    diffs[i] = swapped - prevVal
                    displayed[i] = swapped
                    personalStats[i] = swapped - targetClass.baseStats[i]
                }
                classChanges += EngageClassChangeEvent(
                    afterLevelUps = totalStepCounter,
                    totalInternalLevel = totalInternal,
                    fromClass = currentClass.name,
                    toClass = targetClass.name,
                    sealLabel = sealLabel,
                    statDiffs = diffs.toList(),
                    requirement = targetClass.weaponRequirement.ifBlank { "大师证 / 转职证" },
                    classSkill = targetClass.classSkill,
                )
                pendingSwitchFrom = currentClass.name
                pendingSwitchDiffs = diffs.toList()
                currentClass = targetClass
                levelInClass = 1
            } else if (stageIdx > 0 && levelInClass >= currentClass.maxLevel) {
                // Second Seal loop at max level
                levelInClass = 1
            }

            val effRates = effectiveRates(character, currentClass, starsphere)
            val effCaps = effectiveCaps(character, currentClass)
            val stageStartLv = levelInClass

            repeat(levelsToGain) {
                if (levelInClass >= currentClass.maxLevel) {
                    // Auto Second Seal reset on max class level (20 for Base/Adv, 40 for Special)
                    levelInClass = 1
                }
                val lvFrom = levelInClass
                val lvTo = lvFrom + 1
                levelInClass = lvTo
                totalInternal += 1
                totalStepCounter += 1

                val gains = IntArray(STAT_COUNT)
                val hitCaps = mutableListOf<Int>()
                for (i in 0 until STAT_COUNT) {
                    val room = (effCaps[i] - displayed[i]).coerceAtLeast(0)
                    if (room <= 0) {
                        acc[i] = 0
                        hitCaps += i
                        continue
                    }
                    val sum = acc[i] + effRates[i]
                    val rawGain = sum / 100
                    val actualGain = minOf(rawGain, room)
                    gains[i] = actualGain
                    displayed[i] += actualGain
                    personalStats[i] += actualGain
                    if (displayed[i] >= effCaps[i]) {
                        acc[i] = 0
                        hitCaps += i
                    } else {
                        acc[i] = sum % 100
                    }
                }

                levelSteps += EngageLevelStep(
                    stepIndex = totalStepCounter,
                    className = currentClass.name,
                    levelFromInClass = lvFrom,
                    levelToInClass = lvTo,
                    totalInternalLevel = totalInternal,
                    switchedFromClass = pendingSwitchFrom,
                    classChangeDiffs = pendingSwitchDiffs,
                    gains = gains.toList(),
                    statsAfter = displayed.toList(),
                    accumulatorsAfter = acc.toList(),
                    cappedIndices = hitCaps,
                )
                pendingSwitchFrom = null
                pendingSwitchDiffs = null
            }

            stageSummaries += EngageStageSummary(
                className = currentClass.name,
                tier = currentClass.tier,
                levelsGained = levelsToGain,
                levelFromInClass = stageStartLv,
                levelToInClass = levelInClass,
                starsphere = starsphere,
                effectiveRates = effRates,
                effectiveCaps = effCaps,
                weaponRequirement = currentClass.weaponRequirement,
                classSkill = currentClass.classSkill,
            )
        }

        // Rank top alternative legal Advanced/Special classes for comparison
        val finalClassName = currentClass.name
        val altClasses = legalClasses
            .filter { it.tier in setOf("高级", "特殊") && it.name != finalClassName }
            .map { alt ->
                val simScore = quickScoreAdvanced(character, startClass, alt, request.totalLevelUps.coerceIn(1, 40), request.starsphere, weights)
                alt.name to simScore
            }
            .sortedByDescending { it.second }
            .take(4)

        val finalCaps = effectiveCaps(character, currentClass)
        val netGains = List(STAT_COUNT) { i -> displayed[i] - startDisplayed[i] }

        return EngageFixedGrowthReport(
            characterName = character.name,
            affiliation = character.affiliation,
            initialClassName = startClass.name,
            initialLevel = character.initialLevel,
            initialInternalLevel = character.internalLevel,
            hasExpertise = character.hasExpertise,
            usedStarsphere = resolvedStages.any { it.second.second },
            totalLevelUps = totalStepCounter,
            finalClassName = finalClassName,
            finalTotalInternalLevel = totalInternal,
            weights = weights,
            startStats = startDisplayed,
            initialAccumulators = initAcc,
            finalStats = displayed.toList(),
            finalAccumulators = acc.toList(),
            finalCaps = finalCaps,
            totalNetGains = netGains,
            stages = stageSummaries,
            classChanges = classChanges,
            levelSteps = levelSteps,
            alternativeAdvancedClasses = altClasses,
            warnings = warnings,
        )
    }

    private fun autoPlanStages(
        character: EngageCharacterData,
        startClass: EngageClassData,
        legalClasses: List<EngageClassData>,
        totalLevelUps: Int,
        starsphere: Boolean,
        weights: List<Int>,
    ): List<Pair<EngageClassData, Pair<Int, Boolean>>> {
        val levelsToTen = if (startClass.tier == "基础" && character.initialLevel < 10) {
            minOf(10 - character.initialLevel, totalLevelUps)
        } else 0
        val remainingAfterPromo = (totalLevelUps - levelsToTen).coerceAtLeast(0)
        if (remainingAfterPromo == 0) {
            return listOf(startClass to (totalLevelUps to starsphere))
        }
        val promoPool = legalClasses.filter { it.tier in setOf("高级", "特殊") }.ifEmpty { legalClasses }
        val bestAdv = promoPool.maxByOrNull { cand ->
            quickScoreAdvanced(character, startClass, cand, totalLevelUps, starsphere, weights)
        } ?: startClass

        return buildList {
            if (levelsToTen > 0) add(startClass to (levelsToTen to starsphere))
            add(bestAdv to (remainingAfterPromo to starsphere))
        }
    }

    private fun quickScoreAdvanced(
        character: EngageCharacterData,
        startClass: EngageClassData,
        targetAdv: EngageClassData,
        totalLevelUps: Int,
        starsphere: Boolean,
        weights: List<Int>,
    ): Long {
        val levelsBeforePromo = if (startClass.tier == "基础" && character.initialLevel < 10) {
            minOf(10 - character.initialLevel, totalLevelUps)
        } else 0
        val levelsAfterPromo = (totalLevelUps - levelsBeforePromo).coerceAtLeast(0)
        val rates1 = effectiveRates(character, startClass, starsphere)
        val rates2 = effectiveRates(character, targetAdv, starsphere)
        val caps2 = effectiveCaps(character, targetAdv)
        var score = 0L
        for (i in 0 until STAT_COUNT) {
            val accSum = (character.personalGrowth[i] % 100) + rates1[i] * levelsBeforePromo + rates2[i] * levelsAfterPromo
            val personalGain = accSum / 100
            val personalBase = character.baseStats[i] - startClass.baseStats[i]
            val finalVal = (personalBase + personalGain + targetAdv.baseStats[i]).coerceIn(targetAdv.baseStats[i], caps2[i])
            score += finalVal.toLong() * weights[i].toLong()
        }
        return score
    }

    fun resolveCharacter(query: String, characters: List<EngageCharacterData>): EngageCharacterData? {
        val q = query.trim()
        if (q.isEmpty()) return null
        return characters.firstOrNull { it.name == q }
            ?: characters.firstOrNull { it.name.substringBefore("（") == q }
            ?: characters.firstOrNull { it.name.contains(q, ignoreCase = true) || q.contains(it.name.substringBefore("（"), ignoreCase = true) }
    }

    fun resolveStartingClass(character: EngageCharacterData, classes: List<EngageClassData>): EngageClassData? {
        val clean = EngageRules.normalizeClassName(character.initialClassName)
        if (clean.isEmpty()) return null
        // Exact match first, then handle character-qualified variants like "领主（史塔卢克）" / "义警队（佛贾特）"
        val shortChar = character.name.substringBefore("（").trim()
        return classes.firstOrNull { it.name == clean }
            ?: classes.firstOrNull { it.name == "${clean.substringBefore("（")}（$shortChar）" }
            ?: classes.firstOrNull { it.name.startsWith(clean.substringBefore("（")) && it.restrictionNote.contains(shortChar) }
            ?: classes.firstOrNull { it.name.startsWith(clean.substringBefore("（")) }
    }

    fun resolveClass(query: String, classes: List<EngageClassData>): EngageClassData? {
        val q = EngageRules.normalizeClassName(query)
        if (q.isEmpty()) return null
        return classes.firstOrNull { it.name == q }
            ?: classes.firstOrNull { it.name.substringBefore("（") == q }
            ?: classes.firstOrNull { it.name.contains(q, ignoreCase = true) }
    }

    private fun normalizeWeights(weights: List<Int>): List<Int> {
        val out = List(STAT_COUNT) { idx -> weights.getOrElse(idx) { 1 }.coerceIn(0, 100) }
        return if (out.all { it == 0 }) List(STAT_COUNT) { 1 } else out
    }
}
