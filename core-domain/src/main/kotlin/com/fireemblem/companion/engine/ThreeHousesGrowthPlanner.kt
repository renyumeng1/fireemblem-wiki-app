package com.fireemblem.companion.engine

import kotlin.math.abs

enum class ThreeHousesStat(val label: String, val shortLabel: String) {
    HP("HP", "HP"),
    STRENGTH("力量", "力"),
    MAGIC("魔力", "魔"),
    DEXTERITY("技巧", "技"),
    SPEED("速度", "速"),
    LUCK("幸运", "幸"),
    DEFENSE("防守", "防"),
    RESISTANCE("魔防", "魔防"),
    CHARM("魅力", "魅"),
}

enum class ThreeHousesGender(val label: String) {
    MALE("男"),
    FEMALE("女"),
    ANY("男女可选"),
}

data class ThreeHousesCharacterData(
    val entryId: String,
    val name: String,
    val englishName: String = "",
    val affiliation: String = "",
    val gender: ThreeHousesGender = ThreeHousesRules.inferGender(name, englishName),
    val startLevel: Int = 1,
    val startClassName: String = "平民",
    /** Displayed base stats at [startLevel] in order of [ThreeHousesStat.entries]. */
    val baseStats: List<Int>,
    /** Personal growth rates (%) in order of [ThreeHousesStat.entries]. */
    val personalGrowth: List<Int>,
    /** Students & Byleth receive the >=2 stat level-up reroll guarantee. */
    val hasTwoStatReroll: Boolean = ThreeHousesRules.hasTwoStatReroll(name, affiliation),
) {
    init {
        require(baseStats.size == ThreeHousesStat.entries.size) { "风花雪月初始属性必须包含 9 项" }
        require(personalGrowth.size == ThreeHousesStat.entries.size) { "风花雪月个人成长率必须包含 9 项" }
    }
}

data class ThreeHousesClassData(
    val entryId: String,
    val name: String,
    val englishName: String = "",
    val tier: String,
    val minLevel: Int = ThreeHousesRules.minLevelForClass(name, tier),
    val movement: Int = 4,
    val movementType: String = "步兵",
    /** Permanent certification base-stat floors (`转职保底属性`). */
    val baseFloors: List<Int>,
    /** Class growth-rate modifiers (%) added to personal growth. */
    val growthBonus: List<Int>,
    /** Active class stat bonuses added while deployed in this class (`职业能力补正`). */
    val statBonuses: List<Int> = List(ThreeHousesStat.entries.size) { 0 },
    val promotionRequirement: String = "",
    val classSkill: String = "",
    val note: String = "",
) {
    init {
        require(baseFloors.size == ThreeHousesStat.entries.size) { "职业保底属性必须包含 9 项" }
        require(growthBonus.size == ThreeHousesStat.entries.size) { "职业成长补正必须包含 9 项" }
        require(statBonuses.size == ThreeHousesStat.entries.size) { "职业能力补正必须包含 9 项" }
    }
}

data class ThreeHousesStageSpec(
    val className: String,
    val fromLevel: Int,
    val toLevel: Int,
)

data class ThreeHousesCertDipSpec(
    val atLevel: Int,
    val className: String,
)

data class ThreeHousesPlanRequest(
    val characterName: String,
    val startLevel: Int? = null,
    val targetLevel: Int = 40,
    val currentStats: List<Int>? = null,
    val genderOverride: ThreeHousesGender? = null,
    val weights: List<Int> = List(ThreeHousesStat.entries.size) { 1 },
    /** Optional explicit class-path stages; when empty, automatically recommends an optimal legal path. */
    val customStages: List<ThreeHousesStageSpec> = emptyList(),
    /** Optional certification-only dips (e.g. Lv.10 重甲骑士 for Def 12 floor without staying in it). */
    val extraCertDips: List<ThreeHousesCertDipSpec> = emptyList(),
    val includeTwoStatReroll: Boolean? = null,
    val includeDlcClasses: Boolean = true,
)

