## V0.2.1 会话合并验证（2026-10-07）

assembleDebug / lintDebug 构建检查通过；testDebugUnitTest 共 16 项全部通过。新增连续重复/计数变化、跨应用返回、明确会话时长与数字证据变化回归验证。

手动覆盖安装目标及测试 APK，在 emulator-5554 运行 AndroidJUnitRunner，3 项通过：分析界面、中文多页 PDF 导出与渲染、数据库 1→2 保留旧记录且时长未知。迁移使用独立测试数据库，未删除用户数据库。未运行 connectedDebugAndroidTest，未卸载目标应用。

采集会话逻辑未在 OPPO 真机自动验证；跨应用节点、OEM 后台策略及内容识别误拆/误合仍需实测。APK SHA256: EE4C4B40CA8952AD2A667CD839FFCF656B976DED5E71235E303D611301A4B82D。

# V0.2 构建与验证 — 2026-10-07

工程位置：E:\Projects\ZhiHeng。
applicationId：com.zhiheng.app；版本 0.2.0 / versionCode 3。
minSdk 29，compileSdk/targetSdk 34，Gradle 8.9，JDK 21，字节码目标 17。

## 已完成

- 完全离线的文本清洗、预设主题与观点候选提取。
- 原文索引、条件与归属未知状态、按浏览时间比较最近同主题历史。
- 依据片段新增/重复/未知/条件不同的规则区分。
- 跨日持续重复线索观察，无心理或认知风险评分。
- 概览分析控制、主题环图、观点原文对话框、小时回顾。
- PDF 与 UTF-8 文本报告，通过系统文件选择器保存。
- 未改动 Room schema，无破坏性数据库迁移；APK 可覆盖安装。

## 验证结果

- assembleDebug、lintDebug、testDebugUnitTest 成功。
- lint：0 errors、1 warning（kapt 替换为 KSP 的性能建议）。
- 12 项单元测试通过：9 项认知规则/报告测试，3 项统计窗口测试。
- 2 项设备测试通过：使用内存合成数据渲染分析卡片；生成并渲染多页中文 PDF。
- 在 emulator-5554（API 37）检查分析卡片截图，以及合成样例 PDF 的 4 页 PNG，中文正常、换行和边距正常。
- PDF 附录最多 100 个候选，文本报告包含完整索引；统计与观察都使用完整分析快照。
- 用户的 OPPO 手机没有连接到本机，尚未验证该手机的文件选择器导出和离线规则准确性。

## 测试过程说明

首次 connectedDebugAndroidTest 的工具在结束时卸载了模拟器目标应用，模拟器已有记录被清除；已重新安装，并已告知用户。后续通过直接运行 instrumentation 测试验证，未再自动卸载。测试样本只存于内存与缓存，不写入浏览事件数据库；用户的 OPPO 手机没有被操作。

## 解释边界

这是用户确认的离线可解释原型。主题词表和字符相似度不能替代语义模型；同屏依据不一定真正支撑主张。规则阈值尚未通过人工标注真实数据校准，测试是工程回归检查，不是学术准确率评估。短时集中阅读不被直接判为信息茧房，未触发线索也不证明没有信息茧房。

未实现第一章规划的多模态联合理解、双路编码、交叉编码器、时序图神经网络、阅读投入估计、情绪归因、TEE 或远程证明。这些不得写入第二三章已完成能力。

报告文件含浏览引文；导出后用户自行管理，清空应用数据库不会删除外部文件。重新打开应用进程后需要重新分析，已导出报告仍保留。

## 报告与检查文件

lint：app/build/reports/lint-results-debug.html。
单元测试：app/build/reports/tests/testDebugUnitTest/index.html。
设备测试代码：app/src/androidTest/java/com/zhiheng/app/analysis/。
APK：app/build/outputs/apk/debug/app-debug.apk（调试签名）。
