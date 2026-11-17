package com.qiuyuxc.tingyu

import java.security.SecureRandom

/** 明确标记的随机主密钥导出；无标记的文本始终保留为旧口令。 */
object ChunkSealKeys {
    const val PREFIX = "cseal2:"

    @JvmStatic fun isExport(value: String?): Boolean = value?.startsWith(PREFIX) == true

    @JvmStatic
    fun generate(): String {
        val raw = ByteArray(32)
        SecureRandom().nextBytes(raw)
        return encode(raw)
    }

    @JvmStatic
    fun encode(raw: ByteArray): String {
        require(raw.size == 16 || raw.size == 32) { "随机主密钥必须是 16 或 32 字节" }
        val digits = "0123456789abcdef"
        return buildString(PREFIX.length + raw.size * 2) {
            append(PREFIX)
            for (byte in raw) {
                val value = byte.toInt() and 0xFF
                append(digits[value ushr 4])
                append(digits[value and 0x0F])
            }
        }
    }

    /** 严格解码显式导出，不 trim，也不推测普通十六进制文本的来源。 */
    @JvmStatic
    fun decode(value: String): ByteArray {
        require(isExport(value)) { "随机主密钥缺少 cseal2: 标记" }
        val hex = value.substring(PREFIX.length)
        require(hex.length == 32 || hex.length == 64) { "随机主密钥导出长度不正确" }
        return ByteArray(hex.length / 2) { index ->
            val high = Character.digit(hex[index * 2], 16)
            val low = Character.digit(hex[index * 2 + 1], 16)
            require(
                high >= 0 && low >= 0 && hex[index * 2].code < 128 && hex[index * 2 + 1].code < 128
            ) {
                "随机主密钥导出包含无效的十六进制字符"
            }
            ((high shl 4) or low).toByte()
        }
    }

    /** 只为文件声明的随机密钥模式提供已标记、已解码的候选。 */
    internal fun rawCandidates(values: Iterable<String>): List<ByteArray?> {
        val valid = mutableListOf<ByteArray?>()
        var invalid: IllegalArgumentException? = null
        for (value in values) {
            if (!isExport(value)) continue
            try {
                valid.add(decode(value))
            } catch (error: IllegalArgumentException) {
                if (invalid == null) invalid = error
            }
        }
        // 读旧库时坏掉的一条候选不阻断其他明确的随机密钥，也不会改走口令模式。
        if (valid.isEmpty() && invalid != null) throw invalid
        return valid
    }
}
