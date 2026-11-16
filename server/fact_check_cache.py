"""Bounded process-local fact results; concurrent callers share one external check."""
from concurrent.futures import Future
import copy
import threading
import time
import re


def independent_fact_counts(viewpoints, history_facts):
    """Local exposure coverage, deliberately separate from external fact truth.

    The existing FactCheckCache caches public verification, not user histories;
    its global entries must not leak another device's private exposure history.
    """
    history = {item.fact_id: item for item in history_facts}
    if len(history) != len(history_facts):
        raise ValueError("Duplicate historical fact identity")
    facts, point_identities = {}, {}
    for viewpoint in viewpoints:
        for evidence in viewpoint.evidences:
            if evidence.is_covered_by_history:
                if evidence.matched_history_id not in history:
                    raise ValueError("Historical fact reference does not resolve")
                matched = history[evidence.matched_history_id]
                # The model cannot infer a "14 works" citation count from a
                # bounded list of unique historical facts. Derive this note
                # only from the record that actually resolved.
                location = " · ".join(value for value in (matched.source_platform.strip(), matched.captured_at.strip()) if value)
                evidence.matched_source_note = "与历史事实重合" + (f"（{location}）" if location else "")
            elif evidence.matched_history_id is not None or evidence.matched_source_note:
                raise ValueError("Historical reference without coverage")
            point = re.sub(r"\s+", " ", evidence.fact_point.strip()).lower()
            identity = (evidence.is_covered_by_history, evidence.matched_history_id)
            if point in point_identities and point_identities[point] != identity:
                raise ValueError("Inconsistent duplicate fact coverage")
            point_identities[point] = identity
            key = ("history", evidence.matched_history_id) if evidence.is_covered_by_history else ("new", point)
            facts[key] = evidence.is_covered_by_history
    total = len(facts)
    new = sum(not covered for covered in facts.values())
    return new, total, new / total if total else 0.0


class FactCheckCache:
    def __init__(self, capacity=128, ttl=3600):
        self.capacity, self.ttl = capacity, ttl
        self._lock = threading.Lock()
        self._results = {}
        self._running = {}

    def run(self, key, deadline, check, allow_create=True):
        with self._lock:
            now = time.monotonic()
            self._results = {k: v for k, v in self._results.items() if 0 <= now - v[0] < self.ttl}
            if key in self._results:
                return copy.deepcopy(self._results[key][1]), True
            future = self._running.get(key)
            owner = future is None
            if owner:
                if not allow_create:
                    return None, False
                future = Future()
                self._running[key] = future
        if not owner:
            return copy.deepcopy(future.result(timeout=max(0, deadline - time.monotonic()))), True
        try:
            result = check()
            with self._lock:
                self._results[key] = (time.monotonic(), copy.deepcopy(result))
                while len(self._results) > self.capacity:
                    self._results.pop(next(iter(self._results)))
            future.set_result(copy.deepcopy(result))
            return result, False
        except Exception as error:
            future.set_exception(error)
            raise
        finally:
            with self._lock:
                self._running.pop(key, None)
