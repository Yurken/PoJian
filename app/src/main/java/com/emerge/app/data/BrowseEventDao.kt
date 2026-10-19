package com.emerge.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface BrowseEventDao {
    @Query("SELECT * FROM browse_events WHERE id = :id")
    suspend fun event(id: Long): BrowseEvent?
    @Query("SELECT COUNT(*) FROM browse_semantics")
    fun observeSemanticsInvalidation(): Flow<Int>
    @Query("SELECT * FROM browse_semantics ORDER BY eventId LIMIT :limit")
    suspend fun firstSemanticsPage(limit: Int): List<BrowseSemantic>
    @Query("SELECT * FROM browse_semantics WHERE eventId > :afterId ORDER BY eventId LIMIT :limit")
    suspend fun semanticsPage(afterId: Long, limit: Int): List<BrowseSemantic>
    @Query("SELECT * FROM browse_semantics WHERE eventId = :eventId")
    suspend fun semanticsForEvent(eventId: Long): BrowseSemantic?
    @Query("SELECT * FROM browse_semantics WHERE eventId IN (:eventIds)")
    suspend fun semanticsForEvents(eventIds: List<Long>): List<BrowseSemantic>
    @Query("SELECT * FROM browse_events WHERE sourceType IN ('accessibility','audio_playback') " +
        "AND MAX(timestamp,endTimestamp) > :after AND MAX(timestamp,endTimestamp) <= :now ORDER BY timestamp DESC, id DESC")
    suspend fun recentTextEvents(after: Long, now: Long): List<BrowseEvent>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSemantics(semantics: BrowseSemantic)
    @Query("DELETE FROM browse_semantics WHERE eventId = :eventId AND analyzedAt = :analyzedAt AND inputText = :inputText")
    suspend fun removeSemanticsVersion(eventId: Long, analyzedAt: Long, inputText: String)
    @Query("DELETE FROM browse_semantics")
    suspend fun clearSemantics()
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertWork(work: ObservedWork)
    @Query("SELECT * FROM observed_works") fun observeWorks(): Flow<List<ObservedWork>>
    @Query("SELECT * FROM observed_works WHERE packageName=:packageName AND author=:author AND identityMethod IN ('author_title','platform_id')")
    suspend fun captionCandidates(packageName: String, author: String): List<ObservedWork>
    @Query("UPDATE browse_events SET workKey=:key,identityMethod=:method WHERE parentEventId=:parent")
    suspend fun updateMaterialIdentity(parent: Long, key: String?, method: String)
    @Query("SELECT * FROM browse_events WHERE sourceType='accessibility' AND packageName=:pkg AND timestamp<=:start AND endTimestamp>=:end")
    suspend fun coveringVisits(pkg: String, start: Long, end: Long): List<BrowseEvent>
    @Insert suspend fun insertSample(sample: BrowseSample)
    @Query("SELECT visibleText FROM browse_samples WHERE visitId = :visitId ORDER BY id DESC LIMIT 1")
    suspend fun lastSampleText(visitId: Long): String?
    @Query("DELETE FROM browse_samples WHERE visitId = :visitId AND id NOT IN (SELECT id FROM browse_samples WHERE visitId = :visitId ORDER BY id DESC LIMIT 120)")
    suspend fun trimSamples(visitId: Long)
    @Query("DELETE FROM observed_works WHERE workKey NOT IN (SELECT workKey FROM browse_events WHERE workKey IS NOT NULL)")
    suspend fun trimWorks()
    @Query("SELECT * FROM browse_events WHERE sourceType != 'recommendation_list' ORDER BY timestamp DESC, id DESC")
    fun observeAll(): Flow<List<BrowseEvent>>
    @Query("SELECT * FROM browse_events WHERE sourceType != 'recommendation_list' ORDER BY timestamp DESC, id DESC LIMIT 200")
    fun observeRecent(): Flow<List<BrowseEvent>>
    @Query("SELECT COUNT(*) FROM browse_events WHERE sourceType != 'recommendation_list'")
    fun observeCount(): Flow<Int>
    @Query("SELECT * FROM browse_events ORDER BY timestamp, id")
    suspend fun chronologicalEvents(): List<BrowseEvent>
    @Query("UPDATE browse_samples SET visitId = :target WHERE visitId = :source")
    suspend fun moveSamples(source: Long, target: Long)
    @Query("UPDATE browse_events SET parentEventId = :target WHERE parentEventId = :source")
    suspend fun moveMaterials(source: Long, target: Long)
    @Insert suspend fun insert(event: BrowseEvent): Long
    @Update suspend fun update(event: BrowseEvent): Int
    @Query("UPDATE browse_events SET visibleText = :text WHERE id = :id")
    suspend fun updateText(id: Long, text: String): Int
    @Query("DELETE FROM browse_events WHERE id = :id") suspend fun deleteEvent(id: Long)
    @Query("DELETE FROM browse_events WHERE packageName = :packageName") suspend fun deletePackage(packageName: String)
    @Query("DELETE FROM browse_events") suspend fun clear()
    // Audio is evicted as a whole session first. Never cascade a partial transcript session.
    @Query("""SELECT e.id, e.timestamp FROM browse_events e
        WHERE e.parentEventId IS NULL AND e.audioSessionId IS NULL
        AND e.id != COALESCE((SELECT id FROM browse_events
            WHERE sourceType='accessibility' AND parentEventId IS NULL ORDER BY timestamp DESC,id DESC LIMIT 1), -1)
        AND NOT EXISTS (SELECT 1 FROM browse_events child WHERE child.parentEventId=e.id AND child.audioSessionId IS NOT NULL)
        AND (:beforeTimestamp IS NULL OR (
            MAX(e.timestamp,e.endTimestamp) < :beforeTimestamp
            AND NOT EXISTS (SELECT 1 FROM browse_events child WHERE child.parentEventId=e.id
                AND MAX(child.timestamp,child.endTimestamp) >= :beforeTimestamp)
            AND NOT EXISTS (SELECT 1 FROM browse_samples sample WHERE sample.visitId=e.id
                AND sample.capturedAt >= :beforeTimestamp)))
        ORDER BY e.timestamp,e.id LIMIT 1""")
    suspend fun oldestStorageVisit(beforeTimestamp: Long? = null): StorageVisitCandidate?
}

data class StorageVisitCandidate(val id: Long, val timestamp: Long)
