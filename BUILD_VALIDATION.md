# 破茧 Emerge v0.3.0 构建与验证

验证日期：2026-10-08（Asia/Shanghai）。

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
