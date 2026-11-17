# 听屿 Android 验证记录

## 0.12.9 云端文件与上传

2026-11-16，新增 S3、OpenList 和 HTTP 存储连接、云端文件列表与上传入口。
曲库可打开云端目录并刷新列表；本地歌曲菜单可直接上传，系统文件选择器支持多选。
上传保留原始内容和文件名，显示进度与逐项结果，遇到同名文件提示改名后上传。
关闭上传面板会取消当前任务并清理临时副本，连续上传的面板回调按批次隔离。

2026-11-17，收尾检查修复 OpenList 对空格文件名的编码和上传失败响应的处理。
HTTP 和 OpenList 上传增加禁止覆盖的请求条件；取消操作会断开正在等待响应的连接，
分页结束后释放已关闭的连接。ChunkSeal 的 GPLv3 集成授权限定到听屿的具体仓库，
关于页与内置许可同步引用听屿和协议来源。正式包从最终源码重新构建、签名和核对。

| 检查 | 结果 |
| --- | --- |
| 单元测试 | 218 项通过，无失败、错误或跳过；其中 FileStorageTest 16 项 |
| 后端专项 | AWS 签名示例、S3 分页与原始字节上传、HTTP 清单及空文件、OpenList 分页、空格与加号文件名、服务端失败响应、条件写入和取消等待通过 |
| Lint | app Debug Lint 103 Warning / 4 Hint，lrcview 4 Warning，无 Error / Fatal；Release Lint Vital 通过 |
| 构建 | assembleDebug、assembleRelease 通过 |
| 包名与版本 | com.qiuyuxc.tingyu.opensource，0.12.9 / versionCode 26 |
| 正式包核对 | 沿用原签名；ZIP CRC、16 KB 原生库对齐、10 张密度图标和 17 份许可核对通过 |
| 源码与文案 | README、关于页与发布检查同步更新；源码导出按文件集合与哈希核对 |

本轮使用本机 HTTP 服务验证后端协议和文件上传，未连接真实云存储或进行真机交互验收。
包体验证与自动检查结果见 [validation/0.12.9.json](validation/0.12.9.json)。

## 0.12.8 验证

- 本地与在线播放共用浮点音频链路，读取已有 FLAC 的响度资料。
- 关于页补齐 ChunkSeal 项目致谢、版权声明、GPLv3 集成授权与上游 AGPLv3 原文。
- 项目保持 GPL-3.0-only，原有第三方署名与许可保留。
- 正式包沿用原签名；应用 ID 为 com.qiuyuxc.tingyu.opensource，versionCode 为 25。
- 本机按维护者要求未运行回归或额外 Lint；核对构建、签名、包名与内置许可。

详细包体验证见 [validation/0.12.8.json](validation/0.12.8.json)。
