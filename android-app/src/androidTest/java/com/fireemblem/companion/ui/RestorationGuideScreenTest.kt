package com.fireemblem.companion.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.fireemblem.companion.content.RestorationGuideData
import com.fireemblem.companion.content.WikiDatabaseRepository
import com.fireemblem.companion.personal.PersonalSnapshot
import com.fireemblem.companion.personal.PersonalStore
import com.fireemblem.companion.personal.RestorationProgress
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class RestorationGuideScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var repository: WikiDatabaseRepository
    private lateinit var guide: RestorationGuideData

    @Before fun setUp() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        root = Files.createTempDirectory(target.cacheDir.toPath(), "restoration-test-").toFile()
        context = object : ContextWrapper(target) {
            override fun getNoBackupFilesDir(): File = File(root, "no-backup").apply { mkdirs() }
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = target.getSharedPreferences("${root.name}-$name", mode)
        }
        repository = WikiDatabaseRepository.open(context)
        guide = requireNotNull(repository.restorationGuide())
    }

    @After fun tearDown() {
        repository.close()
        InstrumentationRegistry.getInstrumentation().targetContext.deleteSharedPreferences("${root.name}-personal-view")
        root.deleteRecursively()
    }

    @Test fun savesStockCompletesAndReopensLocationWithoutChangingOtherProfile() {
        val location = guide.locations.first()
        val material = guide.materials.single { it.id == location.requirements.first().materialId }
        val store = PersonalStore(context)
        val openedId = AtomicReference<String>()
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme { RestorationGuideScreen(repository, {}, { openedId.set(it) }) }
            }
        }
        waitForGuide()
        scrollTo("restoration-mode-materials")
        compose.onNodeWithTag("restoration-mode-materials").performClick()
        search(material.title)
        scrollTo("restoration-edit:${material.id}")
        compose.onNodeWithTag("restoration-edit:${material.id}").performClick()
        compose.onNodeWithTag("restoration-stock-input").performTextReplacement("7")
        compose.onNodeWithText("保存库存").performClick()
        compose.waitUntil(15_000) { RestorationProgress.read(store.snapshot(), "global").inventory[material.id] == 7 }
        compose.onNode(hasText(material.title) and hasAnyAncestor(hasTestTag("restoration-material:${material.id}"))).performClick()
        assertEquals(material.itemEntryId, openedId.get())
        scrollTo("restoration-mode-locations")
        compose.onNodeWithTag("restoration-mode-locations").performClick()
        search(location.title)
        scrollTo("restoration-complete:${location.id}")
        compose.onNodeWithTag("restoration-complete:${location.id}").performClick()
        compose.waitUntil(15_000) { location.id in RestorationProgress.read(store.snapshot(), "global").completedLocations }
        compose.onNodeWithText("已完成 · 撤回").assertIsDisplayed()
        assertEquals(7, RestorationProgress.read(store.snapshot(), "global").inventory[material.id])
        assertTrue(RestorationProgress.read(store.snapshot(), "other-profile").inventory.isEmpty())
        compose.onNodeWithTag("restoration-complete:${location.id}").performClick()
        compose.waitUntil(15_000) { RestorationProgress.read(store.snapshot(), "global").completedLocations.isEmpty() }
    }

    @Test fun narrowLayoutKeepsMissingMaterialReadableAndRejectsInvalidStock() {
        val missing = guide.materials.first { it.itemEntryId == null }
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme {
                    Box(Modifier.width(360.dp).fillMaxHeight()) { RestorationGuideScreen(repository, {}, {}) }
                }
            }
        }
        waitForGuide()
        scrollTo("restoration-mode-materials")
        compose.onNodeWithTag("restoration-mode-materials").performClick()
        search(missing.title)
        scrollTo("restoration-edit:${missing.id}")
        compose.onNodeWithText("道具资料待核实").assertIsDisplayed()
        compose.onNodeWithTag("restoration-edit:${missing.id}").performClick()
        compose.onNodeWithTag("restoration-stock-input").performTextReplacement("-1")
        compose.onNodeWithText("保存库存").assertIsNotEnabled()
        compose.onNodeWithTag("restoration-stock-input").performTextReplacement("0")
        compose.onNodeWithText("保存库存").performClick()
        compose.waitUntil(15_000) { RestorationProgress.read(PersonalStore(context).snapshot(), "global").inventory[missing.id] == 0 }
    }

    @Test fun concurrentProgressVersionsDoNotBecomeAnEmptyChecklist() {
        val progress = RestorationProgress("global")
        val entityId = RestorationProgress.entityId("global")
        val base = PersonalSnapshot(emptyList())
        val device = "11111111-1111-1111-1111-111111111111"
        PersonalStore(context).merge(base.edit(entityId, "progress", device, progress.payload())
            .merge(base.edit(entityId, "progress", device, progress.copy(completedLocations = setOf(guide.locations.first().id)).payload())))
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme { RestorationGuideScreen(repository, {}, {}) }
            }
        }
        waitForGuide()
        compose.onNodeWithText("复兴清单有多个版本，请先到个人存档处理冲突").assertIsDisplayed()
        val location = guide.locations.first()
        search(location.title)
        scrollTo("restoration-complete:${location.id}")
        compose.onNodeWithTag("restoration-complete:${location.id}").assertIsNotEnabled()
    }

    @Test fun linkedMaterialReturnsToTheSameScrolledCards() {
        val material = guide.materials.last { it.itemEntryId != null }
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme {
                    ReadingPositionProvider {
                        val pages = rememberSaveableStateHolder()
                        var detailId by rememberSaveable { mutableStateOf<String?>(null) }
                        if (detailId != null) {
                            TextButton(onClick = { detailId = null }) { Text("返回复兴清单") }
                        } else pages.SaveableStateProvider("restoration") {
                            RestorationGuideScreen(repository, {}, { detailId = it })
                        }
                    }
                }
            }
        }
        waitForGuide()
        scrollTo("restoration-mode-materials")
        compose.onNodeWithTag("restoration-mode-materials").performClick()
        val tag = "restoration-material:${material.id}"
        scrollTo(tag)
        compose.onNodeWithText(material.title).performClick()
        compose.onNodeWithText("返回复兴清单").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }

    private fun waitForGuide() {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("restoration-mode-locations").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun scrollTo(tag: String) {
        compose.onNodeWithTag("restoration-grid").performScrollToNode(hasTestTag(tag))
    }

    private fun search(value: String) {
        scrollTo("restoration-search")
        compose.onNodeWithTag("restoration-search").performTextReplacement(value)
    }
}
