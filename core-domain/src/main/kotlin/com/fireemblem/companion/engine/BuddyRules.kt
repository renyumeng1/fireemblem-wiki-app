package com.fireemblem.companion.engine

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class GrowthStat(val label: String) {
    HP("HP"), STRENGTH("力量"), MAGIC("魔力"), DEXTERITY("技巧"), SPEED("速度"),
    LUCK("幸运"), DEFENSE("防守"), RESISTANCE("魔防"), CHARM("魅力"),
}

enum class BuddyRoute { KAI, OTHER }

enum class BuddyCampaignLine(
    val id: String,
    val label: String,
    val rulesRoute: BuddyRoute,
) {
    KAI("kai", "凯伊篇", BuddyRoute.KAI),
    DIT("dit", "迪托利希篇", BuddyRoute.OTHER),
    SEO("seo", "赛奥朵拉篇", BuddyRoute.OTHER),
    LEDA("leda", "蕾达篇", BuddyRoute.OTHER),
}

data class BuddyRideSelection(val id: String, val level: Int) {
    init {
        require(level in 1..5) { "坐骑友好等级应为 1 至 5" }
    }
}

data class BuddyGrowthInput(
    val characterId: String,
    val profession: String,
    val level: Int,
    val part3: Boolean,
    val route: BuddyRoute,
    val ride: BuddyRideSelection? = null,
    val ruleRevision: String = BuddyRules.REVISION,
)

data class BuddyCharacter(val id: String, val name: String, val personId: String, val part3Only: Boolean)
data class BuddyProfession(
    val name: String,
    val tier: Int,
    val jobId: String,
    val part3Only: Boolean,
    val kind: String?,
)
data class BuddyRide(val id: String, val name: String)
data class BuddyGrowthResult(
    val parameters: LevelParameters,
    val effectiveRide: BuddyRideSelection?,
    val classAllowed: Boolean,
    val ruleRevision: String,
)

data class BuddyForecastRow(
    val index: Int,
    val fromLevel: Int,
    val seed: Long,
    val nextSeed: Long,
    val gains: Map<GrowthStat, Int>,
    val skipped: Boolean,
    val examMinimum: Int,
)

data class BuddyForecast(
    val input: BuddyGrowthInput,
    val originSeed: Long,
    val rows: List<BuddyForecastRow>,
    val ignoredRide: Boolean,
)

class BuddyRules private constructor(private val root: JsonObject) {
    private val people = root.getValue("people").jsonArray.map { it.jsonObject }
    private val characterRules = people.associateBy { it.text("id") }
    private val professionRules = root.getValue("professions").jsonArray.map { it.jsonObject }
        .associateBy { it.text("name") }
    private val rideRules = root.getValue("rides").jsonObject.mapValues { it.value.jsonObject }
    val characters: List<BuddyCharacter> = people.map {
        BuddyCharacter(it.text("id"), it.text("name"), it.text("personId"), it.flag("part3Only"))
    }
    val professions: List<BuddyProfession> = professionRules.values.map {
        BuddyProfession(it.text("name"), it.number("tier"), it.text("jobId"), it.flag("part3Only"), it.optionalText("kind"))
    }

    fun professionsForJobId(jobId: String): List<BuddyProfession> = professions.filter { it.jobId == jobId }

    fun professionForJobId(jobId: String, characterId: String, part3: Boolean): BuddyProfession? =
        professionsForJobId(jobId).singleOrNull { classAllowed(characterId, it.name, part3) }

    fun classAllowed(characterId: String, profession: String, part3: Boolean): Boolean {
        val character = characterRules[characterId] ?: throw IllegalArgumentException("该角色暂不支持成长计算")
        val job = professionRules[profession] ?: throw IllegalArgumentException("无法识别该职业")
        if (!part3 && job.flag("part3Only")) return false
        if (!part3 && profession == "舞者" && characterId != "c017") return false
        val rule = character.getValue("classRule").jsonObject
        rule.optionalText("fixed")?.let { return profession == it }
        if (job.flag("base")) return profession == rule.optionalText("base")
        return rule["excluded"]?.jsonArray?.none { it.jsonPrimitive.content == profession } ?: true
    }

    fun professionIsPart3Only(profession: String): Boolean = professionRules[profession]?.flag("part3Only") == true

    fun professionTier(profession: String): Int = professionRules[profession]?.number("tier") ?: 0

    /**
     * Checks whether [profession] can actually be obtained on campaign [line] in the given story part.
     * Part-1 quest/route-exclusive advanced classes are restricted to their unlocking routes until Part 3.
     */
    fun isClassObtainableInRoute(
        profession: String,
        line: BuddyCampaignLine,
        part3: Boolean,
    ): Boolean {
        if (professionIsPart3Only(profession) && !part3) return false
        if (part3) return true
        val allowedLines = PART1_ROUTE_LOCKED_CLASSES[profession] ?: return true
        return line in allowedLines
    }

