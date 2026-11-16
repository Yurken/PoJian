from typing import Annotated, Literal
from pydantic import BaseModel, ConfigDict, Field

Hex32 = Annotated[str, Field(pattern=r"^[0-9a-f]{32}$")]
Hex64 = Annotated[str, Field(pattern=r"^[0-9a-f]{64}$")]


class Contract(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)


class AttestationRequest(Contract):
    client_id: Hex32
    client_nonce: Hex64
    client_ephemeral_pubkey: str = Field(min_length=44, max_length=44)


class AttestationResponse(Contract):
    session_id: Hex32
    server_ephemeral_pubkey: str
    code_measurement_hash: Hex64
    quote_signature: str
    enclave_mode: Literal["SIGNED_APPLICATION_V2"] = "SIGNED_APPLICATION_V2"
    timestamp_ms: int


class EncryptedAnalyzeRequest(Contract):
    session_id: Hex32
    client_id: Hex32
    request_id: Hex32
    client_ephemeral_pubkey: str = Field(min_length=44, max_length=44)
    iv_base64: str = Field(min_length=16, max_length=16)
    ciphertext_base64: str = Field(min_length=24, max_length=160000)
    expected_state_version: int = Field(ge=0, le=2**63-2)
    expected_state_hash: Hex64


class EncryptedAnalyzeResponse(Contract):
    status: Literal["OK", "SECURITY_BLOCKED"]
    alert_code: str | None = None
    new_state_version: int
    new_state_hash: Hex64
    iv_base64: str
    ciphertext_base64: str


class HistoryFactItem(Contract):
    fact_id: str = Field(min_length=1, max_length=256)
    fact_text: str = Field(min_length=1, max_length=1000)
    source_platform: str = Field(max_length=120)
    captured_at: str = Field(max_length=80)


class PlainAnalyzePayload(Contract):
    work_id: str = Field(min_length=1, max_length=256)
    platform: str = Field(max_length=120)
    title: str = Field(max_length=500)
    author: str = Field(max_length=200)
    clean_text: str = Field(min_length=1, max_length=16000)
    original_url: str | None = Field(default=None, max_length=2000)
    dwell_seconds: int = Field(ge=0, le=86400)
    topic_hint: str | None = Field(default=None, max_length=120)
    history_facts: list[HistoryFactItem] = Field(default_factory=list, max_length=15)


class ExtractedEvidence(Contract):
    fact_point: str = Field(min_length=1, max_length=1000)
    fact_span: str = Field(min_length=1, max_length=1000)
    is_covered_by_history: bool
    matched_history_id: str | None = Field(default=None, max_length=256)
    matched_source_note: str | None = Field(default=None, max_length=500)


class ExtractedViewpoint(Contract):
    stance_summary: str = Field(min_length=1, max_length=120)
    stance_span: str | None = Field(default=None, max_length=1000)
    author_attitude: Literal["支持", "反驳", "中立"]
    evidences: list[ExtractedEvidence] = Field(default_factory=list, max_length=12)


class EmotionalGuidanceResult(Contract):
    is_over_extrapolation: bool
    fact_span: str = Field(default="", max_length=1000)
    conclusion_span: str = Field(default="", max_length=1000)
    scope_extrapolation: str = Field(default="", max_length=500)
    certainty_upgrade: str = Field(default="", max_length=500)
    emotion_amplification: str = Field(default="", max_length=500)
    span_verified: bool = True


class InfoBlindSpotItem(Contract):
    dimension: str = Field(min_length=1, max_length=80)
    gap_tip: str = Field(min_length=1, max_length=500)
    sanitized_query: str = Field(min_length=1, max_length=200)


class ExpressionSpans(Contract):
    fact_spans: list[Annotated[str, Field(min_length=1, max_length=1000)]] = Field(max_length=12)
    emotion_spans: list[Annotated[str, Field(min_length=1, max_length=1000)]] = Field(max_length=12)


class ModelAnalysis(Contract):
    topic_name: str = Field(min_length=1, max_length=80)
    is_new_topic: bool
    viewpoints: list[ExtractedViewpoint] = Field(max_length=3)
    emotional_guidance: EmotionalGuidanceResult
    blind_spots: list[InfoBlindSpotItem] = Field(max_length=4)
    expression_spans: ExpressionSpans | None = None


class SemanticAnalysisResult(ModelAnalysis):
    work_id: str
    dedup_new_fact_count: int = Field(ge=0, le=36)
    dedup_total_fact_count: int = Field(ge=0, le=36)
    novelty_score: float = Field(ge=0, le=1)
    processing_ms: int = Field(ge=0)
