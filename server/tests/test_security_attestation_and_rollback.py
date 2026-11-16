import base64
import hashlib
import http.client
import json
import os
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from types import SimpleNamespace
from unittest.mock import patch
from http.server import ThreadingHTTPServer

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from cryptography.exceptions import InvalidSignature, InvalidTag
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey, X25519PublicKey
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from crypto_guard import SecurityStateStore, b64, unb64, transcript, request_aad, response_aad, next_state_hash
from semantic_contract import AttestationRequest, AttestationResponse, EncryptedAnalyzeRequest, EncryptedAnalyzeResponse, PlainAnalyzePayload, HistoryFactItem
from cognitive_api import validated_result, CognitiveApiEngine
from secure_gateway import SecureGateway
import monitor_server as monitor
from emerge_server import Config

TEXT = "企业宣布减少岗位。所有毕业生都没有出路。"


def payload():
    return PlainAnalyzePayload(work_id="work-1", title="招聘", platform="平台", author="", clean_text=TEXT, dwell_seconds=10)


def model_result():
    return {"topic_name": "就业", "is_new_topic": True,
        "viewpoints": [{"stance_summary": "就业困难", "stance_span": "所有毕业生都没有出路", "author_attitude": "支持",
            "evidences": [{"fact_point": "企业减少岗位", "fact_span": "企业宣布减少岗位", "is_covered_by_history": False,
                           "matched_history_id": None, "matched_source_note": None}]}],
        "emotional_guidance": {"is_over_extrapolation": True, "fact_span": "企业宣布减少岗位", "conclusion_span": "所有毕业生都没有出路",
            "scope_extrapolation": "企业到所有毕业生", "certainty_upgrade": "", "emotion_amplification": "无助化", "span_verified": True},
        "blind_spots": [], "expression_spans": {"fact_spans": ["企业宣布减少岗位"], "emotion_spans": ["所有毕业生都没有出路"]}}


class FakeEngine:
    calls = 0
    fail = False
    async def analyze(self, source):
        FakeEngine.calls += 1
        if FakeEngine.fail:
            raise RuntimeError("sensitive provider diagnostic")
        return validated_result(source, model_result(), 1)
    async def close(self):
        pass


class Peer:
    def __init__(self, gateway, client_id="1"*32):
        self.private = X25519PrivateKey.generate()
        self.request = AttestationRequest(client_id=client_id, client_nonce=os.urandom(32).hex(),
            client_ephemeral_pubkey=b64(self.private.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)))
        status, quote = gateway.attest(self.request.model_dump())
        assert status == 200
        self.quote = AttestationResponse.model_validate(quote)
        signed = transcript(self.request, self.quote)
        Ed25519PublicKey.from_public_bytes(unb64(gateway.store.public_key)).verify(unb64(self.quote.quote_signature), signed)
        shared = self.private.exchange(X25519PublicKey.from_public_bytes(unb64(self.quote.server_ephemeral_pubkey)))
        material = HKDF(algorithm=hashes.SHA256(), length=64, salt=b"pojian-hpke-v2-salt",
            info=b"pojian-cognitive-channel|" + hashlib.sha256(signed).digest()).derive(shared)
        self.request_key, self.response_key = material[:32], material[32:]

    def encrypt(self, version=0, state_hash="0"*64):
        request = EncryptedAnalyzeRequest(session_id=self.quote.session_id, client_id=self.request.client_id,
            request_id=os.urandom(16).hex(), client_ephemeral_pubkey=self.request.client_ephemeral_pubkey,
            iv_base64=b64(os.urandom(12)), ciphertext_base64=b64(b"0"*20),
            expected_state_version=version, expected_state_hash=state_hash)
        request.ciphertext_base64 = b64(AESGCM(self.request_key).encrypt(unb64(request.iv_base64), payload().model_dump_json().encode(), request_aad(request)))
        return request

    def decrypt(self, request, reply):
        response = EncryptedAnalyzeResponse.model_validate(reply)
        return json.loads(AESGCM(self.response_key).decrypt(unb64(response.iv_base64), unb64(response.ciphertext_base64), response_aad(request, response)))


class SecurityTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = str(Path(self.temp.name)/"security.sqlite3")
        self.key = bytes(range(32))
        self.store = SecurityStateStore(self.path, self.key)
        self.gateway = SecureGateway(self.store, FakeEngine, debug=True)
        FakeEngine.calls, FakeEngine.fail = 0, False
    def tearDown(self):
        self.store.close()
        self.temp.cleanup()

    def test_normal_encrypted_exchange_and_durable_exact_retry(self):
        peer = Peer(self.gateway)
        req = peer.encrypt()
        status, reply = self.gateway.analyze(req.model_dump())
        self.assertEqual(status, 200)
        self.assertEqual(peer.decrypt(req, reply)["work_id"], "work-1")
        self.assertEqual(reply["new_state_hash"], next_state_hash(req))
        self.assertEqual(self.store.state(req.client_id), (1, reply["new_state_hash"]))
        self.store.close()
        self.store = SecurityStateStore(self.path, self.key)
        self.gateway = SecureGateway(self.store, FakeEngine)
        self.assertEqual(self.gateway.analyze(req.model_dump()), (200, reply))
        self.assertEqual(FakeEngine.calls, 1)
        raw = Path(self.path).read_bytes()
        self.assertNotIn(TEXT.encode(), raw)
        self.assertNotIn(TEXT, json.dumps(self.gateway.status(), ensure_ascii=False))

    def test_tampered_quote_signature_nonce_and_measurement_are_detectable(self):
        peer = Peer(self.gateway)
        changed = peer.quote.model_copy(update={"code_measurement_hash": "f"*64})
        with self.assertRaises(InvalidSignature):
            Ed25519PublicKey.from_public_bytes(unb64(self.store.public_key)).verify(unb64(changed.quote_signature), transcript(peer.request, changed))
        self.gateway.toggle("tamper")
        peer2 = Peer(self.gateway)
        self.assertNotEqual(peer2.quote.code_measurement_hash, self.store.golden_measurement)
        # Valid signature alone does not authorize unpinned measurements.

    def test_aad_ciphertext_and_response_metadata_tampering_fail(self):
        peer = Peer(self.gateway)
        req = peer.encrypt()
        req.expected_state_version = 7
        status, reply = self.gateway.analyze(req.model_dump())
        self.assertEqual(reply["alert_code"], "GCM_AUTH_FAILED")
        peer.decrypt(req, reply)
        self.assertEqual(FakeEngine.calls, 0)
        reply["new_state_version"] = 8
        with self.assertRaises(InvalidTag):
            peer.decrypt(req, reply)

    def test_exact_version_and_hash_match_required_both_directions(self):
        for version, state_hash in [(1, "0"*64), (0, "f"*64)]:
            peer = Peer(self.gateway)
            req = peer.encrypt(version, state_hash)
            status, reply = self.gateway.analyze(req.model_dump())
            self.assertEqual(reply["alert_code"], "STATE_ROLLBACK_DETECTED")
            peer.decrypt(req, reply)
        self.assertEqual(FakeEngine.calls, 0)

    def test_real_database_rollback_is_rejected_by_client_high_watermark(self):
        peer = Peer(self.gateway)
        req = peer.encrypt()
        _, reply = self.gateway.analyze(req.model_dump())
        self.store.db.execute("DELETE FROM client_state")
        self.store.db.commit()
        next_peer = Peer(self.gateway)
        next_req = next_peer.encrypt(1, reply["new_state_hash"])
        _, blocked = self.gateway.analyze(next_req.model_dump())
        self.assertEqual(blocked["alert_code"], "STATE_ROLLBACK_DETECTED")
        self.assertEqual(FakeEngine.calls, 1)

    def test_failed_model_does_not_advance_and_exact_retry_succeeds(self):
        peer = Peer(self.gateway)
        req = peer.encrypt()
        FakeEngine.fail = True
        self.assertEqual(self.gateway.analyze(req.model_dump())[0], 502)
        self.assertEqual(self.store.state(req.client_id), (0, "0"*64))
        FakeEngine.fail = False
        self.assertEqual(self.gateway.analyze(req.model_dump())[1]["status"], "OK")

    def test_client_states_do_not_collide(self):
        for client in ("1"*32, "2"*32):
            peer = Peer(self.gateway, client)
            self.assertEqual(self.gateway.analyze(peer.encrypt().model_dump())[1]["new_state_version"], 1)

    def test_session_binding_expiry_capacity_and_request_id_conflict(self):
        peer = Peer(self.gateway)
        req = peer.encrypt()
        bad = req.model_copy(update={"client_id": "2"*32})
        self.assertEqual(self.gateway.analyze(bad.model_dump())[0], 401)
        self.store.sessions[req.session_id].expires = 0
        self.assertEqual(self.gateway.analyze(req.model_dump())[0], 401)
        peer = Peer(self.gateway)
        req = peer.encrypt()
        self.gateway.analyze(req.model_dump())
        req.iv_base64 = b64(b"a"*12)
        self.assertEqual(self.gateway.analyze(req.model_dump())[0], 409)
        self.store.max_sessions = 0
        self.assertEqual(self.gateway.attest(peer.request.model_dump())[0], 429)

    def test_simulations_disabled_by_default(self):
        gateway = SecureGateway(self.store, FakeEngine)
        self.assertEqual(gateway.toggle("rollback")[0], 404)
        self.gateway.toggle("rollback")
        peer = Peer(self.gateway)
        self.assertEqual(self.gateway.analyze(peer.encrypt().model_dump())[1]["alert_code"], "STATE_ROLLBACK_DETECTED")

    def test_authenticated_rejection_is_terminal_and_durable(self):
        self.gateway.toggle("rollback")
        peer = Peer(self.gateway)
        req = peer.encrypt()
        blocked = self.gateway.analyze(req.model_dump())
        self.gateway.toggle("rollback")
        self.assertEqual(self.gateway.analyze(req.model_dump()), blocked)
        self.assertEqual(FakeEngine.calls, 0)
        self.store.close()
        self.store = SecurityStateStore(self.path, self.key)
        self.gateway = SecureGateway(self.store, FakeEngine)
        self.assertEqual(self.gateway.analyze(req.model_dump()), blocked)
        self.assertEqual(self.store.state(req.client_id), (0, "0"*64))

    def test_oversized_encrypted_result_is_rejected_before_state_commit(self):
        class HugeEngine(FakeEngine):
            async def analyze(self, source):
                return SimpleNamespace(work_id=source.work_id, model_dump=lambda: {"unexpected": "大"*100000})
        self.gateway.engine_factory = HugeEngine
        peer = Peer(self.gateway)
        req = peer.encrypt()
        status, reply = self.gateway.analyze(req.model_dump())
        self.assertEqual(status, 200)
        self.assertEqual(reply["alert_code"], "RESULT_TOO_LARGE")
        self.assertEqual(self.store.state(req.client_id), (0, "0"*64))
        peer.decrypt(req, reply)

    def test_existing_http_routes_auth_admin_and_cipher_privacy(self):
        budget = monitor.DailyBudget(":memory:", 10)
        config = Config("https://example.org/v1/chat/completions", "model", "key", "token")
        handler = monitor.make_handler(config, monitor.Options(admin_password="security-console-password"),
                                      budget, secure_gateway=self.gateway)
        http = ThreadingHTTPServer(("127.0.0.1", 0), handler)
        thread = threading.Thread(target=http.serve_forever, daemon=True)
        thread.start()
        def request(path, method="GET", token=None, body=None):
            conn = __import__("http.client", fromlist=["HTTPConnection"]).HTTPConnection("127.0.0.1", http.server_port)
            headers = {"Content-Type": "application/json"}
            if token:
                headers["Authorization"] = "Bearer " + token
            conn.request(method, path, json.dumps(body) if body is not None else None, headers)
            response = conn.getresponse()
            result = response.status, response.read()
            conn.close()
            return result
        try:
            self.assertEqual(request("/health")[0], 200)
            self.assertEqual(request("/api/v1/health")[0], 401)
            self.assertEqual(request("/api/v1/attestation", "POST", body={})[0], 401)
            self.assertEqual(request("/admin/debug/toggle-tamper", "POST")[0], 401)
            login = request("/admin/login", "POST", body={"user": "admin", "password": "security-console-password"})
            self.assertEqual(login[0], 200)
            session_token = json.loads(login[1])["token"]
            self.assertEqual(request("/admin/debug/toggle-tamper", "POST", session_token)[0], 200)
            self.assertEqual(request("/api/v1/health", token="token")[0], 200)
            self.assertNotIn(TEXT.encode(), request("/status", token="token")[1])
        finally:
            http.shutdown(); http.server_close(); budget.close()


