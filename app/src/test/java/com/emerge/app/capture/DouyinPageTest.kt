package com.emerge.app.capture

import org.junit.Assert.*
import org.junit.Test

class DouyinPageTest {
    private val pkg = "com.ss.android.ugc.aweme"
    private fun node(id: String, text: String = "", description: String = "") =
        IdentityNode("$pkg:id/$id", text, description)
    private fun extract(vararg nodes: IdentityNode) = WorkIdentityExtractor.extract(pkg, "", nodes.toList())
    private fun video(author: String = "作者甲", caption: String = "我的头饱满嘛 #脖子矫正器 #发际线ok吗 #脑门大 #头顶怎么尖尖的... 展开>") =
        extract(node("title", "@$author"), node("user_avatar", description = author), node("desc", caption))
    private fun comments() = extract(node("e4u", "评论 23"), node("back_btn", description = "关闭"),
        node("title", "评论者甲"), node("user_name", "推荐作者乙"))

    @Test fun commentsAndReturnKeepOneVisitAndDwell() {
        val tracker = CaptureSessionTracker()
        val first = tracker.sample(pkg, "原视频", 1000, 1000, 4096, video())
        tracker.saved(first, 12)
        assertEquals(WorkPage.COMMENTS, comments().page)
        assertEquals("", comments().author)
        val opened = tracker.sample(pkg, "全新的评论文字", 3000, 3000, 4096, comments())
        assertTrue(opened.continuing)
        assertEquals(first.event.workKey, opened.event.workKey)
        tracker.saved(opened, 12)
        val returned = tracker.sample(pkg, "原视频", 5000, 5000, 4096, video())
        assertTrue(returned.continuing)
        assertEquals(12L, returned.event.id)
        assertEquals(4000L, returned.event.durationMillis)
    }

    @Test fun captionExpansionPromotesIdentityWithinTheSameVisit() {
        val tracker = CaptureSessionTracker()
        val first = tracker.sample(pkg, "折叠文案", 1000, 1000, 4096, video())
        tracker.saved(first, 12)
        val expanded = extract(node("g9q", "作者甲"),
            node("g8t", "我的头饱满嘛 #脖子矫正器 #发际线ok吗 #脑门大 #头顶怎么尖尖的#真实生活计划分享    09月24日"),
            node("user_name", "另一篇推荐的作者"))
        assertEquals("作者甲", expanded.author)
        assertEquals(WorkPage.CAPTION, expanded.page)
        val next = tracker.sample(pkg, "完整文案和推荐内容", 3000, 3000, 4096, expanded)
        assertTrue(next.continuing)
        assertEquals("session_estimate", first.event.identityMethod)
        assertEquals(expanded.key(pkg), next.event.workKey)
        assertEquals(12L, next.event.id)
        tracker.saved(next, 12)
        assertTrue(tracker.sample(pkg, "折叠文案", 5000, 5000, 4096, video()).continuing)
    }

    @Test fun differentVideoBySameAuthorStartsNewVisit() {
        val tracker = CaptureSessionTracker()
        tracker.saved(tracker.sample(pkg, "界面", 1000, 1000, 4096, video()), 12)
        val next = tracker.sample(pkg, "界面", 3000, 3000, 4096,
            video(caption = "这是同一个作者发布的另一个视频内容 #不同主题"))
        assertFalse(next.continuing)
    }

    @Test fun fullCaptionsWithCommonPrefixAreDifferentWorks() {
        val a = video(caption = "这是足够长且两篇完全相同的文字开头但是结尾不同甲")
        val b = video(caption = "这是足够长且两篇完全相同的文字开头但是结尾不同乙")
        assertFalse(a.matchesCaption(b))
        assertNotEquals(a.key(pkg), b.key(pkg))
    }

    @Test fun menusAndUnboundCommentsCannotProvideWorkIdentity() {
        assertEquals(WorkPage.NON_CONTENT, extract(node("menu", "菜单"), node("search", "搜索")).page)
        assertNull(comments().key(pkg))
        assertFalse(CaptureSessionTracker().canAttachComments(pkg))
    }
}
