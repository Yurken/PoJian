"""Application signature and encrypted transport; no hardware attestation claim.

The client pins an operator-provisioned Ed25519 public key AND code measurement.
A host controlling this process can read plaintext and use its signing key. A
client-held high-water mark detects older server snapshots, not a rollback of
both the server and the client's protected storage.
"""
import base64
import hashlib
import json
import os
import secrets
import sqlite3
import time
from dataclasses import dataclass
from pathlib import Path

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey, X25519PublicKey
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

try:
    from .semantic_contract import AttestationRequest, AttestationResponse, EncryptedAnalyzeRequest, EncryptedAnalyzeResponse
except ImportError:
    from semantic_contract import AttestationRequest, AttestationResponse, EncryptedAnalyzeRequest, EncryptedAnalyzeResponse

ZERO_HASH = "0" * 64


def b64(data: bytes) -> str:
    return base64.b64encode(data).decode("ascii")


def unb64(value: str, length: int | None = None) -> bytes:
    raw = base64.b64decode(value, validate=True)
    if b64(raw) != value or (length is not None and len(raw) != length):
        raise ValueError("Invalid canonical base64")
    return raw


def code_measurement() -> str:
    digest = hashlib.sha256()
    for name in ("monitor_server.py", "emerge_server.py", "claim_presentation.py", "fact_check_cache.py",
                 "information_supplement.py", "source_provenance.py", "capture_cleanup.py", "admin.py",
                 "semantic_contract.py", "crypto_guard.py", "cognitive_api.py", "secure_gateway.py", "requirements.txt"):
        raw = Path(__file__).with_name(name).read_bytes()
        digest.update(name.encode() + b"\x00" + str(len(raw)).encode() + b"\x00" + raw)
    return digest.hexdigest()


def transcript(req: AttestationRequest, reply: AttestationResponse) -> bytes:
    return "\n".join(("pojian-attestation-v2", req.client_id, req.client_nonce,
        req.client_ephemeral_pubkey, reply.session_id, reply.server_ephemeral_pubkey,
        reply.code_measurement_hash, str(reply.timestamp_ms))).encode()


def request_aad(req: EncryptedAnalyzeRequest) -> bytes:
    return "|".join(("pojian-v2", "request", req.session_id, req.client_id, req.request_id,
        str(req.expected_state_version), req.expected_state_hash)).encode()


def response_aad(req: EncryptedAnalyzeRequest, response: EncryptedAnalyzeResponse) -> bytes:
    return "|".join(("pojian-v2", "response", req.session_id, req.client_id, req.request_id,
        response.status, response.alert_code or "", str(response.new_state_version), response.new_state_hash)).encode()


def next_state_hash(req: EncryptedAnalyzeRequest) -> str:
    record = hashlib.sha256(unb64(req.ciphertext_base64)).hexdigest()
    return hashlib.sha256(f"{req.expected_state_hash}|{req.request_id}|{record}|v{req.expected_state_version + 1}".encode()).hexdigest()


@dataclass
class Session:
    client_id: str
    client_public: str
    receive_key: bytes
    send_key: bytes
    expires: float
    request_digest: str | None = None


