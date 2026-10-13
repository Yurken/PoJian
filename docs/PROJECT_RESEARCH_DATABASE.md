# 项目实验数据库 v2

将已交付的 `research-controlled-v2` 合成数据建立为 **`pojian-research-v2.sqlite`**，并接入 App「设置 → 合成实验数据库」。入口替代旧的 588 作品演示入口；旧版数据和回归测试仍保留。基准提交 `a7ae7058c1594e9d8ee73288c2d654e28cf8a618`，开发分支 `codex/work-identity-thumbnails`。

## 内容与用途

| 实体 | 行数 | 关系 |
| --- | ---: | --- |
| `works` | 8,400 | 七平台/媒体组合各 1,200，六领域各 1,400 |
| `versions` / `arguments` | 各 25,200 | 每作品三个观察版本；每版本一份预设论证标注 |
| `claim_presentations` | 25,200 | 与观察版本一一对应的大主题、短观点、影响依据和建议，明确为合成标注 |
| `profiles` / `user_days` | 200 / 2,800 | 虚拟用户；其中 20 个用户日缺失 |
| `contacts` / `visible_intervals` | 各 33,360 | 每次接触独立，固定关联当时版本和可见区间 |
| `emotions` | 11,200 | 保留情绪、原因、角色、否定/转述与证据跨度 |
| `increments` / `increment_labels` | 各 16,800 | 当前版本及历史关系；目标标签独立存放 |
| `increment_history` | 按历史条目展开 | 外键关联历史作品和版本，保留当时可用时间 |
| `feedback` | 2,780 | 虚拟自报，与内容情绪独立 |
| `traces` / `trace_labels` | 各 240 | 合成页面回归输入和标签 |
| `safety` / `safety_labels` | 各 120 | 安全场景规格，非真机运行结果 |

数据库完整保留每个来源 JSONL 行的 `payload`，另将身份、版本、用户、时间、划分等提取为外键及索引列，支持 SQL 查询并可逐行回查原始标注。`metadata` 保存来源清单、SHA-256、合成属性和未人工复核状态。

这批数据是 AI 编写生成器产生的受控合成材料；虚拟用户不是志愿者。OCR/ASR 为文本代理，数据库没有原平台媒体或真实缩略图。标注与信息茧房/焦虑实验分组均非实测用户心理结果。独立单位、训练划分及论文适用边界见 [研究数据说明](RESEARCH_SYNTHETIC_DATA.md)。

## 项目中的使用

- App 默认选中高集中、高焦虑表达预设组的一名虚拟用户，可切换全部 200 名用户。预设标签直接标为「预设」，实际图表继续调用项目的 `CognitiveAnalyzer`、`calculateStatistics` 和 `ViewpointTimeline`，不会用分组标签覆盖分析结果。
- 每次只从索引读取所选用户的 156–168 次接触，三个语料观察版本不会变成三次观看。再次观看同一作品保留新的事件，窗口作品数按作品身份去重。
- `project_contacts` 直接关联 `contact.version_id`，不取“最新版本”；映射时再次检查观察/可用时间不晚于该次观看。合成材料有明确前缀，实际平台 `nativeId` 为空。
- 内容焦虑计数按时段内作品去重，保留持有者与转述/否定；不会将内容中的焦虑写成观看者焦虑。增量目标标签和虚拟自报不进入该 App 分析投影。
- 「作品库」按稳定作品 ID 每页查询 40 条，可查看全部 8,400 个作品的完整合成文本与平台/媒体、领域、划分，以及新增的短观点和合成分析。这里展示第三观察版本；浏览历史仍使用各次接触绑定的版本。
- 点趋势柱后按大主题展示简短观点卡片，点击卡片再显示原文、三类影响依据和建议。新增字段由当前版本和对应情绪片段生成，来源为 `AI_authored_rules_unreviewed`，不是在线模型实测输出。分类、颜色公式及真实记录兼容见[主题观点说明](VIEWPOINT_TOPICS.md)。
- 界面只增加必要入口、用户/页码控件、真实数量、来源与错误状态；原趋势和详情功能继续复用。

## 隔离、安装与更新

这是只读实验 SQLite，**不是生产 Room 库的替换文件**。不要把它改名为 `zhiheng.db`。生产 `BrowseDatabase`、个人历史、实时采集、上传、签名和 applicationId 均不因本次数据库接入改变。

App 随包携带 `assets/synthetic/research-v2.sqlite.gz`（18,824,913 字节，约 18.8 MB），首次打开实验入口才在 IO 线程解压至私有 `noBackupFilesDir/research`，展开文件 185,962,496 字节（约 186 MB）。包内 SHA-256 校验、解压尺寸上限 256 MiB、空间预检、临时文件和原子替换防止残缺文件被读取；中断会清除临时文件，下次可重试。后续读取复用已验证文件。所有数据库查询、文本转换和图表计算均在后台执行。

