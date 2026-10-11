package com.emerge.app.monitoring

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Protect queued text at rest and authenticate its task identity. No screenshot/audio bytes enter this envelope. */
class MonitorPayloadCipher {
    fun seal(plaintext: String, identity: String): String {
        val bytes = plaintext.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(true)) }
        cipher.updateAAD(aad(identity))
        return "1:" + Base64.encodeToString(cipher.iv + cipher.doFinal(bytes), Base64.NO_WRAP)
    }

    fun open(envelope: String, identity: String): String {
        require(envelope.startsWith("1:") && envelope.length <= (MAX_BYTES + 28) * 4 / 3 + 8)
        val bytes = Base64.decode(envelope.substring(2), Base64.NO_WRAP)
        require(bytes.size in 28..MAX_BYTES + 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        }
        cipher.updateAAD(aad(identity))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }

    private fun aad(identity: String): ByteArray {
        require(identity.length in 1..256)
        return "monitor-task:v1:$identity".toByteArray(Charsets.UTF_8)
    }

    private fun key(create: Boolean): SecretKey = synchronized(KEY_LOCK) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return@synchronized it }
        check(create) { "Queue encryption key unavailable" }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    companion object {
        private const val ALIAS = "emerge_monitor_queue_v1"
        private const val MAX_BYTES = 192 * 1024
        private val KEY_LOCK = Any()
    }
}
