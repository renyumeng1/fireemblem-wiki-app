package com.fireemblem.companion.content

object MealRecommendationProjector {
    fun project(
        characterIds: List<String>,
        meals: List<WikiEntry>,
        preferencesByCharacter: Map<String, List<MealPreferenceRecord>>,
    ): List<MealRecommendation> {
        require(characterIds.size == 3 && characterIds.toSet().size == 3) {
            "Meal recommendations require three different characters"
        }
        val levelsByCharacter = characterIds.associateWith { characterId ->
            preferencesByCharacter[characterId].orEmpty().associate { it.mealEntryId to it.level }
        }
        return meals.map { meal ->
            MealRecommendation(meal, characterIds.map { id -> levelsByCharacter[id]?.get(meal.id) })
        }.sortedWith(
            compareByDescending<MealRecommendation> { it.likedCount }
                .thenByDescending { it.veryLikedCount }
                .thenByDescending { it.ordinaryCount }
                .thenBy { it.dislikedCount }
                .thenByDescending { it.knownCount }
                .thenBy { it.meal.title.lowercase() }
                .thenBy { it.meal.id },
        )
    }
}
