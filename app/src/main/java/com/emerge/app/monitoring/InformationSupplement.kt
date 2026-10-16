package com.emerge.app.monitoring

import com.emerge.app.audioanalysis.EndpointPolicy
import com.emerge.app.audioanalysis.TextServiceConfig
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

data class SupplementKnownArgument(val quote: String, val evidence: String = "")
data class SupplementRequest(val scopeKey: String, val topic: String, val subject: String = "",
    val dimension: String = "", val conditions: String = "", val known: List<SupplementKnownArgument> = emptyList())
data class SupplementResult(val sources: List<WebEvidence>)
data class InformationSupplementState(val loading: Boolean = true, val sources: List<WebEvidence> = emptyList(),
    val error: String? = null)

/** Once invalidated, restoring the old settings cannot revive an earlier network reply. */
class SupplementScopeGuard(private val request: SupplementRequest, private val config: TextServiceConfig,
    private val authorizationKey: String) {
    private val cancelled = AtomicBoolean(false)
    fun cancel() { cancelled.set(true) }
    fun isCurrent(request: SupplementRequest, config: TextServiceConfig, authorizationKey: String): Boolean {
        if (this.request != request || this.config != config || this.authorizationKey != authorizationKey || !config.enabled)
            cancel()
        return !cancelled.get()
    }
}

object InformationSupplementProtocol {
    private fun bounded(value: String, maximum: Int, required: Boolean = false): Boolean =
        value.length <= maximum && (!required || value.isNotBlank()) &&
            value.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) == value

    fun encode(request: SupplementRequest): JSONObject {
        require(bounded(request.scopeKey, 160, true) && bounded(request.topic, 80, true) &&
            bounded(request.subject, 100) && bounded(request.dimension, 80) && bounded(request.conditions, 200) &&
            listOf(request.scopeKey, request.topic, request.subject, request.dimension, request.conditions)
                .all { value -> value.none { it.code < 32 } } && request.known.size <= 6 &&
            request.known.all { bounded(it.quote, 500, true) && bounded(it.evidence, 500) }) { "补全材料超出范围" }
        return JSONObject().put("version", 1).put("scope_key", request.scopeKey).put("topic", request.topic)
            .put("subject", request.subject).put("dimension", request.dimension).put("conditions", request.conditions)
            .put("known", JSONArray().apply { request.known.forEach { put(JSONObject().put("quote", it.quote)
                .put("evidence", it.evidence)) } })
    }

    fun sourceKey(url: String): String? = runCatching {
        require(url.length <= 2000 && url.none { it.code < 33 })
        val uri = URI(url)
        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return null
        require(uri.scheme == "https" && uri.rawUserInfo == null && uri.port in listOf(-1, 443))
        require('.' in host && ':' !in host && !host.endsWith(".localhost") && !host.endsWith(".local") && !host.endsWith(".internal"))
        // No DNS lookup is performed while validating an external link.
        val octets = host.split('.').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 4 }
        if (octets == null) require(host.split('.').any { !it.matches(Regex("(?:[0-9]+|0x[0-9a-f]+)")) })
        if (octets != null) require(octets.joinToString(".") == host && octets.all { it in 0..255 } && octets[0] !in listOf(0, 10, 127) &&
            octets[0] < 224 && !(octets[0] == 169 && octets[1] == 254) &&
            !(octets[0] == 172 && octets[1] in 16..31) && !(octets[0] == 192 && octets[1] == 168) &&
            !(octets[0] == 100 && octets[1] in 64..127) &&
            !(octets[0] == 192 && octets[1] == 0 && octets[2] in listOf(0, 2)) &&
            !(octets[0] == 198 && octets[1] in 18..19) &&
            !(octets[0] == 198 && octets[1] == 51 && octets[2] == 100) &&
            !(octets[0] == 203 && octets[1] == 0 && octets[2] == 113))
        "https://$host${uri.rawPath.orEmpty().ifEmpty { "/" }}${uri.rawQuery?.let { "?$it" }.orEmpty()}"
    }.getOrNull()

    fun decode(payload: JSONObject, request: SupplementRequest): SupplementResult {
        require(payload.opt("version") == 1 && payload.opt("scope_key") == request.scopeKey &&
            payload.opt("topic") == request.topic && payload.opt("source_kind") == "external") { "补全响应范围不匹配" }
        val rows = payload.optJSONArray("sources") ?: throw IllegalArgumentException("补全响应无效")
        require(rows.length() <= 4) { "补全响应超限" }
        val urls = mutableSetOf<String>()
        val bodies = mutableSetOf<String>()
        val sources = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            fun field(key: String): String = row.opt(key) as? String ?: throw IllegalArgumentException("补全响应无效")
            val source = WebEvidence(field("url"), field("title"), field("quote"), field("excerpt"))
            val key = sourceKey(source.url)
            require(key != null && urls.add(key) && bodies.add(source.excerpt) && source.valid() &&
                bounded(source.title, 200, true) && bounded(source.quote, 1000, true) &&
                bounded(source.excerpt, 6000, true)) { "补全来源校验失败" }
            source
        }
        require(payload.opt("status") == if (sources.isEmpty()) "empty" else "ready") { "补全响应无效" }
        return SupplementResult(sources)
    }
}

