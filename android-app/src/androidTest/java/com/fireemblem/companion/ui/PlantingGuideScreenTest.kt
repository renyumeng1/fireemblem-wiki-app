package com.fireemblem.companion.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.app.ActivityOptionsCompat
import com.fireemblem.companion.content.PlantingGuideData
import com.fireemblem.companion.content.WikiDatabaseRepository
import com.fireemblem.companion.maps.MapRepository
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class PlantingGuideScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var repository: WikiDatabaseRepository
    private lateinit var guide: PlantingGuideData

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

    @Before fun setUp() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        root = Files.createTempDirectory(target.cacheDir.toPath(), "planting-guide-").toFile()
        context = object : ContextWrapper(target) {
            override fun getNoBackupFilesDir(): File = File(root, "no-backup").apply { mkdirs() }
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) =
                target.getSharedPreferences("${root.name}-$name", mode)
        }
        repository = WikiDatabaseRepository.open(context)
        guide = requireNotNull(repository.plantingGuide())
    }

    @After fun tearDown() {
        repository.close()
        InstrumentationRegistry.getInstrumentation().targetContext.deleteSharedPreferences("${root.name}-personal-view")
        root.deleteRecursively()
    }

    @Test fun filtersByPlantOpensExactMapPointAndRestoresTheGuideAfterReturn() {
        val selectedPlant = guide.plants.first()
        val location = guide.locations.last { it.plant.id == selectedPlant.id }
        val marker = location.mapMarkers.first()
        val mapPoint = MapRepository.open(context).use { mapRepository ->
            mapRepository.points(marker.chapterId).single { it.id == marker.sourceMapId }
        }
        val mapBackCount = AtomicInteger()

        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides context,
                LocalActivityResultRegistryOwner provides registryOwner,
            ) {
                MaterialTheme {
                    PlantingMapHarness(repository, onMapBack = { mapBackCount.incrementAndGet() }) {}
                }
            }
        }

        waitForGuide()
        compose.onNodeWithTag("planting-mode-plants").performClick()
        replaceQuery(selectedPlant.title)
        val plantCardTag = "planting-plant-card-${selectedPlant.id}"
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag(plantCardTag).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("planting-guide-grid").performScrollToNode(hasTestTag(plantCardTag))
        compose.onNodeWithText("查看地点").performClick()

        val locationCardTag = "planting-location-card-${location.id}"
        compose.onNodeWithTag("planting-guide-grid").performScrollToNode(hasTestTag(locationCardTag))
        compose.onNodeWithTag("planting-guide-grid")
            .performScrollToNode(hasTestTag("planting-map-marker-${marker.sourceMapId}"))
        compose.onNodeWithTag("planting-map-marker-${marker.sourceMapId}").performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText("资料来源：fireemblem.top").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(mapPoint.title).assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText("返回攻略").performScrollTo().performClick()

        assertEquals(1, mapBackCount.get())
        compose.onNodeWithTag("planting-route-guide").assertIsDisplayed()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag(locationCardTag).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(locationCardTag).assertIsDisplayed()
        val grid = compose.onNodeWithTag("planting-guide-grid")
        grid.performScrollToNode(hasTestTag("planting-mode-locations"))
        compose.onNodeWithTag("planting-mode-locations").assertIsDisplayed()
        compose.onNodeWithTag("planting-guide-search").assertIsDisplayed()
        compose.onNodeWithTag("planting-guide-search", useUnmergedTree = true)
            .assertTextContains(selectedPlant.title)
        compose.onNodeWithTag("planting-filter-plant-${selectedPlant.id}").assertIsSelected()
    }

    @Test fun showsFertilizerSearchAndCentersTheReadableGrid() {
        val fertilizer = guide.fertilizers.first()
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme { PlantingGuideScreen(repository, onBack = {}, onOpenMap = { _, _ -> }, onOpenWiki = {}) }
            }
        }

        waitForGuide()
        compose.onNodeWithTag("planting-mode-fertilizers").performClick()
        replaceQuery(fertilizer.title)
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("planting-fertilizer-card-${fertilizer.id}").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("planting-fertilizer-card-${fertilizer.id}").performScrollTo().assertIsDisplayed()
        val grid = compose.onNodeWithTag("planting-guide-grid")
        grid.performScrollToNode(hasTestTag("planting-guide-search"))
        compose.onNodeWithTag("planting-guide-search", useUnmergedTree = true).performTextReplacement("")
        grid.performScrollToNode(hasTestTag("planting-fertilizer-card-${guide.fertilizers.last().id}"))

        val rootBounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val gridBounds = grid.fetchSemanticsNode().boundsInRoot
        assertEquals(rootBounds.center.x, gridBounds.center.x, 1f)
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val availableWidthDp = rootBounds.width / density
        assertTrue(gridBounds.width / density <= minOf(availableWidthDp, 1040f) + 2f)
        if (availableWidthDp >= 660f) {
            grid.performScrollToNode(hasTestTag("planting-fertilizer-card-${guide.fertilizers.first().id}"))
            val first = compose.onNodeWithTag("planting-fertilizer-card-${guide.fertilizers.first().id}")
                .fetchSemanticsNode().boundsInRoot
            val second = compose.onNodeWithTag("planting-fertilizer-card-${guide.fertilizers[1].id}")
                .fetchSemanticsNode().boundsInRoot
            assertEquals(first.top, second.top, 1f)
        }
    }

    private fun waitForGuide() {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("planting-mode-locations").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun replaceQuery(value: String) {
        compose.onNodeWithTag("planting-guide-search", useUnmergedTree = true).performTextReplacement(value)
    }
}

@Composable
private fun PlantingMapHarness(
    repository: WikiDatabaseRepository,
    onMapBack: () -> Unit,
    onOpenWiki: (String) -> Unit,
) {
    val stateHolder = rememberSaveableStateHolder()
    var mapOpen by rememberSaveable { mutableStateOf(false) }
    var chapterId by rememberSaveable { mutableStateOf<String?>(null) }
    var pointId by rememberSaveable { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize().testTag(if (mapOpen) "planting-route-map" else "planting-route-guide")) {
        if (mapOpen) {
            stateHolder.SaveableStateProvider("map") {
                WorldMapScreen(
                    onBack = {
                        mapOpen = false
                        onMapBack()
                    },
                    initialChapterId = chapterId,
                    initialPointId = pointId,
                )
            }
        } else {
            stateHolder.SaveableStateProvider("planting-guide") {
                PlantingGuideScreen(
                    repository = repository,
                    onBack = {},
                    onOpenMap = { requestedChapterId, requestedPointId ->
                        chapterId = requestedChapterId
                        pointId = requestedPointId
                        mapOpen = true
                    },
                    onOpenWiki = onOpenWiki,
                )
            }
        }
    }
}
