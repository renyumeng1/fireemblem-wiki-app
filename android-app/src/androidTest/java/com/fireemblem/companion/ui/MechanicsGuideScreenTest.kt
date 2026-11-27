package com.fireemblem.companion.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.fireemblem.companion.content.WikiDatabaseRepository
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class MechanicsGuideScreenTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var repository: WikiDatabaseRepository

    @Before fun setUp() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        root = Files.createTempDirectory(target.cacheDir.toPath(), "mechanics-ui-").toFile()
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

    @Test fun dictionaryLoadsAllGroupsAndSwitchesToGrowthAndCalculatorTabs() {
        val guide = requireNotNull(repository.mechanicsGuide())
        assertEquals(19, guide.groups.size)
        assertEquals(463, guide.entries.size)
        assertEquals(7, guide.growthSections.size)

        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalContext provides context) {
                    MechanicsGuideScreen(repository = repository, onBack = {})
                }
            }
        }

        compose.waitUntil(5000) {
            runCatching { compose.onNodeWithTag("mechanics-search").fetchSemanticsNode() }.isSuccess
        }

        val firstGroup = guide.groups.first()
        compose.onNodeWithTag("mechanics-group:${firstGroup.id}").performClick()
        compose.onNodeWithTag("mechanics-search").performTextInput("连携")
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("连携", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithTag("mechanics-tab:growth-rules").performClick()
        compose.waitUntil(5000) {
            runCatching { compose.onNodeWithTag("growth-section:overview").fetchSemanticsNode() }.isSuccess
        }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("growth-section:fixed"))
        compose.onNodeWithTag("growth-section:fixed").assertIsDisplayed()

        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("mechanics-tab:fixed-calc"))
        compose.onNodeWithTag("mechanics-tab:fixed-calc").performClick()
        compose.waitUntil(5000) {
            runCatching { compose.onNodeWithTag("fixed-step:1").fetchSemanticsNode() }.isSuccess
        }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("fixed-step:1"))
        compose.onNodeWithTag("fixed-step:1").assertIsDisplayed()
    }
}
