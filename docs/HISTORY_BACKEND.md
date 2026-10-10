# 持续历史后端联调原型

对应第二章2.1的版本数据、2.5的严格过去历史和多材料覆盖、2.6的基础图结构、2.9的幂等与重算以及2.10的窗口报告。本服务与`monitor_server.py`分开运行，不改变现有生产网关和Android APK。

## 能力和边界

客户端提供作品ID、事件ID、修订号、观察区间、文字和结构化观点。服务器检查引用是否在文字中，保存SQLite修订，重复输入返回原状态，同修订不同输入返回409。事件身份不能被修订改变，修订连续且观察时间不得倒退。上传重试与真实再次观看分别处理：前者复用事件ID，后者新建事件ID并复用作品ID。

报告选取截至窗口终点可用的版本，历史比较使用当前主张观察时刻之前已可用的修订，排除同时间材料和同作品。最多比较最近50个同议题/对象候选，多条历史可以联合覆盖当前的字面论据。未匹配不是已证明的新增，改写、引用反驳、主体或范围不明需要语义复核。

窗口内作品按应用和作品ID去重，观看次数按事件ID计数，时长按裁剪后的区间并集计算。未知观点作品计入总作品数；多标签观点占比分别使用总作品数，比例可以超过100%。图中“来源”目前只是应用，不表示独立媒体源头。报告带事件修订、原文位置和输入摘要。删除清理原事件的全部修订，下一次报告重算；不是备份清除或密码学销毁。

无训练模型、无持久任务队列、无真实TEE、无存储加密、无校准风险结论。服务仅监听本机，用户Token与身份在服务器配置中绑定，不接受请求体指定用户。输入字段与Token不得用于真实用户明文浏览历史生产部署。Android端尚未连接此接口。

## 启动

Python3.10+，无第三方依赖。仅做本机合成样本联调。PowerShell示例：

```powershell
$env:POJIAN_HISTORY_PROTOTYPE='1'
$env:POJIAN_HISTORY_USERS='{"demo":"replace-with-a-random-token-at-least-24-characters"}'
py -3 -m server.history_server
```

默认端口8788，数据库`server/.runtime/history.sqlite3`。可用`POJIAN_HISTORY_PORT`、`POJIAN_HISTORY_DB`调整。运行目录请放在本机受控目录；不要上传Token或数据库文件。主服务的Linux部署脚本不会自动启动这个联调服务。

## 协议

鉴权头为`Authorization: Bearer <用户专属Token>`，请求类型为`application/json`，请求体上限256KiB，文本上限16000字符，每事件最多20个主张，事件区间不超过24小时。每用户默认最多10000个事件，每事件最多1000次修订；报告窗口最多31天。容量限制不是负载能力承诺。

`POST /history/events`示例：

```json
{
  "event_id":"visit-1", "work_id":"post-1", "platform":"synthetic-reader",
  "revision":1, "start_ms":1000, "end_ms":2000, "revision_time_ms":2000,
  "text":"当前就业困难。招聘数量下降20%",
  "claims":[{
    "topic":"就业", "subject":"应届生", "stance":"就业困难",
    "quote":"当前就业困难", "evidence":"招聘数量下降20%", "conditions":"",
    "observed_ms":1000
  }]
}
```

时间使用Unix毫秒；示例为合成时间。`revision_time_ms`为此输入修订实际可用的观察时刻，不是任务完成时刻。引用/论据/条件必须是`text`中的连续片段。主张类别由调用方提交，字面定位不能证明该语义字段正确。新增修订应提供当前观察快照，保留已知原文及新增片段。原型没有实现字段级依赖修订。

`POST /history/report`请求`{"start_ms":0,"end_ms":10000}`，返回JSON并设置下载附件`PoJian-history-report.json`。窗口左闭右开，零时长观测按起点归窗；有时长区间取交集。`POST /history/delete`请求`{"event_id":"visit-1"}`。无身份、错误引用、冲突修订和超过容量分别返回401、400、409、429。`GET /health`不返回数据或凭据。

报告中的`literal_covered`只表示所选历史里有字面相同论据；`literal_repetition`须同时具有字面相同立场、条件及全部论据覆盖。`initial_material`表示未取得可比历史，`insufficient_evidence`表示没有当前论据，`needs_semantic_review`表示需更强语义判别。任何状态都不是事实真假判断或用户心理诊断。

## 验证

```powershell
py -3 -m unittest server.tests.test_cognitive_history -v
py -3 -m unittest discover -s server/tests -v
py -3 server/bench_history.py --output outputs/history-benchmark.json
```

新增21项测试覆盖版本替换、幂等、并发重试、晚到与删除重算、未来排除、历史旧版本、联合论据覆盖、未知分母、多标签、用户隔离、窗口裁剪、数据库恢复、引用约束和HTTP报告下载。本轮全套135项测试通过。微基准仅用合成、单主张、同词论据记录与内存SQLite，不能作为模型准确率或生产性能测试。
