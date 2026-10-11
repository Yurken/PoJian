# 破茧：语义、视觉与外部证据网关

Android 客户端不直接保存模型密钥。网关接收用户明确开启的文字、稳定口播或关键画面，进行结构化与外部证据核查。Python 3.10+，仅使用标准库。

Linux 部署使用服务名 `pojian-backend.service`，端口 `28763`。运行 `bash scripts/deploy-backend.sh YOUR_SSH_HOST` 时显式指定自己的 SSH 主机，配置与 HTTPS 入口见 [后端部署说明](../docs/DEPLOYMENT.md)。

## 启动新版服务

```sh
export EMERGE_UPSTREAM_URL='https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions'
export EMERGE_UPSTREAM_MODEL='你的文字模型名称'
export EMERGE_UPSTREAM_API_KEY='你的文字模型密钥'
export EMERGE_SERVICE_TOKEN='自行生成的高强度访问令牌'
# 视觉使用兼容 Chat Completions 图像输入的模型；三项可与文字提供方不同。
export EMERGE_VISION_URL="$EMERGE_UPSTREAM_URL"
export EMERGE_VISION_MODEL='你的视觉模型名称'
export EMERGE_VISION_API_KEY='你的视觉模型密钥'
# 检索优先使用 Tavily（要求取得 raw_content 正文）；不配置时回退到文字模型的联网搜索
# （enable_search 仅用于发现 URL，正文由服务端自行抓取）。
export TAVILY_API_KEY='你的检索密钥'
export EMERGE_MONITOR_DAILY_LIMIT=200
python server/monitor_server.py
```

默认监听 `127.0.0.1:8787`，对外使用受控的 HTTPS 反向代理。手机填写可达的网关根地址，不包含 `/monitor`，令牌填写 `EMERGE_SERVICE_TOKEN`，不是模型 API Key。仅开发调试允许符合既有 EndpointPolicy 的本地 HTTP；不要在不受信网络明文传输。

如需先部署、以后再配置模型 API，可显式设置 `EMERGE_ALLOW_UNCONFIGURED=1`；服务访问令牌仍须填写。此时健康与能力接口返回 `model_configured=false`，认证后的分析接口返回 `503`，不会调用模型或消耗监测额度。之后可直接在 `/admin` 页面填写模型与密钥（即时生效、无需重启），或填写环境变量后重启；未设置该开关且无持久化配置时，缺少模型密钥仍会拒绝启动。

手机开启云端分析后，当前采集授权范围内的文字与播放音频转写会自动进入 `/monitor`，并补处理最近 24 小时尚未分析的记录。实时事实核查只控制 `check_facts`，不再控制观点分析的发送；关键画面分析仍单独开启。客户端不设置云端或画面请求额度，保留请求计数，额度由服务端管理。原有手动音频回顾继续使用 `/analyze`；纯旧服务 `python server/emerge_server.py` 仍可运行，但不提供 `/monitor`，不能用于新版实时核查。

## 接口及限制

- `GET /health`：存活检查，保留旧协议，公开。
- `GET /capabilities`：返回监测协议以及视觉、检索是否配置；这是配置状态，不是效果验证；公开。
- `GET /status`：只读运行状态 JSON，含请求计数、状态码分布、各接口平均/最大耗时、今日额度用量与脱敏后的 API 设置；**须 Bearer 服务令牌**，不返回完整密钥和请求内容。
- `GET /admin`：自包含的中文管理台页面（令牌登录后可用），每 5 秒轮询 `/status`；纯旧服务 `emerge_server.py` 同样提供这两个入口，但只含文字模型信息、无配置管理。
- `POST /config/update`（仅监测服务，须令牌）：更新生效中的文字/视觉/检索配置与每日限额，校验后原子写入状态目录的 `runtime-config.json`（0600）并即时生效，无需重启；密钥字段留空表示保持不变。
- `POST /config/profile/save|apply|delete`（须令牌）：命名保存当前配置、应用或删除已保存配置（最多 10 套）；应用后即时生效。接口响应与 `/status` 中的密钥始终只显示尾四位。
- `POST /monitor`：Bearer 令牌认证；`version=1`、`id`、`kind=text/audio/frame`、`text`、`captured_at`、`check_facts`；画面额外传 `image`（JPEG base64，不接受外部图片 URL）。
- `POST /analyze`：旧的转写与历史比较协议保持不变。

