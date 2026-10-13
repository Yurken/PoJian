package com.emerge.app.capture

import com.emerge.app.data.BrowseEvent
import org.junit.Assert.*
import org.junit.Test

class MaterialWorkBindingTest {
    private val visit = BrowseEvent(1,"reader",1000,32,"正文",endTimestamp=9000,durationMillis=8000,
        sampleCount=5,workKey="a",visibilityIntervals=VisibilityIntervals.encode(listOf(
            VisibilityInterval(1000,9000,8000,VisibilityRegion.UNLOCATED))))
    @Test fun playbackBindsOnlyToOneFullyMeasuredVisit() {
        assertEquals(visit, MaterialWorkBinding.playbackParent(listOf(visit),"reader",2000,8000))
        assertNull(MaterialWorkBinding.playbackParent(listOf(visit),"other",2000,8000))
        assertNull(MaterialWorkBinding.playbackParent(listOf(visit),"reader",2000,10000))
        assertNull(MaterialWorkBinding.playbackParent(listOf(visit),"reader",2000,2000))
        assertNull(MaterialWorkBinding.playbackParent(listOf(visit,visit.copy(id=2)),"reader",2000,8000))
        assertNull(MaterialWorkBinding.playbackParent(listOf(visit.copy(visibilityIntervals=null)),"reader",2000,8000))
    }
    @Test fun lockedOrUnobservedGapCannotAcquireAWorkBinding() {
        val hole=visit.copy(durationMillis=4000,visibilityIntervals=VisibilityIntervals.encode(listOf(
            VisibilityInterval(1000,3000,2000,VisibilityRegion.UNLOCATED),
            VisibilityInterval(7000,9000,2000,VisibilityRegion.UNLOCATED))))
        assertNull(MaterialWorkBinding.playbackParent(listOf(hole),"reader",2000,8000))
        assertEquals(hole, MaterialWorkBinding.playbackParent(listOf(hole),"reader",7000,8000))
    }
}
