package com.emerge.app.analysis

import org.junit.Assert.*
import org.junit.Test

class ViewpointLabelerTest {
    @Test fun sentencesBecomeShortPhrasesInsteadOfTruncatedQuotes() {
        val cases = mapOf(
            "由于觉得zotero的UI很丑且使用不方便，我用codex做了工具" to "工具难用",
            "当你学会在Zotero实现一键双语翻译" to "双语阅读",
            "可以呀，他翻译的时候会消耗API吗" to "翻译成本",
            "丁香花结了又结，人生焦虑的课题换了又换" to "人生焦虑",
            "坚持天天爬楼，就有收获" to "坚持成长",
            "我不会为任何一个人和事放弃我的前途" to "前途优先",
            "我就想今天能赚多少，能不能破3000一天" to "收入期待",
            "虽然产品有很多质量问题，但相信会改善短板" to "质量改善期待",
            "两个时序变量只有在同一频率和同一尺度下才会相关吗" to "相关条件",
            "机器人终于学会用历史记忆了" to "机器人记忆",
            "#留学上课日常 #美国 #搞笑" to "未识别"
        )
        cases.forEach { (quote, expected) ->
            val label = ViewpointTimeline.label(quote)
            assertEquals(expected, label)
            assertTrue(label.length <= 8)
            assertFalse(label.contains("…"))
        }
    }
    @Test fun oppositeAndUnknownStatementsAreNotForcedIntoTheSameViewpoint() {
        assertNotEquals(ViewpointTimeline.label("女性应该独立生活"), ViewpointTimeline.label("女性不应该独立生活"))
        assertNotEquals(ViewpointTimeline.label("求职焦虑"), ViewpointTimeline.label("求职不用焦虑"))
        assertEquals("未识别", ViewpointTimeline.label("需要讨论一个尚无明确含义的新问题"))
        assertEquals(ViewpointTimeline.label("Zotero界面很丑，使用不方便"), ViewpointTimeline.label("软件界面繁琐，使用难用"))
    }
}
