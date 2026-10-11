package com.emerge.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface BrowseEventDao {
    @Query("SELECT * FROM browse_events WHERE id = :id")
    suspend fun event(id: Long): BrowseEvent?
    @Query("SELECT * FROM browse_semantics ORDER BY analyzedAt DESC, eventId DESC")
    fun observeSemantics(): Flow<List<BrowseSemantic>>
    @Query("SELECT * FROM browse_semantics WHERE eventId = :eventId")
    suspend fun semanticsForEvent(eventId: Long): BrowseSemantic?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSemantics(semantics: BrowseSemantic)
    @Query("DELETE FROM browse_semantics WHERE eventId = :eventId AND analyzedAt = :analyzedAt AND inputText = :inputText")
    suspend fun removeSemanticsVersion(eventId: Long, analyzedAt: Long, inputText: String)
    @Query("DELETE FROM browse_semantics")
    suspend fun clearSemantics()
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertWork(work: ObservedWork)
    @Insert suspend fun insertSample(sample: BrowseSample)
    @Query("SELECT visibleText FROM browse_samples WHERE visitId = :visitId ORDER BY id DESC LIMIT 1")
    suspend fun lastSampleText(visitId: Long): String?
    @Query("DELETE FROM browse_samples WHERE visitId = :visitId AND id NOT IN (SELECT id FROM browse_samples WHERE visitId = :visitId ORDER BY id DESC LIMIT 120)")
    suspend fun trimSamples(visitId: Long)
    @Query("DELETE FROM browse_samples WHERE id NOT IN (SELECT id FROM browse_samples ORDER BY capturedAt DESC, id DESC LIMIT 5000)")
    suspend fun trimAllSamples()
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
    @Query("DELETE FROM browse_events WHERE id NOT IN (SELECT id FROM browse_events ORDER BY timestamp DESC, id DESC LIMIT 5000)")
    suspend fun trim()
}
