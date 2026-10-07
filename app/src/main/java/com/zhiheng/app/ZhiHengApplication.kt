package com.zhiheng.app

import android.app.Application
import androidx.room.Room
import com.zhiheng.app.capture.CaptureSettings
import com.zhiheng.app.capture.CaptureDiagnostics
import com.zhiheng.app.data.BrowseDatabase
import com.zhiheng.app.data.BrowseRepository

class ZhiHengApplication : Application() {
    val database by lazy { Room.databaseBuilder(this, BrowseDatabase::class.java, "zhiheng.db").addMigrations(BrowseDatabase.MIGRATION_1_2).build() }
    val repository by lazy { BrowseRepository(database) }
    val captureSettings by lazy { CaptureSettings(this) }
    val captureDiagnostics = CaptureDiagnostics()
}
