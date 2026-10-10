package com.emerge.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/** Semantic output belongs to an existing encounter and never creates another visit. */
@Entity(
    tableName = "browse_semantics",
    foreignKeys = [ForeignKey(entity = BrowseEvent::class, parentColumns = ["id"],
        childColumns = ["eventId"], onDelete = ForeignKey.CASCADE)]
)
data class BrowseSemantic(
    @PrimaryKey val eventId: Long,
    val inputText: String,
    val analyzedAt: Long,
    val claimsJson: String,
    val inputKind: String
)
