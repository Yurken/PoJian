package com.zhiheng.app.data

import androidx.room.withTransaction

class BrowseRepository(private val database: BrowseDatabase) {
    private val dao = database.browseEventDao()
    val recentEvents = dao.observeRecent()
    val allEvents = dao.observeAll()
    val count = dao.observeCount()
    suspend fun record(event: BrowseEvent) = database.withTransaction { val id = dao.insert(event); dao.trim(); id }
    suspend fun updateSession(event: BrowseEvent): Long = database.withTransaction {
        if (dao.update(event) > 0) event.id else dao.insert(event.copy(id = 0)).also { dao.trim() }
    }
    suspend fun clear() = dao.clear()
}
