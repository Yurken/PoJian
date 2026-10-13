"""Bounded, evidence-linked optional UI annotations; never a diagnosis of a viewer."""
CATEGORIES = {"教育", "生活", "求职", "就业", "体育", "健康", "消费", "科技", "家庭", "公共议题", "其他"}
LEVELS = {"unknown", "low", "medium", "high"}


def validate_presentation(value, available):
    def string(value, limit):
        return isinstance(value, str) and bool(value.strip()) and len(value) <= limit
    if not isinstance(value, dict) or value.get("category") not in CATEGORIES:
        raise ValueError("Invalid presentation category")
    if not string(value.get("viewpoint"), 48) or value["viewpoint"] in CATEGORIES:
        raise ValueError("Invalid concise viewpoint")
    clean = {"category": value["category"], "viewpoint": value["viewpoint"]}
    for key in ("anxiety", "cocoon", "cognition"):
        signal = value.get(key)
        if not isinstance(signal, dict) or signal.get("level") not in LEVELS or not string(signal.get("reason"), 240):
            raise ValueError("Invalid impact signal")
        evidence = signal.get("evidence")
        if not isinstance(evidence, str) or len(evidence) > 300 or (evidence and evidence not in available):
            raise ValueError("Impact evidence does not match observed content")
        if signal["level"] != "unknown" and not evidence.strip():
            raise ValueError("Rated impact requires observed evidence")
        clean[key] = {field: signal[field] for field in ("level", "reason", "evidence")}
    suggestions = value.get("suggestions")
    if not isinstance(suggestions, list) or not 1 <= len(suggestions) <= 3 or any(not string(v, 120) for v in suggestions):
        raise ValueError("Invalid impact suggestions")
    clean["suggestions"] = list(suggestions)
    return clean
