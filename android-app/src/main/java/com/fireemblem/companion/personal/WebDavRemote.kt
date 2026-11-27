package com.fireemblem.companion.personal

import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.concurrent.TimeUnit
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

data class SyncSettings(val url: String, val username: String, val password: String, val encryptionPassword: String)

class WebDavRemote internal constructor(settings: SyncSettings, client: OkHttpClient) : PersonalRemote {
    constructor(settings: SyncSettings) : this(settings, defaultClient)

    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val collection: String
    private val authorization = Credentials.basic(settings.username, settings.password, Charsets.UTF_8)

    init {
        val base = URI(settings.url.trim())
        require(base.scheme == "https" && !base.host.isNullOrBlank() && base.userInfo == null && base.fragment == null && base.query == null) {
            "请输入不含账号、查询参数或片段的HTTPS WebDAV目录地址"
        }
        require(':' !in settings.username && '\r' !in settings.username && '\n' !in settings.username) { "WebDAV用户名不合法" }
        collection = base.toASCIIString().trimEnd('/') + "/fireemblem-companion/"
    }

    private fun request(url: String, method: String, bytes: ByteArray? = null, etag: String? = null): Response {
        val builder = Request.Builder().url(url).header("Authorization", authorization).header("Accept-Encoding", "identity")
            .method(method, bytes?.toRequestBody("application/octet-stream".toMediaType()))
        if (method == "PUT") builder.header(if (etag == null) "If-None-Match" else "If-Match", etag ?: "*")
        return client.newCall(builder.build()).execute()
    }

    override fun read(): RemotePersonalBackup = request(collection + "snapshot.enc", "GET").use { response ->
        when (val status = response.code) {
            404 -> RemotePersonalBackup(null, null)
            200 -> {
                val bytes = requireNotNull(response.body).byteStream().use { input ->
                    val result = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var count = input.read(buffer)
                    while (count >= 0) {
                        require(result.size() + count <= PersonalSnapshot.MAX_BYTES + 128) { "远端备份超过支持大小" }
                        result.write(buffer, 0, count)
                        count = input.read(buffer)
                    }
                    result.toByteArray()
                }
                RemotePersonalBackup(bytes, response.header("ETag"))
            }
            else -> error(message(status))
        }
    }

    override fun put(bytes: ByteArray, expectedEtag: String?): Boolean {
        if (expectedEtag == null) request(collection, "MKCOL").use { response ->
            check(response.code in setOf(201, 405)) { message(response.code) }
        }
        return request(collection + "snapshot.enc", "PUT", bytes, expectedEtag).use { response ->
            when (val status = response.code) {
                200, 201, 204 -> true
                412 -> false
                else -> error(message(status))
            }
        }
    }

    private fun message(status: Int): String = when (status) {
        401, 403 -> "WebDAV账号、密码或目录权限不正确（$status）"
        301, 302, 307, 308 -> "服务器要求跳转，请填写最终的HTTPS目录地址"
        409 -> "WebDAV上级目录不存在，请先创建所填写的目录"
        507 -> "WebDAV空间不足，本机记录仍保留"
        else -> "WebDAV请求未成功（$status），本机记录仍保留"
    }

    companion object {
        private val defaultClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS).build()
    }
}
