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
}