data class ThreeHousesCertEvent(
    val atLevel: Int,
    val className: String,
    val tier: String,
    val isCertDipOnly: Boolean,
    val floorGains: List<Double>,
    val requirement: String,
    val masterySkill: String,
) {
    val totalFloorGain: Double get() = floorGains.sum()
}

data class ThreeHousesStageSummary(
    val className: String,
    val tier: String,
    val fromLevel: Int,
    val toLevel: Int,
    val effectiveRates: List<Int>,
    val requirement: String,
    val classSkill: String,
    val statBonuses: List<Int>,
)

data class ThreeHousesLevelRow(
    val levelFrom: Int,
    val levelTo: Int,
    val className: String,
    val switchedToClass: Boolean,
    val expectedGains: List<Double>,
    val floorGainsAtLevel: List<Double>,
    val displayedStatsAfter: List<Double>,
)

data class ThreeHousesPlanReport(
    val characterName: String,
    val affiliation: String,
    val gender: ThreeHousesGender,
    val fromLevel: Int,
    val targetLevel: Int,
    val usedTwoStatReroll: Boolean,
    val weights: List<Int>,
    val startDisplayedStats: List<Double>,
    val finalPersonalStats: List<Double>,
    val finalClassBonuses: List<Int>,
    val finalDisplayedStats: List<Double>,
    val totalExpectedGains: List<Double>,
    val linearComparisonStats: List<Double>,
    val stages: List<ThreeHousesStageSummary>,
    val certEvents: List<ThreeHousesCertEvent>,
    val levelRows: List<ThreeHousesLevelRow>,
    val alternativeFinalClasses: List<Pair<String, Double>>,
    val warnings: List<String>,
)

object ThreeHousesRules {
    private val FEMALE_KEYWORDS = listOf(
        "艾黛尔贾特", "贝尔娜提塔", "多洛缇雅", "佩托拉",
        "梅尔赛德司", "雅妮特", "英谷莉特",
        "莉丝缇亚", "玛莉安奴", "希尔妲", "蕾欧妮",
        "芙莲", "玛努艾拉", "卡多莉奴", "萨米亚",
        "康丝坦洁", "哈琵", "安娜",
    )

    private val MALE_KEYWORDS = listOf(
        "修伯特", "菲尔迪南特", "林哈尔特", "卡斯帕尔",
        "帝弥托利", "杜笃", "菲力克斯", "亚修", "希尔凡",
        "库罗德", "洛廉兹", "拉斐尔", "伊古纳兹",
        "锥里尔", "西提司", "汉尼曼", "阿罗伊斯", "吉尔伯特",
        "尤里斯", "巴尔塔札尔", "伊艾里扎",
    )

    private val FEMALE_ONLY_CLASSES = setOf("天马骑士", "圣天马骑士", "格莫瑞", "女武神", "暗天马")
    private val MALE_ONLY_CLASSES = setOf("拳斗士", "暗魔道士", "暗魔法师", "暗主教", "勇者", "格斗家", "战争将领")
    private val DLC_CLASSES = setOf("女武神", "暗天马", "武僧 / 战修女", "骗术师")
    private val UNPLAYABLE_CLASSES = setOf(
        "炎帝", "炎帝（复用版）", "解放之王", "大司教", "圣者", "阿加斯提亚",
        "暴徒", "叛逆者", "领主（茱迪特）", "盗贼（序章）", "圣骑士（序章）", "堡垒骑士（第5章）",
    )

    private val EXCLUSIVE_CLASS_OWNERS: Map<String, List<String>> = mapOf(
        "领主" to listOf("艾黛尔贾特", "帝弥托利", "库罗德"),
        "重装领主" to listOf("艾黛尔贾特"),
        "皇帝" to listOf("艾黛尔贾特"),
        "上级领主" to listOf("帝弥托利"),
        "大领主" to listOf("帝弥托利"),
        "飞龙统领" to listOf("库罗德"),
        "巴巴罗萨" to listOf("库罗德"),
        "涅槃那" to listOf("贝雷特", "贝雷丝"),
        "死神骑士（我方）" to listOf("伊艾里扎"),
        "死神骑士" to listOf("伊艾里扎"),
    )

