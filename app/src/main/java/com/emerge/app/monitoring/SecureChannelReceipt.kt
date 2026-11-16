package com.emerge.app.monitoring

import org.json.JSONObject

internal data class SecureReceiptDecision(val result: JSONObject? = null, val error: SecurityAttestationException? = null)

/** Mutates the checkpoint only after AEAD and chain validation; caller persists it before returning. */
internal object SecureChannelReceipt {
    fun apply(state: JSONObject, pending: JSONObject, response: JSONObject): SecureReceiptDecision = try {
        verifyAndApply(state, pending, response)
    } catch (error: SecurityAttestationException) { throw error }
    catch (_: Exception) { throw SecurityAttestationException("安全服务响应格式异常，已保留待恢复请求", "RESULT_CONTRACT_INVALID") }

    private fun verifyAndApply(state: JSONObject, pending: JSONObject, response: JSONObject): SecureReceiptDecision {
        val request = pending.getJSONObject("request")
        val key = AttestedPayloadCipher.decode(pending.getString("response_key"))
        val plaintext = try { AttestedPayloadCipher.decrypt(key, response.getString("iv_base64"),
            response.getString("ciphertext_base64"), AttestedPayloadCipher.responseAad(request.getString("session_id"),
                request.getString("client_id"), request.getString("request_id"), response)) }
        finally { key.fill(0) }
        val result = runCatching { JSONObject(plaintext) }.getOrNull()
        val status = response.getString("status")
        if (status == "SECURITY_BLOCKED") {
            state.remove("pending")
            return SecureReceiptDecision(error = SecurityAttestationException(
                result?.optString("alert_message", "服务已阻断不安全请求") ?: "服务已阻断不安全请求",
                response.optString("alert_code", "SECURITY_BLOCKED")))
        }
        if (status != "OK" || !response.isNull("alert_code"))
            throw SecurityAttestationException("安全服务响应状态异常，已保留待恢复请求", "RESULT_CONTRACT_INVALID")
        val next = ChannelState(response.getLong("new_state_version"), response.getString("new_state_hash"))
        AttestedPayloadCipher.verifyNextState(ChannelState(state.getLong("version"), state.getString("hash")),
            request.getString("request_id"), request.getString("ciphertext_base64"), next)
        val contractValid = result != null && runCatching {
            val validation = pending.getJSONObject("validation")
            val semantic = com.emerge.app.analysis.SemanticV2Codec.decode(result)
            require(semantic.workId == validation.getString("work_id") && semantic.validFor(validation.getString("source_text")))
            val ids = validation.getJSONArray("history_ids").let { values -> (0 until values.length()).map(values::getString).toSet() }
            require(semantic.facts.all { it.matchedHistoryId == null || it.matchedHistoryId in ids })
        }.isSuccess
        // The server has already committed this authenticated transport transition. Preserve
        // its high-water mark even when model output is unusable, without caching it as valid.
        state.put("version", next.version).put("hash", next.hash).put("last_request_id", request.getString("request_id"))
        if (contractValid) state.put("last_digest", pending.getString("digest")).put("last_result", result)
        else {
            state.remove("last_digest"); state.remove("last_result")
            state.put("last_rejected_result", result ?: plaintext)
        }
        state.remove("pending")
        return if (contractValid) SecureReceiptDecision(result = result) else SecureReceiptDecision(error =
            SecurityAttestationException("安全服务结果与请求或原文不一致，已阻断结果", "RESULT_CONTRACT_INVALID"))
    }
}
