#!/usr/bin/env python3
"""Prepare a private, portable first-install snapshot from an extracted app backup.

Input must include databases/zhiheng.db and any accompanying WAL. Never point this
at a live database. The original backup is preserved; no personal files belong in Git.
"""
import argparse
from contextlib import closing
import hashlib
import json
from pathlib import Path
import re
import sqlite3
import tempfile
import xml.etree.ElementTree as ET
import zipfile


def preference(root, name, kind, value):
    for child in list(root):
        if child.get("name") == name:
            root.remove(child)
    child = ET.SubElement(root, kind, {"name": name})
    if kind == "string":
        child.text = str(value)
    else:
        child.set("value", str(value).lower())


def prepare(source, destination):
    destination.mkdir(parents=True, exist_ok=True)
    output = destination / "demo-snapshot.zip"
    if output.exists():
        raise FileExistsError(output)
    with tempfile.TemporaryDirectory(prefix="pojian-demo-") as temporary:
        database = Path(temporary) / "zhiheng.db"
        original = source / "databases/zhiheng.db"
        with closing(sqlite3.connect(original.resolve().as_uri() + "?mode=ro", uri=True)) as src:
            with closing(sqlite3.connect(database)) as db:
                src.backup(db)  # Includes committed WAL content without modifying the original backup.
                assert db.execute("PRAGMA integrity_check").fetchone() == ("ok",)
                assert db.execute("PRAGMA user_version").fetchone() == (9,)
                assert not db.execute("PRAGMA foreign_key_check").fetchall()
                tables = [row[0] for row in db.execute(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")]
                counts = {table: db.execute(f'SELECT COUNT(*) FROM "{table}"').fetchone()[0]
                          for table in tables}
                # Android Keystore queue envelopes cannot be decrypted on another device.
                # Retain task history and completed results, but never resume the old phone's jobs.
                db.execute("""UPDATE monitor_tasks SET payload='',leaseOwner='',leaseUntil=0,
                    status=CASE WHEN status IN ('queued','running') THEN 'cancelled' ELSE status END""")
                db.commit()
                db.execute("PRAGMA journal_mode=DELETE")
                thumbnails = db.execute("SELECT fileName, byteCount FROM work_thumbnails").fetchall()
                for name, size in thumbnails:
                    assert re.fullmatch(r"[a-f0-9-]{36}\.jpg", name), "Invalid thumbnail name"
                    image = source / "no_backup/work-thumbnails" / name
                    assert image.is_file() and image.stat().st_size == size, "Missing thumbnail"

        with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.write(database, "databases/zhiheng.db")
            for name in ["capture", "audio_analysis", "live_monitor", "report_preferences", "local_storage"]:
                path = source / "shared_prefs" / f"{name}.xml"
                root = ET.parse(path).getroot() if path.exists() else ET.Element("map")
                if name == "capture":
                    preference(root, "enabled", "boolean", False)
                elif name == "audio_analysis":
                    for child in list(root):
                        if child.get("name") in {"token", "endpoint"}:
                            root.remove(child)
                    # The old device's encrypted token is not portable. Use the same
                    # configured gateway credential as a normal signed build instead.
                    preference(root, "enabled", "boolean", True)
                    preference(root, "demo_online_migrated", "boolean", True)
                elif name == "local_storage":
                    preference(root, "retention_days", "int", 0)
                    # Keep the full snapshot instead of trimming it on first launch.
                    stored_limit = max([int(c.get("value", "0")) for c in root
                                        if c.get("name") == "limit_bytes"] or [0])
                    total = database.stat().st_size + sum(size for _, size in thumbnails)
                    preference(root, "limit_bytes", "long", max(stored_limit, total * 2, 536870912))
                archive.writestr(f"shared_prefs/{name}.xml", ET.tostring(root, encoding="utf-8",
                                                                       xml_declaration=True))
            for name, _ in thumbnails:
                archive.write(source / "no_backup/work-thumbnails" / name,
                              f"no_backup/work-thumbnails/{name}")
            model_root = source / "files/speech-models"
            if model_root.is_dir():
                for path in sorted(model_root.rglob("*")):
                    if path.is_file() and not path.is_symlink():
                        archive.write(path, path.relative_to(source).as_posix())
        summary = {
            "tables": counts, "thumbnails": len(thumbnails),
            "snapshotBytes": output.stat().st_size,
            "sha256": hashlib.sha256(output.read_bytes()).hexdigest(),
            "changes": ["Capture disabled", "Device-bound cloud credentials removed; bundled gateway enabled",
                        "Device-bound queue payloads removed; unfinished tasks cancelled",
                        "Automatic retention disabled", "Original timestamps preserved"],
        }
        # Keep metadata alongside assets, not inside the directory included in the APK.
        return summary


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path, help="Private assets directory outside app/src")
    args = parser.parse_args()
    print(json.dumps(prepare(args.source, args.destination), ensure_ascii=False, indent=2))
