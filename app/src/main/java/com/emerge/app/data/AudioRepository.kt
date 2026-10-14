package com.emerge.app.data

import androidx.room.withTransaction
import java.util.UUID

/** A transcript and its searchable browse event always commit or disappear together. */
class AudioRepository(private val database: BrowseDatabase) {
    private val audio = database.audioDao()
    private val events = database.browseEventDao()
    val sessions = audio.observeSessions()
    val allTranscripts = audio.observeAllTranscripts()
    fun observeSessions() = sessions
    fun observeSession(id: String) = audio.observeSession(id)
    fun observeTranscripts(sessionId: String) = audio.observeTranscripts(sessionId)
    suspend fun getSession(id: String) = audio.session(id)
    suspend fun getTranscript(id: Long) = audio.transcript(id)
    suspend fun getTranscripts(sessionId: String) = audio.transcripts(sessionId)
    suspend fun getHistory(beforeStartedAt: Long, excludingSessionId: String, limit: Int = 120) =
        audio.history(beforeStartedAt, excludingSessionId, limit.coerceIn(1, 500))

    suspend fun createSession(
        packageName: String,
        source: String,
        title: String,
        startedAt: Long = System.currentTimeMillis(),
        id: String = UUID.randomUUID().toString()
    ): AudioSession {
        require(packageName.isNotBlank() && id.isNotBlank())
        require(source == AudioSource.IMPORT || source == AudioSource.PLAYBACK)
        val session = AudioSession(id, packageName, source, title, startedAt)
        audio.insertSession(session)
        return session
    }

    /** Stable segments only; replaying a batch cannot duplicate the same media range. */
    suspend fun appendTranscript(
        sessionId: String,
        generation: Long,
        startMillis: Long,
        endMillis: Long,
        originalText: String
    ): AudioTranscript? = database.withTransaction {
        require(startMillis >= 0 && endMillis >= startMillis)
        if (originalText.isBlank()) return@withTransaction null
        val session = audio.session(sessionId) ?: return@withTransaction null
        if (session.generation != generation || session.status in TERMINAL_STATUSES) return@withTransaction null
        audio.transcriptAt(sessionId, startMillis, endMillis)?.let { return@withTransaction it }
        val receivedAt = System.currentTimeMillis()
        val timestamp = if (session.source == AudioSource.PLAYBACK) session.startedAt + startMillis else receivedAt
        val parent = if (session.source == AudioSource.PLAYBACK && session.startedAt <= Long.MAX_VALUE - endMillis)
            com.emerge.app.capture.MaterialWorkBinding.playbackParent(
                events.coveringVisits(session.packageName, timestamp, session.startedAt + endMillis),
                session.packageName, timestamp, session.startedAt + endMillis) else null
        val eventId = events.insert(BrowseEvent(packageName = session.packageName, timestamp = timestamp,
            eventType = 0, visibleText = originalText, endTimestamp = timestamp, durationMillis = 0, sampleCount = 0,
            sourceType = "audio_${session.source}", audioSessionId = sessionId,
            mediaStartMillis = startMillis, mediaEndMillis = endMillis, parentEventId = parent?.id,
            workKey = parent?.workKey, identityMethod = parent?.identityMethod ?: "unknown"))
        val transcript = AudioTranscript(sessionId = sessionId, browseEventId = eventId,
            offsetStartMillis = startMillis, offsetEndMillis = endMillis, originalText = originalText, createdAt = receivedAt)
        val id = audio.insertTranscript(transcript)
        audio.updateSession(session.copy(contentVersion = session.contentVersion + 1, analysisJson = null))
        audio.invalidateLaterAnalyses(session.startedAt, session.id)
        events.trimWorks()
        audio.transcript(id)
    }

    /** Preserve the recognizer output, and update the text used by search and local reports. */
    suspend fun editTranscript(id: Long, correctedText: String?): Boolean = database.withTransaction {
        val transcript = audio.transcript(id) ?: return@withTransaction false
        val session = audio.session(transcript.sessionId) ?: return@withTransaction false
        val correction = correctedText?.trim()?.takeIf { it.isNotEmpty() && it != transcript.originalText }
        val updated = transcript.copy(correctedText = correction)
        if (updated == transcript) return@withTransaction true
        audio.updateTranscript(updated)
        events.updateText(transcript.browseEventId, updated.displayText)
        audio.updateSession(session.copy(contentVersion = session.contentVersion + 1, analysisJson = null))
        audio.invalidateLaterAnalyses(session.startedAt, session.id)
        true
    }

    suspend fun updateSessionStatus(
        id: String,
        generation: Long,
        status: String,
        errorMessage: String? = null,
        durationMillis: Long? = null
    ): Boolean = database.withTransaction {
        val session = audio.session(id) ?: return@withTransaction false
        if (session.generation != generation) return@withTransaction false
        if (session.status in TERMINAL_STATUSES && session.status != status) return@withTransaction false
        audio.updateSession(session.copy(status = status, errorMessage = errorMessage,
            durationMillis = maxOf(session.durationMillis, durationMillis?.coerceAtLeast(0) ?: 0))) > 0
    }

    /** Compare a content version to discard analysis that finished after a user correction. */
    suspend fun setSessionAnalysis(
        id: String,
        generation: Long,
        json: String?,
        contentVersion: Long? = null
    ): Boolean = database.withTransaction {
        val session = audio.session(id) ?: return@withTransaction false
        if (session.generation != generation || (contentVersion != null && session.contentVersion != contentVersion)) return@withTransaction false
        audio.updateSession(session.copy(analysisJson = json)) > 0
    }

    suspend fun deleteSession(id: String) = database.withTransaction {
        val session = audio.session(id) ?: return@withTransaction
        audio.invalidateLaterAnalyses(session.startedAt, session.id)
        audio.deleteSession(id)
        events.trimWorks()
    }

    companion object {
        private val TERMINAL_STATUSES = setOf(AudioSessionStatus.CANCELLED, AudioSessionStatus.FAILED)
    }
}