    fun examRequirementSummary(profession: String): String {
        val tier = professionTier(profession)
        val baseReq = when (tier) {
            0 -> "初始基础职业"
            1 -> "初级考试 · 推荐 Lv.5+ · 名声 Lv.1 · 对应武器 D · 初级票证"
            2 -> "中级考试 · 推荐 Lv.20+ · 名声 Lv.4 · 对应武器 C · 中级票证"
            3 -> "上级考试 · 推荐 Lv.35+ · 名声 Lv.8 · 对应武器 B · 上级票证"
            4 -> "最上级考试 · 第3部 · 推荐 Lv.45+ · 对应武器 A · 最上级票证"
            5 -> "神将考试 · 第3部 · 推荐 Lv.45+ · 对应武器 S · 专属道具"
            else -> "需满足对应考试条件"
        }
        val routeNote = PART1_ROUTE_LOCKED_NOTES[profession]
        return if (routeNote != null) "$baseReq（$routeNote）" else baseReq
    }

    fun isKnownRide(rideId: String): Boolean = rideId in rideRules

    fun availableRides(input: BuddyGrowthInput): List<BuddyRide> {
        validateInput(input)
        return rideRules.filter { (id, rule) -> rideAllowed(input, id, rule) }
            .map { (id, rule) -> BuddyRide(id, rule.text("name")) }
    }

    fun personalGrowth(characterId: String): List<Int> {
        val character = characterRules[characterId] ?: throw IllegalArgumentException("该角色暂不支持成长计算")
        return root.getValue("persons").jsonObject.getValue(character.text("personId")).jsonObject.vector("growth")
    }

    fun characterGuarantee(characterId: String, part3: Boolean): Int {
        val character = characterRules[characterId] ?: throw IllegalArgumentException("该角色暂不支持成长计算")
        val person = root.getValue("persons").jsonObject.getValue(character.text("personId")).jsonObject
        return person.getValue("guarantee").jsonObject.number(if (part3) "part3" else "pre")
    }

    fun growth(input: BuddyGrowthInput): BuddyGrowthResult {
        validateInput(input)
        val character = characterRules.getValue(input.characterId)
        val person = root.getValue("persons").jsonObject.getValue(character.text("personId")).jsonObject
        val profession = professionRules.getValue(input.profession)
        val job = root.getValue("jobs").jsonObject.getValue(profession.text("jobId")).jsonObject
        val effectiveRide = input.ride?.takeIf { rideAllowed(input, it.id, rideRules.getValue(it.id)) }
        val defaultRide = job.optionalText("defaultRide")?.let(rideRules::getValue)
        val owned = effectiveRide?.let { rideRules.getValue(it.id) }
        val rideBonus = if (job.number("allocationType") == 2 && defaultRide != null && owned != null &&
            owned.number("type") == defaultRide.number("type")
        ) {
            owned.getValue("growth").jsonArray[effectiveRide.level - 1].jsonArray.ints()
                .map { it * job.number("rideMultiplier") }
        } else List(GrowthStat.entries.size) { 0 }
        val levelBonus = job.getValue("levelBonuses").jsonArray.map { it.jsonObject }
            .lastOrNull { input.level >= it.number("from") }?.vector("growth")
            ?: List(GrowthStat.entries.size) { 0 }
        val personal = person.vector("growth")
        val base = job.vector("baseGrowth")
        val bonus = job.vector("growthBonus")
        val growth = personal.indices.map { index ->
            maxOf(0, (personal[index].takeIf { it != 0 } ?: base[index]) +
                bonus[index] + rideBonus[index] + levelBonus[index])
        }
        val guarantee = person.getValue("guarantee").jsonObject.number(if (input.part3) "part3" else "pre")
        return BuddyGrowthResult(
            LevelParameters(growth, List(GrowthStat.entries.size) { 32767 }, guarantee),
            effectiveRide, classAllowed(input.characterId, input.profession, input.part3), REVISION,
        )
    }

    fun forecast(
        seed: Long,
        input: BuddyGrowthInput,
        count: Int = 10,
        skippedIndices: Set<Int> = emptySet(),
    ): BuddyForecast {
        require(seed in 0 until SeedEngine.UINT32_LIMIT) { "种子应为 0 至 4294967295 的整数" }
        require(count in 1..100) { "一次可预测 1 至 100 次" }
        require(skippedIndices.all { it in 0 until count }) { "跳过的位置超出预测范围" }
        val first = growth(input)
        require(first.classAllowed) { "该角色在当前篇章不能选择此职业" }
        var currentSeed = seed
        var currentLevel = input.level
        val rows = mutableListOf<BuddyForecastRow>()
        for (index in 0 until count) {
            if (currentLevel >= 99) break
            val parameters = growth(input.copy(level = currentLevel)).parameters
            val result = SeedEngine.simulateLevel(currentSeed, parameters)
            val skipped = index in skippedIndices
            rows += BuddyForecastRow(
                index, currentLevel, currentSeed, result.nextSeed,
                GrowthStat.entries.associateWith { result.gains[it.ordinal] }, skipped,
                SeedEngine.simulateExam(currentSeed, ExamParameters(100.0)).minimumProbability,
            )
            currentSeed = result.nextSeed
            if (!skipped) currentLevel++
        }
        return BuddyForecast(input, seed, rows, input.ride != null && first.effectiveRide == null)
    }