class SemanticContractTests(unittest.TestCase):
    def test_single_provider_call_is_schema_checked_and_excludes_private_metadata(self):
        import asyncio
        calls = []
        class FakeClient:
            def __init__(self, **kwargs):
                self.chat = SimpleNamespace(completions=self)
            async def create(self, **kwargs):
                calls.append(kwargs)
                return SimpleNamespace(choices=[SimpleNamespace(finish_reason="stop", message=SimpleNamespace(
                    refusal=None, content=json.dumps(model_result(), ensure_ascii=False)))])
            async def close(self): pass
        source = payload()
        source.author = "sensitive-author"
        source.original_url = "https://example.com/private"
        source.clean_text += " 联系13812345678"
        async def run():
            engine = CognitiveApiEngine()
            try: return await engine.analyze(source)
            finally: await engine.close()
        with patch.dict(os.environ, {"LLM_API_KEY": "fixture", "LLM_RESPONSE_FORMAT": "json_object"}), patch("cognitive_api.AsyncOpenAI", FakeClient):
            result = asyncio.run(run())
        self.assertEqual(result.work_id, source.work_id)
        self.assertEqual(len(calls), 1)
        self.assertEqual(calls[0]["response_format"], {"type": "json_object"})
        sent = calls[0]["messages"][1]["content"]
        for private in (source.author, source.original_url, "13812345678"):
            self.assertNotIn(private, sent)
        self.assertIn("[端侧已脱敏]", sent)

    def test_history_aliases_count_once_and_source_notes_are_record_derived(self):
        source = payload()
        source.history_facts = [HistoryFactItem(fact_id="old", fact_text="企业减少岗位", source_platform="平台", captured_at="2026-11-15")]
        raw = model_result()
        evidence = raw["viewpoints"][0]["evidences"][0]
        evidence.update(is_covered_by_history=True, matched_history_id="old", matched_source_note="14条作品均来自同一报道")
        raw["viewpoints"][0]["evidences"].append({**evidence, "fact_point": "该企业招聘岗位减少"})
        result = validated_result(source, raw, 1)
        self.assertEqual((result.dedup_new_fact_count, result.dedup_total_fact_count), (0, 1))
        self.assertEqual(result.viewpoints[0].evidences[0].matched_source_note, "与历史事实重合（平台 · 2026-11-15）")

    def test_contradictory_history_identity_rejected_and_spaces_are_preserved(self):
        source = payload()
        source.history_facts = [HistoryFactItem(fact_id=key, fact_text="企业减少岗位", source_platform="", captured_at="") for key in ("a", "b")]
        raw = model_result()
        original = raw["viewpoints"][0]["evidences"][0]
        raw["viewpoints"][0]["evidences"] = [{**original, "is_covered_by_history": True, "matched_history_id": key} for key in ("a", "b")]
        with self.assertRaises(ValueError): validated_result(source, raw, 1)
        raw = model_result()
        original = raw["viewpoints"][0]["evidences"][0]
        raw["viewpoints"][0]["evidences"] = [{**original, "fact_point": point} for point in ("A B", " a   b ", "AB")]
        self.assertEqual(validated_result(payload(), raw, 1).dedup_total_fact_count, 2)

    def test_counts_are_distinct_and_exact_not_model_supplied(self):
        result = model_result()
        result["viewpoints"][0]["evidences"] *= 2
        analyzed = validated_result(payload(), result, 1)
        self.assertEqual((analyzed.dedup_new_fact_count, analyzed.dedup_total_fact_count, analyzed.novelty_score), (1, 1, 1))

    def test_hallucinated_guidance_is_downgraded_without_inventing_quotes(self):
        raw = model_result()
        raw["emotional_guidance"]["conclusion_span"] = "原文没有这句话"
        result = validated_result(payload(), raw, 1)
        self.assertFalse(result.emotional_guidance.is_over_extrapolation)
        self.assertFalse(result.emotional_guidance.span_verified)
        self.assertEqual(result.emotional_guidance.conclusion_span, "")

    def test_nonverbatim_evidence_and_unknown_history_are_rejected(self):
        raw = model_result()
        raw["viewpoints"][0]["evidences"][0]["fact_span"] = "企业宣布，大量减少岗位"
        with self.assertRaises(ValueError): validated_result(payload(), raw, 1)
        raw = model_result()
        raw["viewpoints"][0]["evidences"][0].update(is_covered_by_history=True, matched_history_id="invented")
        with self.assertRaises(ValueError): validated_result(payload(), raw, 1)

    def test_expression_and_query_privacy_validation(self):
        raw = model_result()
        raw["expression_spans"]["fact_spans"] = ["invented"]
        with self.assertRaises(ValueError): validated_result(payload(), raw, 1)
        raw = model_result()
        raw["blind_spots"] = [{"dimension": "岗位", "gap_tip": "岗位分布", "sanitized_query": "求职 联系13812345678"}]
        with self.assertRaises(ValueError): validated_result(payload(), raw, 1)


if __name__ == "__main__":
    unittest.main()
