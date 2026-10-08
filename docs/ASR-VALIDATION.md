# 本地语音转写验证

音频引擎使用官方 [sherpa-onnx v1.13.8 Android AAR](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8)，构建时下载到 `app/build/asr-dependencies` 并检查 SHA-256。中文模型是 [14M 流式 Zipformer](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23)，固定到 `204ad334e2e683fd295359930cc16fc0432a23ac`。所用 encoder/joiner 为 int8、decoder 为 float32，四个文件共 30,975,688 字节。

用户点击“下载模型”后才下载，逐个校验大小和 SHA-256，再把完整目录安装到应用私有存储。下载可取消。每次创建识别器前重新校验文件。模型不随 APK 内置，也不会通过开始转写触发隐式下载。

`AudioPcmTest` 检查声道混音、PCM8/16/float、非有限采样、codec buffer 的 offset/limit，以及 5 分钟截断。`AudioPipelineInstrumentedTest` 用生成的 WAV 验证设备解码器，还可以运行官方公开中文音频样例的完整“解码 → 本地 ASR → 文字和时间戳”验证，并覆盖 AAC 音轨正常结束后的尾段转写。

原生 ASR 集成测试需要先安装公开 fixtures；缺少模型或对应音频时，WAV/AAC 原生识别用例和 Coordinator 导入用例会跳过。生成 WAV 的纯解码用例无需模型。测试文件为上面模型仓库同一固定版本的 `test_wavs/0.wav`（179,646 字节，SHA-256 `668bf8df51a10027b84d5d8816a1ce11ae93545538dc05cfe2aa6811d399c250`）。将四个模型文件放入测试设备的应用私有目录 `files/speech-models/zipformer-zh-14m-int8-v1`，该目录下 `.verified` 的内容为固定版本号；将样例放在 `files/audio-test-sample.wav`。用 `adb push` 到设备临时目录，再通过 `adb shell run-as com.zhiheng.app cp ...` 复制即可。所有 fixtures 均为公开测试素材。

AAC 用例还需将同一 WAV 转为 AAC/M4A，复制到 `files/audio-test-sample.m4a`。macOS 可用 `afconvert -f m4af -d aac 0.wav sample.m4a`。App 内主动下载好的相同模型也可直接用于这些测试。

测试时应使用独立 QA 模拟器，不清空日常使用的模拟器。指定目标设备，例如：

```bash
ANDROID_SERIAL=emulator-5562 ./gradlew :app:connectedDebugAndroidTest
```

若 Gradle 宿主 UTP 依赖尚未缓存，可先 `assembleDebug assembleDebugAndroidTest`、安装两个 APK，然后直接执行 `adb -s emulator-5562 shell am instrument -w -r com.zhiheng.app.test/androidx.test.runner.AndroidJUnitRunner`。构建参数 `-PemergeAbi=arm64-v8a` 可为已知 arm64 设备生成较小 APK；默认保留上游全部 ABI。

真实内录还需验证来源应用是否允许 AudioPlaybackCapture：系统授权后仅匹配用户所选的单个应用 UID；通知栏可暂停、继续、停止；最长 5 分钟。语音处理落后超过有界 PCM 队列时会明确停止。系统撤销投影授权时释放采集器，不重用旧授权。全程没有麦克风输入、视频帧或持久音频文件。

## 已执行的验证（2026-10-08）

在独立 API 37 / arm64 / 16 KB 页大小模拟器上，验证了公开中文 WAV 和 AAC 文件的解码、真实 Zipformer 识别与媒体时间范围。`AudioCoordinatorInstrumentedTest` 进一步走 `Runtime.events → Coordinator → Room`，检查终态前文字已持久化、原文保留、删除后不复活。原生测试使用的模型也经过 App 实际下载：点击下载收到 13% 时取消，确认界面回到未安装状态；再次点击完整下载成功，逐文件校验通过并生成安装标记。

另安装了仅播放官方公开 WAV 样例的 QA 播放器，通过实际系统授权选择该应用，验证单 UID 内录、后台前台服务、真实文字落库、暂停、继续和停止。服务运行类型为 `mediaProjection`，停止后服务被释放。此结果覆盖测试播放器；短视频平台是否允许内部声音捕获仍需逐平台、真机验证。5 分钟截断由样本边界测试验证，本轮没有连续等待 5 分钟或模拟原生识别器性能落后。

用户日常模拟器未被清空、卸载或修改设置。所有测试操作都在独立 QA 模拟器完成。
