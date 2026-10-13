package com.emerge.app.analysis

import com.emerge.app.data.BrowseEvent
import com.emerge.app.ui.*
import java.time.ZoneId
import java.time.Instant
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class WorkStatisticsConsistencyTest {
    private val zone=ZoneId.of("UTC")
    private val now=Instant.parse("2026-10-12T12:10:00Z").toEpochMilli()
    private fun row(id:Long, start:Long, duration:Long, key:String)=BrowseEvent(id,"reader",start,32,
        "就业困难让很多求职者感到焦虑",endTimestamp=start+duration,durationMillis=duration,sampleCount=2,workKey=key)
    @Test fun allPeriodsPlatformsBarsAndCardsShareIdentitiesAndClippedUnionTime() {
        val a=row(1,now-800_000,700_000,"a") // crosses 12:00
        val b=row(2,now-200_000,100_000,"a") // overlaps first visit
        val c=row(3,now-90_000,80_000,"b")
        val material=c.copy(id=4,sourceType="screen_ocr",parentEventId=c.id,durationMillis=0,sampleCount=0)
        for(period in StatisticsPeriod.entries) {
            val rows=listOf(a,b,c,material)
            val stats=calculateStatistics(rows,period,now,zone)
            val buckets=ViewpointTimeline.build(rows,period,now,zone)
            assertEquals(2,stats.total)
            assertEquals(3,stats.visitCount)
            assertEquals(780_000L,stats.observedDurationMillis)
            assertEquals(stats.total,stats.apps.single().count)
            assertEquals(stats.observedDurationMillis,stats.apps.single().durationMillis)
            assertEquals(stats.trend.map { it.count },buckets.map { it.items.size })
            assertEquals(2,ViewpointTimeline.details(buckets.flatMap { it.items }).size)
            assertEquals(setOf(2),ViewpointTimeline.groups(buckets.flatMap { it.items }).map { it.total }.toSet())
        }
    }
    @Test fun intervalUnionMatchesAnIndependentMillisecondCoverageOracle() {
        val random=Random(701)
        repeat(100) {
            val rows=(1L..40).map { id -> val start=random.nextInt(1000); row(id,start.toLong(),random.nextInt(1,100).toLong(),"$id") }
            val expected=(0 until 1100).count { t -> rows.any { t >= it.timestamp && t < it.endTimestamp } }.toLong()
            assertEquals(expected,ObservationWindow(0,1100,zone).unionDuration(rows))
        }
    }
    @Test fun latestAnalysisVersionAndLinkedMaterialsDoNotMultiplyWorks() {
        val a=row(1,now-5000,4000,"a")
        val ocr=a.copy(id=2,sourceType="screen_ocr",parentEventId=1,visibleText="女性应该经济独立",sampleCount=0,durationMillis=0)
        fun sem(row:BrowseEvent,time:Long,quote:String)=EventSemantics(row.id,row.visibleText,time,
            listOf(ArgumentUnit(quote,"主题","主体","维度","支持","",uncertain=false)),"text")
        val newer=sem(a,10,a.visibleText)
        val old=EventSemantics(a.id,a.visibleText,1,emptyList(),"text")
        val buckets=ViewpointTimeline.build(listOf(a,ocr),StatisticsPeriod.DAY,now,zone,
            semantics=listOf(newer,sem(ocr,11,ocr.visibleText),old))
        val item=ViewpointTimeline.details(buckets.flatMap { it.items }).single()
        // Same structured claim key from two modalities contributes once.
        assertEquals(1,item.claims.size)
        assertEquals(2,item.records.size)
        assertEquals(4000L,item.durationMillis(ObservationWindow.forPeriod(StatisticsPeriod.DAY,now,zone)))
    }
    @Test fun criticalConditionsAndOppositeStancesNeverShareALocalGroupKey() {
        assertNotEquals(ViewpointClaim("如果失业才需要提升职业能力").key,ViewpointClaim("在职也需要提升职业能力").key)
        assertNotEquals(ViewpointClaim("女性应该经济独立").key,ViewpointClaim("女性不应该经济独立").key)
        assertEquals("女性经济独立",ViewpointLabeler.compact("女性应该经济独立"))
        assertNotEquals("女性经济独立",ViewpointLabeler.compact("女性不需要经济独立"))
    }
    @Test fun retainedCapacityUsesBoundedWorkCardsAndConsistentCounts() {
        val rows=(1L..5000).map { id -> row(id,now-id*6000,2000,"work:${id%250}") }
        val started=System.nanoTime()
        val stats=calculateStatistics(rows,StatisticsPeriod.HOURS24,now,zone)
        val timeline=ViewpointTimeline.build(rows,StatisticsPeriod.HOURS24,now,zone)
        assertEquals(250,stats.total)
        assertEquals(5000,stats.visitCount)
        assertEquals(10_000_000L,stats.observedDurationMillis)
        assertEquals(250,ViewpointTimeline.details(timeline.flatMap { it.items }).size)
        println("CAPACITY,visits=5000,works=250,millis=${(System.nanoTime()-started)/1_000_000}")
    }
    @Test fun unboundAudioAndFramesCannotCreateIndependentExposureVotes() {
        val parent=row(1,now-5000,4000,"a")
        val material=parent.copy(id=2,sourceType="audio_playback",audioSessionId="session",sampleCount=0,durationMillis=0)
        assertEquals(0,ExposureAnalyzer.analyze(listOf(material),emptyList(),now,zone).topics.contacts)
        assertEquals(1,ExposureAnalyzer.analyze(listOf(parent,material.copy(parentEventId=1)),emptyList(),now,zone).topics.contacts)
        assertEquals(1,ExposureAnalyzer.analyze(listOf(parent,material,material.copy(id=3,sourceType="visual_model")),emptyList(),now,zone).topics.contacts)
    }
}
