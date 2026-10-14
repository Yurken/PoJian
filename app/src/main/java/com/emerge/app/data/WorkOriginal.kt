package com.emerge.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** The platform page retains the work's video/gallery/article, not an observed screenshot. */
@Entity(tableName = "work_originals", foreignKeys = [ForeignKey(entity = ObservedWork::class,
    parentColumns = ["workKey"], childColumns = ["workKey"], onDelete = ForeignKey.CASCADE)])
data class WorkOriginal(@PrimaryKey val workKey: String, val url: String, val source: String, val savedAt: Long)

@Dao interface WorkOriginalDao {
    @Query("SELECT * FROM work_originals WHERE workKey=:key") fun observe(key: String): Flow<WorkOriginal?>
    @Query("SELECT * FROM work_originals WHERE workKey=:key") suspend fun get(key: String): WorkOriginal?
    @Query("SELECT * FROM observed_works WHERE workKey=:key") suspend fun work(key: String): ObservedWork?
    @Query("SELECT * FROM observed_works WHERE workKey=:key") fun observeWork(key: String): Flow<ObservedWork?>
    @Query("SELECT * FROM observed_works") suspend fun works(): List<ObservedWork>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(row: WorkOriginal)
    @Upsert suspend fun put(row: WorkOriginal)
}
