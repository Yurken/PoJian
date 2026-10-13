# 作品身份、浏览统计与本地缩略图修复

工作分支：`codex/work-identity-thumbnails`。开始时已获取 `origin/main`，基准提交为 `a7ae7058c1594e9d8ee73288c2d654e28cf8a618`（0.5.18 / 40）。保留现有观点筛选、分布、监测、更新及音频功能；未修改签名、applicationId、更新分发或版本号，未推送或合并。

## 根因与复现

这些结论来自代码调用链和工程预设边界的合成操作轨迹，不是根据用户截图推断，也不是实机准确率评估。

| 排查项 | 基准行为及复现 | 本次处理 |
| --- | --- | --- |
| 作品身份 | `WorkHint` 有 nativeId 字段，但提取器未读取平台 ID；同作者、相同文案、不同 aweme_id 被计成一个作品。反过来，同一 ID 文案变化可能分成两个。 | 仅提取已可见、已知资源字段中的单个平台 ID；冲突 ID 优先作为边界。多个可见 ID 判为列表，不能任取卡片。 |
| 文案展开、图文、评论、字幕 | 截断文案参与标题哈希，展开后身份不同；固定采样间隔还会切开观看。 | 截断标题不独立充当可靠身份；同作者且明确前缀延续时在同一观看内提升身份。重返文案仅在已观察的同作者完整标题候选唯一时恢复，也适用于此前有原生 ID、当前暂不暴露 ID 的情况。延迟本身不构成作品边界；未观察间隔不计时。 |
| 相同文本跨作品 | `BrowseWorkKey` 的全文哈希及传递合并可把不同 visit 键桥接起来。历史分析也会按近邻全文合并。 | 删除跨身份的全文合并；无法判断的旧记录保留独立身份及不确定方法。历史分析仅可合并已有相同作品键的重复采样。 |
| 多次观看 | 基准已有部分稳定身份，但匿名全文合并会误减数量，身份分裂会误增数量。 | 观看行保留，按窗口内作品键去重。历史修复不再删除“相邻相同正文”的观看行。 |
| OCR、音频、云端结果 | 基准浏览统计已排除这些来源，不能声称它们直接增加了浏览趋势作品数；但趋势观点未完整纳入已关联材料，部分暴露统计把播放会话当成独立接触。 | OCR/视觉结果绑定截图时的观看上下文；转写只绑定完整覆盖其时间段的唯一可见观看；关联材料继承作品身份，不独立贡献作品数、时长或暴露票数。无法绑定时仍保留材料。 |
| 分析版本 | 同一事件已有“最新有效分析版本”校验，这部分不是重复根因。 | 保留最新版本和全文校验，增加身份与父事件变更检查，避免迟到分析绑定修订前身份。 |
| 多个真实观点 | 柱子详情调用 `groups`，一部作品的两个观点会进入两个主列表分组。 | 主列表调用 `details` 按作品排列，一卡可带多个观点。完整观点、条件、证据及每次观看原文移入详情。 |
| 不同作品相同观点 | 旧详情按相同引文折叠多个来源，掩盖了作品差异。 | 观点分组保留；组内来源保留不同作品卡。相反立场和关键条件不合并，同作品相同结构观点键只贡献一次。 |
| 时长与范围 | 重叠观看时长直接相加；观点分布固定取当天，与“过去 24 小时 / 最近 7 天”不一致。 | 计时取有效区间并集；旧比例时长仅作为估计。趋势、平台统计、作品卡、分布共用窗口和作品键，整体作品数不能累加柱子得到。 |

数据流：可见节点 → `WorkIdentityExtractor` / `CaptureSessionTracker` → `ObservedWork` + 多次 `BrowseEvent` + `BrowseSample` → 父事件关联的多模态材料 → 最新有效 `EventSemantics` → `BrowseWorkKey` / `ObservationWindow` → 趋势、平台统计、作品卡和观点分布。身份变化更新关联材料，并使依赖旧身份的监测输入失效；ViewModel 观察数据库变化重新计算分析。

`identityMethod=session_estimate` 表示身份不确定；新增 `identityEvidence` / `nativeId` 保存判断依据。相同作者、模糊标题前缀、相同观点均不能单独证明同一作品。没有历史节点结构的旧记录只使用已充分支持的有限修复，不猜造 ID 或历史图片。

## 缩略图与隐私边界

