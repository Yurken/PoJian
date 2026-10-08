# 音频组件来源

- 推理库：[sherpa-onnx v1.13.8](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8)，Apache-2.0；原许可证保存在 `app/src/main/assets/licenses/sherpa-onnx-LICENSE.txt` 并打包进 APK。AAR 下载地址、长度与 SHA-256 固定在构建脚本中。
- 中文模型：[sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23)。文件固定 revision `204ad334e2e683fd295359930cc16fc0432a23ac`，逐文件长度/SHA-256 见 `SpeechModelStore.kt`；31 MB 指四个模型/词表文件合计，不包含原生引擎与运行内存。模型由用户主动从上游下载，没有提交进本仓库。
- 工程验证使用上游公开 `test_wavs/0.wav`；样例和下载模型仅位于忽略的构建或 QA 目录，未作为用户记录或产品预置数据发布。

Zipformer 模型与 sherpa-onnx 引擎属于上游工作；本项目的实现范围是 Android 音频输入、时间分段与持久化、转写修订、原句约束及历史信息回顾。
