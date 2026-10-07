package com.zhiheng.app.data

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
    @Query("DELETE FROM browse_events") suspend fun clear()
    @Query("DELETE FROM browse_events WHERE id NOT IN (SELECT id FROM browse_events ORDER BY timestamp DESC, id DESC LIMIT 5000)")
    suspend fun trim()
}
