# 下一阶段开发实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 先让现有版本可稳定验证、可保留数据升级，再用独立标注材料定位模型与采集质量的主要缺口。

**Architecture:** 保持 Android 采集与 Room 本地数据、Python 网关、GitHub Actions 发布三条现有链路。优先复用现有回归入口；故障诊断、设备验收和模型评测分别产出证据，不把编译、合成回归、真实准确率混为一谈。

**Tech Stack:** Kotlin / Compose / Room、JDK 17、Android API 29+（编译 API 34）、Python 3.12+ 标准库、GitHub Actions。

**Spec:** [产品需求](../../PRD-Multimodal.md) R1/R2/R3/R6/R7、[固定签名规则](../../ANDROID_SIGNING.md)、[离线评测协议](../../OFFLINE_QUALITY_EVALUATION.md)、仓库根目录 `AGENTS.md`，以及本轮五项修复的验证缺口。

**状态：** 仅完成规划，下面任务尚未执行。计划基线为本地修复 `3d6b1c3` 与远端 `1d4d8b0`（0.6.32）合并后的 0.6.33；实际 CI 结果以对应提交的 Actions 记录为准。

## Global Constraints

- 不修改现有 `EMERGE_SERVICE_TOKEN`、构建令牌或固定签名；`applicationId` 保持 `com.zhiheng.app`。
- “默认界面文案增量为零。”本计划不授权新增副标题、解释、教学、标签或帮助内容。
- 每个任务完成必要检查后单独本地提交。推送仍需授权，按 AGENTS 递增补丁版本和 versionCode、创建唯一附注 `v<versionName>` Tag。
- 不在用户日常设备上清空数据库、卸载应用或写入测试数据；设备测试只用明确指定的隔离设备。
- 不将真实手机快照、私钥、令牌或未脱敏材料提交到仓库、公开 CI 附件或发布 APK。
- 编译通过不等于设备测试通过；合成样本结果不等于真实模型准确率。

## Review Focus

1. 请求体延迟到达、只发部分请求体或超大请求：提前拒绝不能无限等待、无限读入，也不能把预期错误响应稳定变成连接中断。由任务 1 覆盖。
2. 删除发生在上游请求完成之前：被删除材料不得复活，未删除的旧分析应保留。由任务 2 覆盖。
3. 最后一条记录虽过期但有最近采样、正在录音或新转写：不能只按父记录时间误删。由任务 2 覆盖。
4. 覆盖升级、进程重启、演示包初始化：UID、Keystore、已有数据库和分析不得丢失或重复灌入。由任务 2 覆盖。
5. 引文不合法、未知结果、漏抽取、网络失败和转载同源：不能从质量分母排除，不能跨训练/测试组泄漏。由任务 3 覆盖。

## 顺序与交付

| 顺序 | 优先级 | 交付 | 进入下一步的条件 |
| --- | --- | --- | --- |
| 1 | P0 | HTTP 提前拒绝的稳定复现、修复和 Windows/Linux 回归 | 受影响场景连续运行稳定，完整服务端套件通过 |
| 2 | P0 | 删除、保留周期、恢复、覆盖升级的设备验收记录 | 隔离设备实际执行通过，无法执行的项明确保留未验收 |
| 3 | P1 | 真实模型评测入口与小规模独立标注结果 | 材料和费用获得授权，误差有完整分母和可回查案例 |

任务 1 可直接准备实现。任务 2 需要现有固定私钥及可用的隔离设备；缺少条件时先完善可编译的回归用例，不替换签名。任务 3 可先用现有合成夹具开发工具，真实数据和付费 API 调用须另行明确授权。每个任务可独立验收，不因后续外部条件未满足而阻塞前面的交付。

### Task 1：关闭 HTTP 提前拒绝的稳定性缺口

**Files**

- Modify: `server/emerge_server.py`、`server/monitor_server.py`（只调整已证实有问题的响应/连接关闭路径）。
- Test: `server/tests/test_monitor_server.py`、`server/tests/test_audio_gateway.py`。
- Create: `server/tests/test_http_rejection.py`（真实回环 socket，控制请求头、请求体发送顺序）。
- Modify: `.github/workflows/build-apk.yml`（故障修复后，将服务端检查扩为 Ubuntu/Windows 矩阵；APK 仍等待所有测试作业成功）。