    fun inferGender(name: String, englishName: String = ""): ThreeHousesGender {
        if (name.contains("贝雷特") && name.contains("贝雷丝")) return ThreeHousesGender.ANY
        if (name == "贝雷丝" || englishName.equals("Byleth (F)", ignoreCase = true)) return ThreeHousesGender.FEMALE
        if (name == "贝雷特" || englishName.equals("Byleth (M)", ignoreCase = true)) return ThreeHousesGender.MALE
        if (FEMALE_KEYWORDS.any { name.contains(it) }) return ThreeHousesGender.FEMALE
        if (MALE_KEYWORDS.any { name.contains(it) }) return ThreeHousesGender.MALE
        return ThreeHousesGender.ANY
    }

    fun hasTwoStatReroll(name: String, affiliation: String): Boolean {
        if (name.contains("贝雷")) return true
        return affiliation.contains("学级")
    }

    fun isPlayableClass(cls: ThreeHousesClassData): Boolean {
        if (cls.tier == "特殊 / 魔物" || cls.tier == "--") return false
        if (cls.name in UNPLAYABLE_CLASSES) return false
        if (cls.name.contains("敌方") || cls.name.contains("复用版") || cls.name.contains("序章") || cls.name.contains("第5章") || cls.name.contains("茱迪特")) return false
        return true
    }

    fun minLevelForClass(className: String, tier: String): Int = when {
        className == "平民" || className == "贵族" -> 1
        className in setOf("重装领主", "上级领主", "飞龙统领", "涅槃那", "死神骑士（我方）", "死神骑士") -> 20
        className in setOf("皇帝", "大领主", "巴巴罗萨") -> 30
        className == "舞者" -> 10
        tier == "初级" -> 5
        tier == "中级" -> 10
        tier == "高级" || tier == "特殊" -> 20
        tier == "最高级" -> 30
        else -> 1
    }

    fun isClassAllowed(
        character: ThreeHousesCharacterData,
        cls: ThreeHousesClassData,
        effectiveGender: ThreeHousesGender = character.gender,
        includeDlc: Boolean = true,
    ): Boolean {
        if (!isPlayableClass(cls)) return false
        if (!includeDlc && cls.name in DLC_CLASSES) return false
        if (cls.name in FEMALE_ONLY_CLASSES && effectiveGender == ThreeHousesGender.MALE) return false
        if (cls.name in MALE_ONLY_CLASSES && effectiveGender == ThreeHousesGender.FEMALE) return false
        val owners = EXCLUSIVE_CLASS_OWNERS[cls.name]
        if (owners != null && owners.none { character.name.contains(it) }) return false
        return true
    }
}

object ThreeHousesGrowthPlanner {
    private const val STAT_COUNT = 9

