package com.emerge.app.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

data class ResearchProfile(val id: String, val ordinal: Int, val concentration: String, val anxiety: String, val now: Long) {
    val label: String get() = "虚拟用户 ${ordinal.toString().padStart(3, '0')}"
    val conditions: String get() = "预设：集中度${if (concentration == "high") "高" else "低"} · 焦虑表达${if (anxiety == "high") "高" else "低"}"
}
data class ResearchOverview(val workCount: Int, val versionCount: Int, val contactCount: Int, val profiles: List<ResearchProfile>)
data class ResearchWork(val id: String, val media: String, val domain: String, val split: String, val text: String)

/** Immutable experiment store. Deliberately never passed to Room BrowseRepository, monitoring or upload. */
object ResearchDatabaseStore {
    private val mutex = Mutex()
    private val installer = ResearchAssetInstaller()
    private suspend fun <T> read(context: Context, block: (SQLiteDatabase, JSONObject) -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            val task = currentCoroutineContext()
            val metadata = context.assets.open("synthetic/research-v2.json").bufferedReader().use { JSONObject(it.readText()) }
            val path = installer.install(File(context.noBackupFilesDir, "research"), metadata,
                { context.assets.open("synthetic/research-v2.sqlite.gz") }, { task.ensureActive() })
            SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                check(db.version == 1)
                check(db.rawQuery("SELECT value FROM metadata WHERE key='kind'", null).use {
                    it.moveToFirst() && it.getString(0) == "controlled_synthetic_research"
                })
                task.ensureActive()
                block(db, metadata)
            }
        }
    }

    suspend fun overview(context: Context): ResearchOverview = read(context) { db, metadata ->
        val profiles = db.rawQuery("""SELECT p.id,p.ordinal,p.concentration,p.anxiety,
            (SELECT MAX(d.start)+43200000 FROM user_days d WHERE d.profile_id=p.id) AS now
            FROM profiles p ORDER BY p.ordinal""", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(ResearchProfile(cursor.getString(0), cursor.getInt(1),
                cursor.getString(2), cursor.getString(3), cursor.getLong(4))) }
        }
        val counts = metadata.getJSONObject("counts")
        ResearchOverview(counts.getInt("works"), counts.getInt("versions"), counts.getInt("contacts"), profiles)
    }

    suspend fun load(context: Context, profile: ResearchProfile): SyntheticDataset = read(context) { db, _ ->
        val contacts = db.rawQuery("SELECT * FROM project_contacts WHERE profile_id=? ORDER BY timestamp,row_id", arrayOf(profile.id)).use(::objects)
        require(contacts.isNotEmpty())
        val emotions = db.rawQuery("SELECT * FROM project_emotions WHERE profile_id=? ORDER BY timestamp,work_id", arrayOf(profile.id)).use(::objects)
        ResearchProjection.decode(profile.id, profile.now, contacts, emotions)
    }

    /** Bounded corpus pages: no full-corpus decoding during Compose layout or scrolling. */
    suspend fun works(context: Context, page: Int): List<ResearchWork> = read(context) { db, _ ->
        require(page >= 0 && page <= 10000)
        db.rawQuery("""SELECT w.id,w.media_type,w.domain,w.split,v.text FROM works w
            JOIN versions v ON v.work_id=w.id AND v.ordinal=3 ORDER BY w.id LIMIT 40 OFFSET ?""",
            arrayOf((page * 40).toString())).use { cursor ->
            buildList { while (cursor.moveToNext()) add(ResearchWork(cursor.getString(0), cursor.getString(1),
                cursor.getString(2), cursor.getString(3), cursor.getString(4))) }
        }
    }

    private fun objects(cursor: Cursor): List<JSONObject> = buildList {
        while (cursor.moveToNext()) add(JSONObject().apply {
            for (column in 0 until cursor.columnCount) put(cursor.getColumnName(column), when (cursor.getType(column)) {
                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(column)
                Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                else -> cursor.getString(column)
            })
        })
    }
}
