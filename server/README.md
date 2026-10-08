# 破茧：语义、视觉与外部证据网关

Android 客户端不直接保存模型密钥。网关接收用户明确开启的文字、稳定口播或关键画面，进行结构化与外部证据核查。Python 3.10+，仅使用标准库。

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
# 当前实现的检索适配器：Tavily Search，要求取得 raw_content 正文。
export TAVILY_API_KEY='你的检索密钥'
export EMERGE_MONITOR_DAILY_LIMIT=200
python server/monitor_server.py
```

默认监听 `127.0.0.1:8787`，对外使用受控的 HTTPS 反向代理。手机填写可达的网关根地址，不包含 `/monitor`，令牌填写 `EMERGE_SERVICE_TOKEN`，不是模型 API Key。仅开发调试允许符合既有 EndpointPolicy 的本地 HTTP；不要在不受信网络明文传输。

手机上分别开启云端网关、实时事实核查和/或关键画面分析。单独开启云端网关不会自动开启截图。原有手动音频回顾继续使用 `/analyze`；纯旧服务 `python server/emerge_server.py` 仍可运行，但不提供 `/monitor`，不能用于新版实时核查。

## 接口及限制

- `GET /health`：存活检查，保留旧协议。
- `GET /capabilities`：返回监测协议以及视觉、检索是否配置；这是配置状态，不是效果验证。
- `POST /monitor`：Bearer 令牌认证；`version=1`、`id`、`kind=text/audio/frame`、`text`、`captured_at`、`check_facts`；画面额外传 `image`（JPEG base64，不接受外部图片 URL）。
- `POST /analyze`：旧的转写与历史比较协议保持不变。

文字最多 6000 字，JPEG 最多 180 KiB，整个请求最多 512 KiB。客户端只上传当前可见画面采样，不自动抓取分享链接。每次抽取最多三条声明，最多对一条事实声明执行外部搜索和证据对齐；其他候选返回 `not_checked` 并说明预算。总体处理设有截止时间和并发上限。

输出包括 `observed_text`、结构化 `claims`、明确 `verdict`、解释、`sources` 和覆盖说明。来源 URL 必须来自检索返回，连续引文必须存在于取回的正文，客户端会再次校验。只取得搜索摘要时不判真伪。缺少检索配置、取证失败、引文不匹配、归属不明分别保留未核查或证据不足。

## 调用成本与数据

一次客户端请求不是一次付费调用：至多包含一次语义或视觉抽取、一次 Tavily 检索、一次文字证据对齐；费用取决于各自的实际计费。手机共享请求限额与画面子限额另外设置，失败请求也消耗已预留额度。

服务端总监测请求额度使用 SQLite 持久化，默认 `server/.runtime/monitor-budget.sqlite3`，按 UTC 日历日计数；手机额度按设备本地日期计数。可用 `EMERGE_BUDGET_DB` 指定路径。额度在并发和进程重启后仍有效，不记录内容正文。多进程部署需共用同一数据库路径；删除额度数据库会重置计数，不能将其当作完整计费系统。

模型和检索服务会收到必要的请求内容，使用自己的 API 不代表没有第三方传输。服务不写请求正文、截图或密钥日志；仍应妥善配置反向代理日志、访问权限和保留策略。外部文档、截图和转写都被当作不可信数据；提示词约束不是已证明的提示注入防御。

## 测试

```sh
python -m unittest discover -s server/tests -v
```

本轮新增的 55 项测试使用确定性假模型和假检索，并通过真实本地 HTTP 服务验证认证、协议、证据引用、预算和旧接口兼容。它们不证明真实 API 的准确率、延迟或可用性。接入自己的 API 后，应先用有明确原始来源的少量图文和口播材料检查引文、条件、费用和错误状态。

实现与 Android 限制见 [实时监测说明](../docs/REALTIME_MONITORING.md)。
