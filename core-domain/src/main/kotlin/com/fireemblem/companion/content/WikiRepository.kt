package com.fireemblem.companion.content

data class WikiCategorySummary(
    val id: String,
    val title: String,
    val description: String,
    val entryCount: Int,
    val iconAsset: String? = null,
)

data class WikiCatalog(
    val title: String,
    val sourceRevision: String,
    val contentVersion: String,
    val categories: List<WikiCategorySummary>,
    val updateNotice: String? = null,
)

data class WikiPageRequest(
    val categoryId: String? = null,
    val query: String = "",
    val offset: Int = 0,
    val limit: Int = PAGE_SIZE,
    val filterField: String? = null,
    val filterValue: String? = null,
    val sortField: String? = null,
    val descending: Boolean = false,
    val routeKey: String? = null,
    val fameDescending: Boolean = false,
) {
    init {
        require(offset >= 0) { "Page offset must not be negative" }
        require(limit in 1..MAX_PAGE_SIZE) { "Page size must be between 1 and $MAX_PAGE_SIZE" }
        require((filterField == null) == (filterValue == null)) { "Filter needs both a field and value" }
        require(categoryId != null || filterField == null && sortField == null && routeKey == null) { "Field queries require a category" }
    }

    companion object {
        const val PAGE_SIZE = 40
        const val MAX_PAGE_SIZE = 100
    }
}

data class WikiEntryPage(
    val entries: List<WikiEntry>,
    val totalMatches: Int,
    val offset: Int,
    val limit: Int,
) {
    val hasPrevious: Boolean get() = offset > 0
    val hasNext: Boolean get() = offset + entries.size < totalMatches
}

data class CharacterRouteSummary(
    val characterEntryId: String,
    val characterName: String,
    val routeKey: String,
    val routeLabel: String,
    val fameLevel: Int,
    val joinKind: String,
    val joinChapter: String,
    val joinChapterNum: Int,
    val initialLevel: Int?,
    val supportReq: String,
    val poachCondition: String,
    val contactLocation: String,
    val recommended: Boolean,
)

interface WikiRepository : AutoCloseable {
    val catalog: WikiCatalog
    fun page(request: WikiPageRequest): WikiEntryPage
    fun entry(id: String): WikiEntry?
    fun options(categoryId: String): List<WikiFieldOptions>
    fun links(id: String): List<WikiLink>
    fun characterRoutes(characterId: String): List<CharacterRouteSummary> = emptyList()
    fun characterLearning(characterId: String): WikiCharacterLearningData = WikiCharacterLearningData()
    fun giftPreferencesForCharacter(characterId: String): CharacterGiftGuideData? = null
    fun giftPreferencesForGift(giftEntryId: String): List<GiftPreferenceRecord>? = null
    fun mealPreferencesForCharacter(characterId: String): CharacterMealGuideData? = null
    fun mealPreferencesForMeal(mealEntryId: String): List<MealPreferenceRecord>? = null
    fun diningTrio(characterIds: List<String>): DiningTrioRecord? = null
    fun birdDialogues(request: BirdDialogueRequest): BirdDialoguePage? = null
    fun birdDialogue(entryId: String): BirdDialogueRecord? = null
    fun plantingGuide(): PlantingGuideData? = null
    fun restorationGuide(): RestorationGuideData? = null
    fun theaterGuide(): TheaterGuideData? = null
    fun supportGuide(): SupportGuideData? = null
    fun supportPairs(request: SupportPairRequest): SupportPairPage? = null
    fun supportPair(pairId: String): SupportPairDetail? = null
    fun mechanicsGuide(): MechanicsGuideData? = null
    fun growthSimulatorCatalog(): GrowthSimulatorCatalog? = null
    fun suggest(query: String, categoryId: String? = null, limit: Int = 8): List<FuzzySuggestion> = emptyList()
    fun affiliationPortraits(): Map<String, String> = emptyMap()
}

data class WikiFieldOptions(val key: String, val label: String, val numeric: Boolean, val values: List<String>)
data class WikiLink(
    val targetId: String,
    val title: String,
    val label: String,
    val evidence: String,
    val targetImageAsset: String? = null,
)

