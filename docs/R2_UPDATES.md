# Cloudflare R2 自动更新配置

GitHub Actions 构建并验证固定签名 APK，先备份到本仓库 GitHub Release，再上传 R2。App 从 `updates/latest.json` 检测版本，直接从公开 R2 域名下载。上传密钥只存于 GitHub Secrets，不进入 APK。未配置完整 R2 参数时，CI 直接失败，不回退到其他分发入口。

## 1. 创建存储桶和公开下载地址

在 Cloudflare 控制台开通 R2 Object Storage，创建默认辖区的存储桶，例如 `pojian`。本流水线使用默认 S3 端点；不要选择 EU/US/FedRAMP 专属辖区。

当前使用桶 `pojian` 的 **Settings → Public Development URL → Enable**，公开地址为 `https://pub-6ad0b56b335240c696618fb628a56ef5.r2.dev`，不需要自定义子域名。该地址有访问限流。桶仅用于公开安装包与更新清单。

也可在 Settings → Custom Domains 绑定自己的下载域名。若启用自定义域名缓存，对 `/updates/latest.json` 绕过缓存；版本文件可遵循对象 Cache-Control 长期缓存。

## 2. 创建上传令牌

R2 Overview → Account Details → API Tokens → Manage → Create API token。权限选择 **Object Read & Write**，只限定 `pojian` 桶。保存 **Access Key ID** 和 **Secret Access Key**；本流水线使用 S3 密钥，不使用普通 Cloudflare REST API Bearer Token。Account ID 从 R2 控制台取得。密钥不要发送到聊天、提交到 Git 或放进 App。

官方说明：[R2令牌](https://developers.cloudflare.com/r2/api/tokens/)、[公开存储桶](https://developers.cloudflare.com/r2/buckets/public-buckets/)、[R2缓存](https://developers.cloudflare.com/cache/interaction-cloudflare-products/r2/)。

## 3. GitHub 配置

打开 [仓库 Actions 配置](https://github.com/Yurken/PoJian/settings/secrets/actions)。

Repository secrets：

| 名称 | 值 |
| --- | --- |
| `R2_ACCESS_KEY_ID` | R2 的 Access Key ID |
| `R2_SECRET_ACCESS_KEY` | R2 的 Secret Access Key |

Repository variables（Variables 页签）：

| 名称 | 值 |
| --- | --- |
| `R2_ACCOUNT_ID` | Cloudflare Account ID，32位十六进制 |
| `R2_BUCKET` | `pojian`，或实际桶名 |
| `R2_PUBLIC_BASE_URL` | `https://updates.example.com`，不附加 `/updates/latest.json` |

原有固定签名 Secrets 和 `POJIAN_SERVICE_TOKEN` 保持不变。先填好两个 Secrets、Account ID 与公开地址，最后设置 `R2_BUCKET`，避免中间配置触发不完整发布。

## 4. 首次发布和旧版迁移

配置后，对带有正确 `v<versionName>` Tag 的 main 提交运行 Build APK。没有同名且指向本提交的 Tag 时，只有构建产物，不发布 GitHub Release 或 R2。

首次上传产生完整 APK 和清单，不产生差分。访问 `https://<下载域名>/updates/latest.json`，确认 APK 地址可公开下载，并核对清单的 versionCode、sha256。

新构建 App 使用 R2 清单地址。已安装的 GitHub 或网关版本需要手动覆盖安装首个 R2 版本一次，之后直接使用 R2 更新；不再维护旧下载入口。GitHub Release 备份同一份 APK 与版本元数据，并保留历史版本，不受 R2 三版本清理影响。保留固定签名及 applicationId，不需要卸载旧应用。

## 5. 增量更新

发布器读取 R2 中上一版 APK，对未改变的压缩 ZIP 数据生成 COPY 指令，其余部分生成 DATA 指令，签名块和文件目录按目标 APK 原始字节保留。CI 先还原差分并验证整个 APK 的 SHA-256，仅在差分比完整 APK 小至少20%时发布。格式为 `pjdiff1`，流式处理，不需要新增原生库。

App 仅在已安装 versionCode 和 APK 哈希与差分基础版本一致时使用差分；校验差分摘要、还原完整 APK，再校验完整摘要、包名、版本与固定签名。基础包不匹配、补丁损坏或还原失败时下载完整 APK。取消不会触发回退下载。系统安装仍由用户确认，增量不是静默安装，也不是热更新。

仅生成上一版到当前版的补丁；跨多个版本、进程恢复后丢失补丁元数据、没有差分支持的旧客户端均走完整包。只读安装目录不会被修改。压缩参数、依赖和 native 库变化较大时差分不一定更小。保留上一版 APK，避免生命周期规则将下一次差分生成所需的包提前删除。

## 6. 验证

流水线依次上传不可变 APK、可选补丁，最后写清单；任何上传/还原失败都不更新清单。清单拒绝版本倒退和同 versionCode 的不同 APK，重试相同已发布版本保持幂等。主分支发布使用现有工作流并发锁。发布后使用 `PoJian-Updater/1.0` 请求标识匿名读取公开清单并完整下载 APK，校验 SHA-256 与文件大小，验证失败则 CI 失败且不清理历史包。

公开下载校验成功后，仅保留最近 3 个版本的 APK，清理更早版本的 APK 及对应 `.pjdiff` 增量包。按数值 versionCode 排序，保留当前清单指向的 APK；不删除清单、无关文件或版本号更高的并行上传文件。清理失败会使 CI 失败并可重试。

完成配置后验证首次全量更新、紧邻版本差分更新、跨版本全量更新、补丁下载失败回退、取消下载、签名不匹配拒绝、断网恢复及更新后本地数据保留。实际下载加速程度需在手机网络上测量，不能仅凭换存储服务保证速度。
