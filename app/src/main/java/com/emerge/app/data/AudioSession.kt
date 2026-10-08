package com.emerge.app.data

import androidx.room.*

object AudioSource {
    const val IMPORT = "import"
    const val PLAYBACK = "playback"
}

object AudioSessionStatus {
    const val CREATED = "created"
    const val RECORDING = "recording"
    const val TRANSCRIBING = "transcribing"
    const val COMPLETED = "completed"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
}

@Entity(tableName = "audio_sessions", indices = [Index("packageName")])
data class AudioSession(
    @PrimaryKey val id: String,
    val packageName: String,
    val source: String,
    val title: String,
    val startedAt: Long,
    @ColumnInfo(defaultValue = "'created'") val status: String = AudioSessionStatus.CREATED,
    @ColumnInfo(defaultValue = "0") val generation: Long = 0,
    // Media duration is metadata; it is never added to observed browsing time.
    @ColumnInfo(defaultValue = "0") val durationMillis: Long = 0,
    @ColumnInfo(defaultValue = "NULL") val errorMessage: String? = null,
    @ColumnInfo(defaultValue = "NULL") val analysisJson: String? = null,
    @ColumnInfo(defaultValue = "0") val contentVersion: Long = 0,
    @ColumnInfo(defaultValue = "'zipformer-zh-14M-2023-02-23/int8'")
    val modelId: String = "zipformer-zh-14M-2023-02-23/int8"
)

@Entity(
    tableName = "audio_transcripts",
    foreignKeys = [
        ForeignKey(entity = AudioSession::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = BrowseEvent::class, parentColumns = ["id"], childColumns = ["browseEventId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("sessionId"), Index(value = ["browseEventId"], unique = true),
        Index(value = ["sessionId", "offsetStartMillis", "offsetEndMillis"], unique = true)]
)
data class AudioTranscript(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val browseEventId: Long,
    val offsetStartMillis: Long,
    val offsetEndMillis: Long,
    val originalText: String,
    @ColumnInfo(defaultValue = "NULL") val correctedText: String? = null,
    val createdAt: Long
) {
    @get:Ignore val displayText: String get() = correctedText ?: originalText
}
