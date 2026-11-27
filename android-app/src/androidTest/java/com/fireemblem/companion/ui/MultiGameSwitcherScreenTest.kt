package com.fireemblem.companion.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class MultiGameSwitcherScreenTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences

    private val registryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?,
            ) = Unit
        }
    }

    @Before fun setUp() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        root = Files.createTempDirectory(target.cacheDir.toPath(), "multi-game-ui-").toFile()
        prefs = target.getSharedPreferences("multi-game-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        context = object : ContextWrapper(target) {
            override fun getNoBackupFilesDir(): File = root
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                if (name == "personal-view") prefs else super.getSharedPreferences(name, mode)
        }
    }

    @After fun tearDown() {
        prefs.edit().clear().commit()
        root.deleteRecursively()
    }

    @Test fun switchingBetweenThreeGamesLoadsLiveSqlPacks() {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalContext provides context,
                    LocalActivityResultRegistryOwner provides registryOwner,
                ) {
                    FireEmblemCompanionApp()
                }
            }
        }

        compose.waitUntil(10000) {
            compose.onAllNodesWithTag("game-switcher:engage").fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithTag("game-switcher:engage").performClick()
        compose.waitUntil(10000) {
            compose.onAllNodesWithTag("game-pack-cat:emblems").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("game-pack-cat:emblems").assertIsDisplayed()

        compose.onNodeWithTag("game-switcher:three-houses").performClick()
        compose.waitUntil(10000) {
            compose.onAllNodesWithTag("game-pack-cat:characters").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("game-pack-cat:characters").performClick()
        compose.waitUntil(10000) {
            compose.onAllNodesWithTag("game-pack-filter:青狮子学级").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("game-pack-filter:青狮子学级").performClick()
        compose.waitUntil(10000) {
            compose.onAllNodesWithTag("game-pack-filter-bar:characters").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText("亚修·多蓝").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("亚修·多蓝").performClick()
        compose.waitUntil(10000) {
            compose.onAllNodesWithTag("character-wiki-hub").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag("rated-stat-card:初始属性").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("wiki-hub-tab:skills").performClick()
        compose.waitUntil(10000) {
            compose.onAllNodesWithTag("structured-skill-matrix").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("wiki-hub-tab:social").performClick()
        compose.waitUntil(10000) {
            compose.onAllNodesWithTag("character-support-partners").fetchSemanticsNodes().isNotEmpty()
        }

        // Navigate back to Home and verify switching back to Fortune's Weave
        compose.onNodeWithText("首页").performClick()
        compose.waitUntil(10000) {
            compose.onAllNodesWithTag("game-switcher:fortunes-weave").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("game-switcher:fortunes-weave").performClick()
        compose.waitUntil(10000) {
            compose.onAllNodesWithTag("home-hero-scrim").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
