package com.emerge.app.capture

/** Deterministic, local masking before any cloud request. This is not general anonymisation. */
object CloudTextPrivacy {
    const val MASK = "[端侧已脱敏]"
    private val notification = Regex("(?m)^(?:微信|QQ|短信|通知|验证码|通知栏|系统通知)\\s*[:：].*$")
    private val entities = listOf(
        Regex("(?<![0-9])[0-9]{17}[0-9Xx](?![0-9])"),
        Regex("(?<![0-9])(?:\\+86[ -]?)?1[3-9][0-9]{9}(?![0-9])"),
        Regex("(?<![A-Za-z0-9._%+\\-])[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}(?![A-Za-z0-9._%+\\-])")
    )

    fun sanitize(text: String): Pair<String, Int> {
        var count = 0
        var cleaned = notification.replace(text) { count++; MASK }
        entities.forEach { pattern -> cleaned = pattern.replace(cleaned) { count++; MASK } }
        return cleaned to count
    }
}
