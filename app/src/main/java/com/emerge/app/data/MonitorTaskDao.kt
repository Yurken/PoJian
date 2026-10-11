package com.emerge.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MonitorTaskDao {
    @Query("SELECT * FROM monitor_tasks ORDER BY createdAt DESC,id DESC")
    fun observeTasks(): Flow<List<MonitorTask>>
    @Query("SELECT * FROM monitor_tasks ORDER BY createdAt,id")
    suspend fun all(): List<MonitorTask>
    @Query("SELECT * FROM monitor_tasks WHERE id=:id")
    suspend fun get(id: String): MonitorTask?
    @Insert suspend fun insert(task: MonitorTask)
    @Update suspend fun update(task: MonitorTask): Int
    @Insert suspend fun insertInputs(inputs: List<MonitorTaskInput>)
    @Query("SELECT * FROM monitor_task_inputs WHERE taskId=:id ORDER BY eventId")
    suspend fun inputs(id: String): List<MonitorTaskInput>
    @Query("SELECT * FROM browse_events WHERE id=:id")
    suspend fun event(id: Long): BrowseEvent?
    @Query("SELECT COUNT(*) FROM monitor_tasks WHERE status IN ('queued','running')")
    suspend fun pendingCount(): Int
    @Query("DELETE FROM monitor_tasks WHERE expiresAt<=:now")
    suspend fun deleteExpired(now: Long): Int
    @Query("DELETE FROM monitor_tasks")
    suspend fun clear()
    @Query("SELECT i.eventId,t.status FROM monitor_tasks t INNER JOIN monitor_task_inputs i ON i.taskId=t.id ORDER BY t.createdAt DESC,t.id DESC")
    fun observeInputStates(): Flow<List<MonitorTaskInputState>>
    @Query("SELECT MIN(nextAttemptAt) FROM monitor_tasks WHERE status='queued' AND expiresAt>:now")
    suspend fun nextReadyAt(now: Long): Long?
}
