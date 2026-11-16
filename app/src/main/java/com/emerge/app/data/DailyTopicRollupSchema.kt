package com.emerge.app.data

import androidx.sqlite.db.SupportSQLiteDatabase

/** Triggers maintain counts on cascading deletes too (audio removal, pruning and clearAllTables). */
object DailyTopicRollupSchema {
    fun create(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS daily_topic_rollup (
            dateKey TEXT NOT NULL, topicName TEXT NOT NULL, viewEvents INTEGER NOT NULL,
            uniqueWorks INTEGER NOT NULL, dedupFacts INTEGER NOT NULL, newFacts INTEGER NOT NULL,
            guidanceEvents INTEGER NOT NULL, guidanceDurationSec INTEGER NOT NULL,
            guidanceDurationMillis INTEGER NOT NULL, analyzedEvents INTEGER NOT NULL, PRIMARY KEY(dateKey,topicName))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS rollup_contributions (
            eventId INTEGER NOT NULL, dateKey TEXT NOT NULL, topicName TEXT NOT NULL, workKey TEXT NOT NULL,
            viewEvents INTEGER NOT NULL, guidanceEvents INTEGER NOT NULL, guidanceDurationMillis INTEGER NOT NULL,
            analyzedEvents INTEGER NOT NULL, capturedAt INTEGER NOT NULL, semanticFingerprint TEXT NOT NULL, PRIMARY KEY(eventId,dateKey,topicName),
            FOREIGN KEY(eventId) REFERENCES browse_events(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_rollup_contributions_dateKey_topicName_workKey ON rollup_contributions(dateKey,topicName,workKey)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_rollup_contributions_dateKey_eventId ON rollup_contributions(dateKey,eventId)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS rollup_facts (
            eventId INTEGER NOT NULL, dateKey TEXT NOT NULL, topicName TEXT NOT NULL, factKey TEXT NOT NULL,
            factText TEXT NOT NULL, sourcePlatform TEXT NOT NULL, capturedAt INTEGER NOT NULL, isNew INTEGER NOT NULL,
            PRIMARY KEY(eventId,dateKey,topicName,factKey),
            FOREIGN KEY(eventId) REFERENCES browse_events(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_rollup_facts_dateKey_topicName_factKey_isNew ON rollup_facts(dateKey,topicName,factKey,isNew)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_rollup_facts_capturedAt ON rollup_facts(capturedAt)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_rollup_facts_factKey ON rollup_facts(factKey)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS rollup_dirty (eventId INTEGER NOT NULL PRIMARY KEY,
            FOREIGN KEY(eventId) REFERENCES browse_events(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
        db.execSQL("CREATE TABLE IF NOT EXISTS rollup_metadata (id INTEGER NOT NULL PRIMARY KEY, zone TEXT NOT NULL)")
        installTriggers(db)
        db.execSQL("INSERT OR IGNORE INTO rollup_dirty SELECT id FROM browse_events")
    }

    fun installTriggers(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS rollup_event_insert AFTER INSERT ON browse_events
            BEGIN INSERT OR IGNORE INTO rollup_dirty VALUES(NEW.id); END""")
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS rollup_event_update AFTER UPDATE ON browse_events
            BEGIN INSERT OR IGNORE INTO rollup_dirty VALUES(NEW.id); END""")
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS rollup_semantic_insert AFTER INSERT ON browse_semantics
            BEGIN INSERT OR IGNORE INTO rollup_dirty VALUES(NEW.eventId); END""")
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS rollup_semantic_update AFTER UPDATE ON browse_semantics
            BEGIN INSERT OR IGNORE INTO rollup_dirty VALUES(NEW.eventId); END""")
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS rollup_semantic_delete AFTER DELETE ON browse_semantics
            BEGIN INSERT OR IGNORE INTO rollup_dirty SELECT id FROM browse_events WHERE id=OLD.eventId; END""")
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS rollup_contribution_insert AFTER INSERT ON rollup_contributions
            BEGIN
            INSERT OR IGNORE INTO daily_topic_rollup VALUES(NEW.dateKey,NEW.topicName,0,0,0,0,0,0,0,0);
            UPDATE daily_topic_rollup SET viewEvents=viewEvents+NEW.viewEvents,
                uniqueWorks=uniqueWorks+CASE WHEN NEW.viewEvents=1 AND
                    NOT EXISTS (SELECT 1 FROM rollup_contributions WHERE dateKey=NEW.dateKey AND topicName=NEW.topicName AND workKey=NEW.workKey AND viewEvents=1 AND eventId!=NEW.eventId) THEN 1 ELSE 0 END,
                guidanceEvents=guidanceEvents+NEW.guidanceEvents,
                guidanceDurationMillis=guidanceDurationMillis+NEW.guidanceDurationMillis,
                guidanceDurationSec=(guidanceDurationMillis+NEW.guidanceDurationMillis)/1000,
                analyzedEvents=analyzedEvents+NEW.analyzedEvents
            WHERE dateKey=NEW.dateKey AND topicName=NEW.topicName;
            END""")
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS rollup_contribution_delete AFTER DELETE ON rollup_contributions
            BEGIN
            UPDATE daily_topic_rollup SET viewEvents=viewEvents-OLD.viewEvents,
                uniqueWorks=uniqueWorks-CASE WHEN OLD.viewEvents=1 AND NOT EXISTS
                    (SELECT 1 FROM rollup_contributions WHERE dateKey=OLD.dateKey AND topicName=OLD.topicName AND workKey=OLD.workKey AND viewEvents=1) THEN 1 ELSE 0 END,
                guidanceEvents=guidanceEvents-OLD.guidanceEvents,
                guidanceDurationMillis=guidanceDurationMillis-OLD.guidanceDurationMillis,
                guidanceDurationSec=(guidanceDurationMillis-OLD.guidanceDurationMillis)/1000,
                analyzedEvents=analyzedEvents-OLD.analyzedEvents
            WHERE dateKey=OLD.dateKey AND topicName=OLD.topicName;
            DELETE FROM daily_topic_rollup WHERE dateKey=OLD.dateKey AND topicName=OLD.topicName
                AND viewEvents=0 AND uniqueWorks=0 AND dedupFacts=0 AND newFacts=0
                AND guidanceEvents=0 AND guidanceDurationMillis=0 AND analyzedEvents=0;
            END""")
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS rollup_fact_insert AFTER INSERT ON rollup_facts
            BEGIN
            INSERT OR IGNORE INTO daily_topic_rollup VALUES(NEW.dateKey,NEW.topicName,0,0,0,0,0,0,0,0);
            UPDATE daily_topic_rollup SET dedupFacts=dedupFacts+CASE WHEN
                NOT EXISTS (SELECT 1 FROM rollup_facts WHERE dateKey=NEW.dateKey AND topicName=NEW.topicName AND factKey=NEW.factKey AND eventId!=NEW.eventId) THEN 1 ELSE 0 END,
                newFacts=newFacts+CASE WHEN NEW.isNew=1 AND
                NOT EXISTS (SELECT 1 FROM rollup_facts WHERE dateKey=NEW.dateKey AND topicName=NEW.topicName AND factKey=NEW.factKey AND isNew=1 AND eventId!=NEW.eventId) THEN 1 ELSE 0 END
            WHERE dateKey=NEW.dateKey AND topicName=NEW.topicName;
            END""")
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS rollup_fact_delete AFTER DELETE ON rollup_facts
            BEGIN
            UPDATE daily_topic_rollup SET dedupFacts=dedupFacts-CASE WHEN NOT EXISTS
                (SELECT 1 FROM rollup_facts WHERE dateKey=OLD.dateKey AND topicName=OLD.topicName AND factKey=OLD.factKey) THEN 1 ELSE 0 END,
                newFacts=newFacts-CASE WHEN OLD.isNew=1 AND NOT EXISTS
                (SELECT 1 FROM rollup_facts WHERE dateKey=OLD.dateKey AND topicName=OLD.topicName AND factKey=OLD.factKey AND isNew=1) THEN 1 ELSE 0 END
            WHERE dateKey=OLD.dateKey AND topicName=OLD.topicName;
            DELETE FROM daily_topic_rollup WHERE dateKey=OLD.dateKey AND topicName=OLD.topicName
                AND viewEvents=0 AND uniqueWorks=0 AND dedupFacts=0 AND newFacts=0
                AND guidanceEvents=0 AND guidanceDurationMillis=0 AND analyzedEvents=0;
            END""")
    }
}
