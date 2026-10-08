package com.emerge.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface BrowseEventDao {
    @Query("SELECT * FROM browse_events ORDER BY timestamp DESC, id DESC")
    fun observeAll(): Flow<List<BrowseEvent>>
    @Query("SELECT * FROM browse_events ORDER BY timestamp DESC, id DESC LIMIT 200")
    fun observeRecent(): Flow<List<BrowseEvent>>
    @Query("SELECT COUNT(*) FROM browse_events")
    fun observeCount(): Flow<Int>
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
