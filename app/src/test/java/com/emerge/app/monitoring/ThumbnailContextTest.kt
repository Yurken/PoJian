package com.emerge.app.monitoring

import org.junit.Assert.*
import org.junit.Test

class ThumbnailContextTest {
    private val request = ThumbnailContext(1, "a", "reader", 4, 5, 6, 7)
    @Test fun lateCallbackRejectsEveryChangedBoundaryAndConsent() {
        assertTrue(request.matches(request, 5, 6, 7, true))
        listOf(request.copy(eventId = 2), request.copy(workKey = "b"), request.copy(packageName = "other"),
            request.copy(windowId = 8), request.copy(revision = 9)).forEach {
            assertFalse(request.matches(it, 5, 6, 7, true))
        }
        assertFalse(request.matches(null, 5, 6, 7, true))
        assertFalse(request.matches(request, 9, 6, 7, true))
        assertFalse(request.matches(request, 5, null, 7, true))
        assertFalse(request.matches(request, 5, 8, 7, true))
        assertFalse(request.matches(request, 5, 6, 9, true))
        assertFalse(request.matches(request, 5, 6, 7, false))
    }
    @Test fun revokingThenReauthorizingDoesNotReviveAnOldCallback() {
        assertFalse(request.matches(request, 5, 6, 9, true))
        assertFalse(request.matches(request.copy(revision = 7), 7, 6, 7, true))
    }
}