    /**
     * Exact closed-form expected stat gains per level-up under Three Houses' >=2 stat reroll rule
     * (computed via exact 9-variable Poisson-Binomial distribution in O(9), zero Monte Carlo variance).
     */
    fun expectedStepGains(effectiveRates: List<Int>, guaranteeTwoStats: Boolean): List<Double> {
        require(effectiveRates.size == STAT_COUNT)
        val clamped = effectiveRates.map { it.coerceAtLeast(0) }
        if (!guaranteeTwoStats) return clamped.map { it / 100.0 }

        val guaranteedBase = clamped.map { it / 100 }
        val guaranteedSum = guaranteedBase.sum()
        if (guaranteedSum >= 2) return clamped.map { it / 100.0 }

        val p = DoubleArray(STAT_COUNT) { (clamped[it] % 100) / 100.0 }
        val nonZeroCount = p.count { it > 0.0 }
        if (guaranteedSum + nonZeroCount < 2) return clamped.map { it / 100.0 }

        // Compute product of (1 - p_j) for all j, and without each index i
        val prodExcept = DoubleArray(STAT_COUNT) { i ->
            var prod = 1.0
            for (j in 0 until STAT_COUNT) {
                if (j != i) prod *= (1.0 - p[j])
            }
            prod
        }
        var prob0 = 1.0
        for (j in 0 until STAT_COUNT) prob0 *= (1.0 - p[j])

        if (guaranteedSum == 1) {
            // Reroll only when fractional gains == 0
            val denom = 1.0 - prob0
            if (denom <= 1e-9) return clamped.map { it / 100.0 }
            return List(STAT_COUNT) { i -> guaranteedBase[i] + p[i] / denom }
        }

        // guaranteedSum == 0: reroll whenever fractional sum is 0 or 1
        var prob1 = 0.0
        for (k in 0 until STAT_COUNT) prob1 += p[k] * prodExcept[k]
        val denom = 1.0 - prob0 - prob1
        if (denom <= 1e-9) return clamped.map { it / 100.0 }
        return List(STAT_COUNT) { i ->
            val pOnlyI = p[i] * prodExcept[i]
            ((p[i] - pOnlyI) / denom).coerceIn(0.0, 1.0)
        }
    }

