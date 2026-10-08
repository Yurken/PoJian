# 破茧 Emerge 构建与验证

验证日期：2026-10-08（Asia/Shanghai）。以下为 v0.3.0 功能基线记录，v0.3.1 界面优化验证见末节。

## 结果

- `assembleDebug`、`assembleDebugAndroidTest`、`testDebugUnitTest`、`lintDebug` 全部成功。
- JVM 单元测试：**38 项通过，0 失败**。
- Pixel 10 / API 37 / arm64 模拟器：**14 项通过，0 失败**。
- lint：**0 errors、10 warnings**。警告为旧 target SDK、可升级的现有运行依赖及 kapt→KSP 建议；没有本次新增的资源未使用警告。
- 首页、小时核查详情及多页中文 PDF 已检查截图，文字、分页和主要布局正常。
- `git diff --check` 通过。

## 环境与产物

macOS arm64，Android Studio bundled JDK 21.0.10，Gradle 8.9，AGP 8.5.2，Kotlin 2.0.21；字节码目标 17。minSdk 29，compileSdk / targetSdk 34。测试依赖使用 AndroidX Test runner 1.7.0、JUnit extension 1.3.0、Espresso 3.7.0，以兼容新系统输入服务。

安装包：`app/build/outputs/apk/debug/Emerge-0.3.0-debug.apk`（调试签名）。

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
- 界面截图：`app/build/reports/emerge-home.png`、`emerge-hourly.png`
- PDF 首页面：`app/build/reports/emerge-report-page-1.png`

## 实际边界

规则与阈值尚未通过人工标注真实浏览数据校准；测试是工程回归，不能当作语义准确率、认知保护效果或真实注意力验证。跨应用节点覆盖、OEM 后台策略与文件选择器仍需按实际手机测试。

多模态模型、双路编码、交叉编码器、时序图学习、真实阅读区域校准、TEE 与远程证明尚未实现，详见 `docs/IMPLEMENTATION.md`。当前程序完全离线，但本地数据库尚无额外加密。已导出的外部文件由用户自行管理。

## Logo 接入验证（2026-10-08）

将 `docs/brand/emerge-logo-v1.png` 接入启动图标，提供五种屏幕密度的透明前景、独立背景以及 Android 13 单色主题图标。旧启动别名继续继承应用图标。

- `assembleDebug`、`lintDebug` 成功；lint 为 0 errors、10 warnings，没有新增图标警告。
- 已检查圆形、圆角方形、单色及 48px 本地预览，右上金色翼尖与茧形轮廓完整。
- 已检查实际 APK 中的 adaptive icon，背景、前景和 monochrome 资源均已打包。
- 本次资源更新的验证范围为构建、lint、APK 资源及本地裁切预览。

安装包：`app/build/outputs/apk/debug/Emerge-0.3.0-logo-debug.apk`。

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

安装包：`Emerge-0.3.1-debug.apk`（versionCode 6，调试签名）。另存于 `app/build/outputs/apk/debug/Emerge-0.3.1-debug.apk`。

SHA256：`a9e84ebf7bdbd35ca385042161770e686884fa02662db6a50b9d687c225a0814`。

验证产物：

- 全部设备测试：`app/build/reports/instrumentation-emerge-0.3.1.txt`
- 浅色概览：`app/build/reports/emerge-ui-overview-light.png`
- 深色与 130% 字体概览：`app/build/reports/emerge-ui-overview-dark-large.png`
- 深色原文核查：`app/build/reports/emerge-ui-evidence-dark-large.png`
- 短屏 200% 字体与键盘：`app/build/reports/emerge-native-picker-keyboard-final-200.png`

既有离线规则、采集范围、保留上限、删除与导出一致性及升级兼容机制保持有效；本轮工程验证不代表观点提取准确率或信息茧房干预效果。
