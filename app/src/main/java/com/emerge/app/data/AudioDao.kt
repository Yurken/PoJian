package com.emerge.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AudioDao {
    @Query("SELECT * FROM audio_sessions ORDER BY startedAt DESC, id DESC")
    fun observeSessions(): Flow<List<AudioSession>>

    @Query("SELECT * FROM audio_sessions WHERE id = :id")
    fun observeSession(id: String): Flow<AudioSession?>

    @Query("SELECT * FROM audio_transcripts WHERE sessionId = :sessionId ORDER BY offsetStartMillis, id")
    fun observeTranscripts(sessionId: String): Flow<List<AudioTranscript>>

    @Query("SELECT * FROM audio_transcripts ORDER BY createdAt DESC, id DESC")
    fun observeAllTranscripts(): Flow<List<AudioTranscript>>

    @Query("SELECT * FROM audio_sessions WHERE id = :id")
    suspend fun session(id: String): AudioSession?

    @Query("SELECT * FROM audio_transcripts WHERE id = :id")
    suspend fun transcript(id: Long): AudioTranscript?

    @Query("SELECT * FROM audio_transcripts WHERE sessionId = :sessionId ORDER BY offsetStartMillis, id")
    suspend fun transcripts(sessionId: String): List<AudioTranscript>

    @Query("SELECT t.* FROM audio_transcripts t INNER JOIN audio_sessions s ON t.sessionId = s.id WHERE s.startedAt < :beforeStartedAt AND s.id != :excludingSessionId ORDER BY s.startedAt DESC, t.offsetStartMillis DESC LIMIT :limit")
    suspend fun history(beforeStartedAt: Long, excludingSessionId: String, limit: Int): List<AudioTranscript>

    @Query("SELECT * FROM audio_transcripts WHERE sessionId = :sessionId AND offsetStartMillis = :startMillis AND offsetEndMillis = :endMillis LIMIT 1")
    suspend fun transcriptAt(sessionId: String, startMillis: Long, endMillis: Long): AudioTranscript?

    @Insert suspend fun insertSession(session: AudioSession)
    @Insert suspend fun insertTranscript(transcript: AudioTranscript): Long
    @Update suspend fun updateSession(session: AudioSession): Int
    @Update suspend fun updateTranscript(transcript: AudioTranscript): Int

    @Query("UPDATE audio_sessions SET contentVersion = contentVersion + 1, analysisJson = NULL WHERE startedAt >= :startedAt AND id != :excludingId")
    suspend fun invalidateLaterAnalyses(startedAt: Long, excludingId: String)

    @Query("UPDATE audio_sessions SET contentVersion = contentVersion + 1, analysisJson = NULL")
    suspend fun invalidateAllAnalyses()

    @Query("UPDATE audio_sessions SET generation = generation + 1, status = 'cancelled', analysisJson = NULL WHERE id IN (SELECT audioSessionId FROM browse_events WHERE id = :eventId)")
    suspend fun invalidateForEvent(eventId: Long)

    @Query("""SELECT s.id,s.startedAt FROM audio_sessions s
        WHERE s.status IN ('completed','failed','cancelled')
        AND (:beforeTimestamp IS NOT NULL OR NOT EXISTS (SELECT 1 FROM browse_events e WHERE e.audioSessionId=s.id
            AND e.parentEventId=(SELECT id FROM browse_events WHERE sourceType='accessibility'
                AND parentEventId IS NULL ORDER BY timestamp DESC,id DESC LIMIT 1)))
        AND (:beforeTimestamp IS NULL OR (
            s.startedAt < :beforeTimestamp
            AND NOT EXISTS (SELECT 1 FROM browse_events e WHERE e.audioSessionId=s.id
                AND MAX(e.timestamp,e.endTimestamp) >= :beforeTimestamp)
            AND NOT EXISTS (SELECT 1 FROM audio_transcripts t WHERE t.sessionId=s.id
                AND t.createdAt >= :beforeTimestamp)))
        ORDER BY s.startedAt,s.id LIMIT 1""")
    suspend fun oldestStorageSession(beforeTimestamp: Long? = null): StorageAudioCandidate?

    @Query("DELETE FROM audio_sessions WHERE id = :id") suspend fun deleteSession(id: String)
    @Query("DELETE FROM audio_sessions WHERE packageName = :packageName") suspend fun deletePackage(packageName: String)
    @Query("DELETE FROM audio_sessions") suspend fun clear()
}

data class StorageAudioCandidate(val id: String, val startedAt: Long)
