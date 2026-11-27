package com.fireemblem.companion.campaign

import com.fireemblem.companion.content.WikiEntry
import com.fireemblem.companion.engine.BuddyCampaignLine
import com.fireemblem.companion.personal.PersonalRevision
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

enum class CampaignTaskKind(val categoryId: String, val label: String) {
    SIDE_QUEST("side-quests", "支线任务"),
    PARALOGUE("paralogues", "外传"),
    RECRUITMENT("characters", "角色招募"),
    TIMELINE("campaign-timeline", "事件时间轴"),
    CHALLENGE("challenge-book", "挑战书"),
}

enum class CampaignTaskStatus(val id: String) {
    TODO("todo"),
    COMPLETED("completed"),
    MISSED("missed");

    fun label(kind: CampaignTaskKind): String = when (this) {
        TODO -> when (kind) {
            CampaignTaskKind.RECRUITMENT -> "未招募"
            CampaignTaskKind.TIMELINE -> "待记录"
            else -> "待办"
        }
        COMPLETED -> if (kind == CampaignTaskKind.RECRUITMENT) "已招募" else "已完成"
        MISSED -> "已错过"
    }

    companion object {
        fun fromId(id: String?): CampaignTaskStatus? = entries.firstOrNull { it.id == id }
    }
}

data class CampaignGuideTask(
    val entryId: String,
    val title: String,
    val kind: CampaignTaskKind,
    val imageAsset: String?,
    val availableOnLine: Boolean,
    val availability: String?,
    val deadline: String?,
    val timing: String?,
    val characters: String?,
    val location: String?,
    val completion: String?,
    val conditions: String?,
    val rewards: String?,
    val hints: String?,
    val victoryCondition: String?,
    val chapter: String? = null,
    val isCommon: Boolean = false,
    val sourceOrder: Int = 0,
)

enum class CampaignTaskFacet(val label: String) {
    CHAPTER("章节 / 接取时间"),
    DEADLINE("期限 / 完成条件"),
    CONDITION("条件"),
    REWARD("奖励");

    fun value(task: CampaignGuideTask): String? = when (this) {
        CHAPTER -> when (task.kind) {
            CampaignTaskKind.SIDE_QUEST -> task.timing
            CampaignTaskKind.PARALOGUE, CampaignTaskKind.RECRUITMENT -> task.availability
            CampaignTaskKind.TIMELINE -> task.chapter
            CampaignTaskKind.CHALLENGE -> null
        }
        DEADLINE -> if (task.kind == CampaignTaskKind.SIDE_QUEST) task.completion else task.deadline
        CONDITION -> when (task.kind) {
            CampaignTaskKind.SIDE_QUEST -> task.completion
            CampaignTaskKind.PARALOGUE -> task.conditions
            CampaignTaskKind.RECRUITMENT -> task.timing
            CampaignTaskKind.TIMELINE -> task.location ?: task.conditions
            CampaignTaskKind.CHALLENGE -> task.availability
        }
        REWARD -> task.rewards
    }?.trim()?.takeIf(String::isNotEmpty)

    fun options(tasks: List<CampaignGuideTask>): List<String> = tasks.mapNotNull(::value).distinct().sorted()

    fun matches(task: CampaignGuideTask, selectedValue: String?): Boolean =
        selectedValue.isNullOrBlank() || value(task) == selectedValue
}

object CampaignGuideProjector {
    fun project(line: BuddyCampaignLine, entries: List<WikiEntry>): List<CampaignGuideTask> = entries.mapNotNull { entry ->
        when (entry.id.substringBefore(':')) {
            CampaignTaskKind.SIDE_QUEST.categoryId -> projectSideQuest(line, entry)
            CampaignTaskKind.PARALOGUE.categoryId -> projectParalogue(line, entry)
            CampaignTaskKind.RECRUITMENT.categoryId -> projectRecruitment(line, entry)
            CampaignTaskKind.TIMELINE.categoryId -> projectTimeline(line, entry)
            CampaignTaskKind.CHALLENGE.categoryId -> projectChallenge(line, entry)
            else -> null
        }
    }.sortedWith(
        compareBy<CampaignGuideTask> { it.kind.ordinal }
            .thenBy { if (it.kind == CampaignTaskKind.TIMELINE || it.kind == CampaignTaskKind.CHALLENGE) it.sourceOrder else 0 }
            .thenBy { if (it.kind == CampaignTaskKind.TIMELINE || it.kind == CampaignTaskKind.CHALLENGE) "" else it.title.lowercase() }
            .thenBy { it.entryId },
    )

