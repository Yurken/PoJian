# 破茧 构建与验证

## 多向织茧原生接入与真实依据占比（2026-10-11）

- 按用户最新 `ai_studio_code (1).html` 将四向交错的 160 根丝线、金青色光核、渐密遮挡及触摸视差移植为 Compose Canvas，接入首页统计区域之后。跟随 App 深浅主题，保留暂停／播放；未照搬说明段落和状态口号。
- 用户指定由真实重复依据占比驱动。分子为 `Increment.REPEATED` 观点数，分母与既有依据比例条一致，为报告内全部观点（含首次、新增、条件不同、无法判断）。显示“重复依据”、分子／分母及百分比；无报告、报告过期、时段不匹配、无观点或全部无法判断时显示空轮廓与“—”。未新增风险分数或手动调节入口。
- 离开可见列表范围或进入后台时停止持续动画，系统关闭动画即时生效。动画更新只触发绘制，路径对象复用，尺寸与丝线宽度按画布缩放。
- APK 与测试 APK 编译通过；213 项 JVM 测试通过；lint 0 errors、17 项既有 warnings。18 项相关设备测试通过，另 1 项系统关闭动画专项通过，包含数据过期／时段切换、分母、运动与暂停的图像差异、浅色及深色 150% 字号检查。所有测试均在任务专用模拟器运行。
- 截图和设备日志：`app/build/reports/cocoon-native/`，截图数值来自测试夹具。APK：`app/build/outputs/apk/debug/app-debug.apk`。本轮无后端修改，无部署。

## 信息茧房动效交互预览（2026-10-11）

- 根据后续 `ai_studio_code.html` 参考，将两瓣开合改为 0–150 根丝线在椭球表面旋转缠绕：五档密度、金青色光核随密度渐隐、深色默认与指针视差；未引入参考稿中的状态解释和风险判断。再次通过五档预设、指针跟随、320px 布局、键盘、暂停／恢复和减少动态效果检查，无脚本错误。新版截图位于 `app/build/reports/cocoon-preview/weaving/`。
- 新增独立预览 `docs/design/cocoon-preview.html`：茧丝前后遮挡、茧壳开合、光核透出、拖动与三档预设、暂停及深浅背景切换。用户选择先确认效果，本轮未接 Android 首页或真实数据；百分比仅为手动控制的闭合程度。
- Chromium 验证展开／半合／闭合、键盘调整、暂停后停止帧循环、恢复播放、减少动态效果时立即更新且画面保持静止；320px 宽度无横向溢出，无页面脚本错误。已检查展开、闭合、深色与窄屏截图。隐藏页面或图形离开视口时取消动画帧。
- 预览无外部依赖或网络请求。只使用标题、必要控件标签和预览数值，无新增说明段落或风险判断。验证脚本和截图位于 `app/build/reports/cocoon-preview/`；本轮不涉及 Android 构建或后端部署。

## 观点分布样式优化（2026-10-11）

- 按参考建议将互斥观点饼图改为环形图，中央显示原有作品总数；多观点重叠继续使用独立比例条。采用适配深浅主题的新色盘、分段间隙、分组折叠卡片及引文容器。
- 未新增描述性文案，保留原有统计帮助、分析来源、时长文案、原文展开与 `daily-viewpoint-pie` 内容描述。小比例分段保留可见弧线，单一分类不添加间隙，空数据使用主题色空环。
- 构建及测试 APK 编译通过，210 项 JVM 测试通过，lint 无错误；15 项现有相关界面测试通过，另以深色主题、150% 字号复测环形图和重叠观点用例，两项通过。
- 已检查浅色和深色大字号截图。日志与截图位于 `app/build/reports/viewpoint-distribution/`；安装包为 `app/build/outputs/apk/debug/app-debug.apk`。

## 词云 HTML 与 Gemini 参考优化（2026-10-11）

