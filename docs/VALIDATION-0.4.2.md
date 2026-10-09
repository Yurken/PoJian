# 0.4.2 验证记录

日期：2026-10-09。基线：e45b798，分支：dev/baijiamin。

## 构建与回归

使用本机 JDK 21、Android SDK 34、Gradle 8.9 构建；minSdk29、targetSdk34。执行 assembleDebug、assembleDebugAndroidTest、testDebugUnitTest、lintDebug，最终全部成功。

- JVM：92项，0失败。包含新增作品连续性、平台隔离、再次观看、过去24小时边界以及音频／视觉材料不制造作品数的回归。
- Lint：0错误，7警告。警告仍包括 SharedPreferences同步提交、截图属性版本、资源／ABI／kapt及浮窗触控可访问性等，未把警告全部关闭。
- Android专项：13项，全部通过。覆盖 WorkPersistenceTest、SessionMigrationTest、AudioPersistenceTest、RecordsGroupingUiTest、BrandCompatibilityTest 的录音服务权限方法、FunctionalMinimalUiTest 的跨午夜回看方法及 ReportPdfTest。
- 独立预览APK覆盖安装成功，MainActivity启动成功，进程存在且为前台活动；首页截图已检查。
- git diff --check 无空白格式错误。

上述Android测试用合成数据及独立测试数据库，不清空用户应用数据库。手动安装测试APK后直接运行指定instrumentation，不使用会卸载目标应用的connectedDebugAndroidTest任务。

## 原失败项处理

音频测试存在互相矛盾的旧契约：一个要求新依据引用早先材料，另一项要求新依据不能伪称匹配历史。现分别保存 historySegmentId/historyQuote（实际重复匹配）与 comparedSegmentId/comparedQuote（比较材料），界面明确区分，两组回归均通过。

截图回调移入RequiresApi(30)方法，Android10仍明确拒绝截图链路；裁剪矩形现在检查相交返回值。

前台服务类型在源码和合并Manifest中均已声明mediaProjection，但AGP8.5.2仍报ForegroundServiceType。只对专用启动方法局部抑制该项误报，不关闭全局检查；启动前查询已安装服务的类型，缺失即拒绝启动。Android专项测试实际验证安装后的mediaProjection声明、权限及非导出属性。此检查不代替真实平台的播放内录验收。

## 模拟器环境

Pixel_10，Android17/API37，x86_64。初始2GB内存时测试进程被系统终止；exit-info显示LOW_MEMORY，没有应用Java异常。保持原AVD数据，冷启动时临时增加至4GB后，13项专项全部通过。没有卸载、清空目标应用或改动OPPO设备。

## 交付身份与未验证事项

本次可下载APK为com.zhiheng.mainpreview，版本0.4.2/code9；沿用之前独立预览包签名及组件适配，可覆盖该预览包。正常工程applicationId仍为com.zhiheng.app，预览适配仅在忽略的构建配置目录，不提交到源码。

APK SHA256：3CF0A35721D23F82FF03A83F08FF06EC63D1006AC87F0CB1EDD71286816B3523。

未执行全套Compose仪器测试，未验证OPPO上的实际平台资源字段、背景存活、真实音频捕获、视觉模型及外部检索API。本轮不声称完整作品识别、分析准确率或耗电验收已经完成。请按WORK_VISITS.md的真机步骤验证；未知身份、倒序翻图、评论页和标题重复仍是当前限制。
