package com.fireemblem.companion.content

data class GamePackCategory(
    val id: String,
    val title: String,
    val description: String,
    val position: Int,
    val coverageStatus: String,
    val sourceCount: Int,
    val recordCount: Int,
    val iconAsset: String? = null,
)

data class GamePackSourceInfo(
    val sourceId: String,
    val sourceType: String,
    val title: String,
    val url: String?,
    val language: String?,
    val rightsStatus: String,
    val notes: String,
)

data class GamePackCatalog(
    val gameId: String,
    val contentVersion: String,
    val categories: List<GamePackCategory>,
    val sources: List<GamePackSourceInfo>,
)

data class GamePackEntrySummary(
    val id: String,
    val categoryId: String,
    val title: String,
    val titleJa: String?,
    val titleEn: String?,
    val subtitle: String,
    val verificationStatus: String,
    val imageAsset: String?,
)

data class GamePackFilterOption(
    val value: String,
    val label: String,
    val count: Int,
)

data class GamePackPageRequest(
    val categoryId: String? = null,
    val query: String = "",
    val filterGroup: String? = null,
    val offset: Int = 0,
    val limit: Int = PAGE_SIZE,
) {
    init {
        require(offset >= 0) { "Page offset must not be negative" }
        require(limit in 1..MAX_PAGE_SIZE) { "Page size invalid" }
    }

    companion object {
        const val PAGE_SIZE = 40
        const val MAX_PAGE_SIZE = 100
    }
}

data class GamePackPage(
    val entries: List<GamePackEntrySummary>,
    val totalMatches: Int,
    val offset: Int,
    val limit: Int,
) {
    val hasPrevious: Boolean get() = offset > 0
    val hasNext: Boolean get() = offset + entries.size < totalMatches
}

data class GamePackDetailField(
    val label: String,
    val value: String,
)

data class GamePackDetailSection(
    val title: String,
    val fields: List<GamePackDetailField>,
)

data class GamePackRelation(
    val targetId: String,
    val targetTitle: String,
    val targetCategoryId: String,
    val relationType: String,
    val evidence: String,
    val verificationStatus: String,
    val targetImageAsset: String? = null,
)

data class GamePackCitation(
    val sourceTitle: String,
    val url: String?,
    val locator: String,
    val rightsStatus: String,
)

data class GamePackImage(
    val assetPath: String,
    val roleLabel: String,
)

enum class SkillProficiency(val label: String) {
    STRENGTH("擅长"),
    WEAKNESS("弱项"),
    BUDDING("才能绽放"),
    NEUTRAL(""),
}

data class SkillMatrixItem(
    val name: String,
    val requirement: String,
    val targetEntryId: String? = null,
    val isUniversal: Boolean = false,
    val kind: String = "",
    val imageAsset: String? = null,
    val range: String? = null,
    val itemTag: String? = null,
    val rankTag: String? = null,
)

data class SkillMatrixTrack(
    val trackKey: String,
    val trackLabel: String,
    val initialRank: String = "—",
    val proficiency: SkillProficiency = SkillProficiency.NEUTRAL,
    val buddingSkillName: String? = null,
    val items: List<SkillMatrixItem> = emptyList(),
)

data class GamePackLinkedItem(
    val name: String,
    val targetEntryId: String? = null,
    val imageAsset: String? = null,
)

data class GamePackSocialPartner(
    val name: String,
    val targetEntryId: String?,
    val imageAsset: String? = null,
    val note: String = "",
)

data class GamePackPreferenceItem(
    val name: String,
    val targetEntryId: String? = null,
    val liked: Boolean = true,
)

data class GamePackCharacterWikiData(
    val affiliation: String? = null,
    val startingClass: String? = null,
    val crest: String? = null,
    val personalSkillName: String? = null,
    val personalSkillDescription: String? = null,
    val personalSkillEntryId: String? = null,
    val personalSkillIconAsset: String? = null,
    val startingWeapons: List<String> = emptyList(),
    val startingWeaponItems: List<GamePackLinkedItem> = emptyList(),
    val joinCondition: String? = null,
    val routeAvailability: String? = null,
    val supportBonusNote: String? = null,
    val skillTracks: List<SkillMatrixTrack> = emptyList(),
    val supportPartners: List<GamePackSocialPartner> = emptyList(),
    val likedGifts: List<GamePackPreferenceItem> = emptyList(),
    val dislikedGifts: List<GamePackPreferenceItem> = emptyList(),
    val likedMeals: List<GamePackPreferenceItem> = emptyList(),
    val dislikedMeals: List<GamePackPreferenceItem> = emptyList(),
)

data class SupportDialogueLine(
    val speaker: String,
    val text: String,
)

data class SupportRankConversation(
    val rank: String,
    val lines: List<SupportDialogueLine>,
)

data class GamePackEntryDetail(
    val summary: GamePackEntrySummary,
    val sections: List<GamePackDetailSection>,
    val relations: List<GamePackRelation>,
    val citations: List<GamePackCitation>,
    val isCommunityOpinion: Boolean = false,
    val images: List<GamePackImage> = emptyList(),
    val characterWiki: GamePackCharacterWikiData? = null,
    val supportConversations: List<SupportRankConversation> = emptyList(),
)

interface GamePackRepository : AutoCloseable {
    val catalog: GamePackCatalog
    val statBenchmarks: GameStatBenchmarks
    fun filterOptions(categoryId: String): List<GamePackFilterOption> = emptyList()
    fun page(request: GamePackPageRequest): GamePackPage
    fun entry(id: String): GamePackEntryDetail?
    fun highlightEntries(categoryId: String, limit: Int = 8): List<GamePackEntrySummary>
    fun suggest(query: String, categoryId: String? = null, limit: Int = 8): List<FuzzySuggestion> = emptyList()
}
