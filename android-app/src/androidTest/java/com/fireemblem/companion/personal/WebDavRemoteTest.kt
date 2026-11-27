package com.fireemblem.companion.personal

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebDavRemoteTest {
    private lateinit var server: MockWebServer
    private lateinit var remote: WebDavRemote

    @Before
    fun setup() {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply { useHttps(serverCertificates.sslSocketFactory(), false); start() }
        val client = OkHttpClient.Builder().sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .followRedirects(true).followSslRedirects(true).callTimeout(5, TimeUnit.SECONDS).build()
        remote = WebDavRemote(SyncSettings(server.url("/dav/").toString(), "owner", "test-secret", "separate-test-passphrase"), client)
    }

    @After fun cleanup() { server.shutdown() }

    @Test
    fun createsOnlyDedicatedCollectionAndUsesConditionalWrites() {
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(remote.read().bytes)
        assertEquals("/dav/fireemblem-companion/snapshot.enc", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setResponseCode(201).setHeader("ETag", "\"v1\""))
        assertTrue(remote.put(byteArrayOf(1, 2, 3), null))
        val collection = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("MKCOL", collection.method)
        assertEquals("/dav/fireemblem-companion/", collection.path)
        val upload = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("*", upload.getHeader("If-None-Match"))
        assertEquals(3, upload.body.size.toInt())
        server.enqueue(MockResponse().setResponseCode(412))
        assertFalse(remote.put(byteArrayOf(4), "\"v1\""))
        assertEquals("\"v1\"", server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("If-Match"))
    }

    @Test
    fun readsEtagsAndRefusesAuthenticationRedirectAndFullServerResponses() {
        server.enqueue(MockResponse().setBody("encrypted-data").setHeader("ETag", "\"current\""))
        val read = remote.read()
        assertEquals("\"current\"", read.etag)
        assertEquals("encrypted-data", read.bytes!!.toString(Charsets.UTF_8))
        assertEquals("GET", server.takeRequest(5, TimeUnit.SECONDS)!!.method)
        for (status in listOf(401, 403, 301, 302, 303, 307, 308, 507)) {
            server.enqueue(response(status))
            assertThrows(IllegalStateException::class.java) { remote.read() }
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("GET", request.method)
            assertEquals("/dav/fireemblem-companion/snapshot.enc", request.path)
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
        }
    }

    @Test
    fun rejectsAuthenticationForbiddenRedirectConflictAndFullResponsesToPut() {
        for (status in listOf(401, 403, 301, 302, 303, 307, 308, 409, 507)) {
            server.enqueue(response(status))
            assertThrows(IllegalStateException::class.java) { remote.put(byteArrayOf(1, 2, 3), "\"current\"") }
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("PUT", request.method)
            assertEquals("/dav/fireemblem-companion/snapshot.enc", request.path)
            assertEquals("\"current\"", request.getHeader("If-Match"))
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
        }
    }

    @Test
    fun collectionCreationFailuresStopBeforeSnapshotUpload() {
        for (status in listOf(401, 403, 302, 507)) {
            server.enqueue(response(status))
            assertThrows(IllegalStateException::class.java) { remote.put(byteArrayOf(1), null) }
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("MKCOL", request.method)
            assertEquals("/dav/fireemblem-companion/", request.path)
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
        }
    }

    @Test
    fun interruptedUploadPropagatesIoFailure() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_DURING_REQUEST_BODY))
        assertThrows(IOException::class.java) {
            remote.put(ByteArray(512 * 1024) { 7 }, "\"current\"")
        }
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("PUT", request.method)
        assertEquals("/dav/fireemblem-companion/snapshot.enc", request.path)
    }

    @Test
    fun fullServerFailureDoesNotReportSyncSuccessOrOverwriteLocalRecords() {
        withLocalStore { store, localBefore ->
            val remoteSnapshot = snapshot("remote")
            server.enqueue(MockResponse().setResponseCode(200)
                .setBody(Buffer().write(EncryptedBackup.encrypt(remoteSnapshot, "separate-test-passphrase".toCharArray())))
                .setHeader("ETag", "\"current\""))
            server.enqueue(MockResponse().setResponseCode(507))

            val failure = assertThrows(IllegalStateException::class.java) {
                store.merge(PersonalSync.synchronize(store.snapshot(), remote, "separate-test-passphrase".toCharArray()))
            }

            assertTrue(failure.message.orEmpty().contains("空间不足"))
            assertEquals(localBefore, store.snapshot())
            assertEquals("GET", server.takeRequest(5, TimeUnit.SECONDS)!!.method)
            val upload = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("PUT", upload.method)
            assertEquals("\"current\"", upload.getHeader("If-Match"))
        }
    }

    private fun response(status: Int) = MockResponse().setResponseCode(status).setHeader("Location", server.url("/redirect-target"))

    private fun snapshot(note: String) = PersonalSnapshot(emptyList()).edit(UUID.randomUUID().toString(), "note",
        UUID.randomUUID().toString(), buildJsonObject { put("note", note) })

    private fun withLocalStore(block: (PersonalStore, PersonalSnapshot) -> Unit) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val root = Files.createTempDirectory(target.cacheDir.toPath(), "webdav-test-").toFile()
        try {
            val context = object : ContextWrapper(target) { override fun getNoBackupFilesDir() = root }
            val store = PersonalStore(context)
            val before = store.edit(UUID.randomUUID().toString(), "note", buildJsonObject { put("note", "local") }, expectedParents = emptySet())
            block(store, before)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun neverAllowsPlaintextCredentialsOrUrlEmbeddedPasswords() {
        assertThrows(IllegalArgumentException::class.java) { WebDavRemote(SyncSettings("http://example.org", "u", "p", "secret")) }
        assertThrows(IllegalArgumentException::class.java) { WebDavRemote(SyncSettings("https://u:p@example.org", "u", "p", "secret")) }
    }
}
