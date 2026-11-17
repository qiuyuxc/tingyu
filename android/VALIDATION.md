# 听屿 Android 验证记录

## 0.12.9 云端文件与上传

2026-11-16，新增 S3、OpenList 和 HTTP 存储连接、云端文件列表与上传入口。
曲库可打开云端目录并刷新列表；本地歌曲菜单可直接上传，系统文件选择器支持多选。
上传保留原始内容和文件名，显示进度与逐项结果，遇到同名文件提示改名后上传。
关闭上传面板会取消当前任务并清理临时副本，连续上传的面板回调按批次隔离。

| 检查 | 结果 |
| --- | --- |
| 单元测试 | 215 项通过，无失败、错误或跳过；其中 FileStorageTest 13 项 |
| 后端专项 | AWS 签名示例、S3 分页与原始字节上传、HTTP 清单及空文件、OpenList 分页与上传错误、同名保护通过 |
| Lint | Debug Lint 无错误，102 条提示；Release Lint Vital 通过 |
| 构建 | assembleDebug、assembleRelease 通过 |
| 包名与版本 | com.qiuyuxc.tingyu.opensource，0.12.9 / versionCode 26 |
| 正式包核对 | 沿用原签名；ZIP CRC、16 KB 原生库对齐、10 张密度图标和 17 份许可核对通过 |
| 源码与文案 | README、关于页与发布检查同步更新；源码导出按文件集合与哈希核对 |

本轮使用本机 HTTP 服务验证后端协议和文件上传，未连接真实云存储或进行真机交互验收。
包体验证与自动检查结果见 [validation/0.12.9.json](validation/0.12.9.json)。

## 0.12.8 验证

- 本地与在线播放共用浮点音频链路，读取已有 FLAC 的响度资料。
- 关于页补齐 ChunkSeal 项目致谢、版权声明、听屿专项 GPLv3 授权与上游 AGPLv3 原文。
- 项目保持 GPL-3.0-only，原有第三方署名与许可保留。
- 正式包沿用原签名；应用 ID 为 com.qiuyuxc.tingyu.opensource，versionCode 为 25。
- 本机按维护者要求未运行回归或额外 Lint；核对构建、签名、包名与内置许可。

详细包体验证见 [validation/0.12.8.json](validation/0.12.8.json)。
