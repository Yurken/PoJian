package com.emerge.app.data

import java.net.URI
import java.net.URLEncoder

/** Original work links and explicitly identified title-search fallbacks are separate. */
object OriginalLinks {
    private val hosts = mapOf(
        "com.ss.android.ugc.aweme" to setOf("douyin.com", "iesdouyin.com"),
        "com.xingin.xhs" to setOf("xiaohongshu.com", "xhslink.com"),
        "com.sina.weibo" to setOf("weibo.com", "weibo.cn"),
        "com.tencent.mm" to setOf("mp.weixin.qq.com", "channels.weixin.qq.com"),
        "com.smile.gifmaker" to setOf("kuaishou.com", "gifshow.com", "chenzhongtech.com"),
        "tv.danmaku.bili" to setOf("bilibili.com", "b23.tv"),
        "com.zhihu.android" to setOf("zhihu.com")
    )
    private fun uri(value: String): URI? = runCatching { URI(value) }.getOrNull()?.takeIf {
        it.scheme == "https" && it.userInfo == null && it.port in setOf(-1, 443) && value.length <= 4096 &&
            !it.host.isNullOrBlank()
    }
    private fun belongs(host: String, domains: Set<String>) = domains.any { host == it || host.endsWith(".$it") }
    fun supports(packageName: String) = packageName in hosts
    fun allowed(value: String, packageName: String): Boolean {
        val parsed = uri(value) ?: return false
        val host = parsed.host.lowercase()
        if (!belongs(host, hosts[packageName].orEmpty())) return false
        val path = parsed.path.orEmpty()
        return when (packageName) {
            "com.ss.android.ugc.aweme" -> if (host == "v.douyin.com") path.matches(Regex("/[A-Za-z0-9_-]+/?"))
                else path.matches(Regex("/(?:video|note|share/video)/[0-9]{5,30}/?"))
            "com.xingin.xhs" -> if (belongs(host, setOf("xhslink.com"))) path.length > 1
                else path.matches(Regex("/(?:explore|discovery/item)/[a-fA-F0-9]{24}/?"))
            "com.sina.weibo" -> path.matches(Regex("/(?:[0-9]+/[A-Za-z0-9]+|detail/[0-9]+|status/[A-Za-z0-9]+)/?"))
            "com.tencent.mm" -> if (host == "mp.weixin.qq.com") path.startsWith("/s/") || path == "/s"
                else path.startsWith("/web/pages/feed")
            "com.smile.gifmaker" -> path.matches(Regex("/(?:short-video|fw/photo|f|s)/[A-Za-z0-9_-]+/?"))
            "tv.danmaku.bili" -> if (host == "b23.tv") path.matches(Regex("/[A-Za-z0-9]+/?"))
                else path.matches(Regex("/video/(?:BV[A-Za-z0-9]+|av[0-9]+)/?"))
            "com.zhihu.android" -> path.matches(Regex("/(?:question/[0-9]+(?:/answer/[0-9]+)?|p/[0-9]+)/?"))
            else -> false
        }
    }
    /** Keep signed query parameters: platforms can require them to open a shared work. */
    fun fromInput(input: String, packageName: String): String? {
        if (input.length > 16000) return null
        val links = Regex("https://[^\\s<>\"，。；！）)]+", RegexOption.IGNORE_CASE).findAll(input)
            .map { it.value.trimEnd('.', ',', ';', '!', ']', '}', '】') }.distinct().toList()
        return links.singleOrNull()?.takeIf { allowed(it, packageName) }
    }
    fun forWork(work: ObservedWork): String? = when {
        work.packageName == "com.ss.android.ugc.aweme" && work.nativeId.matches(Regex("[0-9]{5,30}")) ->
            "https://www.douyin.com/video/${work.nativeId}"
        work.packageName == "com.xingin.xhs" && work.nativeId.matches(Regex("[a-fA-F0-9]{24}")) ->
            "https://www.xiaohongshu.com/explore/${work.nativeId}"
        else -> null
    }

    private val searches = mapOf(
        "com.ss.android.ugc.aweme" to "snssdk1128://search/keyword?keyword=",
        "com.xingin.xhs" to "xhsdiscover://search/result?keyword=",
        "tv.danmaku.bili" to "bilibili://search?keyword=",
        "com.zhihu.android" to "zhihu://search?q=",
        "com.smile.gifmaker" to "kwai://search?keyword="
    )

    /** A search opens matching results, never claims to identify a particular original. */
    fun searchFallback(packageName: String, title: String): String? {
        val prefix = searches[packageName] ?: return null
        val query = com.emerge.app.capture.CloudTextPrivacy.sanitize(title.trim()).first
        if (query.isBlank() || query.length > 300 || query.contains(com.emerge.app.capture.CloudTextPrivacy.MASK)) return null
        return prefix + URLEncoder.encode(query, "UTF-8")
    }

    fun isSearchFallback(value: String, packageName: String): Boolean {
        val prefix = searches[packageName] ?: return false
        if (!value.startsWith(prefix)) return false
        val encoded = value.removePrefix(prefix)
        if (encoded.isEmpty() || encoded.length > 2700 || !encoded.matches(Regex("(?:[A-Za-z0-9.*_+\\-]|%[0-9a-fA-F]{2})+"))) return false
        val decoded = runCatching { java.net.URLDecoder.decode(encoded, "UTF-8") }.getOrNull() ?: return false
        return searchFallback(packageName, decoded) == value
    }

    fun forCapturedWork(work: ObservedWork): WorkOriginal? = forWork(work)?.let {
        WorkOriginal(work.workKey, it, "platform_id", work.firstSeenAt)
    } ?: searchFallback(work.packageName, work.title)?.let {
        WorkOriginal(work.workKey, it, "platform_search", work.firstSeenAt)
    }
    /** Browser navigation may reach platform login/CDN pages, but must stay HTTPS. */
    fun canNavigate(value: String): Boolean = uri(value)?.let { parsed ->
        val host = parsed.host.lowercase()
        belongs(host, hosts.values.flatten().toSet() + setOf("passport.weibo.cn", "open.weixin.qq.com"))
    } == true
}
