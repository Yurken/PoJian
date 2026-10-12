package com.emerge.app.audioanalysis

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException
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
import org.json.JSONException
import org.json.JSONObject

data class ServiceCapabilities(val monitor: Boolean, val text: Boolean, val vision: Boolean, val retrieval: Boolean)
data class ServiceProbeResult(val message: String, val capabilities: ServiceCapabilities? = null)

/** Read-only gateway diagnostics. Never submits retained material or invokes a model. */
object RemoteServiceProbe {
    private class ProbeFailure(message: String) : IOException(message)
    private const val MAX_RESPONSE_BYTES = 256 * 1024

    suspend fun check(config: TextServiceConfig, allowLocalHttp: Boolean): ServiceProbeResult {
        val endpoint = try { EndpointPolicy.validate(config.endpoint, allowLocalHttp) }
        catch (error: IllegalArgumentException) { return ServiceProbeResult(error.message ?: "服务地址无效") }
        val token = config.token.trim()
        if (token.length > 512 || token.any { it.code !in 33..126 })
            return ServiceProbeResult("访问令牌格式无效")
        var reachable = false
        val started = System.nanoTime()
        return try {
            withTimeout(120_000) {
                val health = getJson("$endpoint/health")
                if (health.optString("status") != "ok" || health.optInt("version") != 1)
                    throw ProbeFailure("服务协议不兼容")
                reachable = true
                if (token.isEmpty()) return@withTimeout ServiceProbeResult("服务可达，未配置访问令牌")
                val service = getJson("$endpoint/status", token).getJSONObject("service")
                if (service.optInt("protocol") != 1) throw ProbeFailure("服务协议不兼容")
                val capabilities = ServiceCapabilities(service.getBoolean("monitor"), service.getBoolean("model_configured"),
                    service.getBoolean("vision_configured"), service.getBoolean("retrieval_configured"))
                val elapsed = (System.nanoTime() - started) / 1_000_000
                ServiceProbeResult("连接与认证成功 · $elapsed ms", capabilities)
            }
        } catch (error: Exception) {
            if (error is CancellationException && error !is TimeoutCancellationException) throw error
            currentCoroutineContext().ensureActive()
            val reason = when (error) {
                is ProbeFailure -> error.message ?: "连接失败"
                is UnknownHostException -> "域名解析失败"
                is SocketTimeoutException, is TimeoutCancellationException -> "连接超时"
                is ConnectException -> "无法连接服务器"
                is SSLException -> "HTTPS 证书校验失败"
                is JSONException -> "服务响应格式不兼容"
                is IOException -> "网络连接失败"
                else -> "连接检查失败"
            }
            ServiceProbeResult(if (reachable) "服务可达，$reason" else reason)
        }
    }

    private suspend fun getJson(url: String, token: String = ""): JSONObject = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 120_000
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Encoding", "identity")
            if (token.isNotEmpty()) setRequestProperty("Authorization", "Bearer $token")
        }
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { connection.disconnect() }
            try {
                continuation.context.ensureActive()
                val status = connection.responseCode
                if (status != HttpURLConnection.HTTP_OK) throw ProbeFailure(when (status) {
                    401, 403 -> "认证失败（$status）"
                    404 -> "服务接口不存在（404）"
                    in 300..399 -> "服务地址发生重定向（$status）"
                    429 -> "服务繁忙或额度受限（429）"
                    in 500..599 -> "服务端异常（$status）"
                    else -> "服务返回 HTTP $status"
                })
                if (connection.contentLengthLong > MAX_RESPONSE_BYTES) throw ProbeFailure("服务响应过大")
                val bytes = connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        continuation.context.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > MAX_RESPONSE_BYTES) throw ProbeFailure("服务响应过大")
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                continuation.context.ensureActive()
                continuation.resume(JSONObject(String(bytes, Charsets.UTF_8)))
            } catch (error: Exception) {
                continuation.resumeWithException(error)
            } finally {
                connection.disconnect()
            }
        }
    }
}
