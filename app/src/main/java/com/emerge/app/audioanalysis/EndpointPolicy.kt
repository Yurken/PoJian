package com.emerge.app.audioanalysis

import java.net.URI
import java.util.Locale

object EndpointPolicy {
    fun validate(value: String, allowLocalHttp: Boolean): String {
        val uri = runCatching { URI(value.trim()) }.getOrElse { throw IllegalArgumentException("服务地址格式不正确", it) }
        require(uri.host != null && uri.userInfo == null && uri.fragment == null && uri.query == null) { "请填写完整服务地址，不要包含账号、查询参数或片段" }
        val host = uri.host.lowercase(Locale.ROOT)
        val octets = host.split('.').takeIf { it.size == 4 }?.mapNotNull { it.toIntOrNull() }
            ?.takeIf { it.size == 4 && it.all { octet -> octet in 0..255 } }
        val privateIpv4 = octets != null &&
            (octets[0] == 10 || octets[0] == 127 ||
                (octets[0] == 192 && octets[1] == 168) ||
                (octets[0] == 172 && octets[1] in 16..31))
        val local = host == "localhost" || host == "[::1]" || privateIpv4
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        require(scheme == "https" || (allowLocalHttp && scheme == "http" && local)) { "请使用 HTTPS；调试版仅允许本机或局域网 HTTP" }
        require(uri.port == -1 || uri.port in 1..65535) { "服务端口不正确" }
        return value.trim().trimEnd('/')
    }
}
