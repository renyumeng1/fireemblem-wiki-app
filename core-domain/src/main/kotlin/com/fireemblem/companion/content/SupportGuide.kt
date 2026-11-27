package com.fireemblem.companion.content

enum class SupportRelationFilter(val label: String) {
    ALL("全部关系"),
    WITH_COMBINATION_BONUS("有组合加成"),
    WITH_DIALOGUES("有支援对话"),
    WITHOUT_DIALOGUES("未收录对话"),
}

data class SupportPairRequest(
    val query: String = "",
    val characterName: String? = null,
    val filter: SupportRelationFilter = SupportRelationFilter.ALL,
    val offset: Int = 0,
    val limit: Int = WikiPageRequest.PAGE_SIZE,
) {
    init {
        require(offset >= 0) { "Page offset must not be negative" }
        require(limit in 1..WikiPageRequest.MAX_PAGE_SIZE) {
            "Page size must be between 1 and ${WikiPageRequest.MAX_PAGE_SIZE}"
        }
        require(characterName == null || characterName.isNotBlank()) { "Character name must not be blank" }
    }

    companion object {
        const val PAGE_SIZE = WikiPageRequest.PAGE_SIZE
    }
}

data class SupportPersonSummary(
    val sourceName: String,
    val displayName: String,
    val wikiCharacterId: String?,
    val imageAsset: String?,
    val matchStatus: SupportWikiMatchStatus,
)

enum class SupportWikiMatchStatus(val key: String, val label: String) {
    MATCHED("matched", "已关联图鉴"),
    MISSING("missing", "图鉴资料未关联"),
    AMBIGUOUS("ambiguous", "图鉴名称有歧义");

    companion object {
        fun fromKey(key: String): SupportWikiMatchStatus? = entries.firstOrNull { it.key == key }
    }
}

data class SupportPairSummary(
    val pairId: String,
    val first: SupportPersonSummary,
    val second: SupportPersonSummary,
    val initialLevel: Int,
    val hasCombinationBonus: Boolean,
    val dialogueCount: Int,
)

data class SupportPairPage(
    val entries: List<SupportPairSummary>,
    val totalMatches: Int,
    val offset: Int,
    val limit: Int,
) {
    val hasPrevious: Boolean get() = offset > 0
    val hasNext: Boolean get() = offset + entries.size < totalMatches
}

enum class SupportDialogueRank(val sourceRank: Int, val label: String, val minimumSupportLevel: Int) {
    C(1, "C", 2),
    B(2, "B", 3),
    A(3, "A", 4),
    S(4, "S", 5);

    companion object {
        fun fromSourceRank(sourceRank: Int): SupportDialogueRank? = entries.firstOrNull { it.sourceRank == sourceRank }
    }
}

data class SupportDialogueCondition(
    val dialogueId: String,
    val rank: SupportDialogueRank,
    val startPart: Int,
    val startChapter: Int,
    val endPart: Int,
    val endChapter: Int,
    val routeId: Int,
    val anyJoined: List<String>,
    val conditions: List<String>,
    val pendingConditions: Int,
    val datePending: Boolean,
)

data class SupportPairDetail(
    val summary: SupportPairSummary,
    val affinityCode: Int,
    val combinationBonus: SupportBonusVector,
    val dialogues: List<SupportDialogueCondition>,
    val sourceUri: String,
    val sourceRevision: String,
)

data class SupportJob(
    val sourceId: String,
    val wikiEntryId: String?,
    val name: String,
    val displayName: String,
    val supportTypeId: String,
    val supportTypeLabel: String,
    val tiers: List<String>,
    val imageAsset: String?,
    val matchStatus: SupportWikiMatchStatus,
)

data class SupportTypeLevelBonus(
    val supportLevel: Int,
    val values: SupportBonusVector,
)

data class SupportTypeBonus(
    val id: String,
    val label: String,
    val levels: List<SupportTypeLevelBonus>,
)

data class SupportGuideData(
    val sourceUri: String,
    val bundleUri: String,
    val sourceRevision: String,
    val statNames: List<String>,
    val supportThresholds: List<Int>,
    val supportTypes: List<SupportTypeBonus>,
    val people: List<SupportPersonSummary>,
    val jobs: List<SupportJob>,
) {
    init {
        require(statNames.size == SupportBonusVector.STAT_COUNT)
        require(supportThresholds.size == SUPPORT_LEVEL_COUNT)
        require(supportThresholds.first() == 0)
        require(supportThresholds.zipWithNext().all { (current, next) -> current < next })
    }

    companion object {
        const val SUPPORT_LEVEL_COUNT = 5
    }
}

data class SupportBonusVector(val values: List<Int>) {
    init {
        require(values.size == STAT_COUNT) { "Support bonuses must contain $STAT_COUNT values" }
        require(values.all { it in -100..100 }) { "Support bonus values must be between -100 and 100" }
    }

    operator fun get(index: Int): Int = values[index]

    companion object {
        const val STAT_COUNT = 9
    }
}

data class SupportBonusProjection(
    val jobBonus: SupportBonusVector,
    val combinationBonus: SupportBonusVector,
    val totalBonus: SupportBonusVector,
)

object SupportBonusProjector {
    fun project(
        combinationBonus: SupportBonusVector,
        job: SupportJob?,
        supportLevel: Int,
        supportTypes: List<SupportTypeBonus>,
    ): SupportBonusProjection? {
        require(supportLevel in 1..SupportGuideData.SUPPORT_LEVEL_COUNT) {
            "Support level must be between 1 and ${SupportGuideData.SUPPORT_LEVEL_COUNT}"
        }
        job ?: return null
        val jobBonus = supportTypes.firstOrNull { it.id == job.supportTypeId }
            ?.levels?.firstOrNull { it.supportLevel == supportLevel }?.values ?: return null
        val total = combinationBonus.values.zip(jobBonus.values).map { (combination, profession) ->
            (combination + profession).coerceIn(-100, 100)
        }
        return SupportBonusProjection(jobBonus, combinationBonus, SupportBonusVector(total))
    }
}
