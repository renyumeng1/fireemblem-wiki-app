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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import com.fireemblem.companion.maps.MapChapter
import com.fireemblem.companion.maps.MapPoint
import com.fireemblem.companion.maps.MapRepository
import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class WorldMapScreenTest {
    @get:Rule val compose = createComposeRule()

    private val registryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <Input, Output> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<Input, Output>,
                input: Input,
                options: ActivityOptionsCompat?,
            ) = Unit
        }
    }

    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var profilePreferences: SharedPreferences
    private lateinit var chapters: List<MapChapter>
    private lateinit var kaiPoints: List<MapPoint>
    private val compositionRevision = mutableIntStateOf(0)

    @Before fun setUp() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        root = Files.createTempDirectory(target.cacheDir.toPath(), "world-map-ui-").toFile()
        profilePreferences = target.getSharedPreferences("world-map-ui-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        context = object : ContextWrapper(target) {
            override fun getNoBackupFilesDir(): File = root
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                if (name == "personal-view") profilePreferences else super.getSharedPreferences(name, mode)
        }
        profilePreferences.edit().putString("profile", "profile-a").commit()
        MapRepository.open(context).use { repository ->
            chapters = repository.chapters
            kaiPoints = repository.points("kai")
        }
        assertEquals(setOf("kai", "dietrich", "theodora", "leda", "salvation"), chapters.map(MapChapter::id).toSet())
        assertTrue(kaiPoints.isNotEmpty())
    }

    @After fun tearDown() {
        profilePreferences.edit().clear().commit()
        root.deleteRecursively()
    }

    @Test fun selectsAllOfflineChaptersAndShowsAccessiblePointDetails() {
        showScreen()
        waitForMap()

        chapters.forEach { chapter ->
            compose.onNodeWithText(chapter.title).performScrollTo().performClick()
            waitForMap()
            compose.onNodeWithText(chapter.title).assertIsSelected()
        }

        val kai = chapters.single { it.id == "kai" }
        compose.onNodeWithText(kai.title).performScrollTo().performClick()
        waitForMap()
        compose.waitUntil(30000) {
            compose.onAllNodesWithContentDescription(MAP_CONTENT_DESCRIPTION).fetchSemanticsNodes().isNotEmpty()
        }

        assertTrue("The loaded map should expose its complete game-road network",
            compose.onAllNodesWithContentDescription(MAP_CONTENT_DESCRIPTION).fetchSemanticsNodes().isNotEmpty())

        val firstPoint = kaiPoints.first()
        openPoint(firstPoint)
        compose.onNodeWithText("标记已探索").assertIsDisplayed()
        compose.onNodeWithText(firstPoint.description.ifBlank { "暂无已核实的地点说明。" }).fetchSemanticsNode()
        compose.onNodeWithText("资料来源：fireemblem.top").fetchSemanticsNode()
    }

    @Test fun explorationMarkersRemainIsolatedBetweenProfiles() {
        showScreen()
        waitForMap()
        val firstPoint = kaiPoints.first()

        openPoint(firstPoint)
        compose.waitUntil(15000) {
            compose.onAllNodesWithText("标记已探索").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("标记已探索").performClick()
        compose.waitUntil(15000) {
            compose.onAllNodesWithText("取消探索标记").fetchSemanticsNodes().isNotEmpty()
        }

        switchProfile("profile-b")
        compose.onNodeWithText("关闭").performClick()
        openPoint(firstPoint)
        compose.waitUntil(15000) {
            compose.onAllNodesWithText("标记已探索").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("标记已探索").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("取消探索标记").fetchSemanticsNodes().isEmpty())
    }

    @Test fun opensTheRequestedChapterAndExactMapPoint() {
        val chapterId = "salvation"
        val point = MapRepository.open(context).use { it.points(chapterId).first() }
        val chapter = chapters.single { it.id == chapterId }
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides context,
                LocalActivityResultRegistryOwner provides registryOwner,
            ) {
                MaterialTheme {
                    WorldMapScreen(onBack = {}, initialChapterId = chapterId, initialPointId = point.id)
                }
            }
        }

        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("资料来源：fireemblem.top").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(chapter.title).assertIsSelected()
        compose.onNodeWithText(point.title).assertIsDisplayed()
        compose.onNodeWithText(point.description.ifBlank { "暂无已核实的地点说明。" }).assertIsDisplayed()
    }

    private fun showScreen() {
        compose.setContent {
            val revision = compositionRevision.intValue
            CompositionLocalProvider(
                LocalContext provides context,
                LocalActivityResultRegistryOwner provides registryOwner,
            ) {
                if (revision >= 0) MaterialTheme { WorldMapScreen(onBack = {}) }
            }
        }
    }

    private fun switchProfile(profile: String) {
        assertTrue(profilePreferences.edit().putString("profile", profile).commit())
        compose.runOnIdle { compositionRevision.intValue++ }
    }

    private fun waitForMap() {
        compose.waitUntil(15000) {
            compose.onAllNodesWithText("全部地点").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun openPoint(point: MapPoint) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(point.title))
        compose.onNodeWithText(point.title).performClick()
    }

    private companion object {
        const val MAP_CONTENT_DESCRIPTION = "世界地图，已显示游戏道路，可拖动缩放，地点也可从下方列表选择"
    }
}
