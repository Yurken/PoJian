package com.emerge.app.data

import androidx.room.*

/** Daily counts are materialized; identities remain indexed so multi-day totals are not sums of distinct counts. */
@Entity(tableName = "daily_topic_rollup", primaryKeys = ["dateKey", "topicName"])
data class DailyTopicRollupEntity(
    val dateKey: String, val topicName: String,
    val viewEvents: Int = 0, val uniqueWorks: Int = 0, val dedupFacts: Int = 0,
    val newFacts: Int = 0, val guidanceEvents: Int = 0, val guidanceDurationSec: Long = 0,
    val guidanceDurationMillis: Long = 0, val analyzedEvents: Int = 0
)

@Entity(tableName = "rollup_contributions", primaryKeys = ["eventId", "dateKey", "topicName"],
    foreignKeys = [ForeignKey(entity = BrowseEvent::class, parentColumns = ["id"], childColumns = ["eventId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["dateKey", "topicName", "workKey"]), Index(value = ["dateKey", "eventId"])])
data class RollupContribution(
    val eventId: Long, val dateKey: String, val topicName: String, val workKey: String,
    val viewEvents: Int, val guidanceEvents: Int, val guidanceDurationMillis: Long,
    val analyzedEvents: Int, val capturedAt: Long, val semanticFingerprint: String
)

@Entity(tableName = "rollup_facts", primaryKeys = ["eventId", "dateKey", "topicName", "factKey"],
    foreignKeys = [ForeignKey(entity = BrowseEvent::class, parentColumns = ["id"], childColumns = ["eventId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["dateKey", "topicName", "factKey", "isNew"]), Index(value = ["capturedAt"]), Index(value = ["factKey"])])
data class RollupFact(
    val eventId: Long, val dateKey: String, val topicName: String, val factKey: String,
    val factText: String, val sourcePlatform: String, val capturedAt: Long, val isNew: Boolean
)

@Entity(tableName = "rollup_dirty", foreignKeys = [ForeignKey(entity = BrowseEvent::class,
    parentColumns = ["id"], childColumns = ["eventId"], onDelete = ForeignKey.CASCADE)])
data class RollupDirty(@PrimaryKey val eventId: Long)

@Entity(tableName = "rollup_metadata")
data class RollupMetadata(@PrimaryKey val id: Int = 1, val zone: String)

data class HistoryFact(val factId: String, val factText: String, val sourcePlatform: String, val capturedAt: Long)

data class DailyRollupSummary(
    val days: List<DailyTopicRollupEntity>, val viewEvents: Int, val uniqueWorks: Int,
    val dedupFacts: Int, val newFacts: Int, val analyzedEvents: Int, val guidanceEvents: Int,
    val guidanceDurationSec: Long, val semanticFingerprint: String = ""
)

data class RollupEventTotals(val viewEvents: Int, val uniqueWorks: Int, val analyzedEvents: Int, val guidanceEvents: Int)
data class RollupFactTotals(val dedupFacts: Int, val newFacts: Int)
data class RollupSemanticKey(val eventId: Long, val semanticFingerprint: String)

@Dao
interface DailyTopicRollupDao {
    @Query("SELECT * FROM daily_topic_rollup WHERE dateKey BETWEEN :first AND :last AND (viewEvents > 0 OR dedupFacts > 0 OR analyzedEvents > 0) ORDER BY dateKey,topicName")
    suspend fun days(first: String, last: String): List<DailyTopicRollupEntity>
    @Query("""SELECT COUNT(DISTINCT CASE WHEN viewEvents=1 THEN eventId END) AS viewEvents,
        COUNT(DISTINCT CASE WHEN viewEvents=1 THEN workKey END) AS uniqueWorks,
        COUNT(DISTINCT CASE WHEN analyzedEvents=1 THEN eventId END) AS analyzedEvents,
        COUNT(DISTINCT CASE WHEN guidanceEvents=1 THEN eventId END) AS guidanceEvents
        FROM rollup_contributions WHERE dateKey BETWEEN :first AND :last AND capturedAt<=:through""")
    suspend fun eventTotals(first: String, last: String, through: Long): RollupEventTotals
    @Query("""SELECT COUNT(DISTINCT factKey) AS dedupFacts,
        COUNT(DISTINCT CASE WHEN isNew=1 THEN factKey END) AS newFacts
        FROM rollup_facts WHERE dateKey BETWEEN :first AND :last AND capturedAt<=:through""")
    suspend fun factTotals(first: String, last: String, through: Long): RollupFactTotals
    @Query("""SELECT factKey AS factId, factText, sourcePlatform, capturedAt FROM rollup_facts
        WHERE capturedAt < :before AND (:topic IS NULL OR topicName=:topic)
        GROUP BY factKey ORDER BY MAX(capturedAt) DESC, factKey LIMIT :limit""")
    suspend fun history(topic: String?, limit: Int, before: Long): List<HistoryFact>
    @Query("""SELECT COALESCE(SUM(duration),0) FROM (SELECT MAX(guidanceDurationMillis) AS duration
        FROM rollup_contributions WHERE dateKey BETWEEN :first AND :last AND capturedAt<=:through GROUP BY eventId,dateKey)""")
    suspend fun guidanceMillis(first: String, last: String, through: Long): Long
    @Query("SELECT EXISTS(SELECT 1 FROM rollup_contributions WHERE dateKey BETWEEN :first AND :last AND capturedAt>:through)")
    suspend fun hasFuture(first: String, last: String, through: Long): Boolean
    @Query("""SELECT c.dateKey, c.topicName, SUM(c.viewEvents) AS viewEvents,
        COUNT(DISTINCT CASE WHEN c.viewEvents=1 THEN c.workKey END) AS uniqueWorks,
        (SELECT COUNT(DISTINCT f.factKey) FROM rollup_facts f WHERE f.dateKey=c.dateKey AND f.topicName=c.topicName AND f.capturedAt<=:through) AS dedupFacts,
        (SELECT COUNT(DISTINCT f.factKey) FROM rollup_facts f WHERE f.dateKey=c.dateKey AND f.topicName=c.topicName AND f.capturedAt<=:through AND f.isNew=1) AS newFacts,
        SUM(c.guidanceEvents) AS guidanceEvents, SUM(c.guidanceDurationMillis)/1000 AS guidanceDurationSec,
        SUM(c.guidanceDurationMillis) AS guidanceDurationMillis, SUM(c.analyzedEvents) AS analyzedEvents
        FROM rollup_contributions c WHERE c.dateKey BETWEEN :first AND :last AND c.capturedAt<=:through
        GROUP BY c.dateKey,c.topicName ORDER BY c.dateKey,c.topicName""")
    suspend fun filteredDays(first: String, last: String, through: Long): List<DailyTopicRollupEntity>
    @Query("""SELECT DISTINCT eventId,semanticFingerprint FROM rollup_contributions
        WHERE dateKey BETWEEN :first AND :last AND capturedAt<=:through AND analyzedEvents=1 ORDER BY eventId""")
    suspend fun semanticKeys(first: String, last: String, through: Long): List<RollupSemanticKey>
    @Query("SELECT eventId FROM rollup_dirty ORDER BY eventId LIMIT 128")
    suspend fun dirty(): List<Long>
    @Query("DELETE FROM rollup_dirty WHERE eventId=:eventId") suspend fun clean(eventId: Long)
    @Query("DELETE FROM rollup_contributions WHERE eventId=:eventId") suspend fun removeContributions(eventId: Long)
    @Query("DELETE FROM rollup_facts WHERE eventId=:eventId") suspend fun removeFacts(eventId: Long)
    @Insert suspend fun insertContributions(rows: List<RollupContribution>)
    @Insert suspend fun insertFacts(rows: List<RollupFact>)
    @Query("SELECT zone FROM rollup_metadata WHERE id=1") suspend fun zone(): String?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putMetadata(metadata: RollupMetadata)
}
