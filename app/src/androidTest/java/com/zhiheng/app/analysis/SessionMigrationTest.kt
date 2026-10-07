package com.zhiheng.app.analysis

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import com.zhiheng.app.data.BrowseDatabase
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionMigrationTest {
    @Test fun oldRecordsSurviveMigrationWithUnknownDuration() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-session-qa.db"
        context.deleteDatabase(name)
        try {
            context.openOrCreateDatabase(name, 0, null).use { db ->
                db.execSQL("CREATE TABLE browse_events (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, packageName TEXT NOT NULL, timestamp INTEGER NOT NULL, eventType INTEGER NOT NULL, visibleText TEXT NOT NULL)")
                db.execSQL("INSERT INTO browse_events VALUES (7, 'com.reader', 1000, 32, 'legacy preserved')")
                db.version = 1
            }
            val room = Room.databaseBuilder(context, BrowseDatabase::class.java, name)
                .addMigrations(BrowseDatabase.MIGRATION_1_2).build()
                try {
                    room.openHelper.writableDatabase.query("SELECT * FROM browse_events WHERE id=7").use { row ->
                        assertTrue(row.moveToFirst())
                        assertEquals("legacy preserved", row.getString(row.getColumnIndexOrThrow("visibleText")))
                        assertEquals(0, row.getInt(row.getColumnIndexOrThrow("sampleCount")))
                        assertEquals(0L, row.getLong(row.getColumnIndexOrThrow("durationMillis")))
                    }
                } finally { room.close() }
        } finally { context.deleteDatabase(name) }
    }
}
