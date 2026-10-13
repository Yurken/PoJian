"""Bounded process-local fact results; concurrent callers share one external check."""
from concurrent.futures import Future
import copy
import threading
import time


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
