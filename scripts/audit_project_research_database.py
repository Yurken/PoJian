"""Independent SQLite/source audit and full App-projection regression inputs (no emulator)."""
import argparse
from contextlib import closing
from datetime import datetime, timedelta, timezone
import hashlib
import json
from pathlib import Path
import sqlite3
import time
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "server"))
from claim_presentation import validate_presentation

ROOT = Path(__file__).resolve().parents[1]
ZONE = timezone(timedelta(hours=8))


def check(condition, message):
    if not condition:
        raise AssertionError(message)


def union_duration(spans, start, end):
    result = 0
    last = start
    for a, b in sorted(spans):
        a, b = max(start, a, last), min(end, b)
        if b > a:
            result += b - a
            last = b
    return result


def expected_windows(profile, contacts, emotions):
    result = []
    for phase in (0, 1):
        now = profile["now"] - (1 - phase) * 7 * 86400000
        today = datetime.fromtimestamp(now / 1000, ZONE).replace(hour=0, minute=0, second=0, microsecond=0)
        for period in ("DAY", "HOURS24", "WEEK"):
            start = now - 86400000 if period == "HOURS24" else int((today - timedelta(days=6 if period == "WEEK" else 0)).timestamp() * 1000)
            selected = [r for r in contacts if start <= r["timestamp"] <= now]
            spans = [span for r in selected for span in json.loads(r["contact_json"])["visible_intervals"]]
            anxious = {r["work_id"] for r in emotions if start <= r["timestamp"] < now and
                       json.loads(r["emotion_json"])["expression"] == "焦虑" and
                       json.loads(r["emotion_json"])["attribution"] in ("affirmed", "reported")}
            result.append(dict(phase=phase, period=period, now=now, start=start,
                               works=len({r["work_id"] for r in selected}), visits=len(selected),
                               duration=union_duration(spans, start, now), anxiety_works=len(anxious)))
    return result