文件名带内容哈希；新语料成功安装后才淘汰旧实验副本。该实验 schema 从 `user_version=1` 升至 `2`，通过只读资产的校验安装更新，不需要也不会触发生产 Room 迁移。新库 SHA-256 为 `883e3366de895d4320cfbdb671b1b2da557aa501b0be17f4eba31f78006bb35c`。旧演示 `pojian-synthetic-demo-v1.db` 保留；新的入口不再创建或读取它。实验库不会放入系统备份，也不会被监控 Repository 的 5,000 条裁剪影响。

## 复现

在项目根目录运行，先生成或恢复已交付的 v2 来源文件：

```powershell
python scripts/build_research_dataset.py
python scripts/audit_research_dataset.py app/build/research-synthetic-v2 --output app/build/reports/research-v2/data-audit.json
python scripts/build_project_research_database.py --install-assets
python scripts/audit_project_research_database.py
python -m unittest discover -s scripts/tests -p test_project_research_database.py
```

正常签名环境执行：

```powershell
./gradlew.bat :app:testDebugUnitTest :app:evaluateResearchDatabase :app:compileDebugAndroidTestKotlin :app:lintDebug
```

本机原固定签名缺失，仅对上述不产出 APK 的检查使用 `-x :app:verifySigningIdentity`；不得据此绕过 APK 签名。没有启动模拟器。

可直接使用标准 SQLite 客户端只读打开交付的 `.sqlite`。典型查询：

```sql
-- 单个用户、单个窗口；不能把 200 名用户合并为一份个人历史。
SELECT COUNT(*) AS visits, COUNT(DISTINCT work_id) AS works
FROM contacts WHERE profile_id = :profile AND timestamp >= :start AND timestamp < :end;

-- 固定该次接触的可见版本；不得另行连接每作品的最新版本。
SELECT * FROM project_contacts WHERE profile_id = :profile ORDER BY timestamp, row_id;

-- 同源、作者与划分可以直接追溯；原始标签/授权来源等保留在 payload。
SELECT id, author_id, identity_group, near_duplicate_group, split FROM works;

-- 新展示分析固定关联观察版本，来源不能被当作真实模型响应。
SELECT version_id, work_id, available_at, provenance, payload FROM claim_presentations;
```

停留时间应裁剪到查询窗口后取 `visible_intervals` 的区间并集；跨柱作品不能简单累加为全天去重作品数。

## 验证记录

数据库审计报告为 `app/build/reports/project-research-database/database-audit.json`：14 张来源表逐行无损比对；SQLite 完整性通过、外键错误 0；33,360 次接触无投影增殖，所有历史与可用时间检查通过。25,200 条新增展示分析全部通过字段、来源和同版本原文证据检查。200 名用户的接触/情绪查询使用 `contacts_profile_time` 索引；本机 SQLite 查询平均约 9.6 ms、最大约 17.5 ms，仅作主机测量，不代表手机流畅度。

项目映射、真实统计函数和趋势详情的全量对照结果写入 `app-projection-results.json`：200 名虚拟用户、33,360 次观看、前后两周 × 三种范围共 1,200 个窗口，作品数/观看次数/趋势详情数量/焦虑作品计数错误均为 0，停留时间最大误差 0 ms。新增的 12,380 个非空柱区间主题卡片数量与顺序核对全部通过。200 份实际分析器报告中 100 份产生主要接触模式，实验分组未用于覆盖计算结果。全量 JVM 对照用时约 214.3 秒。

原数据库接入时 283 个 JVM 单元测试和 6 个 SQLite 约束测试通过。主题分析补充后 SQLite 约束增加为 7 项，新增 3 项标注生成测试；当前 App/服务端完整验证见[实现对照](IMPLEMENTATION.md#验证状态)。本机 Gradle 曾有既有缓存索引损坏警告，没有修改或清空全局缓存。

人工手机轨迹、Android SQLite 解压/首次打开、Compose 实际操作与滚动性能仍需要真机验证。本次不将主机验证宣称为真机验证。签名配置仍缺失，因此没有生成新的可安装 APK。

## 修改文件

新增数据库 schema/压缩资产/清单，`ResearchDatabaseStore.kt`、`ResearchAssetInstaller.kt`、`ResearchProjection.kt`；调整 `SyntheticDemoScreen.kt`、`SyntheticDataset.kt`、`BrowseWorkCards.kt`。新增构建/审计 Python 脚本、SQLite 约束测试、10 个 JVM 测试、全量 Kotlin 评测入口及测试样本；`app/build.gradle.kts` 增加离线评测任务。本说明及 README/原数据说明同步更新。
