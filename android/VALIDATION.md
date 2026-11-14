# 0.12.6 验证

2026-11-14，在 JDK 21、Gradle 9.7.1、SDK Platform 37.0 和 Build Tools 37.0.0 环境运行：

```bash
./build.sh testDebugUnitTest lintDebug --offline
```

- 202 项单元测试通过，0 失败、0 错误、0 跳过。
- Lint 通过：0 错误、87 条警告、4 条提示；未禁用 API 兼容检查。
- 调试 APK 构建和签名校验通过。
- 应用 ID 为 `com.qiuyuxc.tingyu.opensource`，版本为 `0.12.6`，最低 Android API 为 24。
- APK 内的 GPLv3、项目版权和第三方来源说明与源码文件一致，关于页引用的许可文件均已打包。

APK 大小为 19393532 字节，SHA-256：

```text
c9b9ede045cbad5e9078674d76277adcd130daf8d77988a2a1e113c5166e2e41
```

自动回归覆盖模拟平台响应、登录与账号模型、在线播放解析、队列、歌词和界面布局计算。
本次未进行真机扫码、实际音频输出或触控验收。

项目名称统一为 Tingyu 后，使用 `./build.sh --offline` 重新构建调试 APK，
并核对源码、版权说明、第三方致谢与 APK 内的许可文本。
