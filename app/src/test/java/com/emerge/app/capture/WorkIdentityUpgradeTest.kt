package com.emerge.app.capture

import org.junit.Assert.*
import org.junit.Test

class WorkIdentityUpgradeTest {
    @Test fun nativeIdentifierAppearingLaterPromotesSameVisitAndSurvivesCaptionOnlySample() {
        val tracker=CaptureSessionTracker()
        val hint=WorkHint("完整稳定的文案足以建立作品连续性并且包含作者", "作者",page=WorkPage.VIDEO)
        val first=tracker.sample("reader","字幕一",1000,1000,32,hint)
        tracker.saved(first,1)
        val upgraded=tracker.sample("reader","字幕二",3000,3000,32,hint.copy(nativeId="123456"))
        assertTrue(upgraded.continuing)
        assertEquals("platform_id",upgraded.event.identityMethod)
        assertNotEquals(first.event.workKey,upgraded.event.workKey)
        tracker.saved(upgraded,1)
        val later=tracker.sample("reader","字幕三",5000,5000,32,hint)
        assertTrue(later.continuing)
        assertEquals(upgraded.event.workKey,later.event.workKey)
    }
    @Test fun captionExpansionForPhotoPostPromotesWithoutAnotherVisit() {
        val tracker=CaptureSessionTracker()
        val full="这是足够长的图文正文说明，展开后还有新的内容与例子"
        val short=WorkHint(full.take(18),"作者",truncated=true)
        tracker.saved(tracker.sample("reader","图片一",1000,1000,32,short),1)
        val next=tracker.sample("reader","图片二和展开文案",3000,3000,32,short.copy(title=full,truncated=false))
        assertTrue(next.continuing)
        assertEquals(2000L,next.event.durationMillis)
        assertEquals("author_title",next.event.identityMethod)
    }
    @Test fun multipleVisiblePlatformIdsAreAFeedNotAnEnteredWork() {
        val nodes=listOf(IdentityNode("id/aweme_id","123456"),IdentityNode("id/aweme_id","654321"),
            IdentityNode("id/desc","一个作品的可见标题与说明"),IdentityNode("id/title","作者"))
        assertTrue(WorkIdentityExtractor.isRecommendationList("com.ss.android.ugc.aweme","",nodes))
        assertEquals(WorkPage.NON_CONTENT, WorkIdentityExtractor.extract("com.ss.android.ugc.aweme","",nodes).page)
    }
}
