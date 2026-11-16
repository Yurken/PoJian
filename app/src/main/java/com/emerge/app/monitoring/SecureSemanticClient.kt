package com.emerge.app.monitoring

import android.content.Context
import com.emerge.app.BuildConfig
import com.emerge.app.audioanalysis.EndpointPolicy
import com.emerge.app.audioanalysis.TextServiceConfig
import com.emerge.app.capture.CloudTextPrivacy
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import org.json.JSONObject

data class SecureChannelStatus(val verified: Boolean = false, val measurement: String = "",
    val version: Long = 0, val stateHash: String = "", val blocked: String? = null, val piiCount: Long = 0,
    val serviceIdentity: String = "")

object SecureChannelTelemetry {
    private val mutable = MutableStateFlow(SecureChannelStatus())
    val state = mutable.asStateFlow()
    fun update(transform: (SecureChannelStatus) -> SecureChannelStatus) = mutable.update(transform)
    fun blocked(error: SecurityAttestationException) = update { it.copy(verified = false, blocked = error.message) }
    fun select(identity: String) = update {
        if (it.serviceIdentity == identity) it else SecureChannelStatus(piiCount = it.piiCount, serviceIdentity = identity)
    }
}

/** Only counts are retained; masking never records the removed entity. */
object CloudPrivacyStats {
    private var preferences: android.content.SharedPreferences? = null
    @Synchronized fun attach(context: Context) {
        preferences = context.applicationContext.getSharedPreferences("cloud_privacy", Context.MODE_PRIVATE)
        SecureChannelTelemetry.update { it.copy(piiCount = preferences!!.getLong("masked", 0)) }
    }
    @Synchronized fun sanitize(text: String): String {
        val (value, count) = CloudTextPrivacy.sanitize(text)
        if (count > 0) preferences?.let { prefs ->
            val total = (prefs.getLong("masked", 0) + count).coerceAtLeast(0)
            check(prefs.edit().putLong("masked", total).commit())
            SecureChannelTelemetry.update { it.copy(piiCount = total) }
        }
        return value
    }
}

object SecureChannelConfig {
    val configured: Boolean get() = BuildConfig.POJIAN_SECURE_ENDPOINT.isNotEmpty()
    // A changed settings URL must not silently switch a pinned build back to plaintext payloads.
    fun applies(@Suppress("UNUSED_PARAMETER") config: TextServiceConfig): Boolean = configured
    internal fun requirePinnedEndpoint(actual: String, expected: String) {
        if (expected.isBlank() || actual.trimEnd('/') != expected.trimEnd('/'))
            throw SecurityAttestationException("服务地址与可信端点不一致，已阻断上传", "ENDPOINT_PIN_MISMATCH")
    }
    // Gateway bearer-token rotation must not reset this installation's rollback checkpoint.
    fun identity(config: TextServiceConfig) = AttestedPayloadCipher.hash(config.endpoint.trimEnd('/') + "|" + BuildConfig.POJIAN_ATTESTATION_PUBLIC_KEY)
    fun validate(config: TextServiceConfig) {
        if (!applies(config) || BuildConfig.POJIAN_ATTESTATION_PUBLIC_KEY.isEmpty() || BuildConfig.POJIAN_CODE_MEASUREMENT.isEmpty())
            throw SecurityAttestationException("可信服务公钥或程序度量未配置", "TRUST_NOT_CONFIGURED")
        requirePinnedEndpoint(config.endpoint, BuildConfig.POJIAN_SECURE_ENDPOINT)
        EndpointPolicy.validate(config.endpoint, allowLocalHttp = false)
        require(config.token.isNotBlank()) { "请配置服务凭据" }
    }
}

/** Keystore-encrypted checkpoint and pending ciphertext commit together, before upload. */
internal class SecureChannelStore(context: Context, private val identity: String) {
    private val prefs = context.getSharedPreferences("secure_semantic_channel", Context.MODE_PRIVATE)
    private val cipher = MonitorPayloadCipher(512 * 1024)
    fun read(): JSONObject = prefs.getString(identity, null)?.let {
        try { JSONObject(cipher.open(it, identity)) }
        catch (_: Exception) { throw SecurityAttestationException("本地安全状态无法读取，已阻断上传", "LOCAL_STATE_UNAVAILABLE") }
    } ?: JSONObject().put("client_id", AttestedPayloadCipher.randomHex(16)).put("version", 0).put("hash", "0".repeat(64)).also(::write)
    fun write(state: JSONObject) {
        check(prefs.edit().putString(identity, cipher.seal(state.toString(), identity)).commit()) { "安全状态保存失败" }
    }
}

class SecureSemanticClient(context: Context) {
    private val context = context.applicationContext
    private fun identity(config: TextServiceConfig) = SecureChannelConfig.identity(config)

    suspend fun probe(config: TextServiceConfig): Unit = withContext(Dispatchers.IO) {
        try {
            SecureChannelTelemetry.select(identity(config))
            SecureChannelConfig.validate(config)
            mutex.withLock {
                val state = SecureChannelStore(context, identity(config)).read()
                val session = handshake(config, state.getString("client_id")) { true }
                session.requestKey.fill(0); session.responseKey.fill(0)
                publish(state, session.measurement, config)
            }
        } catch (error: SecurityAttestationException) { SecureChannelTelemetry.blocked(error); throw error }
    }

