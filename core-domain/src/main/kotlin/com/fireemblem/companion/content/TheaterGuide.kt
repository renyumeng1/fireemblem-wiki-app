package com.fireemblem.companion.content

enum class TheaterRating { PERFECT, GREAT, GOOD, BAD }

data class TheaterCue(val position: Int, val sourceRow: Int, val cellAddress: String, val rawText: String, val options: List<String>)
data class TheaterReward(val rating: TheaterRating, val sourceRow: Int, val cellAddress: String, val text: String?)
data class TheaterPlay(val id: String, val title: String, val cues: List<TheaterCue>, val rewards: List<TheaterReward>) {
    val hasRecordedCoverage: Boolean get() = cues.map { it.sourceRow }.containsAll(listOf(2, 3, 4)) &&
        rewards.map { it.rating }.toSet() == TheaterRating.entries.toSet() && rewards.all { !it.text.isNullOrBlank() }
}
data class TheaterNote(val sourceRow: Int, val cellAddress: String, val kind: String, val text: String)
data class TheaterActivity(
    val id: String, val label: String, val participants: Int, val effect: String, val note: String,
    val sourceRow: Int, val cellAddress: String, val sourceSheetId: String,
)
data class TheaterCharacterPreference(
    val characterId: String, val playId: String, val label: String, val evidence: String,
    val sourceSheetId: String, val sourceRow: Int,
)
data class TheaterGuideData(
    val plays: List<TheaterPlay>, val notes: List<TheaterNote>, val activities: List<TheaterActivity>,
    val preferences: List<TheaterCharacterPreference>, val preferenceStatus: String,
    val contextNote: String, val relatedSidequestId: String?, val relatedSidequestTitle: String,
    val sourceSheetId: String, val sourceSheetName: String, val sourceRevision: String, val sourceUri: String,
    val activitySheetId: String, val activitySheetName: String,
    val weeklyResetNote: String = "",
)

enum class TheaterCoverage { ALL, RECORDED, GAPS }

object TheaterGuideQuery {
    fun plays(guide: TheaterGuideData, query: String = "", coverage: TheaterCoverage = TheaterCoverage.ALL,
        favorites: Set<String>? = null,
    ): List<TheaterPlay> = guide.plays.filter { play ->
        (favorites == null || play.id in favorites) && when (coverage) {
            TheaterCoverage.ALL -> true
            TheaterCoverage.RECORDED -> play.hasRecordedCoverage
            TheaterCoverage.GAPS -> !play.hasRecordedCoverage
        } && (query.isBlank() || (listOf(play.title) + play.cues.map { it.rawText } + play.rewards.mapNotNull { it.text })
            .any { it.contains(query.trim(), ignoreCase = true) })
    }

    fun preferences(guide: TheaterGuideData, characterId: String? = null, playId: String? = null): List<TheaterCharacterPreference> =
        guide.preferences.filter { (characterId == null || it.characterId == characterId) && (playId == null || it.playId == playId) }
}
