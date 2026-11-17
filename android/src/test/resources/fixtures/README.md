# 平台响应样例

JSON 文件用于测试酷狗歌单、排行榜音源与播放权限字段的解析。
回归测试使用模拟网络响应，不依赖真实账号或在线服务。

## Encrypted music

`chunkseal-v1.properties` and `chunkseal-v2.properties` contain the protocol repository's fixed known-answer vectors, used under Tingyu's GPLv3 integration permission.
Their copyright and license notices are retained in the files.
`ncmx-read.properties` contains a synthetic legacy vector generated independently
with Python cryptography, using fixed randomness only for the test.
No diagnostic music recording is included in this directory.
