package com.qiuyuxc.tingyu

import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap

/**
 * Ncmx 派生密钥的落盘缓存。
 *
 * PBKDF2 在 ART 上 60 万轮要 4.5 秒, 而本地密文库每次冷启动都要把整库的 Meta 解一遍。 进程内存里那份缓存帮不上忙 —— 冷启动就是新进程。口令本来就明文存在同一个
 * App 私有目录 (cloudKey + 旧口令表), 能读这个文件的本来就能读到口令, 所以把 派生结果也放在这儿不额外降低安全性, 却能把首屏那十几秒抹掉。
 *
 * 存的是 `cacheKey \t 密钥base64` 一行一条, 最多留最近用到的 {@link #MAX} 条 (LRU)。换口令、重新生成 salt 会产生新键, 老的按使用时间自然淘汰。
 */
internal class NcmxKeyStore(private val file: File?) : Ncmx.KeyStore {
    private val map = LinkedHashMap<String?, String?>(MAX, 0.75f, true)

    init {
        load()
    }

    @Synchronized
    public override fun load(key: String?): String? {
        return map.get(key)
    }

    @Synchronized
    public override fun save(key: String?, value: String?) {
        map.put(key, value)
        trim()
        flush()
    }

    @Synchronized
    public override fun clear() {
        map.clear()
        flush()
    }

    private fun trim() {
        val it = map.entries.iterator()
        while (map.size > MAX && it.hasNext()) {
            it.next()
            it.remove()
        }
    }

    private fun load() {
        if (!file!!.isFile()) return
        try {
            BufferedReader(
                    InputStreamReader(
                        FileInputStream(file),
                        StandardCharsets.UTF_8,
                    )
                )
                .use({ r ->
                    var line: String?
                    while (true) {
                        line = r.readLine()
                        if (line == null) break
                        val tab = line.indexOf('\t')
                        if (tab <= 0 || tab == line.length - 1) continue
                        map.put(line.substring(0, tab), line.substring(tab + 1))
                    }
                })
        } catch (ignored: Exception) {
            // 缓存读不出来就当没有, 重新派生
        }
    }

    private fun flush() {
        try {
            val fos = FileOutputStream(file)
            try {
                val w = OutputStreamWriter(fos, StandardCharsets.UTF_8)
                for (e in map.entries) {
                    w.write(e.key)
                    w.write('\t'.code)
                    w.write(e.value)
                    w.write('\n'.code)
                }
                w.flush()
            } finally {
                fos.close()
            }
        } catch (ignored: Exception) {
            // 落盘失败不影响解密, 只是下次冷启动还要再派生
        }
    }

    companion object {

        private val MAX = 16
    }
}
