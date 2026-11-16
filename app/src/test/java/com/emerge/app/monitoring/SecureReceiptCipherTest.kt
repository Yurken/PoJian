package com.emerge.app.monitoring

import com.emerge.app.analysis.SemanticAnalysisV2
import com.emerge.app.analysis.SemanticGuidance
import com.emerge.app.analysis.SemanticV2Codec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SecureReceiptCipherTest {
    private val vector = javaClass.classLoader!!.getResourceAsStream("secure-channel-v2.json")!!.bufferedReader().use { JSONObject(it.readText()) }
    private fun pending() = JSONObject().put("request", vector.getJSONObject("analyze_request"))
        .put("response_key", vector.getString("response_key_base64")).put("digest", "payload-digest")
        .put("validation", JSONObject().put("work_id", "vector-work").put("source_text", "企业减少岗位")
            .put("history_ids", JSONArray()))
    private fun state(pending: JSONObject) = JSONObject().put("version", 0).put("hash", "0".repeat(64)).put("pending", pending)
    private fun semantic(id: String = "vector-work") = SemanticV2Codec.encode(SemanticAnalysisV2(id, "招聘",
        true, emptyList(), SemanticGuidance(false), emptyList(), 0, 0, 0.0, 5))
    private fun response(plain: String, blocked: Boolean = false): JSONObject {
        val reply = JSONObject(vector.getJSONObject("analyze_response").toString())
        if (blocked) reply.put("status", "SECURITY_BLOCKED").put("alert_code", "STATE_ROLLBACK_DETECTED")
            .put("new_state_version", 0).put("new_state_hash", "0".repeat(64))
        val request = vector.getJSONObject("analyze_request")
        val aad = AttestedPayloadCipher.responseAad(request.getString("session_id"), request.getString("client_id"),
            request.getString("request_id"), reply)
        val (iv, cipher) = AttestedPayloadCipher.encrypt(AttestedPayloadCipher.decode(vector.getString("response_key_base64")), plain, aad)
        return reply.put("iv_base64", iv).put("ciphertext_base64", cipher)
    }

    @Test fun successfulReceiptKeepsReplayResultAndAdvancesOnlyOnce() {
        val pending = pending(); val state = state(pending)
        val reply = response(semantic().toString())
        val decision = SecureChannelReceipt.apply(state, pending, reply)
        assertNull(decision.error)
        assertEquals("vector-work", decision.result!!.getString("work_id"))
        assertEquals(1L, state.getLong("version"))
        assertFalse(state.has("pending"))
        assertEquals("payload-digest", state.getString("last_digest"))
        assertEquals("vector-work", state.getJSONObject("last_result").getString("work_id"))
        try { SecureChannelReceipt.apply(state, pending, reply); fail("Must not advance duplicate receipt") }
        catch (error: SecurityAttestationException) { assertEquals("STATE_ROLLBACK_DETECTED", error.alertCode) }
        assertEquals(1L, state.getLong("version"))
    }

    @Test fun wrongWorkOrMalformedSemanticNeverEscapesButKeepsAuthenticatedCheckpoint() {
        listOf(semantic("another-work").toString(), "not-json").forEach { invalid ->
            val pending = pending(); val state = state(pending)
            val decision = SecureChannelReceipt.apply(state, pending, response(invalid))
            assertEquals("RESULT_CONTRACT_INVALID", decision.error!!.alertCode)
            assertNull(decision.result)
            assertEquals(1L, state.getLong("version"))
            assertFalse(state.has("last_digest"))
            assertTrue(state.has("last_rejected_result"))
            assertFalse(state.has("pending"))
        }
    }

    @Test fun unauthenticatedResponseCannotClearPendingOrAdvanceCheckpoint() {
        val pending = pending(); val state = state(pending)
        val reply = response(semantic().toString()).put("new_state_version", 100)
        try { SecureChannelReceipt.apply(state, pending, reply); fail("Must reject altered metadata") }
        catch (error: SecurityAttestationException) { assertEquals("GCM_AUTH_FAILED", error.alertCode) }
        assertEquals(0L, state.getLong("version"))
        assertTrue(state.has("pending"))
    }

    @Test fun authenticatedRollbackBlockKeepsLocalHighWaterMark() {
        val pending = pending(); val state = state(pending)
        val decision = SecureChannelReceipt.apply(state, pending,
            response(JSONObject().put("alert_message", "已阻断").toString(), blocked = true))
        assertEquals("STATE_ROLLBACK_DETECTED", decision.error!!.alertCode)
        assertEquals(0L, state.getLong("version"))
        assertEquals("0".repeat(64), state.getString("hash"))
        assertFalse(state.has("pending"))
        assertFalse(state.has("last_result"))
    }
}
