package com.emerge.app.monitoring

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONObject

class SecurityAttestationException(message: String, val alertCode: String = "ATTESTATION_FAILED") : Exception(message)

data class ChannelState(val version: Long = 0, val hash: String = "0".repeat(64))
data class AttestedSession(val id: String, val clientId: String, val clientPublicKey: String,
    val measurement: String, val requestKey: ByteArray, val responseKey: ByteArray)

/** Version 2 wire crypto; separate from MonitorPayloadCipher's existing local queue format. */
object AttestedPayloadCipher {
    private val random = SecureRandom()
    fun randomHex(bytes: Int) = ByteArray(bytes).also(random::nextBytes).joinToString("") { "%02x".format(it) }
    fun ephemeralKey() = X25519PrivateKeyParameters(random)
    fun publicKey(key: X25519PrivateKeyParameters): String = b64(key.generatePublicKey().encoded)
    fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    fun decode(value: String): ByteArray = Base64.getDecoder().decode(value)
    fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun hash(text: String): String = hash(text.toByteArray(Charsets.UTF_8))

    fun attest(clientId: String, nonce: String, privateKey: X25519PrivateKeyParameters,
        response: JSONObject, pinnedPublicKey: String, expectedMeasurement: String, now: Long): AttestedSession {
        try {
            require(clientId.matches(Regex("[0-9a-f]{32}")) && nonce.matches(Regex("[0-9a-f]{64}")))
            require(expectedMeasurement.matches(Regex("[0-9a-f]{64}")))
            val sessionId = response.getString("session_id")
            val serverPublic = response.getString("server_ephemeral_pubkey")
            val clientPublic = publicKey(privateKey)
            val measurement = response.getString("code_measurement_hash")
            val timestamp = response.getLong("timestamp_ms")
            require(sessionId.matches(Regex("[0-9a-f]{32}")) && measurement == expectedMeasurement)
            require(response.getString("enclave_mode") == "SIGNED_APPLICATION_V2")
            require(timestamp in (now - 120_000)..(now + 30_000))
            val transcript = listOf("pojian-attestation-v2", clientId, nonce, clientPublic, sessionId,
                serverPublic, measurement, timestamp.toString()).joinToString("\n").toByteArray(Charsets.UTF_8)
            val pin = decode(pinnedPublicKey).also { require(it.size == 32) }
            val signature = decode(response.getString("quote_signature")).also { require(it.size == 64) }
            val verifier = Ed25519Signer().apply { init(false, Ed25519PublicKeyParameters(pin, 0)); update(transcript, 0, transcript.size) }
            require(verifier.verifySignature(signature))
            val serverKey = decode(serverPublic).also { require(it.size == 32) }
            val shared = ByteArray(32)
            privateKey.generateSecret(X25519PublicKeyParameters(serverKey, 0), shared, 0)
            val info = "pojian-cognitive-channel|".toByteArray(Charsets.UTF_8) + MessageDigest.getInstance("SHA-256").digest(transcript)
            val keys = hkdf(shared, "pojian-hpke-v2-salt".toByteArray(Charsets.UTF_8), info)
            shared.fill(0)
            return AttestedSession(sessionId, clientId, clientPublic, measurement, keys.copyOfRange(0, 32), keys.copyOfRange(32, 64))
                .also { keys.fill(0) }
        } catch (error: Exception) {
            throw SecurityAttestationException("云端程序签名或度量异常，已阻断上传")
        }
    }

    private fun hkdf(shared: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
        fun hmac(key: ByteArray, value: ByteArray) = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256")); doFinal(value)
        }
        val prk = hmac(salt, shared)
        val first = hmac(prk, info + byteArrayOf(1))
        val second = hmac(prk, first + info + byteArrayOf(2))
        prk.fill(0)
        return first + second
    }

    fun requestAad(session: AttestedSession, requestId: String, state: ChannelState) =
        listOf("pojian-v2", "request", session.id, session.clientId, requestId, state.version.toString(), state.hash).joinToString("|")

    fun responseAad(sessionId: String, clientId: String, requestId: String, response: JSONObject) =
        listOf("pojian-v2", "response", sessionId, clientId, requestId, response.getString("status"),
            if (response.isNull("alert_code")) "" else response.getString("alert_code"),
            response.getLong("new_state_version").toString(), response.getString("new_state_hash")).joinToString("|")

    fun encrypt(key: ByteArray, plaintext: String, aad: String): Pair<String, String> {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            updateAAD(aad.toByteArray(Charsets.UTF_8))
        }
        return b64(iv) to b64(cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)))
    }

    fun decrypt(key: ByteArray, iv: String, ciphertext: String, aad: String): String = try {
        val nonce = decode(iv).also { require(it.size == 12) }
        val bytes = decode(ciphertext).also { require(it.size in 16..256 * 1024) }
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(aad.toByteArray(Charsets.UTF_8))
            String(doFinal(bytes), Charsets.UTF_8)
        }
    } catch (_: Exception) { throw SecurityAttestationException("响应密文认证失败，已阻断结果", "GCM_AUTH_FAILED") }

    fun verifyNextState(previous: ChannelState, requestId: String, ciphertext: String, next: ChannelState) {
        val expectedHash = hash("${previous.hash}|$requestId|${hash(decode(ciphertext))}|v${previous.version + 1}")
        if (previous.version == Long.MAX_VALUE || next.version != previous.version + 1 || next.hash != expectedHash)
            throw SecurityAttestationException("历史状态版本链不连续，已阻断结果", "STATE_ROLLBACK_DETECTED")
    }
}
