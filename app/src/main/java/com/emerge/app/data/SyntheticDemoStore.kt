package com.emerge.app.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import com.emerge.app.analysis.SemanticsCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** This database is never injected into the production Repository or monitoring runtime. */
object SyntheticDemoStore {
    private val mutex = Mutex()

    suspend fun load(context: Context): SyntheticDataset = withContext(Dispatchers.IO) {
        mutex.withLock {
            val data = context.assets.open(SyntheticDataset.ASSET).bufferedReader().use { SyntheticDataset.decode(it.readText()) }
            val db = Room.databaseBuilder(context.applicationContext, BrowseDatabase::class.java, SyntheticDataset.DATABASE).build()
            try {
                val dao = db.browseEventDao()
                db.withTransaction {
                    // Replace only this versioned, dedicated fixture DB. Interrupted seeding rolls back.
                    dao.clear()
                    dao.trimWorks()
                    data.works.forEach { dao.insertWork(it) }
                    data.events.forEach { dao.insert(it) }
                    data.samples.forEach { dao.insertSample(it) }
                    data.semantics.forEach { dao.putSemantics(SemanticsCodec.encode(it)) }
                }
                // Read back through Room; the UI uses the actual persisted events and annotations.
                val events = dao.chronologicalEvents()
                val semantics = data.semantics.map { original ->
                    requireNotNull(dao.semanticsForEvent(original.eventId)?.let(SemanticsCodec::decode))
                }
                data.copy(events = events, semantics = semantics)
            } finally {
                db.close()
            }
        }
    }
}
