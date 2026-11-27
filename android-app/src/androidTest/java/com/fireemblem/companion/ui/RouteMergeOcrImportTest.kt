package com.fireemblem.companion.ui

import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.fireemblem.companion.content.WikiDatabaseRepository
import com.fireemblem.companion.engine.BuddyCampaignLine
import com.fireemblem.companion.engine.GrowthStat
import com.fireemblem.companion.ocr.LocalOnnxOcrRecognizer
import com.fireemblem.companion.ocr.OcrModelFileSpec
import com.fireemblem.companion.ocr.OcrModelManager
import com.fireemblem.companion.ocr.ScreenshotStatRecognizer
import com.fireemblem.companion.ocr.WikiOcrDictionaryLoader
import com.fireemblem.companion.personal.NsScreenshotStatParser
import com.fireemblem.companion.personal.OcrRecognizedImage
import com.fireemblem.companion.personal.OcrSlotText
import com.fireemblem.companion.personal.PersonalStore
import com.fireemblem.companion.personal.RouteMergeCharacter
import com.fireemblem.companion.personal.RouteMergeSnapshot
import com.fireemblem.companion.ui.theme.FireEmblemTheme
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test

class RouteMergeOcrImportTest {
    @get:Rule
    val rule = createComposeRule()

    private fun noopRegistryOwner(): ActivityResultRegistryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <Input, Output> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<Input, Output>,
                input: Input,
                options: ActivityOptionsCompat?,
            ) = Unit
        }
    }

    private fun isolatedContext(tempRoot: File): android.content.Context {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        return object : android.content.ContextWrapper(target) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getFilesDir(): File = File(tempRoot, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir(): File = File(tempRoot, "no-backup").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) =
                target.getSharedPreferences("${tempRoot.name}-$name", mode)
        }
    }

    @Test
    fun batchReviewDetectsRoutesGuardsDuplicatesAndPreservesNoteAndSelection() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val tempRoot = Files.createTempDirectory(target.cacheDir.toPath(), "ocr-batch-ui-").toFile()
        val testContext = isolatedContext(tempRoot)
        try {
            WikiDatabaseRepository.open(target).use { repo ->
                val allChars = repo.page(
                    com.fireemblem.companion.content.WikiPageRequest(categoryId = "characters", limit = 100),
                ).entries
                val kai = allChars.first { it.title == "凯伊" }
                val multiRouteChar = allChars.first { entry ->
                    entry.id != kai.id && repo.characterRoutes(entry.id)
                        .count { it.joinKind != "unrecruitable" } >= 2
                }

                // Pre-seed Kai with existing SEO snapshot (with user note + custom selectedLines)
                val store = PersonalStore(testContext)
                val existingKai = RouteMergeCharacter(
                    profileId = "global",
                    characterId = kai.id,
                    characterName = kai.title,
                ).withSnapshot(
                    RouteMergeSnapshot(
                        line = BuddyCampaignLine.SEO,
                        level = 20,
                        profession = "士兵",
                        stats = listOf(35, 19, 10, 18, 17, 15, 16, 11, 14),
                        note = "重点培养",
                    ),
                ).withSelectedLines(setOf(BuddyCampaignLine.SEO))
                store.edit(
                    entityId = RouteMergeCharacter.entityId("global", kai.id),
                    kind = RouteMergeCharacter.KIND,
                    payload = existingKai.payload(),
                    deleted = false,
                    expectedParents = emptySet(),
                )

                val uri0 = Uri.parse("content://mock/switch_0_kai_seo.jpg")
                val uri1 = Uri.parse("content://mock/switch_1_multi_unknown.jpg")
                val uri2 = Uri.parse("content://mock/switch_2_kai_seo_dup.jpg")

                val fakeRecognizer = object : ScreenshotStatRecognizer {
                    override suspend fun recognize(uri: Uri): OcrRecognizedImage = when (uri) {
                        uri0, uri2 -> OcrRecognizedImage(
                            sourceUri = uri.toString(),
                            detectedCrestLine = BuddyCampaignLine.SEO,
                            crestReason = "识别到赛奥朵拉篇纹章",
                            templateSlots = mapOf(
                                "name" to OcrSlotText("凯伊", 0.99f),
                                "lv" to OcrSlotText("24", 0.99f),
                                "hp" to OcrSlotText("42", 0.99f),
                                "prof" to OcrSlotText("勇士", 0.99f),
                                "sum8" to OcrSlotText("168", 0.99f),
                                "STR" to OcrSlotText("25", 0.99f),
                                "MAG" to OcrSlotText("13", 0.99f),
                                "SPD" to OcrSlotText("24", 0.99f),
                                "DEX" to OcrSlotText("22", 0.99f),
                                "DEF" to OcrSlotText("23", 0.99f),
                                "RES" to OcrSlotText("17", 0.99f),
                                "LCK" to OcrSlotText("21", 0.99f),
                                "CHM" to OcrSlotText("23", 0.99f),
                            ),
                        )
                        uri1 -> OcrRecognizedImage(
                            sourceUri = uri.toString(),
                            detectedCrestLine = null,
                            crestReason = null,
                            templateSlots = mapOf(
                                "name" to OcrSlotText(multiRouteChar.title, 0.99f),
                                "lv" to OcrSlotText("21", 0.99f),
                                "hp" to OcrSlotText("36", 0.99f),
                                "prof" to OcrSlotText("贤者", 0.99f),
                                "sum8" to OcrSlotText("147", 0.99f),
                                "STR" to OcrSlotText("11", 0.99f),
                                "MAG" to OcrSlotText("27", 0.99f),
                                "SPD" to OcrSlotText("20", 0.99f),
                                "DEX" to OcrSlotText("19", 0.99f),
                                "DEF" to OcrSlotText("13", 0.99f),
                                "RES" to OcrSlotText("25", 0.99f),
                                "LCK" to OcrSlotText("15", 0.99f),
                                "CHM" to OcrSlotText("17", 0.99f),
                            ),
                        )
                        else -> error("unexpected uri $uri")
                    }

                    override fun close() = Unit
                }

                rule.setContent {
                    CompositionLocalProvider(
                        LocalContext provides testContext,
                        LocalActivityResultRegistryOwner provides noopRegistryOwner(),
                    ) {
                        FireEmblemTheme {
                            RouteMergeScreen(
                                repository = repo,
                                onBack = {},
                                ocrRecognizerFactory = { fakeRecognizer },
                                initialImportUris = listOf(uri0, uri1, uri2),
                            )
                        }
                    }
                }

                // Wait for batch sheet recognition to complete
                rule.waitUntil(10_000) {
                    rule.onAllNodesWithTag("ocr-bulk-line:kai").fetchSemanticsNodes().isNotEmpty()
                }

                // Item 0 should show existing-snapshot diff banner & checksum pass badge
                rule.onNodeWithTag("ocr-item-diff:0").assertIsDisplayed()
                rule.onNodeWithText("校验通过 168", substring = true).assertIsDisplayed()

                // Bulk assign undetermined screenshot to KAI line
                rule.onNodeWithTag("ocr-bulk-line:kai").performClick()

                // Duplicate item 2 remains auto-unchecked so commit is enabled for 2 valid items
                rule.waitUntil(5_000) {
                    rule.onAllNodesWithText("批量写入（2）").fetchSemanticsNodes().isNotEmpty()
                }
                rule.onNodeWithTag("ocr-batch-commit").assertIsEnabled().performClick()

                // Returns to Overview with batch commit status banner
                rule.waitUntil(10_000) {
                    rule.onAllNodesWithText("已批量写入 2 名角色 · 共 2 条路线快照").fetchSemanticsNodes().isNotEmpty()
                }

                // Verify PersonalStore persistence: Kai updated with note & selectedLines preserved, second added under KAI
                val persisted = RouteMergeCharacter.readProfile(store.snapshot(), "global")
                val savedKai = persisted.records.firstOrNull { it.character.characterId == kai.id }?.character
                val savedSecond = persisted.records.firstOrNull { it.character.characterId == multiRouteChar.id }?.character
                assertNotNull(savedKai)
                assertNotNull(savedSecond)
                assertEquals(24, savedKai!!.snapshots[BuddyCampaignLine.SEO]?.level)
                assertEquals(25, savedKai.snapshots[BuddyCampaignLine.SEO]?.stat(GrowthStat.STRENGTH))
                assertEquals("重点培养", savedKai.snapshots[BuddyCampaignLine.SEO]?.note)
                assertEquals(setOf(BuddyCampaignLine.SEO), savedKai.selectedLines)
                assertEquals(21, savedSecond!!.snapshots[BuddyCampaignLine.KAI]?.level)
                assertEquals(27, savedSecond.snapshots[BuddyCampaignLine.KAI]?.stat(GrowthStat.MAGIC))
            }
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun settingsCardDownloadsFromMockMirrorAndDeletesCleanly() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val tempRoot = Files.createTempDirectory(target.cacheDir.toPath(), "ocr-settings-ui-").toFile()
        val detBytes = "settings-det-model-bytes".toByteArray()
        val recBytes = "settings-rec-model-bytes".toByteArray()
        fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
        val cert = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverCerts = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientCerts = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        val server = MockWebServer().apply { useHttps(serverCerts.sslSocketFactory(), false) }
        val client = OkHttpClient.Builder()
            .sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/det.onnx" -> MockResponse().setResponseCode(200).setBody(Buffer().write(detBytes))
                "/rec.onnx" -> MockResponse().setResponseCode(200).setBody(Buffer().write(recBytes))
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        try {
            val manager = OcrModelManager(
                baseDir = File(tempRoot, "ocr-models"),
                specs = listOf(
                    OcrModelFileSpec(OcrModelManager.ID_DET, "det.onnx", detBytes.size.toLong(), sha(detBytes), listOf(server.url("/det.onnx").toString())),
                    OcrModelFileSpec(OcrModelManager.ID_REC, "rec.onnx", recBytes.size.toLong(), sha(recBytes), listOf(server.url("/rec.onnx").toString())),
                ),
                client = client,
            )
            rule.setContent {
                FireEmblemTheme {
                    LocalOcrModelSettingsCard(managerOverride = manager)
                }
            }
            rule.onNodeWithTag("ocr-settings-download").assertIsDisplayed().performClick()
            rule.waitUntil(10_000) {
                rule.onAllNodesWithTag("ocr-settings-delete").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithText("已就绪", substring = true).assertIsDisplayed()
            rule.onNodeWithTag("ocr-settings-delete").performClick()
            rule.waitUntil(10_000) {
                rule.onAllNodesWithTag("ocr-settings-download").fetchSemanticsNodes().isNotEmpty()
            }
        } finally {
            server.shutdown()
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun uninstalledModelShowsDownloadPromptWhenTappingImport() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val tempRoot = Files.createTempDirectory(target.cacheDir.toPath(), "ocr-prompt-ui-").toFile()
        val testContext = isolatedContext(tempRoot)
        try {
            val emptyManager = OcrModelManager(baseDir = File(tempRoot, "empty-ocr-models"))
            rule.setContent {
                CompositionLocalProvider(
                    LocalContext provides testContext,
                    LocalActivityResultRegistryOwner provides noopRegistryOwner(),
                ) {
                    FireEmblemTheme {
                        RouteMergeScreen(
                            repository = null,
                            onBack = {},
                            ocrModelManager = emptyManager,
                        )
                    }
                }
            }
            rule.waitUntil(10_000) {
                rule.onAllNodesWithTag("route-merge-ocr-import").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithTag("route-merge-ocr-import").performClick()
            rule.waitUntil(5_000) {
                rule.onAllNodesWithTag("ocr-prompt-download").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithText("下载本地 OCR 识别模型").assertIsDisplayed()
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    /**
     * Runs real ONNX Runtime Android inference on real 1920x1080 Switch screenshots when staged under
     * `<appCacheDir>/ocr_e2e/` (det model, rec model, and `pics/` folder).
     * Skips cleanly when external fixtures are not pushed on device.
     */
    @Test
    fun realSwitchScreenshotsRecognizeEndToEndWhenStagedOnDevice() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val stageDir = listOfNotNull(
            File(target.cacheDir, "ocr_e2e"),
            target.getExternalFilesDir(null)?.let { File(it, "ocr_e2e") },
        ).firstOrNull { File(it, "ch_PP-OCRv4_det_mobile.onnx").isFile }
        val detFile = stageDir?.let { File(it, "ch_PP-OCRv4_det_mobile.onnx") }
        val recFile = stageDir?.let { File(it, "ch_PP-OCRv4_rec_mobile.onnx") }
        val pics = stageDir?.let { File(it, "pics") }?.listFiles { f ->
            f.isFile && (f.name.endsWith(".jpg", ignoreCase = true) || f.name.endsWith(".png", ignoreCase = true))
        }?.sortedBy { it.name }.orEmpty()
        Assume.assumeTrue(
            "Real ONNX E2E fixtures not staged under cacheDir/ocr_e2e",
            detFile != null && detFile.isFile && recFile != null && recFile.isFile && pics.isNotEmpty(),
        )

        val tempRoot = Files.createTempDirectory(target.cacheDir.toPath(), "ocr-real-e2e-").toFile()
        val testContext = isolatedContext(tempRoot)
        try {
            WikiDatabaseRepository.open(target).use { repo ->
                val dictionary = WikiOcrDictionaryLoader.load(repo)
                val draftsByFile = linkedMapOf<File, com.fireemblem.companion.personal.ParsedRouteMergeDraft>()
                LocalOnnxOcrRecognizer(target, detFile!!, recFile!!).use { recognizer ->
                    val summaries = mutableListOf<String>()
                    for (pic in pics) {
                        val recognized = runBlocking { recognizer.recognize(Uri.fromFile(pic)) }
                        val draft = NsScreenshotStatParser.parse(recognized, dictionary)
                        draftsByFile[pic] = draft
                        summaries += "${pic.name}: char=${draft.characterName}(${draft.characterId}) line=${draft.detectedLine} lv=${draft.level} prof=${draft.profession} sum8=${draft.checksumComputed}/${draft.checksumDeclared}(${draft.checksumMatched}) stats=${draft.stats}"
                        assertNotNull("Failed character on ${pic.name}: $draft", draft.characterId)
                        assertNotNull("Failed route on ${pic.name}: $draft", draft.detectedLine)
                        assertNotNull("Failed level on ${pic.name}: $draft", draft.level)
                        assertTrue("Failed 9 stats on ${pic.name}: $draft", draft.hasAllRequiredStats)
                        assertEquals("Checksum mismatch on ${pic.name}: $draft", true, draft.checksumMatched)
                    }
                    android.util.Log.i("RealSwitchOcrE2E", "PASSED ${pics.size}/${pics.size} real Switch screenshots:\n${summaries.joinToString("\n")}")
                }

                // Also verify live RouteMergeScreen batch review + commit on 4 real Switch screenshots
                // spanning SEO + KAI + LEDA + 1 real duplicate!
                val seoPic = draftsByFile.entries.firstOrNull { it.value.detectedLine == BuddyCampaignLine.SEO }?.key
                val kaiPic = draftsByFile.entries.firstOrNull { it.value.detectedLine == BuddyCampaignLine.KAI }?.key
                val ledaPic = draftsByFile.entries.firstOrNull { it.value.detectedLine == BuddyCampaignLine.LEDA }?.key
                val dupPic = draftsByFile.entries.firstOrNull { (f, d) ->
                    f != kaiPic && kaiPic != null && d.characterId == draftsByFile[kaiPic]?.characterId && d.detectedLine == draftsByFile[kaiPic]?.detectedLine
                }?.key
                val uiSample = listOfNotNull(seoPic, kaiPic, ledaPic, dupPic).distinct()
                if (uiSample.size >= 3) {
                    val expectedUnique = uiSample.map { draftsByFile[it]!!.characterId to draftsByFile[it]!!.detectedLine }.distinct().size
                    rule.setContent {
                        CompositionLocalProvider(
                            LocalContext provides testContext,
                            LocalActivityResultRegistryOwner provides noopRegistryOwner(),
                        ) {
                            FireEmblemTheme {
                                RouteMergeScreen(
                                    repository = repo,
                                    onBack = {},
                                    ocrRecognizerFactory = { LocalOnnxOcrRecognizer(target, detFile, recFile) },
                                    initialImportUris = uiSample.map { Uri.fromFile(it) },
                                )
                            }
                        }
                    }
                    rule.waitUntil(30_000) {
                        rule.onAllNodesWithText("批量写入（$expectedUnique）").fetchSemanticsNodes().isNotEmpty()
                    }
                    if (File(stageDir, "capture_shots").exists()) {
                        val shotsDir = File(target.cacheDir, "ocr_e2e_shots").apply { mkdirs() }
                        rule.waitForIdle()
                        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bmp ->
                            File(shotsDir, "batch_review.png").outputStream().use {
                                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                            }
                            bmp.recycle()
                        }
                    }
                    rule.onNodeWithTag("ocr-batch-commit").assertIsEnabled().performClick()
                    rule.waitUntil(10_000) {
                        rule.onAllNodesWithText("共 $expectedUnique 条路线快照", substring = true).fetchSemanticsNodes().isNotEmpty()
                    }
                    if (File(stageDir, "capture_shots").exists()) {
                        val shotsDir = File(target.cacheDir, "ocr_e2e_shots").apply { mkdirs() }
                        rule.waitForIdle()
                        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bmp ->
                            File(shotsDir, "overview_after_import.png").outputStream().use {
                                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                            }
                            bmp.recycle()
                        }
                    }
                    android.util.Log.i("RealSwitchOcrE2E", "UI Batch Import + Commit PASSED for ${uiSample.size} screenshots ($expectedUnique unique snapshots)")
                }
            }
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
