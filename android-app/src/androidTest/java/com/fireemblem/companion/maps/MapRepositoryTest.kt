package com.fireemblem.companion.maps

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fireemblem.companion.personal.PersonalStore
import com.fireemblem.companion.ui.WorldMapScreen
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MapRepositoryTest {
    @get:Rule val compose = createComposeRule()
    private var uiRoot: File? = null
    private var uiPreferencesName: String? = null

    @After fun cleanUiStorage() {
        uiRoot?.deleteRecursively()
        uiPreferencesName?.let { InstrumentationRegistry.getInstrumentation().targetContext.deleteSharedPreferences(it) }
    }

    @Test
    fun installsAllOfflineChaptersAndKeepsCoordinatesWithinTheMap() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        MapRepository.open(context).use { repository ->
            assertEquals(setOf("kai", "dietrich", "theodora", "leda", "salvation"), repository.chapters.map { it.id }.toSet())
            assertEquals(1242, repository.chapters.sumOf { repository.points(it.id).size })
            assertEquals(1, repository.chapters.sumOf { chapter -> repository.points(chapter.id).count { it.node == null } })
            assertTrue(repository.width > 3000.0)
            assertTrue(repository.height > 2000.0)
            repository.chapters.forEach { chapter ->
                repository.points(chapter.id).forEach { point ->
                    assertTrue(point.position.x in 0.0..repository.width)
                    assertTrue(point.position.y in 0.0..repository.height)
                }
                val graph = repository.graph(chapter.id)
                assertTrue(graph.nodes.isNotEmpty())
                assertTrue(graph.roads.isNotEmpty())
                assertTrue(chapter.startNode in graph.nodes.indices)
            }
        }
    }

    @Test
    fun searchesMaterialAssociationsAndFiltersPointTypesOffline() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        MapRepository.open(context).use { repository ->
            val materialMatches = repository.points("kai", query = "锻造石")
            val typeMatches = repository.points("kai", type = "拠点")

            assertTrue(materialMatches.isNotEmpty())
            assertTrue(materialMatches.all { point -> point.description.contains("锻造石") || point.quests.contains("锻造石") || point.nativeName.isNotBlank() })
            assertTrue(typeMatches.isNotEmpty())
            assertTrue(typeMatches.all { it.type == "拠点" })
            assertNotNull(repository.points("kai", query = "not-a-place"))
            assertTrue(repository.points("kai", query = "not-a-place").isEmpty())
        }
    }

    @Test
    fun terrainImageMatchesTheCoordinatesInTheInstalledDatabase() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val expected = MapRepository.open(context).use { it.terrainSha256 }
        val bytes = context.assets.open("world-map/terrain.webp").use { it.readBytes() }
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        assertEquals(expected, actual)
        val assets = context.assets.list("world-map").orEmpty().toSet()
        assertTrue(assets.containsAll(setOf("maps.db", "maps.sha256", "terrain.webp")))
        assertTrue(assets.none { it.endsWith(".json", ignoreCase = true) })
    }

    @Test
    fun mapControlsAndDirectRoutePickersRemainTouchableOnNarrowWindows() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val root = Files.createTempDirectory(target.cacheDir.toPath(), "map-route-ui-").toFile()
        val preferencesName = "map-route-ui-${UUID.randomUUID()}"
        uiRoot = root; uiPreferencesName = preferencesName
        val context = object : ContextWrapper(target) {
            override fun getNoBackupFilesDir() = root
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                if (name == "personal-view") target.getSharedPreferences(preferencesName, mode) else super.getSharedPreferences(name, mode)
        }
        val point = MapRepository.open(context).use { it.points("kai").first { point -> point.node != null } }
        val width = mutableStateOf(360.dp)
        var availableWidth = 0.dp
        val registryOwner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = object : ActivityResultRegistry() {
                override fun <Input, Output> onLaunch(requestCode: Int, contract: ActivityResultContract<Input, Output>,
                    input: Input, options: ActivityOptionsCompat?) = Unit
            }
        }
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context, LocalActivityResultRegistryOwner provides registryOwner) {
                MaterialTheme {
                    BoxWithConstraints(Modifier.width(width.value)) {
                        availableWidth = maxWidth
                        WorldMapScreen(onBack = {})
                    }
                }
            }
        }
        compose.waitUntil(15000) { compose.onAllNodesWithText("选择起点").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(15000) { compose.onAllNodesWithContentDescription("放大地图").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("地点搜索").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("地点列表", substring = true).assertIsDisplayed()
        compose.onNodeWithText("搜索地点、任务或物品").performScrollTo().performTextReplacement("测试地点")
        compose.onNodeWithText("地图与路线").performScrollTo().performClick()
        compose.onNodeWithText("路线规划").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("清除搜索").performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("选择起点").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("放大地图").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithContentDescription("重置地图位置和缩放").assertHeightIsAtLeast(48.dp).performClick()

        listOf("选择起点", "选择终点").forEach { label ->
            compose.onNodeWithText(label).performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
            compose.onNodeWithText("搜索可通行地点").performTextReplacement(point.title)
            compose.onNode(hasText(point.title) and hasAnyAncestor(isDialog()) and !hasSetTextAction()).performClick()
        }
        compose.waitUntil(15000) { compose.onAllNodesWithText("路线已计算").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("保存路线").performScrollTo().performClick()
        compose.waitUntil(15000) { PersonalStore(context).snapshot().heads().values.any { it.singleOrNull()?.kind == "map-route" } }
        compose.onNodeWithText("查看已保存路线（1）").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { width.value = 1000.dp }
        compose.waitForIdle()
        if (availableWidth >= 840.dp) {
            compose.onNodeWithText("路线规划").performScrollTo()
            val mapDescription = "世界地图，已显示游戏道路，可拖动缩放，地点也可从下方列表选择"
            compose.waitUntil(30000) {
                compose.onAllNodesWithContentDescription(mapDescription).fetchSemanticsNodes().isNotEmpty()
            }
            val map = compose.onNodeWithContentDescription(mapDescription).fetchSemanticsNode().boundsInRoot
            val planner = compose.onNodeWithText("路线规划").fetchSemanticsNode().boundsInRoot
            assertTrue("Expanded route controls should be beside the map: map=$map planner=$planner", planner.left > map.right)
            compose.onNodeWithText("路线已计算").fetchSemanticsNode()
        }
        compose.onNodeWithText("清除起终点").performScrollTo().performClick()
        compose.onNodeWithText("选择起点").performScrollTo().assertIsDisplayed()
    }
}
