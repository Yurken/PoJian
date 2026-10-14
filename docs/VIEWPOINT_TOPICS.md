# 主题观点卡片与分析数据

当前 App 已移除内置合成数据库及入口。本文中的实验界面描述属于历史实现；生成、投影和回归能力仍保留在开发工具与 JVM 测试源码集中，见[开发用实验数据库](PROJECT_RESEARCH_DATABASE.md)。真实浏览数据链路保持不变。

本轮基准为 `ff56d14a6cdbc55abf43508d3461509e0e14cbc9`，独立分支 `codex/work-identity-thumbnails`。主分支 `a066f09` 已在基准历史中。版本 `0.5.22` / `44`，未修改签名、applicationId、更新分发或采集权限。

## 数据现状与补充

| 数据库 | 目前内容 | 本轮变化 |
| --- | --- | --- |
| App 个人记录：Room 2.6.1 管理的 SQLite，`zhiheng.db`，schema 8 | 作品、观看事件、采样、有效可见区间、原文、结构化观点、音频转写、持久任务和缩略图关联 | 在 `browse_semantics.claimsJson` 内增加可选 `presentation`；表结构及旧记录不重写，原迁移链保留 |
| 独立只读实验库：`pojian-research-v2.sqlite`，schema 2 | 8,400 作品、25,200 观察版本、200 虚拟用户、2,800 用户日、33,360 次观看，以及原论证/情绪/增量/回归材料 | 新增 `claim_presentations`，25,200 条版本级展示分析；14 张来源表的原始 payload 逐行保持不变 |

没有连接用户手机查询个人库，不能据此报告已安装 App 的真实记录数量。实验数据全为受控合成材料，0 名真实参与者；无真实平台媒体和封面。

旧结构有 `quote/topic/subject/dimension/stance/conditions/evidence`，足以展示原文和论证，但没有统一大主题、独立短观点、三类内容潜在影响及建议。旧列表直接展示作品文字，结构化卡片还把维度、条件和立场展开，造成视觉冗长。本轮新增字段解决展示缺口，不将主题相同的作品合并，也不以隐藏文字改变统计。

新字段结构如下（等级取 `unknown/low/medium/high`）：

```json
{
  "category": "求职",
  "viewpoint": "岗位要求相近时准备项目",
  "anxiety": {"level": "unknown", "reason": "当前片段没有足够表达依据。", "evidence": ""},
  "cocoon": {"level": "unknown", "reason": "不能由单个片段判断长期接触情况。", "evidence": ""},
  "cognition": {"level": "unknown", "reason": "需要核对原文适用条件。", "evidence": ""},
  "suggestions": ["比较不同岗位的要求和适用条件。"]
}
```

这是协议示例，不是模型实测结果。主题枚举为教育、生活、求职、就业、体育、健康、消费、科技、家庭、公共议题、其他。短观点最多 48 字且不能只是领域名；每维必须有简短理由，已评级维度必须附当前输入内的连续原文证据；建议 1–3 条。客户端与服务端分别校验类型、长度和引文归属。批量请求的扩展证据若来自另一作品，保留原观点、舍弃无法绑定的展示分析。

真实链路为网关抽取 → `MonitorRuntime` → `MonitorInputs` 绑定输入 → `EventSemantics` → `SemanticsCodec` → Room。旧网关和历史 JSON 缺字段时仍兼容；展示已有大模型的立场/条件/证据，并标明影响分析未生成。没有为历史个人记录伪造结果或自动触发重新上传。新在线字段需要部署包含 `claim_presentation.py` 的服务端并取得新的分析响应；本轮未部署、未调用真实模型。

实验链路为原观察版本及当时情绪片段 → `research_presentations.py` → `claim_presentations` → `project_contacts` → `ResearchProjection`。每条补充记录带 `AI_authored_rules_unreviewed` 来源，界面显示「AI 合成分析（未人工复核）」，不能作为在线模型准确率结果。投影固定观看时的版本，检查作品、版本、来源与可用时间；不读取未来版本、增量金标、用户预设组或虚拟自报来决定卡片等级。作品库仅用于语料浏览，仍明确展示第三观察版本。

## 展示与排序

