package com.fireemblem.companion.ui

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import com.fireemblem.companion.content.CompanionGame
import com.fireemblem.companion.content.GameDataV1Repository
import com.fireemblem.companion.content.WikiDatabaseRepository
import com.fireemblem.companion.ui.theme.FireEmblemTheme
import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FuzzySearchAndVisualFiltersTest {
    @get:Rule
    val rule = createComposeRule()

    private fun captureIfEnabled(name: String) {
        val args = InstrumentationRegistry.getArguments()
        if (args.getString("capture_wave2_shots") != "true") return
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(target.getExternalFilesDir(null), "wave2_accept").apply { mkdirs() }
        rule.waitForIdle()
        Thread.sleep(450)
        val bmp = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        if (bmp != null) {
            File(dir, "$name.png").outputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            bmp.recycle()
        }
    }

    @Test
    fun fortunesWeavePortraitFiltersAndFuzzyDropdownWorkEndToEnd() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        var openedEntryId: String? = null
        WikiDatabaseRepository.open(target).use { repo ->
            // Verify repository fuzzy suggest + affiliationPortraits directly
            val subseqSuggestions = repo.suggest("皮鲁", limit = 6)
            assertTrue("Subsequence '皮鲁' must match 皮特鲁", subseqSuggestions.any { it.title == "皮特鲁" })
            val typoSuggestions = repo.suggest("皮特路", limit = 6)
            assertTrue("Typo '皮特路' must match 皮特鲁", typoSuggestions.any { it.title == "皮特鲁" })
            val portraits = repo.affiliationPortraits()
            assertTrue("Expected non-empty affiliation portrait map", portraits.isNotEmpty())

            rule.setContent {
                var query by remember { mutableStateOf("") }
                var selectedCat by remember { mutableStateOf<String?>("characters") }
                var selectedEntry by remember { mutableStateOf<String?>(null) }
                FireEmblemTheme {
                    WikiCatalogPane(
                        repository = repo,
                        query = query,
                        selectedCategoryId = selectedCat,
                        selectedEntryId = selectedEntry,
                        onQueryChange = { query = it },
                        onOpenCategory = { selectedCat = it; selectedEntry = null },
                        onOpenEntry = { id ->
                            openedEntryId = id
                            selectedEntry = id
                        },
                        onBackToCategories = { selectedCat = null; selectedEntry = null },
                        onCloseEntry = { selectedEntry = null },
                        onCompare = {},
                    )
                }
            }

            // 1. Visual portrait route & faction filter bars render cleanly
            rule.waitUntil(10_000) {
                rule.onAllNodesWithTag("fw-route-filter-bar").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithTag("wiki-route-chip-kai").performScrollTo().assertIsDisplayed()
            rule.onNodeWithTag("wiki-route-chip-dit").performScrollTo().assertIsDisplayed()
            rule.onNodeWithTag("wiki-route-chip-seo").performScrollTo().assertIsDisplayed()
            rule.onNodeWithTag("wiki-route-chip-leda").performScrollTo().assertIsDisplayed()
            rule.onNodeWithTag("wiki-route-chip-kai").performScrollTo()
            rule.onNodeWithTag("fw-faction-filter-bar").assertIsDisplayed()
            captureIfEnabled("01_fw_character_portrait_filters")

            // Click 凯伊篇 route card -> fame sort chips appear
            rule.onNodeWithTag("wiki-route-chip-kai").performClick()
            rule.waitUntil(5_000) {
                rule.onAllNodesWithTag("wiki-route-fame-asc").fetchSemanticsNodes().isNotEmpty()
            }
            captureIfEnabled("02_fw_kai_route_selected")

            // 2. Type fuzzy subsequence "皮鲁" -> live dropdown offers "皮特鲁" -> click opens entry directly!
            rule.onNodeWithTag("wiki-atlas-search").performTextReplacement("皮鲁")
            rule.waitUntil(10_000) {
                rule.onAllNodesWithTag("fuzzy-search-dropdown").fetchSemanticsNodes().isNotEmpty() &&
                    rule.onAllNodesWithText("皮特鲁").fetchSemanticsNodes().isNotEmpty()
            }
            captureIfEnabled("03_fw_fuzzy_dropdown")
            val pitruId = subseqSuggestions.first { it.title == "皮特鲁" }.id
            rule.onNodeWithTag("fuzzy-suggestion:$pitruId").performClick()
            rule.waitUntil(5_000) { openedEntryId == pitruId }
            assertNotNull(openedEntryId)
        }
    }

    @Test
    fun threeHousesGamePackFuzzyDropdownMatchesSubsequenceAndOpensEntry() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        var openedEntryId: String? = null
        GameDataV1Repository.open(target, CompanionGame.THREE_HOUSES.id).use { repo ->
            val suggestions = repo.suggest("天马骑", limit = 6)
            assertTrue("Subsequence '天马骑' must match 圣天马骑士", suggestions.any { it.title.contains("圣天马骑士") })

            rule.setContent {
                var query by remember { mutableStateOf("") }
                var selectedCat by remember { mutableStateOf<String?>("classes") }
                var selectedEntry by remember { mutableStateOf<String?>(null) }
                FireEmblemTheme {
                    GamePackCatalogScreen(
                        game = CompanionGame.THREE_HOUSES,
                        repository = repo,
                        query = query,
                        selectedCategoryId = selectedCat,
                        selectedEntryId = selectedEntry,
                        onQueryChange = { query = it },
                        onOpenCategory = { selectedCat = it; selectedEntry = null },
                        onOpenEntry = { id ->
                            openedEntryId = id
                            selectedEntry = id
                        },
                        onBackToCategories = { selectedCat = null; selectedEntry = null },
                        onCloseEntry = { selectedEntry = null },
                    )
                }
            }

            rule.waitUntil(10_000) {
                rule.onAllNodesWithTag("game-pack-catalog-search").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithTag("game-pack-catalog-search").performTextReplacement("天马骑")
            rule.waitUntil(10_000) {
                rule.onAllNodesWithTag("fuzzy-search-dropdown").fetchSemanticsNodes().isNotEmpty() &&
                    rule.onAllNodesWithText("圣天马骑士").fetchSemanticsNodes().isNotEmpty()
            }
            captureIfEnabled("04_three_houses_fuzzy_dropdown")

            val pegId = suggestions.first { it.title.contains("圣天马骑士") }.id
            rule.onNodeWithTag("fuzzy-suggestion:$pegId").performClick()
            rule.waitUntil(5_000) { openedEntryId == pegId }
            assertNotNull(openedEntryId)
        }
    }
}
