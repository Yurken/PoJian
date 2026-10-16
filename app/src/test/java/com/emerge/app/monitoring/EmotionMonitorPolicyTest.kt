package com.emerge.app.monitoring

import org.junit.Assert.*
import org.junit.Test

class EmotionMonitorPolicyTest {
    private val now = 5_000_000L
    private val config = MonitorConfig(bubble = true, emotion = true)
    private val finding = MonitorFinding("emotion:topic", "event:8", "reader", now - 1_000,
        "emotion", "emotional_exposure", "内容原句", "已知 8；强烈 8", topic = "就业 · 焦虑", contextToken = "screen")
    private fun allowed(value: MonitorFinding = finding, options: MonitorConfig = config, token: String = "screen",
        hour: Int = 12, count: Int = 0, last: Long? = null) =
        MonitorPolicy.mayAlert(value, options, token, now, hour, count, last)

    @Test fun emotionUsesTheExistingScreenFreshnessSilenceAndRateLimits() {
        assertTrue(allowed())
        assertFalse(allowed(options = config.copy(emotion = false)))
        assertFalse(allowed(options = config.copy(bubble = false)))
        assertFalse(allowed(options = config.copy(researchMode = true)))
        assertFalse(allowed(token = "another-screen"))
        assertFalse(allowed(value = finding.copy(contextToken = "")))
        assertFalse(allowed(value = finding.copy(capturedAt = now - 120_001)))
        assertFalse(allowed(value = finding.copy(capturedAt = now + 1)))
        assertFalse(allowed(options = config.copy(quiet = true), hour = 23))
        assertFalse(allowed(count = 6))
        assertFalse(allowed(last = now - 29 * 60_000L))
        assertTrue(allowed(last = now - 30 * 60_000L))
        assertFalse(allowed(value = finding.copy(verdict = "not_checked")))
    }

    @Test fun cooldownSeparatesCategoriesButNotRepeatedWordingForOneTopicCategory() {
        assertEquals(MonitorPolicy.alertKey(finding), MonitorPolicy.alertKey(finding.copy(claim = "另一段相同主题的原句")))
        assertNotEquals(MonitorPolicy.alertKey(finding), MonitorPolicy.alertKey(finding.copy(topic = "就业 · 愤怒")))
    }
}