    suspend fun analyze(config: TextServiceConfig, payload: JSONObject,
        isCurrent: () -> Boolean = { true }): JSONObject = withContext(Dispatchers.IO) {
        try {
            SecureChannelTelemetry.select(identity(config))
            SecureChannelConfig.validate(config)
            mutex.withLock {
                val store = SecureChannelStore(context, identity(config))
                val state = store.read()
                val digest = AttestedPayloadCipher.hash(payload.toString())
                // A pending upload can have committed remotely before a network interruption.
                // Replay that exact envelope/key before ever allocating the next state version.
                state.optJSONObject("pending")?.let { pending ->
                    complete(config, store, state, pending, isCurrent)
                }
                requireCurrentMonitorInput(isCurrent())
                if (state.optString("last_digest") == digest && state.has("last_result"))
                    return@withLock state.getJSONObject("last_result")
                requireCurrentMonitorInput(isCurrent())
                val session = handshake(config, state.getString("client_id"), isCurrent)
                publish(state, session.measurement, config)
                val previous = ChannelState(state.getLong("version"), state.getString("hash"))
                val requestId = AttestedPayloadCipher.randomHex(16)
                val (iv, ciphertext) = AttestedPayloadCipher.encrypt(session.requestKey, payload.toString(),
                    AttestedPayloadCipher.requestAad(session, requestId, previous))
                session.requestKey.fill(0)
                val envelope = JSONObject().put("session_id", session.id).put("client_id", session.clientId)
                    .put("request_id", requestId).put("client_ephemeral_pubkey", session.clientPublicKey)
                    .put("expected_state_version", previous.version).put("expected_state_hash", previous.hash)
                    .put("iv_base64", iv).put("ciphertext_base64", ciphertext)
                val pending = JSONObject().put("request", envelope).put("response_key", AttestedPayloadCipher.b64(session.responseKey))
                    .put("digest", digest).put("measurement", session.measurement)
                    .put("validation", JSONObject().put("work_id", payload.getString("work_id"))
                        .put("source_text", payload.getString("clean_text"))
                        .put("history_ids", org.json.JSONArray().apply {
                            val facts = payload.optJSONArray("history_facts") ?: org.json.JSONArray()
                            repeat(facts.length()) { put(facts.getJSONObject(it).getString("fact_id")) }
                        }))
                session.responseKey.fill(0)
                state.remove("last_result"); state.remove("last_digest"); state.remove("last_rejected_result")
                state.put("pending", pending)
                store.write(state)
                complete(config, store, state, pending, isCurrent)
            }
        } catch (error: SecurityAttestationException) { SecureChannelTelemetry.blocked(error); throw error }
    }

    private suspend fun complete(config: TextServiceConfig, store: SecureChannelStore, state: JSONObject,
        pending: JSONObject, isCurrent: () -> Boolean): JSONObject {
        val request = pending.getJSONObject("request")
        val response = post(config, "/api/v1/analyze", request, isCurrent)
        val decision = SecureChannelReceipt.apply(state, pending, response)
        store.write(state)
        publish(state, pending.getString("measurement"), config)
        decision.error?.let { throw it }
        return requireNotNull(decision.result)
    }

    private fun publish(state: JSONObject, measurement: String, config: TextServiceConfig) = SecureChannelTelemetry.update {
        it.copy(verified = true, measurement = measurement, version = state.getLong("version"),
            stateHash = state.getString("hash"), blocked = null, serviceIdentity = identity(config))
    }

    private suspend fun handshake(config: TextServiceConfig, clientId: String, isCurrent: () -> Boolean): AttestedSession {
        val key = AttestedPayloadCipher.ephemeralKey()
        val nonce = AttestedPayloadCipher.randomHex(32)
        val request = JSONObject().put("client_id", clientId).put("client_nonce", nonce)
            .put("client_ephemeral_pubkey", AttestedPayloadCipher.publicKey(key))
        val response = post(config, "/api/v1/attestation", request, isCurrent)
        return AttestedPayloadCipher.attest(clientId, nonce, key, response, BuildConfig.POJIAN_ATTESTATION_PUBLIC_KEY,
            BuildConfig.POJIAN_CODE_MEASUREMENT, System.currentTimeMillis())
    }

    private suspend fun post(config: TextServiceConfig, path: String, payload: JSONObject,
        isCurrent: () -> Boolean): JSONObject {
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 192 * 1024)
        val connection = (URL(config.endpoint.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 15_000; readTimeout = 135_000
            instanceFollowRedirects = false; useCaches = false
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer ${config.token}")
            setFixedLengthStreamingMode(bytes.size)
        }
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { connection.disconnect() }
            try {
                continuation.context.ensureActive(); requireCurrentMonitorInput(isCurrent())
                connection.outputStream.use { stream ->
                    var offset = 0
                    while (offset < bytes.size) {
                        continuation.context.ensureActive(); requireCurrentMonitorInput(isCurrent())
                        val count = minOf(4096, bytes.size - offset)
                        stream.write(bytes, offset, count)
                        offset += count
                    }
                }
                if (connection.responseCode != 200) {
                    if (connection.responseCode in setOf(401, 409, 410))
                        throw SecurityAttestationException("安全会话无法恢复，已保留本地状态并阻断上传", "SESSION_RECOVERY_REQUIRED")
                    throw IOException("安全服务返回 ${connection.responseCode}")
                }
                val response = connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        continuation.context.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= 256 * 1024) { "安全服务响应超限" }
                        output.write(buffer, 0, count)
                    }
                    output.toString("UTF-8")
                }
                continuation.resume(JSONObject(response))
            } catch (error: Exception) { continuation.resumeWithException(error) }
            finally { connection.disconnect() }
        }
    }

    companion object { private val mutex = Mutex() }
}
