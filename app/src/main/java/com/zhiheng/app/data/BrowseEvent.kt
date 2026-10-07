package com.zhiheng.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "browse_events")
data class BrowseEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val timestamp: Long,
    val eventType: Int,
    val visibleText: String,
    @androidx.room.ColumnInfo(defaultValue = "0") val endTimestamp: Long = 0,
    @androidx.room.ColumnInfo(defaultValue = "0") val durationMillis: Long = 0,
    @androidx.room.ColumnInfo(defaultValue = "0") val sampleCount: Int = 0
)