    fun plan(
        request: ThreeHousesPlanRequest,
        characters: List<ThreeHousesCharacterData>,
        classes: List<ThreeHousesClassData>,
    ): ThreeHousesPlanReport {
        val character = resolveCharacter(request.characterName, characters)
            ?: throw IllegalArgumentException("在《风花雪月》资料库中未找到角色「${request.characterName}」")
        val effectiveGender = request.genderOverride ?: character.gender
        val fromLevel = (request.startLevel ?: character.startLevel).coerceIn(1, 98)
        val targetLevel = request.targetLevel.coerceIn(fromLevel + 1, 99)
        val weights = normalizeWeights(request.weights)
        val useReroll = request.includeTwoStatReroll ?: character.hasTwoStatReroll

        val playableClasses = classes.filter {
            ThreeHousesRules.isClassAllowed(character, it, effectiveGender, request.includeDlcClasses)
        }
        require(playableClasses.isNotEmpty()) { "未找到角色「${character.name}」可用的《风花雪月》职业列表" }

        val startClass = resolveClass(character.startClassName, classes)
            ?: playableClasses.firstOrNull { it.name == "平民" || it.name == "贵族" }
            ?: playableClasses.first()

        // Base personal stats (subtracting initial class bonus if displayed stats include them)
        val rawStartDisplayed = request.currentStats?.takeIf { it.size == STAT_COUNT } ?: character.baseStats
        val startPersonal = DoubleArray(STAT_COUNT) { i ->
            (rawStartDisplayed[i] - startClass.statBonuses[i]).coerceAtLeast(1).toDouble()
        }

        val warnings = mutableListOf<String>()
        if (character.gender == ThreeHousesGender.ANY && request.genderOverride == null) {
            warnings += "贝雷特/贝雷丝未指定性别，已开放全部通用性别职业供规划；如需限定男/女路线请指定性别。"
        }

        // Optional extra certification dips (resolved before auto-path recommendation so the recommender
        // knows which stat floors are already covered by cert dips and doesn't stay in Armored Knight!)
        val certDipsByLevel = mutableMapOf<Int, MutableList<ThreeHousesClassData>>()
        request.extraCertDips.forEach { dip ->
            val cls = resolveClass(dip.className, playableClasses)
            if (cls == null) {
                warnings += "忽略无法转职的保底考试「${dip.className}」。"
            } else if (dip.atLevel < cls.minLevel) {
                warnings += "「${cls.name}」需要 Lv.${cls.minLevel}+ 才能报考，已自动调整至 Lv.${cls.minLevel}。"
                if (cls.minLevel in fromLevel..targetLevel) {
                    certDipsByLevel.getOrPut(cls.minLevel) { mutableListOf() }.add(cls)
                }
            } else if (dip.atLevel in fromLevel..targetLevel) {
                certDipsByLevel.getOrPut(dip.atLevel) { mutableListOf() }.add(cls)
            }
        }

        // Build ordered class path (either custom user stages or auto-recommended optimal path)
        val resolvedStages: List<Pair<ThreeHousesClassData, IntRange>> = if (request.customStages.isNotEmpty()) {
            buildCustomStages(request.customStages, fromLevel, targetLevel, startClass, playableClasses, classes, warnings)
        } else {
            recommendOptimalStages(
                character = character,
                fromLevel = fromLevel,
                targetLevel = targetLevel,
                startClass = startClass,
                startPersonal = startPersonal,
                playableClasses = playableClasses,
                certDipsByLevel = certDipsByLevel,
                weights = weights,
                useReroll = useReroll,
            )
        }

        // Simulate level-by-level with exact reroll expectation + certification stat flooring
        val personal = startPersonal.copyOf()
        val linearPersonal = startPersonal.copyOf()
        val stageSummaries = mutableListOf<ThreeHousesStageSummary>()
        val certEvents = mutableListOf<ThreeHousesCertEvent>()
        val levelRows = mutableListOf<ThreeHousesLevelRow>()

        fun applyCertFloor(targetPersonal: DoubleArray, cls: ThreeHousesClassData): List<Double> {
            val bumps = MutableList(STAT_COUNT) { 0.0 }
            for (i in 0 until STAT_COUNT) {
                val floor = cls.baseFloors[i].toDouble()
                if (floor > targetPersonal[i]) {
                    bumps[i] = floor - targetPersonal[i]
                    targetPersonal[i] = floor
                }
            }
            return bumps
        }

        var prevClassName: String? = null
        resolvedStages.forEach { (cls, levelRange) ->
            val effRates = List(STAT_COUNT) { i -> (character.personalGrowth[i] + cls.growthBonus[i]).coerceAtLeast(0) }
            stageSummaries += ThreeHousesStageSummary(
                className = cls.name,
                tier = cls.tier,
                fromLevel = levelRange.first,
                toLevel = levelRange.last + 1,
                effectiveRates = effRates,
                requirement = cls.promotionRequirement.ifBlank { "剧情/默认解锁" },
                classSkill = cls.classSkill,
                statBonuses = cls.statBonuses,
            )

            val stepExpected = expectedStepGains(effRates, useReroll)
            val stepLinear = expectedStepGains(effRates, guaranteeTwoStats = false)

            for (lv in levelRange) {
                val switchedNow = prevClassName != null && prevClassName != cls.name
                val floorTotalThisLv = DoubleArray(STAT_COUNT)

                if (switchedNow) {
                    val bumps = applyCertFloor(personal, cls)
                    applyCertFloor(linearPersonal, cls)
                    for (i in 0 until STAT_COUNT) floorTotalThisLv[i] += bumps[i]
                    certEvents += ThreeHousesCertEvent(
                        atLevel = lv,
                        className = cls.name,
                        tier = cls.tier,
                        isCertDipOnly = false,
                        floorGains = bumps,
                        requirement = cls.promotionRequirement.ifBlank { "剧情/默认解锁" },
                        masterySkill = extractMasterySkill(cls.classSkill),
                    )
                }
                prevClassName = cls.name

                // Apply any certification-only dips scheduled at this level
                certDipsByLevel[lv].orEmpty().forEach { dipCls ->
                    val bumps = applyCertFloor(personal, dipCls)
                    applyCertFloor(linearPersonal, dipCls)
                    for (i in 0 until STAT_COUNT) floorTotalThisLv[i] += bumps[i]
                    certEvents += ThreeHousesCertEvent(
                        atLevel = lv,
                        className = dipCls.name,
                        tier = dipCls.tier,
                        isCertDipOnly = true,
                        floorGains = bumps,
                        requirement = dipCls.promotionRequirement.ifBlank { "Lv.${dipCls.minLevel}+ 资格考试" },
                        masterySkill = extractMasterySkill(dipCls.classSkill),
                    )
                }

                // Level-up expected gains
                for (i in 0 until STAT_COUNT) {
                    personal[i] += stepExpected[i]
                    linearPersonal[i] += stepLinear[i]
                }
                val displayedAfter = List(STAT_COUNT) { i -> personal[i] + cls.statBonuses[i] }
                levelRows += ThreeHousesLevelRow(
                    levelFrom = lv,
                    levelTo = lv + 1,
                    className = cls.name,
                    switchedToClass = switchedNow,
                    expectedGains = stepExpected,
                    floorGainsAtLevel = floorTotalThisLv.toList(),
                    displayedStatsAfter = displayedAfter,
                )
            }
        }

        val finalClass = resolvedStages.last().first
        val startDisplayed = rawStartDisplayed.map { it.toDouble() }
        val finalDisplayed = List(STAT_COUNT) { i -> personal[i] + finalClass.statBonuses[i] }
        val linearDisplayed = List(STAT_COUNT) { i -> linearPersonal[i] + finalClass.statBonuses[i] }
        val totalGains = List(STAT_COUNT) { i -> finalDisplayed[i] - startDisplayed[i] }

        // Rank top alternative final classes reachable by targetLevel
        val altFinals = playableClasses
            .filter { it.minLevel <= targetLevel && it.name != finalClass.name && it.tier in setOf("最高级", "高级", "特殊", "专属") }
            .map { alt ->
                val score = scoreClassValue(character, alt, weights, remainingLevels = 10, useReroll = useReroll)
                alt.name to score
            }
            .sortedByDescending { it.second }
            .take(4)

        return ThreeHousesPlanReport(
            characterName = character.name,
            affiliation = character.affiliation,
            gender = effectiveGender,
            fromLevel = fromLevel,
            targetLevel = targetLevel,
            usedTwoStatReroll = useReroll,
            weights = weights,
            startDisplayedStats = startDisplayed,
            finalPersonalStats = personal.toList(),
            finalClassBonuses = finalClass.statBonuses,
            finalDisplayedStats = finalDisplayed,
            totalExpectedGains = totalGains,
            linearComparisonStats = linearDisplayed,
            stages = stageSummaries,
            certEvents = certEvents,
            levelRows = levelRows,
            alternativeFinalClasses = altFinals,
            warnings = warnings,
        )
    }

