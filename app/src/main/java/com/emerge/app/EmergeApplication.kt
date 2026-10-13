package com.emerge.app

import android.app.Application
import androidx.room.Room
import com.emerge.app.capture.CaptureSettings
import com.emerge.app.capture.CaptureDiagnostics
import com.emerge.app.data.BrowseDatabase
import com.emerge.app.data.BrowseRepository
import com.emerge.app.data.AudioRepository

class EmergeApplication : Application() {
    // Keep the existing database filename so a rebrand never strands local history.
    val database by lazy { Room.databaseBuilder(this, BrowseDatabase::class.java, "zhiheng.db").addMigrations(BrowseDatabase.MIGRATION_1_2, BrowseDatabase.MIGRATION_2_3, BrowseDatabase.MIGRATION_3_4, BrowseDatabase.MIGRATION_4_5, BrowseDatabase.MIGRATION_5_6, BrowseDatabase.MIGRATION_6_7, BrowseDatabase.MIGRATION_7_8).build() }
    val repository by lazy { BrowseRepository(database) }
    val audioRepository by lazy { AudioRepository(database) }
    val audioCoordinator by lazy { com.emerge.app.audioanalysis.AudioCoordinator(this) }
    val captureSettings by lazy { CaptureSettings(this) }
    val workThumbnails by lazy { com.emerge.app.data.WorkThumbnailStore(this, database, captureSettings) }
    val captureDiagnostics = CaptureDiagnostics()
    val monitorTasks by lazy { com.emerge.app.monitoring.MonitorTaskStore(database) }
    val monitor by lazy { com.emerge.app.monitoring.MonitorRuntime(this) }

    override fun onCreate() {
        super.onCreate()
        // The accessibility service and audio ingestion continue to publish while the Activity is backgrounded.
        monitor
        workThumbnails
    }
}
