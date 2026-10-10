import copy
import json
import tempfile
import threading
import unittest
from http.client import HTTPConnection
from http.server import ThreadingHTTPServer
from pathlib import Path

from server.cognitive_history import HistoryError, HistoryStore, union_ms
from server.history_server import make_handler


def event(ident="a", start=1000, evidence="招聘数量下降20%", stance="就业困难", conditions="", work=None):
    quote = "当前就业困难"
    text = quote + "。" + evidence + "。" + conditions
    return dict(event_id=ident, work_id=work or ident, platform="reader", revision=1,
                start_ms=start, end_ms=start + 100, revision_time_ms=start + 100, text=text,
                claims=[dict(topic="就业", subject="应届生", stance=stance, quote=quote,
                             evidence=evidence, conditions=conditions, observed_ms=start)])


class HistoryTest(unittest.TestCase):
    def setUp(self):
        self.store = HistoryStore(":memory:")

    def tearDown(self):
        self.store.close()

    def report(self, owner="a"):
        return self.store.report(owner, 0, 10000)

    def test_retry_is_idempotent_and_changed_same_revision_conflicts(self):
        row = event()
        self.assertEqual("recorded", self.store.submit("a", row)["status"])
        self.assertEqual("duplicate", self.store.submit("a", row)["status"])
        self.assertEqual(1, self.report()["totals"]["visit_count"])
        row["text"] += "附加文字"
        with self.assertRaises(HistoryError) as caught:
            self.store.submit("a", row)
        self.assertEqual(409, caught.exception.status)

    def test_revision_replaces_contribution_without_double_counting(self):
        row = event()
        self.store.submit("a", row)
        revised = copy.deepcopy(row)
        revised.update(revision=2, end_ms=1500, revision_time_ms=1500)
        revised["claims"][0]["stance"] = "未来恢复"
        self.store.submit("a", revised)
        report = self.report()
        self.assertEqual(1, report["totals"]["visit_count"])
        self.assertEqual(500, report["totals"]["visible_ms"])
        self.assertEqual("未来恢复", report["viewpoints"][0]["stance"])
        earlier = self.store.report("a", 0, 1200)
        self.assertEqual("就业困难", earlier["viewpoints"][0]["stance"])

    def test_history_never_reads_future_or_same_time_events(self):
        self.store.submit("a", event("future", 3000))
        self.store.submit("a", event("now", 1000))
        self.store.submit("a", event("tie", 1000))
        report = self.report()
        states = {x["event_id"]: x for x in report["comparisons"]}
        self.assertEqual(0, states["now"]["candidate_count"])
        self.assertEqual(0, states["tie"]["candidate_count"])
        self.assertEqual("literal_repetition", states["future"]["state"])

    def test_late_input_replays_comparison_and_delete_removes_dependency(self):
        self.store.submit("a", event("new", 3000))
        self.assertEqual("initial_material", self.report()["comparisons"][0]["state"])
        self.store.submit("a", event("old", 1000))
        self.assertEqual("literal_repetition", self.report()["comparisons"][-1]["state"])
        self.store.delete("a", "old")
        report = self.report()
        self.assertEqual("initial_material", report["comparisons"][0]["state"])
        self.assertNotIn("old", json.dumps(report))

    def test_joint_evidence_coverage(self):
        self.store.submit("a", event("old1", 1000, "招聘数量下降20%"))
        self.store.submit("a", event("old2", 2000, "申请人数增长30%"))
        self.store.submit("a", event("new", 3000, "招聘数量下降20%。申请人数增长30%"))
        comparison = self.report()["comparisons"][-1]
        self.assertEqual("literal_repetition", comparison["state"])
        self.assertEqual(2, len(comparison["evidence_units"]))

    def test_new_evidence_conditions_or_stance_do_not_become_repetition(self):
        self.store.submit("a", event("old", 1000))
        for row in [event("data", 2000, "招聘数量下降10%"),
                    event("scope", 3000, conditions="仅限软件行业"), event("opposite", 4000, stance="就业改善")]:
            self.store.submit("a", row)
        self.assertTrue(all(x["state"] != "literal_repetition" for x in self.report()["comparisons"]))

    def test_missing_evidence_is_unknown_not_zero_novelty(self):
        self.store.submit("a", event("old", 1000))
        self.store.submit("a", event("new", 2000, evidence=""))
        self.assertEqual("insufficient_evidence", self.report()["comparisons"][-1]["state"])

    def test_work_and_visit_counts_and_unknown_denominator(self):
        self.store.submit("a", event("visit1", 1000, work="same"))
        self.store.submit("a", event("visit2", 2000, work="same"))
        row = event("unknown", 3000)
        row["claims"] = []
        self.store.submit("a", row)
        report = self.report()
        self.assertEqual(2, report["totals"]["work_count"])
        self.assertEqual(3, report["totals"]["visit_count"])
        self.assertEqual(1, report["totals"]["unknown_work_count"])
        self.assertEqual(50, report["viewpoints"][0]["percent_of_all_works"])

    def test_overlap_and_window_clip(self):
        row = event("first", 1000)
        row.update(end_ms=3000, revision_time_ms=3000)
        self.store.submit("a", row)
        self.store.submit("a", event("overlap", 2000))
        self.assertEqual(2000, self.report()["totals"]["visible_ms"])
        self.assertEqual(500, self.store.report("a", 2500, 3500)["totals"]["visible_ms"])
        self.assertEqual(250, union_ms([(0, 100), (50, 200), (150, 170), (250, 300)]))

    def test_user_isolation(self):
        self.store.submit("a", event())
        self.assertEqual(0, self.report("b")["totals"]["work_count"])
        self.assertEqual(0, self.store.delete("b", "a")["deleted_revisions"])
        self.assertEqual(1, self.report()["totals"]["work_count"])

    def test_unsupported_and_fabricated_evidence_rejected(self):
        for mutation in [dict(text="不含引用的文本"), dict(revision=True), dict(end_ms=0), dict(user_id="b")]:
            row = event()
            row.update(mutation)
            with self.assertRaises(HistoryError):
                self.store.submit("a", row)

    def test_revision_gap_identity_change_and_capacity(self):
        self.store.submit("a", event())
        for changes in [dict(revision=3), dict(revision=2, work_id="other"), dict(revision=2, revision_time_ms=1000)]:
            row = event()
            row.update(changes)
            with self.assertRaises(HistoryError):
                self.store.submit("a", row)
        self.store.max_events = 1
        with self.assertRaises(HistoryError):
            self.store.submit("a", event("b", 2000))

    def test_restart_preserves_records(self):
        with tempfile.TemporaryDirectory() as directory:
            path = str(Path(directory) / "history.sqlite3")
            store = HistoryStore(path)
            store.submit("a", event())
            before = store.report("a", 0, 10000)
            store.close()
            store = HistoryStore(path)
            try:
                self.assertEqual(before, store.report("a", 0, 10000))
            finally:
                store.close()

    def test_graph_and_provenance_and_no_fake_tee_or_risk(self):
        self.store.submit("a", event())
        report = self.report()
        self.assertEqual({"content", "application", "viewpoint", "evidence"}, {n["type"] for n in report["graph"]["nodes"]})
        ref = report["viewpoints"][0]["references"][0]
        self.assertEqual(ref["quote"], event()["text"][ref["quote_start"]:ref["quote_start"] + len(ref["quote"])])
        self.assertFalse(report["limitations"]["tee"])
        self.assertEqual("not_evaluated", report["observation"]["risk_state"])

    def test_future_revision_does_not_change_asof_report_hash(self):
        self.store.submit("a", event())
        before = self.store.report("a", 0, 2000)
        self.store.submit("a", event("future", 3000))
        self.assertEqual(before, self.store.report("a", 0, 2000))

    def test_previous_version_is_used_before_future_revision(self):
        old = event("old", 1000)
        self.store.submit("a", old)
        revised = copy.deepcopy(old)
        revised.update(revision=2, end_ms=4000, revision_time_ms=4000)
        revised["claims"][0]["stance"] = "就业改善"
        self.store.submit("a", revised)
        self.store.submit("a", event("new", 2000))
        row = next(c for c in self.report()["comparisons"] if c["event_id"] == "new")
        self.assertEqual("literal_match", row["stance_relation"])
        self.assertEqual(1, row["evidence_units"][0]["history"][0]["revision"])

    def test_parallel_retries_store_one_revision(self):
        results = []
        threads = [threading.Thread(target=lambda: results.append(self.store.submit("a", event())["status"])) for _ in range(8)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join()
        self.assertEqual(1, results.count("recorded"))
        self.assertEqual(7, results.count("duplicate"))

    def test_same_work_does_not_compare_against_itself(self):
        self.store.submit("a", event("visit1", 1000, work="same"))
        self.store.submit("a", event("visit2", 2000, work="same"))
        self.assertTrue(all(c["candidate_count"] == 0 for c in self.report()["comparisons"]))

    def test_multiple_viewpoints_can_overlap_without_fake_percent_sum(self):
        row = event()
        other = copy.deepcopy(row["claims"][0])
        other["stance"] = "就业改善"
        row["claims"].append(other)
        self.store.submit("a", row)
        report = self.report()
        self.assertEqual(200, sum(v["percent_of_all_works"] for v in report["viewpoints"]))
        self.assertTrue(report["limitations"]["multi_label_percentages_may_exceed_100"])


class HistoryHttpTest(unittest.TestCase):
    def setUp(self):
        self.store = HistoryStore(":memory:")
        self.token = "a" * 32
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(self.store, {"a": self.token, "b": "b" * 32}))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.store.close()

    def request(self, path, payload, token=None):
        connection = HTTPConnection(*self.server.server_address, timeout=5)
        connection.request("POST", path, json.dumps(payload), {"Content-Type": "application/json", "Authorization": "Bearer " + (token or self.token)})
        response = connection.getresponse()
        result = response.status, json.loads(response.read()), dict(response.getheaders())
        connection.close()
        return result

    def test_auth_export_and_delete(self):
        self.assertEqual(401, self.request("/history/events", event(), "invalid")[0])
        self.assertEqual(200, self.request("/history/events", event())[0])
        status, report, headers = self.request("/history/report", {"start_ms": 0, "end_ms": 10000})
        self.assertEqual(200, status)
        self.assertEqual(1, report["totals"]["work_count"])
        self.assertIn("attachment", headers["Content-Disposition"])
        self.assertEqual("no-store", headers["Cache-Control"])
        self.assertEqual(0, self.request("/history/report", {"start_ms": 0, "end_ms": 10000}, "b" * 32)[1]["totals"]["work_count"])
        self.assertEqual(1, self.request("/history/delete", {"event_id": "a"})[1]["deleted_revisions"])

    def test_spoofed_owner_and_bad_window_rejected(self):
        self.assertEqual(400, self.request("/history/events", event() | {"owner": "b"})[0])
        self.assertEqual(400, self.request("/history/report", {"start_ms": 10000, "end_ms": 0})[0])


if __name__ == "__main__":
    unittest.main()
