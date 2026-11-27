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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.fireemblem.companion.content.WikiDatabaseRepository
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class BeginnerGuideScreenTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var repository: WikiDatabaseRepository

    @Before fun setUp() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        root = Files.createTempDirectory(target.cacheDir.toPath(), "beginner-guide-ui-").toFile()
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

    @Test fun offlineQuestionsSearchAndExpandInAdaptiveCards() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.requiredWidth(800.dp).height(760.dp)) {
                    androidx.compose.runtime.CompositionLocalProvider(LocalContext provides context) {
                        BeginnerGuideScreen(repository = repository, onBack = {}, onOpenWorldMap = {})
                    }
                }
            }
        }

        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("beginner-guide-count").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("beginner-guide-count").assertTextContains("路线 5 · 区域 9 · 问答 18")
        scrollTo("beginner-question-9")
        val first = compose.onNodeWithTag("beginner-question-9").fetchSemanticsNode().boundsInRoot
        scrollTo("beginner-question-11")
        val second = compose.onNodeWithTag("beginner-question-11").fetchSemanticsNode().boundsInRoot
        assertEquals("Neighbor questions should share a row on an expanded layout", first.top, second.top, 1f)
        assertTrue("Neighbor questions should use separate columns", first.left != second.left)

        scrollTo("beginner-question-9")
        compose.onNodeWithTag("beginner-question-9").performClick()
        compose.onNodeWithTag("beginner-answer-9", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("beginner-answer-9", useUnmergedTree = true)
            .assertTextContains("三部构成", substring = true)

        scrollTo("beginner-guide-search")
        compose.onNodeWithTag("beginner-guide-search").performClick()
        compose.onNodeWithTag("beginner-guide-search").performTextInput("三部构成")
        scrollTo("beginner-guide-count")
        compose.onNodeWithTag("beginner-guide-count").assertTextContains("问答 1", substring = true)
        scrollTo("beginner-question-9")
        compose.onNodeWithTag("beginner-question-9").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTag("beginner-question-11").fetchSemanticsNodes().size)
    }

    private fun scrollTo(tag: String) {
        compose.onNode(hasScrollToNodeAction() and hasTestTag("beginner-guide-grid"))
            .performScrollToNode(hasTestTag(tag))
    }
}
