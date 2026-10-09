"""Read-only service status dashboard. Tracks counters only — never request content or credentials."""
from __future__ import annotations

import hmac
import json
import secrets
import threading
import time
from collections import deque
from pathlib import Path

ADMIN_HTML_PATH = Path(__file__).with_name("admin.html")
_html_cache: str | None = None
_html_lock = threading.Lock()


def bearer_authorized(values: list[str], token: str) -> bool:
    """Constant-time check for a single 'Bearer <token>' Authorization header."""
    return len(values) == 1 and bool(token) and hmac.compare_digest(
        values[0].encode("utf-8"), ("Bearer " + token).encode("utf-8"))


class Sessions:
    """Short-lived in-memory login sessions issued to admin console browsers."""

    def __init__(self, ttl_seconds: int = 12 * 3600):
        self.ttl = ttl_seconds
        self._lock = threading.Lock()
        self._tokens: dict[str, float] = {}

    def issue(self) -> str:
        token = secrets.token_hex(32)
        with self._lock:
            self._tokens[token] = time.time() + self.ttl
        return token

    def valid(self, token: str) -> bool:
        with self._lock:
            expiry = self._tokens.get(token)
            if expiry is None:
                return False
            if time.time() > expiry:
                del self._tokens[token]
                return False
            return True

    def revoke(self, token: str) -> None:
        with self._lock:
            self._tokens.pop(token, None)


class LoginThrottle:
    """Per-IP lockout after repeated failed console logins."""

    def __init__(self, limit: int = 5, lock_seconds: int = 30):
        self.limit = limit
        self.lock_seconds = lock_seconds
        self._lock = threading.Lock()
        self._failures: dict[str, list[float]] = {}

    def allowed(self, ip: str) -> bool:
        now = time.time()
        with self._lock:
            return len([t for t in self._failures.get(ip, []) if now - t < self.lock_seconds]) < self.limit

    def failed(self, ip: str) -> None:
        with self._lock:
            self._failures.setdefault(ip, []).append(time.time())

    def reset(self, ip: str) -> None:
        with self._lock:
            self._failures.pop(ip, None)


def admin_html() -> str:
    global _html_cache
    with _html_lock:
        if _html_cache is None:
            _html_cache = ADMIN_HTML_PATH.read_text(encoding="utf-8")
        return _html_cache


def mask_secret(value: str) -> str:
    """Show at most the last four characters of a credential; empty means unconfigured."""
    if not value:
        return ""
    tail = value[-4:]
    return "****" + tail


def _round_ms(value: float) -> float:
    return round(value, 1)


class Stats:
    """Thread-safe request counters with a small ring buffer of recent requests."""

    def __init__(self, recent_limit: int = 100):
        self.started = time.time()
        self.lock = threading.Lock()
        self.total = 0
        self.active = 0
        self.endpoints: dict[str, dict] = {}
        self.by_status: dict[int, int] = {}
        self.recent: deque[dict] = deque(maxlen=recent_limit)

    def begin(self) -> float:
        with self.lock:
            self.active += 1
        return time.monotonic()

    def end(self, token: float, path: str, status: int) -> None:
        elapsed = (time.monotonic() - token) * 1000
        with self.lock:
            self.active = max(0, self.active - 1)
            self.total += 1
            entry = self.endpoints.setdefault(path, {"count": 0, "errors": 0, "total_ms": 0.0, "max_ms": 0.0})
            entry["count"] += 1
            entry["total_ms"] += elapsed
            entry["max_ms"] = max(entry["max_ms"], elapsed)
            if status >= 400:
                entry["errors"] += 1
            self.by_status[status] = self.by_status.get(status, 0) + 1
            self.recent.append({"time": time.time(), "path": path, "status": status, "ms": _round_ms(elapsed)})

    def snapshot(self) -> dict:
        with self.lock:
            endpoints = {}
            for path, entry in self.endpoints.items():
                count = entry["count"]
                endpoints[path] = {
                    "count": count,
                    "errors": entry["errors"],
                    "avg_ms": _round_ms(entry["total_ms"] / count) if count else 0.0,
                    "max_ms": _round_ms(entry["max_ms"]),
                }
            by_status = {str(code): count for code, count in sorted(self.by_status.items())}
            recent = [dict(row) for row in list(self.recent)]
            recent.reverse()
            return {
                "uptime_seconds": int(time.time() - self.started),
                "total": self.total,
                "active": self.active,
                "endpoints": endpoints,
                "by_status": by_status,
                "recent": recent,
            }


def empty_snapshot() -> dict:
    return {"uptime_seconds": 0, "total": 0, "active": 0, "endpoints": {}, "by_status": {}, "recent": []}


def service_payload(stats: Stats | None, *, monitor: bool, model_configured: bool,
                    vision_configured: bool = False, retrieval_configured: bool = False) -> dict:
    return {
        "monitor": monitor,
        "protocol": 1,
        "model_configured": model_configured,
        "vision_configured": vision_configured,
        "retrieval_configured": retrieval_configured,
        "stats": stats.snapshot() if stats else empty_snapshot(),
    }


def status_json(payload: dict) -> bytes:
    return json.dumps(payload, ensure_ascii=False, allow_nan=False).encode("utf-8")
