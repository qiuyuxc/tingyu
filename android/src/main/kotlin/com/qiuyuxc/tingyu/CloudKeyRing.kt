package com.qiuyuxc.tingyu

import java.util.Base64

/** 不依赖 Android 的凭据变换；每次变换完成后由 Cloud 一次性保存全部字段。 */
internal data class CloudKeyRing(
    val current: String,
    val currentId: Int,
    val old: List<Entry>,
) {
    data class Entry(val id: Int, val value: String)

    fun candidates(keyId: Int): List<String> =
        linkedSetOf<String>()
            .apply {
                if (keyId in 1..MAX_ID) {
                    if (currentId == keyId && current.isNotEmpty()) add(current)
                    old.filter { it.id == keyId }.forEach { add(it.value) }
                }
                if (current.isNotEmpty()) add(current)
                old.forEach { add(it.value) }
            }
            .toList()

    fun ensureCurrentId(): CloudKeyRing =
        if (current.isEmpty() || currentId in 1..MAX_ID) this else copy(currentId = nextId())

    fun ensureMasterKey(generate: () -> String = ChunkSealKeys::generate): CloudKeyRing {
        if (ChunkSealKeys.isExport(current)) {
            ChunkSealKeys.decode(current)
            return ensureCurrentId()
        }
        val exported = generate()
        ChunkSealKeys.decode(exported)
        return withCurrent(exported)
    }

    /** 清空也先沉淀当前凭据；导入与轮换从不覆盖同编号的其他旧凭据。 */
    fun withCurrent(next: String): CloudKeyRing {
        if (ChunkSealKeys.isExport(next)) ChunkSealKeys.decode(next)
        if (next == current) return ensureCurrentId()
        val previous = if (current.isNotEmpty()) ensureCurrentId() else this
        val kept = previous.old.toMutableList()
        if (previous.current.isNotEmpty()) {
            val entry = Entry(previous.currentId, previous.current)
            if (!kept.contains(entry)) kept.add(0, entry)
        }
        if (next.isEmpty()) return CloudKeyRing("", 0, kept)
        return CloudKeyRing(next, previous.nextId(), kept)
    }

    fun importOld(value: String): CloudKeyRing {
        if (value.isEmpty()) return this
        if (ChunkSealKeys.isExport(value)) ChunkSealKeys.decode(value)
        if (value == current || old.any { it.value == value }) return this
        return copy(old = listOf(Entry(nextId(), value)) + old)
    }

    fun oldCount(): Int = old.map { it.value }.distinct().size

    /** 优先沿用旧文件的文本格式；控制字符与 UTF-16 代理项采用无损编码。 */
    fun serializedOld(): String {
        if (
            old.none { entry ->
                entry.value.any { it < ' ' && it != '\t' || it in '\uD800'..'\uDFFF' }
            }
        ) {
            return old.joinToString("") { "${it.id}\t${it.value}\n" }
        }
        return ENCODED_PREFIX +
            old.joinToString("") { entry ->
                val chars = entry.value
                val bytes = ByteArray(chars.length * 2)
                for (index in chars.indices) {
                    bytes[index * 2] = (chars[index].code ushr 8).toByte()
                    bytes[index * 2 + 1] = chars[index].code.toByte()
                }
                "${entry.id}\t${Base64.getEncoder().encodeToString(bytes)}\n"
            }
    }

    private fun nextId(): Int {
        val used = old.map { it.id }.toHashSet()
        if (currentId in 1..MAX_ID && current.isNotEmpty()) used.add(currentId)
        val start = if (currentId in 1 until MAX_ID) currentId + 1 else 1
        for (offset in 0 until MAX_ID) {
            val candidate = (start - 1 + offset) % MAX_ID + 1
            if (candidate !in used) return candidate
        }
        throw IllegalStateException("密钥编号已用完，请先备份并整理旧密钥")
    }

    companion object {
        private const val MAX_ID = 0xFFFF
        private const val ENCODED_PREFIX = "cseal-keyring-v2\n"

        fun fromStored(current: String?, currentId: Int, serialized: String?): CloudKeyRing {
            val text = serialized.orEmpty()
            val encoded = text.startsWith(ENCODED_PREFIX)
            val rows = if (encoded) text.substring(ENCODED_PREFIX.length) else text
            val entries = mutableListOf<Entry>()
            for (line in rows.splitToSequence('\n')) {
                if (line.isEmpty()) continue
                val tab = line.indexOf('\t')
                require(tab > 0) { "旧密钥表格式不正确" }
                val id =
                    line.substring(0, tab).trim().toIntOrNull()
                        ?: throw IllegalArgumentException("旧密钥编号不正确")
                val stored = line.substring(tab + 1)
                val value =
                    if (encoded) {
                        val bytes = Base64.getDecoder().decode(stored)
                        require(bytes.size % 2 == 0) { "旧密钥编码不完整" }
                        CharArray(bytes.size / 2) { index ->
                                (((bytes[index * 2].toInt() and 0xFF) shl 8) or
                                        (bytes[index * 2 + 1].toInt() and 0xFF))
                                    .toChar()
                            }
                            .concatToString()
                    } else stored
                if (value.isNotEmpty())
                    entries.add(Entry(id.takeIf { it in 1..MAX_ID } ?: 0, value))
            }
            return CloudKeyRing(
                current.orEmpty(),
                currentId.takeIf { it in 1..MAX_ID } ?: 0,
                entries.distinct(),
            )
        }
    }
}
