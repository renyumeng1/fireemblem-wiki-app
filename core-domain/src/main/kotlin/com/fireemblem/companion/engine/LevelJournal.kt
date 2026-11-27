package com.fireemblem.companion.engine

import java.util.UUID
import java.security.MessageDigest
import kotlinx.serialization.json.*

data class LevelObservation(
    val id: String = UUID.randomUUID().toString(),
    val level: Int,
    val profession: String,
    val part3: Boolean,
    val ride: BuddyRideSelection? = null,
    val gains: List<Int> = List(GrowthStat.entries.size) { 0 },
    val room: List<Int> = List(GrowthStat.entries.size) { 32767 },
    val skipped: Boolean = false,
    val exam: JournalExam? = null,
    val professionChangeFrom: String? = null,
    val growthLevel: Int? = null,
)

data class JournalExam(
    val targetProfession: String,
    val probability: Double?,
    val passed: Boolean,
    val resourceCost: Int? = 1,
)

val LevelObservation.levelsUp: Boolean
    get() = !skipped && exam == null && professionChangeFrom == null

enum class JournalSeedEvidence(val id: String) {
    FULL_SEARCH("full-search"),
    PARTIAL_SEARCH("partial-search"),
    IMPORTED_REPLAY("imported-replay"),
    USER_CONFIRMED_IMPORT("user-confirmed-import"),
    MANUAL("manual"),
    EVENT_CONFIRMED("event-confirmed"),
    PLAN_CONFIRMED("plan-confirmed"),
}

data class JournalSeedCheckpoint(
    val seed: Long,
    val eventIndex: Int,
    val level: Int,
    val profession: String,
    val part3: Boolean,
    val stats: List<Int>?,
    val caps: List<Int?>?,
    val evidence: JournalSeedEvidence,
    val historyDigest: String,
    val ruleRevision: String = BuddyRules.REVISION,
    val sourceSha256: String? = null,
    val originSeed: Long? = null,
    val matchCount: Long? = null,
    val unlockedProfessions: Set<String> = emptySet(),
) {
    fun validated(): JournalSeedCheckpoint {
        require(seed in 0 until SeedEngine.UINT32_LIMIT && (originSeed == null || originSeed in 0 until SeedEngine.UINT32_LIMIT)) {
            "种子应为 0 至 4294967295 的整数"
        }
        require(eventIndex >= 0 && level in 1..99 && profession.isNotBlank()) { "种子位置缺少等级或职业" }
        require(stats == null || stats.size == 9 && stats.all { it >= 0 }) { "当前属性数据无效" }
        require(caps == null || caps.size == 9 && caps.indices.all { caps[it] == null || stats == null || caps[it]!! >= stats[it] }) { "属性上限数据无效" }
        require(SHA256_PATTERN.matches(historyDigest) && (sourceSha256 == null || SHA256_PATTERN.matches(sourceSha256))) {
            "种子证据校验编号无效"
        }
        require(ruleRevision.isNotBlank() && (matchCount == null || matchCount >= 0)) { "种子证据版本或匹配数无效" }
        require(evidence != JournalSeedEvidence.FULL_SEARCH || matchCount == 1L) { "完整搜索证据必须只有一个匹配种子" }
        require(unlockedProfessions.all(String::isNotBlank)) { "种子证据职业列表无效" }
        return this
    }

    companion object {
        private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
    }
}

