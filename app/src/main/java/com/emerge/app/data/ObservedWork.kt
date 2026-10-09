package com.emerge.app.data

import androidx.room.*

/** Content identity is separate from a visit and from the samples within that visit. */
@Entity(tableName = "observed_works")
data class ObservedWork(
    @PrimaryKey val workKey: String,
    val packageName: String,
    val title: String,
    val author: String,
    val identityMethod: String,
    val firstSeenAt: Long
)

@Entity(tableName = "browse_samples", foreignKeys = [ForeignKey(
    entity = BrowseEvent::class, parentColumns = ["id"], childColumns = ["visitId"],
    onDelete = ForeignKey.CASCADE)], indices = [Index("visitId")])
data class BrowseSample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val visitId: Long,
    val capturedAt: Long,
    val eventType: Int,
    val visibleText: String
)