- 点击柱子后展示「大主题 → 简短观点卡片」，保留圆角卡片风格、真实缩略图能力和平台；正文、论证及建议移到点击后的详情。
- 每个去重作品在主列表只出现一次。多观点作品归入占比最高的主主题，保留最多三条短观点；完整观点及多次观看原文在详情中保留。相同语义键的分析修订使用最近 `analyzedAt`，不重复贡献卡片。
- 主题按窗口内首次遇见时间排序，主题内作品按首次有效可见时间从早到晚排序，同时间以稳定作品身份排序。再次观看合入作品卡，时长取本窗口有效可见区间并集。
- 卡片可整块点击，红黄绿同时有「影响提示高/中/低」标签与无障碍状态；未知为灰色。详情区分真实大模型、合成分析与本地识别。
- 选中柱子时不再重复追加全时段观点分布；「全部观点」返回既有分布功能。原吸顶图表、滚动时隐藏底部导航和报告预览继续保留。柱状图原有低饱和实色绿/黄/红仍按数量映射，和观点卡的影响提示口径不同。

## 颜色计算边界

卡片颜色是可解释的工程提示强度，不是心理诊断、事实真假或经过校准的风险概率。取非不确定观点的三维内容信号，结合该窗口有效停留时长：

| 条件 | 颜色 |
| --- | --- |
| 有高信号且停留至少 60 秒，或有中信号且停留至少 180 秒 | 红 |
| 其余有中/高信号 | 黄 |
| 全部观点三维均有低信号，且时长已知且大于零 | 绿 |
| 缺少分析/时长、仅不确定观点或没有足够已知信号 | 灰 |

已知高/中信号可提示关注，其他未知维度仍在详情标为未评估。时长本身不能把完整低信号变成高影响。60/180 秒是未校准的工程阈值；需要人工标注和真实用户研究才能评价其效度。不能仅因题材、作者、价值选择或出现「焦虑」一词判高；提示词要求保留反驳、否定、条件及情绪持有者。

## 验证与复现

先按[实验数据库说明](PROJECT_RESEARCH_DATABASE.md)构建和审计数据库，再运行：

```powershell
python -m unittest discover -s scripts/tests -p test_project_research_database.py -v
python -m unittest discover -s scripts/tests -p test_research_presentations.py -v
python -m unittest discover -s server/tests -v
./gradlew.bat :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug :app:evaluateResearchDatabase
```

最终结果见[实现对照的本轮验证](IMPLEMENTATION.md#验证状态)和 `app/build/reports/project-research-database/`。新回归覆盖旧/新 JSON、非法等级与错属引文、修订取最新、重复观看、同观点不同作品、按时间排序、阈值边界、未评估、未来版本和来源伪造。SQLite 测试额外验证展示分析不能关联错误作品或假冒在线来源。

设备测试新增「主题短卡 → 原文与分析」、扩充选柱后分布不重复和 Room 重开保留新字段；仅编译，未启动模拟器或真机。真实布局、大字号、长文滚动、截图关联及在线模型语义正确性仍未实测。固定签名配置缺失，本机不能生成已确认可覆盖升级的 APK；没有改用临时签名。

## 修改文件索引

| 作用 | 文件 |
| --- | --- |
| 字段与等级计算 | `analysis/ClaimPresentation.kt`、`WorkTopicPresentation.kt`、`StructuredSemantics.kt`、`SemanticsCodec.kt` |
| 作品归并与顺序 | `analysis/ViewpointTimeline.kt`、`ViewpointDistribution.kt` |
| 观点界面与详情 | `ui/BrowseWorkCards.kt`、`ViewpointTimelineCard.kt`、`ViewpointDistributionCard.kt` |
| 在线结果归属 | `monitoring/MonitorRuntime.kt`、`MonitorInputs.kt`、`server/monitor_server.py`、`server/claim_presentation.py` |
| 开发用实验数据 | `evaluation/kotlin/com/emerge/app/data/ResearchProjection.kt`、`evaluation/schemas/research-schema.sql`、`scripts/research_presentations.py`、`build_project_research_database.py`、`audit_project_research_database.py` |
| 回归与评测 | `ClaimPresentationTest`、`ResearchProjectionTest`、`TopicWorkCardsTest`、`FunctionalMinimalUiTest`、`SemanticsPersistenceTest`、Python 对应测试及 `evaluation/.../ResearchDatabaseEvaluator.kt` |

完整精确清单可从本轮提交的 `git show --stat` 获取；App 文件位于 `app/src/`，开发评测与测试辅助代码位于 `evaluation/`。