    private fun projectSideQuest(line: BuddyCampaignLine, entry: WikiEntry): CampaignGuideTask? {
        val values = entry.fields.associate { it.label to it.value.clean() }
        val routeFields = line.routeFields()
        val routeMarker = values[routeFields.sideQuestMarker].orEmpty()
        if (routeMarker.isBlank()) return null
        return CampaignGuideTask(
            entryId = entry.id,
            title = entry.title,
            kind = CampaignTaskKind.SIDE_QUEST,
            imageAsset = entry.imageAsset,
            availableOnLine = true,
            availability = null,
            deadline = null,
            timing = sideQuestTiming(line, values),
            characters = values["任务NPC"],
            location = null,
            completion = values["达成条件/期限"],
            conditions = null,
            rewards = values["报酬奖励"],
            hints = values["提示与备注"],
            victoryCondition = null,
        )
    }

    private fun sideQuestTiming(line: BuddyCampaignLine, values: Map<String, String?>): String? {
        val timing = values["触发时期与地点"] ?: return null
        val routeTitles = BuddyCampaignLine.entries.associateWith { it.routeFields().recruitmentRouteTitle }
        val availableRoutes = BuddyCampaignLine.entries.filter { candidate ->
            !values[candidate.routeFields().sideQuestMarker].isNullOrBlank()
        }.toSet()
        val routeLines = timing.lines().map { text ->
            text to routeTitles.filterValues { routeTitle -> routeTitle in text }.keys
        }
        if (routeLines.any { (_, routes) -> routes.size > 1 }) return timing
        val describedRoutes = routeLines.flatMap { (_, routes) -> routes }.toSet()
        if (!describedRoutes.containsAll(availableRoutes)) return timing
        return routeLines.filter { (_, routes) -> routes.isEmpty() || line in routes }
            .joinToString("\n") { (text, _) -> text }
            .trim()
            .takeIf(String::isNotEmpty)
            ?: timing
    }

    private fun projectParalogue(line: BuddyCampaignLine, entry: WikiEntry): CampaignGuideTask? {
        val values = entry.fields.associate { it.label to it.value.clean() }
        val routeFields = line.routeFields()
        val availability = values[routeFields.paralogueAvailability]
        val deadline = values[routeFields.paralogueDeadline]
        if (availability.isNullOrBlank() && deadline.isNullOrBlank()) return null
        return CampaignGuideTask(
            entryId = entry.id,
            title = entry.title,
            kind = CampaignTaskKind.PARALOGUE,
            imageAsset = entry.imageAsset,
            availableOnLine = true,
            availability = availability,
            deadline = deadline,
            timing = values["触发时期"],
            characters = values["相关角色"],
            location = null,
            completion = null,
            conditions = values["触发条件与特殊奖励"],
            rewards = values["报酬奖励"],
            hints = null,
            victoryCondition = values["胜利条件"],
        )
    }

    private fun projectRecruitment(line: BuddyCampaignLine, entry: WikiEntry): CampaignGuideTask? {
        val values = entry.fields.associate { it.label to it.value.clean() }
        val routeTitle = line.routeFields().recruitmentRouteTitle
        val routeChapter = values["最早加入章节"]?.lineSequence()?.map(String::trim)
            ?.withIndex()?.firstOrNull { (_, value) -> value.contains(routeTitle) }
            ?: return null
        if (listOf("不可挖角", "不可加入", "无缘加入").any { marker -> routeChapter.value.contains(marker) }) return null
        val recruitmentCondition = values["加入条件"]?.lineSequence()?.map(String::trim)
            ?.toList()?.getOrNull(routeChapter.index)?.takeIf(String::isNotEmpty)
        return CampaignGuideTask(
            entryId = entry.id,
            title = entry.title,
            kind = CampaignTaskKind.RECRUITMENT,
            imageAsset = entry.imageAsset,
            availableOnLine = true,
            availability = routeChapter.value,
            deadline = null,
            timing = recruitmentCondition,
            characters = null,
            location = null,
            completion = null,
            conditions = null,
            rewards = null,
            hints = null,
            victoryCondition = null,
        )
    }

    private fun projectTimeline(line: BuddyCampaignLine, entry: WikiEntry): CampaignGuideTask? {
        val values = entry.fields.associate { it.label to it.value.clean() }
        val scope = values["路线范围"] ?: return null
        val common = scope.startsWith("common-")
        if (!common && scope != line.id) return null
        val eventType = values["事件类别"].orEmpty()
        val eventName = values["事件名称"]
        val notes = values["备注"]
        val isGenericEvent = eventType == "事件" && eventName in setOf("对话", "事件", "CG", "专武", "支线")
        val title = if (isGenericEvent) notes ?: entry.title else entry.title
        val isParalogue = eventType == "外传"
        return CampaignGuideTask(
            entryId = entry.id,
            title = title,
            kind = CampaignTaskKind.TIMELINE,
            imageAsset = entry.imageAsset,
            availableOnLine = true,
            availability = values["章节"],
            deadline = values["截止时间"],
            timing = values["开始时间"],
            characters = values["相关人物"],
            location = values["触发方式"],
            completion = null,
            conditions = listOfNotNull(eventType.takeIf(String::isNotBlank), eventName).joinToString(" · ").takeIf(String::isNotBlank),
            rewards = values["奖励"],
            hints = notes?.takeIf { !isParalogue && it != title },
            victoryCondition = notes?.takeIf { isParalogue },
            chapter = values["章节"],
            isCommon = common,
            sourceOrder = entry.sourceRowNumber,
        )
    }

