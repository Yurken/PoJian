# GitHub Release 安装包备份

带有匹配 `v<versionName>` Tag 的主分支构建，会先把固定签名的 `PoJian.apk` 和版本元数据 `update.json` 备份至当前源码仓库的 GitHub Release，再向 R2 发布同一份 APK。GitHub Release 不执行三版本清理，R2 仅在新包公开下载校验成功后保留最近 3 个版本。

流水线使用内置 `github.token` 和 `contents: write` 权限，复用推送时创建的同名 Tag，不另建或移动 Tag。无匹配 Tag 的构建只上传 Actions 产物。

App 检查更新和下载仍使用 [R2](R2_UPDATES.md)。原独立公开仓库发布方案不再使用，`APK_RELEASE_TOKEN`、`APK_RELEASE_REPOSITORY` 不参与本流程。
