# 发布

## 构建和验证

1. 更新 `android/build.gradle.kts` 中的 `versionCode` 与 `versionName`。
2. 在更新后的源码上运行 `./gradlew testDebugUnitTest lintDebug assembleDebug`。
3. 在 Android 设备验证扫码登录、账号恢复、本地与在线播放、歌词拖动、
   队列排序、主题切换、通知和锁屏控制，以及云端设置、文件列表和上传。
   加密音乐检查包括密钥及旧口令导入、本地扫描、ChunkSeal V1/V2 与旧 NCMX 播放，
   云端起播与拖动、快速切歌、暂停恢复、缺少密钥与损坏文件提示。
   上传检查包括系统多文件选择、本地歌曲入口、同名文件、网络失败、
   关闭进度面板后取消，以及连续开启上传时的任务与缓存清理。
4. 检查关于页能打开 GPLv3 全文和所有已使用组件的许可说明，
   ChunkSeal 条目中的项目仓库和集成授权对应仓库能正确打开。
5. 记录测试结果、设备验证范围和 APK 的 SHA-256。

GitHub Actions 产出调试 APK，适合安装验证。
正式发布时使用 `./gradlew assembleRelease` 构建，并使用项目自己的发布密钥签名。
签名配置和密钥保存在仓库之外，同一应用 ID 的后续版本继续使用同一把发布密钥。

签名前使用 Android Build Tools 的 `zipalign -P 16 -f 4` 对齐原生库。
签名后运行下列核对；Python 需要安装 Pillow，Android 工具目录需要在 PATH 中：

```bash
python3 -m pip install Pillow
python3 android/tools/verify-release.py /path/to/tingyu.apk
```

该工具检查发布签名、包名、版本、构建和测试时间、单元测试、Lint、
APK 内的许可全文、仓库引用、原生库对齐和启动图标。许可文件按源码逐字节比较。
已有发布包可以增加 `--artifacts-only`，跳过本机构建报告与时间检查。

## 创建版本

完成验证后，提交本次修改，并使用与应用版本一致的 Git 标签。
创建 GitHub Release，附上签名 APK、SHA-256、对应源码版本和简短更新说明。
发行说明应准确列出本次验证过的功能和设备。

源码包需要包含 `LICENSE`、`NOTICE`、第三方许可说明、Gradle Wrapper 和歌词视图模块。
应用包也需要包含 `assets/licenses/` 中的协议和版权文件。

源码 ZIP 生成后可与当前检出的源码一并核对：

```bash
python3 android/tools/verify-release.py /path/to/tingyu.apk \
  --source-archive /path/to/tingyu-source.zip
```

每次修改代码或内置许可后重新构建和核对，再生成对应的源码包与校验和。