    private fun buildCustomStages(
        specs: List<ThreeHousesStageSpec>,
        fromLevel: Int,
        targetLevel: Int,
        startClass: ThreeHousesClassData,
        playableClasses: List<ThreeHousesClassData>,
        allClasses: List<ThreeHousesClassData>,
        warnings: MutableList<String>,
    ): List<Pair<ThreeHousesClassData, IntRange>> {
        val sorted = specs.sortedBy { it.fromLevel }
        val result = mutableListOf<Pair<ThreeHousesClassData, IntRange>>()
        var cursor = fromLevel
        for (spec in sorted) {
            if (cursor >= targetLevel) break
            val cls = resolveClass(spec.className, playableClasses)
                ?: resolveClass(spec.className, allClasses)?.also {
                    warnings += "职业「${it.name}」存在性别/专属限制，已按用户指定强制试算。"
                }
                ?: throw IllegalArgumentException("在《风花雪月》职业库中未找到「${spec.className}」")
            val effectiveStart = maxOf(cursor, spec.fromLevel)
            if (effectiveStart > cursor) {
                result += (result.lastOrNull()?.first ?: startClass) to (cursor until effectiveStart)
                cursor = effectiveStart
            }
            if (cursor < cls.minLevel && cls.name != startClass.name) {
                warnings += "「${cls.name}」最低考试等级为 Lv.${cls.minLevel}，已顺延至 Lv.${cls.minLevel} 起转职。"
                val fillEnd = minOf(cls.minLevel, targetLevel)
                if (fillEnd > cursor) {
                    result += (result.lastOrNull()?.first ?: startClass) to (cursor until fillEnd)
                    cursor = fillEnd
                }
            }
            val endLv = spec.toLevel.coerceIn(cursor, targetLevel)
            if (endLv > cursor) {
                result += cls to (cursor until endLv)
                cursor = endLv
            }
        }
        if (cursor < targetLevel) {
            val tailCls = result.lastOrNull()?.first ?: startClass
            result += tailCls to (cursor until targetLevel)
        }
        return mergeAdjacentStages(result)
    }

