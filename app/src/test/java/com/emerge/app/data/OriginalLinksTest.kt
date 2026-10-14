package com.emerge.app.data

import org.junit.Assert.*
import org.junit.Test

class OriginalLinksTest {
    private val douyin = "com.ss.android.ugc.aweme"
    private val xhs = "com.xingin.xhs"
    private val note = "https://www.xiaohongshu.com/explore/6699aabb0011223344556677?xsec_token=abc%2Bdef%3D&xsec_source=pc_share"
    @Test fun keepsSignedShareQueryAndExtractsCopiedShareText() {
        assertEquals(note, OriginalLinks.fromInput("分享这篇笔记 $note 复制后打开", xhs))
        assertEquals("https://v.douyin.com/AbCdEf/", OriginalLinks.fromInput("4.5 分享 https://v.douyin.com/AbCdEf/", douyin))
    }
    @Test fun refusesAmbiguousOrMismatchedWorkLinks() {
        assertNull(OriginalLinks.fromInput("https://v.douyin.com/one/ https://v.douyin.com/two/", douyin))
        assertNull(OriginalLinks.fromInput(note, douyin))
        assertNull(OriginalLinks.fromInput("https://www.douyin.com/user/123456", douyin))
        assertNull(OriginalLinks.fromInput("https://www.douyin.com/", douyin))
    }
    @Test fun allowsSixPlatformWorkPages() {
        mapOf(
            "https://www.douyin.com/video/7000000000000000000" to douyin,
            note to xhs,
            "https://weibo.com/1234567890/AbCd12345" to "com.sina.weibo",
            "https://channels.weixin.qq.com/web/pages/feed?feedId=abc" to "com.tencent.mm",
            "https://mp.weixin.qq.com/s/abcdefghijk" to "com.tencent.mm",
            "https://www.kuaishou.com/short-video/3xabcdef" to "com.smile.gifmaker"
        ).forEach { (url, pkg) -> assertTrue(url, OriginalLinks.allowed(url, pkg)) }
    }
    @Test fun rejectsUntrustedSchemesHostSuffixTricksCredentialsAndPorts() {
        listOf("http://www.douyin.com/video/123456", "javascript:alert(1)", "file:///tmp/movie.mp4",
            "content://gallery/1", "https://www.douyin.com.evil.example/video/123456",
            "https://evil.example@www.douyin.com/video/123456", "https://www.douyin.com:8443/video/123456",
            "https://127.0.0.1/video/123456", "https://www.douyin.com/video/123456/../../").forEach {
            assertFalse(it, OriginalLinks.allowed(it, douyin))
        }
    }
    @Test fun navigationDoesNotLaunchIntentsOrUnknownHosts() {
        assertTrue(OriginalLinks.canNavigate("https://passport.weibo.cn/signin"))
        assertTrue(OriginalLinks.canNavigate(note))
        listOf("intent://work#Intent;scheme=snssdk1128;end", "snssdk1128://aweme/detail/12345",
            "http://weibo.com", "https://evil.example", "about:blank", "https://weibo.com.evil.example/1").forEach {
            assertFalse(it, OriginalLinks.canNavigate(it))
        }
    }
    @Test fun backfillRequiresARealSupportedIdentifier() {
        fun work(pkg: String, id: String) = ObservedWork("w",pkg,"标题","作者","platform_id",1,"visible_platform_id",id)
        assertEquals("https://www.douyin.com/video/7000000000000000000", OriginalLinks.forWork(work(douyin, "7000000000000000000")))
        assertEquals(note.substringBefore('?'), OriginalLinks.forWork(work(xhs, "6699aabb0011223344556677")))
        assertNull(OriginalLinks.forWork(work(douyin, "")))
        assertNull(OriginalLinks.forWork(work(douyin, "../login")))
        assertNull(OriginalLinks.forWork(work("reader", "7000000000000000000")))
        assertNull(OriginalLinks.forWork(work(xhs, "fake-note")))
    }
}