data class WikiCharacterLearningData(
    val sourceSheetId: String? = null,
    val sourceSheetName: String? = null,
    val sourceRowNumber: Int? = null,
    val skills: List<WikiCharacterSkill> = emptyList(),
) {
    val hasSourceData: Boolean get() = sourceRowNumber != null
}

data class WikiCharacterSkill(
    val targetId: String?,
    val name: String,
    val groupLabel: String,
    val requirement: String,
    val groupPosition: Int,
    val position: Int,
    val imageAsset: String? = null,
    val range: String? = null,
)

enum class GiftPreferenceLevel(val sourceLevel: Int, val label: String) {
    VERY_LIKED(1, "非常喜欢"),
    LIKED(2, "很喜欢"),
    SOMEWHAT_LIKED(3, "还算喜欢"),
    DISLIKED(4, "不喜欢");

    companion object {
        fun fromSourceLevel(sourceLevel: Int): GiftPreferenceLevel? = entries.firstOrNull { it.sourceLevel == sourceLevel }
    }
}

data class CharacterGiftGuideData(
    val sourceSheetId: String? = null,
    val sourceSheetName: String? = null,
    val sourceRowNumber: Int? = null,
    val categoryPreferenceText: String? = null,
    val preferences: List<GiftPreferenceRecord> = emptyList(),
) {
    val hasSourceData: Boolean get() = sourceRowNumber != null
}

data class GiftPreferenceRecord(
    val characterId: String,
    val characterName: String,
    val characterImageAsset: String?,
    val giftEntryId: String,
    val giftName: String,
    val giftImageAsset: String?,
    val level: GiftPreferenceLevel,
    val evidence: String,
    val sourceSheetId: String,
    val sourceSheetName: String,
    val sourceRowNumber: Int,
    val sourceColumn: Int,
)

enum class MealPreferenceLevel(val sourceLevel: Int, val label: String) {
    VERY_LIKED(1, "特别喜欢"),
    LIKED(2, "喜欢"),
    ORDINARY(3, "普通"),
    DISLIKED(4, "不喜欢");

    companion object {
        fun fromSourceLevel(sourceLevel: Int): MealPreferenceLevel? = entries.firstOrNull { it.sourceLevel == sourceLevel }
    }
}

data class CharacterMealGuideData(
    val sourceSheetId: String? = null,
    val sourceSheetName: String? = null,
    val sourceRowNumber: Int? = null,
    val preferences: List<MealPreferenceRecord> = emptyList(),
) {
    val hasSourceData: Boolean get() = sourceRowNumber != null
}

data class MealPreferenceRecord(
    val characterId: String,
    val characterName: String,
    val characterImageAsset: String?,
    val mealEntryId: String,
    val mealName: String,
    val mealImageAsset: String?,
    val level: MealPreferenceLevel,
    val evidence: String,
    val sourceSheetId: String,
    val sourceSheetName: String,
    val sourceRowNumber: Int,
    val sourceColumn: Int,
)

data class DiningTrioMember(
    val characterId: String,
    val name: String,
    val imageAsset: String?,
)

data class DiningTrioRecord(
    val first: DiningTrioMember,
    val second: DiningTrioMember,
    val third: DiningTrioMember,
    val relationship12: String?,
    val relationship13: String?,
    val relationship23: String?,
    val minimumLevel: String?,
    val evidence: String,
    val sourceSheetId: String,
    val sourceSheetName: String,
    val sourceRowNumber: Int,
)

enum class BirdDialogueReviewFilter(val label: String) {
    ALL("全部"),
    MARKED_CORRECTED("已校订"),
    UNMARKED("待校订"),
}

data class BirdDialogueRequest(
    val query: String = "",
    val characterGroup: String? = null,
    val reviewFilter: BirdDialogueReviewFilter = BirdDialogueReviewFilter.ALL,
    val offset: Int = 0,
    val limit: Int = WikiPageRequest.PAGE_SIZE,
) {
    init {
        require(offset >= 0) { "Page offset must not be negative" }
        require(limit in 1..WikiPageRequest.MAX_PAGE_SIZE) {
            "Page size must be between 1 and ${WikiPageRequest.MAX_PAGE_SIZE}"
        }
        require(characterGroup == null || characterGroup.isNotBlank()) { "Character group must not be blank" }
    }

    companion object {
        const val PAGE_SIZE = WikiPageRequest.PAGE_SIZE
    }
}

