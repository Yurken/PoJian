# 结构观点与个人历史离线评测

数据集：`history-synthetic-v1`；性质：`synthetic`；标注状态：`single_editor_unadjudicated`。

所有内容和序列均为手工构造的合成诊断样本。结果不能代表真实平台准确率、用户风险率或云端模型抽取准确率。
本入口固定提供结构字段，评测实际 CognitiveAnalyzer 的历史比较与提醒规则。UNKNOWN 和无观点均保留在完整混淆矩阵与总体分母中，未按预测成功情况删样本。

## 信息增量

样本 112；整体准确率 83.93%；有金标类别的 macro-F1 0.8016。

| 类别 | 金标数 | 预测数 | precision | recall | F1 |
| --- | ---: | ---: | ---: | ---: | ---: |
| FIRST | 26 | 26 | 0.9615 | 0.9615 | 0.9615 |
| NEW | 37 | 30 | 0.8667 | 0.7027 | 0.7761 |
| REPEATED | 30 | 35 | 0.6857 | 0.8000 | 0.7385 |
| CONDITIONS | 1 | 2 | 0.5000 | 1.0000 | 0.6667 |
| UNKNOWN | 17 | 17 | 1.0000 | 1.0000 | 1.0000 |
| NO_CLAIM | 1 | 2 | 0.5000 | 1.0000 | 0.6667 |

混淆矩阵：行是独立金标，列是预测。

| 金标\预测 | FIRST | NEW | REPEATED | CONDITIONS | UNKNOWN | NO_CLAIM |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| FIRST | 25 | 0 | 0 | 0 | 0 | 1 |
| NEW | 0 | 26 | 11 | 0 | 0 | 0 |
| REPEATED | 1 | 4 | 24 | 1 | 0 | 0 |
| CONDITIONS | 0 | 0 | 0 | 1 | 0 | 0 |
| UNKNOWN | 0 | 0 | 0 | 0 | 17 | 0 |
| NO_CLAIM | 0 | 0 | 0 | 0 | 0 | 1 |

## 窗口提醒

样本 6；整体准确率 83.33%；有金标类别的 macro-F1 0.8222。

| 类别 | 金标数 | 预测数 | precision | recall | F1 |
| --- | ---: | ---: | ---: | ---: | ---: |
| ALERT | 1 | 2 | 0.5000 | 1.0000 | 0.6667 |
| NO_ALERT | 3 | 2 | 1.0000 | 0.6667 | 0.8000 |
| INSUFFICIENT | 2 | 2 | 1.0000 | 1.0000 | 1.0000 |

混淆矩阵：行是独立金标，列是预测。

| 金标\预测 | ALERT | NO_ALERT | INSUFFICIENT |
| --- | ---: | ---: | ---: |
| ALERT | 1 | 0 | 0 |
| NO_ALERT | 1 | 2 | 0 |
| INSUFFICIENT | 0 | 0 | 2 |

## 覆盖与误提醒

结构结果覆盖 109/112 个事件（97.32%），本地兜底 3 个。此处表示提供结果的覆盖，不证明观点抽取完整。
增量可判断 93/112（83.04%）；UNKNOWN 17，未提取到目标观点 2。仅可判断项准确率 81.72%须与完整总体准确率一起阅读。
负类窗口误提醒 1/3（33.33%）；证据不足窗口仍提醒 0 个；正类窗口漏提醒 0 个。窗口可判断覆盖 66.67%。

## 错误样本

- `evidence_paraphrase.02`（evidence_paraphrase）：REPEATED → NEW。仍为报告甲、同一公司、本月20个名额，只将数字改写为中文。
- `evidence_paraphrase.03`（evidence_paraphrase）：REPEATED → NEW。名额为二十和只能招20人是同一事实，未增加独立材料。
- `stance_paraphrase.02`（stance_paraphrase）：REPEATED → FIRST。规范化立场使用同义措辞，原观点和支撑材料均没有变化。
- `condition_paraphrase.02`（condition_paraphrase）：REPEATED → CONDITIONS。本季度与当前季度指向同一时间范围，不构成范围扩展。
- `evidence_reordered.02`（reordered_evidence）：REPEATED → NEW。相同城市与数字仅调整排列顺序；联合信息没有增加。
- `union_coverage.03`（historical_union）：REPEATED → NEW。当前两条事实分别被前两条历史覆盖，合并复述不产生新事实。
- `local_uncovered.01`（uncovered_topic）：FIRST → NO_CLAIM。已观察文本包含明确水质判断；词表未覆盖也应作为提取缺失计入完整分母。
- `window_distinct_cases.02`（similar_wording_distinct_facts）：NEW → REPEATED。明示不同当事人的独立个人记录，句式相近不代表同一个事实；每条新增一个个体案例。
- `window_distinct_cases.03`（similar_wording_distinct_facts）：NEW → REPEATED。明示不同当事人的独立个人记录，句式相近不代表同一个事实；每条新增一个个体案例。
- `window_distinct_cases.04`（similar_wording_distinct_facts）：NEW → REPEATED。明示不同当事人的独立个人记录，句式相近不代表同一个事实；每条新增一个个体案例。
- `window_distinct_cases.05`（similar_wording_distinct_facts）：NEW → REPEATED。明示不同当事人的独立个人记录，句式相近不代表同一个事实；每条新增一个个体案例。
- `window_distinct_cases.06`（similar_wording_distinct_facts）：NEW → REPEATED。明示不同当事人的独立个人记录，句式相近不代表同一个事实；每条新增一个个体案例。
- `window_distinct_cases.07`（similar_wording_distinct_facts）：NEW → REPEATED。明示不同当事人的独立个人记录，句式相近不代表同一个事实；每条新增一个个体案例。
- `window_distinct_cases.08`（similar_wording_distinct_facts）：NEW → REPEATED。明示不同当事人的独立个人记录，句式相近不代表同一个事实；每条新增一个个体案例。
- `window_distinct_cases.09`（similar_wording_distinct_facts）：NEW → REPEATED。明示不同当事人的独立个人记录，句式相近不代表同一个事实；每条新增一个个体案例。
- `window_distinct_cases.10`（similar_wording_distinct_facts）：NEW → REPEATED。明示不同当事人的独立个人记录，句式相近不代表同一个事实；每条新增一个个体案例。
- `window_distinct_cases.11`（similar_wording_distinct_facts）：NEW → REPEATED。明示不同当事人的独立个人记录，句式相近不代表同一个事实；每条新增一个个体案例。
- `window_distinct_cases.12`（similar_wording_distinct_facts）：NEW → REPEATED。明示不同当事人的独立个人记录，句式相近不代表同一个事实；每条新增一个个体案例。
- `window_distinct_cases`（similar_wording_distinct_facts）：NO_ALERT → ALERT。材料明确来自12位不同求职者，持续补充个体事实。相似句式不能自动等同同源复述；个体案例也不证明行业全貌。

机器结果、完整预测及逐项理由见同目录 summary.json、predictions.tsv、window-predictions.tsv。数据与源码 SHA-256 记录在 JSON 中；重复运行不会写入当前时间，以便逐字比较。真实标注与双人复核步骤见 docs/OFFLINE_QUALITY_EVALUATION.md。
