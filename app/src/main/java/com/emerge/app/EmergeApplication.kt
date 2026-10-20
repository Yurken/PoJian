package com.emerge.app

import android.app.Application
import androidx.room.Room
import com.emerge.app.capture.CaptureSettings
import com.emerge.app.capture.CaptureDiagnostics
import com.emerge.app.data.BrowseDatabase
import com.emerge.app.data.BrowseRepository
import com.emerge.app.data.AudioRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.shareIn

class EmergeApplication : Application() {
    // Keep the existing database filename so a rebrand never strands local history.
    val database by lazy { Room.databaseBuilder(this, BrowseDatabase::class.java, "zhiheng.db").addMigrations(BrowseDatabase.MIGRATION_1_2, BrowseDatabase.MIGRATION_2_3, BrowseDatabase.MIGRATION_3_4, BrowseDatabase.MIGRATION_4_5, BrowseDatabase.MIGRATION_5_6, BrowseDatabase.MIGRATION_6_7, BrowseDatabase.MIGRATION_7_8, BrowseDatabase.MIGRATION_8_9).build() }
    val repository by lazy { BrowseRepository(database) }
    private val historyScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // Long-lived observers share one load. Direct repository first() reads remain
    // transactional and fresh for capture, deletion guards and task scheduling.
    val observedEvents by lazy {
        repository.allEvents.shareIn(historyScope, SharingStarted.WhileSubscribed(5_000, 0), replay = 1)
    }
    val observedSemantics by lazy {
        repository.semantics.shareIn(historyScope, SharingStarted.WhileSubscribed(5_000, 0), replay = 1)
    }
    val audioRepository by lazy { AudioRepository(database) }
    val audioCoordinator by lazy { com.emerge.app.audioanalysis.AudioCoordinator(this) }
    val captureSettings by lazy { CaptureSettings(this) }
    val workThumbnails by lazy { com.emerge.app.data.WorkThumbnailStore(this, database, captureSettings) }
    val localStorageSettings by lazy { com.emerge.app.data.LocalStorageSettings(this) }
    val localStorage by lazy { com.emerge.app.data.LocalStorageManager(this, database, repository, workThumbnails, localStorageSettings) }
    val captureDiagnostics = CaptureDiagnostics()
    val monitorTasks by lazy { com.emerge.app.monitoring.MonitorTaskStore(database) }
    val monitor by lazy { com.emerge.app.monitoring.MonitorRuntime(this) }
    private val captureTextCleaner by lazy {
        com.emerge.app.capture.CaptureTextCleaner { text, config, current ->
            audioCoordinator.settings.recordRequest()
            com.emerge.app.capture.CaptureCleanupClient.request(text, config, BuildConfig.DEBUG, current)
        }
    }

    internal suspend fun cleanCaptureText(text: String, packageName: String, generation: Long,
        permitted: () -> Boolean): String {
        val settings = audioCoordinator.settings
        val config = settings.config.value
        val authorization = settings.authorizationRevision
        val permission = captureSettings.permissionToken(packageName)
        return captureTextCleaner.clean(text, config, "$authorization|$generation|$packageName|$permission") {
            permission != null && captureSettings.isPermissionCurrent(packageName, permission) &&
                repository.captureGeneration == generation && permitted() &&
                settings.authorizationRevision == authorization && settings.config.value == config &&
                captureSettings.permissionToken(packageName) == permission
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.BUNDLED_DEMO_DATA) {
            com.emerge.app.data.DemoSnapshotInstaller.install(dataDir) { assets.open("demo-snapshot.zip") }
            // Also restore online analysis when upgrading the earlier offline demo,
            // without reimporting its database or resetting later user choices.
            val service = getSharedPreferences("audio_analysis", MODE_PRIVATE)
            if (!service.getBoolean("demo_online_migrated", false)) {
                check(service.edit().putBoolean("enabled", true).putBoolean("demo_online_migrated", true).commit())
            }
        }
        // The accessibility service and audio ingestion continue to publish while the Activity is backgrounded.
        monitor
        workThumbnails
        localStorage
    }
}