**Interfaces**

- 保留 `Handler.send_json(status: int, payload: dict)` 及所有 HTTP 协议字段。
- 保留错误状态：错误令牌 401、未配置模型 503、超大请求 413、并发满 429。
- 不改变令牌比较方式、配额扣除规则或认证顺序；不以客户端自动重试掩盖响应丢失。

- [ ] **记录失败边界。** 对同一请求分别立即发送完整 body、分段发送、仅发送 headers；记录响应状态、关闭时刻和异常类型。比较修复前提交与当前提交，区分服务端关闭、客户端 fixture 和运行环境，不能直接将 WinError 10053 归因为网络。
- [ ] **添加失败测试。** `test_rejected_body_in_multiple_writes_preserves_status` 断言完整接收预期状态及 JSON；`test_rejection_does_not_wait_for_unbounded_body` 用明确截止时间验证只发 headers 时线程可退出；`test_rejection_does_not_call_provider_or_spend_budget` 断言上游调用为 0、配额增量为 0。正常成功请求仍返回 200。
- [ ] **运行并确认复现。** `python -m unittest server.tests.test_http_rejection -v`；必须先捕获与上述因果链一致的失败，禁止添加忽略异常或无限重试。
- [ ] **实施最小修复。** 依据复现选择关闭顺序或有界输入处理；任何等待或读取必须有时间/字节上限。复用父 Handler，避免为音频和监测接口分别增加不一致的关闭逻辑。
- [ ] **验收。** Ubuntu 与 Windows 各连续运行受影响小套件 20 次且无失败，再运行 `python -m unittest discover -s server/tests -v`；记录环境、耗时和第一次失败，不删除失败记录。CI 两个平台都通过才允许 APK 构建。
- [ ] **提交。** `fix(server): make early HTTP rejections reliable`，仅包含该根因修复、行为测试和对应 CI 检查。

### Task 2：补齐设备上的数据生命周期验收

**Files**

- Test: `app/src/androidTest/java/com/emerge/app/analysis/MonitorSemanticsIntegrationTest.kt`。
- Test: `app/src/androidTest/java/com/emerge/app/data/LocalStorageRetentionTest.kt`。
- Test: `app/src/androidTest/java/com/emerge/app/monitoring/MonitorTaskPersistenceTest.kt`。
- Test: `app/src/androidTest/java/com/emerge/app/analysis/SigningUpgradeTest.kt`、`DemoOnlineAnalysisTest.kt`。
- Reuse: `scripts/check-monitor-recovery.py`、`scripts/verify-apk-signing.py`。
- Create: `docs/validation/2026-10-device-lifecycle.md`（仅设备型号/系统/版本/结果，不存用户正文或凭据）。

**Interfaces**

- 复用 `MonitorRuntime.invalidate(clearSemantics: Boolean = false)`、仓库删除方法及 `evictOldestStorageUnit(beforeTimestamp)`。
- 复用 `SigningUpgradeTest` 的 `signingUpgradePhase=seed/verify`；保留现有恢复脚本的生命周期阶段。
- 保持 Room schema、包名、固定证书和服务令牌；只有实测缺陷证明必要时，才另开最小功能修复提交。

- [ ] **补足可复现断言。** 部分删除后，超过 24 小时的未删除记录语义仍存在；被删除材料的在途返回不能重新写入。使用可控闩锁等待请求和删除完成，避免靠固定 sleep 判断异步清理。
- [ ] **覆盖保留边界。** 最后一条真正过期访问及已结束音频可删；最近采样、最近转写和进行中录音保留；容量清理继续保护最近访问。检查关联语义、任务和缩略图的级联结果。
- [ ] **编译。** `./gradlew :app:compileDebugAndroidTestKotlin -x :app:verifySigningIdentity`；这一步只证明源码可编译，不作为设备验收。
- [ ] **隔离设备执行。** 恢复现有固定签名，构建测试 APK，在明确指定的 `QA_DEVICE` 运行上述用例及恢复脚本；禁止默认选择当前连接的日常手机。补测 Android 10 与 Android 14+ 的可用测试设备，记录未覆盖的系统。
- [ ] **覆盖升级。** 按 `docs/ANDROID_SIGNING.md` 依次安装旧固定签名版本、seed、新版本、verify；核对版本递增、UID、正文/时长/设置、Keystore 解密。演示包另验首次导入、已有库不覆盖、重启不重灌和升级恢复在线配置。
- [ ] **提交。** `test(android): cover deletion retention recovery and upgrades`；验收记录须区分实际通过、失败与未执行，不把未执行标为通过。