- 近期接触词云采用深海蓝、松石绿、暖金与靛蓝色盘，14–34sp 字号按真实记录数变化；增加主题适配的径向微光晕、层次透明度与高频词字重。小词透明度受文字对比度约束。
- 使用参考布局锚点、螺旋避让、空隙填充与整体重排；保留至少 48dp 的不重叠触控区域，窄屏及大字号完整显示词语并可滚动查看。
- 点击词语可回查当前时段的原文。统计和回查共用最新有效语义、时间窗口和记录去重逻辑，排除过期、修订、删除及仅原文包含关键词但模型未归类的记录。
- 构建、210 项 JVM 测试通过，lint 0 errors。概览及词云相关 15 项设备测试通过；最终重排和换行调整后再次运行全部 3 项词云用例通过。独立模拟器覆盖 360dp、深色 150% 字体、11 词可见性、触控区域无重叠和原文筛选。
- 已检查浅色、深色大字号和原文回查截图；测试日志及截图：`app/build/reports/word-cloud/`。参考词语只存在于测试夹具，未写入用户数据。APK：`app/build/outputs/apk/debug/app-debug.apk`。

## Gemini 界面建议落地（2026-10-11）

- 更新 5 个核心 UI 文件：柔和卡片、引用侧边条、渐变主按钮、胶囊操作、时间分段控件、发现页徽章及采集状态卡。
- 保留既有文案、`testTag` 与状态描述；按钮使用 Material 交互语义，点击区域至少 48dp，颜色适配深浅主题。采集状态灯仅在已选应用、开关开启、授权有效且服务连接时显示绿色。
- `:app:assembleDebug`、`:app:testDebugUnitTest`、`:app:assembleDebugAndroidTest`、`:app:lintDebug` 成功；209 项 JVM 测试通过，lint 0 errors、17 warnings。
- 独立 API 37 / arm64 / 16KB 页模拟器，以 360dp 宽运行 8 个受影响界面测试类，31 项通过（含新增按钮禁用及时间分段语义检查）。覆盖发现、记录、设置、回顾详情、音频入口、关键词、深色与 130% 字体。未操作既有模拟器或真机数据。
- 已检查浅色概览、回顾及深色大字号原文截图；验证日志与截图位于 `app/build/reports/gemini-ui/`。APK：`app/build/outputs/apk/debug/app-debug.apk`。
- 以下章节保留历史版本验证记录。

验证日期：2026-10-08（Asia/Shanghai）。保留 v0.3.0 / v0.3.1 / v0.4.0 基线记录；最新 v0.4.1 功能极简 2.0 验证见末节。

## 结果

- `assembleDebug`、`assembleDebugAndroidTest`、`testDebugUnitTest`、`lintDebug` 全部成功。
- JVM 单元测试：**38 项通过，0 失败**。
- Pixel 10 / API 37 / arm64 模拟器：**14 项通过，0 失败**。
- lint：**0 errors、10 warnings**。警告为旧 target SDK、可升级的现有运行依赖及 kapt→KSP 建议；没有本次新增的资源未使用警告。
- 首页、小时核查详情及多页中文 PDF 已检查截图，文字、分页和主要布局正常。
- `git diff --check` 通过。

## 环境与产物

macOS arm64，Android Studio bundled JDK 21.0.10，Gradle 8.9，AGP 8.5.2，Kotlin 2.0.21；字节码目标 17。minSdk 29，compileSdk / targetSdk 34。测试依赖使用 AndroidX Test runner 1.7.0、JUnit extension 1.3.0、Espresso 3.7.0，以兼容新系统输入服务。

安装包：`app/build/outputs/apk/debug/破茧-0.3.0-debug.apk`（调试签名）。

