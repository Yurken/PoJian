PRAGMA user_version = 2;
CREATE TABLE metadata (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL);
CREATE TABLE works (
 id TEXT PRIMARY KEY NOT NULL, package_name TEXT NOT NULL, media_type TEXT NOT NULL,
 domain TEXT NOT NULL, author_id TEXT NOT NULL, split TEXT NOT NULL,
 identity_group TEXT NOT NULL, near_duplicate_group TEXT NOT NULL, payload TEXT NOT NULL
);
CREATE INDEX works_media_domain ON works(media_type, domain);
CREATE INDEX works_split_group ON works(split, identity_group);
CREATE TABLE versions (
 id TEXT PRIMARY KEY NOT NULL, work_id TEXT NOT NULL REFERENCES works(id), ordinal INTEGER NOT NULL,
 observed_at INTEGER NOT NULL, available_at INTEGER NOT NULL, text TEXT NOT NULL, payload TEXT NOT NULL,
 UNIQUE(id, work_id), UNIQUE(work_id, ordinal), CHECK(available_at >= observed_at)
);
CREATE TABLE arguments (
 version_id TEXT PRIMARY KEY NOT NULL, work_id TEXT NOT NULL, payload TEXT NOT NULL,
 FOREIGN KEY(version_id, work_id) REFERENCES versions(id, work_id)
);
CREATE INDEX arguments_work ON arguments(work_id);
CREATE TABLE claim_presentations (
 version_id TEXT PRIMARY KEY NOT NULL, work_id TEXT NOT NULL, available_at INTEGER NOT NULL,
 provenance TEXT NOT NULL CHECK(provenance = 'AI_authored_rules_unreviewed'), payload TEXT NOT NULL,
 FOREIGN KEY(version_id, work_id) REFERENCES versions(id, work_id)
);
CREATE TABLE emotions (
 id TEXT PRIMARY KEY NOT NULL, version_id TEXT NOT NULL, work_id TEXT NOT NULL,
 expression TEXT NOT NULL, attribution TEXT NOT NULL, holder TEXT NOT NULL, payload TEXT NOT NULL,
 FOREIGN KEY(version_id, work_id) REFERENCES versions(id, work_id)
);
CREATE INDEX emotions_version ON emotions(version_id);
CREATE TABLE profiles (
 id TEXT PRIMARY KEY NOT NULL, ordinal INTEGER NOT NULL UNIQUE, user_split TEXT NOT NULL,
 concentration TEXT NOT NULL, anxiety TEXT NOT NULL, payload TEXT NOT NULL
);
CREATE TABLE user_days (
 id TEXT PRIMARY KEY NOT NULL, profile_id TEXT NOT NULL REFERENCES profiles(id), scheduled_day INTEGER NOT NULL,
 user_split TEXT NOT NULL, time_split TEXT NOT NULL, start INTEGER NOT NULL, end INTEGER NOT NULL,
 status TEXT NOT NULL, payload TEXT NOT NULL, UNIQUE(id, profile_id), UNIQUE(profile_id, scheduled_day), CHECK(end > start)
);
CREATE TABLE contacts (
 row_id INTEGER PRIMARY KEY, id TEXT NOT NULL UNIQUE, profile_id TEXT NOT NULL REFERENCES profiles(id),
 day_id TEXT NOT NULL, work_id TEXT NOT NULL, version_id TEXT NOT NULL, timestamp INTEGER NOT NULL,
 user_split TEXT NOT NULL, time_split TEXT NOT NULL, payload TEXT NOT NULL,
 FOREIGN KEY(day_id, profile_id) REFERENCES user_days(id, profile_id),
 FOREIGN KEY(version_id, work_id) REFERENCES versions(id, work_id)
);
CREATE INDEX contacts_profile_time ON contacts(profile_id, timestamp, row_id);
CREATE INDEX contacts_day ON contacts(day_id, profile_id);
CREATE INDEX contacts_version ON contacts(version_id, work_id);
CREATE TABLE visible_intervals (
 contact_row_id INTEGER NOT NULL REFERENCES contacts(row_id), ordinal INTEGER NOT NULL,
 start INTEGER NOT NULL, end INTEGER NOT NULL, PRIMARY KEY(contact_row_id, ordinal), CHECK(end > start)
);
CREATE TABLE increments (
 id TEXT PRIMARY KEY NOT NULL, split TEXT NOT NULL, current_version_id TEXT NOT NULL REFERENCES versions(id),
 query_at INTEGER NOT NULL, payload TEXT NOT NULL
);
CREATE INDEX increments_split ON increments(split);
CREATE TABLE increment_history (
 increment_id TEXT NOT NULL REFERENCES increments(id), ordinal INTEGER NOT NULL, version_id TEXT NOT NULL,
 work_id TEXT NOT NULL, observed_at INTEGER NOT NULL, available_at INTEGER NOT NULL,
 PRIMARY KEY(increment_id, ordinal), FOREIGN KEY(version_id, work_id) REFERENCES versions(id, work_id)
);
CREATE TABLE increment_labels (id TEXT PRIMARY KEY NOT NULL REFERENCES increments(id), payload TEXT NOT NULL);
CREATE TABLE feedback (
 id TEXT PRIMARY KEY NOT NULL, profile_id TEXT NOT NULL, day_id TEXT NOT NULL, available_at INTEGER NOT NULL,
 payload TEXT NOT NULL, FOREIGN KEY(day_id, profile_id) REFERENCES user_days(id, profile_id)
);
CREATE INDEX feedback_profile_time ON feedback(profile_id, available_at);
CREATE TABLE traces (id TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL);
CREATE TABLE trace_labels (id TEXT PRIMARY KEY NOT NULL REFERENCES traces(id), payload TEXT NOT NULL);
CREATE TABLE safety (id TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL);
CREATE TABLE safety_labels (id TEXT PRIMARY KEY NOT NULL REFERENCES safety(id), payload TEXT NOT NULL);

-- A contact pins its observed version. Never join MAX(version) here: that leaks future text.
CREATE VIEW project_contacts AS
SELECT c.row_id, c.profile_id, c.work_id, c.timestamp, c.payload AS contact_json,
 w.package_name, w.media_type, w.author_id, v.observed_at, v.available_at, v.text,
 a.payload AS argument_json, p.payload AS presentation_json, p.version_id AS presentation_version_id,
 p.work_id AS presentation_work_id, p.available_at AS presentation_available_at, p.provenance AS presentation_provenance
FROM contacts c JOIN works w ON w.id=c.work_id
 JOIN versions v ON v.id=c.version_id AND v.work_id=c.work_id
 JOIN arguments a ON a.version_id=v.id AND a.work_id=v.work_id
 LEFT JOIN claim_presentations p ON p.version_id=v.id AND p.work_id=v.work_id;
CREATE VIEW project_emotions AS
SELECT c.profile_id, c.work_id, c.timestamp, v.text, e.payload AS emotion_json
FROM contacts c JOIN versions v ON v.id=c.version_id AND v.work_id=c.work_id
 JOIN emotions e ON e.version_id=v.id AND e.work_id=v.work_id;