### Task 3：用独立材料评估模型质量与成本

**Files**

- Reuse: `evaluation/kotlin/com/emerge/app/evaluation/HistoryQualityEvaluator.kt`、`evaluation/datasets/synthetic-v1/`。
- Create: `scripts/evaluate_gateway_quality.py`、`scripts/tests/test_gateway_quality.py`。
- Create: `evaluation/schemas/gateway-quality-v1.json`（输入、标注、预测、错误和来源分组契约）。
- Modify: `docs/OFFLINE_QUALITY_EVALUATION.md`（明确网关抽取评测与现有历史比较评测的区别）。
- Private outputs: `app/build/reports/gateway-quality/`；真实输入保存在仓库外的授权目录。

**Interfaces**

- CLI：`python scripts/evaluate_gateway_quality.py --dataset <目录> --predictions <JSONL> --output <目录>`；第一版只消费预先取得的响应，不自动调用外部 API。
- 样本标识包含 `sample_id`、`source_group`、`modality`、标注版本和授权说明；预测记录包含状态、耗时、模型标识及完整错误分类，不保存凭据。
- 输出 `summary.json` 和错误样本索引；历史比较继续由现有评测器处理，两个阶段的分母分别呈现。

- [ ] **先固定协议。** 用现有合成材料写出输入/预测/标注示例；同一原始事件及转载共享 `source_group`，不得拆到不同划分。
- [ ] **添加失败测试。** `test_missing_prediction_counts_as_failure`、`test_unknown_stays_in_denominator`、`test_invalid_quote_is_not_success`、`test_source_group_cannot_cross_splits`。另验日志和输出不包含令牌。
- [ ] **实现离线评测入口。** 区分传输失败、协议失败、引文失败、事实/观点误分类和未知；输出混淆矩阵、覆盖率及延迟分布。提供方未返回使用量时保留费用未知，不用请求次数假冒 token 费用。
- [ ] **工具验收。** `python -m unittest discover -s scripts/tests -p test_gateway_quality.py -v`；同一冻结输入和响应产生相同质量指标，缺失响应不能使分母变小。
- [ ] **开展授权试评。** 建议先准备 30 个可追溯材料：文字、图像、口播各 10 个，包含否定、条件、引用、同源转载和信息不足；两人独立标注后裁定。真人材料、API 提供方及费用上限均需在执行前确认；此规模只用于发现问题和估算成本，不能宣称整体准确率。
- [ ] **形成下一轮功能依据并提交。** 按误报、漏报、未知、延迟与成本列出主要失败原因；再决定是优先修作品身份/跨页归并、抽取提示还是来源判断。提交 `feat(evaluation): add gateway quality evaluation`，真实材料和密钥不进入提交。

## 暂缓的方向

连续视频时序推理、通用跨页自动归并、训练编码器、TEE/远程证明分别需要独立设计与证据，本轮不展开实现。原文数据库加密列为后续隐私专项，先评估密钥恢复、旧库迁移和备份兼容，不直接替换数据库或声称现有原文已加密。

## 计划自检

- 三项任务分别对应已观测 HTTP 失败、设备验收缺口和现有合成评测边界；没有把已实现功能重新列为待开发。
- 五类审查边界均分配到对应任务的具体断言或验收步骤。
- 新增文件、既有接口和完成条件已列明；需要外部条件的任务注明依赖。
- 本文是研发计划，不授权自动发布下一版、访问真实用户数据、付费模型调用或向界面加入说明文字。
