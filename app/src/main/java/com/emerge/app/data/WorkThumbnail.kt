package com.emerge.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "work_thumbnails", foreignKeys = [ForeignKey(entity = ObservedWork::class,
    parentColumns = ["workKey"], childColumns = ["workKey"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["fileName"], unique = true)])
data class WorkThumbnail(@PrimaryKey val workKey: String, val packageName: String, val fileName: String,
    val capturedAt: Long, val kind: String, val width: Int, val height: Int, val byteCount: Long)

val WorkThumbnail.sourceLabel: String get() = when(kind) {
    "video_frame" -> "已采样视频帧"
    "content_crop" -> "正文截图"
    else -> "页面截图"
}

@Dao interface WorkThumbnailDao {
    @Query("SELECT * FROM work_thumbnails ORDER BY capturedAt, workKey") fun observe(): Flow<List<WorkThumbnail>>
    @Query("SELECT * FROM work_thumbnails ORDER BY capturedAt, workKey") suspend fun all(): List<WorkThumbnail>
    @Query("SELECT * FROM work_thumbnails WHERE workKey=:key") suspend fun get(key: String): WorkThumbnail?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(row: WorkThumbnail): Long
    @Query("DELETE FROM work_thumbnails WHERE workKey=:key AND fileName=:file") suspend fun delete(key: String, file: String)
}
