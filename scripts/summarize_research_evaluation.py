"""Score only held-out target items, never the easy history/context rows."""
import argparse
from collections import Counter, defaultdict
import csv
import hashlib
import json
from pathlib import Path

LABELS = ["FIRST", "NEW", "REPEATED", "CONDITIONS", "UNKNOWN", "NO_CLAIM"]


def load_rows(path):
    return [json.loads(line) for line in path.read_text(encoding="utf8").splitlines()]


def score(pairs):
    matrix = [[0] * len(LABELS) for _ in LABELS]
    for expected, prediction in pairs:
        matrix[LABELS.index(expected)][LABELS.index(prediction)] += 1
    classes = []
    for i, name in enumerate(LABELS):
        support, predicted, true = sum(matrix[i]), sum(row[i] for row in matrix), matrix[i][i]
        classes.append(dict(label=name, support=support, predicted=predicted, true_positive=true,
                            precision=true / predicted if predicted else None, recall=true / support if support else None,
                            f1=2 * true / (support + predicted) if support + predicted else None))
    total, correct = len(pairs), sum(matrix[i][i] for i in range(len(LABELS)))
    supported = [row["f1"] for row in classes if row["support"]]
    return dict(total=total, correct=correct, accuracy=correct / total if total else None,
                macro_f1=sum(supported) / len(supported) if supported else None,
                labels=LABELS, confusion_matrix_rows_expected_columns_predicted=matrix, per_class=classes)


def target_rows(rows, gold):
    targets = [row for row in rows if row["sample_id"].endswith(".target")]
    ids = [row["sample_id"][:-7] for row in targets]
    if len(set(ids)) != len(ids) or set(ids) != set(gold):
        raise ValueError("Missing, repeated, or extra test target predictions")
    for row in targets:
        if row["expected"] != gold[row["sample_id"][:-7]]["expected_legacy_increment"]:
            raise ValueError("Prediction file belongs to different frozen labels")
    return targets


