# 本地带数据展示 APK

展示包使用固定应用签名和原包名，仅在首次创建数据库之前导入显式指定的手机快照，默认显示近 30 天。已有数据库不会被覆盖；删除记录、重启或覆盖升级不会重新灌入数据。普通构建不包含快照。

## 制作

1. 备份手机原 APK，并通过 `scripts/verify-apk-signing.py` 核对固定签名。
2. 停止应用，使用 `adb exec-out run-as com.zhiheng.app tar -cf - databases shared_prefs files no_backup cache` 导出私有数据。正式版不能 `run-as` 时，可先用相同版本、包名和固定签名的调试包保留数据覆盖安装，导出后立即恢复原 APK。不得卸载或清除源手机的数据。
3. 将备份解包到被 Git 忽略的私有目录，例如 `build/device-demo/<日期>/extracted`。保留数据库及其 WAL，防止遗漏尚未合并的记录。
4. 运行：

   ```sh
   python3 scripts/prepare-demo-snapshot.py \
     build/device-demo/<日期>/extracted \
     build/device-demo/<日期>/assets
   ./gradlew :app:assembleRelease \
     -Ppojian.demoSnapshotDir=build/device-demo/<日期>/assets
   ```

5. 校验生成 APK 的固定签名，并在隔离设备上验证首次安装。每次分发新构建前按项目规则递增应用版本。此流程不发布 APK、不推送 Git。

`assets` 目录只能放需要随 APK 分发的 `demo-snapshot.zip`；原 APK、完整备份和核验报告应放在其父目录。

## 数据范围

- 包含浏览记录、采样文本、已生成的分析结果、任务历史、设置、有效缩略图和已下载的语音模型，保留原始时间。
- 使用 SQLite backup 合并已提交的 WAL，检查完整性、外键和缩略图文件。
- 展示副本移除云端凭据，关闭云服务、自动采集和自动更新。无法跨设备解密的队列内容不携带，未完成任务改为取消；已完成分析结果保留。
- 关闭展示副本的自动到期清理，并提高容量下限，避免导入后立即删除历史记录。
- 系统授予的无障碍、录音、通知等权限不能移植。需要新采集或在线分析时，应在目标设备自行授权和配置。
- 完整原始备份另外保留；缓存中的安装包和临时预览不进入展示 APK。已导出到系统文档目录的 PDF 不属于应用私有目录备份。

快照和成品 APK 含有真实个人数据，仅用于用户授权的分享，禁止提交到 Git 或默认上传到公开发布渠道。