- 设置中的“本地缩略图”默认关闭。简短确认说明持久保存范围；关闭后清理已保存图片。与云端总开关、画面上传开关分别授权，本地流程没有云端提交调用。
- Android 11+ 使用现有辅助功能截图能力和裁剪编码器。Android 14+ 请求指定窗口；旧版仅在无其他窗口遮挡时裁剪应用区域。安全窗口错误不绕过，不下载隐藏媒体。Android 10 保留文字与占位路径。
- 请求绑定观看 ID、作品键、应用、窗口、内容修订、采集权限及保存授权版本。回调、编码后、写入事务和事务完成后分别校验。锁屏、窗口切换、快速滑动、撤权、删除均使旧请求无效。
- 遍历全部允许数量的节点做隐私检查，文字长度上限不会提前结束输入检查；遍历不完整、输入框、密码、键盘、其他覆盖窗口、无法确认几何坐标时不保存。Android 14 的窗口截图可排除已确认属于本应用的无障碍悬浮层；Android 11–13 出现覆盖层时保守降级。
- 单个可信正文图片/视频区域优先裁剪；区域不明时才保存通过隐私检查的页面截图。空白/黑屏跳过，视频帧增加轻量边缘清晰度筛选。此规则不是经过实机校准的遮挡或清晰度分类器。
- 只保存每作品一个代表图，可将页面截图提升为正文裁剪。图片标记“页面截图 / 正文截图 / 已采样视频帧”，没有“平台原图”或“原作品封面”的虚构标记。
- JPEG 最长边 480 px、单图最多 96 KiB；应用私有且不备份的目录 `work-thumbnails`，数据库仅存元数据。总上限 64 MiB / 400 张，按保存时间淘汰。启动、授权、元数据变化及写入后清理孤儿文件。
- 10 MiB 内存缓存、最多两个后台解码任务、懒加载有界作品列表；UI 线程不解码大图。删除一次观看不会误删共享缩略图，最后一个关联记录删除后级联移除元数据并清理文件。

Room 7→8 增加两个身份依据字段和缩略图表，保留已有表、记录 ID、采样及分析。1→8 迁移链继续注册，没有 destructive migration。

## 回归结果及边界

基线和修复后使用同样的操作与工程标注，详见 `WorkRegressionTraceTest` 和 [逐轨迹 CSV](work-identity-traces.csv)。12 条轨迹、33 次采样、34 对同轨迹样本身份比较：

| 指标 | 基准 | 修复后 |
| --- | ---: | ---: |
| 身份等价判断错误 | 7 / 34 | 0 / 34 |
| 观看开始/延续边界错误（含各轨迹首样本） | 5 / 33 | 0 / 33 |
| 窗口去重作品数不符 | 6 / 12 轨迹 | 0 / 12 轨迹 |
| 各轨迹有效测量区间时长绝对误差合计 | 8,000 ms | 0 ms |
| 两次重叠观看的并集时长（期望 6,000 ms） | 8,000 ms | 6,000 ms |

覆盖视频播放/暂停/字幕变化、明确平台 ID、同作者不同作品、不同作品相同观点、文案展开、图文正反翻页及评论、返回同作品、跨应用、节点暂缺、锁屏重返、身份不确定且正文相同。原基线夹具名 `cross-hour` 的时间实际为 03:09:56，因此在 CSV 及现夹具中改名 `same-work-spacing`，没有更改操作时间；它**不作为跨小时验证证据**。真正跨 12:00 的窗口验证另在 `WorkStatisticsConsistencyTest`，同时对照三种范围、平台、柱子、卡片和观点分母。

时长表中的标注是“两个有效采样之间能确认连续可见的区间”，不是整段人工操作的墙钟停留。切换作品、锁屏或离开应用时，最后一次采样到边界之间的尾段不外推；常规 2 秒采样可能少计接近 2 秒的尾段，系统调度延迟时上界不能仅按 2 秒保证。采集开始前、结束后及无效观察期间同样不补算。随机区间测试验证已记录区间的并集准确性，不能证明真实观看时间误差为零。真实边界时间误差、身份漏采率和截图关联率仍未测量。

额外测试包括：100 组随机区间对独立逐毫秒覆盖算法、分析乱序、关联多模态不增作品、无关联材料不增暴露票数、原生 ID 后出现、截断前缀歧义、截图迟到/换应用/换窗口/换作品/撤权上下文、锁屏或观察空洞不能绑定整段转写，以及 5000 条保留记录的统计容量检查。

已运行的命令和最终数量见下方验证记录。设备测试仅完成源码编译：`WorkThumbnailStoreTest` 覆盖存储不足、关闭保存、迟到拒绝、共享图片删除、缓存、孤儿文件、存储上限及应用范围撤回；`WorkPersistenceTest` 覆盖保留观看与采样、前缀歧义；`SessionMigrationTest` 覆盖完整 Room 迁移链；UI 测试检查作品卡 key 与多个观点。**编译通过不代表这些设备测试已经通过。**

界面数据结构对比（不是运行截图）：同一作品两条观点，基准柱子详情进入两个观点分组并分别展开作品；现在先显示一个作品卡、两条短语，点击详情查看完整内容。不同作品同一观点，基准详情可能折成一个来源区块；现在各有自己的卡片，观点分布仍可归为同组。实际截图、帧时间和快速滑动缩略图关联仍需设备验收。

## 构建与未完成验收

固定签名校验因缺少 `keystore.properties` 阻止 APK 构建。本轮没有生成可安装 APK，也没有使用 Debug 密钥替代。用户目录文件名搜索未发现项目配置或密钥；`.android/debug.keystore` 不是固定签名的替代品。需要维护者或备份提供原配置及 `storeFile` 指向的密钥文件，参见 [固定签名说明](ANDROID_SIGNING.md)。不要从密码内容发起排查，也不能从 APK 或 GitHub Secrets 读取回原始私钥。

