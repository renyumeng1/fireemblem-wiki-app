package com.fireemblem.companion.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.fireemblem.companion.content.WikiDatabaseRepository
import com.fireemblem.companion.content.WikiPageRequest
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class DiningGuideScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var repository: WikiDatabaseRepository

    @Before fun setUp() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        root = Files.createTempDirectory(target.cacheDir.toPath(), "dining-guide-").toFile()
        context = object : ContextWrapper(target) {
            override fun getNoBackupFilesDir(): File = File(root, "no-backup").apply { mkdirs() }
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = target.getSharedPreferences("${root.name}-$name", mode)
        }
        repository = WikiDatabaseRepository.open(context)
    }

    @After fun tearDown() {
        repository.close()
        InstrumentationRegistry.getInstrumentation().targetContext.deleteSharedPreferences("${root.name}-personal-view")
        root.deleteRecursively()
    }

    @Test fun selectsThreeDistinctCharactersAndShowsSourceBackedRecommendations() {
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme { DiningGuideScreen(repository = repository, onBack = {}) }
            }
        }

        compose.onNodeWithTag("dining-mode-trio").performClick()
        selectCharacter("丹提")
        selectCharacter("哥莱亚斯")
        selectCharacter("杰斯塔")

        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("资料记录的三人组合").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("资料记录的三人组合").assertIsDisplayed()
        compose.onNodeWithText("共同偏好料理").assertIsDisplayed()
        compose.onNodeWithText("组合最低等级").assertIsDisplayed()
    }

    @Test fun mealDetailReturnsToItsSearchResults() {
        val meal = repository.page(WikiPageRequest(categoryId = "meals", query = "炭烤鱼"))
            .entries.single { it.title == "炭烤鱼" }
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme { DiningGuideScreen(repository = repository, onBack = {}) }
            }
        }

        compose.onNodeWithTag("dining-mode-meal").performClick()
        replaceQuery("炭烤鱼")
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("dining-entry-${meal.id}").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("dining-entry-${meal.id}").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("角色用餐偏好").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("角色用餐偏好").assertIsDisplayed()
        compose.onNodeWithText("返回菜品查询").performClick()
        compose.onNodeWithTag("dining-entry-${meal.id}").assertIsDisplayed()
    }

    @Test fun characterPreferenceOpensMealAndReturnsToTheSameCharacter() {
        val characters = repository.page(WikiPageRequest(categoryId = "characters", limit = 100)).entries
        val (character, preference) = characters.asSequence()
            .mapNotNull { entry ->
                repository.mealPreferencesForCharacter(entry.id)?.preferences?.firstOrNull()?.let { entry to it }
            }
            .first()
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme { DiningGuideScreen(repository = repository, onBack = {}) }
            }
        }

        replaceQuery(character.title)
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("dining-entry-${character.id}").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("dining-entry-${character.id}").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("dining-preference-${preference.mealEntryId}").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("dining-preference-${preference.mealEntryId}").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("返回角色偏好").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("返回角色偏好").performClick()
        compose.onNodeWithText(character.title).assertIsDisplayed()
        compose.onNodeWithText("返回角色查询").performClick()
        compose.onNodeWithTag("dining-entry-${character.id}").assertIsDisplayed()
    }

    @Test fun narrowWindowKeepsTrioSlotsAndCharacterCardsReachable() {
        val firstCharacter = repository.page(WikiPageRequest(categoryId = "characters", limit = 1)).entries.single()
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme {
                    Box(Modifier.width(360.dp).height(800.dp)) {
                        DiningGuideScreen(repository = repository, onBack = {})
                    }
                }
            }
        }

        compose.onNodeWithTag("dining-mode-trio").performClick()
        compose.onNodeWithTag("dining-slot-0").assertIsDisplayed()
        compose.onNodeWithTag("dining-slot-1").assertIsDisplayed()
        compose.onNodeWithTag("dining-slot-2").assertIsDisplayed()
        replaceQuery(firstCharacter.title)
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("dining-picker-${firstCharacter.id}").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("dining-picker-${firstCharacter.id}").assertIsDisplayed()
    }

    private fun selectCharacter(name: String) {
        val entry = repository.page(WikiPageRequest(categoryId = "characters", query = name))
            .entries.single { it.title == name }
        replaceQuery(name)
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("dining-picker-${entry.id}").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("dining-picker-${entry.id}").performClick()
    }

    private fun replaceQuery(value: String) {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("dining-search", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("dining-search", useUnmergedTree = true).performTextReplacement(value)
    }
}