文字最多 6000 字，JPEG 最多 180 KiB，整个请求最多 512 KiB。客户端只上传当前可见画面采样，不自动抓取分享链接。每次抽取最多三条声明，最多对一条事实声明执行外部搜索和证据对齐；其他候选返回 `not_checked` 并说明预算。总体处理设有截止时间和并发上限。

输出包括 `observed_text`、结构化 `claims`、明确 `verdict`、解释、`sources` 和覆盖说明。来源 URL 必须来自检索返回，连续引文必须存在于取回的正文，客户端会再次校验。只取得搜索摘要时不判真伪。缺少检索配置、取证失败、引文不匹配、归属不明分别保留未核查或证据不足。

结构观点增加可选 `evidence_actors`（案例当事对象）和 `evidence_origins`（原始材料名称）两列。每列最多 4 个字符串，每项非空、不重复、不超过 80 字符，且必须逐字连续出现在本条 `evidence` 中；不能从其他声明或整页文字补身份。旧模型响应缺字段时补空列表，非法字段拒绝整次抽取。字段用于回查材料关系，不证明人物识别正确、事实真实或来源独立。

## 调用成本与数据

一次客户端请求不是一次付费调用：至多包含一次语义或视觉抽取、一次 Tavily 检索、一次文字证据对齐；费用取决于各自的实际计费。手机共享请求限额与画面子限额另外设置，失败请求也消耗已预留额度。

服务端总监测请求额度使用 SQLite 持久化，默认 `server/.runtime/monitor-budget.sqlite3`，按 UTC 日历日计数；手机额度按设备本地日期计数。可用 `EMERGE_BUDGET_DB` 指定路径。额度在并发和进程重启后仍有效，不记录内容正文。多进程部署需共用同一数据库路径；删除额度数据库会重置计数，不能将其当作完整计费系统。

模型和检索服务会收到必要的请求内容，使用自己的 API 不代表没有第三方传输。服务不写请求正文、截图或密钥日志；仍应妥善配置反向代理日志、访问权限和保留策略。外部文档、截图和转写都被当作不可信数据；提示词约束不是已证明的提示注入防御。

管理台与 `/status`、`/config` 接口使用服务令牌或管理台登录会话鉴权（`Authorization: Bearer …`），页面登录态仅保存在浏览器会话中。管理台使用账号密码登录（`POST /admin/login`，`EMERGE_ADMIN_USER` / `EMERGE_ADMIN_PASSWORD`，未设置密码时回退为服务令牌；会话 12 小时过期，连续 5 次失败锁定 30 秒）。页面保存的模型密钥写入状态目录的 `runtime-config.json`（0600，优先级高于环境变量）；任何接口都不回传完整密钥。`POST /config/test` 可对文字、视觉、检索分别做连通性测试（真实调用一次提供方，返回延迟或错误）。服务令牌请使用足够长且随机的值（建议 20 字符以上），它保护分析接口，也是管理台密码的默认值。

## 测试

```sh
python -m unittest discover -s server/tests -v
```

当前服务端 127 项测试使用确定性假模型和假检索，并通过真实本地 HTTP 服务验证认证、协议、证据引用、身份字段、预算和旧接口兼容。它们不证明真实 API 的准确率、延迟或可用性。接入自己的 API 后，应先用有明确原始来源的少量图文和口播材料检查引文、条件、费用和错误状态。

实现与 Android 限制见 [实时监测说明](../docs/REALTIME_MONITORING.md)。
