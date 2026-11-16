"""V2 handlers used by the existing threaded HTTP server and admin console."""
import asyncio
import os
import threading
from pathlib import Path

try:
    from .crypto_guard import SecurityStateStore, next_state_hash, unb64
    from .semantic_contract import AttestationRequest, EncryptedAnalyzeRequest, PlainAnalyzePayload
    from .cognitive_api import CognitiveApiEngine
    from .admin import SecurityAudit
except ImportError:
    from crypto_guard import SecurityStateStore, next_state_hash, unb64
    from semantic_contract import AttestationRequest, EncryptedAnalyzeRequest, PlainAnalyzePayload
    from cognitive_api import CognitiveApiEngine
    from admin import SecurityAudit
from cryptography.exceptions import InvalidTag
from pydantic import ValidationError


class SecureGateway:
    def __init__(self, store, engine_factory=CognitiveApiEngine, debug=False):
        self.store, self.engine_factory, self.debug = store, engine_factory, debug
        self.lock = threading.RLock()
        self.audit = SecurityAudit()
        self.reserve_budget = lambda: True

    @classmethod
    def from_env(cls):
        value = os.getenv("POJIAN_SIGNING_PRIVATE_KEY", "")
        if not value:
            return None
        path = os.getenv("POJIAN_STATE_DB", str(Path(os.getenv("EMERGE_BUDGET_DB", "server/.runtime/monitor-budget.sqlite3")).with_name("security.sqlite3")))
        Path(path).parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        return cls(SecurityStateStore(path, unb64(value, 32)), debug=os.getenv("POJIAN_ENABLE_DEBUG") == "1")

    def status(self):
        with self.lock:
            row = self.store.db.execute("SELECT version,state_hash FROM client_state ORDER BY version DESC LIMIT 1").fetchone()
            return {"protocol": "X25519 + AES-256-GCM", "enclave_mode": "SIGNED_APPLICATION_V2",
                "code_measurement_hash": self.store.golden_measurement,
                "signing_public_key": self.store.public_key,
                "current_version": row[0] if row else 0, "current_state_hash": row[1] if row else "0"*64,
                "debug_enabled": self.debug, "simulate_tamper_attack": self.store.simulate_tamper_attack,
                "simulate_rollback_attack": self.store.simulate_rollback_attack, "logs": self.audit.snapshot()}

    def toggle(self, kind):
        if not self.debug:
            return 404, {"error": "Attack simulation is disabled"}
        with self.lock:
            field = "simulate_tamper_attack" if kind == "tamper" else "simulate_rollback_attack"
            setattr(self.store, field, not getattr(self.store, field))
            self.audit.record("SIMULATED_" + kind.upper() + "_TOGGLE")
            return 200, {field: getattr(self.store, field)}

    def attest(self, payload):
        try:
            req = AttestationRequest.model_validate(payload)
            with self.lock:
                reply = self.store.create_attestation_bundle(req)
            self.audit.record("APPLICATION_SIGNATURE")
            return 200, reply.model_dump()
        except OverflowError:
            return 429, {"error": "Session capacity reached"}
        except (ValidationError, ValueError):
            return 422, {"error": "Invalid attestation contract"}

    async def _infer(self, payload):
        engine = self.engine_factory()
        try:
            return await asyncio.wait_for(engine.analyze(payload), timeout=45)
        finally:
            await engine.close()

    def analyze(self, payload):
        try:
            req = EncryptedAnalyzeRequest.model_validate(payload)
        except ValidationError:
            return 422, {"error": "Invalid encrypted request contract"}
        # Reject concurrent submissions instead of holding unbounded threads.
        if not self.lock.acquire(blocking=False):
            return 429, {"error": "Signed analysis is busy"}
        try:
            try:
                receipt = self.store.receipt(req)
                if receipt:
                    return 200, receipt.model_dump()
                session = self.store.get_session(req)
            except KeyError:
                return 401, {"error": "Session expired or binding invalid"}
            except ValueError:
                return 409, {"error": "Request identity conflict"}

            def blocked(code):
                self.audit.record(code)
                reply = self.store.encrypt_response(session, req, {"alert_message": "安全校验失败，已阻断"},
                    *self.store.state(req.client_id), alert=code)
                self.store.reject(req, reply)
                return 200, reply.model_dump()
            try:
                raw = self.store.decrypt_payload(session, req)
            except (ValueError, InvalidTag):
                return blocked("GCM_AUTH_FAILED")
            if not self.store.matches(req):
                return blocked("STATE_ROLLBACK_DETECTED")
            try:
                try:
                    from .emerge_server import decode_json
                except ImportError:
                    from emerge_server import decode_json
                source = PlainAnalyzePayload.model_validate(decode_json(raw))
            except (ValueError, ValidationError):
                return blocked("PAYLOAD_INVALID")
            self.audit.record("CIPHER_INGRESS", req.ciphertext_base64)
            if not self.reserve_budget():
                return 429, {"error": "Daily monitor request budget exhausted"}
            try:
                result = asyncio.run(self._infer(source))
                if result.work_id != source.work_id:
                    raise ValueError("Work identity mismatch")
            except Exception:
                self.audit.record("ANALYSIS_FAILED")
                # Retryable transport error: no chain advancement and session
                # remains available for the exact already-persisted envelope.
                return 502, {"error": "Model analysis unavailable; state unchanged"}
            reply = self.store.encrypt_response(session, req, result.model_dump(),
                req.expected_state_version+1, next_state_hash(req))
            if len(reply.model_dump_json().encode()) > 240 * 1024:
                # Leave margin for the existing HTTP JSON serializer and the
                # Android 256 KiB cap; never commit an undeliverable receipt.
                return blocked("RESULT_TOO_LARGE")
            if not self.store.commit(req, reply):
                return blocked("STATE_ROLLBACK_DETECTED")
            self.store.sessions.pop(req.session_id, None)
            self.audit.record("ANALYSIS_COMPLETED", reply.ciphertext_base64)
            return 200, reply.model_dump()
        finally:
            self.lock.release()