    private fun validateInput(input: BuddyGrowthInput) {
        require(input.ruleRevision == REVISION) { "此记录使用的规则版本尚未安装，未重新计算" }
        require(input.characterId in characterRules) { "该角色暂不支持成长计算" }
        require(input.profession in professionRules) { "无法识别该职业" }
        require(input.level in 1..99) { "当前等级应为 1 至 99" }
        require(input.ride == null || rideRules[input.ride.id]?.optionalText("kind") != null) { "无法识别该坐骑" }
    }

    private fun rideAllowed(input: BuddyGrowthInput, id: String, ride: JsonObject): Boolean {
        val kind = professionRules.getValue(input.profession).optionalText("kind") ?: return false
        if (ride.optionalText("kind") != kind) return false
        return input.part3 || (!ride.flag("part3Only") &&
            (input.route != BuddyRoute.OTHER || id == OWN_RIDES[input.characterId]))
    }

    companion object {
        const val REVISION = "016f048b5c636035ac0b56b0c4b9d89a742fba4a"
        const val RULES_SHA256 = "d44de84918c3953060b1702526fef34abfb39176147d5ae842c3d9a15ead30d1"
        private val OWN_RIDES = mapOf("c044" to "RID_ロシナン", "c054" to "RID_ブーケパラス")

        private val PART1_ROUTE_LOCKED_CLASSES: Map<String, Set<BuddyCampaignLine>> = mapOf(
            "战象兵" to emptySet(), // Part 3 only
            "舞者" to setOf(BuddyCampaignLine.LEDA),
            "锻造师" to setOf(BuddyCampaignLine.DIT),
            "铁甲骑兵" to setOf(BuddyCampaignLine.SEO),
            "卫士" to setOf(BuddyCampaignLine.DIT, BuddyCampaignLine.SEO),
            "游侠" to setOf(BuddyCampaignLine.DIT, BuddyCampaignLine.LEDA),
            "神鸵兵" to setOf(BuddyCampaignLine.KAI),
            "游唱诗人" to setOf(BuddyCampaignLine.KAI, BuddyCampaignLine.LEDA),
            "驭龙兵" to setOf(BuddyCampaignLine.KAI, BuddyCampaignLine.SEO),
        )

        private val PART1_ROUTE_LOCKED_NOTES: Map<String, String> = mapOf(
            "战象兵" to "第1部安娜外传后第3部解锁",
            "舞者" to "第1部蕾达篇第9章任务限定，第3部通用",
            "锻造师" to "第1部迪托利希篇解锁",
            "铁甲骑兵" to "第1部赛奥朵拉篇第8章解锁",
            "卫士" to "第1部迪托利希/赛奥朵拉篇解锁",
            "游侠" to "第1部迪托利希/蕾达篇解锁",
            "神鸵兵" to "第1部凯伊篇名声Lv.7指导解锁",
            "游唱诗人" to "第1部凯伊/蕾达篇解锁",
            "驭龙兵" to "第1部凯伊/赛奥朵拉篇解锁",
        )

        fun tierLabel(tier: Int): String = when (tier) {
            0 -> "基础"
            1 -> "下级"
            2 -> "中级"
            3 -> "上级"
            4 -> "最上级"
            5 -> "神将"
            else -> "其他"
        }

        /**
         * Official recommended exam unlock level per profession tier in Fortunes Weave.
         * Tier 0 (基础): Lv.1; Tier 1 (下级): Lv.5; Tier 2 (中级): Lv.20;
         * Tier 3 (上级): Lv.35; Tier 4 (最上级) & Tier 5 (神将): Lv.45 (Part 3).
         */
        fun defaultUnlockLevelForTier(tier: Int): Int = when (tier) {
            0 -> 1
            1 -> 5
            2 -> 20
            3 -> 35
            4 -> 45
            5 -> 45
            else -> 1
        }

        fun bundled(): BuddyRules {
            val bytes = requireNotNull(BuddyRules::class.java.getResourceAsStream("/buddy/rules-v1.json")) {
                "成长规则未安装"
            }.use { it.readBytes() }
            return load(bytes)
        }

        fun load(bytes: ByteArray): BuddyRules {
            require(bytes.size <= 1_048_576) { "成长规则文件过大" }
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            require(digest == RULES_SHA256) { "成长规则校验失败，未进行计算" }
            val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            require(root.number("schemaVersion") == 1 && root.text("revision") == REVISION) { "成长规则版本不兼容" }
            require(root.getValue("stats").jsonArray.map { it.jsonPrimitive.content } == GrowthStat.entries.map { it.label }) {
                "成长属性顺序不兼容"
            }
            return BuddyRules(root)
        }
    }
}

private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
private fun JsonObject.optionalText(key: String): String? = get(key)?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
private fun JsonObject.number(key: String): Int = getValue(key).jsonPrimitive.int
private fun JsonObject.flag(key: String): Boolean = getValue(key).jsonPrimitive.boolean
private fun JsonObject.vector(key: String): List<Int> = getValue(key).jsonArray.ints()
private fun JsonArray.ints(): List<Int> = map { it.jsonPrimitive.int }
