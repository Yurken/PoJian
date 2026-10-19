# 报告文档结构重构

用户明确要求按 HTML 的组织方式重构，上一版的单列长报告换色不满足要求。

## 目标

首页采用报告元信息、目的栏、四格摘要、1.15:0.85 双栏概览；正文采用主题内并排观点卡片、原文/条件/依据/历史来源层次及情绪原文与诱因。详细统计、逐小时记录、近7天历史观察、原始观点和方法放在附录。PDF 中不复制网页的交互按钮、示例数据、未经证实的安全状态或过度推断诊断。可见字段使用已有名称或结构必需的最短标签。

## 实现顺序与接口

1. `ReportPdfDocument` 定义文档、摘要、章节、行、卡片及观测比例模型。`ReportPdfDocumentBuilder.build()` 从 `CognitiveReport` 构造文档；保留既有统计分母与未知状态，当前信息视野只用 `periodExposure`，独立近7天观察进入附录。主题卡片按真实观点而不是强制正反立场配对。
2. `ReportPdfDocumentLayout.layout(document)` 输出原有 `List<PdfPage>`。新增卡片内容测量与列流分页，双栏允许独立续页；原文及表格按行拆分，标题、表头和来源按对应关系保留。继续使用原有绘制指令，附录复用旧引擎并连续编号。
3. `ReportExporter.writePdf` 切换到新文档与排版引擎，所有导出入口自动共用。文本与 JSON 导出不变。
4. 回归覆盖未知指标、当前与近7天范围、重叠作品的非互斥比例、引文归属、双栏边界/续页、长原文保留及取消。编译应用及测试源码，运行 JVM 与 Lint；使用实际引擎生成完整结构样张并逐页渲染，复核结构和代码后本地提交。

## 文件归属

- 内容实现：`ReportPdfDocumentBuilder.kt` 与 `ReportPdfDocumentTest.kt`。
- 排版与集成：`ReportPdfDocumentLayout.kt`、`ReportPdfCardFlow.kt`、`ReportPdfDocument.kt`、`ReportPdfLayout.kt`、`ReportExporter.kt` 及排版测试。

## 复核重点

不把作品数算作独立信源，不把未知视为零；不把情绪标签升级为过度推断；不将非互斥观点作品数做100%堆叠图；不截断长原文、不把来源带到下一观点；不因双栏高度差产生重叠或漏页。
