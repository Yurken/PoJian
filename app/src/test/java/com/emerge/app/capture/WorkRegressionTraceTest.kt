package com.emerge.app.capture

import com.emerge.app.analysis.*
import com.emerge.app.data.BrowseEvent
import com.emerge.app.ui.*
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

/** Engineer-labelled synthetic operations, not recordings or an accuracy claim about real apps. */
class WorkRegressionTraceTest {
    private val pkg = "com.ss.android.ugc.aweme"
    private val full = "职业能力需要持续提升，求职时也不必过度焦虑"
    private fun video(id: String, caption: String = full) = WorkIdentityExtractor.extract(pkg, caption,
        listOf(IdentityNode("id/aweme_id", id), IdentityNode("id/desc", caption), IdentityNode("id/title", "作者甲")))
    data class Step(val work: String, val visit: Int, val hint: WorkHint, val text: String = "字幕内容变化",
        val reset: Boolean = false, val pause: Boolean = false, val seconds: Long? = null,
        val pkg: String = "com.ss.android.ugc.aweme")
    private fun cases(): Map<String, List<Step>> {
        val caption = WorkHint(full, "作者甲", page = WorkPage.VIDEO)
        return linkedMapOf(
            "video-subtitles-pause" to listOf(Step("a",1,video("123456789")), Step("a",1,video("123456789"),"下一句字幕"),
                Step("a",1,video("123456789"),"暂停视频"), Step("a",1,video("123456789"),"继续视频")),
            "native-id-changing-caption" to listOf(Step("a",1,video("123456789","这部作品开头可见的一段文字")),
                Step("a",1,video("123456789","同一作品现在显示不同的文案"))),
            "same-author-different-work" to listOf(Step("a",1,video("123456789")),Step("b",2,video("987654321"))),
            "different-works-same-opinion" to listOf(Step("a",1,video("123456789")),Step("b",2,video("987654321"),reset=true)),
            "caption-expand-return" to listOf(Step("a",1,caption.copy(title=full.take(17),truncated=true)),
                Step("a",1,caption.copy(page=WorkPage.CAPTION)),Step("a",2,caption,reset=true)),
            "gallery-scroll-comments" to listOf(Step("a",1,caption.copy(page=WorkPage.GENERIC,galleryIndex=1,galleryTotal=3)),
                Step("a",1,caption.copy(page=WorkPage.GENERIC,galleryIndex=2,galleryTotal=3)),
                Step("a",1,caption.copy(page=WorkPage.GENERIC,galleryIndex=1,galleryTotal=3)),
                Step("a",1,WorkHint(page=WorkPage.COMMENTS)),Step("a",1,caption.copy(page=WorkPage.GENERIC))),
            "return-known-work" to listOf(Step("a",1,video("123456789")),Step("b",2,video("987654321")),Step("a",3,video("123456789"))),
            "across-app-return" to listOf(Step("a",1,caption),Step("b",2,caption,pkg="com.xingin.xhs"),Step("a",3,caption)),
            "same-work-spacing" to listOf(Step("a",1,caption,seconds=0),Step("a",1,caption,seconds=4),Step("a",1,caption,seconds=8)),
            "missing-root-no-time" to listOf(Step("a",1,caption),Step("a",1,caption,pause=true,seconds=20)),
            "lock-and-return" to listOf(Step("a",1,caption),Step("a",2,caption,reset=true,seconds=20)),
            "uncertain-identical-text" to listOf(Step("unknown-a",1,WorkHint(),full),Step("unknown-b",2,WorkHint(),full,reset=true))
        )
    }
    @Test fun labelledOperationsMeasureIdentityVisitsCountsAndDuration() {
        val baseline = System.getenv("POJIAN_TRACE_BASELINE") == "1"
        val base = 1_791_774_596_000L // fixed sample clock; actual cross-hour coverage is in WorkStatisticsConsistencyTest
        val zone = ZoneId.of("UTC")
        var samples = 0
        cases().forEach { (name, steps) ->
            val tracker = CaptureSessionTracker()
            val rows = linkedMapOf<Long,BrowseEvent>()
            val sampleVisitIds = mutableListOf<Long>()
            var nextId = 0L; var boundaryErrors = 0; var expectedDuration = 0L; var lastSeconds = 0L
            steps.forEachIndexed { i, step ->
                if (step.reset) tracker.reset()
                if (step.pause) tracker.pause()
                val seconds = step.seconds ?: i * 2L
                val sample = tracker.sample(step.pkg,step.text,base+seconds*1000,1000+seconds*1000,4096,step.hint)
                val isNew = i == 0 || step.visit != steps[i-1].visit
                if (sample.continuing == isNew) boundaryErrors++
                if (!isNew && !step.pause && seconds-lastSeconds in 1..6) expectedDuration += (seconds-lastSeconds)*1000
                val id = if(sample.continuing) sample.event.id else ++nextId
                rows[id] = sample.event.copy(id=id); tracker.saved(sample,id); sampleVisitIds += id
                lastSeconds=seconds; samples++
            }
            val identities = sampleVisitIds.map { rows.getValue(it).workKey }
            var identityErrors=0; var pairs=0
            for(i in steps.indices) for(j in 0 until i) { pairs++; if((steps[i].work==steps[j].work)!=(identities[i]==identities[j])) identityErrors++ }
            val now=base+(lastSeconds+1)*1000
            val stats=calculateStatistics(rows.values.toList(),StatisticsPeriod.DAY,now,zone)
            val expected=steps.map { it.work }.distinct().size
            println("TRACE,$name,${steps.size},$pairs,$identityErrors,$boundaryErrors,$expected,${stats.total},$expectedDuration,${stats.observedDurationMillis}")
            if(!baseline) { assertEquals(name,0,identityErrors); assertEquals(name,0,boundaryErrors); assertEquals(name,expected,stats.total); assertEquals(name,expectedDuration,stats.observedDurationMillis) }
        }
        println("TRACE_SAMPLES,$samples")
    }

    @Test fun overlappingVisitsAndMultipleClaimsHaveSeparateMetrics() {
        val start=1_791_774_596_000L; val zone=ZoneId.of("UTC"); val text="今年就业困难让求职者焦虑；女性应该经济独立并自主生活"
        val a=BrowseEvent(1,"reader",start,32,text,endTimestamp=start+4000,durationMillis=4000,sampleCount=3,workKey="a")
        val b=a.copy(id=2,timestamp=start+2000,endTimestamp=start+6000)
        val semantic=EventSemantics(a.id,text,start+7000,listOf(
            ArgumentUnit("今年就业困难让求职者焦虑","就业","求职者","情绪","焦虑","",uncertain=false),
            ArgumentUnit("女性应该经济独立并自主生活","经济","女性","自主","独立","",uncertain=false)),"text")
        val buckets=ViewpointTimeline.build(listOf(a,b),StatisticsPeriod.DAY,start+8000,zone,semantics=listOf(semantic))
        val stats=calculateStatistics(listOf(a,b),StatisticsPeriod.DAY,start+8000,zone)
        val items=buckets.flatMap { it.items }.distinctBy { it.workIdentity }
        println("DISPLAY,works=${stats.total},visits=${stats.visitCount},duration=${stats.observedDurationMillis},expectedDuration=6000,viewpointEntries=${ViewpointTimeline.groups(items).sumOf { it.items.size }},workCards=${ViewpointTimeline.details(items).size}")
        if(System.getenv("POJIAN_TRACE_BASELINE")!="1") { assertEquals(6000L,stats.observedDurationMillis); assertEquals(1,items.size); assertEquals(2,items.single().claims.size) }
    }
}
