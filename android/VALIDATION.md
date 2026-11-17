# 听屿 Android 验证记录

## 0.12.11 恢复现有密钥页

2026-11-17，复用完整版 `SettingsPage.securitySettings` 的分组卡片、当前密钥、
并排复制与重新生成按钮、「高级」折叠、统一导入弹窗及旧凭据复制。
入口恢复到“我的 → 设置 → 音乐与文件 → 加密与密钥”，曲库与云端菜单打开同一页面。
`CloudKeyRing` 与 `ChunkSealKeys` 复用原实现；读取路径共用密钥环，兼容上一版已导入的凭据。

本版已经维护者确认发布。本次按要求直接出包，未运行单元测试、回归测试、Lint、
播放诊断或设备界面检查；发布提交跳过重复的 GitHub 自动检查。
出包使用 `assembleRelease -x lintVitalRelease`，版本为 0.12.11 / versionCode 28。
签名与包体记录见 [validation/0.12.11.json](validation/0.12.11.json)。
下方 0.12.10 的测试和播放结果属于上一版，不作为本次重新验证的结果。

## 0.12.10 加密音乐读取与播放

2026-11-17，修复公开版导出时遗漏加密音乐读取与播放链路的问题。
本地扫描识别 ChunkSeal V1/V2 的 `.cseal` 与旧 `.ncmx`，缺少凭据时仍保留歌曲并提示导入。
设置新增随机密钥和口令导入，保留原始密码文本及已导入凭据。
云端音乐菜单提供播放入口，HTTP、S3 和 OpenList 按需读取音频块。
解密数据接入已有 Media3 浮点音频配置，沿用响度处理；切歌、停止和失败释放底层文件，
拖动时允许读取器重新打开。云端历史保存对应来源，连接变更后提示重新打开文件列表。

| 检查 | 结果 |
| --- | --- |
| 单元测试 | 241 项通过，无失败、错误或跳过；原有 218 项及新增 23 项 |
| 格式向量 | ChunkSeal V1 三组、V2 四组及独立 Python 生成的旧 NCMX 一组，完整音频与元数据一致 |
| 读取与凭据 | 跨块读取、短尾块、EOF、损坏块不改写目标缓冲区、截断文件、类型隔离、原始密码文本、源释放与关闭正在进行的读取通过 |
| 云端后端 | 本机服务验证 HTTP、S3、OpenList 的加密文件列表与按块播放；打开操作结束后继续读取及取消网络请求通过 |
| Lint | app Debug Lint 106 Warning / 7 Hint，lrcview 4 Warning，无 Error / Fatal；Release Lint Vital 通过 |
| 构建与签名 | assembleRelease 通过；com.qiuyuxc.tingyu.opensource，0.12.10 / versionCode 27，沿用原发布签名 |
| 包体 | ZIP CRC、16 KB 原生库对齐、10 张密度图标和 17 份内置许可核对通过 |
| 正式 APK 实际读取 | 独立 Android 进程加载签名 APK，完整读回 167,293,911 字节的旧 `.cseal` 试听文件，音频 SHA-256 与原样本一致；Media3 读取器重新打开、末尾拖动及 FLAC 识别通过 |

包体结果见 [validation/0.12.10.json](validation/0.12.10.json)，实际读取结果见
[validation/encrypted-playback-0.12.10.json](validation/encrypted-playback-0.12.10.json)。
复验工具为 [tools/run-encrypted-playback-smoke.py](tools/run-encrypted-playback-smoke.py)。

APK SHA-256：`9c642934ad3800b0341395ef7880cff13adb6a14acb050a56cfd64b53852704e`。
本轮没有输出音频或进行应用窗口交互，也未连接真实云存储账号。
整首帧提取的诊断尝试被系统终止，未计为成功；完成的 APK 检查范围为完整认证读取、
读取器关闭后的重新打开、末尾拖动与 FLAC 格式识别。实际试听与界面验收留待安装后完成。

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
