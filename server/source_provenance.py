"""Bounded provenance in retained evidence, never an external truth certificate."""
import re

PRIMARY_COLLECTION = r"(?:独立|自行|自主)(?:开展|进行|完成)?(?:调查|采样|采集|采访|测量|实验|实测|统计)|一手采访|现场实测"
UNRESOLVED = re.compile(r"不|未|没有|并非|否认|据说|网传|声称|自称|可能|如果|假如|假设|计划|拟|将|预计|转引|转载|引用|援引|基于")
ANONYMOUS = re.compile(r"^(?:我|我们|本人|作者|博主|记者|本机构|本团队|该机构|该团队)$|^(?:某|匿名|一位|一名)")


def provenance_context(evidence, available):
    boundaries = "。！？；;\n"
    pieces, offset = [], 0
    while evidence and (start := available.find(evidence, offset)) >= 0:
        end = start + len(evidence)
        left = max(available.rfind(boundary, 0, start) for boundary in boundaries) + 1
        right = min((position for boundary in boundaries
                     if (position := available.find(boundary, end)) >= 0), default=len(available))
        pieces.append(available[left:right])
        offset = end
    return "\n".join(pieces) or evidence


def validate_provenance(value, evidence, available=None):
    if not isinstance(value, dict) or type(value.get("complete")) is not bool:
        raise ValueError("Invalid source provenance")
    sources = value.get("sources")
    if not isinstance(sources, list) or len(sources) > 4 or (value["complete"] and not sources):
        raise ValueError("Invalid source provenance count")
    clean, seen = [], set()
    context = provenance_context(evidence, available if available is not None else evidence)
    for row in sources:
        if not isinstance(row, dict):
            raise ValueError("Invalid source provenance entry")
        basis, producer, material = (row.get(key) for key in ("basis", "producer", "material"))
        if not isinstance(basis, str) or not basis.strip() or len(basis) > 500 or basis not in evidence:
            raise ValueError("Source provenance quotation does not match evidence")
        if any(not isinstance(anchor, str) or not anchor.strip() or len(anchor) > 80 or anchor not in basis
               for anchor in (producer, material)):
            raise ValueError("Source provenance identity does not match quotation")
        kind, upstream = row.get("kind"), row.get("upstream")
        if kind not in {"primary", "secondary", "unknown"} or not isinstance(upstream, list) or len(upstream) > 4:
            raise ValueError("Invalid source provenance relationship")
        if (any(not isinstance(anchor, str) or not anchor.strip() or len(anchor) > 80 or anchor not in basis
                for anchor in upstream) or len(set(upstream)) != len(upstream)):
            raise ValueError("Invalid upstream provenance anchor")
        if kind == "primary" and (upstream or UNRESOLVED.search(context) or ANONYMOUS.search(producer) or
                                  not re.search(re.escape(producer) + r"[\s，,：:]*(?:已|曾)?(?:开展|进行|完成)?(?:" + PRIMARY_COLLECTION + ")", basis)):
            raise ValueError("Primary provenance requires explicit original collection")
        if kind == "secondary" and not upstream:
            raise ValueError("Secondary provenance requires an upstream material")
        identity = (producer, material)
        if identity in seen:
            raise ValueError("Duplicate source provenance")
        seen.add(identity)
        clean.append(dict(producer=producer, material=material, kind=kind, basis=basis, upstream=list(upstream)))
    return dict(complete=value["complete"], sources=clean)