    private fun recommendOptimalStages(
        character: ThreeHousesCharacterData,
        fromLevel: Int,
        targetLevel: Int,
        startClass: ThreeHousesClassData,
        startPersonal: DoubleArray,
        playableClasses: List<ThreeHousesClassData>,
        certDipsByLevel: Map<Int, List<ThreeHousesClassData>>,
        weights: List<Int>,
        useReroll: Boolean,
    ): List<Pair<ThreeHousesClassData, IntRange>> {
        // Canonical Three Houses certification breakpoints: 5 (初级), 10 (中级), 20 (高级/特殊), 30 (最高级/专属)
        val breakpoints = listOf(fromLevel, 5, 10, 20, 30, targetLevel)
            .filter { it in fromLevel..targetLevel }
            .distinct()
            .sorted()

        val stages = mutableListOf<Pair<ThreeHousesClassData, IntRange>>()
        val simPersonal = startPersonal.copyOf()
        var currentClass = startClass

        for (segIdx in 0 until breakpoints.size - 1) {
            val segStart = breakpoints[segIdx]
            val segEnd = breakpoints[segIdx + 1]
            val segLevels = segEnd - segStart
            if (segLevels <= 0) continue

            // Apply scheduled cert-dip floors at segStart before scoring leveling classes!
            certDipsByLevel[segStart].orEmpty().forEach { dipCls ->
                for (i in 0 until STAT_COUNT) {
                    if (dipCls.baseFloors[i] > simPersonal[i]) simPersonal[i] = dipCls.baseFloors[i].toDouble()
                }
            }

            val isFinalSegment = segIdx == breakpoints.size - 2
            val candidates = playableClasses.filter { cls ->
                cls.minLevel <= segStart && when {
                    segStart < 5 -> cls.name == startClass.name
                    segStart < 10 -> cls.tier == "初级" || cls.name == currentClass.name
                    segStart < 20 -> cls.tier == "中级" || cls.name == currentClass.name
                    segStart < 30 -> cls.tier in setOf("高级", "特殊", "专属") && cls.minLevel <= 20 || cls.name == currentClass.name
                    else -> cls.tier in setOf("最高级", "高级", "特殊", "专属")
                }
            }.ifEmpty { listOf(currentClass) }

            val picked = candidates.maxByOrNull { cand ->
                val effRates = List(STAT_COUNT) { i -> (character.personalGrowth[i] + cand.growthBonus[i]).coerceAtLeast(0) }
                val perLv = expectedStepGains(effRates, useReroll)
                var score = 0.0
                for (i in 0 until STAT_COUNT) {
                    val floorBump = (cand.baseFloors[i].toDouble() - simPersonal[i]).coerceAtLeast(0.0)
                    val growthSum = perLv[i] * segLevels
                    val activeBonus = if (isFinalSegment) cand.statBonuses[i].toDouble() else 0.0
                    score += weights[i] * (floorBump + growthSum + activeBonus)
                }
                // Small preference for iconic mastery/high mobility on tie
                if (cand.name == "土匪" && segStart == 10 && weights[ThreeHousesStat.STRENGTH.ordinal] >= 3) score += 1.5
                if (cand.name == "魔道士" && segStart == 10 && weights[ThreeHousesStat.MAGIC.ordinal] >= 3) score += 1.5
                if (cand.name == "飞马骑士" && segStart == 10 && weights[ThreeHousesStat.SPEED.ordinal] >= 3) score += 1.5
                if (cand.movement >= 6 && isFinalSegment) score += 0.6
                if (cand.name == currentClass.name) score += 0.05 // avoid pointless zero-gain switches
                score
            } ?: currentClass

            // Advance running personal stats for next segment's floor evaluation
            for (i in 0 until STAT_COUNT) {
                if (picked.baseFloors[i] > simPersonal[i]) simPersonal[i] = picked.baseFloors[i].toDouble()
            }
            val effRates = List(STAT_COUNT) { i -> (character.personalGrowth[i] + picked.growthBonus[i]).coerceAtLeast(0) }
            val perLv = expectedStepGains(effRates, useReroll)
            for (i in 0 until STAT_COUNT) simPersonal[i] += perLv[i] * segLevels

            stages += picked to (segStart until segEnd)
            currentClass = picked
        }
        return mergeAdjacentStages(stages)
    }

