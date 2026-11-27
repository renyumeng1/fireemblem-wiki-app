package com.fireemblem.companion.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.fireemblem.companion.content.WikiDatabaseRepository
import com.fireemblem.companion.content.WikiPageRequest
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class EndingsGuideScreenTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var repository: WikiDatabaseRepository

    @org.junit.Before fun setUp() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        root = Files.createTempDirectory(target.cacheDir.toPath(), "endings-guide-ui-").toFile()
        context = object : ContextWrapper(target) {
            override fun getNoBackupFilesDir(): File = root
            override fun getApplicationContext(): Context = this
        }
        repository = WikiDatabaseRepository.open(context)
    }

    @After fun tearDown() {
        compose.runOnIdle { repository.close() }
        root.deleteRecursively()
    }

    @Test fun bodySearchKeepsSpoilersHiddenUntilTheReaderOpensAnEnding() {
        val matches = repository.page(WikiPageRequest(categoryId = "endings", query = "2万大军", limit = 100))
        assertEquals(1, matches.totalMatches)
        val endingId = matches.entries.single().id

        compose.setContent {
            MaterialTheme {
                Box(Modifier.requiredWidth(390.dp).height(800.dp)) {
                    androidx.compose.runtime.CompositionLocalProvider(LocalContext provides context) {
                        EndingsGuideScreen(repository = repository, onBack = {}, onOpenWiki = {})
                    }
                }
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("ending-result-count").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("ending-kind-pair").assertTextContains("双人 82")
        compose.onNodeWithTag("ending-kind-single").assertTextContains("单人 124")
        compose.onNodeWithTag("ending-search").performClick()
        compose.onNodeWithTag("ending-search").performTextInput("2万大军")
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("ending-result-count").fetchSemanticsNodes()
                .any { it.config.toString().contains("1/1") }
        }
        compose.onNodeWithText("凯伊很不适应自己被当作英雄对待", substring = true).assertDoesNotExist()
        compose.onNode(hasScrollToNodeAction() and hasTestTag("endings-guide-grid"))
            .performScrollToNode(hasTestTag("ending-read-$endingId"))
        compose.onNodeWithTag("ending-read-$endingId").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("ending-story-paragraph-0").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("ending-story-paragraph-0").assertIsDisplayed()
        compose.onNodeWithText("凯伊很不适应自己被当作英雄对待", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("ending-close-dialog").performClick()
        compose.onNodeWithText("凯伊很不适应自己被当作英雄对待", substring = true).assertDoesNotExist()
    }
}
