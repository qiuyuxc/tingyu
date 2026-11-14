# 参与开发

使用 JDK 21 和项目内的 Gradle Wrapper 构建，工具版本见 [README](README.md)。
修改应用代码时，请保留对应第三方代码的许可与署名。
提交到本项目的原创代码按 GPL-3.0-only 分发。

提交 PR 前运行：

```bash
cd android
./gradlew testDebugUnitTest lintDebug assembleDebug
```

PR 说明应包含实际问题、修改后的行为和验证结果。
播放器、登录、歌词或手势的修改还需要记录设备、Android 版本及实际操作结果；
单元测试使用模拟响应，不代表真实账号和设备上的验收。

报告问题时，请附上应用版本、Android 版本、复现步骤及相关错误信息。
日志请去掉账号 Cookie、访问令牌和个人文件路径。
