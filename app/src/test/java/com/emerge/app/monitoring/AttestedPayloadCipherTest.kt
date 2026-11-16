package com.emerge.app.monitoring

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AttestedPayloadCipherTest {
    private val clientId = "11".repeat(16)
    private val nonce = "22".repeat(32)
    private val sessionId = "33".repeat(16)
    private val measurement = "44".repeat(32)
    private val now = 1_800_000_000_000L
    private val client = X25519PrivateKeyParameters(ByteArray(32) { (it + 1).toByte() }, 0)
    private val server = X25519PrivateKeyParameters(ByteArray(32) { (it + 33).toByte() }, 0)
    private val signerKey = Ed25519PrivateKeyParameters(ByteArray(32) { (it + 66).toByte() }, 0)
    private val pin = AttestedPayloadCipher.b64(signerKey.generatePublicKey().encoded)

    private fun quote(timestamp: Long = now): JSONObject {
        val serverPublic = AttestedPayloadCipher.publicKey(server)
        val transcript = listOf("pojian-attestation-v2", clientId, nonce, AttestedPayloadCipher.publicKey(client),
            sessionId, serverPublic, measurement, timestamp.toString()).joinToString("\n").toByteArray()
        val signer = Ed25519Signer().apply { init(true, signerKey); update(transcript, 0, transcript.size) }
        return JSONObject().put("session_id", sessionId).put("server_ephemeral_pubkey", serverPublic)
            .put("code_measurement_hash", measurement).put("quote_signature", AttestedPayloadCipher.b64(signer.generateSignature()))
            .put("timestamp_ms", timestamp).put("enclave_mode", "SIGNED_APPLICATION_V2")
    }

    private fun attest(response: JSONObject = quote()) =
        AttestedPayloadCipher.attest(clientId, nonce, client, response, pin, measurement, now)

    private fun blocked(block: () -> Unit) {
        try { block(); fail("Unsafe message accepted") } catch (_: SecurityAttestationException) { }
    }

    @Test fun matchesPythonCryptographyWireVectorExactly() {
        val vector = javaClass.classLoader!!.getResourceAsStream("secure-channel-v2.json")!!.bufferedReader().use { JSONObject(it.readText()) }
        val request = vector.getJSONObject("attestation_request")
        val response = vector.getJSONObject("attestation_response")
        val privateKey = X25519PrivateKeyParameters(AttestedPayloadCipher.decode(vector.getString("client_private_key_base64")), 0)
        val session = AttestedPayloadCipher.attest(request.getString("client_id"), request.getString("client_nonce"),
            privateKey, response, vector.getString("signing_public_key_base64"), response.getString("code_measurement_hash"),
            response.getLong("timestamp_ms"))
        assertEquals(vector.getString("request_key_base64"), AttestedPayloadCipher.b64(session.requestKey))
        assertEquals(vector.getString("response_key_base64"), AttestedPayloadCipher.b64(session.responseKey))
        val encrypted = vector.getJSONObject("analyze_request")
        val requestId = encrypted.getString("request_id")
        val aad = AttestedPayloadCipher.requestAad(session, requestId, ChannelState())
        assertEquals(vector.getString("request_aad_utf8"), aad)
        assertEquals(vector.getString("request_plaintext"), AttestedPayloadCipher.decrypt(session.requestKey,
            encrypted.getString("iv_base64"), encrypted.getString("ciphertext_base64"), aad))
        val result = vector.getJSONObject("analyze_response")
        val replyAad = AttestedPayloadCipher.responseAad(session.id, session.clientId, requestId, result)
        assertEquals(vector.getString("response_aad_utf8"), replyAad)
        assertEquals(vector.getString("response_plaintext"), AttestedPayloadCipher.decrypt(session.responseKey,
            result.getString("iv_base64"), result.getString("ciphertext_base64"), replyAad))
        AttestedPayloadCipher.verifyNextState(ChannelState(), requestId, encrypted.getString("ciphertext_base64"),
            ChannelState(result.getLong("new_state_version"), result.getString("new_state_hash")))
    }

    @Test fun signedHandshakeDerivesSeparateDirectionalKeysAndAuthenticatesCiphertext() {
        val session = attest()
        assertEquals(measurement, session.measurement)
        assertEquals(32, session.requestKey.size)
        assertFalse(session.requestKey.contentEquals(session.responseKey))
        val aad = AttestedPayloadCipher.requestAad(session, "55".repeat(16), ChannelState())
        val (iv, cipher) = AttestedPayloadCipher.encrypt(session.requestKey, "事实原文", aad)
        assertEquals("事实原文", AttestedPayloadCipher.decrypt(session.requestKey, iv, cipher, aad))
        blocked { AttestedPayloadCipher.decrypt(session.responseKey, iv, cipher, aad) }
        blocked { AttestedPayloadCipher.decrypt(session.requestKey, iv, cipher, aad + "1") }
        val changed = AttestedPayloadCipher.decode(cipher).also { it[0] = (it[0].toInt() xor 1).toByte() }
        blocked { AttestedPayloadCipher.decrypt(session.requestKey, iv, AttestedPayloadCipher.b64(changed), aad) }
    }

    @Test fun refusesChangedMeasurementSessionKeyNonceTimestampAndSignature() {
        listOf("code_measurement_hash" to "66".repeat(32), "session_id" to "77".repeat(16),
            "server_ephemeral_pubkey" to AttestedPayloadCipher.publicKey(client),
            "quote_signature" to AttestedPayloadCipher.b64(ByteArray(64))).forEach { (key, value) ->
            blocked { attest(quote().put(key, value)) }
        }
        blocked { AttestedPayloadCipher.attest(clientId, "99".repeat(32), client, quote(), pin, measurement, now) }
        blocked { attest(quote(now - 120_001)) }
        blocked { attest(quote(now + 30_001)) }
    }

    @Test fun onlyExactMonotonicStateAndCipherDigestAreAccepted() {
        val request = "88".repeat(16)
        val ciphertext = AttestedPayloadCipher.b64(ByteArray(32) { it.toByte() })
        val previous = ChannelState(7, "ab".repeat(32))
        val next = ChannelState(8, AttestedPayloadCipher.hash("${previous.hash}|$request|${AttestedPayloadCipher.hash(AttestedPayloadCipher.decode(ciphertext))}|v8"))
        AttestedPayloadCipher.verifyNextState(previous, request, ciphertext, next)
        blocked { AttestedPayloadCipher.verifyNextState(previous, request, ciphertext, next.copy(version = 7)) }
        blocked { AttestedPayloadCipher.verifyNextState(previous, request, ciphertext, next.copy(version = 9)) }
        blocked { AttestedPayloadCipher.verifyNextState(previous, request, ciphertext, next.copy(hash = "0".repeat(64))) }
        blocked { AttestedPayloadCipher.verifyNextState(previous, "12".repeat(16), ciphertext, next) }
    }

    @Test fun responseAadProtectsStatusAlertAndState() {
        val session = attest()
        val response = JSONObject().put("status", "OK").put("alert_code", JSONObject.NULL)
            .put("new_state_version", 1).put("new_state_hash", "aa".repeat(32))
        fun aad() = AttestedPayloadCipher.responseAad(sessionId, clientId, "bb".repeat(16), response)
        val (iv, cipher) = AttestedPayloadCipher.encrypt(session.responseKey, "{}", aad())
        assertEquals("{}", AttestedPayloadCipher.decrypt(session.responseKey, iv, cipher, aad()))
        response.put("status", "SECURITY_BLOCKED")
        blocked { AttestedPayloadCipher.decrypt(session.responseKey, iv, cipher, aad()) }
    }

    @Test fun endpointChangesCannotDowngradePinnedChannel() {
        SecureChannelConfig.requirePinnedEndpoint("https://trusted.example/", "https://trusted.example")
        blocked { SecureChannelConfig.requirePinnedEndpoint("https://other.example", "https://trusted.example") }
        blocked { SecureChannelConfig.requirePinnedEndpoint("http://trusted.example", "https://trusted.example") }
        blocked { SecureChannelConfig.requirePinnedEndpoint("https://trusted.example", "") }
    }
}
