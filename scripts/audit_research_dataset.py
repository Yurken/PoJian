"""Independent file-level invariants and leakage audit; does not import the generator."""
import argparse
from collections import Counter, defaultdict
import hashlib
import json
from pathlib import Path
import re
import sqlite3


def read(folder, name, label=False):
    return [json.loads(line) for line in (folder / ("labels" if label else "inputs") / f"{name}.jsonl").read_text(encoding="utf8").splitlines()]


def check_history_pair(pair, vi, wi):
    current = vi[pair["current_version_id"]]
    assert current["available_at"] <= pair["query_at"], "current unavailable"
    assert pair["split"] == current["split"], "current partition"
    for previous in pair["history"]:
        version = vi[previous["version_id"]]
        work = wi[version["work_id"]]
        assert previous["observed_at"] == version["observed_at"] < pair["query_at"], "history not strictly earlier"
        assert previous["available_at"] == version["available_at"] < pair["query_at"], "future completion leaked"
        assert work["split"] == pair["split"] and work["identity_group"] == pair["identity_group"], "cross-group history"
        assert previous["work_id"] == version["work_id"] != current["work_id"], "wrong history identity"


def audit(folder):
    if not __debug__:
        raise RuntimeError("Run audits without Python -O; invariant checks must remain enabled")
    manifest = json.loads((folder / "manifest.json").read_text(encoding="utf8"))
    for name, expected in manifest["hashes"].items():
        if name.startswith("../") or not (folder / name).resolve().is_relative_to(folder.resolve()):
            raise ValueError("Unsafe manifest path")
        assert hashlib.sha256((folder / name).read_bytes()).hexdigest() == expected, ("hash", name)
    w, v, a, e = read(folder, "works"), read(folder, "versions"), read(folder, "arguments", True), read(folder, "emotions", True)
    pairs, labels = read(folder, "increments"), read(folder, "increment_labels", True)
    wi, vi, ai = {r["id"]: r for r in w}, {r["id"]: r for r in v}, {r["version_id"]: r for r in a}
    assert (len(w), len(v), len(a), len(e), len(pairs)) == (8400, 25200, 25200, 11200, 16800)
    assert (len(wi), len(vi), len(ai)) == (len(w), len(v), len(a))
    quotas = {"train": (5880, 11760, 7840), "validation": (1260, 2520, 1680), "test": (1260, 2520, 1680)}
    for split, (works, increments, emotions) in quotas.items():
        assert sum(x["split"] == split for x in w) == works
        assert sum(x["split"] == split for x in pairs) == increments
        assert sum(x["split"] == split for x in e) == emotions
        assert set(Counter(x["stratum"] for x in labels if x["split"] == split).values()) == {increments // 8}
        assert set(Counter(x["media_type"] for x in w if x["split"] == split).values()) == {works // 7}
        assert set(Counter(x["domain"] for x in w if x["split"] == split).values()) == {works // 6}
    for field in ("author_id", "identity_group", "near_duplicate_group", "semantic_family", "source_origin"):
        groups = defaultdict(set)
        for row in w:
            groups[row[field]].add(row["split"])
        assert all(len(s) == 1 for s in groups.values()), ("cross-split", field)
    exact_text, normalized_text = defaultdict(set), defaultdict(set)
    for row in v:
        parent = wi[row["work_id"]]
        assert row["split"] == parent["split"] and row["id"] in parent["version_ids"]
        assert row["observed_at"] <= row["available_at"]
        exact_text[row["text"]].add(row["split"])
        normalized = re.sub(r"syn-[a-z]+-[0-9a-f]+|\d+|\s+", "", row["text"])
        normalized_text[normalized].add(row["split"])
        annotation = ai[row["id"]]
        for field in ("quote", "evidence"):
            span = annotation[field + "_span"]
            if span is not None:
                assert row["text"][span[0]:span[1]] == annotation[field], ("argument span", row["id"])
        if row["version"] < 3:
            assert not any(token in row["text"] for token in ["我因为", "朋友告诉我", "评论者说"])
        if row["version"] == 1:
            assert annotation["evidence"] == "" and annotation["source_origin"] is None
    assert all(len(s) == 1 for s in exact_text.values()), "Exact text leaked across partitions"
    assert all(len(s) == 1 for s in normalized_text.values()), "Identifier/number-stripped text leaked"
    for pair in pairs:
        check_history_pair(pair, vi, wi)
    assert {p["id"] for p in pairs} == {l["id"] for l in labels}
    for row in e:
        version = vi[row["version_id"]]
        assert version["split"] == row["split"] and version["work_id"] == row["work_id"]
        assert version["version"] == 3 and row["viewer_emotion"] is None
        for field in ("expression", "cause"):
            start, end = row[field + "_span"]
            assert version["text"][start:end] == row[field]
        assert row["holder"] in ("author", "quoted_person", "commenter")
        assert all(value["applicable"] or value["value"] is None for value in row["appraisal"].values())
    profiles, days, contacts, feedback = read(folder, "profiles", True), read(folder, "user_days"), read(folder, "contacts"), read(folder, "feedback", True)
    pi, di, ci = ({p["id"]: p for p in profiles}, {d["id"]: d for d in days}, {c["id"]: c for c in contacts})
    assert len(pi) == 200 and len(di) == 2800
    assert Counter(p["user_split"] for p in profiles) == dict(train=140, validation=30, test=30)
    assert Counter(d["user_split"] for d in days) == dict(train=1960, validation=420, test=420)
    assert Counter(d["time_split"] for d in days) == dict(train=1600, validation=600, test=600)
    factorial = Counter((p["concentration_condition"], p["anxiety_condition"]) for p in profiles)
    assert len(factorial) == 4 and set(factorial.values()) == {50}
    for day in days:
        events = [ci[key] for key in day["contact_ids"]]
        assert all(c["day_id"] == day["id"] and c["profile_id"] == day["profile_id"] for c in events)
        intervals = sorted([interval for c in events for interval in c["visible_intervals"]] + [[gap["start"], gap["end"]] for gap in day["gaps"]])
        assert intervals[0][0] == day["start"] and intervals[-1][1] == day["end"]
        assert all(a[1] == b[0] for a, b in zip(intervals, intervals[1:])), "Observation/gap coverage is not a partition"
        assert all(x[1] > x[0] for x in intervals)
        if day["status"] == "missing":
            assert not events
        else:
            assert len(events) == 12
        for event in events:
            assert vi[event["version_id"]]["available_at"] < event["timestamp"]
            assert wi[event["work_id"]]["split"] == day["user_split"]
    for item in feedback:
        day, profile = di[item["day_id"]], pi[item["profile_id"]]
        assert item["available_at"] > max(ci[key]["visible_intervals"][-1][1] for key in day["contact_ids"])
        assert item["user_protocol_training_allowed"] == (profile["user_split"] == "train")
        assert item["time_protocol_training_allowed"] == (day["scheduled_day"] < 8)
        if item["time_protocol_training_allowed"]:
            # First test-period observation must not receive same-time/future training feedback.
            cutoff = day["start"] + (8 - day["scheduled_day"]) * 86_400_000
            assert item["available_at"] < cutoff
    traces, safety = read(folder, "traces"), read(folder, "safety")
    assert len(traces) == 240 and set(Counter(t["platform"] for t in traces).values()) == {120}
    assert len(safety) == 120
    assert {t["id"] for t in traces} == {t["id"] for t in read(folder, "trace_labels", True)}
    assert all(s["execution_status"] == "not_executed_on_device" for s in read(folder, "safety_labels", True))
    db = sqlite3.connect((folder / "research-synthetic-v2.sqlite").resolve().as_uri() + "?mode=ro", uri=True)
    try:
        assert db.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
        for table, count in manifest["counts"].items():
            assert db.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0] == count
    finally:
        db.close()
    return dict(status="passed", scope="structural_and_protocol_audit_not_model_accuracy",
                counts=manifest["counts"], exact_text_cross_split_groups=0, normalized_text_cross_split_groups=0,
                semantic_families=len({row["semantic_family"] for row in w}), source_cases=len({x["identity_group"] for x in w}),
                missing_user_days=sum(d["status"] == "missing" for d in days),
                limitation="Shared syntactic scaffolds remain. This audit does not establish natural-language or media generalization.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dataset", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    result = audit(args.dataset)
    text = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text, encoding="utf8", newline="\n")
    print(text)
