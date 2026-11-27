package com.fireemblem.companion.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class BuddySeedHistoryEndState(
    val level: Int,
    val profession: String,
    val part3: Boolean,
    val line: BuddyCampaignLine,
    val ride: BuddyRideSelection? = null,
    val unlockedProfessions: Set<String> = setOf(profession),
)

data class BuddySeedHistory(
    val characterId: String,
    val events: List<ObservedSeedEvent>,
    val observedLevels: Int,
    val skippedAttempts: Int,
    val warnings: List<String>,
    val errors: List<String>,
    val endState: BuddySeedHistoryEndState? = null,
) {
    val ready: Boolean
        get() = errors.isEmpty() && observedLevels >= MIN_OBSERVED_LEVELS

    companion object {
        const val MIN_OBSERVED_LEVELS = 4

        fun fromBackup(canonicalJson: String, characterId: String, rules: BuddyRules): BuddySeedHistory {
            val events = mutableListOf<ObservedSeedEvent>()
            val warnings = linkedSetOf<String>()
            val errors = linkedSetOf<String>()
            var observedLevels = 0
            var skippedAttempts = 0
            val seenProfessions = linkedSetOf<String>()
            var lastRowPart3: Boolean? = null
            fun result(endState: BuddySeedHistoryEndState? = null) = BuddySeedHistory(
                characterId, events.toList(), observedLevels, skippedAttempts,
                warnings.toList(), errors.toList(), endState,
            )

            val root = try { Json.parseToJsonElement(canonicalJson).jsonObject }
            catch (_: Exception) { return BuddySeedHistory(characterId, emptyList(), 0, 0, emptyList(), listOf("存档内容无法读取。")) }
            val roster = root["roster"] as? JsonArray
                ?: return BuddySeedHistory(characterId, emptyList(), 0, 0, emptyList(), listOf("存档缺少角色记录。"))
            val character = rules.characters.firstOrNull { it.id == characterId }
                ?: return BuddySeedHistory(characterId, emptyList(), 0, 0, emptyList(), listOf("该角色没有已验证的成长规则。"))
            val item = roster.mapNotNull { runCatching { it.jsonObject }.getOrNull() }
                .firstOrNull { it["id"]?.jsonPrimitive?.content == characterId }
                ?: return BuddySeedHistory(characterId, emptyList(), 0, 0, emptyList(), listOf("所选存档中没有该角色。"))
            if (item["deleted"]?.jsonPrimitive?.booleanOrNull == true) {
                return BuddySeedHistory(characterId, emptyList(), 0, 0, emptyList(), listOf("该角色在存档中已删除。"))
            }
            val initialLevel = item["initialLevel"]?.jsonPrimitive?.intOrNull
            if (initialLevel == null || initialLevel !in 1..98) {
                errors += "缺少有效的起始等级，无法重建每次升级的成长参数。"
            }
            val line = if (root["route"]?.jsonPrimitive?.content == "kai") BuddyCampaignLine.KAI else BuddyCampaignLine.DIT
            if (line != BuddyCampaignLine.KAI) warnings += "此备份标记为非凯伊路线；获准移植的规则对迪托利希、赛奥朵拉和蕾达路线使用相同成长算法。"
            val part3Default = root["part3"]?.jsonPrimitive?.booleanOrNull
            val liveRecords = (item["records"] as? JsonArray).orEmpty().mapNotNull { runCatching { it.jsonObject }.getOrNull() }
                .filter { it["deleted"]?.jsonPrimitive?.booleanOrNull != true }
                .sortedBy { it["seq"]?.jsonPrimitive?.intOrNull ?: Int.MAX_VALUE }
            val initialProfession = item["initialProfession"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
            var profession = item["pendingProfession"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
                ?: liveRecords.asReversed().firstNotNullOfOrNull { row ->
                    row["profession"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
                }
                ?: initialProfession
            var storyProfession = initialProfession?.takeUnless { name ->
                rules.characters.firstOrNull { it.id == characterId }?.part3Only == true && name !in setOf("平民", "贵族")
            }
            var lockedToPart3 = character.part3Only
            var currentLevel = initialLevel ?: 1
            var previousRide: BuddyRideSelection? = null
            val rideMemory = (root["rideMemory"] as? JsonObject)?.get(characterId) as? JsonObject
            val rememberedRides = rideMemory?.values.orEmpty().mapNotNull { value ->
                val ride = value as? JsonObject ?: return@mapNotNull null
                val id = ride["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val level = ride["level"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                runCatching { BuddyRideSelection(id, level) }.getOrNull()
            }

            for ((index, row) in liveRecords.withIndex()) {
                val status = row["status"]?.jsonPrimitive?.content ?: "normal"
                if (status !in setOf("normal", "skipped", "upgraded")) {
                    errors += "第 ${index + 1} 条成长记录状态无法识别。"
                    continue
                }
                if (status == "skipped") {
                    events += ObservedSeedEvent.Advance()
                    skippedAttempts++
                    continue
                }
                val recordedPart3 = row["part3"]?.jsonPrimitive?.booleanOrNull
                if (!character.part3Only && !lockedToPart3 && recordedPart3 == null && part3Default == null) {
                    errors += "第 ${index + 1} 条记录缺少篇章标记；为避免猜错成长率，未纳入反推。"
                    continue
                }
                val rowPart3 = character.part3Only || lockedToPart3 || (recordedPart3 ?: part3Default ?: false)
                val recordedProfession = row["profession"]
                if (recordedProfession != null) {
                    val value = recordedProfession.takeUnless { it is JsonNull }?.jsonPrimitive?.content
                    if (value != null && (rowPart3 || !rules.characters.first { it.id == characterId }.part3Only)) profession = value
                    else if (value == null) profession = null
                }
                if (profession == null) {
                    errors += "第 ${index + 1} 条记录没有职业信息。"
                    continue
                }
                var rowProfession = profession
                var rowRide = readRide(row["ride"], errors, index)
                if (!rowPart3 && rules.professionIsPart3Only(rowProfession)) {
                    rowProfession = storyProfession
                    rowRide = null
                } else if (!rules.professionIsPart3Only(rowProfession)) {
                    storyProfession = rowProfession
                }
                if (rowProfession == null) {
                    errors += "第 ${index + 1} 条记录的篇章专属职业缺少可核实的前篇职业。"
                    continue
                }
                if (row["ride"] == null) {
                    val input = BuddyGrowthInput(characterId, rowProfession, currentLevel, rowPart3, line.rulesRoute)
                    val availableRides = runCatching { rules.availableRides(input).map(BuddyRide::id).toSet() }.getOrDefault(emptySet())
                    rowRide = previousRide?.takeIf { it.id in availableRides }
                        ?: rememberedRides.firstOrNull { it.id in availableRides }
                    if (rowRide == null && availableRides.isNotEmpty()) {
                        errors += "第 ${index + 1} 条记录缺少坐骑信息；可选坐骑存在，无法判断当时是否骑乘。"
                        continue
                    }
                    if (rowRide != null) warnings += "部分行未单独保存坐骑，使用了最近可用的存档坐骑设置。"
                }
                val seedInput = (row["seedInput"] as? JsonObject).takeIf { row["corrected"]?.jsonPrimitive?.booleanOrNull == true }
                val effectiveProfession = seedInput?.get("profession")?.takeUnless { it is JsonNull }?.jsonPrimitive?.content ?: rowProfession
                val effectiveRide = if (seedInput != null && seedInput.containsKey("ride")) readRide(seedInput["ride"], errors, index) else rowRide
                val statsSource = seedInput?.get("stats") as? JsonArray ?: row["stats"] as? JsonArray
                if (statsSource == null) {
                    errors += "第 ${index + 1} 条记录缺少实际提升结果。"
                    continue
                }
                val plus2 = (seedInput?.get("plus2") as? JsonArray ?: row["plus2"] as? JsonArray).orEmpty()
                    .mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }.toSet()
                val stats = statsSource.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }.toSet()
                val gains = GrowthStat.entries.map { stat -> when {
                    stat.label in plus2 -> 2
                    stat.label in stats -> 1
                    else -> 0
                } }
                val input = BuddyGrowthInput(characterId, effectiveProfession, currentLevel, rowPart3, line.rulesRoute, effectiveRide)
                val growth = runCatching { rules.growth(input) }.getOrElse {
                    errors += "第 ${index + 1} 条记录无法按已验证规则重建：${it.message ?: "输入不兼容"}。"
                    continue
                }
                if (!growth.classAllowed) {
                    errors += "第 ${index + 1} 条记录的职业与篇章组合不合法。"
                    continue
                }
                val parameters = growth.parameters.copy(room = List(GrowthStat.entries.size) { 32_767 })
                events += ObservedSeedEvent.Level(parameters, gains)
                observedLevels++
                seenProfessions += effectiveProfession
                lastRowPart3 = rowPart3
                previousRide = effectiveRide
                if (status == "upgraded" && rowPart3) lockedToPart3 = true
                if (initialLevel != null && status != "skipped") currentLevel++
            }
            if (observedLevels < MIN_OBSERVED_LEVELS) warnings += "至少需要 $MIN_OBSERVED_LEVELS 条可核实的非跳过升级记录；当前可用 $observedLevels 条。"
            val finalProfession = profession ?: storyProfession ?: initialProfession
            val finalPart3 = character.part3Only || lockedToPart3 || (lastRowPart3 ?: part3Default ?: false)
            val endState = if (finalProfession != null && currentLevel in 1..99) {
                if (initialProfession != null) seenProfessions += initialProfession
                seenProfessions += finalProfession
                BuddySeedHistoryEndState(
                    level = currentLevel.coerceIn(1, 99),
                    profession = finalProfession,
                    part3 = finalPart3,
                    line = line,
                    ride = previousRide,
                    unlockedProfessions = seenProfessions.toSet(),
                )
            } else null
            return result(endState)
        }

        private fun readRide(value: kotlinx.serialization.json.JsonElement?, errors: MutableSet<String>, index: Int): BuddyRideSelection? {
            if (value == null || value is JsonNull) return null
            val ride = runCatching { value.jsonObject }.getOrNull()
            val id = ride?.get("id")?.jsonPrimitive?.content
            val level = ride?.get("level")?.jsonPrimitive?.intOrNull
            if (id == null || level == null || level !in 1..5) {
                errors += "第 ${index + 1} 条记录的坐骑数据无效。"
                return null
            }
            return BuddyRideSelection(id, level)
        }
    }
}
