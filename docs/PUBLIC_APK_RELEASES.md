# 公开 APK 仓库发布

源码仓库 `baijiamin/PoJian` 保持私有；公开下载仓库为 [baijiamin/PoJian-Releases](https://github.com/baijiamin/PoJian-Releases)。公开仓库只保存发布说明、签名 APK、更新清单及可选差分，不推送源码、签名私钥或发布令牌。

App 更新地址为 `https://github.com/baijiamin/PoJian-Releases/releases/latest/download/update.json`。清单内 APK 和补丁采用具体版本的下载地址，避免检查后 Latest 变化导致摘要不匹配。App 无须 GitHub 令牌。

## 一次性令牌配置

1. GitHub → Settings → Developer settings → Personal access tokens → Fine-grained tokens → Generate new token。
2. Resource owner 选择 `baijiamin`；Repository access 选择 Only select repositories，仅勾选 `PoJian-Releases`。
3. Repository permissions 中 Contents 选择 Read and write，Metadata 保持只读。设置有效期并在到期前更换。
4. 生成后直接在**私有源码仓库 PoJian** → Settings → Secrets and variables → Actions → New repository secret 中保存，名称为 `APK_RELEASE_TOKEN`。不要保存到公开仓库，不要发到聊天。

可选 Repository variable `APK_RELEASE_REPOSITORY` 默认 `baijiamin/PoJian-Releases`。现有固定签名 Secrets 和服务配置保持不变。R2 开通及令牌不再需要。

默认 GITHUB_TOKEN 的权限限定于运行工作流的仓库，跨仓库写 Release 需要上述专用令牌：[官方说明](https://docs.github.com/en/actions/concepts/security/github_token)、[细粒度令牌](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens)。

## 发布与迁移

main 本次提交具有正确的 `v<versionName>` Tag 时，流水线先检查 `APK_RELEASE_TOKEN`、目标仓库公开状态及写入权限，再构建固定签名 APK。令牌缺失、仓库不可访问或没有写入权限时直接失败，不再将跳过公开发布报告为成功。没有 Tag 的提交仍构建并上传 APK artifact，跳过两处 Release 发布，不自动创建 Tag。

构建及签名验证成功后，先向公开仓库发布，再保留源码仓库 Release。公开 Release 先保持 draft，APK、可选补丁和清单全部上传后才公开为 Latest；随后重新读取清单并检查 APK／补丁资产的大小与 GitHub 返回的摘要。全部检查通过后才记录发布成功及公开 Release 链接。修正 Secret 后可重新运行失败的流水线；已经完整发布的相同 APK 可幂等重试，不覆盖已发布版本的不同内容。

维护者可使用有目标仓库写入权限的凭据先检查配置：`GH_TOKEN=... APK_RELEASE_REPOSITORY=baijiamin/PoJian-Releases python3 scripts/publish_public_apk.py --check`。`--check` 只读取仓库信息，不发布或修改 Release。

差分沿用 `pjdiff1`，只对紧邻上一版且同签名的 APK 生成；还原摘要验证通过且至少节省20%时提供。客户端基础包或补丁不匹配时下载完整 APK。首次版本仅有全量 APK。

旧 App 若仍访问网关 `/app-update`，需要部署新的服务器代码并设置 `EMERGE_APP_UPDATE_PUBLIC_REPO=baijiamin/PoJian-Releases`。网关匿名读取公开 Release，返回具体版本的公开 APK 链接；旧下载端点重定向，不再转发大文件。未更新服务器的旧 App 可以手动下载同签名新 APK 覆盖安装。新 APK 已内置公开更新地址。

公开仓库解决匿名下载和分发问题，文件仍由 GitHub 提供，不能保证改善所有地区的速度。APK 可被反编译，源码仓库私有不意味着 APK 中的客户端配置保密；服务端不应将客户端共享令牌作为强身份凭据。

验证项目：首次公开发布、紧邻版本差分、跨版本全量、损坏补丁回退、固定签名覆盖安装、公开地址匿名访问，以及私有源码仍不可匿名访问。