SHA256：`140caa28ee3d2fd9345b8faa34fc63c4e44335ae9d93397d8c08498f1b80c940`。

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest
```

模拟器覆盖安装目标与测试 APK，再直接运行 AndroidJUnitRunner；未使用会自动卸载目标应用的 connected 测试任务，未卸载或清空应用。未操作真实手机。

## 测试覆盖

| 范围 | 已验证行为 |
| --- | --- |
| 自然日与小时 | 今天当地午夜边界、24 个整点桶、跨小时及跨午夜时长切分、窗口截断、每条会话时长守恒 |
| 分母与未知 | 多观点不重复时长、旧记录保持未知、无可比时长时不显示伪造的 0% 占比 |
| 历史比较 | 浏览顺序、窗口外保留历史、同主题不同对象、不同条件、A/B/A 条件匹配优先级、数字/建议新增、未知归属 |
| 采集与删除 | 空白名单、旧版开启偏好、范围持久化与不可变集合、暂停/范围令牌失效、删除后不重插、旧采样失效、按应用删除保留其他应用 |
| 界面 | 小时条点击、跨午夜会话在覆盖小时可回查、当前与前序原文、全文搜索与删除目标 |
| 报告 | 多页中文 PDF 生成并逐页渲染、完整文本索引、原文与方法限制 |
| 升级 | 更名后的标签、旧 launcher alias 指向新 MainActivity、辅助服务兼容类与绑定保护、Room 1→2 保留旧记录 |

Room v2 JSON schema 与更名前完全一致，identity hash 保持 `777cfc8fd186e170116961bd7cbe45a6`。源码 namespace 改为 `com.emerge.app`；安装 ID、旧启动/服务组件和数据库文件名仅为保留安装与数据兼容。覆盖安装要求签名一致。

## 验证文件

- 单元测试：`app/build/reports/tests/testDebugUnitTest/index.html`
- lint：`app/build/reports/lint-results-debug.html`
- 设备测试：`app/build/reports/instrumentation-emulator.txt`
- 界面截图：`app/build/reports/pojian-home.png`、`pojian-hourly.png`
- PDF 首页面：`app/build/reports/pojian-report-page-1.png`

## 实际边界

规则与阈值尚未通过人工标注真实浏览数据校准；测试是工程回归，不能当作语义准确率、认知保护效果或真实注意力验证。跨应用节点覆盖、OEM 后台策略与文件选择器仍需按实际手机测试。

此功能基线尚未接入语音识别；v0.4.0 的音频与可选文字服务见末节。双路编码、交叉编码器、时序图学习、真实阅读区域校准、TEE 与远程证明尚未实现，详见 `docs/IMPLEMENTATION.md`。v0.3.x 完全离线，本地数据库尚无额外加密。已导出的外部文件由用户自行管理。

## Logo 接入验证（2026-10-08）

将 `docs/brand/pojian-logo-v1.png` 接入启动图标，提供五种屏幕密度的透明前景、独立背景以及 Android 13 单色主题图标。旧启动别名继续继承应用图标。

- `assembleDebug`、`lintDebug` 成功；lint 为 0 errors、10 warnings，没有新增图标警告。
- 已检查圆形、圆角方形、单色及 48px 本地预览，右上金色翼尖与茧形轮廓完整。
- 已检查实际 APK 中的 adaptive icon，背景、前景和 monochrome 资源均已打包。
- 本次资源更新的验证范围为构建、lint、APK 资源及本地裁切预览。

安装包：`app/build/outputs/apk/debug/破茧-0.3.0-logo-debug.apk`。

SHA256：`4df31c89a4f521b291d64ef3fcd9cc34daa2cac1ca750eb29c95e255a863cf5d`。


## v0.3.1 界面优化验证（2026-10-08）

依据用户选定的 Gemini 建议，统一暖纸白、深林绿、细边框与小圆角；新增系统深色主题。三页分别为概览、记录、控制。完整 24 小时时间带、按主题与候选分母区分的横条、可滚动原文核查面板、按日期及应用筛选、浏览目的草稿编辑均使用真实保留记录。示例中的观点立场和同源判断未加入程序。

- `assembleDebug`、`assembleDebugAndroidTest`、`testDebugUnitTest`、`lintDebug` 成功。
- JVM 单元测试 **38 项通过，0 失败**。
- 独立临时 Pixel 10 / API 37 / arm64 模拟器 **21 项设备测试通过，0 失败**，包含新增 7 项界面测试。
- lint **0 errors、10 warnings**，均为既有 SDK、依赖及 kapt 建议，无新增资源或界面警告。
- 交互覆盖：16 个应用的列表尾部可达、应用/关键词交集、精确删除、范围草稿取消/保存、空范围禁用开关、授权与连接状态、浏览目的保留自定义文本及取消、未知停留保持未知、深色 130% 字体原文核查。
- 跨午夜详情进一步验证候选显示该小时分摊的停留，避免误用整个窗口的时长。
- 已检查浅色、深色、130% 字体及原文核查截图；另在系统深色、320dp 宽与短屏、200% 字体下实测主界面和应用选择面板，键盘弹出后输入、取消和保存均可操作。紧凑面板保留系统栏安全区。
- `git diff --check` 通过。
- 仅在本次创建的独立临时设备运行测试，未操作既有模拟器或真实手机；测试数据仅存在于测试代码中。

安装包：`破茧-0.3.1-debug.apk`（versionCode 6，调试签名）。另存于 `app/build/outputs/apk/debug/破茧-0.3.1-debug.apk`。

SHA256：`a9e84ebf7bdbd35ca385042161770e686884fa02662db6a50b9d687c225a0814`。

验证产物：

- 全部设备测试：`app/build/reports/instrumentation-pojian-0.3.1.txt`
- 浅色概览：`app/build/reports/pojian-ui-overview-light.png`
- 深色与 130% 字体概览：`app/build/reports/pojian-ui-overview-dark-large.png`
- 深色原文核查：`app/build/reports/pojian-ui-evidence-dark-large.png`
- 短屏 200% 字体与键盘：`app/build/reports/pojian-native-picker-keyboard-final-200.png`

既有离线规则、采集范围、保留上限、删除与导出一致性及升级兼容机制保持有效；本轮工程验证不代表观点提取准确率或信息茧房干预效果。


## v0.4.0 音频优先实现验证（2026-10-08）

实现用户确定的“声音 → 本地小模型转文字 → 观点/依据与历史比较”路线。保留可见文字功能，增加音轨导入、单应用播放声音采集、分段时间、原始与修订文字、音频回顾、可选文字服务及 Room 2→3 迁移。没有图像捕获、视频编码或麦克风输入；原声不持久化。单次处理前 5 分钟，音频长度不计观看停留。

- `assembleDebug`、`assembleDebugAndroidTest`、`testDebugUnitTest`、`lintDebug` 成功，最终参数为 `-PemergeAbi=arm64-v8a`。
- JVM **53 项通过、0 失败、0 跳过**；独立 API 37 / arm64 / 16 KB 页模拟器 **46 项设备测试通过、0 失败、0 跳过**。
- Python 文字服务 **15 项通过**。全部使用本机假上游，不调用付费模型 API。
- lint **0 errors、11 warnings**：10 项既有 SDK/依赖/kapt 建议，加指定 arm64 构建缺少 ChromeOS x86 ABI 的提示；默认构建仍包含四种 ABI。
- `git diff --check` 通过。设备测试只在独立 `emulator-5562` 执行。回归后向既有 `emulator-5554` 覆盖升级，24 条历史记录保留；未卸载或清空。仅在应用私有目录暂存的升级备份已在核对后删除，未导出数据库或偏好设置。

### 真实音频链路

1. App 主动下载固定 revision 的四个模型文件（30,975,688 字节），13% 时取消无误标安装；重新下载完成、逐文件 SHA-256 通过、原子安装成功。
2. 上游公开中文 `0.wav` 实际经过设备 MediaExtractor 和 Zipformer，输出包含“对我做了介绍那么我想说的是大家如果对我的研究感兴趣”。时间偏移落在源音频范围内。
3. 将同一公开音频转码为 AAC，验证压缩音轨的 MediaCodec 解码与真实 ASR，修复 EOS 标志误判为加密轨道的问题。
4. `Runtime.events → AudioCoordinator → Room` 的真实导入、完成前转写持久化、媒体时长不计停留及删除级联通过仪器测试。
5. 使用只播放公开样例的独立 QA 播放器，验证系统授权、单 UID AudioPlaybackCapture、mediaProjection 前台服务、实际中文文字落库；暂停、继续与停止有效，停止后服务释放。

### 回顾、控制和迁移

本地分析覆盖旧日期记录、重复依据、新数字、历史时间顺序和精确原句；候选相似度只用于检索。服务端与手机均拒绝不存在的片段及杜撰引文。设备测试验证 AndroidKeyStore 令牌加密与重新读取、请求次数持久化、非法地址/凭据拒绝。云端默认关闭，只有用户点击音频回顾才发请求。

迁移保留旧 ID、文字和停留字段，音频关系采用外键级联。修订保留原始 ASR，清除本会话及后续相关分析；删除/清空/范围变更停止任务，generation 与 contentVersion 拒绝迟到写入。单一有序运行事件流确保最后稳定文字先于结束状态持久化。

音频 UI 测试覆盖模型门控、停止、修订保存/取消、删除确认、分析入口及错误可见。既有深色大字用例补正嵌套横向/纵向列表滚动，再真实点击小时按钮；标题与原句可见性断言均保留。

### 产物

安装包：`破茧-0.4.0-arm64-debug.apk`，versionCode 7，调试签名，仅 arm64-v8a。58,594,458 字节（约 55.9 MiB）；语音模型另行下载。

SHA-256：`9984531554ed208b5077ac3172b0f0b59e2b30e00bf6f4dae5e665f6400ecb23`。

- JVM 报告：`app/build/reports/tests/testDebugUnitTest/index.html`
- 全量设备记录：`app/build/reports/pojian-audio-instrumentation.txt`
- 真实 SAF 导入界面：`app/build/reports/pojian-audio-detail-light.png`、`pojian-audio-detail-dark-large.png`
- 服务测试：`app/build/reports/pojian-audio-server-tests.txt`
- 音频模型与复现说明：`docs/ASR-VALIDATION.md`
- 上游来源：`docs/THIRD_PARTY_AUDIO.md`

目标短视频平台真机内录兼容、10 分钟连续输入、20 段 CER/关键语义错误、内存温升耗电及语义分类 F1 尚未测量。测试播放器内录成功不能代替目标平台兼容结论；本轮工程测试不代表识别准确率、认知保护效果或真实注意力验证。原声回放、独立 VAD、自动跨视频归并和视觉理解尚未实现。

## v0.4.1 功能极简 2.0 适配（2026-10-08）

按用户提供的蓝白 HTML 参考实现原生 Compose 界面。底栏为分析、足迹、录制、设置；统一冷灰背景、白色细边框卡片、蓝色操作、无衬线字体及系统深色主题。常用操作前置，长说明折叠，原文预览可展开。

- 分析页使用真实记录数绘制 24 小时热图；未生成回顾也可按时段查原文。360dp 小屏为 6 列 × 4 行，点击区域至少 48dp，窄屏自动减少列数。
- 观点依据用蓝色新增、橙色重复和灰色其余观察组成比例条；首次、条件差异和未知保留在完整分母中。默认展示前三组说法，其余按需展开。
- 录制卡与独立录制页使用真实任务状态、已用时间、进度和转写文字，接入暂停、继续、停止；模型未就绪时仍禁止启动。不显示模拟声波或虚构分数。
- 足迹保留日期、来源、关键词筛选、完整原文及删除；设置保留采集范围、系统授权、模型及云端文字服务控制。音频偏移与浏览停留分别显示。

### 验证与产物

- `assembleDebug`、`assembleDebugAndroidTest`、`testDebugUnitTest`、`lintDebug` 成功，参数 `--offline -PemergeAbi=arm64-v8a`；JVM 53 项通过。
- 独立 API 37 / arm64 / 16KB 页模拟器先以 411dp、最终以 360dp 宽运行全量设备回归，均为 **47 项通过、0 失败、3 项跳过**；跳过项需语音模型与公开 WAV/AAC 夹具，本轮干净模拟器未安装。界面回归覆盖四页导航、跨午夜时长分摊、未知时长、模型门控、录制操作、原文核查、筛选/删除、迁移、持久化及深色 130% 字体。没有操作既有 `emulator-5554` 或真机；真实识别的既有验证见 v0.4.0 节。
- lint 0 errors、11 项既有/构建 ABI warnings；`git diff --check` 通过。

安装包：`破茧-0.4.1-arm64-debug.apk`，versionCode 8，调试签名，稳定 applicationId `com.zhiheng.app`，59,322,325 字节（约 56.6 MiB）。语音模型仍由用户另行下载。

SHA-256：`6758c94e0c7e8b5722d99c7da8963df226f36802def392042b16aaf41e6cef1d`。

设备记录：`app/build/reports/pojian-minimal-instrumentation.txt`。四页截图：`app/build/reports/pojian-minimal-analysis.png`、`pojian-minimal-footprints.png`、`pojian-minimal-recording.png`、`pojian-minimal-settings.png`；活动录制测试截图：`pojian-minimal-active-recording.png`。热图及深色大字截图使用测试夹具，未向生产代码或用户数据库写入示例数据。
