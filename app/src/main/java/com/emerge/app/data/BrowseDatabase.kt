package com.emerge.app.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [BrowseEvent::class, AudioSession::class, AudioTranscript::class], version = 3, exportSchema = true)
abstract class BrowseDatabase : RoomDatabase() {
    companion object {
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE browse_events ADD COLUMN endTimestamp INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE browse_events ADD COLUMN durationMillis INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE browse_events ADD COLUMN sampleCount INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS audio_sessions (
                    id TEXT NOT NULL PRIMARY KEY, packageName TEXT NOT NULL, source TEXT NOT NULL,
                    title TEXT NOT NULL, startedAt INTEGER NOT NULL, status TEXT NOT NULL DEFAULT 'created',
                    generation INTEGER NOT NULL DEFAULT 0, durationMillis INTEGER NOT NULL DEFAULT 0,
                    errorMessage TEXT DEFAULT NULL, analysisJson TEXT DEFAULT NULL, contentVersion INTEGER NOT NULL DEFAULT 0,
                    modelId TEXT NOT NULL DEFAULT 'zipformer-zh-14M-2023-02-23/int8')""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_audio_sessions_packageName ON audio_sessions(packageName)")
                // Rebuild to add the session FK while preserving every legacy record and id.
                db.execSQL("""CREATE TABLE browse_events_v3 (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, packageName TEXT NOT NULL,
                    timestamp INTEGER NOT NULL, eventType INTEGER NOT NULL, visibleText TEXT NOT NULL,
                    endTimestamp INTEGER NOT NULL DEFAULT 0, durationMillis INTEGER NOT NULL DEFAULT 0,
                    sampleCount INTEGER NOT NULL DEFAULT 0, sourceType TEXT NOT NULL DEFAULT 'accessibility',
                    audioSessionId TEXT DEFAULT NULL, mediaStartMillis INTEGER NOT NULL DEFAULT 0,
                    mediaEndMillis INTEGER NOT NULL DEFAULT 0,
                    FOREIGN KEY(audioSessionId) REFERENCES audio_sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
                db.execSQL("""INSERT INTO browse_events_v3
                    (id, packageName, timestamp, eventType, visibleText, endTimestamp, durationMillis, sampleCount)
                    SELECT id, packageName, timestamp, eventType, visibleText, endTimestamp, durationMillis, sampleCount FROM browse_events""")
                db.execSQL("DROP TABLE browse_events")
                db.execSQL("ALTER TABLE browse_events_v3 RENAME TO browse_events")
                db.execSQL("CREATE INDEX index_browse_events_audioSessionId ON browse_events(audioSessionId)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS audio_transcripts (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, sessionId TEXT NOT NULL,
                    browseEventId INTEGER NOT NULL, offsetStartMillis INTEGER NOT NULL,
                    offsetEndMillis INTEGER NOT NULL, originalText TEXT NOT NULL,
                    correctedText TEXT DEFAULT NULL, createdAt INTEGER NOT NULL,
                    FOREIGN KEY(sessionId) REFERENCES audio_sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(browseEventId) REFERENCES browse_events(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
                db.execSQL("CREATE INDEX index_audio_transcripts_sessionId ON audio_transcripts(sessionId)")
                db.execSQL("CREATE UNIQUE INDEX index_audio_transcripts_browseEventId ON audio_transcripts(browseEventId)")
                db.execSQL("CREATE UNIQUE INDEX index_audio_transcripts_sessionId_offsetStartMillis_offsetEndMillis ON audio_transcripts(sessionId, offsetStartMillis, offsetEndMillis)")
            }
        }
    }
    abstract fun browseEventDao(): BrowseEventDao
    abstract fun audioDao(): AudioDao
}