JVM / 静态检查临时使用 `-x :app:verifySigningIdentity`，只执行测试、源码编译和 Lint，没有借此生成 APK。Android Gradle Plugin / kapt 有现存警告，本机 Gradle 的 `file-access.bin` 缓存警告也出现在日志中；均未把失败伪报为成功。

本轮按用户偏好未启动模拟器；暂无真实手机结构与操作轨迹。仍待验证：固定签名 APK、覆盖安装、Android 真机 Room 迁移、截图 API/OEM 行为、键盘/通知/安全窗口、真实帧清晰度与关联率、磁盘失败恢复、列表滚动帧时间、真实 OCR 误差及弱网端到端。所需轨迹：同一图文翻页→展开→评论→返回；同一视频播放→暂停→滑走→返回；跨应用、锁屏、快速连滑各一组，记录人工作品边界和操作时间，附平台版本即可。

## 可见字符串审计

仅新增必要控件“本地缩略图 / 保存本地缩略图 / 允许保存 / 取消 / 查看详情”、持久保存授权句、系统不支持和保存失败状态、真实图片来源标签、时长与观看/材料来源数据，以及简洁空图符号。观点短语来自规则或截短原文。已有趋势帮助的统计范围改为当前窗口，读屏点击目标改为作品；未新增口号、模块副标题或教学段落。

## 文件清单

生产代码：

- `capture/WorkIdentity.kt`、`CaptureSessionTracker.kt`、`BrowseAccessibilityService.kt`、`CaptureSettings.kt`、新增 `MaterialWorkBinding.kt`。
- `data/ObservedWork.kt`、`BrowseEventDao.kt`、`BrowseRepository.kt`、`AudioRepository.kt`、`BrowseDatabase.kt`、新增 `WorkThumbnail.kt`、`WorkThumbnailStore.kt`；`EmergeApplication.kt`；Room `8.json`。
- `analysis/BrowseWorkKey.kt`、`ObservationWindow.kt`、`ViewpointTimeline.kt`、`ViewpointLabeler.kt`、`CognitiveAnalyzer.kt`、`ExposureAnalyzer.kt`。
- `monitoring/MonitorAccessibilityBridge.kt`、`MonitorRuntime.kt`、新增 `LocalThumbnailCapture.kt`、`ThumbnailContext.kt`、`ThumbnailEncoder.kt`。复用 `MonitorFrameEncoder` 与 `ScreenTextRecognizer`，未更换 OCR 模型。
- `ui/BrowseStatistics.kt`、`ViewpointTimelineCard.kt`、`RecordControlScreens.kt`、新增 `BrowseWorkCards.kt`。保留 `ViewpointDistributionCard` 的现有交互，仅调整其输入范围。

测试：新增 `WorkRegressionTraceTest`、`WorkIdentityUpgradeTest`、`MaterialWorkBindingTest`、`ThumbnailContextTest`、`WorkStatisticsConsistencyTest`、设备端 `WorkThumbnailStoreTest`、主机 `test_work_thumbnail_migration.py`；更新身份/时间/历史分析/分布的旧测试契约、`WorkPersistenceTest`、`FunctionalMinimalUiTest` 和数据库测试迁移注册。旧“按全文合并匿名作品 / 相近观看删除为一行”的断言按新需求更正，没有以删掉失败用例代替修复。

文档：本报告及 CSV、README、IMPLEMENTATION、REALTIME_MONITORING，更新独立本地保存授权和多模态关联约定。


## 本次验证记录（2026-10-12）

| 检查 | 结果 |
| --- | --- |
| `:app:testDebugUnitTest` | 268 项，0 失败、0 跳过 |
| `:app:compileDebugAndroidTestKotlin` | 编译通过，未运行设备测试 |
| `:app:lintDebug` | 0 错误、18 警告；其中包含新存储检查使用 `usableSpace` 的保守空间判断建议 |
| `python scripts/tests/test_work_thumbnail_migration.py` | 5 项通过，直接执行 7→8 生产 SQL，对比 Room 8 导出表结构和外键 |
| 容量轨迹 | 5000 次观看、250 个作品、有效并集时长 10,000,000 ms；本次主机 JVM 统计 + 时间线 + 卡片数据整理 444 ms，未测设备帧率 |
| `git diff --check` | 通过 |
| 项目签名校验 / APK | 原配置缺失；本机 Debug 公共证书与 `app/signing-certificate.sha256` 不匹配；未产出 APK |

本地日志：`app/build/ui-tools/work-repair-unit.log`；基准及修复后轨迹：`app/build/reports/work-repair/{baseline-traces,fixed-traces}.xml`；JVM 报告：`app/build/reports/tests/testDebugUnitTest/index.html`。这些构建产物被 Git 忽略，CSV 与测试源码纳入本次改动。复现需使用本机 JDK 17 / Gradle 8.9 / Android SDK，Windows 本机通过已有 ASCII 目录映射执行；缺签名时只可对上述非 APK 任务排除签名预检查。
