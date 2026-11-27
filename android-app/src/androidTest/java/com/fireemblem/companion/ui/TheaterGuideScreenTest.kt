package com.fireemblem.companion.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.fireemblem.companion.content.TheaterGuideData
import com.fireemblem.companion.content.WikiDatabaseRepository
import com.fireemblem.companion.content.WikiPageRequest
import com.fireemblem.companion.importer.AtomicBuddyArchiveStore
import com.fireemblem.companion.personal.PersonalSnapshot
import com.fireemblem.companion.personal.PersonalStore
import com.fireemblem.companion.personal.TheaterBookmark
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class TheaterGuideScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var repository: WikiDatabaseRepository
    private lateinit var guide: TheaterGuideData
    private val registryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <Input, Output> onLaunch(requestCode: Int, contract: ActivityResultContract<Input, Output>, input: Input, options: ActivityOptionsCompat?) {
                error("剧目收藏导航测试不应打开文件选择器")
            }
        }
    }

    @Before fun setUp() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        root = Files.createTempDirectory(target.cacheDir.toPath(), "theater-test-").toFile()
        context = object : ContextWrapper(target) {
            override fun getNoBackupFilesDir(): File = File(root, "no-backup").apply { mkdirs() }
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = target.getSharedPreferences("${root.name}-$name", mode)
        }
        repository = WikiDatabaseRepository.open(context)
        guide = requireNotNull(repository.theaterGuide())
    }

    @After fun tearDown() { repository.close(); root.deleteRecursively() }

    @Test fun filtersPlayGapsAndShowsUnrecordedRewardsInsteadOfZero() {
        compose.setContent { CompositionLocalProvider(LocalContext provides context) { MaterialTheme { TheaterGuideScreen(repository, {}, {}) } } }
        waitForGuide()
        scrollTo("theater-only-gaps")
        compose.onNodeWithTag("theater-only-gaps").performClick()
        val play = guide.plays.single { it.title == "戏曲·斗技场的白发鬼" }
        scrollTo("theater-play:${play.id}")
        compose.onNodeWithTag("theater-play:${play.id}").performClick()
        scrollTo("theater-reward:BAD")
        compose.onNodeWithText("报酬未收录").assertIsDisplayed()
        scrollTo("theater-back")
        compose.onNodeWithTag("theater-back").performClick()
        scrollTo("theater-only-gaps")
        compose.onNodeWithTag("theater-only-gaps").performClick()
        search("喜悦")
        val first = guide.plays.first()
        scrollTo("theater-play:${first.id}")
        compose.onNodeWithTag("theater-play:${first.id}").performClick()
        compose.onNodeWithTag("theater-grid").performScrollToNode(androidx.compose.ui.test.hasText("满怀喜悦地"))
        compose.onNodeWithText("满怀喜悦地").assertIsDisplayed()
    }

    @Test fun bookmarksPersistAndAreIsolatedByProfile() {
        val first = guide.plays.first()
        val store = PersonalStore(context)
        compose.setContent { CompositionLocalProvider(LocalContext provides context) { MaterialTheme { TheaterGuideScreen(repository, {}, {}) } } }
        waitForGuide()
        scrollTo("theater-favorite:${first.id}")
        compose.onNodeWithTag("theater-favorite:${first.id}").performClick()
        compose.waitUntil(15_000) { TheaterBookmark.read(store.snapshot(), "global", first.id, first.title).favorite }
        assertFalse(TheaterBookmark.read(store.snapshot(), "other-profile", first.id, first.title).favorite)
        scrollTo("theater-only-favorites")
        compose.onNodeWithTag("theater-only-favorites").performClick()
        scrollTo("theater-play:${first.id}")
        compose.onNodeWithTag("theater-play:${first.id}").performClick()
        scrollTo("theater-favorite:${first.id}")
        compose.onNodeWithText("已收藏").assertIsDisplayed()
        compose.onNodeWithTag("theater-favorite:${first.id}").performClick()
        compose.waitUntil(15_000) { !TheaterBookmark.read(store.snapshot(), "global", first.id, first.title).favorite }
        scrollTo("theater-back")
        compose.onNodeWithTag("theater-back").performClick()
        compose.onNodeWithTag("theater-grid").performScrollToNode(androidx.compose.ui.test.hasText("没有匹配的剧目。试试其他关键词或取消筛选。"))
        compose.onNodeWithText("没有匹配的剧目。试试其他关键词或取消筛选。").assertIsDisplayed()
    }

    @Test fun characterWikiRoundTripKeepsSearchAndReadingPosition() {
        val entry = repository.page(WikiPageRequest("characters", limit = 100)).entries.last()
        val openedId = AtomicReference<String>()
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme { ReadingPositionProvider {
                    val pages = rememberSaveableStateHolder()
                    var wiki by rememberSaveable { mutableStateOf(false) }
                    if (wiki) TextButton(onClick = { wiki = false }) { Text("回到剧场资料") }
                    else pages.SaveableStateProvider("theater") {
                        TheaterGuideScreen(repository, {}, { openedId.set(it); wiki = true })
                    }
                } }
            }
        }
        waitForGuide()
        scrollTo("theater-mode-characters")
        compose.onNodeWithTag("theater-mode-characters").performClick()
        search(entry.title)
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("theater-character:${entry.id}").fetchSemanticsNodes().isNotEmpty() }
        scrollTo("theater-character:${entry.id}")
        compose.onNodeWithTag("theater-character:${entry.id}").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("theater-open-character").fetchSemanticsNodes().isNotEmpty() }
        scrollTo("theater-open-character")
        compose.onNodeWithTag("theater-open-character").performClick()
        assertEquals(entry.id, openedId.get())
        compose.onNodeWithText("回到剧场资料").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("theater-open-character").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("theater-open-character").assertIsDisplayed()
        scrollTo("theater-back")
        compose.onNodeWithTag("theater-back").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("theater-character:${entry.id}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("theater-character:${entry.id}").assertIsDisplayed()
    }

    @Test fun conflictedBookmarkCannotOverwriteEitherVersion() {
        val first = guide.plays.first()
        val bookmark = TheaterBookmark("global", first.id, first.title, true)
        val entity = TheaterBookmark.entityId("global", first.id)
        val base = PersonalSnapshot(emptyList())
        val device = "11111111-1111-1111-1111-111111111111"
        val snapshot = base.edit(entity, "bookmark", device, bookmark.payload())
            .merge(base.edit(entity, "bookmark", device, bookmark.copy(favorite = false).payload()))
        PersonalStore(context).merge(snapshot)
        compose.setContent { CompositionLocalProvider(LocalContext provides context) { MaterialTheme { TheaterGuideScreen(repository, {}, {}) } } }
        waitForGuide()
        search(first.title)
        scrollTo("theater-favorite:${first.id}")
        compose.onNodeWithTag("theater-favorite:${first.id}").assertIsNotEnabled()
        assertEquals(2, PersonalStore(context).snapshot().heads().getValue(entity).size)
    }

    @Test fun narrowLayoutShowsRealWeeklyRulesAndExactQuestLink() {
        val openedId = AtomicReference<String>()
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) { MaterialTheme {
                Box(Modifier.width(360.dp).fillMaxHeight()) { TheaterGuideScreen(repository, {}, { openedId.set(it) }) }
            } }
        }
        waitForGuide()
        compose.onNodeWithTag("theater-source").performClick()
        compose.onNodeWithTag("theater-grid").performScrollToNode(androidx.compose.ui.test.hasText("观剧\n（观看费500G） · 2人"))
        compose.onNodeWithText("观剧\n（观看费500G） · 2人").assertIsDisplayed()
        compose.onNodeWithTag("theater-grid").performScrollToNode(androidx.compose.ui.test.hasText(guide.relatedSidequestTitle))
        compose.onNodeWithText(guide.relatedSidequestTitle).performClick()
        assertEquals(guide.relatedSidequestId, openedId.get())
        assertTrue(guide.activities.last().note.contains("二选一"))
    }

    @Test fun personalRecordOpensExactPlayAndReturnsToPersonalManager() {
        val play = guide.plays.first()
        val bookmark = TheaterBookmark("global", play.id, play.title, true)
        PersonalStore(context).edit(TheaterBookmark.entityId("global", play.id), "bookmark", bookmark.payload(), expectedParents = emptySet())
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context, LocalActivityResultRegistryOwner provides registryOwner) { MaterialTheme { ReadingPositionProvider {
                var opened by rememberSaveable { mutableStateOf<String?>(null) }
                if (opened != null) TheaterGuideScreen(repository, { opened = null }, {}, initialPlayId = opened)
                else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    PersonalManager(AtomicBuddyArchiveStore(File(root, "buddy").toPath()), {}, {}, onOpenTheater = { opened = it })
                }
            } } }
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("theater-grid").fetchSemanticsNodes().isNotEmpty() ||
            compose.onAllNodes(androidx.compose.ui.test.hasText("查看剧目")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("查看剧目").performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("theater-favorite:${play.id}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(play.title).assertIsDisplayed()
        scrollTo("theater-back")
        compose.onNodeWithText("返回个人记录").assertIsDisplayed()
        compose.onNodeWithTag("theater-back").performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("查看剧目")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("查看剧目").performScrollTo().assertIsDisplayed()
    }

    private fun waitForGuide() { compose.waitUntil(15_000) { compose.onAllNodesWithTag("theater-mode-plays").fetchSemanticsNodes().isNotEmpty() } }
    private fun scrollTo(tag: String) { compose.onNodeWithTag("theater-grid").performScrollToNode(hasTestTag(tag)) }
    private fun search(value: String) { scrollTo("theater-search"); compose.onNodeWithTag("theater-search").performTextReplacement(value) }
}
