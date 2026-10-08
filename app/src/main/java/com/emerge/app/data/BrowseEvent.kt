package com.emerge.app.data

import androidx.room.*

@Entity(
    tableName = "browse_events",
    foreignKeys = [ForeignKey(entity = AudioSession::class, parentColumns = ["id"],
        childColumns = ["audioSessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("audioSessionId")]
)
data class BrowseEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val timestamp: Long,
    val eventType: Int,
    val visibleText: String,
    @androidx.room.ColumnInfo(defaultValue = "0") val endTimestamp: Long = 0,
    @androidx.room.ColumnInfo(defaultValue = "0") val durationMillis: Long = 0,
    @ColumnInfo(defaultValue = "0") val sampleCount: Int = 0,
    @ColumnInfo(defaultValue = "'accessibility'") val sourceType: String = "accessibility",
    @ColumnInfo(defaultValue = "NULL") val audioSessionId: String? = null,
    @ColumnInfo(defaultValue = "0") val mediaStartMillis: Long = 0,
    @ColumnInfo(defaultValue = "0") val mediaEndMillis: Long = 0
)
