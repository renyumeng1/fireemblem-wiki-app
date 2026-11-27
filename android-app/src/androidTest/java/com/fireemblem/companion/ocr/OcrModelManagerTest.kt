package com.fireemblem.companion.ocr

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrModelManagerTest {
    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun newHttpsServer(): Pair<MockWebServer, OkHttpClient> {
        val cert = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCerts = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientCerts = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        val server = MockWebServer().apply {
            useHttps(serverCerts.sslSocketFactory(), false)
        }
        val client = OkHttpClient.Builder()
            .sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        return server to client
    }

    @Test
    fun downloadVerifiesSha256InstallsAtomicallyAndDeletesCleanly() = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val tempRoot = Files.createTempDirectory(target.cacheDir.toPath(), "ocr-mgr-ok-").toFile()
        val detBytes = ByteArray(140_000) { i -> ((i * 31) and 0xFF).toByte() }
        val recBytes = ByteArray(200_000) { i -> ((i * 17 + 7) and 0xFF).toByte() }
        val (server, client) = newHttpsServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/det.onnx" -> MockResponse().setResponseCode(200).setBody(Buffer().write(detBytes))
                "/rec.onnx" -> MockResponse().setResponseCode(200).setBody(Buffer().write(recBytes))
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        try {
            val specs = listOf(
                OcrModelFileSpec(
                    id = OcrModelManager.ID_DET,
                    fileName = "det.onnx",
                    expectedBytes = detBytes.size.toLong(),
                    expectedSha256 = sha256Hex(detBytes),
                    urls = listOf(server.url("/det.onnx").toString()),
                ),
                OcrModelFileSpec(
                    id = OcrModelManager.ID_REC,
                    fileName = "rec.onnx",
                    expectedBytes = recBytes.size.toLong(),
                    expectedSha256 = sha256Hex(recBytes),
                    urls = listOf(server.url("/rec.onnx").toString()),
                ),
            )
            val baseDir = File(tempRoot, "ocr-models")
            val manager = OcrModelManager(baseDir = baseDir, specs = specs, client = client)

            assertEquals(OcrModelStatus.NotInstalled, manager.status())

            val progressEvents = mutableListOf<OcrDownloadProgress>()
            val installed = manager.download { progressEvents += it }

            assertEquals((detBytes.size + recBytes.size).toLong(), installed.totalBytes)
            assertTrue(installed.detFile.isFile)
            assertTrue(installed.recFile.isFile)
            assertFalse("Temp staging dir must be removed", File(baseDir, ".tmp").exists())
            assertTrue("Should emit multiple streaming progress updates", progressEvents.size >= 2)
            assertEquals(1.0f, progressEvents.last().fraction, 0.001f)

            val offlineStatus = manager.status()
            assertTrue(offlineStatus is OcrModelStatus.Installed)

            manager.deleteModels()
            assertEquals(OcrModelStatus.NotInstalled, manager.status())
            assertFalse(baseDir.exists())
        } finally {
            server.shutdown()
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun sha256MismatchRejectsAndCleansTempFiles() = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val tempRoot = Files.createTempDirectory(target.cacheDir.toPath(), "ocr-mgr-bad-").toFile()
        val detBytes = "valid-det-payload".toByteArray()
        val badRecBytes = "corrupted-rec-payload".toByteArray()
        val expectedRecBytes = "expected-rec-payload!".toByteArray()
        val (server, client) = newHttpsServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/det.onnx" -> MockResponse().setResponseCode(200).setBody(Buffer().write(detBytes))
                "/rec.onnx" -> MockResponse().setResponseCode(200).setBody(Buffer().write(badRecBytes))
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        try {
            val specs = listOf(
                OcrModelFileSpec(
                    id = OcrModelManager.ID_DET,
                    fileName = "det.onnx",
                    expectedBytes = detBytes.size.toLong(),
                    expectedSha256 = sha256Hex(detBytes),
                    urls = listOf(server.url("/det.onnx").toString()),
                ),
                OcrModelFileSpec(
                    id = OcrModelManager.ID_REC,
                    fileName = "rec.onnx",
                    expectedBytes = badRecBytes.size.toLong(),
                    expectedSha256 = sha256Hex(expectedRecBytes),
                    urls = listOf(server.url("/rec.onnx").toString()),
                ),
            )
            val baseDir = File(tempRoot, "ocr-models")
            val manager = OcrModelManager(baseDir = baseDir, specs = specs, client = client)

            var caught: IOException? = null
            try {
                manager.download()
            } catch (e: IOException) {
                caught = e
            }
            assertTrue(
                "Expected SHA-256 verification failure, got: ${caught?.message}",
                caught != null && caught.message.orEmpty().contains("SHA-256"),
            )
            assertEquals(OcrModelStatus.NotInstalled, manager.status())
            assertFalse("Temp staging dir must be cleaned up on error", File(baseDir, ".tmp").exists())
        } finally {
            server.shutdown()
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun fallsBackToMirrorWhenPrimaryUrlFails() = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val tempRoot = Files.createTempDirectory(target.cacheDir.toPath(), "ocr-mgr-mirror-").toFile()
        val detBytes = "mirror-det-ok".toByteArray()
        val recBytes = "mirror-rec-ok".toByteArray()
        val (server, client) = newHttpsServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/primary/det.onnx" -> MockResponse().setResponseCode(503)
                "/mirror/det.onnx" -> MockResponse().setResponseCode(200).setBody(Buffer().write(detBytes))
                "/mirror/rec.onnx" -> MockResponse().setResponseCode(200).setBody(Buffer().write(recBytes))
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        try {
            val specs = listOf(
                OcrModelFileSpec(
                    id = OcrModelManager.ID_DET,
                    fileName = "det.onnx",
                    expectedBytes = detBytes.size.toLong(),
                    expectedSha256 = sha256Hex(detBytes),
                    urls = listOf(
                        server.url("/primary/det.onnx").toString(),
                        server.url("/mirror/det.onnx").toString(),
                    ),
                ),
                OcrModelFileSpec(
                    id = OcrModelManager.ID_REC,
                    fileName = "rec.onnx",
                    expectedBytes = recBytes.size.toLong(),
                    expectedSha256 = sha256Hex(recBytes),
                    urls = listOf(server.url("/mirror/rec.onnx").toString()),
                ),
            )
            val manager = OcrModelManager(baseDir = File(tempRoot, "ocr-models"), specs = specs, client = client)
            val installed = manager.download()
            assertEquals((detBytes.size + recBytes.size).toLong(), installed.totalBytes)
        } finally {
            server.shutdown()
            tempRoot.deleteRecursively()
        }
    }
}
