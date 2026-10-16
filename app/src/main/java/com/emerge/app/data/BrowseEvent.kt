package com.emerge.app.data

import androidx.room.*

@Entity(
    tableName = "browse_events",
    foreignKeys = [ForeignKey(entity = AudioSession::class, parentColumns = ["id"],
        childColumns = ["audioSessionId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = BrowseEvent::class, parentColumns = ["id"], childColumns = ["parentEventId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("audioSessionId"), Index("parentEventId")]
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
    @ColumnInfo(defaultValue = "0") val mediaEndMillis: Long = 0,
    @ColumnInfo(defaultValue = "NULL") val workKey: String? = null,
    @ColumnInfo(defaultValue = "'unknown'") val identityMethod: String = "unknown",
    @ColumnInfo(defaultValue = "NULL") val visibilityIntervals: String? = null,
    @ColumnInfo(defaultValue = "NULL") val parentEventId: Long? = null
)

/** Non-destructive historical display: keep the stored snapshot valid for existing analysis. */
val BrowseEvent.contentText: String
    get() = com.emerge.app.capture.PlatformTextCleaner.forSource(visibleText, sourceType)

/** Necessary source metadata for machine-read text, never a claim of a second viewing. */
val BrowseEvent.visualSourceLabel: String?
    get() = when (sourceType) {
        "screen_ocr" -> "画面文字识别"
        "visual_model" -> "视觉模型提取"
        else -> null
    }?.let { label -> parentEventId?.let { "$label · 关联记录 #$it" } ?: label }
