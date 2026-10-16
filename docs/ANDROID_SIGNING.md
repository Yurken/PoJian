# Android 固定签名

本地 Debug、Release 和 GitHub Actions 共用项目固定私钥，应用 ID 保持 `com.zhiheng.app`。证书 SHA-256 保存在 `app/signing-certificate.sha256`；构建会校验私钥、密码和证书，配置缺失或证书不符时直接失败，不再生成临时签名。CI 发布 Release APK，并在上传前再次验证 APK 签名。

## 本地配置

从项目维护者的安全备份恢复现有密钥，不要重新生成。将以下配置保存为仓库根目录的 `keystore.properties`（已忽略），或用环境变量 `POJIAN_SIGNING_PROPERTIES` 指定文件：

```properties
storeFile=/absolute/private/path/pojian-release.p12
storeType=PKCS12
storePassword=<密钥库密码>
keyAlias=pojian
keyPassword=<私钥密码>
```

目录权限设为 `700`，密钥及配置文件设为 `600`。用 Java Properties 格式转义特殊字符。使用 JDK 17 构建：

```sh
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease -PemergeAbi=arm64-v8a
python3 scripts/verify-apk-signing.py "$ANDROID_HOME/build-tools/34.0.0/apksigner" \
  app/build/outputs/apk/release/app-release.apk app/signing-certificate.sha256
```

私钥和密码不得提交到 Git、放入构建产物或输出到日志。密钥及密码应有独立、安全的离线备份；更换电脑时恢复同一套文件。GitHub Secrets 不能读回原值，不能替代备份。不要为解决配置错误而更换证书指纹。

## GitHub Actions

配置以下仓库 Secrets：

| Secret | 内容 |
| --- | --- |
| `POJIAN_SIGNING_KEYSTORE_BASE64` | 固定 PKCS12 密钥库的 Base64 编码 |
| `POJIAN_SIGNING_STORE_PASSWORD` | 密钥库密码 |
| `POJIAN_SIGNING_KEY_ALIAS` | `pojian` |
| `POJIAN_SIGNING_KEY_PASSWORD` | 私钥密码 |
| `POJIAN_SERVICE_TOKEN` | 已有服务认证令牌，与签名无关 |

CI 在 runner 临时目录恢复密钥，使用后清理。签名秘密不进入 Gradle 缓存或上传的 APK 附件。无 Secret 的 fork 构建不能产出项目签名的 APK。

## 旧版与数据

此前 CI 使用临时 Android Debug 密钥。审计未找到对应私钥备份，已发布旧 APK 的证书与本地旧 Debug 密钥不同，因此不能沿用本地 Debug 密钥来更新这些 CI 旧版，也不能从 APK 提取签名私钥。

固定签名解决的是此后版本之间的覆盖升级；不能让此前不同签名的已安装版本直接升级。保留旧应用及数据，先针对实际设备完成并验证备份/迁移。不要以卸载旧版作为默认升级步骤，也不要承诺仅凭固定签名就能恢复旧版数据。若找回旧版私钥，须重新评估签名迁移方案。

后续每次发布仍需递增 `versionCode`，保持应用 ID 和签名，并验证数据库迁移。固定签名是覆盖升级的必要条件，不替代数据迁移测试。

## 覆盖升级验证

仅在独立、可丢弃的测试设备执行。构建两个使用相同固定密钥、递增 `versionCode` 的版本，以及包含 `SigningUpgradeTest` 的测试 APK。该用例默认跳过，必须显式指定阶段，不能在用户设备写入测试数据。

```sh
adb -s "$QA_DEVICE" install -r old-fixed-release.apk
adb -s "$QA_DEVICE" install -r app-debug-androidTest.apk
adb -s "$QA_DEVICE" shell am instrument -w \
  -e class com.emerge.app.analysis.SigningUpgradeTest -e signingUpgradePhase seed \
  com.zhiheng.app.test/androidx.test.runner.AndroidJUnitRunner
adb -s "$QA_DEVICE" install -r new-fixed-release.apk
adb -s "$QA_DEVICE" shell am instrument -w \
  -e class com.emerge.app.analysis.SigningUpgradeTest -e signingUpgradePhase verify \
  com.zhiheng.app.test/androidx.test.runner.AndroidJUnitRunner
```

验证要求版本递增、UID 不变，浏览记录正文/时长/时间、采集范围设置和 Android Keystore 加密内容均保留。它不证明此前不同 CI 签名旧版可以直接升级。
