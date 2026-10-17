package com.emerge.app.capture

import com.emerge.app.analysis.ArgumentUnit
import com.emerge.app.analysis.CognitiveAnalyzer
import com.emerge.app.analysis.EventSemantics
import com.emerge.app.data.BrowseEvent
import com.emerge.app.data.contentText
import com.emerge.app.ui.StatisticsPeriod
import com.emerge.app.ui.calculateStatistics
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class PlatformTextCleanerTest {
    @Test fun screenshotChromeIsRemovedWithoutDeletingNumericProse() {
        val raw = "欧洲赛场之第一场WSK世界赛 #赛车 #赛车手 广告 展开\n" +
            "未点赞，喜欢16.2万，按钮\n16.2万\n3023\n1.2万\n6452\n相关搜索\n学而思学习机T6国际版"
        assertEquals("欧洲赛场之第一场WSK世界赛 #赛车 #赛车手", PlatformTextCleaner.clean(raw))
        val facts = "2026年比赛共有20名选手。\n2026\n1.2万\n20%\n相关搜索是平台的一项功能。"
        assertEquals(facts, PlatformTextCleaner.clean(facts))
    }

    @Test fun ambiguousProseSuffixAndSearchTopicAreKeptWithoutUiContext() {
        val prose = "这段论证需要进一步 展开\n矩阵按第一行 展开\n请把雨伞 收起\n相关搜索\n搜索技术综述"
        assertEquals(prose, PlatformTextCleaner.clean(prose))
    }

    private val body = "今天分享求职经历，推荐比较不同岗位的要求。"
    private val chrome = listOf("分享", "点赞", "关注", "评论", "按钮", "直播", "推荐", "已关注", "首页", "返回",
        "说点什么…", "展开全文", "图片，第 1 张，共 3 张")

    @Test fun standaloneControlsAndNavigationLeaveOnlyContent() {
        assertEquals(body, PlatformTextCleaner.clean((chrome.take(6) + body + chrome.drop(6)).joinToString("\n")))
        assertEquals("", PlatformTextCleaner.clean(chrome.joinToString("\n")))
    }

    @Test fun controlCountsAndAccessibilityRolesAreRemovedAsWholeLines() {
        listOf("点赞 1.2万", "1.2万赞", "评论(36)", "共36条评论", "收藏：２.３万", "分享, 按钮",
            "点赞，1.2万次，按钮", "关注按钮，未选中", "推荐，已选中，标签", "关注 推荐 同城", "点赞 23 | 分享 2").forEach {
            assertEquals(it, "", PlatformTextCleaner.clean(it))
        }
    }

    @Test fun contentUsingThoseWordsIncludingNumbersAndNegationIsPreservedVerbatim() {
        val lines = listOf(body, "点赞1.2万不能证明招聘消息一定可信。", "评论区的讨论需要核查证据。",
            "关注焦虑的原因，不要只关注结果。", "收藏可以帮助整理学习材料。", "直播带货需要关注产品质量。",
            "推荐算法不等于事实依据。", "这个按钮设计并不方便。", "说点什么并不能解决问题。", "昨天我们讨论了就业焦虑。",
            "分享并不意味着认同，推荐也不是保证。", "“关注”这个词不应从正文里删除。")
        assertEquals(lines.joinToString("\n"), PlatformTextCleaner.clean(lines.joinToString("\n")))
        assertEquals(lines, WorkIdentityExtractor.body(lines.joinToString("\n")))
    }

    @Test fun plainNumbersFractionsAndImageDescriptionsAreNotAssumedToBeCounters() {
        val text = "2026\n1.2万\n20%\n1/3\n图片，一名学生在准备面试\n岗位  招聘要求保持原有间距"
        assertEquals(text, PlatformTextCleaner.clean(text))
    }

    @Test fun knownActionNodesAreExcludedWithoutDroppingContentNodesOrAllClickableText() {
        assertEquals("", PlatformTextCleaner.nodeText("12", "点击向朋友分享", "app:id/share_button"))
        assertEquals(body, PlatformTextCleaner.nodeText(body, "按钮", "app:id/desc"))
        assertEquals(body, PlatformTextCleaner.nodeText(body, body, "app:id/article_text"))
        assertEquals("我推荐这本书", PlatformTextCleaner.nodeText("我推荐这本书", "", "app:id/comment_text"))
    }

    @Test fun cleaningIsIdempotentAndKeepsEvidenceInsideSurvivingLines() {
        val text = "分享\n  $body  \n点赞 20\n推荐算法不等于事实依据。\n"
        val cleaned = PlatformTextCleaner.clean(text)
        assertEquals(cleaned, PlatformTextCleaner.clean(cleaned))
        assertTrue(cleaned.contains("推荐比较不同岗位的要求"))
        assertTrue(text.contains(body))
    }

    @Test fun legacyDisplayKeepsStoredSnapshotAnalysisAndStatisticsUnchanged() {
        val raw = "分享\n$body\n点赞 20\n推荐"
        val event = BrowseEvent(1, "reader", 1000, 32, raw, endTimestamp = 5000, durationMillis = 4000,
            sampleCount = 2, workKey = "work-a")
        val semantics = EventSemantics(1, raw, 6000, listOf(ArgumentUnit(body, "求职", "岗位", "要求", "比较")))
        val before = calculateStatistics(listOf(event), StatisticsPeriod.DAY, 10_000, ZoneId.of("UTC"))
        assertEquals(body, event.contentText)
        assertEquals(raw, event.visibleText)
        assertTrue(semantics.validFor(event))
        assertEquals(before, calculateStatistics(listOf(event), StatisticsPeriod.DAY, 10_000, ZoneId.of("UTC")))
    }

    @Test fun audioTranscriptsAreNotTreatedAsApplicationControls() {
        for (source in listOf("audio_playback", "audio_import")) {
            val event = BrowseEvent(1, "reader", 1, 0, "分享\n推荐\n$body", sourceType = source)
            assertEquals(event.visibleText, event.contentText)
        }
        for (source in listOf("accessibility", "screen_ocr", "visual_model")) {
            assertEquals(body, BrowseEvent(1, "reader", 1, 0, "分享\n$body\n推荐", sourceType = source).contentText)
        }
    }

    @Test fun identityAndGalleryMetadataSurviveWhileButtonUpdatesDoNotSplitAVisit() {
        val tracker = CaptureSessionTracker()
        val nodes = listOf(IdentityNode("note_title", body), IdentityNode("nickname", "作者甲"))
        val raw = "作者甲\n关注\n图片，第1张，共3张\n$body\n点赞 12"
        val hint = WorkIdentityExtractor.extract("com.xingin.xhs", raw, nodes)
        assertEquals("作者甲", hint.author)
        assertEquals(3, hint.galleryTotal)
        val first = tracker.sample("com.xingin.xhs", PlatformTextCleaner.clean(raw), 1000, 1000, 32, hint)
        tracker.saved(first, 1)
        val second = tracker.sample("com.xingin.xhs", PlatformTextCleaner.clean(raw.replace("点赞 12", "点赞 13")),
            3000, 3000, 32, hint)
        assertTrue(second.continuing)
        assertEquals(first.work.workKey, second.work.workKey)
        assertEquals(2000L, second.event.durationMillis)
        assertFalse(second.event.visibleText.contains("点赞"))
    }

    @Test fun localAnalysisRetainsRealArgumentAboutLikeCounts() {
        val quote = "招聘视频点赞1.2万并不意味着岗位信息一定可靠"
        val event = BrowseEvent(1, "reader", 1000, 32, "分享\n$quote\n评论 18\n推荐")
        val report = CognitiveAnalyzer().analyze(listOf(event), StatisticsPeriod.DAY, 10_000, ZoneId.of("UTC"))
        assertTrue(report.claims.any { it.quote == quote })
    }
}
