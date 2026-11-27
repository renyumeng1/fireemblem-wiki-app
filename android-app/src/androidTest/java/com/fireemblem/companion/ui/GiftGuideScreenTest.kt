package com.fireemblem.companion.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import com.fireemblem.companion.content.WikiDatabaseRepository
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class GiftGuideScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var repository: WikiDatabaseRepository

    @Before fun setUp() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        root = Files.createTempDirectory(target.cacheDir.toPath(), "gift-guide-").toFile()
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

    @Test fun characterAndGiftQueriesNavigateBothWaysWithoutLosingPreferenceContext() {
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme { GiftGuideScreen(repository = repository, onBack = {}) }
            }
        }

        compose.onNodeWithTag("gift-search").performTextReplacement("凯伊")
        compose.waitUntil(15000) { compose.onAllNodesWithText("凯伊").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("凯伊")[1].performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("马匹保养工具").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("非常喜欢")[0].assertIsDisplayed()

        compose.onNodeWithText("马匹保养工具").performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("参考价格").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("返回角色偏好").performClick()
        compose.onNodeWithText("凯伊").assertIsDisplayed()

        compose.onNodeWithText("返回查询结果").performClick()
        compose.onNodeWithTag("gift-mode-item").performClick()
        compose.onNodeWithTag("gift-search").performTextReplacement("马匹保养工具")
        compose.waitUntil(15000) { compose.onAllNodesWithText("马匹保养工具").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("马匹保养工具")[1].performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("凯伊").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("非常喜欢")[0].assertIsDisplayed()
        compose.onNodeWithText("凯伊").assertIsDisplayed()
        compose.onNodeWithTag("gift-guide-back").assertIsDisplayed()
    }
}
