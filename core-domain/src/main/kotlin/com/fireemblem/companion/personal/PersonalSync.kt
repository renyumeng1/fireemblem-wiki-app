package com.fireemblem.companion.personal

import java.util.concurrent.CancellationException

data class RemotePersonalBackup(val bytes: ByteArray?, val etag: String?)

interface PersonalRemote {
    fun read(): RemotePersonalBackup
    fun put(bytes: ByteArray, expectedEtag: String?): Boolean
}

object PersonalSync {
    fun synchronize(local: PersonalSnapshot, remote: PersonalRemote, password: CharArray,
        isCancelled: () -> Boolean = { false },
    ): PersonalSnapshot {
        local.validated()
        repeat(3) {
            if (isCancelled()) throw CancellationException()
            val current = remote.read()
            require(current.bytes == null || current.etag != null && Regex("\"[^\"\\r\\n]+\"").matches(current.etag)) {
                "服务器未提供可靠的ETag，已停止同步以避免覆盖其他设备"
            }
            val merged = current.bytes?.let { local.merge(EncryptedBackup.decrypt(it, password)) } ?: local
            val encrypted = EncryptedBackup.encrypt(merged, password)
            if (isCancelled()) throw CancellationException()
            if (remote.put(encrypted, current.etag)) return merged
        }
        error("其他设备正在更新，请稍后重试；本机记录未被覆盖")
    }
}
