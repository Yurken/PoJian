"""Chapter 2 engineering baseline: versioned history and reproducible reports.

No trained semantic model, encryption, TEE, or calibrated risk classifier is
implemented here. Claim fields are supplied by the caller, not inferred.
"""
from __future__ import annotations

import hashlib
import json
import re
import sqlite3
import threading
import time


class HistoryError(ValueError):
    def __init__(self, message, status=400):
        super().__init__(message)
        self.status = status


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def norm(text):
    # Keep numbers, negation, entities and scope; do not claim semantic equivalence.
    return re.sub(r"\s+", "", text).casefold()


def validate_event(data):
    if not isinstance(data, dict):
        raise HistoryError("Expected event object")
    keys = {"event_id", "work_id", "platform", "revision", "start_ms", "end_ms",
            "revision_time_ms", "text", "claims"}
    if set(data) != keys:
        raise HistoryError("Invalid event fields")
    for key in ("event_id", "work_id", "platform"):
        if not isinstance(data[key], str) or not 1 <= len(data[key]) <= 120:
            raise HistoryError("Invalid identifier")
    for key in ("revision", "start_ms", "end_ms", "revision_time_ms"):
        if type(data[key]) is not int or not 0 <= data[key] <= 253402300799000:
            raise HistoryError("Invalid time or revision")
    if not 1 <= data["revision"] <= 1000 or not data["start_ms"] <= data["end_ms"] <= data["revision_time_ms"]:
        raise HistoryError("Invalid observation interval")
    if data["end_ms"] - data["start_ms"] > 24 * 3600000:
        raise HistoryError("An event cannot exceed 24 hours")
    if not isinstance(data["text"], str) or not 1 <= len(data["text"]) <= 16000:
        raise HistoryError("Invalid text")
    if not isinstance(data["claims"], list) or len(data["claims"]) > 20:
        raise HistoryError("Invalid claim count")
    for claim in data["claims"]:
        if not isinstance(claim, dict) or set(claim) != {"topic", "subject", "stance", "quote", "evidence", "conditions", "observed_ms"}:
            raise HistoryError("Invalid claim fields")
        for key in ("topic", "subject", "stance", "quote", "evidence", "conditions"):
            if not isinstance(claim[key], str) or len(claim[key]) > 1000:
                raise HistoryError("Invalid claim text")
        if any(not claim[k].strip() for k in ("topic", "subject", "stance", "quote")):
            raise HistoryError("Missing claim identity")
        for key in ("quote", "evidence", "conditions"):
            if claim[key] and claim[key] not in data["text"]:
                raise HistoryError("Claim evidence must reference captured text")
        if type(claim["observed_ms"]) is not int or not data["start_ms"] <= claim["observed_ms"] <= data["end_ms"]:
            raise HistoryError("Claim outside observed interval")
    return data


def union_ms(intervals):
    total, right = 0, None
    for left, end in sorted(intervals):
        if end <= left:
            continue
        total += end - max(left, right if right is not None else left) if right is None or end > right else 0
        right = max(right or end, end)
    return total


def evidence_units(text):
    return {norm(part) for part in re.split(r"[。；;\n]+", text) if norm(part)}


