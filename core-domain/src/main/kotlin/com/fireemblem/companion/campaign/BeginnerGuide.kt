package com.fireemblem.companion.campaign

import com.fireemblem.companion.content.WikiEntry

data class BeginnerGuideQuestion(
    val entryId: String,
    val question: String,
    val answer: String?,
    val sourceRowNumber: Int,
)

data class BeginnerRouteSummary(
    val entryId: String,
    val title: String,
    val imageAsset: String?,
    val recommendation: String?,
    val recruitableCount: Int?,
    val features: String?,
    val considerations: String?,
    val worldCoverage: String?,
    val sourceRowNumber: Int,
)

data class BeginnerMapAccessRule(
    val entryId: String,
    val requirement: String,
    val locations: String,
    val sourceRowNumber: Int,
)

object BeginnerGuideProjector {
    const val CATEGORY_ID = "beginner-guide"
    const val ROUTES_CATEGORY_ID = "beginner-routes"
    const val MAP_ACCESS_CATEGORY_ID = "beginner-map-access"

    fun project(entries: List<WikiEntry>): List<BeginnerGuideQuestion> = entries
        .asSequence()
        .filter { it.id.substringBefore(':') == CATEGORY_ID }
        .map { entry ->
            val question = entry.title.replaceFirst(QUESTION_PREFIX, "").trim()
            val answer = entry.fields.firstOrNull { it.label == "解答" }?.value
                ?.replaceFirst(ANSWER_PREFIX, "")
                ?.trim()
                ?.takeIf(String::isNotEmpty)
            BeginnerGuideQuestion(entry.id, question, answer, entry.sourceRowNumber)
        }
        .filter { it.question.isNotEmpty() }
        .sortedBy(BeginnerGuideQuestion::sourceRowNumber)
        .toList()

    fun projectRoutes(entries: List<WikiEntry>): List<BeginnerRouteSummary> = entries
        .asSequence()
        .filter { it.id.substringBefore(':') == ROUTES_CATEGORY_ID }
        .map { entry ->
            val fields = entry.fields.associate { it.label to it.value.trim() }
            BeginnerRouteSummary(
                entryId = entry.id,
                title = entry.title,
                imageAsset = entry.imageAsset,
                recommendation = fields["原资料主观推荐"]?.takeIf(String::isNotEmpty),
                recruitableCount = fields["可挖角人数"]?.toIntOrNull(),
                features = fields["路线特色"]?.takeIf(String::isNotEmpty),
                considerations = fields["游玩注意"]?.takeIf(String::isNotEmpty),
                worldCoverage = fields["可活动范围"]?.takeIf(String::isNotEmpty),
                sourceRowNumber = entry.sourceRowNumber,
            )
        }
        .sortedBy(BeginnerRouteSummary::sourceRowNumber)
        .toList()

    fun projectMapAccess(entries: List<WikiEntry>): List<BeginnerMapAccessRule> = entries
        .asSequence()
        .filter { it.id.substringBefore(':') == MAP_ACCESS_CATEGORY_ID }
        .flatMap { entry ->
            entry.fields.firstOrNull { it.label == "开放条件" }?.value.orEmpty()
                .lineSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .mapIndexed { index, line ->
                    val separator = line.indexOfAny(charArrayOf('：', ':'))
                    val requirement = if (separator > 0) line.substring(0, separator).trim() else "开放区域"
                    val locations = if (separator > 0) line.substring(separator + 1).trim() else line
                    BeginnerMapAccessRule(
                        entryId = "${entry.id}#$index",
                        requirement = requirement,
                        locations = locations,
                        sourceRowNumber = entry.sourceRowNumber,
                    )
                }
        }
        .toList()

    private val QUESTION_PREFIX = Regex("^Q[:：;；]\\s*")
    private val ANSWER_PREFIX = Regex("^A[:：]\\s*")
}
