package com.emerge.app

import android.app.Application
import androidx.room.Room
import com.emerge.app.capture.CaptureSettings
import com.emerge.app.capture.CaptureDiagnostics
import com.emerge.app.data.BrowseDatabase
import com.emerge.app.data.BrowseRepository

class EmergeApplication : Application() {
    // Keep the existing database filename so a rebrand never strands local history.
    val database by lazy { Room.databaseBuilder(this, BrowseDatabase::class.java, "zhiheng.db").addMigrations(BrowseDatabase.MIGRATION_1_2).build() }
    val repository by lazy { BrowseRepository(database) }
    val captureSettings by lazy { CaptureSettings(this) }
    val captureDiagnostics = CaptureDiagnostics()
}
