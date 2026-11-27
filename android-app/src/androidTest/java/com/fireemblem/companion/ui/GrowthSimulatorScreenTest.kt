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
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.core.app.ActivityOptionsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.fireemblem.companion.content.WikiDatabaseRepository
import com.fireemblem.companion.content.WikiRepository
import com.fireemblem.companion.engine.GrowthSimulatorPlan
import com.fireemblem.companion.personal.PersonalStore
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class GrowthSimulatorScreenTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var repository: WikiDatabaseRepository

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
        root = Files.createTempDirectory(target.cacheDir.toPath(), "growth-sim-ui-").toFile()
        prefs = target.getSharedPreferences("growth-sim-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        context = object : ContextWrapper(target) {
            override fun getNoBackupFilesDir(): File = root
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                if (name == "personal-view") prefs else super.getSharedPreferences(name, mode)
        }
        prefs.edit().putString("profile", "profile-sim").commit()
        repository = WikiDatabaseRepository.open(context)
    }

    @After fun tearDown() {
        compose.runOnIdle { repository.close() }
        prefs.edit().clear().commit()
        root.deleteRecursively()
    }

    @Test fun multiStageSimulationSwitchesModesComparesAndPersistsToStore() {
        val catalog = requireNotNull(repository.growthSimulatorCatalog())
        assertEquals(64, catalog.characters.size)
        assertEquals(85, catalog.classes.size)
        assertEquals(135, catalog.mounts.size)

        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalContext provides context,
                    LocalActivityResultRegistryOwner provides registryOwner,
                ) {
                    GrowthSimulatorScreen(repository = repository, onBack = {})
                }
            }
        }

        compose.waitUntil(5000) {
            runCatching { compose.onNodeWithTag("sim-mode:random").fetchSemanticsNode() }.isSuccess
        }

        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("sim-mode:random"))
        compose.onNodeWithTag("sim-mode:random").performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("sim-result-card"))
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("随机期望结果", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("sim-mode:linear"))
        compose.onNodeWithTag("sim-mode:linear").performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("sim-result-card"))
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("线性期望结果", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("sim-mode:fixed"))
        compose.onNodeWithTag("sim-mode:fixed").performClick()

        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("sim-compare-toggle"))
        compose.onNodeWithTag("sim-compare-toggle").performClick()
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("与基准方案", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithTag("sim-save-plan").performClick()
        compose.waitUntil(5000) {
            PersonalStore(context).snapshot().heads().values.any { group ->
                group.any { it.kind == GrowthSimulatorPlan.KIND && !it.deleted }
            }
        }
        assertTrue(
            PersonalStore(context).snapshot().heads().values.any { group ->
                group.any { it.kind == GrowthSimulatorPlan.KIND }
            },
        )
    }

    @Test fun asyncCatalogLoadTransitionDoesNotCrashLazyGridSnapshot() {
        val gate = CountDownLatch(1)
        val delayedRepo = object : WikiRepository by repository {
            override fun growthSimulatorCatalog() = run {
                gate.await(5, TimeUnit.SECONDS)
                repository.growthSimulatorCatalog()
            }
        }
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalContext provides context,
                    LocalActivityResultRegistryOwner provides registryOwner,
                ) {
                    GrowthSimulatorScreen(repository = delayedRepo, onBack = {})
                }
            }
        }
        compose.waitForIdle()
        gate.countDown()
        compose.waitUntil(5000) {
            runCatching { compose.onNodeWithTag("sim-mode:fixed").fetchSemanticsNode() }.isSuccess
        }
        compose.onNodeWithTag("sim-mode:fixed").assertIsDisplayed()
    }
}
