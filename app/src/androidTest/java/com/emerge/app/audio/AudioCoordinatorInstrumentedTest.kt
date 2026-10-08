package com.emerge.app.audio

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.emerge.app.EmergeApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AudioCoordinatorInstrumentedTest {
    @Test fun importedPublicSpeechIsDurableBeforeCompletionAndCanBeDeleted() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EmergeApplication
        val sample = File(app.filesDir, "audio-test-sample.wav")
        val coordinator = app.audioCoordinator
        assumeTrue("Public ASR fixtures are required", coordinator.runtime.state.value.modelInstalled && sample.isFile)
        coordinator.importAudio(Uri.fromFile(sample))
        val id = requireNotNull(coordinator.runtime.state.value.sessionId)
        try {
            val session = withTimeout(60_000) { app.audioRepository.observeSession(id).first { it?.status in setOf("completed", "failed", "cancelled") } }
            assertEquals("completed", session?.status)
            val rows = app.audioRepository.getTranscripts(id)
            assertTrue(rows.joinToString("") { it.originalText }.contains("研究感兴趣"))
            assertTrue(rows.all { it.correctedText == null })
            val events = app.repository.allEvents.first().filter { it.audioSessionId == id }
            assertEquals(rows.size, events.size)
            assertTrue(events.all { it.durationMillis == 0L && it.sampleCount == 0 && it.sourceType == "audio_import" })
            assertTrue(requireNotNull(session).modelId.contains("zipformer"))
        } finally { coordinator.deleteSession(id) }
        assertNull(app.audioRepository.getSession(id))
        assertTrue(app.repository.allEvents.first().none { it.audioSessionId == id })
    }
}