    private fun mergeAdjacentStages(raw: List<Pair<ThreeHousesClassData, IntRange>>): List<Pair<ThreeHousesClassData, IntRange>> {
        val merged = mutableListOf<Pair<ThreeHousesClassData, IntRange>>()
        for ((cls, range) in raw) {
            val last = merged.lastOrNull()
            if (last != null && last.first.name == cls.name && last.second.last + 1 == range.first) {
                merged[merged.lastIndex] = cls to (last.second.first..range.last)
            } else {
                merged += cls to range
            }
        }
        return merged
    }

    private fun scoreClassValue(
        character: ThreeHousesCharacterData,
        cls: ThreeHousesClassData,
        weights: List<Int>,
        remainingLevels: Int,
        useReroll: Boolean,
    ): Double {
        val effRates = List(STAT_COUNT) { i -> (character.personalGrowth[i] + cls.growthBonus[i]).coerceAtLeast(0) }
        val perLv = expectedStepGains(effRates, useReroll)
        var s = 0.0
        for (i in 0 until STAT_COUNT) {
            s += weights[i] * (perLv[i] * remainingLevels + cls.statBonuses[i])
        }
        return s
    }

    fun resolveCharacter(query: String, characters: List<ThreeHousesCharacterData>): ThreeHousesCharacterData? {
        val q = query.trim()
        if (q.isEmpty()) return null
        return characters.firstOrNull { it.name == q || it.englishName.equals(q, ignoreCase = true) }
            ?: characters.firstOrNull { it.name.substringBefore('·') == q }
            ?: characters.firstOrNull { it.name.contains(q, ignoreCase = true) || q.contains(it.name.substringBefore('·'), ignoreCase = true) }
    }

    fun resolveClass(query: String, classes: List<ThreeHousesClassData>): ThreeHousesClassData? {
        val raw = query.trim()
        if (raw.isEmpty()) return null
        val q = when (raw) {
            "重甲骑士" -> "重骑士"
            "要塞" -> "堡垒骑士"
            "暗魔法师" -> "暗魔道士"
            "死神骑士" -> "死神骑士（我方）"
            else -> raw
        }
        return classes.firstOrNull { it.name == q || it.englishName.equals(q, ignoreCase = true) }
            ?: classes.firstOrNull { it.name == raw || it.englishName.equals(raw, ignoreCase = true) }
            ?: classes.firstOrNull { it.name.contains(q, ignoreCase = true) || it.englishName.contains(q, ignoreCase = true) }
    }

    private fun normalizeWeights(weights: List<Int>): List<Int> {
        val out = List(STAT_COUNT) { idx -> weights.getOrElse(idx) { 1 }.coerceIn(0, 100) }
        return if (out.all { it == 0 }) List(STAT_COUNT) { 1 } else out
    }

    private fun extractMasterySkill(classSkill: String): String {
        val idx = classSkill.indexOf("兵种精通特技：")
        if (idx < 0) return ""
        return classSkill.substring(idx + "兵种精通特技：".length).substringBefore("（").trim()
    }
}
