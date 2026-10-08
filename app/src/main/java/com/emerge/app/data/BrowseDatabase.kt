package com.emerge.app.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [BrowseEvent::class], version = 2, exportSchema = true)
abstract class BrowseDatabase : RoomDatabase() {
    companion object {
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE browse_events ADD COLUMN endTimestamp INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE browse_events ADD COLUMN durationMillis INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE browse_events ADD COLUMN sampleCount INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
    abstract fun browseEventDao(): BrowseEventDao
}
