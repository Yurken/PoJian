package com.emerge.app.capture

import org.junit.Assert.*
import org.junit.Test

class WorkIdentityTest {
    private val tracker = CaptureSessionTracker()
    private fun sample(text: String, hint: WorkHint, time: Long = 1000, pkg: String = "com.xingin.xhs") =
        tracker.sample(pkg, text, 100_000 + time, time, 4096, hint)

    @Test fun sameTitleKeepsOneVisitWhenTheBodyChangesCompletely() {
        val hint = WorkHint("NUS harness skills实现智能体原生全模态", "乌萨奇今天读paper了吗")
        val first = sample("第一张图片，论文背景与标题", hint)
        tracker.saved(first, 12)
        val next = sample("Motivation：现实任务很少只涉及一种模态", hint, 3000)
        assertTrue(next.continuing)
        assertEquals(12L, next.event.id)
        assertEquals(first.event.workKey, next.event.workKey)
        assertEquals(2000L, next.event.durationMillis)
        assertTrue(next.event.visibleText.contains("论文背景"))
        assertTrue(next.event.visibleText.contains("Motivation"))
    }

    @Test fun forwardGalleryChangeMergesWithoutStableTitle() {
        val first = sample("图片1文字", WorkHint(author = "作者甲", galleryIndex = 1, galleryTotal = 4))
        tracker.saved(first, 12)
        val second = sample("完全不同的图片2文字", WorkHint(author = "作者甲", galleryIndex = 2, galleryTotal = 4), 3000)
        assertTrue(second.continuing)
        assertEquals(first.work.workKey, second.work.workKey)
        assertEquals("session_estimate", second.event.identityMethod)
    }

    @Test fun authorAloneAndResetToCoverCannotMergeDifferentWorks() {
        tracker.saved(sample("旧作品图片2", WorkHint(author = "作者甲", galleryIndex = 2, galleryTotal = 4)), 12)
        assertFalse(sample("另一作品图片1", WorkHint(author = "作者甲", galleryIndex = 1, galleryTotal = 4), 3000).continuing)
        assertFalse(sample("另一篇正文", WorkHint(author = "作者甲"), 3000).continuing)
    }

    @Test fun explicitDifferentTitleOverridesNearlyIdenticalBody() {
        tracker.saved(sample("共同的界面内容", WorkHint("第一篇至少八个字的标题", "同作者")), 12)
        assertFalse(sample("共同的界面内容", WorkHint("第二篇至少八个字的标题", "同作者"), 3000).continuing)
    }

    @Test fun returningToKnownWorkStartsAnotherVisitWithSameWorkKey() {
        val hint = WorkHint("一个有稳定标题的作品", "作者甲")
        val first = sample("正文", hint)
        tracker.saved(first, 12)
        tracker.reset()
        val returned = sample("正文", hint, 10_000)
        assertFalse(returned.continuing)
        assertEquals(first.work.workKey, returned.work.workKey)
        assertEquals(0L, returned.event.durationMillis)
    }

    @Test fun titleAndAuthorCannotMergeAcrossPlatforms() {
        val hint = WorkHint("一个有稳定标题的作品", "作者甲")
        assertNotEquals(hint.key("com.xingin.xhs"), hint.key("com.ss.android.ugc.aweme"))
    }

    @Test fun extractsImagePositionAndVisibleAuthorWithoutTreatingImageTextAsTitle() {
        val hint = WorkIdentityExtractor.extract("com.xingin.xhs",
            "乌萨奇今天读paper了吗\n关注\n图片,第1张,共4张,双指左划或右划即可查看更多内容\nNUS论文第一页")
        assertEquals("乌萨奇今天读paper了吗", hint.author)
        assertEquals(1, hint.galleryIndex)
        assertEquals(4, hint.galleryTotal)
        assertNull(hint.key("com.xingin.xhs"))
    }

    @Test fun onlyExplicitSupportedResourceFieldsProvideTitleIdentity() {
        val nodes = listOf(IdentityNode("com.xingin.xhs:id/note_title", "一篇可用于识别的稳定标题"),
            IdentityNode("com.xingin.xhs:id/nickname", "作者甲"))
        assertNotNull(WorkIdentityExtractor.extract("com.xingin.xhs", "", nodes).key("com.xingin.xhs"))
        assertNull(WorkIdentityExtractor.extract("com.other", "", nodes).key("com.other"))
    }
    @Test fun realSingleImagePostKeepsVisitDespiteCountersAndTemporaryMissingRoot() {
        val original = "不写bug\n关注\n图片,第1张,共1张,双指左划或右划即可查看更多内容\n更多内容\n神经网络 GNN CNN 创新创新点 创新思路\n创新点 创新思路，代码改进。\n点赞7\n收藏2\n评论1"
        val first = tracker.sample("com.xingin.xhs", original, 100000, 1000, 4096)
        tracker.saved(first, 12)
        val changed = original.replace("点赞7", "点赞8")
        val second = tracker.sample("com.xingin.xhs", changed, 102000, 3000, 4096)
        assertTrue(second.continuing)
        assertEquals(2000L, second.event.durationMillis)
        tracker.saved(second, 12)
        tracker.pause()
        val resumed = tracker.sample("com.xingin.xhs", changed, 110000, 11000, 4096)
        assertTrue(resumed.continuing)
        assertEquals(12L, resumed.event.id)
        assertEquals(2000L, resumed.event.durationMillis)
    }

    @Test fun recommendationCardsAreNotASingleConsumedWork() {
        val feed = "笔记 hit跳车or留下？ 来自一条咸鱼 8赞\n一条咸鱼\n8\n笔记 没有头绪的宝放心好啦 来自师姐 1赞\n视频 宿舍小锅吃什么 来自日食记 1408赞"
        assertTrue(WorkIdentityExtractor.isRecommendationList("com.xingin.xhs", feed))
        assertFalse(WorkIdentityExtractor.isRecommendationList("com.other", feed))
        assertFalse(WorkIdentityExtractor.isRecommendationList("com.xingin.xhs", "作者\n关注\n这是一篇完整的笔记正文"))
    }

    @Test fun pauseCannotMergeAnotherPostOrHideALongAbsence() {
        val hint = WorkHint("一个有稳定标题的作品", "作者甲")
        tracker.saved(sample("正文", hint), 12)
        tracker.pause()
        assertFalse(sample("正文", WorkHint("另一个完全不同的作品", "作者甲"), 3000).continuing)
        assertFalse(sample("正文", hint, 20000).continuing)
    }
}
