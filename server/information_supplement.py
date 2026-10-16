"""On-demand external reading, kept separate from observed history and fact verdicts."""
from __future__ import annotations

import ipaddress
import re
import time
from urllib.parse import urlsplit, urlunsplit

from emerge_server import ClientError, UpstreamError, text

MAX_BODY = 32 * 1024
TIMEOUT_SECONDS = 40.0


def validate_request(payload: object) -> dict:
    if not isinstance(payload, dict) or type(payload.get("version")) is not int or payload["version"] != 1:
        raise ClientError("Invalid supplement request")
    result = {"version": 1}
    for field, maximum in (("scope_key", 160), ("topic", 80), ("subject", 100),
                           ("dimension", 80), ("conditions", 200)):
        value = payload.get(field, "")
        required = field in {"scope_key", "topic"}
        if not isinstance(value, str) or (value or required) and not text(value, maximum):
            raise ClientError("Invalid supplement request")
        if any(ord(character) < 32 for character in value):
            raise ClientError("Invalid supplement request")
        result[field] = value.strip()
    known = payload.get("known", [])
    if not isinstance(known, list) or len(known) > 6:
        raise ClientError("Invalid supplement request")
    result["known"] = []
    for row in known:
        if not isinstance(row, dict) or not text(row.get("quote"), 500):
            raise ClientError("Invalid supplement request")
        evidence = row.get("evidence", "")
        if not isinstance(evidence, str) or evidence and not text(evidence, 500):
            raise ClientError("Invalid supplement request")
        result["known"].append({"quote": row["quote"], "evidence": evidence})
    return result


def canonical_source_url(value: object) -> str | None:
    if not isinstance(value, str) or len(value) > 2000 or any(ord(c) < 33 for c in value):
        return None
    try:
        parsed = urlsplit(value)
        host = (parsed.hostname or "").lower().rstrip(".")
        if parsed.scheme != "https" or not host or ":" in host or parsed.username or parsed.password:
            return None
        if host == "localhost" or host.endswith((".localhost", ".local", ".internal")):
            return None
        try:
            address = ipaddress.ip_address(host)
            if not address.is_global or address.is_multicast or address.is_reserved:
                return None
        except ValueError:
            if "." not in host or all(re.fullmatch(r"(?:\d+|0x[0-9a-f]+)", part) for part in host.split(".")):
                return None
        if parsed.port not in (None, 443):
            return None
        # Fragments identify positions inside one material, not independent sources.
        authority = "[" + host + "]" if ":" in host else host
        return urlunsplit(("https", authority, parsed.path or "/", parsed.query, ""))
    except ValueError:
        return None


def _quote(body: str, source: dict) -> str:
    # Return existing paragraphs, not model-written summaries or snippets stitched together.
    # Long unbroken paragraphs are omitted rather than cutting their qualifications away.
    paragraphs = [part.strip() for part in re.split(r"\n\s*\n", body) if part.strip()]
    known = [row[key] for row in source["known"] for key in ("quote", "evidence") if row[key]]
    terms = [source[key] for key in ("topic", "subject", "dimension", "conditions") if source[key]]
    candidates = [part for part in paragraphs if len(part) <= 1000 and not any(part in item for item in known)]
    # A provider caps its body at 6000 characters. Never quote its possibly cut final paragraph.
    if len(body) >= 6000 and paragraphs:
        candidates = [part for part in candidates if part != paragraphs[-1]]
    return max(candidates, key=lambda part: sum(term in part for term in terms), default="")


def retrieve(source: dict, providers) -> dict:
    if not providers.retrieval_available():
        raise UpstreamError("Retrieval is not configured", 503)
    deadline = time.monotonic() + TIMEOUT_SECONDS
    # Known quotes are untrusted observed content. They are only used for deduplication,
    # never copied into a model instruction or transformed into an opposing-position query.
    query = " ".join(source[key] for key in ("topic", "subject", "dimension", "conditions") if source[key])
    query += " 原始资料 研究方法 适用条件"
    rows = providers.search(query, deadline)
    if time.monotonic() > deadline:
        raise UpstreamError("Retrieval timed out", 504)
    if not isinstance(rows, list):
        raise UpstreamError("Invalid retrieval response")
    sources, urls, bodies = [], set(), set()
    for row in rows[:10]:
        if not isinstance(row, dict):
            continue
        url = canonical_source_url(row.get("url"))
        body = row.get("excerpt")
        if not url or url in urls or not text(body, 6000) or body in bodies:
            continue
        quote = _quote(body, source)
        if not quote or quote not in body:
            continue
        title = row.get("title", "")
        if not isinstance(title, str) or not text(title, 200):
            title = urlsplit(url).hostname or url
        urls.add(url)
        bodies.add(body)
        sources.append({"url": url, "title": title, "quote": quote, "excerpt": body})
        if len(sources) == 4:
            break
    return {"version": 1, "scope_key": source["scope_key"], "topic": source["topic"],
            "source_kind": "external", "status": "ready" if sources else "empty", "sources": sources}