data class LevelJournal(
    val name: String,
    val profile: String,
    val line: BuddyCampaignLine,
    val characterId: String,
    val observations: List<LevelObservation> = emptyList(),
    val ruleRevision: String = BuddyRules.REVISION,
    val seedCheckpoint: JournalSeedCheckpoint? = null,
    val unlockedProfessions: Set<String> = emptySet(),
    val growthPlan: JournalGrowthPlan? = null,
    val importSourceSha256: String? = null,
    val importSourceRecordIndex: Int? = null,
    val importedGrowthPlan: JournalImportedGrowthPlan? = null,
) {
    fun validated(): LevelJournal {
        require(name.isNotBlank() && name.length <= 100 && profile.isNotBlank()) { "请填写记录册名称" }
        require(characterId.isNotBlank() && ruleRevision.isNotBlank()) { "记录册缺少角色或规则版本" }
        require(observations.size <= 1000) { "每册最多保存 1000 次尝试，请新建记录册" }
        require(observations.map { it.id }.distinct().size == observations.size) { "升级记录编号重复" }
        observations.forEach { row ->
            require(runCatching { UUID.fromString(row.id).toString() == row.id }.getOrDefault(false)) { "升级记录编号无效" }
            require(row.level in 1..(if (row.levelsUp) 98 else 99) && row.profession.isNotBlank()) { "请填写有效的操作前等级及职业" }
            require(row.gains.size == 9 && row.gains.all { it in 0..9 }) { "九项实际提升应为 0 至 9 的整数" }
            require(row.room.size == 9 && row.room.all { it in 0..32767 }) { "属性剩余空间无效" }
            require(listOf(row.skipped, row.exam != null, row.professionChangeFrom != null).count { it } <= 1) { "一次记录不能同时是考试、换职业和跳过" }
            row.exam?.let {
                require(it.targetProfession.isEmpty() || it.targetProfession.isNotBlank()) { "考试目标职业无效" }
                require(it.probability == null || it.probability.isFinite() && it.probability in 0.0..100.0) { "考试成功率无效" }
                require(it.resourceCost == null || it.resourceCost in 0..10000) { "考试资源消耗无效" }
            }
            require(row.professionChangeFrom == null || row.professionChangeFrom.isNotBlank()) { "换职业记录缺少原职业" }
            require(row.growthLevel == null || row.growthLevel in 1..98 && row.levelsUp) { "批次成长等级只能用于实际升级，且应在 1 至 98 之间" }
            require((row.exam == null && row.professionChangeFrom == null) || row.gains.all { it == 0 }) { "考试和换职业不能记录为升级提升" }
        }
        seedCheckpoint?.validated()
        require(unlockedProfessions.all(String::isNotBlank)) { "已解锁职业列表无效" }
        growthPlan?.validated()
        importedGrowthPlan?.validated()
        require(importSourceSha256 == null || SHA256_PATTERN.matches(importSourceSha256)) { "来源文件摘要无效" }
        require(importSourceRecordIndex == null || importSourceRecordIndex >= 0 && importSourceSha256 != null) {
            "来源记录位置无效"
        }
        return this
    }

    val currentLevel: Int?
        get() = observations.lastOrNull()?.let { it.level + if (it.levelsUp) 1 else 0 }
            ?: seedCheckpoint?.takeIf { hasCurrentSeedCheckpoint() }?.level

    val currentProfession: String?
        get() = observations.lastOrNull()?.profession ?: seedCheckpoint?.takeIf { hasCurrentSeedCheckpoint() }?.profession

    val currentPart3: Boolean?
        get() = observations.lastOrNull()?.part3 ?: seedCheckpoint?.takeIf { hasCurrentSeedCheckpoint() }?.part3

    fun confirmedUnlockedProfessions(): Set<String> = unlockedProfessions +
        seedCheckpoint?.takeIf { hasCurrentSeedCheckpoint() }?.unlockedProfessions.orEmpty() + observations.flatMap { row ->
        buildSet {
            add(row.profession)
            row.exam?.takeIf { it.passed && it.targetProfession.isNotBlank() }?.let { add(it.targetProfession) }
        }
    }

    fun recordExam(targetProfession: String, probability: Double, passed: Boolean, resourceCost: Int, rules: BuddyRules): LevelJournal {
        val level = requireNotNull(currentLevel) { "先记录当前等级和职业，再记录考试结果" }
        val profession = requireNotNull(currentProfession)
        val part3 = requireNotNull(currentPart3)
        require(rules.classAllowed(characterId, targetProfession, part3)) { "考试目标职业不适用于当前角色或篇章" }
        val row = LevelObservation(level = level, profession = profession, part3 = part3,
            exam = JournalExam(targetProfession, probability, passed, resourceCost))
        val updated = copy(observations = observations + row)
        val checkpoint = seedCheckpoint?.takeIf { hasCurrentSeedCheckpoint() }?.let { current ->
            val simulation = SeedEngine.simulateExam(current.seed, ExamParameters(probability))
            if (simulation.passed == passed) current.copy(
                seed = simulation.nextSeed,
                eventIndex = current.eventIndex + 1,
                evidence = JournalSeedEvidence.EVENT_CONFIRMED,
                historyDigest = updated.historyDigest(),
                unlockedProfessions = current.unlockedProfessions + if (passed) setOf(targetProfession) else emptySet<String>(),
            ).validated() else current
        }
        return updated.copy(seedCheckpoint = checkpoint).validated()
    }

    fun recordProfessionChange(targetProfession: String, rules: BuddyRules): LevelJournal {
        val level = requireNotNull(currentLevel) { "先记录当前等级和职业，再记录转职" }
        val profession = requireNotNull(currentProfession)
        val part3 = requireNotNull(currentPart3)
        require(targetProfession != profession) { "当前已经是这个职业" }
        require(rules.classAllowed(characterId, targetProfession, part3)) { "目标职业不适用于当前角色或篇章" }
        require(targetProfession in confirmedUnlockedProfessions()) { "该职业尚未确认解锁；请先记录通过考试或确认已解锁" }
        val row = LevelObservation(level = level, profession = targetProfession, part3 = part3,
            professionChangeFrom = profession)
        val updated = copy(observations = observations + row)
        val checkpoint = seedCheckpoint?.takeIf { hasCurrentSeedCheckpoint() }?.copy(
            profession = targetProfession,
            historyDigest = updated.historyDigest(),
            unlockedProfessions = confirmedUnlockedProfessions(),
        )
        return updated.copy(seedCheckpoint = checkpoint).validated()
    }

    fun removeObservations(ids: Set<String>): LevelJournal {
        require(ids.isNotEmpty()) { "请先选择要删除的记录" }
        require(ids.all { selectedId -> observations.any { it.id == selectedId } }) { "所选记录已不存在，请刷新后重试" }
        return copy(observations = observations.filterNot { it.id in ids }).validated()
    }

    fun moveObservation(id: String, offset: Int): LevelJournal {
        require(offset == -1 || offset == 1) { "记录只能上移或下移一位" }
        val from = observations.indexOfFirst { it.id == id }
        require(from >= 0) { "所选记录已不存在，请刷新后重试" }
        val to = from + offset
        require(to in observations.indices) { "记录已位于列表边界" }
        val reordered = observations.toMutableList().apply { add(to, removeAt(from)) }
        return copy(observations = reordered).validated()
    }

    fun recordPlannedUpgrade(observation: LevelObservation, rules: BuddyRules): LevelJournal {
        require(hasCurrentGrowthPlan()) { "培养方案已失效，请重新反推并规划" }
        require(observation.levelsUp && !observation.skipped && observation.exam == null && observation.professionChangeFrom == null) {
            "计划步骤只能确认实际升级结果"
        }
        require(observation.growthLevel == null || observation.growthLevel == observation.level) {
            "计划步骤不能沿用批次导入的成长等级"
        }
        require(observation.gains.size == GrowthStat.entries.size && observation.gains.all { it in 0..9 }) {
            "实际提升应为九项 0 至 9 的整数"
        }
        val plan = requireNotNull(growthPlan)
        val checkpoint = requireNotNull(seedCheckpoint)
        val step = plan.result.steps.getOrNull(plan.nextStepIndex) ?: error("培养方案已全部完成")
        require(step.level == checkpoint.level && step.seed == checkpoint.seed && observation.level == step.level) {
            "培养方案起点与当前记录不一致"
        }
        val previousProfession = if (plan.nextStepIndex == 0) plan.request.initialProfession
            else plan.result.steps[plan.nextStepIndex - 1].profession
        require(checkpoint.profession == previousProfession) { "当前职业与培养方案进度不一致" }
        require(step.switchFrom == if (step.profession == previousProfession) null else previousProfession) {
            "培养方案职业切换与上一步不一致"
        }
        require(observation.profession in confirmedUnlockedProfessions()) { "实际职业尚未确认解锁" }
        require(!checkpoint.part3 || observation.part3) { "已经进入第三部，不能记录为前篇升级" }
        val growth = rules.growth(BuddyGrowthInput(characterId, observation.profession, observation.level,
            observation.part3, line.rulesRoute, observation.ride, ruleRevision))
        require(growth.classAllowed && growth.effectiveRide == observation.ride) { "实际职业或坐骑不适用于当前记录" }
        val expectedPart3 = checkpoint.part3 || plan.request.currentPart3 ||
            plan.request.part3StartLevel?.let { step.level >= it } == true
        val checkpointStats = requireNotNull(checkpoint.stats) { "当前属性尚未确认，请先补充实测值" }
        val expectedRoom = plan.request.caps.indices.map { index ->
            ((plan.request.caps[index] ?: 32_767) - checkpointStats[index]).coerceAtLeast(0)
        }
        val simulatedStep = runCatching {
            val character = rules.characters.single { it.id == characterId }
            require(!character.part3Only || expectedPart3)
            require(!rules.professionIsPart3Only(step.profession) || expectedPart3)
            require(step.profession in plan.request.unlockedProfessions)
            require(plan.request.professionWindows.any { window ->
                window.profession == step.profession && step.level in window.fromLevel..window.throughLevel
            })
            val predictedGrowth = rules.growth(BuddyGrowthInput(characterId, step.profession, step.level,
                expectedPart3, line.rulesRoute, step.ride, plan.ruleRevision))
            require(predictedGrowth.classAllowed && predictedGrowth.effectiveRide == step.ride)
            SeedEngine.simulateLevel(step.seed, predictedGrowth.parameters.copy(room = expectedRoom))
        }.getOrNull()
        val trustedStep = simulatedStep?.let { it.gains == step.gains && it.nextSeed == step.nextSeed } == true
        val rows = buildList {
            if (observation.profession != previousProfession) {
                add(LevelObservation(level = step.level, profession = observation.profession, part3 = observation.part3,
                    professionChangeFrom = previousProfession))
            }
            add(observation)
        }
        val updated = copy(observations = observations + rows)
        val matchesPrediction = trustedStep && observation.profession == step.profession && observation.part3 == expectedPart3 &&
            observation.ride == step.ride && observation.gains == step.gains && observation.room == expectedRoom
        if (!matchesPrediction) return updated.validated()

        val stats = checkpointStats.indices.map { index -> checkpointStats[index] + observation.gains[index] }
        require(plan.request.caps.indices.all { index -> plan.request.caps[index] == null || stats[index] <= plan.request.caps[index]!! }) {
            "实测属性超过方案记录的上限，请检查起始属性或上限后重新规划"
        }
        val updatedCheckpoint = checkpoint.copy(seed = step.nextSeed,
            eventIndex = updated.observations.count { it.professionChangeFrom == null },
            level = step.level + 1, profession = observation.profession, part3 = observation.part3, stats = stats,
            caps = plan.request.caps, unlockedProfessions = updated.confirmedUnlockedProfessions(),
            evidence = JournalSeedEvidence.PLAN_CONFIRMED, historyDigest = updated.historyDigest()).validated()
        val updatedPlan = plan.copy(historyDigest = updated.historyDigest(),
            nextStepIndex = plan.nextStepIndex + 1).validated()
        return updated.copy(seedCheckpoint = updatedCheckpoint, growthPlan = updatedPlan).validated()
    }

    fun hasCurrentImportedGrowthPlan(): Boolean = importedGrowthPlan?.let { plan ->
        plan.matches(this) && ruleRevision == BuddyRules.REVISION && plan.ruleRevision == ruleRevision
    } == true

    fun confirmImportedGrowthUpgrade(observation: LevelObservation, rules: BuddyRules): LevelJournal {
        val plan = requireNotNull(importedGrowthPlan) { "没有可继续的导入路线" }
        require(hasCurrentImportedGrowthPlan()) { "导入路线与当前记录不一致，不能继续或撤销" }
        val action = plan.actions.getOrNull(plan.nextStepIndex) ?: error("导入路线已全部完成")
        require(action.kind == ImportedGrowthActionKind.UPGRADE) { "下一步是考试，请先确认考试结果" }
        val level = plan.currentLevel()
        val currentProfession = plan.currentProfession()
        val part3 = plan.part3
        require(observation.level == level && observation.part3 == part3 &&
            observation.levelsUp && !observation.skipped && observation.exam == null &&
            observation.professionChangeFrom == null && observation.growthLevel == null) {
            "请按当前路线步骤录入实际升级结果"
        }
        require(rules.professionForJobId(action.jobId, characterId, part3)?.name == action.profession) {
            "路线职业映射与当前规则不一致"
        }
        require(observation.profession in confirmedUnlockedProfessions()) { "实际职业尚未确认解锁" }
        require(rules.classAllowed(characterId, observation.profession, part3)) { "实际职业不适用于当前角色或篇章" }
        require(observation.gains.size == GrowthStat.entries.size && observation.gains.all { it in 0..9 } &&
            observation.room.size == GrowthStat.entries.size && observation.room.all { it in 0..32767 }) {
            "请填写九项有效的实际提升和属性剩余空间"
        }
        val priorStats = plan.currentStats()
        val nextStats = priorStats.indices.map { index -> priorStats[index] + observation.gains[index] }
        require(nextStats.all { it <= 32767 } && plan.caps.indices.all { index ->
            plan.caps[index] == null || nextStats[index] <= plan.caps[index]!!
        }) { "实际属性超过已记录的上限，请核对属性或上限" }
        val room = observation.room
        require(plan.caps.indices.all { index ->
            plan.caps[index] == null || room[index] == (plan.caps[index]!! - priorStats[index]).coerceAtLeast(0)
        }) { "属性剩余空间与已记录上限不一致" }
        val recorded = observation
        val priorSeed = plan.currentSeed()
        val verified = priorSeed?.let { seed -> runCatching {
            val growth = rules.growth(BuddyGrowthInput(characterId, observation.profession, level, part3,
                line.rulesRoute, observation.ride, ruleRevision))
            require(growth.classAllowed && growth.effectiveRide == observation.ride)
            SeedEngine.simulateLevel(seed, growth.parameters.copy(room = room))
        }.getOrNull() }?.takeIf { it.gains == recorded.gains }
        val addedRows = buildList {
            if (currentProfession != recorded.profession) add(LevelObservation(level = level, profession = recorded.profession,
                part3 = part3, professionChangeFrom = currentProfession))
            add(recorded)
        }
        val updated = copy(observations = observations + addedRows, seedCheckpoint = null)
        val digest = updated.historyDigest()
        val nextUnlocked = updated.confirmedUnlockedProfessions()
        val nextSeed = verified?.nextSeed
        val checkpoint = nextSeed?.let { seed -> JournalSeedCheckpoint(seed = seed,
            eventIndex = updated.observations.count { it.professionChangeFrom == null }, level = level + 1,
            profession = recorded.profession, part3 = part3, stats = nextStats, caps = plan.caps,
            evidence = JournalSeedEvidence.PLAN_CONFIRMED, historyDigest = digest, ruleRevision = ruleRevision,
            sourceSha256 = plan.sourceSha256, originSeed = plan.initialSeed,
            unlockedProfessions = nextUnlocked).validated() }
        val confirmation = JournalImportedGrowthConfirmation(plan.nextStepIndex, addedRows.map(LevelObservation::id),
            nextSeed, nextStats, level + 1, recorded.profession, nextUnlocked)
        val updatedPlan = plan.copy(nextStepIndex = plan.nextStepIndex + 1,
            confirmations = plan.confirmations + confirmation, historyDigest = digest).validated()
        return updated.copy(seedCheckpoint = checkpoint, importedGrowthPlan = updatedPlan).validated()
    }

    fun confirmImportedGrowthExam(passed: Boolean, rules: BuddyRules): LevelJournal {
        val plan = requireNotNull(importedGrowthPlan) { "没有可继续的导入路线" }
        require(hasCurrentImportedGrowthPlan()) { "导入路线与当前记录不一致，不能继续或撤销" }
        val action = plan.actions.getOrNull(plan.nextStepIndex) ?: error("导入路线已全部完成")
        require(action.kind == ImportedGrowthActionKind.EXAM) { "下一步是升级，请先录入实际成长" }
        val level = plan.currentLevel()
        val profession = plan.currentProfession()
        require(rules.professionForJobId(action.jobId, characterId, plan.part3)?.name == action.profession &&
            rules.classAllowed(characterId, action.profession, plan.part3)) { "考试目标职业与当前角色或篇章不匹配" }
        val observation = LevelObservation(level = level, profession = profession, part3 = plan.part3,
            exam = JournalExam(action.profession, probability = null, passed = passed, resourceCost = null))
        val updated = copy(observations = observations + observation, seedCheckpoint = null)
        val digest = updated.historyDigest()
        val unlocked = updated.confirmedUnlockedProfessions()
        val nextSeed = plan.currentSeed()?.let(SeedEngine::advance)
        val checkpoint = nextSeed?.let { seed -> JournalSeedCheckpoint(seed = seed,
            eventIndex = updated.observations.count { it.professionChangeFrom == null }, level = level,
            profession = profession, part3 = plan.part3, stats = plan.currentStats(), caps = plan.caps,
            evidence = JournalSeedEvidence.PLAN_CONFIRMED, historyDigest = digest, ruleRevision = ruleRevision,
            sourceSha256 = plan.sourceSha256, originSeed = plan.initialSeed,
            unlockedProfessions = unlocked).validated() }
        val confirmation = JournalImportedGrowthConfirmation(plan.nextStepIndex, listOf(observation.id), nextSeed,
            plan.currentStats(), level, profession, unlocked)
        val updatedPlan = plan.copy(nextStepIndex = plan.nextStepIndex + 1,
            confirmations = plan.confirmations + confirmation, historyDigest = digest).validated()
        return updated.copy(seedCheckpoint = checkpoint, importedGrowthPlan = updatedPlan).validated()
    }

    fun undoLatestImportedGrowthAction(): LevelJournal {
        val plan = requireNotNull(importedGrowthPlan) { "没有可撤销的导入路线步骤" }
        require(hasCurrentImportedGrowthPlan()) { "导入路线与当前记录不一致，不能继续或撤销" }
        val confirmation = plan.confirmations.lastOrNull() ?: error("还没有确认过路线步骤")
        val tailIds = observations.takeLast(confirmation.observationIds.size).map(LevelObservation::id)
        require(tailIds == confirmation.observationIds) { "最近一步已不是记录册末尾内容，不能安全撤销" }
        val remaining = observations.dropLast(confirmation.observationIds.size)
        val base = copy(observations = remaining, seedCheckpoint = null)
        val digest = base.historyDigest()
        val restoredPlan = plan.copy(nextStepIndex = plan.nextStepIndex - 1,
            confirmations = plan.confirmations.dropLast(1), historyDigest = digest).validated()
        val seed = restoredPlan.currentSeed()
        val unlocked = restoredPlan.currentUnlockedProfessions()
        val checkpoint = seed?.let { value -> JournalSeedCheckpoint(seed = value,
            eventIndex = remaining.count { it.professionChangeFrom == null }, level = restoredPlan.currentLevel(),
            profession = restoredPlan.currentProfession(), part3 = restoredPlan.part3,
            stats = restoredPlan.currentStats(), caps = restoredPlan.caps,
            evidence = if (restoredPlan.confirmations.isEmpty()) JournalSeedEvidence.USER_CONFIRMED_IMPORT
                else JournalSeedEvidence.PLAN_CONFIRMED,
            historyDigest = digest, ruleRevision = ruleRevision, sourceSha256 = restoredPlan.sourceSha256,
            originSeed = restoredPlan.initialSeed, unlockedProfessions = unlocked).validated() }
        return base.copy(seedCheckpoint = checkpoint, unlockedProfessions = unlocked,
            importedGrowthPlan = restoredPlan).validated()
    }

    fun withConfirmedUnlockedProfessions(professions: Set<String>): LevelJournal {
        require(professions.all(String::isNotBlank)) { "已解锁职业列表无效" }
        val required = buildSet {
            observations.forEach { row ->
                add(row.profession)
                row.professionChangeFrom?.let(::add)
                row.exam?.takeIf { it.passed }?.targetProfession?.takeIf(String::isNotBlank)?.let(::add)
            }
            seedCheckpoint?.takeIf { hasCurrentSeedCheckpoint() }?.profession?.let(::add)
        }
        require(professions.containsAll(required)) { "不能取消记录中已确认使用或通过考试的职业" }
        val checkpoint = seedCheckpoint?.takeIf { hasCurrentSeedCheckpoint() }?.copy(unlockedProfessions = professions)
        val updatedPlan = importedGrowthPlan?.takeIf { it.matches(this) }?.let { plan ->
            if (plan.confirmations.isEmpty()) plan.copy(initialUnlockedProfessions = professions)
            else plan.copy(confirmations = plan.confirmations.dropLast(1) +
                plan.confirmations.last().copy(unlockedProfessionsAfter = professions))
        }
        return copy(unlockedProfessions = professions, seedCheckpoint = checkpoint ?: seedCheckpoint,
            importedGrowthPlan = updatedPlan).validated()
    }

    fun historyDigest(): String {
        val canonical = buildJsonObject {
            put("line", line.id)
            put("characterId", characterId)
            put("ruleRevision", ruleRevision)
            put("observations", JsonArray(observations.map { row -> buildJsonObject {
                put("id", row.id); put("level", row.level); put("profession", row.profession)
                row.growthLevel?.let { put("growthLevel", it) }
                put("part3", row.part3); put("skipped", row.skipped)
                put("exam", row.exam?.encode() ?: JsonNull)
                put("professionChangeFrom", row.professionChangeFrom?.let(::JsonPrimitive) ?: JsonNull)
                put("gains", JsonArray(row.gains.map(::JsonPrimitive)))
                put("room", JsonArray(row.room.map(::JsonPrimitive)))
                put("ride", row.ride?.let { ride -> buildJsonObject { put("id", ride.id); put("level", ride.level) } } ?: JsonNull)
            } }))
        }.toString().toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(canonical).joinToString("") { "%02x".format(it) }
    }

    fun hasCurrentSeedCheckpoint(): Boolean = seedCheckpoint?.let {
        it.historyDigest == historyDigest() && ruleRevision == BuddyRules.REVISION &&
            it.ruleRevision == BuddyRules.REVISION && it.ruleRevision == ruleRevision &&
            it.eventIndex == observations.count { row -> row.professionChangeFrom == null }
    } == true

    fun hasCurrentGrowthPlan(): Boolean {
        val checkpoint = seedCheckpoint ?: return false
        val plan = growthPlan ?: return false
        return hasCurrentSeedCheckpoint() && plan.historyDigest == historyDigest() &&
            plan.ruleRevision == ruleRevision && plan.request.characterId == characterId && plan.request.line == line &&
            plan.matchesCheckpoint(checkpoint) && runCatching { plan.validated() }.isSuccess
    }

    fun hasCurrentGrowthPlan(rules: BuddyRules): Boolean {
        if (!hasCurrentGrowthPlan()) return false
        val plan = requireNotNull(growthPlan)
        return runCatching { BuddyGrowthPlanner.plan(plan.request, rules) == plan.result }.getOrDefault(false)
    }

    fun history(rules: BuddyRules): BuddySeedHistory {
        validated()
        val errors = mutableListOf<String>()
        val events = mutableListOf<ObservedSeedEvent>()
        var levels = 0
        var skips = 0
        var expectedLevel: Int? = null
        var expectedProfession: String? = null
        var enteredPart3 = false
        var previousRow: LevelObservation? = null
        if (ruleRevision != BuddyRules.REVISION) errors += "此记录使用的成长规则版本尚未安装。"
        observations.forEachIndexed { index, row ->
            val prefix = "第 ${index + 1} 条："
            if (row.growthLevel != null && row.growthLevel < row.level) errors += prefix + "批次成长等级早于该次升级。"
            if (row.growthLevel != null && row.growthLevel > row.level) {
                val next = observations.getOrNull(index + 1)
                if (next == null || !next.levelsUp || next.level != row.level + 1 || next.growthLevel != row.growthLevel) {
                    errors += prefix + "连续结算批次不完整或中间插入了其他操作。"
                }
            }
            previousRow?.takeIf { it.levelsUp && row.levelsUp && it.growthLevel != null && it.growthLevel == row.growthLevel }
                ?.let { previous ->
                    if (previous.profession != row.profession || previous.ride != row.ride || previous.part3 != row.part3) {
                        errors += prefix + "同次连续结算不能更换职业、坐骑或篇章。"
                    }
                }
            if (expectedLevel != null && row.level != expectedLevel) errors += prefix + "等级不连续，请核对漏记的升级或删除后的等级。"
            if (row.professionChangeFrom != null && expectedProfession != null && row.professionChangeFrom != expectedProfession) {
                errors += prefix + "职业切换记录与上一条职业不连续。"
            }
            if (enteredPart3 && !row.part3) errors += prefix + "不能从第三部退回前篇。"
            enteredPart3 = enteredPart3 || row.part3
            expectedProfession = row.profession
            expectedLevel = row.level + if (row.levelsUp) 1 else 0
            try {
                val character = requireNotNull(rules.characters.find { it.id == characterId }) { "角色没有已验证的成长规则" }
                require(!character.part3Only || row.part3) { "该角色须使用第三部规则" }
                val growth = rules.growth(BuddyGrowthInput(characterId, row.profession, row.growthLevel ?: row.level,
                    row.part3, line.rulesRoute, row.ride, ruleRevision))
                require(growth.classAllowed) { "职业不适用于当前角色或篇章" }
                require(growth.effectiveRide == row.ride) { "坐骑不适用于当前职业或路线" }
                when {
                    row.exam != null -> {
                        if (row.exam.targetProfession.isNotBlank()) {
                            require(rules.classAllowed(characterId, row.exam.targetProfession, row.part3)) { "考试目标职业不适用于当前角色或篇章" }
                        }
                        events += row.exam.probability?.let { probability ->
                            ObservedSeedEvent.Exam(ExamParameters(probability), row.exam.passed)
                        } ?: ObservedSeedEvent.Advance()
                    }
                    row.professionChangeFrom != null -> {
                        require(rules.classAllowed(characterId, row.professionChangeFrom, row.part3)) { "原职业不适用于当前角色或篇章" }
                    }
                    row.skipped -> {
                        events += ObservedSeedEvent.Advance()
                        skips++
                    }
                    else -> {
                        events += ObservedSeedEvent.Level(growth.parameters.copy(room = row.room), row.gains)
                        levels++
                    }
                }
            } catch (error: IllegalArgumentException) {
                errors += prefix + (error.message ?: "成长参数无效")
            }
            previousRow = row
        }
        val warnings = if (levels < BuddySeedHistory.MIN_OBSERVED_LEVELS)
            listOf("至少记录 ${BuddySeedHistory.MIN_OBSERVED_LEVELS} 次实际升级后才能反推；当前 $levels 次。") else emptyList()
        val lastRow = observations.lastOrNull()
        val endProfession = lastRow?.profession ?: seedCheckpoint?.profession
        val endLevel = (lastRow?.let { it.level + if (it.levelsUp) 1 else 0 } ?: seedCheckpoint?.level)?.coerceIn(1, 99)
        val endPart3 = lastRow?.part3 ?: seedCheckpoint?.part3 ?: enteredPart3
        val endState = if (endProfession != null && endLevel != null) {
            BuddySeedHistoryEndState(
                level = endLevel,
                profession = endProfession,
                part3 = endPart3,
                line = line,
                ride = lastRow?.ride,
                unlockedProfessions = confirmedUnlockedProfessions() + endProfession,
            )
        } else null
        return BuddySeedHistory(characterId, events, levels, skips, warnings, errors, endState)
    }

    fun encode(): JsonObject {
        validated()
        return buildJsonObject {
            put("version", 7); put("name", name); put("profileId", profile)
            put("line", line.id); put("characterId", characterId); put("ruleRevision", ruleRevision)
            put("observations", JsonArray(observations.map { row -> buildJsonObject {
                put("id", row.id); put("level", row.level); put("profession", row.profession)
                put("growthLevel", row.growthLevel?.let(::JsonPrimitive) ?: JsonNull)
                put("part3", row.part3); put("skipped", row.skipped)
                put("exam", row.exam?.encode() ?: JsonNull)
                put("professionChangeFrom", row.professionChangeFrom?.let(::JsonPrimitive) ?: JsonNull)
                put("gains", JsonArray(row.gains.map(::JsonPrimitive)))
                put("room", JsonArray(row.room.map(::JsonPrimitive)))
                put("ride", row.ride?.let { ride -> buildJsonObject { put("id", ride.id); put("level", ride.level) } } ?: JsonNull)
            } }))
            put("seedCheckpoint", seedCheckpoint?.let { checkpoint -> buildJsonObject {
                put("seed", checkpoint.seed); put("eventIndex", checkpoint.eventIndex); put("level", checkpoint.level)
                put("profession", checkpoint.profession); put("part3", checkpoint.part3)
                put("stats", checkpoint.stats?.let { JsonArray(it.map(::JsonPrimitive)) } ?: JsonNull)
                put("caps", checkpoint.caps?.let { JsonArray(it.map { cap -> cap?.let(::JsonPrimitive) ?: JsonNull }) } ?: JsonNull)
                put("unlockedProfessions", JsonArray(checkpoint.unlockedProfessions.sorted().map(::JsonPrimitive)))
                put("evidence", checkpoint.evidence.id); put("historyDigest", checkpoint.historyDigest)
                put("ruleRevision", checkpoint.ruleRevision); put("sourceSha256", checkpoint.sourceSha256?.let(::JsonPrimitive) ?: JsonNull)
                put("originSeed", checkpoint.originSeed?.let(::JsonPrimitive) ?: JsonNull)
                put("matchCount", checkpoint.matchCount?.let(::JsonPrimitive) ?: JsonNull)
            } } ?: JsonNull)
            put("unlockedProfessions", JsonArray(unlockedProfessions.sorted().map(::JsonPrimitive)))
            put("growthPlan", growthPlan?.encode() ?: JsonNull)
            put("importSourceSha256", importSourceSha256?.let(::JsonPrimitive) ?: JsonNull)
            put("importSourceRecordIndex", importSourceRecordIndex?.let(::JsonPrimitive) ?: JsonNull)
            put("importedGrowthPlan", importedGrowthPlan?.encode() ?: JsonNull)
        }
    }

    companion object {
        const val KIND = "level-journal"
        private val SHA256_PATTERN = Regex("[0-9a-f]{64}")

        fun decode(payload: JsonObject): LevelJournal {
            val version = payload.getValue("version").jsonPrimitive.int
            require(version in 1..7) { "不支持此升级记录版本" }
            val line = requireNotNull(BuddyCampaignLine.entries.find { it.id == payload.getValue("line").jsonPrimitive.content }) {
                "无法识别路线，未将记录归入其他路线"
            }
            val checkpoint = payload["seedCheckpoint"]?.takeUnless { it is JsonNull }?.jsonObject?.let { item ->
                val evidenceId = item.getValue("evidence").jsonPrimitive.content
                val evidence = requireNotNull(JournalSeedEvidence.entries.find { it.id == evidenceId }) { "无法识别种子证据类型" }
                JournalSeedCheckpoint(
                    item.getValue("seed").jsonPrimitive.long,
                    item.getValue("eventIndex").jsonPrimitive.int,
                    item.getValue("level").jsonPrimitive.int,
                    item.getValue("profession").jsonPrimitive.content,
                    item.getValue("part3").jsonPrimitive.boolean,
                    item["stats"]?.takeUnless { it is JsonNull }?.jsonArray?.map { it.jsonPrimitive.int },
                    item["caps"]?.takeUnless { it is JsonNull }?.jsonArray?.map { it.takeUnless { value -> value is JsonNull }?.jsonPrimitive?.int },
                    evidence,
                    item.getValue("historyDigest").jsonPrimitive.content,
                    item.getValue("ruleRevision").jsonPrimitive.content,
                    item["sourceSha256"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content,
                    item["originSeed"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.long,
                    item["matchCount"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.long,
                    item["unlockedProfessions"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty(),
                ).validated()
            }
            return LevelJournal(payload.getValue("name").jsonPrimitive.content, payload.getValue("profileId").jsonPrimitive.content,
                line, payload.getValue("characterId").jsonPrimitive.content,
                payload.getValue("observations").jsonArray.map { element ->
                    val row = element.jsonObject
                    LevelObservation(row.getValue("id").jsonPrimitive.content, row.getValue("level").jsonPrimitive.int,
                        row.getValue("profession").jsonPrimitive.content, row.getValue("part3").jsonPrimitive.boolean,
                        row.getValue("ride").takeUnless { it is JsonNull }?.jsonObject?.let {
                            BuddyRideSelection(it.getValue("id").jsonPrimitive.content, it.getValue("level").jsonPrimitive.int)
                        }, row.getValue("gains").jsonArray.map { it.jsonPrimitive.int },
                        row.getValue("room").jsonArray.map { it.jsonPrimitive.int }, row.getValue("skipped").jsonPrimitive.boolean,
                        row["exam"]?.takeUnless { it is JsonNull }?.jsonObject?.let { exam ->
                            JournalExam(exam["targetProfession"]?.takeUnless { it is JsonNull }?.let {
                                require(it is JsonPrimitive && it.isString) { "考试目标职业必须是文本" }
                                it.content
                            }.orEmpty(),
                                exam["probability"]?.takeUnless { it is JsonNull }?.let {
                                    require(it is JsonPrimitive && !it.isString && it.doubleOrNull != null) { "考试成功率必须是数字或未知" }
                                    it.double
                                },
                                exam.getValue("passed").jsonPrimitive.boolean,
                                exam["resourceCost"]?.takeUnless { it is JsonNull }?.let {
                                    require(it is JsonPrimitive && !it.isString && it.intOrNull != null) { "考试消耗必须是整数或未知" }
                                    it.int
                                })
                        }, row["professionChangeFrom"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content,
                        row["growthLevel"]?.takeUnless { it is JsonNull }?.let {
                            require(it is JsonPrimitive && !it.isString && it.intOrNull != null) { "批次成长等级必须是整数或空值" }
                            it.int
                        })
                }, payload.getValue("ruleRevision").jsonPrimitive.content, checkpoint,
                payload["unlockedProfessions"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty(),
                payload["growthPlan"]?.takeUnless { it is JsonNull }?.jsonObject?.let(JournalGrowthPlan::decode),
                payload["importSourceSha256"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content,
                payload["importSourceRecordIndex"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.int,
                payload["importedGrowthPlan"]?.takeUnless { it is JsonNull }?.jsonObject?.let(JournalImportedGrowthPlan::decode)).validated()
        }
    }
}

private fun JournalExam.encode(): JsonObject = buildJsonObject {
    put("targetProfession", targetProfession)
    put("probability", probability?.let(::JsonPrimitive) ?: JsonNull)
    put("passed", passed)
    put("resourceCost", resourceCost?.let(::JsonPrimitive) ?: JsonNull)
}