data class BirdDialogueRecord(
    val entryId: String,
    val characterEntryId: String?,
    val characterName: String,
    val characterNameEnglish: String?,
    val characterImageAsset: String?,
    val characterGroup: String?,
    val originalPrompt: String?,
    val translatedPrompt: String,
    val originalReaction: String?,
    val recommendedReaction: String,
    val markedCorrected: Boolean,
    val sourceSheetId: String,
    val sourceSheetName: String,
    val sourceRowNumber: Int,
)

data class BirdDialoguePage(
    val entries: List<BirdDialogueRecord>,
    val totalMatches: Int,
    val offset: Int,
    val limit: Int,
    val sourceNote: String,
) {
    val hasPrevious: Boolean get() = offset > 0
    val hasNext: Boolean get() = offset + entries.size < totalMatches
}

enum class PlantingVerificationStatus(val key: String) {
    SOURCE_RECORDED("source_recorded"),
    UNVERIFIED("unverified"),
}

enum class PlantingItemMatchStatus(val key: String) {
    MATCHED("matched"),
    MISSING("missing"),
    AMBIGUOUS("ambiguous"),
}

data class PlantingHarvestParameters(
    val cycleTurns: Int,
    val cycleEvidence: String,
    val yieldCount: Int,
    val yieldCountStatus: PlantingVerificationStatus,
    val yieldEvidence: String,
    val probabilityNote: String,
    val usageNote: String,
    val fertilizerNote: String,
)

data class PlantingHarvestProbability(
    val harvestNumber: Int,
    val probabilityText: String,
    val probabilityPercent: Double,
    val verificationStatus: PlantingVerificationStatus,
)

data class PlantingPlant(
    val id: String,
    val title: String,
    val itemEntryId: String?,
    val imageAsset: String?,
    val matchStatus: PlantingItemMatchStatus,
    val sourceRowNumber: Int,
)

data class PlantingMapMarker(
    val sourceMapId: String,
    val chapterId: String,
    val chapterSourceToken: String,
    val locationName: String,
    val sourceUri: String,
    val sourceRowNumber: Int,
)

data class PlantingLocation(
    val id: String,
    val title: String,
    val sourceRowNumber: Int,
    val plant: PlantingPlant,
    val plantProbabilityText: String,
    val plantProbabilityPercent: Double,
    val plantDescription: String,
    val mapMarkers: List<PlantingMapMarker>,
)

data class PlantingFertilizer(
    val id: String,
    val title: String,
    val itemEntryId: String?,
    val imageAsset: String?,
    val matchStatus: PlantingItemMatchStatus,
    val parameterText: String,
    val parameterPercent: Double,
    val description: String,
    val acquisitionHint: String,
    val sourceRowNumber: Int,
    val mapMarkers: List<PlantingMapMarker>,
)

data class PlantingGuideData(
    val sourceUri: String,
    val sourcePageSha256: String,
    val sourceRevision: String,
    val harvest: PlantingHarvestParameters,
    val harvestProbabilities: List<PlantingHarvestProbability>,
    val plants: List<PlantingPlant>,
    val locations: List<PlantingLocation>,
    val fertilizers: List<PlantingFertilizer>,
)

data class MealRecommendation(
    val meal: WikiEntry,
    val preferences: List<MealPreferenceLevel?>,
) {
    val knownCount: Int get() = preferences.count { it != null }
    val veryLikedCount: Int get() = preferences.count { it == MealPreferenceLevel.VERY_LIKED }
    val likedCount: Int get() = preferences.count { it == MealPreferenceLevel.VERY_LIKED || it == MealPreferenceLevel.LIKED }
    val ordinaryCount: Int get() = preferences.count { it == MealPreferenceLevel.ORDINARY }
    val dislikedCount: Int get() = preferences.count { it == MealPreferenceLevel.DISLIKED }
}
