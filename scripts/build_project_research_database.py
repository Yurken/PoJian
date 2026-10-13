"""Build the App's isolated, indexed, read-only research SQLite from the audited v2 corpus.

No personal Room database is read or modified. All source rows (including gold labels) are
retained; App projections only consume observed versions and authored argument/emotion fields.
"""
import argparse
from contextlib import closing
import gzip
import hashlib
import json
from pathlib import Path
import sqlite3
from research_presentations import make_presentation, PROVENANCE

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets/synthetic"
PACKAGES = {"小红书图文": "com.xingin.xhs", "小红书短视频": "com.xingin.xhs",
            "抖音短视频": "com.ss.android.ugc.aweme", "快手短视频": "com.smile.gifmaker",
            "微博": "com.sina.weibo", "微信视频号": "com.tencent.mm", "微信公众号": "com.tencent.mm"}


def dump(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def build(source, output):
    manifest_path = source / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf8"))
    if manifest["dataset_id"] != "research-controlled-v2" or manifest["real_participants"] != 0 or not manifest["synthetic"]:
        raise ValueError("Expected fictional controlled research v2 corpus")
    rows = {}
    for relative, expected in manifest["hashes"].items():
        if not relative.endswith(".jsonl"):
            continue
        path = source / relative
        if digest(path) != expected:
            raise ValueError(f"Source checksum mismatch: {relative}")
        rows[path.stem] = [json.loads(line) for line in path.read_text(encoding="utf8").splitlines()]
    if {key: len(value) for key, value in rows.items()} != manifest["counts"]:
        raise ValueError("Source counts do not match manifest")
    output.mkdir(parents=True, exist_ok=True)
    path = output / "pojian-research-v2.sqlite"
    temporary = path.with_suffix(".building")
    temporary.unlink(missing_ok=True)  # Fixed generator-owned file, never a user database.
    with closing(sqlite3.connect(temporary)) as db, db:
        db.execute("PRAGMA foreign_keys=ON")
        db.executescript((ASSETS / "research-schema.sql").read_text(encoding="utf8"))
        db.executemany("INSERT INTO metadata VALUES (?,?)", [
            ("kind", "controlled_synthetic_research"), ("dataset_id", manifest["dataset_id"]),
            ("source_manifest_sha256", digest(manifest_path)), ("source_manifest", dump(manifest)),
            ("real_participants", "0"), ("annotation_status", manifest["annotation_status"])])
        def insert(table, columns, values):
            db.executemany(f"INSERT INTO {table} ({columns}) VALUES ({','.join('?' for _ in columns.split(','))})", values)
        insert("works", "id,package_name,media_type,domain,author_id,split,identity_group,near_duplicate_group,payload",
               ((r["id"], PACKAGES[r["media_type"]], r["media_type"], r["domain"], r["author_id"], r["split"],
                 r["identity_group"], r["near_duplicate_group"], dump(r)) for r in rows["works"]))
        insert("versions", "id,work_id,ordinal,observed_at,available_at,text,payload",
               ((r["id"], r["work_id"], r["version"], r["observed_at"], r["available_at"], r["text"], dump(r)) for r in rows["versions"]))
        insert("arguments", "version_id,work_id,payload", ((r["version_id"], r["work_id"], dump(r)) for r in rows["arguments"]))
        versions = {r["id"]: r for r in rows["versions"]}
        emotions_by_version = {}
        for emotion in rows["emotions"]:
            emotions_by_version.setdefault(emotion["version_id"], []).append(emotion)
        insert("claim_presentations", "version_id,work_id,available_at,provenance,payload",
               ((a["version_id"], a["work_id"], versions[a["version_id"]]["available_at"], PROVENANCE,
                 dump(make_presentation(versions[a["version_id"]], a, emotions_by_version.get(a["version_id"], []))))
                for a in rows["arguments"]))
        insert("emotions", "id,version_id,work_id,expression,attribution,holder,payload",
               ((r["id"], r["version_id"], r["work_id"], r["expression"], r["attribution"], r["holder"], dump(r)) for r in rows["emotions"]))
        insert("profiles", "id,ordinal,user_split,concentration,anxiety,payload",
               ((r["id"], i, r["user_split"], r["concentration_condition"], r["anxiety_condition"], dump(r)) for i, r in enumerate(rows["profiles"], 1)))
        insert("user_days", "id,profile_id,scheduled_day,user_split,time_split,start,end,status,payload",
               ((r["id"], r["profile_id"], r["scheduled_day"], r["user_split"], r["time_split"], r["start"], r["end"], r["status"], dump(r)) for r in rows["user_days"]))
        insert("contacts", "row_id,id,profile_id,day_id,work_id,version_id,timestamp,user_split,time_split,payload",
               ((i, r["id"], r["profile_id"], r["day_id"], r["work_id"], r["version_id"], r["timestamp"], r["user_split"], r["time_split"], dump(r)) for i, r in enumerate(rows["contacts"], 1)))
        insert("visible_intervals", "contact_row_id,ordinal,start,end",
               ((i, n, start, end) for i, r in enumerate(rows["contacts"], 1) for n, (start, end) in enumerate(r["visible_intervals"])))
        insert("increments", "id,split,current_version_id,query_at,payload",
               ((r["id"], r["split"], r["current_version_id"], r["query_at"], dump(r)) for r in rows["increments"]))
        insert("increment_history", "increment_id,ordinal,version_id,work_id,observed_at,available_at",
               ((r["id"], n, h["version_id"], h["work_id"], h["observed_at"], h["available_at"]) for r in rows["increments"] for n, h in enumerate(r["history"])))
        insert("feedback", "id,profile_id,day_id,available_at,payload",
               ((r["id"], r["profile_id"], r["day_id"], r["available_at"], dump(r)) for r in rows["feedback"]))
        for name in ("increment_labels", "traces", "trace_labels", "safety", "safety_labels"):
            insert(name, "id,payload", ((r["id"], dump(r)) for r in rows[name]))
        if db.execute("PRAGMA foreign_key_check").fetchall() or db.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
            raise ValueError("Database integrity failed")
        # No synthetic contact may see a future observation or future completion.
        if db.execute("SELECT count(*) FROM project_contacts WHERE observed_at>timestamp OR available_at>timestamp").fetchone()[0]:
            raise ValueError("Future version in contact projection")
    temporary.replace(path)
    with path.open("rb") as source_file, (output / "research-v2.sqlite.gz").open("wb") as target:
        with gzip.GzipFile(fileobj=target, filename="", mode="wb", mtime=0) as zipped:
            while chunk := source_file.read(1024 * 1024):
                zipped.write(chunk)
    asset_manifest = dict(format_version=1, dataset_id=manifest["dataset_id"], synthetic=True,
                          real_participants=0, annotation_status=manifest["annotation_status"],
                          database_sha256=digest(path), database_bytes=path.stat().st_size,
                          source_manifest_sha256=digest(manifest_path), counts=manifest["counts"],
                          database_schema_version=2, presentation_count=len(rows["arguments"]), presentation_provenance=PROVENANCE)
    (output / "research-v2.json").write_text(dump(asset_manifest) + "\n", encoding="utf8", newline="\n")
    return path, asset_manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=ROOT / "app/build/research-synthetic-v2")
    parser.add_argument("--output", type=Path, default=ROOT / "app/build/project-research-database")
    parser.add_argument("--install-assets", action="store_true")
    args = parser.parse_args()
    path, manifest = build(args.dataset, args.output)
    if args.install_assets:
        import shutil
        for name in ("research-v2.sqlite.gz", "research-v2.json"):
            shutil.copyfile(args.output / name, ASSETS / name)
    print(dump(dict(database=str(path), bytes=manifest["database_bytes"], sha256=manifest["database_sha256"], counts=manifest["counts"])))


if __name__ == "__main__":
    main()
