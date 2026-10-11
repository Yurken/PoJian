package com.emerge.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.sqlite.db.SupportSQLiteDatabase

/** Payload is an authenticated encrypted envelope; scheduling metadata contains no raw content or credential. */
@Entity(tableName = "monitor_tasks", foreignKeys = [ForeignKey(entity = BrowseEvent::class,
    parentColumns = ["id"], childColumns = ["anchorEventId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("anchorEventId"), Index(value = ["status", "nextAttemptAt"])])
data class MonitorTask(
    @PrimaryKey val id: String,
    val anchorEventId: Long,
    val payload: String,
    val scopeFingerprint: String,
    val serviceFingerprint: String,
    val authorizationKey: String,
    val status: String,
    val attempts: Int,
    val createdAt: Long,
    val expiresAt: Long,
    val updatedAt: Long,
    val nextAttemptAt: Long,
    val leaseOwner: String,
    val leaseUntil: Long,
    val lastError: String
)

@Entity(tableName = "monitor_task_inputs", primaryKeys = ["taskId", "eventId"],
    foreignKeys = [ForeignKey(entity = MonitorTask::class, parentColumns = ["id"],
        childColumns = ["taskId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = BrowseEvent::class, parentColumns = ["id"],
            childColumns = ["eventId"], onDelete = ForeignKey.CASCADE)], indices = [Index("eventId")])
data class MonitorTaskInput(val taskId: String, val eventId: Long, val inputFingerprint: String)

data class MonitorTaskInputState(val eventId: Long, val status: String)

object MonitorTaskSchema {
    fun create(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS monitor_tasks (
            id TEXT NOT NULL PRIMARY KEY, anchorEventId INTEGER NOT NULL, payload TEXT NOT NULL,
            scopeFingerprint TEXT NOT NULL, serviceFingerprint TEXT NOT NULL, authorizationKey TEXT NOT NULL,
            status TEXT NOT NULL, attempts INTEGER NOT NULL, createdAt INTEGER NOT NULL, expiresAt INTEGER NOT NULL,
            updatedAt INTEGER NOT NULL, nextAttemptAt INTEGER NOT NULL, leaseOwner TEXT NOT NULL,
            leaseUntil INTEGER NOT NULL, lastError TEXT NOT NULL,
            FOREIGN KEY(anchorEventId) REFERENCES browse_events(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_monitor_tasks_anchorEventId ON monitor_tasks(anchorEventId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_monitor_tasks_status_nextAttemptAt ON monitor_tasks(status,nextAttemptAt)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS monitor_task_inputs (
            taskId TEXT NOT NULL, eventId INTEGER NOT NULL, inputFingerprint TEXT NOT NULL,
            PRIMARY KEY(taskId,eventId),
            FOREIGN KEY(taskId) REFERENCES monitor_tasks(id) ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(eventId) REFERENCES browse_events(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_monitor_task_inputs_eventId ON monitor_task_inputs(eventId)")
        // Version 5→6 predates the OCR parent column.
        installTriggers(db, includeParent = false)
    }

    /** Trigger invalidation prevents an edited input becoming valid again if its old text is restored. */
    fun installTriggers(db: SupportSQLiteDatabase, includeParent: Boolean = true) {
        if (includeParent) db.execSQL("""CREATE TRIGGER IF NOT EXISTS monitor_tasks_parent_changed
            AFTER UPDATE OF parentEventId ON browse_events
            WHEN OLD.parentEventId IS NOT NEW.parentEventId
            BEGIN UPDATE monitor_tasks SET status='cancelled',payload='',leaseOwner='',leaseUntil=0,lastError='input_changed'
            WHERE status IN ('queued','running') AND id IN
                (SELECT taskId FROM monitor_task_inputs WHERE eventId=OLD.id); END""")
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS monitor_tasks_input_changed
            AFTER UPDATE OF visibleText,packageName,sourceType,timestamp,audioSessionId,mediaStartMillis,mediaEndMillis,workKey ON browse_events
            WHEN OLD.visibleText IS NOT NEW.visibleText OR OLD.packageName IS NOT NEW.packageName
                OR OLD.sourceType IS NOT NEW.sourceType OR OLD.timestamp IS NOT NEW.timestamp
                OR OLD.audioSessionId IS NOT NEW.audioSessionId OR OLD.mediaStartMillis IS NOT NEW.mediaStartMillis
                OR OLD.mediaEndMillis IS NOT NEW.mediaEndMillis OR OLD.workKey IS NOT NEW.workKey
            BEGIN UPDATE monitor_tasks SET status='cancelled',payload='',leaseOwner='',leaseUntil=0,lastError='input_changed'
            WHERE status IN ('queued','running') AND id IN
                (SELECT taskId FROM monitor_task_inputs WHERE eventId=OLD.id); END""")
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS monitor_tasks_input_deleted
            BEFORE DELETE ON browse_events
            BEGIN UPDATE monitor_tasks SET status='cancelled',payload='',leaseOwner='',leaseUntil=0,lastError='input_deleted'
            WHERE status IN ('queued','running') AND id IN
                (SELECT taskId FROM monitor_task_inputs WHERE eventId=OLD.id); END""")
    }
}
