# 破茧：语义、视觉与外部证据网关

Android 客户端不直接保存模型密钥。网关接收用户明确开启的文字、稳定口播或关键画面，进行结构化与外部证据核查。Python 3.10+，仅使用标准库。

Linux 部署使用服务名 `pojian-backend.service`，端口 `28763`。运行 `bash scripts/deploy-backend.sh YOUR_SSH_HOST` 时显式指定自己的 SSH 主机，配置与 HTTPS 入口见 [后端部署说明](../docs/DEPLOYMENT.md)。

## 启动新版服务

```sh
export EMERGE_UPSTREAM_URL='https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions'
export EMERGE_UPSTREAM_MODEL='你的文字模型名称'
export EMERGE_UPSTREAM_API_KEY='你的文字模型密钥'
# 可选：首次启动的快速文本模型；未设置时沿用已配置的文字模型。
# 管理台“快速文本模型”可独立配置接口、模型和密钥，保存后覆盖此环境变量。
# export EMERGE_CLEANUP_MODEL='你的快速文字模型名称'
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

快速文本配置用于 `/capture-cleanup`。未单独配置时，沿用已有文字接口、模型及密钥；官方 DashScope 的 Qwen 混合思考模型明确发送 `enable_thinking=false`，其他提供方沿用其兼容参数，不擅自替换模型。管理台支持即时修改、连通性测试、保存配置方案和“沿用文本配置”重置；既有运行时配置和旧方案缺少快速文本字段时自动继承文字配置。`/config/update` 字段为 `fast_text_url`、`fast_text_model`、`fast_text_key`，空字符串保持已有覆盖值，`-` 清除覆盖；密钥仅脱敏展示，独立接口换域名时须提供对应密钥。

如需先部署、以后再配置模型 API，可显式设置 `EMERGE_ALLOW_UNCONFIGURED=1`；服务访问令牌仍须填写。此时健康与能力接口返回 `model_configured=false`，认证后的分析接口返回 `503`，不会调用模型或消耗监测额度。之后可直接在 `/admin` 页面填写模型与密钥（即时生效、无需重启），或填写环境变量后重启；未设置该开关且无持久化配置时，缺少模型密钥仍会拒绝启动。

手机开启云端分析后，当前采集授权范围内的文字与播放音频转写会自动进入 `/monitor`，并补处理最近 24 小时尚未分析的记录。实时事实核查只控制 `check_facts`，不再控制观点分析的发送；关键画面分析仍单独开启。客户端不设置云端或画面请求额度，保留请求计数，额度由服务端管理。原有手动音频回顾继续使用 `/analyze`；纯旧服务 `python server/emerge_server.py` 仍可运行，但不提供 `/monitor`，不能用于新版实时核查。

## 接口及限制

- `POST /capture-cleanup`：保存新采集文字前的清理，须 Bearer 服务令牌，复用监测并发池和每日额度。请求 `{version:1,text:...}` 最多 16000 字、256 行；只返回原文哈希、删除行号及严格限定的控件后缀，不生成替代正文。上游限时 2.5 秒，App 总等待上限 3 秒，成功结果按采集授权缓存 5 分钟；故障后 10 秒内使用本地规则。断网、旧版网关、关闭云端、超时和无效结果均在本地清理后保存。不会回写已有记录，也不处理音频转写。部署需同时更新 App 和本服务。Qwen 非思考参数参考[官方文档](https://www.alibabacloud.com/help/en/model-studio/deep-thinking)。

- `GET /health`：存活检查，保留旧协议，公开。
- `GET /capabilities`：返回监测协议以及视觉、检索是否配置；这是配置状态，不是效果验证；公开。
- `GET /status`：只读运行状态 JSON，含请求计数、状态码分布、各接口平均/最大耗时、今日额度用量与脱敏后的 API 设置；**须 Bearer 服务令牌**，不返回完整密钥和请求内容。
- `GET /status` 的 `fact_checks` 按抽取、检索、证据分析、引文校验阶段统计错误类型，也统计正常结论；不记录声明、网页内容或提供方原始响应，重启后计数清零。
- `GET /status` 的 `provider_calls` 保留最近 100 次上游调用的阶段、密钥序号（从 0 开始）、耗时、等待预算、错误码、实际上游 HTTP 状态及结果数量；本地网络超时的 HTTP 状态为空。不包含密钥、查询、网址和正文；序号对应调用时的配置顺序，修改配置后不能当作同一密钥的永久标识。
- `GET /app-update`：应用内自动更新的 JSON 清单（`versionCode`、`versionName`、`apkUrl`、`sha256`），由服务端用 `EMERGE_APP_UPDATE_TOKEN` 从私有仓库的最新 GitHub Release 代理生成，60 秒缓存；公开，不携带也不接受服务令牌。
- `GET /app-update/download`：安装包字节流（`PoJian.apk`），同样从私有 Release 代理，服务端持有令牌、客户端不持有；公开。令牌未配置时两个端点返回 404。

配置 `EMERGE_APP_UPDATE_PUBLIC_REPO=baijiamin/PoJian-Releases` 后，网关匿名读取公开下载仓库的 Release，返回具体版本的 APK 地址，下载端点重定向至公开仓库，不再转发 APK。旧版 R2 配置保留兼容，但公开仓库配置优先。配置见 [公开 APK 发布](../docs/PUBLIC_APK_RELEASES.md)。
- `GET /admin`：自包含的中文管理台页面（令牌登录后可用），每 5 秒轮询 `/status`；纯旧服务 `emerge_server.py` 同样提供这两个入口，但只含文字模型信息、无配置管理。
- `POST /config/update`（仅监测服务，须令牌）：更新生效中的文字/视觉/检索配置与每日限额，校验后原子写入状态目录的 `runtime-config.json`（0600）并即时生效，无需重启；密钥字段留空表示保持不变。
- `POST /config/profile/save|apply|delete`（须令牌）：命名保存当前配置、应用或删除已保存配置（最多 10 套）；应用后即时生效。接口响应与 `/status` 中的密钥始终只显示尾四位。
- `POST /monitor`：Bearer 令牌认证；`version=1`、`id`、`kind=text/audio/frame`、`text`、`captured_at`、`check_facts`；画面额外传 `image`（JPEG base64，不接受外部图片 URL）。
- `POST /analyze`：旧的转写与历史比较协议保持不变。

文字最多 6000 字，JPEG 最多 180 KiB，整个请求最多 512 KiB。客户端只上传当前可见画面采样，不自动抓取分享链接。每次抽取最多三条声明，最多对一条事实声明执行外部搜索和证据对齐；其他候选返回 `not_checked` 并说明预算。总体处理设有截止时间和并发上限。

首轮模型同时完成观点结构化与事实初筛，不额外增加一次模型调用。文字／口播使用当前配置的文字模型（本轮线上配置为非思考模式 Qwen3.8 Flash），图片仍使用视觉模型。开启核查时先通读完整输入，区分事实与看法，优先选取可独立核查的事实；纯喜好、情绪、建议和价值判断保留为观点，不进入检索。“我觉得”等措辞本身不决定是否核查，其中明确的日期、事件、数据等仍可成为事实候选。

每条声明增加 `check_quote`：从该条 `quote` 中逐字连续选取最多 240 字的最短完整事实原句，去掉无关评价、重复和铺垫，保留主体、否定、数字、单位、时间、条件和归属。检索仅使用短句加必要 `conditions`（最多 200 字，总长度不超过 441 字），不发送整段评论，也不截断末尾条件。`quote` 保留原句；证据模型同时收到短句、原句及条件，若精简改变含义，提示其返回 `insufficient`。缺失／空短句保留 `not_checked`，不回退检索整段；不在原句中、超长或类型不合法的短句拒绝该次模型输出。归属不明确的声明保留 `insufficient` 并跳过取证。新字段可被旧客户端忽略，关闭核查时只提取观点结构。

输出包括 `observed_text`、结构化 `claims`、明确 `verdict`、解释、`sources` 和覆盖说明。来源 URL 必须来自检索返回，连续引文必须存在于取回的正文，客户端会再次校验。只取得搜索摘要时不判真伪。缺少检索配置、取证失败、引文不匹配、归属不明分别保留未核查或证据不足。

从 App 0.5.22 起，每个 claim 可包含 `presentation`：大主题、精炼观点、焦虑/茧房/认知三类内容潜在影响的等级、理由、连续原文证据及建议。缺字段兼容旧数据；不完整或错属引文的扩展拒绝通过。等级是内容信号，不能断言观看者已经焦虑或形成茧房；颜色结合时长由客户端计算。协议、合成来源隔离与边界见[主题观点数据说明](../docs/VIEWPOINT_TOPICS.md)。部署该能力必须同时更新 `monitor_server.py` 与 `claim_presentation.py`；项目部署脚本已整体打包 server 目录。历史结果不会自动补齐，需要在现有授权边界内取得新的分析响应。

事实核查故障修复部署：更新 `server/monitor_server.py` 后重启既有后端服务，保留当前模型／Tavily 密钥与运行配置。Qwen3.8 Flash／Max 的 JSON 请求显式使用非思考模式；整条 Monitor 处理预算为 110 秒，与既有 120 秒代理超时错开。旧 APK 可直接接收修复后的服务端响应；客户端超时余量和取证失败重试需安装包含相应修改的新 APK。历史失败记录不会被伪装为成功，需要对原材料重新发起核查。模型模式依据：[阿里云深度思考文档](https://www.alibabacloud.com/help/en/model-studio/deep-thinking)。

开启事实核查时，声明抽取预算为 45 秒，取证阶段最多 40 秒，给证据分析预留至少 25 秒；未核查的纯语义请求仍可使用完整 110 秒。Tavily 先搜索最多 4 个链接（15 秒），再对这些链接调用 Extract（HTTP 等待最多 20 秒，供应商提取预算最多 12 秒）；接受部分正文成功，仍拒绝摘要、未搜索到的网址及不匹配的引文。正文读取会按剩余预算缩短 socket 等待时间。该拆分遵循 [Tavily Search 最佳实践](https://docs.tavily.com/documentation/best-practices/best-practices-search) 与 [Extract API](https://docs.tavily.com/documentation/api-reference/endpoint/extract)，不保证所有站点都能取得正文。

密钥按配置顺序轮转。只有鉴权／额度错误会在当前阶段换钥（最多尝试 3 把），鉴权失败冷却 5 分钟，额度错误冷却 1 分钟；状态仅在进程内保存。网络超时、连接错误和供应商故障立即返回可重试失败，不再换钥重复同一慢请求。正文提取换钥时复用已发现的链接，不重新搜索。

独立诊断使用认证后的 `POST /config/test`，例如发送 `{"target":"retrieval","mode":"evidence","key_index":0,"query":"2022年冬季奥林匹克运动会在北京举行"}`。`key_index` 从 0 开始，对应当前配置顺序；`mode=evidence` 使用正式搜索及正文提取路径，只测试指定密钥，不调用模型、不修改配置。响应含 `source_count`、总耗时 `ms` 与每一步 `attempts`；没有正文或请求失败时 `ok=false`。`mode=basic` 只测试搜索。显式诊断可绕过进程内密钥冷却，且不更新正式请求的冷却状态。未传新增字段的旧管理台测试仍为首把密钥的简单搜索，不能用其成功代替正式取证测试。

结构观点增加可选 `evidence_actors`（案例当事对象）和 `evidence_origins`（原始材料名称）两列。每列最多 4 个字符串，每项非空、不重复、不超过 80 字符，且必须逐字连续出现在本条 `evidence` 中；不能从其他声明或整页文字补身份。旧模型响应缺字段时补空列表，非法字段拒绝整次抽取。字段用于回查材料关系，不证明人物识别正确、事实真实或来源独立。

## 调用成本与数据

一次客户端请求不是一次付费调用：通常包含一次语义或视觉抽取、一次 Tavily Search、一次 Tavily Extract 和一次文字证据对齐；遇到鉴权／额度错误可能换钥重试，Search 与 Extract 分别计费。独立诊断也是真实调用；费用取决于各自的实际计费。手机共享请求限额与画面子限额另外设置，失败请求也消耗已预留额度。

服务端总监测请求额度使用 SQLite 持久化，默认 `server/.runtime/monitor-budget.sqlite3`，按 UTC 日历日计数；手机额度按设备本地日期计数。可用 `EMERGE_BUDGET_DB` 指定路径。额度在并发和进程重启后仍有效，不记录内容正文。多进程部署需共用同一数据库路径；删除额度数据库会重置计数，不能将其当作完整计费系统。

模型和检索服务会收到必要的请求内容，使用自己的 API 不代表没有第三方传输。服务不写请求正文、截图或密钥日志；仍应妥善配置反向代理日志、访问权限和保留策略。外部文档、截图和转写都被当作不可信数据；提示词约束不是已证明的提示注入防御。

管理台与 `/status`、`/config` 接口使用服务令牌或管理台登录会话鉴权（`Authorization: Bearer …`），页面登录态仅保存在浏览器会话中。管理台使用账号密码登录（`POST /admin/login`，`EMERGE_ADMIN_USER` / `EMERGE_ADMIN_PASSWORD`，未设置密码时回退为服务令牌；会话 12 小时过期，连续 5 次失败锁定 30 秒）。页面保存的模型密钥写入状态目录的 `runtime-config.json`（0600，优先级高于环境变量）；任何接口都不回传完整密钥。`POST /config/test` 可对文字、视觉、检索分别做连通性测试（真实调用一次提供方，返回延迟或错误）。服务令牌请使用足够长且随机的值（建议 20 字符以上），它保护分析接口，也是管理台密码的默认值。

## 测试

```sh
python -m unittest discover -s server/tests -v
```

当前服务端 188 项测试使用确定性假模型和假检索，并通过真实本地 HTTP 服务验证认证、协议、事实初筛字段与下游请求、证据引用、身份字段、阶段预算、取证重试与冷却、独立诊断和旧接口兼容。它们不证明真实模型对观点／事实的分类准确率、真实 API 的延迟或可用性。接入自己的 API 后，应先用有明确原始来源的少量图文和口播材料检查筛选结果、引文、条件、费用和错误状态。

实现与 Android 限制见 [实时监测说明](../docs/REALTIME_MONITORING.md)。
