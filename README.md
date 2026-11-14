# 听屿 / Tingyu

一款 Android 音乐播放器，使用 MIUIX 界面，支持本地曲库与网易云音乐、酷狗概念版账号。

项目仓库：[qiuyuxc/tingyu](https://github.com/qiuyuxc/tingyu)。

## 功能

- 扫码登录、账号管理、会员状态与默认音质选择。
- 在线搜索、推荐、排行榜、歌单、喜欢的歌曲与评论。
- 本地音乐扫描、播放、收藏和 M3U 歌单导出。
- 专辑封面、同步歌词、翻译歌词、最近播放与听歌排行。
- 播放队列排序、循环与随机播放、媒体通知和锁屏控制。
- 跟随系统的明暗主题、三套配色和播放页封面背景。

支持 Android 7.0（API 24）及以上版本；液态玻璃导航在 Android 13 及以上启用。
本地文件支持 FLAC、MP3、M4A、AAC、OGG、Opus、WAV，实际可播放编码取决于设备。

0.12.8 修复高解析本地音乐仍使用旧播放路径的问题。Android 8.1 及以上的本地与
在线播放共用浮点音频链路，并读取已有 FLAC 的响度信息，保留原始音频内容。

## 使用

在“我的 → 设置 → 平台账号”连接账号，也可以从曲库的平台入口扫码登录。
在线播放使用对应平台返回的音源和播放权限。

本地音乐目录默认为 `/storage/emulated/0/Music`，可在“音乐与文件”中修改。
允许访问音乐文件后，点击曲库中的“扫描音乐”导入收藏。
同名 `.lrc` 文件和音频内已有的歌词标签都可以读取。
默认歌单可导出至 `/storage/emulated/0/Music/听屿/默认歌单.m3u`。

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

## 协议

本项目原创代码采用 [GNU GPLv3](LICENSE)（`GPL-3.0-only`），版权说明见 [NOTICE](NOTICE)。
第三方组件保留各自许可证，来源、版本和修改说明见
[第三方许可说明](android/THIRD_PARTY.md)。应用关于页可离线查看协议全文与第三方许可。

ChunkSeal 为听屿集成提供 [专项 GPLv3 授权](licenses/ChunkSeal-Tingyu-Permission.md)，
可供后续集成使用，主项目继续采用 GPL-3.0-only。该授权保留原版权，并给予 GPL
版本的下游完整的修改、分发与复用权利。原项目声明见
[ChunkSeal NOTICE](licenses/ChunkSeal-NOTICE.txt)。
关于页的「第三方致谢 → ChunkSeal v1」提供版权、专项授权与上游 AGPLv3 原文的离线查看入口。
