"""Synthetic local CPU microbenchmark; not a semantic accuracy or GPU benchmark."""
import argparse
import json
import platform
import statistics
import time
from pathlib import Path

try:
    from server.cognitive_history import HistoryStore
except ImportError:
    from cognitive_history import HistoryStore


def run():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    results = []
    for size in (30, 100, 300):
        store = HistoryStore(":memory:")
        latencies = []
        for index in range(size):
            start = (index + 1) * 1000
            text = "当前就业困难。招聘数量下降20%"
            row = {"event_id": str(index), "work_id": str(index), "platform": "synthetic-reader", "revision": 1,
                   "start_ms": start, "end_ms": start + 100, "revision_time_ms": start + 100, "text": text,
                   "claims": [{"topic": "就业", "subject": "应届生", "stance": "就业困难", "quote": "当前就业困难",
                               "evidence": "招聘数量下降20%", "conditions": "", "observed_ms": start}]}
            before = time.perf_counter_ns()
            store.submit("synthetic-user", row)
            latencies.append((time.perf_counter_ns() - before) / 1000000)
        report_latencies = []
        for _ in range(5):
            before = time.perf_counter_ns()
            report = store.report("synthetic-user", 0, (size + 2) * 1000)
            report_latencies.append((time.perf_counter_ns() - before) / 1000000)
        results.append({"events": size, "submit_median_ms": statistics.median(latencies),
                        "submit_p95_ms": sorted(latencies)[max(0, int(.95 * size) - 1)],
                        "report_median_ms": statistics.median(report_latencies),
                        "report_max_ms": max(report_latencies), "report_repeats": 5,
                        "reported_works": report["totals"]["work_count"],
                        "json_bytes": len(json.dumps(report, ensure_ascii=False).encode("utf-8"))})
        store.close()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({"python": platform.python_version(), "os": platform.platform(),
        "processor": platform.processor(), "store": "SQLite in-memory", "network": False, "gpu": False,
        "dataset": "Synthetic exact-repeat one-claim events; 100 ms visibility per event",
        "candidate_limit": 50, "results": results}, ensure_ascii=False, indent=2), encoding="utf-8")
    print(args.output)


if __name__ == "__main__":
    run()