def audit(database, source, output):
    output.mkdir(parents=True, exist_ok=True)
    source_manifest = json.loads((source / "manifest.json").read_text(encoding="utf8"))
    digest = hashlib.sha256(database.read_bytes()).hexdigest()
    query_times = []
    with closing(sqlite3.connect(f"file:{database.as_posix()}?mode=ro", uri=True)) as db:
        db.row_factory = sqlite3.Row
        check(db.execute("PRAGMA integrity_check").fetchone()[0] == "ok", "SQLite integrity")
        check(not db.execute("PRAGMA foreign_key_check").fetchall(), "Foreign keys")
        check(db.execute("PRAGMA user_version").fetchone()[0] == 2, "Schema version")
        count_report = {}
        for name, count in source_manifest["counts"].items():
            path = next(source.glob(f"*/{name}.jsonl"))
            expected_hash = source_manifest["hashes"][path.relative_to(source).as_posix()]
            check(hashlib.sha256(path.read_bytes()).hexdigest() == expected_hash, f"Source checksum: {name}")
            originals = {r.get("id", r.get("version_id")): r for r in map(json.loads, path.read_text(encoding="utf8").splitlines())}
            key = "version_id" if name == "arguments" else "id"
            stored = {r[0]: json.loads(r[1]) for r in db.execute(f"SELECT {key},payload FROM {name}")}
            check(stored == originals and len(stored) == count, f"Lossless source rows: {name}")
            count_report[name] = count
        check(db.execute("SELECT count(*) FROM project_contacts WHERE observed_at>timestamp OR available_at>timestamp").fetchone()[0] == 0, "Future contact version")
        check(db.execute("""SELECT count(*) FROM increment_history h JOIN increments i ON i.id=h.increment_id
            WHERE h.observed_at>=i.query_at OR h.available_at>=i.query_at""").fetchone()[0] == 0, "Future history")
        check(db.execute("SELECT count(*) FROM project_contacts").fetchone()[0] == count_report["contacts"], "Projection fan-out")
        presentations = db.execute("""SELECT p.payload,v.text,p.provenance,p.available_at,v.available_at
            FROM claim_presentations p JOIN versions v ON v.id=p.version_id AND v.work_id=p.work_id""").fetchall()
        check(len(presentations) == count_report["versions"], "Presentation coverage")
        categories = {}
        for payload, content, provenance, ready, version_ready in presentations:
            value = json.loads(payload)
            check(validate_presentation(value, content) == value, "Presentation field contract")
            check(provenance == "AI_authored_rules_unreviewed" and ready == version_ready, "Presentation provenance/time")
            categories[value["category"]] = categories.get(value["category"], 0) + 1
        profiles = [dict(r) for r in db.execute("""SELECT p.*,
            (SELECT MAX(d.start)+43200000 FROM user_days d WHERE d.profile_id=p.id) AS now
            FROM profiles p ORDER BY ordinal""")]
        with (output / "projections.jsonl").open("w", encoding="utf8", newline="\n") as exported:
            for profile in profiles:
                started = time.perf_counter()
                contacts = [dict(r) for r in db.execute("SELECT * FROM project_contacts WHERE profile_id=? ORDER BY timestamp,row_id", (profile["id"],))]
                emotions = [dict(r) for r in db.execute("SELECT * FROM project_emotions WHERE profile_id=? ORDER BY timestamp,work_id", (profile["id"],))]
                query_times.append((time.perf_counter() - started) * 1000)
                check(156 <= len(contacts) <= 168, "Per-profile bounded visits")
                check({r["profile_id"] for r in contacts + emotions} == {profile["id"]}, "Cross-profile rows")
                for contact in contacts:
                    spans = json.loads(contact["contact_json"])["visible_intervals"]
                    persisted = [list(r) for r in db.execute("SELECT start,end FROM visible_intervals WHERE contact_row_id=? ORDER BY ordinal", (contact["row_id"],))]
                    check(spans == persisted, "Interval normalization")
                row = dict(profile=profile, contacts=contacts, emotions=emotions,
                           expected=expected_windows(profile, contacts, emotions))
                exported.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n")
                if profile["ordinal"] == 151:
                    # A small real pair of revisits from the corpus, committed for clean-checkout unit tests.
                    grouped = {}
                    for contact in contacts:
                        grouped.setdefault(contact["work_id"], []).append(contact)
                    pair = next(values[:2] for values in grouped.values() if len(values) >= 2)
                    pair_keys = {(r["work_id"], r["timestamp"]) for r in pair}
                    fixture = dict(profile=profile, contacts=pair,
                                   emotions=[r for r in emotions if (r["work_id"], r["timestamp"]) in pair_keys])
                    (output / "projection-fixture.json").write_text(json.dumps(fixture, ensure_ascii=False, indent=2) + "\n", encoding="utf8", newline="\n")
        plan = [r[3] for r in db.execute("EXPLAIN QUERY PLAN SELECT * FROM project_contacts WHERE profile_id=? ORDER BY timestamp,row_id", (profiles[0]["id"],))]
        check(any("contacts_profile_time" in line for line in plan), "Missing indexed profile access")
    result = dict(status="passed", database_sha256=digest, bytes=database.stat().st_size,
                  lossless_tables=count_report, profiles=len(profiles), expected_windows=len(profiles) * 6,
                  integrity="ok", foreign_key_errors=0, projected_contacts=count_report["contacts"],
                  presentation_count=len(presentations), presentation_categories=categories,
                  profile_query_host_ms_mean=sum(query_times) / len(query_times), profile_query_host_ms_max=max(query_times),
                  query_plan=plan, runtime="host Python SQLite, not Android", device_execution=False)
    (output / "database-audit.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf8", newline="\n")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database", type=Path, default=ROOT / "app/build/project-research-database/pojian-research-v2.sqlite")
    parser.add_argument("--dataset", type=Path, default=ROOT / "app/build/research-synthetic-v2")
    parser.add_argument("--output", type=Path, default=ROOT / "app/build/reports/project-research-database")
    args = parser.parse_args()
    print(json.dumps(audit(args.database, args.dataset, args.output), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
