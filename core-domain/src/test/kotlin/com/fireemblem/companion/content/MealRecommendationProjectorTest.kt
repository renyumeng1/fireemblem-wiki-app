package com.fireemblem.companion.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MealRecommendationProjectorTest {
    @Test
    fun ranksMealsFromRecordedPreferencesWithoutTreatingMissingRowsAsOrdinary() {
        val characters = listOf("one", "two", "three")
        val meals = listOf(entry("unknown"), entry("ordinary"), entry("soup"), entry("roast"), entry("disliked"))
        val preferences = mapOf(
            "one" to listOf(
                preference("one", "roast", MealPreferenceLevel.VERY_LIKED),
                preference("one", "soup", MealPreferenceLevel.LIKED),
                preference("one", "ordinary", MealPreferenceLevel.ORDINARY),
                preference("one", "disliked", MealPreferenceLevel.DISLIKED),
            ),
            "two" to listOf(
                preference("two", "roast", MealPreferenceLevel.LIKED),
                preference("two", "soup", MealPreferenceLevel.LIKED),
                preference("two", "ordinary", MealPreferenceLevel.ORDINARY),
                preference("two", "disliked", MealPreferenceLevel.DISLIKED),
            ),
            "three" to listOf(
                preference("three", "roast", MealPreferenceLevel.ORDINARY),
                preference("three", "soup", MealPreferenceLevel.DISLIKED),
                preference("three", "ordinary", MealPreferenceLevel.ORDINARY),
                preference("three", "disliked", MealPreferenceLevel.DISLIKED),
            ),
        )

        val recommendations = MealRecommendationProjector.project(characters, meals, preferences)

        assertEquals(listOf("roast", "soup", "ordinary", "unknown", "disliked"), recommendations.map { it.meal.id })
        assertEquals(3, recommendations.first().knownCount)
        assertEquals(2, recommendations.first().likedCount)
        assertEquals(listOf(null, null, null), recommendations[3].preferences)
        assertEquals(0, recommendations[3].knownCount)
        assertEquals(3, recommendations[2].ordinaryCount)
    }

    @Test
    fun requiresThreeDistinctCharacters() {
        assertThrows(IllegalArgumentException::class.java) {
            MealRecommendationProjector.project(listOf("one", "one", "three"), emptyList(), emptyMap())
        }
        assertThrows(IllegalArgumentException::class.java) {
            MealRecommendationProjector.project(listOf("one", "two"), emptyList(), emptyMap())
        }
    }

    private fun entry(id: String) = WikiEntry(id, id, "", null, emptyList(), "test", 1)

    private fun preference(characterId: String, mealId: String, level: MealPreferenceLevel) = MealPreferenceRecord(
        characterId = characterId,
        characterName = characterId,
        characterImageAsset = null,
        mealEntryId = mealId,
        mealName = mealId,
        mealImageAsset = null,
        level = level,
        evidence = mealId,
        sourceSheetId = "test",
        sourceSheetName = "test",
        sourceRowNumber = 1,
        sourceColumn = level.sourceLevel + 2,
    )
}
