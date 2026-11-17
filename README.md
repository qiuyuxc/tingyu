# 听屿 / Tingyu

一款 Android 音乐播放器，使用 MIUIX 界面，支持本地曲库、云端文件与网易云音乐、酷狗概念版账号。

项目仓库：[qiuyuxc/tingyu](https://github.com/qiuyuxc/tingyu)。
安装包：[GitHub Releases](https://github.com/qiuyuxc/tingyu/releases/latest)。

## 功能

- 扫码登录、账号管理、会员状态与默认音质选择。
- 在线搜索、推荐、排行榜、歌单、喜欢的歌曲与评论。
- 本地音乐及 ChunkSeal、旧 NCMX 加密音乐的扫描、播放、收藏和 M3U 歌单导出。
- S3、OpenList 与 HTTP 存储连接，本地文件上传、云端文件列表与音乐播放。
- 专辑封面、同步歌词、翻译歌词、最近播放与听歌排行。
- 播放队列排序、循环与随机播放、媒体通知和锁屏控制。
- 跟随系统的明暗主题、三套配色和播放页封面背景。

支持 Android 7.0（API 24）及以上版本；液态玻璃导航在 Android 13 及以上启用。
本地文件支持 FLAC、MP3、M4A、AAC、OGG、Opus、WAV，实际可播放编码取决于设备。
加密音乐支持 ChunkSeal V1/V2 的 `.cseal` 与旧 `.ncmx` 文件，播放前需导入对应密钥或口令。

0.12.11 恢复现有「加密与密钥」设置页与密钥环，支持复制、重新生成，以及在「高级」中
统一导入密钥或旧口令；重新生成会保留旧凭据，并兼容 0.12.10 已导入的凭据。

0.12.10 补齐本地和云端加密音乐读取、密钥导入与播放。云端播放通过 Range 请求
按需读取，拖动进度时读取对应音频块；继续使用现有浮点音频链路与响度处理。

0.12.9 新增云端存储连接、文件列表与上传入口，支持选择多个本地文件，查看上传进度与结果。

0.12.8 修复高解析本地音乐仍使用旧播放路径的问题。Android 8.1 及以上的本地与
在线播放共用浮点音频链路，并读取已有 FLAC 的响度信息，保留原始音频内容。

## 使用

在“我的 → 设置 → 平台账号”连接账号，也可以从曲库的平台入口扫码登录。
在线播放使用对应平台返回的音源和播放权限。

本地音乐目录默认为 `/storage/emulated/0/Music`，可在“音乐与文件”中修改。
允许访问音乐文件后，点击曲库中的“扫描音乐”导入收藏。
同名 `.lrc` 文件和音频内已有的歌词标签都可以读取。
默认歌单可导出至 `/storage/emulated/0/Music/听屿/默认歌单.m3u`。

在“我的 → 设置 → 音乐与文件 → 云端存储”选择连接方式，填写连接信息并启用。
S3 兼容存储填写端点、桶名、区域和访问密钥，可设置目录前缀与公开访问地址；
OpenList 填写服务器地址和云端目录，需要登录时再填写账号；
HTTP 填写文件根地址，目录列表使用 `index.json`，上传需要服务器允许 HTTP PUT。
连接后可从曲库的“云端文件”入口选择本地文件上传，也可以从本地歌曲菜单上传音乐。
文件保留原始内容与文件名；遇到同名文件时会提示改名后上传。

在“我的 → 设置 → 音乐与文件 → 加密与密钥”展开“高级”，
点击“导入密钥或旧口令”，导入文件加密时使用的凭据。
随机密钥使用完整的 `cseal2:` 导出；旧文件或密码模式文件使用原口令。
可以导入多把凭据，口令中的空格与换行会原样保留。
当前密钥可复制或重新生成；旧密钥和口令继续保留，可以分别复制备份。
如果文件由另一份听屿应用加密，先在那份应用中复制密钥或旧口令，再导入本应用。
将加密文件放入本地音乐目录并扫描，或连接其所在的云端目录，在文件菜单中选择“播放”。
读取规则与代码位置见 [加密音乐](docs/encrypted-music.md)。

## 构建

需要 JDK 21、Android SDK Platform 37 和 Build Tools 37.0.0。
Gradle Wrapper 固定为 9.7.1，Android Gradle Plugin 为 9.3.2，Compose 编译插件为 2.4.20。

```bash
export ANDROID_HOME=/path/to/android-sdk
cd android
./build.sh
```

脚本生成 `android/tingyu-open-source.apk`，使用 Android 调试签名。
默认应用 ID 为 `com.qiuyuxc.tingyu.opensource`。
Android Studio 也可以直接打开 `android/` 目录。

单独运行检查：

```bash
cd android
./gradlew testDebugUnitTest lintDebug
```

## 开发与发布

应用代码位于 `android/src/main/kotlin/com/qiuyuxc/tingyu/`，
资源位于 `android/res/`，回归测试位于 `android/src/test/`。
歌词视图作为本地模块保存在 `android/vendor/lrcview/`。

参与开发见 [CONTRIBUTING.md](CONTRIBUTING.md)，发布步骤见 [发布说明](docs/RELEASING.md)。
GitHub Actions 会运行单元测试、Lint 和 APK 构建。
本次自动检查结果见 [验证记录](android/VALIDATION.md)。
正式包可用 `android/tools/verify-release.py` 核对签名、版本、内置许可和源码 ZIP；
用法见发布说明。

## 协议

本项目原创代码采用 [GNU GPLv3](LICENSE)（`GPL-3.0-only`），版权说明见 [NOTICE](NOTICE)。
第三方组件保留各自许可证，来源、版本和修改说明见
[第三方许可说明](android/THIRD_PARTY.md)。应用关于页可离线查看协议全文与第三方许可。

[ChunkSeal](https://github.com/kity-yolo/chunkseal-protocol) 使用 AGPL-3.0-only，
其作者为 [听屿](https://github.com/qiuyuxc/tingyu) 提供了
[GPLv3 集成授权](licenses/ChunkSeal-GPL-Permission.md)。按该授权分发的集成须保留
版权声明、授权文件与 GPLv3 正文。原项目声明见
[ChunkSeal NOTICE](licenses/ChunkSeal-NOTICE.txt)。
关于页的「第三方致谢 → ChunkSeal」可查看版权、集成授权、上游 AGPLv3 原文和对应仓库。