def summarize(dataset, results):
    labels = load_rows(dataset / "labels/increment_labels.jsonl")
    gold = {r["id"]: r for r in labels if r["split"] == "test"}
    with (results / "history/predictions.tsv").open(encoding="utf8", newline="") as stream:
        predictions = list(csv.DictReader(stream, delimiter="\t"))
    targets = target_rows(predictions, gold)
    pairs = [(gold[r["sample_id"][:-7]]["expected_legacy_increment"], r["predicted"]) for r in targets]
    majority = Counter(r["expected_legacy_increment"] for r in labels if r["split"] == "train").most_common(1)[0][0]
    by_stratum = defaultdict(list)
    errors = []
    for row, (expected, predicted) in zip(targets, pairs):
        stratum = gold[row["sample_id"][:-7]]["stratum"]
        by_stratum[stratum].append((expected, predicted))
        if expected != predicted:
            errors.append(dict(id=row["sample_id"][:-7], stratum=stratum, expected=expected, predicted=predicted))
    profiles = load_rows(dataset / "labels/profiles.jsonl")
    pi = {p["id"]: p for p in profiles}
    works = {w["id"]: w for w in load_rows(dataset / "inputs/works.jsonl")}
    emotion_ids = {e["work_id"] for e in load_rows(dataset / "labels/emotions.jsonl") if e["expression"] == "焦虑" and e["attribution"] in ("affirmed", "reported")}
    per_day = defaultdict(list)
    for c in load_rows(dataset / "inputs/contacts.jsonl"):
        per_day[c["day_id"]].append(c)
    arms = defaultdict(list)
    for contacts in per_day.values():
        p = pi[contacts[0]["profile_id"]]
        family = Counter(works[c["work_id"]]["semantic_family"] for c in contacts)
        arms[p["concentration_condition"] + "/" + p["anxiety_condition"]].append(dict(
            dominant_family_share=max(family.values()) / len(contacts),
            anxious_content_share=sum(c["work_id"] in emotion_ids for c in contacts) / len(contacts)))
    design = {key: dict(observed_user_days=len(rows),
                       mean_dominant_family_share=sum(r["dominant_family_share"] for r in rows) / len(rows),
                       mean_anxious_content_share=sum(r["anxious_content_share"] for r in rows) / len(rows)) for key, rows in arms.items()}
    trace = json.loads((results / "trace-summary.json").read_text(encoding="utf8"))
    root = Path(__file__).resolve().parents[1]
    trace_sources = ["app/src/main/java/com/emerge/app/capture/CaptureSessionTracker.kt",
                     "app/src/main/java/com/emerge/app/capture/WorkIdentity.kt", "app/src/main/java/com/emerge/app/capture/ContentContinuity.kt",
                     "app/src/main/java/com/emerge/app/capture/VisibilityIntervals.kt", "app/src/main/java/com/emerge/app/ui/BrowseStatistics.kt",
                     "app/src/main/java/com/emerge/app/analysis/ObservationWindow.kt",
                     "evaluation/kotlin/com/emerge/app/evaluation/SyntheticTraceEvaluator.kt"]
    summary = dict(scope="controlled_synthetic_downstream_history_with_supplied_structured_fields",
                   annotation_status="AI_authored_unreviewed", test_target_items=len(targets),
                   excluded_history_rows=len(predictions) - len(targets), independent_test_source_cases=126,
                   held_out_semantic_families=12, target_metrics=score(pairs), by_stratum={k: score(v) for k, v in sorted(by_stratum.items())},
                   majority_baseline=dict(fitted_on="train_labels", label=majority, metrics=score([(a, majority) for a, _ in pairs])),
                   always_first_baseline=score([(a, "FIRST") for a, _ in pairs]), errors=errors,
                   longitudinal_design_check_not_model_performance=design, trace_metrics=trace,
                   unmeasured=["image/audio understanding", "five-dimensional model predictions", "emotion extraction and attribution model",
                               "human inter-annotator agreement", "real-person effects", "120 device safety fixtures"],
                   dataset_manifest_sha256=hashlib.sha256((dataset / "manifest.json").read_bytes()).hexdigest(),
                   prediction_sha256=hashlib.sha256((results / "history/predictions.tsv").read_bytes()).hexdigest(),
                   trace_prediction_sha256=hashlib.sha256((results / "trace-predictions.jsonl").read_bytes()).hexdigest(),
                   trace_source_sha256={name: hashlib.sha256((root / name).read_bytes()).hexdigest() for name in trace_sources})
    (results / "target-summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf8", newline="\n")
    metrics = summary["target_metrics"]
    report = ["# 受控合成实验：实际运行结果", "", "全部材料为 AI 编写的合成内容，标注未经人类独立复核。以下只测给定结构化字段后的历史判断，不测端到端模型理解。", "",
              f"测试目标 {len(targets)} 项，排除历史上下文 {len(predictions)-len(targets)} 行。目标依赖 126 个源案例、12 个留出语义家族，不能把每行当成独立自然样本。", "",
              f"正确 {metrics['correct']} / {metrics['total']}，准确率 {metrics['accuracy']:.2%}，有支持类别 macro-F1 {metrics['macro_f1']:.4f}。",
              f"训练集多数类基线（{majority}）准确率 {summary['majority_baseline']['metrics']['accuracy']:.2%}。", "",
              "| 场景 | 目标数 | 正确数 | 准确率 |", "| --- | ---: | ---: | ---: |"]
    for kind, result in summary["by_stratum"].items():
        report.append(f"| {kind} | {result['total']} | {result['correct']} | {result['accuracy']:.2%} |")
    report += ["", "## 轨迹回放", "", f"实际 CaptureSessionTracker 回放 {trace['traces']} 条合成轨迹、{trace['samples']} 次采样：观看边界错误 {trace['boundary_errors']}，身份配对错误 {trace['identity_errors']}/{trace['identity_pairs']}，作品数错误轨迹 {trace['distinct_count_error_traces']}，停留 MAE {trace['duration_mae_ms']} ms。",
               "输入直接提供页面提示与精确时钟；这些数值不能代替真实页面抽取、截图时序或手机端可见时长误差。120 个安全场景仅为规格，未执行设备测试。", "",
               "## 解释边界", "", "保留所有错误，不按预测修改预设标签。本次实验不产生人工一致性 κ、情绪模型 F1、五维增量预测分数或真实用户效果。完整混淆矩阵和错误 ID 在 target-summary.json；逐条预测在 history/predictions.tsv。",
               "现有 history/report.md 的整体分母包含上下文行，本实验应引用本报告的 target-only 结果。"]
    (results / "RESULTS.md").write_text("\n".join(report) + "\n", encoding="utf8", newline="\n")
    return summary


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dataset", type=Path)
    parser.add_argument("results", type=Path)
    args = parser.parse_args()
    result = summarize(args.dataset, args.results)
    print(json.dumps({"metrics": result["target_metrics"], "strata": {k: [v["correct"], v["total"]] for k, v in result["by_stratum"].items()}}, ensure_ascii=False, indent=2))