class HistoryStore:
    def __init__(self, path, max_events=10000):
        self.db = sqlite3.connect(path, check_same_thread=False)
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("""CREATE TABLE IF NOT EXISTS revisions (
            owner TEXT NOT NULL, event_id TEXT NOT NULL, revision INTEGER NOT NULL,
            payload TEXT NOT NULL, received_ms INTEGER NOT NULL,
            PRIMARY KEY(owner,event_id,revision))""")
        self.db.commit()
        self.lock = threading.RLock()
        self.max_events = max_events

    def close(self):
        with self.lock:
            self.db.close()

    def submit(self, owner, payload):
        # Roundtrip detaches mutable caller data before validation and storage.
        data = validate_event(json.loads(canonical(payload)))
        body = canonical(data)
        with self.lock:
            rows = self.db.execute("SELECT payload FROM revisions WHERE owner=? AND event_id=? ORDER BY revision",
                                   (owner, data["event_id"])).fetchall()
            previous = [json.loads(row[0]) for row in rows]
            same = next((item for item in previous if item["revision"] == data["revision"]), None)
            if same is not None:
                if canonical(same) != body:
                    raise HistoryError("Revision already exists with different input", 409)
                return {"status": "duplicate", "event_id": data["event_id"], "revision": data["revision"]}
            if previous:
                last = previous[-1]
                if data["revision"] != last["revision"] + 1:
                    raise HistoryError("Revisions must be consecutive", 409)
                if any(last[k] != data[k] for k in ("work_id", "platform", "start_ms")):
                    raise HistoryError("Event identity cannot change", 409)
                if data["revision_time_ms"] < last["revision_time_ms"] or data["end_ms"] < last["end_ms"]:
                    raise HistoryError("Revision observation time cannot go backwards", 409)
            else:
                if data["revision"] != 1:
                    raise HistoryError("First revision must be 1", 409)
                count = self.db.execute("SELECT COUNT(DISTINCT event_id) FROM revisions WHERE owner=?", (owner,)).fetchone()[0]
                if count >= self.max_events:
                    raise HistoryError("History event capacity reached", 429)
            with self.db:
                self.db.execute("INSERT INTO revisions VALUES(?,?,?,?,?)",
                                (owner, data["event_id"], data["revision"], body, time.time_ns() // 1000000))
            return {"status": "recorded", "event_id": data["event_id"], "revision": data["revision"]}

    def delete(self, owner, event_id):
        with self.lock, self.db:
            count = self.db.execute("DELETE FROM revisions WHERE owner=? AND event_id=?", (owner, event_id)).rowcount
            return {"deleted_revisions": count}

    def report(self, owner, start_ms, end_ms):
        if any(type(v) is not int or not 0 <= v <= 253402300799000 for v in (start_ms, end_ms)) or start_ms >= end_ms:
            raise HistoryError("Invalid report window")
        if end_ms - start_ms > 31 * 86400000:
            raise HistoryError("Report window cannot exceed 31 days")
        with self.lock:
            rows = self.db.execute("SELECT payload FROM revisions WHERE owner=? ORDER BY event_id,revision", (owner,)).fetchall()
        versions = [json.loads(row[0]) for row in rows]

        def snapshot(cutoff):
            result = {}
            for event in versions:
                if event["revision_time_ms"] <= cutoff:
                    result[event["event_id"]] = event
            return list(result.values())

        current = snapshot(end_ms)
        selected = [e for e in current if e["start_ms"] < end_ms and
                    (e["end_ms"] > start_ms or e["start_ms"] >= start_ms)]
        selected.sort(key=lambda e: (e["start_ms"], e["event_id"]))
        clip = lambda e: (max(start_ms, e["start_ms"]), min(end_ms, e["end_ms"]))
        platforms = sorted({e["platform"] for e in selected})
        works = {(e["platform"], e["work_id"]) for e in selected}
        groups, comparisons, nodes, edges = {}, [], {}, {}

        def node(kind, value):
            ident = kind + ":" + hashlib.sha256(value.encode()).hexdigest()[:24]
            nodes[ident] = {"id": ident, "type": kind, "label": value}
            return ident

        for event in selected:
            content = node("content", event["platform"] + ":" + event["work_id"])
            source = node("application", event["platform"])
            edges[(content, source, "captured_from")] = {"from": content, "to": source, "type": "captured_from"}
            for index, claim in enumerate(event["claims"]):
                key = canonical([claim[k] for k in ("topic", "subject", "stance", "conditions")])
                group = groups.setdefault(key, {"topic": claim["topic"], "subject": claim["subject"],
                    "stance": claim["stance"], "conditions": claim["conditions"], "works": set(), "references": []})
                group["works"].add((event["platform"], event["work_id"]))
                ref = {"event_id": event["event_id"], "revision": event["revision"], "claim_index": index,
                       "quote": claim["quote"], "quote_start": event["text"].index(claim["quote"]),
                       "observed_ms": claim["observed_ms"]}
                group["references"].append(ref)
                viewpoint = node("viewpoint", key)
                edges[(content, viewpoint, "expresses_candidate")] = {"from": content, "to": viewpoint,
                    "type": "expresses_candidate", "source": "caller_supplied_unverified"}
                history = []
                for old in snapshot(claim["observed_ms"]):
                    if old["platform"] == event["platform"] and old["work_id"] == event["work_id"]:
                        continue
                    for old_index, other in enumerate(old["claims"]):
                        if other["observed_ms"] < claim["observed_ms"] and all(norm(other[k]) == norm(claim[k]) for k in ("topic", "subject")):
                            history.append((old, old_index, other))
                history.sort(key=lambda row: (row[2]["observed_ms"], row[0]["event_id"]), reverse=True)
                history = history[:50]
                units = evidence_units(claim["evidence"])
                covered = []
                for unit in sorted(units):
                    matches = [{"event_id": old["event_id"], "revision": old["revision"], "claim_index": oi}
                               for old, oi, other in history if unit in evidence_units(other["evidence"])]
                    covered.append({"unit": unit, "state": "literal_covered" if matches else "unmatched", "history": matches})
                same_stance = any(norm(other["stance"]) == norm(claim["stance"]) for _, _, other in history)
                same_conditions = any(norm(other["conditions"]) == norm(claim["conditions"]) for _, _, other in history)
                state = "initial_material" if not history else "insufficient_evidence" if not units else \
                    "literal_repetition" if same_stance and same_conditions and all(c["history"] for c in covered) else "needs_semantic_review"
                comparisons.append({**ref, "state": state, "evidence_units": covered,
                    "candidate_count": len(history), "stance_relation": "literal_match" if same_stance else "undetermined",
                    "conditions_relation": "literal_match" if same_conditions else "undetermined"})
                for unit in units:
                    evidence = node("evidence", unit)
                    edges[(evidence, viewpoint, "supports_candidate")] = {"from": evidence, "to": viewpoint,
                        "type": "supports_candidate", "source": "caller_supplied_unverified"}
        shares = [{k: v for k, v in group.items() if k != "works"} | {
                    "work_count": len(group["works"]), "percent_of_all_works": len(group["works"]) * 100 / len(works)}
                  for group in groups.values()]
        shares.sort(key=lambda g: (-g["work_count"], g["stance"]))
        known = {(e["platform"], e["work_id"]) for e in selected if e["claims"]}
        coverage = len(known) / len(works) if works else None
        repeat_count = sum(c["state"] == "literal_repetition" for c in comparisons)
        manifest = [{"event_id": e["event_id"], "revision": e["revision"]} for e in selected]
        return {"schema_version": 1, "algorithm": "literal-history-baseline-v1", "window": {"start_ms": start_ms, "end_ms": end_ms},
            "snapshot_hash": hashlib.sha256(canonical([v for v in versions if v["revision_time_ms"] <= end_ms]).encode()).hexdigest(), "event_versions": manifest,
            "totals": {"work_count": len(works), "visit_count": len(selected), "visible_ms": union_ms([clip(e) for e in selected]),
                       "unknown_work_count": len(works - known), "supplied_claim_work_coverage": coverage},
            "platforms": [{"platform": p, "work_count": len({e["work_id"] for e in selected if e["platform"] == p}),
                "visit_count": sum(e["platform"] == p for e in selected),
                "visible_ms": union_ms([clip(e) for e in selected if e["platform"] == p])} for p in platforms],
            "viewpoints": shares, "comparisons": comparisons,
            "graph": {"nodes": list(nodes.values()), "edges": list(edges.values())},
            "observation": {"literal_repeat_units": repeat_count, "risk_state": "not_evaluated",
                "reason": "No trained or calibrated long-term risk classifier"},
            "limitations": {"tee": False, "encrypted_storage": False, "semantic_model": False,
                "claim_source": "caller_supplied_unverified", "history_candidate_limit": 50,
                "source_scope": "application_not_original_source",
                "multi_label_percentages_may_exceed_100": True,
                "event_duration_is_visibility_not_reading": True}}
