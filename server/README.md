# 破茧文字分析服务

可选的文字服务，把手机提供的转写与少量历史候选发送给 OpenAI Chat Completions 兼容模型。手机端本地转写和本地规则无需此服务。服务仅处理文字，不接收音频；不保存或记录转写、请求体、凭据及上游错误正文。启用云端模型后，文字会发送给所配置供应商，上游处理方式取决于该服务的设置。

需要 Python 3.10 或更新版本，无第三方依赖。在项目根目录启动：

```bash
export EMERGE_UPSTREAM_API_KEY='你的模型服务密钥'
export EMERGE_SERVICE_TOKEN='为手机单独生成的长随机口令'
export EMERGE_UPSTREAM_MODEL='qwen-plus'
python3 server/emerge_server.py
```

可用 `python3 -c 'import secrets; print(secrets.token_urlsafe(32))'` 生成手机口令。上游密钥只配置在服务端；App「文字服务」填写本服务地址和 `EMERGE_SERVICE_TOKEN`，不要填写上游密钥。

默认上游为阿里云北京地域兼容端点 `https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions`，依据[阿里云官方文档](https://www.alibabacloud.com/help/zh/model-studio/model-calling-in-sub-workspace)。地域、工作空间和模型权限须与密钥对应。可设置 `EMERGE_UPSTREAM_URL` 为其他兼容的完整 Chat Completions URL；本机自建模型支持 `http://127.0.0.1:端口/v1/chat/completions`，无鉴权模型也需配置占位的上游 key。

默认监听 `127.0.0.1:8787`。Android 模拟器使用 `http://10.0.2.2:8787`（调试版）；真机局域网调试可设置 `EMERGE_HOST=0.0.0.0` 后填写电脑局域网地址。公开部署时放在 HTTPS 反向代理后，并由代理限制请求速率；配置凭据用环境变量或部署平台的密钥管理，不写入仓库。可配置 `EMERGE_PORT`、`EMERGE_UPSTREAM_TIMEOUT`（默认 30 秒，范围 1–40）及 `EMERGE_CONCURRENCY`（默认 4，范围 1–32）。

`GET /health` 返回服务存活状态，不会调用模型；`POST /analyze` 要求 `Authorization: Bearer <手机口令>` 和 JSON：

```json
{"version":1,"segments":[{"id":2,"text":"岗位减少百分之二十五。","timestamp":2000,"startMillis":0,"endMillis":2000}],"history":[{"id":1,"text":"过去岗位减少百分之二十。","timestamp":1000,"startMillis":0,"endMillis":1000}]}
```

响应与 App `RemoteAudioInsights` 契约一致：`summary`、`model`、`claims`，可选 `usage`。每条观点包含 `claim`、当前片段 `segmentId` 和连续原文 `evidence`、`relation`、`novelty`，以及成对出现的 `historySegmentId`/`historyQuote`（无历史时均为 null）。服务端和手机端都会核对引用 id 及逐字引文；这用于防止引文杜撰，语义判断仍需用户核查。

请求最大 256 KiB；当前转写最多 500 段、2 万字符，历史最多 10 段、2 万字符。响应最多 24 个观点，正文最大 256 KiB。上游调用不重试、不跟随重定向；错误返回通用 JSON，鉴权失败 401、输入错误 400、过大 413、繁忙 429、上游错误 502、上游超时 504。模型需支持 `response_format: json_object`；不支持时更换兼容模型。

运行本地测试（只调用进程内假上游，不调用付费 API）：

```bash
python3 -m unittest discover -s server/tests -v
```
