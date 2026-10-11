# 本地语音转写：配置与验证方法

音频引擎使用官方 [sherpa-onnx v1.13.8 Android AAR](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8)，构建时下载到 `app/build/asr-dependencies` 并检查 SHA-256。中文模型是 [14M 流式 Zipformer](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23)，固定到 `204ad334e2e683fd295359930cc16fc0432a23ac`。所用 encoder/joiner 为 int8、decoder 为 float32，四个文件共 30,975,688 字节。

用户点击“下载模型”后才下载，逐个校验大小和 SHA-256，再把完整目录安装到应用私有存储。下载可取消。每次创建识别器前重新校验文件。模型不随 APK 内置，也不会通过开始转写触发隐式下载。

APK 从自有 HTTPS 服务的 `/pojian/models/zipformer-zh-14m-int8-v1/<revision>/` 下载这四个原始文件，地址固定在 `SpeechModelStore.kt`，不依赖用户填写的网关地址。服务端目录与发布方法见 [部署文档](DEPLOYMENT.md#语音模型静态文件)。模型 ID、版本和校验值未变，已有完整模型无需重新下载。

`AudioPcmTest` 检查声道混音、PCM8/16/float、非有限采样、codec buffer 的 offset/limit，以及 5 分钟截断。`AudioPipelineInstrumentedTest` 用生成的 WAV 验证设备解码器，还可以运行官方公开中文音频样例的完整“解码 → 本地 ASR → 文字和时间戳”验证，并覆盖 AAC 音轨正常结束后的尾段转写。

原生 ASR 集成测试需要先安装公开 fixtures；缺少模型或对应音频时，WAV/AAC 原生识别用例和 Coordinator 导入用例会跳过。生成 WAV 的纯解码用例无需模型。测试文件为上面模型仓库同一固定版本的 `test_wavs/0.wav`（179,646 字节，SHA-256 `668bf8df51a10027b84d5d8816a1ce11ae93545538dc05cfe2aa6811d399c250`）。将四个模型文件放入测试设备的应用私有目录 `files/speech-models/zipformer-zh-14m-int8-v1`，该目录下 `.verified` 的内容为固定版本号；将样例放在 `files/audio-test-sample.wav`。用 `adb push` 到设备临时目录，再通过 `adb shell run-as com.zhiheng.app cp ...` 复制即可。所有 fixtures 均为公开测试素材。

AAC 用例还需将同一 WAV 转为 AAC/M4A，复制到 `files/audio-test-sample.m4a`。macOS 可用 `afconvert -f m4af -d aac 0.wav sample.m4a`。App 内主动下载好的相同模型也可直接用于这些测试。

测试时应使用独立 QA 模拟器，不清空日常使用的模拟器。指定目标设备，例如：

```bash
ANDROID_SERIAL=emulator-5562 ./gradlew :app:connectedDebugAndroidTest
```

若 Gradle 宿主 UTP 依赖尚未缓存，可先 `assembleDebug assembleDebugAndroidTest`、安装两个 APK，然后直接执行 `adb -s emulator-5562 shell am instrument -w -r com.zhiheng.app.test/androidx.test.runner.AndroidJUnitRunner`。构建参数 `-PemergeAbi=arm64-v8a` 可为已知 arm64 设备生成较小 APK；默认保留上游全部 ABI。

模型镜像的网络冒烟测试默认跳过。仅在独立 QA 设备上显式运行以下命令，会实际下载全部模型并验证安装标记、SHA-256、进度和临时目录清理；随后可运行上述 WAV/AAC 转写测试：

```bash
adb -s emulator-5562 shell am instrument -w -r \
  -e downloadSpeechModel true \
  -e class com.emerge.app.audio.AudioPipelineInstrumentedTest#hostedModelDownloadsAndVerifies \
  com.zhiheng.app.test/androidx.test.runner.AndroidJUnitRunner
```

真实内录还需验证来源应用是否允许 AudioPlaybackCapture：系统授权后仅匹配用户所选的单个应用 UID；通知栏可暂停、继续、停止；最长 5 分钟。语音处理落后超过有界 PCM 队列时会明确停止。系统撤销投影授权时释放采集器，不重用旧授权。全程没有麦克风输入、视频帧或持久音频文件。

## 历史验证结果

2026-10-08 的公开音频、模型下载及 QA 播放器内录验证见[更新日志：本地语音转写](CHANGELOG.md#asr-2026-10-08)。后续版本缺少真实模型或音频夹具时跳过的原生识别用例，在各版本条目中单独列出；历史验证不自动覆盖新版本。
