package com.emerge.app.monitoring

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class MonitorInputCancellationTest {
    @Test fun revokedInputLeavesSingleConsumerAliveForTheNextTask() = runBlocking {
        val queue = Channel<Boolean>(Channel.UNLIMITED)
        val completed = mutableListOf<String>()
        queue.send(false)
        queue.send(true)
        queue.close()
        val consumer = launch {
            for (current in queue) completed += handleMonitorInput("cancelled") {
                // This is the same guard used immediately before Runtime opens the HTTP request body.
                requireCurrentMonitorInput(current)
                "completed"
            }
        }
        withTimeout(1000) { consumer.join() }
        assertFalse(consumer.isCancelled)
        assertEquals(listOf("cancelled", "completed"), completed)
    }

    @Test fun explicitCoroutineCancellationExceptionIsPropagatedUnchanged() = runBlocking {
        val cancellation = CancellationException("fixture coroutine cancellation")
        try {
            handleMonitorInput("cancelled") { throw cancellation }
            fail("Expected coroutine cancellation to propagate")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }

    @Test fun cancellingTheWorkerStopsItsSuspendedInputAndDoesNotReturnCompletion() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val returned = mutableListOf<String>()
        var cancellationPropagated = false
        val consumer = launch {
            try {
                returned += handleMonitorInput("cancelled") {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            } catch (error: CancellationException) {
                cancellationPropagated = true
                throw error
            }
        }
        withTimeout(1000) { entered.await() }
        consumer.cancel()
        withTimeout(1000) { consumer.join() }
        assertTrue(consumer.isCancelled)
        assertTrue(cancellationPropagated)
        assertTrue(returned.isEmpty())
    }

    @Test fun inputRevocationDoesNotMaskSimultaneousWorkerCancellation() = runBlocking {
        val returned = mutableListOf<String>()
        var cancellationPropagated = false
        val consumer = launch {
            try {
                returned += handleMonitorInput("cancelled") {
                    currentCoroutineContext().cancel()
                    requireCurrentMonitorInput(false)
                    "completed"
                }
            } catch (error: CancellationException) {
                cancellationPropagated = true
                throw error
            }
        }
        withTimeout(1000) { consumer.join() }
        assertTrue(consumer.isCancelled)
        assertTrue(cancellationPropagated)
        assertTrue(returned.isEmpty())
    }
}