    private fun projectChallenge(line: BuddyCampaignLine, entry: WikiEntry): CampaignGuideTask? {
        if (line != BuddyCampaignLine.DIT) return null
        val values = entry.fields.associate { it.label to it.value.clean() }
        val stats = listOfNotNull(
            values["耐久消耗"]?.let { "耐久 $it" },
            values["威力"]?.let { "威力 $it" },
            values["命中"]?.let { "命中 $it" },
            values["必杀"]?.let { "必杀 $it" },
            values["射程"]?.let { "射程 $it" },
        ).joinToString(" · ").takeIf(String::isNotBlank)
        return CampaignGuideTask(
            entryId = entry.id,
            title = entry.title,
            kind = CampaignTaskKind.CHALLENGE,
            imageAsset = entry.imageAsset,
            availableOnLine = true,
            availability = values["接取条件"],
            deadline = values["任务截止时间"],
            timing = values["对应战技"],
            characters = null,
            location = values["任务地点"],
            completion = values["战技效果"],
            conditions = stats,
            rewards = values["任务奖励"],
            hints = null,
            victoryCondition = null,
            sourceOrder = entry.sourceRowNumber,
        )
    }

    private fun BuddyCampaignLine.routeFields(): RouteFields = when (this) {
        BuddyCampaignLine.KAI -> RouteFields("凯篇", "凯篇接取期限", "凯篇截止期限", "凯伊篇")
        BuddyCampaignLine.DIT -> RouteFields("迪篇", "迪篇接取期限", "迪篇截止期限", "迪托利希篇")
        BuddyCampaignLine.SEO -> RouteFields("赛篇", "赛篇接取期限", "赛篇截止期限", "赛奥朵拉篇")
        BuddyCampaignLine.LEDA -> RouteFields("蕾篇", "蕾篇接取期限", "蕾篇截止期限", "蕾达篇")
    }

    private data class RouteFields(
        val sideQuestMarker: String,
        val paralogueAvailability: String,
        val paralogueDeadline: String,
        val recruitmentRouteTitle: String,
    )

    private fun String.clean(): String? = trim().takeIf { it.isNotEmpty() && it !in setOf("--", "×") }
}

data class CampaignTaskProgress(
    val profileId: String,
    val lineId: String,
    val entryId: String,
    val status: CampaignTaskStatus,
) {
    init {
        require(profileId.isNotBlank()) { "存档编号不能为空" }
        require(BuddyCampaignLine.entries.any { it.id == lineId }) { "路线编号无效" }
        require(CampaignTaskKind.entries.any { it.categoryId == entryId.substringBefore(':') }) { "任务资料编号无效" }
    }

    val entityId: String get() = entityId(profileId, lineId, entryId)

    fun payload(): JsonObject = buildJsonObject {
        put("recordType", RECORD_TYPE)
        put("profileId", profileId)
        put("routeId", lineId)
        put("wikiId", entryId)
        put("status", status.id)
    }

    companion object {
        private const val RECORD_TYPE = "campaign-task"

        fun entityId(profileId: String, lineId: String, entryId: String): String = UUID.nameUUIDFromBytes(
            "campaign-task\u0000$profileId\u0000$lineId\u0000$entryId".toByteArray(StandardCharsets.UTF_8),
        ).toString()

        fun fromRevision(revision: PersonalRevision): CampaignTaskProgress? {
            if (revision.kind != "progress" || revision.deleted) return null
            val payload = revision.payload
            if (payload["recordType"]?.jsonPrimitive?.content != RECORD_TYPE) return null
            val profileId = payload["profileId"]?.jsonPrimitive?.content ?: return null
            val lineId = payload["routeId"]?.jsonPrimitive?.content ?: return null
            val entryId = payload["wikiId"]?.jsonPrimitive?.content ?: return null
            val status = CampaignTaskStatus.fromId(payload["status"]?.jsonPrimitive?.content) ?: return null
            return runCatching { CampaignTaskProgress(profileId, lineId, entryId, status) }
                .getOrNull()?.takeIf { revision.entityId == it.entityId }
        }
    }
}
