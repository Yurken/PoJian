"""Opt-in local history prototype; separate from the production monitor gateway."""
from __future__ import annotations

import hmac
import json
import os
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

try:
    from server.cognitive_history import HistoryError, HistoryStore
    from server.emerge_server import decode_json
except ImportError:
    from cognitive_history import HistoryError, HistoryStore
    from emerge_server import decode_json


def make_handler(store, users):
    if not users or any(not isinstance(k, str) or not k or not isinstance(v, str) or len(v) < 24 for k, v in users.items()):
        raise ValueError("Provide per-user tokens of at least 24 characters")
    if len(set(users.values())) != len(users):
        raise ValueError("User tokens must be distinct")
    users = dict(users)

    class Handler(BaseHTTPRequestHandler):
        # Browser content, tokens and user identifiers never enter access logs.
        def log_message(self, *_):
            pass

        def reply(self, status, value, download=False):
            body = json.dumps(value, ensure_ascii=False, allow_nan=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            if download:
                self.send_header("Content-Disposition", 'attachment; filename="PoJian-history-report.json"')
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            if self.path == "/health":
                return self.reply(200, {"service": "history-prototype", "tee": False, "encrypted_storage": False})
            self.reply(404, {"error": "Not found"})

        def do_POST(self):
            values = self.headers.get_all("Authorization", [])
            owner = None
            if len(values) == 1 and values[0].startswith("Bearer "):
                token = values[0][7:].encode("utf-8")
                for user, secret in users.items():
                    if hmac.compare_digest(token, secret.encode("utf-8")):
                        owner = user
            if owner is None:
                self.close_connection = True
                return self.reply(401, {"error": "Invalid user token"})
            path = urlsplit(self.path).path
            if path not in {"/history/events", "/history/report", "/history/delete"}:
                self.close_connection = True
                return self.reply(404, {"error": "Not found"})
            try:
                lengths = self.headers.get_all("Content-Length", [])
                if self.headers.get("Transfer-Encoding") or len(lengths) != 1 or not lengths[0].isdigit():
                    raise HistoryError("Content-Length required", 411)
                if len(lengths[0]) > 7 or not 0 < int(lengths[0]) <= 256 * 1024:
                    raise HistoryError("Body size limit exceeded", 413)
                if self.headers.get_content_type() != "application/json":
                    raise HistoryError("Expected JSON", 415)
                self.connection.settimeout(10)
                payload = decode_json(self.rfile.read(int(lengths[0])))
                if not isinstance(payload, dict):
                    raise HistoryError("Expected object")
                if path == "/history/events":
                    return self.reply(200, store.submit(owner, payload))
                if path == "/history/report":
                    if set(payload) != {"start_ms", "end_ms"}:
                        raise HistoryError("Invalid report fields")
                    return self.reply(200, store.report(owner, **payload), download=True)
                if set(payload) != {"event_id"} or not isinstance(payload["event_id"], str) or not 1 <= len(payload["event_id"]) <= 120:
                    raise HistoryError("Invalid event identifier")
                return self.reply(200, store.delete(owner, payload["event_id"]))
            except HistoryError as error:
                self.close_connection = True
                self.reply(error.status, {"error": str(error)})
            except (ValueError, UnicodeError, RecursionError):
                self.close_connection = True
                self.reply(400, {"error": "Invalid JSON"})
            except (BrokenPipeError, ConnectionResetError):
                pass
            except Exception:
                self.close_connection = True
                self.reply(500, {"error": "History processing failed"})

    return Handler


def main():
    # Explicit opt-in: never start plaintext history storage via the monitor service.
    if os.getenv("POJIAN_HISTORY_PROTOTYPE") != "1":
        raise SystemExit("Set POJIAN_HISTORY_PROTOTYPE=1 for local protocol testing")
    users = decode_json(os.environ.get("POJIAN_HISTORY_USERS", "{}").encode())
    if not isinstance(users, dict):
        raise SystemExit("Invalid user token configuration")
    path = Path(os.getenv("POJIAN_HISTORY_DB", str(Path(__file__).parent / ".runtime/history.sqlite3")))
    path.parent.mkdir(parents=True, exist_ok=True)
    store = HistoryStore(str(path))
    server = ThreadingHTTPServer(("127.0.0.1", int(os.getenv("POJIAN_HISTORY_PORT", "8788"))), make_handler(store, users))
    server.daemon_threads = True
    print("Local history prototype ready; storage is plaintext, TEE is unavailable.", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        store.close()


if __name__ == "__main__":
    main()
