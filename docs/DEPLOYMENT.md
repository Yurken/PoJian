# 破茧（pojian）后端部署

部署工具适用于有 systemd、Python 3.10+ 和 root（或免密 sudo）权限的 Linux 主机。后端仅依赖 Python 标准库。

## 选择服务器

部署时显式传入 `SSH_HOST`，可以是自己配置的 SSH 别名、主机名或 `user@host`。本机别名、IP、私钥路径及个人自动部署授权应保存在个人 SSH/Codex 配置中，不能成为共享仓库的默认设置。

```sh
bash scripts/deploy-backend.sh YOUR_SSH_HOST
```

SSH 端口和身份文件通过自己的 `~/.ssh/config` 配置；先验证 `ssh YOUR_SSH_HOST` 可用。

## 服务与配置

- 服务：`pojian-backend.service`，专用用户 `pojian`，开机自启、异常退出自动重启。
- 后端：`127.0.0.1:28763`。
- 代码：`/opt/pojian-backend/releases/<发行版>/server/`，`current` 与 `previous` 链接管理版本。
- 配置：`/etc/pojian/backend.env`，root 所有、权限 `600`。
- 状态：`/var/lib/pojian-backend/monitor-budget.sqlite3`，独立于代码版本，更新不会清空额度。

首次部署前，按 [配置示例](../deploy/linux/backend.env.example) 创建远程配置。`EMERGE_SERVICE_TOKEN` 需另行生成，用于手机访问网关；模型 API Key 只放在服务器配置中。不要提交真实配置或将密钥写入日志。

模型 API 的 `EMERGE_UPSTREAM_URL` 必须是完整 Chat Completions 请求地址，通常由供应商基础 URL 追加 `/chat/completions`；填写实际可用的模型名称和 Key。图片分析另填 `EMERGE_VISION_MODEL`，视觉 URL/Key 未填时继承文字服务；外部证据检索另填 `TAVILY_API_KEY`。

需要以后再配置 API 时，可显式设置 `EMERGE_ALLOW_UNCONFIGURED=1` 并留空模型 Key。健康与能力接口会返回 `model_configured=false`；未认证分析返回 `401`，认证后返回 `503`，不调用模型或消耗监测额度。服务令牌仍必须配置。填写真实 API 配置后可改为 `EMERGE_ALLOW_UNCONFIGURED=0` 并重启服务，重新验证真实调用。

## 更新与验证

部署脚本打包当前工作区后端，不要求先提交；排除密钥、数据库、运行目录和缓存。远程执行全部后端测试、验证配置后才切换版本并重启，随后检查健康、协议、认证及未配置模式。失败时恢复上一版本和服务配置。

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s server/tests -v
bash scripts/deploy-backend.sh YOUR_SSH_HOST
ssh YOUR_SSH_HOST 'systemctl status pojian-backend.service --no-pager'
ssh YOUR_SSH_HOST 'journalctl -u pojian-backend.service -n 30 --no-pager'
```

## HTTPS 入口

在已有有效证书的 HTTPS 站点上添加独立 `/pojian/` 路由：

```sh
bash scripts/configure-backend-nginx.sh YOUR_SSH_HOST YOUR_DOMAIN /etc/nginx/sites-available/YOUR_SITE
```

工具使用 [Nginx 路由模板](../deploy/linux/nginx-location.conf)，只更新本项目 snippet 与选定 HTTPS server 块的 include；先备份，再配置检查、重载与验证，失败恢复。域名解析和 TLS 证书须事先可用，保留其他站点路由。必要时可从本项目旧 `/zhiheng/` include 迁移。

手机网关地址填写 `https://YOUR_DOMAIN/pojian`，不追加 `/monitor`，访问令牌填写服务器的 `EMERGE_SERVICE_TOKEN`。上线后验证 `/pojian/health`、`/pojian/capabilities`、未授权 `401`；未配置模式还应验证认证后 `503`，有真实 API 后再进行模型联调。