class SecurityStateStore:
    def __init__(self, path: str, signing_key: bytes, ttl: int = 120, max_sessions: int = 256):
        self.signer = Ed25519PrivateKey.from_private_bytes(signing_key)
        self.golden_measurement = code_measurement()
        self.ttl, self.max_sessions = ttl, max_sessions
        self.sessions: dict[str, Session] = {}
        self.simulate_tamper_attack = False
        self.simulate_rollback_attack = False
        self.db = sqlite3.connect(path, check_same_thread=False)
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("PRAGMA synchronous=FULL")
        self.db.executescript("""
            CREATE TABLE IF NOT EXISTS client_state (
                client_id TEXT PRIMARY KEY, version INTEGER NOT NULL, state_hash TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS receipts (
                client_id TEXT NOT NULL, request_id TEXT NOT NULL, request_digest TEXT NOT NULL,
                response TEXT NOT NULL, PRIMARY KEY(client_id,request_id));
        """)
        self.db.commit()
        if path != ":memory:":
            os.chmod(path, 0o600)

    @property
    def public_key(self) -> str:
        return b64(self.signer.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw))

    def close(self):
        self.sessions.clear()
        self.db.close()

    def create_attestation_bundle(self, req: AttestationRequest) -> AttestationResponse:
        now = time.monotonic()
        self.sessions = {key: item for key, item in self.sessions.items() if item.expires > now}
        if len(self.sessions) >= self.max_sessions:
            raise OverflowError("Session capacity reached")
        private = X25519PrivateKey.generate()
        shared = private.exchange(X25519PublicKey.from_public_bytes(unb64(req.client_ephemeral_pubkey, 32)))
        measurement = code_measurement()
        if self.simulate_tamper_attack:
            measurement = hashlib.sha256((measurement + "simulated-tamper").encode()).hexdigest()
        reply = AttestationResponse(session_id=secrets.token_hex(16),
            server_ephemeral_pubkey=b64(private.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)),
            code_measurement_hash=measurement, quote_signature="", timestamp_ms=int(time.time() * 1000))
        signed = transcript(req, reply)
        reply.quote_signature = b64(self.signer.sign(signed))
        material = HKDF(algorithm=hashes.SHA256(), length=64, salt=b"pojian-hpke-v2-salt",
            info=b"pojian-cognitive-channel|" + hashlib.sha256(signed).digest()).derive(shared)
        self.sessions[reply.session_id] = Session(req.client_id, req.client_ephemeral_pubkey,
            material[:32], material[32:], now + self.ttl)
        return reply

    def get_session(self, req: EncryptedAnalyzeRequest) -> Session:
        session = self.sessions.get(req.session_id)
        if session is None or session.expires <= time.monotonic():
            self.sessions.pop(req.session_id, None)
            raise KeyError("Session expired")
        if session.client_id != req.client_id or session.client_public != req.client_ephemeral_pubkey:
            raise KeyError("Session binding mismatch")
        return session

    def decrypt_payload(self, session: Session, req: EncryptedAnalyzeRequest) -> bytes:
        digest = self.request_digest(req)
        if session.request_digest is not None and session.request_digest != digest:
            raise ValueError("Session is already bound to another request")
        raw = AESGCM(session.receive_key).decrypt(unb64(req.iv_base64, 12), unb64(req.ciphertext_base64), request_aad(req))
        session.request_digest = digest
        return raw

    def encrypt_response(self, session: Session, req: EncryptedAnalyzeRequest, data: dict,
                         version: int, state_hash: str, alert: str | None = None) -> EncryptedAnalyzeResponse:
        iv = os.urandom(12)
        result = EncryptedAnalyzeResponse(status="SECURITY_BLOCKED" if alert else "OK", alert_code=alert,
            new_state_version=version, new_state_hash=state_hash, iv_base64=b64(iv), ciphertext_base64="")
        raw = json.dumps(data, ensure_ascii=False, allow_nan=False, separators=(",", ":")).encode()
        result.ciphertext_base64 = b64(AESGCM(session.send_key).encrypt(iv, raw, response_aad(req, result)))
        return result

    def state(self, client_id: str) -> tuple[int, str]:
        row = self.db.execute("SELECT version,state_hash FROM client_state WHERE client_id=?", (client_id,)).fetchone()
        return tuple(row) if row else (0, ZERO_HASH)

    def matches(self, req: EncryptedAnalyzeRequest) -> bool:
        return not self.simulate_rollback_attack and self.state(req.client_id) == (req.expected_state_version, req.expected_state_hash)

    @staticmethod
    def request_digest(req: EncryptedAnalyzeRequest) -> str:
        return hashlib.sha256(req.model_dump_json().encode()).hexdigest()

    def receipt(self, req: EncryptedAnalyzeRequest) -> EncryptedAnalyzeResponse | None:
        row = self.db.execute("SELECT request_digest,response FROM receipts WHERE client_id=? AND request_id=?",
                              (req.client_id, req.request_id)).fetchone()
        if not row:
            return None
        if row[0] != self.request_digest(req):
            raise ValueError("Request identity reused with different ciphertext")
        return EncryptedAnalyzeResponse.model_validate_json(row[1])

    def commit(self, req: EncryptedAnalyzeRequest, response: EncryptedAnalyzeResponse) -> bool:
        # State and retry receipt are durable in the SAME transaction. Keep only
        # bounded recent receipts per device: clients serialize requests and
        # persist the pending envelope until authenticating its response.
        self.db.execute("BEGIN IMMEDIATE")
        try:
            if not self.matches(req):
                self.db.rollback()
                return False
            self.db.execute("INSERT OR REPLACE INTO client_state VALUES (?,?,?)",
                            (req.client_id, response.new_state_version, response.new_state_hash))
            self._put_receipt(req, response)
            self.db.commit()
            return True
        except Exception:
            self.db.rollback()
            raise

    def _put_receipt(self, req, response):
        self.db.execute("INSERT INTO receipts VALUES (?,?,?,?)", (req.client_id, req.request_id,
            self.request_digest(req), response.model_dump_json()))
        self.db.execute("DELETE FROM receipts WHERE client_id=? AND rowid NOT IN "
            "(SELECT rowid FROM receipts WHERE client_id=? ORDER BY rowid DESC LIMIT 64)", (req.client_id, req.client_id))

    def reject(self, req: EncryptedAnalyzeRequest, response: EncryptedAnalyzeResponse):
        # A signed/encrypted rejection is terminal for this request. Otherwise
        # replaying an earlier rejection after a later success could make a
        # client drop the pending envelope while its server state had advanced.
        with self.db:
            self._put_receipt(req, response)
        self.sessions.pop(req.session_id, None)
