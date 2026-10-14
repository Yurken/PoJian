# 应用更新

应用通过 Cloudflare R2 分发 APK 和可选增量包，配置见 [R2 自动更新](R2_UPDATES.md)。GitHub Actions 负责构建与固定签名校验，不再发布 GitHub Release 更新包。

正式 APK 默认从 `https://pub-6ad0b56b335240c696618fb628a56ef5.r2.dev/updates/latest.json` 检查更新。CI 使用 `R2_PUBLIC_BASE_URL` 注入 `pojian.r2PublicBaseUrl`，让构建地址与上传地址一致。Release 不接受 Debug 的 `pojian.updateManifestUrl` 覆盖；Debug 可通过该属性或本地配置覆盖或关闭检查。

更新清单、APK 和补丁均支持匿名 HTTPS 下载，R2 上传密钥只存于 Actions Secrets，不进入 APK。下载后校验摘要、包名、版本和固定签名，再调用系统安装界面。

已安装的旧版若使用 GitHub 或网关更新地址，需要手动安装首个 R2 版本一次，此后自动检查 R2。旧发布记录不删除，但不再续发；本次不维护旧入口兼容。

App 启动／返回前台时检查，成功后 6 小时内不重复检查，网络或数据错误后至少间隔 15 分钟重试。自动检查失败保持静默。「稍后」关闭本次提示；更高 `versionCode` 才会触发提示，版本名称只用于显示。无后台定时任务。

接口返回示例（域名与哈希仅为占位，发布时替换为真实 APK 信息）：

```json
{
  "versionCode": 28,
  "versionName": "0.5.6",
  "apkUrl": "https://updates.example.com/PoJian.apk",
  "sha256": "0000000000000000000000000000000000000000000000000000000000000000"
}
```

四个字段均必填；`versionCode` 为正整数、`versionName` 为 APK 的真实版本名、`sha256` 为 APK 文件的 64 位十六进制 SHA-256。相同或更低版本直接忽略。接口上限 64 KiB，APK 上限 512 MiB；下载只接受 HTTPS（含每一跳重定向），最多 5 次重定向，禁止 URL 内嵌账号密码，不附带业务服务令牌。新版本的 APK、版本号及摘要应同时发布。

用户选择「立即更新」后下载到私有缓存，可取消或重试。校验摘要、包名、实际版本、最低系统版本及固定签名一致后显示「安装」。点击安装时重新校验文件，再通过限定目录的 FileProvider 将只读 URI 交给系统安装器；缺少安装来源授权时进入系统授权页，返回后继续。旋转屏幕保留下载任务，进程被系统回收时中断下载；已有提示和已下载文件通过 Activity 保存状态恢复。系统安装结果由系统展示，取消后可以再次安装或选择稍后。

必须保持 `applicationId = com.zhiheng.app`、递增 `versionCode` 并使用同一固定签名。项目从 `0.5.4` 起共用固定签名，配置见 [Android 固定签名](ANDROID_SIGNING.md)；不能保证此前临时签名旧版直接覆盖。不同签名的更新包会被拒绝，自动更新逻辑不会卸载旧应用。缓存文件在后续下载时清理超过 24 小时的旧文件；没有申请公共存储访问权限。

安装授权和文件共享依据 Android 官方接口：[安装来源授权](https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls())、[FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider)。