/** A request exists only for an explicit user action. Nothing is persisted as browsed content. */
object InformationSupplementClient {
    private class ServiceFailure(message: String) : IOException(message)
    private const val MAX_RESPONSE_BYTES = 128 * 1024

    suspend fun retrieve(request: SupplementRequest, config: TextServiceConfig, allowLocalHttp: Boolean,
        isCurrent: () -> Boolean, onRequest: () -> Unit = {},
        openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }): SupplementResult {
        require(config.enabled) { "请先开启云端分析" }
        val endpoint = EndpointPolicy.validate(config.endpoint, allowLocalHttp)
        require(config.token.isNotEmpty() && config.token.length <= 512 && config.token.all { it.code in 33..126 }) {
            "访问令牌无效"
        }
        val bytes = InformationSupplementProtocol.encode(request).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 32 * 1024) { "补全材料超出范围" }
        return withTimeout(55_000) {
            val json = post("$endpoint/information-supplement", config.token, bytes, isCurrent, onRequest, openConnection)
            currentCoroutineContext().ensureActive()
            if (!isCurrent()) throw CancellationException("Supplement scope changed")
            InformationSupplementProtocol.decode(json, request)
        }
    }

    fun errorMessage(error: Exception): String = when (error) {
        is ServiceFailure -> error.message ?: "补充资料失败"
        is SocketTimeoutException, is TimeoutCancellationException -> "补充资料超时"
        is IllegalArgumentException -> error.message ?: "补全响应无效"
        is IOException -> "网络连接失败"
        else -> "补充资料失败"
    }

    private suspend fun post(url: String, token: String, bytes: ByteArray, isCurrent: () -> Boolean,
        onRequest: () -> Unit, openConnection: (URL) -> HttpURLConnection): JSONObject = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) throw CancellationException("Supplement scope changed")
        val connection = openConnection(URL(url)).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 45_000
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("Authorization", "Bearer $token")
            setFixedLengthStreamingMode(bytes.size)
        }
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { connection.disconnect() }
            try {
                continuation.context.ensureActive()
                if (!isCurrent()) throw CancellationException("Supplement scope changed")
                onRequest()
                connection.outputStream.use { it.write(bytes) }
                val status = connection.responseCode
                if (status != 200) throw ServiceFailure(when (status) {
                    401, 403 -> "认证失败"
                    404 -> "服务暂不支持补充资料"
                    429 -> "服务繁忙或额度受限"
                    503 -> "检索服务未配置"
                    408, 504 -> "补充资料超时"
                    else -> "补充资料失败"
                })
                if (connection.contentLengthLong > MAX_RESPONSE_BYTES) throw ServiceFailure("补全响应超限")
                val output = connection.inputStream.use { input ->
                    val result = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        continuation.context.ensureActive()
                        if (!isCurrent()) throw CancellationException("Supplement scope changed")
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (result.size() + count > MAX_RESPONSE_BYTES) throw ServiceFailure("补全响应超限")
                        result.write(buffer, 0, count)
                    }
                    result.toByteArray()
                }
                continuation.context.ensureActive()
                if (!isCurrent()) throw CancellationException("Supplement scope changed")
                continuation.resume(JSONObject(output.toString(Charsets.UTF_8)))
            } catch (error: Exception) {
                continuation.resumeWithException(error)
            } finally {
                connection.disconnect()
            }
        }
    }
}
