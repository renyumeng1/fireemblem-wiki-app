package com.fireemblem.companion.personal

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object EncryptedBackup {
    private val magic = "FECS1".toByteArray(Charsets.US_ASCII)
    private const val SALT_SIZE = 16
    private const val NONCE_SIZE = 12
    private const val ITERATIONS = 210000

    fun encrypt(snapshot: PersonalSnapshot, password: CharArray): ByteArray {
        require(password.size >= 12) { "同步口令至少需要12个字符，请使用独立的强口令并妥善保存" }
        val random = SecureRandom()
        val salt = ByteArray(SALT_SIZE).also(random::nextBytes)
        val nonce = ByteArray(NONCE_SIZE).also(random::nextBytes)
        val cipher = cipher(Cipher.ENCRYPT_MODE, password, salt, nonce)
        return magic + salt + nonce + cipher.doFinal(snapshot.encode())
    }

    fun decrypt(bytes: ByteArray, password: CharArray): PersonalSnapshot {
        val prefixSize = magic.size + SALT_SIZE + NONCE_SIZE
        require(bytes.size in prefixSize + 16..PersonalSnapshot.MAX_BYTES + prefixSize + 16 && bytes.take(magic.size).toByteArray().contentEquals(magic)) {
            "加密备份格式或长度不正确"
        }
        val salt = bytes.copyOfRange(magic.size, magic.size + SALT_SIZE)
        val nonce = bytes.copyOfRange(magic.size + SALT_SIZE, prefixSize)
        val plaintext = cipher(Cipher.DECRYPT_MODE, password, salt, nonce).doFinal(bytes, prefixSize, bytes.size - prefixSize)
        return try { PersonalSnapshot.decode(plaintext) } finally { plaintext.fill(0) }
    }

    private fun cipher(mode: Int, password: CharArray, salt: ByteArray, nonce: ByteArray): Cipher {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        val key = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(magic)
            }
        } finally { key.fill(0) }
    }
}
