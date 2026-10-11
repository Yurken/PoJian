package com.emerge.app.monitoring

import java.time.Instant
import java.time.ZoneId

object MonitorCoreCases {
    @JvmStatic fun main(args: Array<String>) {
        var count = 0
        fun test(name: String, block: () -> Unit) { block(); println("PASS $name"); count++ }
        val now = Instant.parse("2026-10-08T10:00:00Z").toEpochMilli()
        val evidence = WebEvidence("https://example.org/report", "报告", "统计范围不同", "原始资料：统计范围不同。")
        val finding = MonitorFinding("1", "content", "app", now, "fact", "contradicted", "某具体声明", "口径不一致",
            listOf(evidence), contextToken = "screen")
        val config = MonitorConfig(bubble = true, realtime = true)
        fun allowed(f: MonitorFinding = finding, c: MonitorConfig = config, token: String = "screen", time: Long = now,
            hour: Int = 12, used: Int = 0, last: Long? = null) = MonitorPolicy.mayAlert(f, c, token, time, hour, used, last)
        test("valid sourced contradiction alerts") { check(allowed()) }
        test("next screen suppresses stale verdict") { check(!allowed(token = "next")) }
        test("recreated screen scope cannot alert a recovered verdict even with identical content") {
            val scope = MonitorScreenScope()
            val originalToken = scope.token("app", 7, 0, "同一份屏幕原文")
            val originalFinding = finding.copy(contextToken = originalToken)
            check(allowed(originalFinding, token = scope.token("app", 7, 0, "同一份屏幕原文")))
            check(!allowed(originalFinding, token = MonitorScreenScope().token("app", 7, 0, "同一份屏幕原文")))
            check(!allowed(originalFinding, token = scope.token("app", 7, 1, "同一份屏幕原文")))
        }
        test("unbound result never auto alerts") { check(!allowed(finding.copy(contextToken = ""))) }
        test("future result is rejected") { check(!allowed(time = now - 1)) }
        test("old screen result is retained but not alerted") { check(!allowed(time = now + 120001)) }
        test("daily cap applies") { check(!allowed(used = 6)) }
        test("zero cap disables proactive alerts") { check(!allowed(c = config.copy(dailyAlerts = 0))) }
        test("cooldown applies") { check(!allowed(last = now - 1000)) }
        test("clock rollback does not defeat cooldown") { check(!allowed(last = now + 1000)) }
        test("cooldown expires") { check(allowed(last = now - 1_800_000)) }
        test("disabled bubble") { check(!allowed(c = config.copy(bubble = false))) }
        test("disabled realtime") { check(!allowed(c = config.copy(realtime = false))) }
        test("no evidence no fake alert") { check(!allowed(finding.copy(sources = emptyList()))) }
        test("mismatched quotation rejected") { check(!allowed(finding.copy(sources = listOf(evidence.copy(quote = "虚构引文"))))) }
        test("javascript URL rejected") { check(!evidence.copy(url = "javascript:alert(1)").valid()) }
        test("credential URL rejected") { check(!evidence.copy(url = "https://key@example.org").valid()) }
        test("http evidence rejected") { check(!evidence.copy(url = "http://example.org").valid()) }
        test("unknown and opinions stay quiet") { listOf("insufficient", "not_checked", "opinion", "supported", "conflicting").forEach { check(!allowed(finding.copy(verdict = it))) } }
        test("overnight quiet start") { check(!allowed(c = config.copy(quiet = true), hour = 22)) }
        test("overnight quiet morning") { check(!allowed(c = config.copy(quiet = true), hour = 7)) }
        test("quiet end exclusive") { check(allowed(c = config.copy(quiet = true), hour = 8)) }
        test("daytime quiet") { check(!allowed(c = config.copy(quiet = true, quietStart = 9, quietEnd = 17))) }
        test("equal quiet hours means all day") { check(!allowed(c = config.copy(quiet = true, quietStart = 9, quietEnd = 9))) }
        test("settings bounds") { val c = config.copy(dailyAlerts = -3, frameIntervalSeconds = 1).bounded(); check(c.dailyAlerts == 0 && c.frameIntervalSeconds == 15) }
        val argsHistory = (0 until 9).map { i -> ObservedArgument("item$i", "app", now - (8 - i) * 43_200_000L,
            "议题", "对象", "维度", "支持", "同一项数据", encounterKey = "encounter$i") }
        test("sustained same scoped stance and evidence") { check(MonitorPolicy.repetition(argsHistory, now, ZoneId.of("UTC")) != null) }
        test("audio fragments are not independent encounters") { check(MonitorPolicy.repetition(argsHistory.map { it.copy(encounterKey = "session") }, now) == null) }
        test("one sitting is insufficient") { check(MonitorPolicy.repetition(argsHistory.map { it.copy(capturedAt = now) }, now) == null) }
        test("novel evidence does not trigger repetition") { check(MonitorPolicy.repetition(argsHistory.mapIndexed { i, a -> a.copy(evidence = "证据$i") }, now) == null) }
        test("unknown attribution excluded") { check(MonitorPolicy.repetition(argsHistory.map { it.copy(uncertain = true) }, now) == null) }
        test("missing topic or dimension cannot trigger a repetition alert") {
            check(MonitorPolicy.repetition(argsHistory.map { it.copy(topic = "") }, now) == null)
            check(MonitorPolicy.repetition(argsHistory.map { it.copy(dimension = "") }, now) == null)
        }
        test("different dimensions remain separate") { check(MonitorPolicy.repetition(argsHistory.mapIndexed { i, a -> a.copy(dimension = "维度$i") }, now) == null) }
        test("conditions remain separate") { check(MonitorPolicy.repetition(argsHistory.mapIndexed { i, a -> a.copy(conditions = "条件$i") }, now) == null) }
        test("opposing stances not collapsed") { check(MonitorPolicy.repetition(argsHistory.mapIndexed { i, a -> a.copy(stance = if (i % 2 == 0) "支持" else "反对") }, now) == null) }
        test("topic alone never establishes a cocoon") { check(MonitorPolicy.repetition(argsHistory.map { it.copy(evidence = "") }, now) == null) }
        test("research mode silences only cocoon hints") { check(!allowed(finding.copy(kind = "cocoon", verdict = "repetition"), config.copy(researchMode = true))); check(allowed(c = config.copy(researchMode = true))) }
        test("cross app claim cooldown shared") { check(MonitorPolicy.alertKey(finding) == MonitorPolicy.alertKey(finding.copy(packageName = "other"))) }
        println("$count/$count core cases passed")
    }
}
