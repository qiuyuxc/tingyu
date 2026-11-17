package com.qiuyuxc.tingyu

import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * ChunkSeal 与旧 NCMX 的随机读取实现。格式与兼容规则见 docs/encrypted-music.md。
 *
 * 分块加密, 块大小固定, 所以「明文偏移 -> 块号」是纯算术。播放器可以只取需要的 那几块 (配合网盘的 HTTP Range), 不必先下完整个文件。
 *
 * 支持 v2 的随机主密钥与显式密码模式；旧文件继续按原规则读取。
 */
object Ncmx {

    enum class Format(val magic: String, val extension: String) {
        LEGACY_NCMX("NCMX", ".ncmx"),
        CHUNKSEAL("CSEA", ".cseal"),
    }

    @JvmStatic
    fun isEncryptedName(name: String?): Boolean =
        name != null && Format.entries.any { name.endsWith(it.extension, ignoreCase = true) }

    @JvmField val VERSION = 1
    @JvmField val CHUNKSEAL_VERSION = 2
    @JvmField val HEADER_SIZE = 64
    @JvmField val TAG_SIZE = 16
    @JvmField val AAD_SIZE = 48
    /**
     * 默认块大小 1 MiB。
     *
     * 实测两个后端: 阿里本地存储 (3 Mbps 上行, 吞吐受限) 1 MiB -> 2.84 秒一块, 能播 8.6 秒, 3.0x R2 经 Cloudflare (请求开销受限) 1
     * MiB -> 2.05 秒一块, 能播 8.6 秒, 4.2x
     *
     * 256 KiB 在阿里那边同样比例(只是起播快一点), 但在 R2 上会被每次请求 1.5 秒的 固定开销吃死(实测只有 1.09 Mbps、实时倍率 1.1, 几乎跟不上)。 1
     * MiB 是两边都不亏的选择。
     */
    @JvmField val DEFAULT_CHUNK_LOG2 = 20 // 1 MiB
    @JvmField val DEFAULT_ROUNDS = 600000
    @JvmField val MAX_CHUNKSEAL_ROUNDS = 2000000
    @JvmField val KDF_PBKDF2_SHA256 = 1
    @JvmField val KDF_HKDF_SHA256 = 2

    private val RECORD_KEY_LABEL =
        "ChunkSeal/v2/record-key\u0000".toByteArray(StandardCharsets.US_ASCII)

    /** Meta 块和数据块用的两个保留块号, 不会和真实数据块撞。 */
    private val NONCE_HEADER = 0xFFFFFFFFL
    private val NONCE_META = 0xFFFFFFFEL

    /**
     * 派生结果的缓存。
     *
     * PBKDF2 在 Android 上很慢 —— 实测 60 万轮要 **4.5 秒** (桌面 JVM 只要 150ms, ART 和它的加密 provider
     * 没有那套加速)。而列表要给每个文件解一次 Meta, 播放又要再解一次, 同一把口令同一份 salt 反复派生纯属浪费。
     *
     * 键里放的是口令的 SHA-256, 不是口令本身。
     */
    /** 列目录时一次读完文件头与最长的 Meta，兼容外部 ChunkSeal 写入器的 65535 字节记录。 */
    @JvmField val META_PREFIX = HEADER_SIZE + 0xFFFF

    private val KEY_CACHE = java.util.concurrent.ConcurrentHashMap<String?, ByteArray?>()

    @JvmField @Volatile var keyStore: KeyStore? = null

    @JvmField @Volatile var trace: Trace? = null

    /**
     * 字节来源。本地文件 (RandomAccessFile) 和网盘 Range 都实现它。
     *
     * 把读和解耦掉, 同一套格式代码既能读本地文件, 也能只按块从云端拉 —— 后者正是流播放要的: 读第 30 秒的数据, 只请求覆盖到的那几块。
     */
    interface Source {
        @Throws(IOException::class) fun length(): Long

        /** 精确读 [offset, offset+length)。短的实现自己补齐或抛错。 */
        @Throws(IOException::class, NcmxException::class)
        fun read(offset: Long, length: Int): ByteArray?
    }

    /** 本地文件。 */
    @JvmStatic
    fun fileSource(raf: RandomAccessFile?): Source? {
        return object : Source {
            @Throws(IOException::class)
            public override fun length(): Long {
                return raf!!.length()
            }

            @Throws(IOException::class)
            public override fun read(offset: Long, length: Int): ByteArray? {
                val out = ByteArray(length)
                raf!!.seek(offset)
                raf.readFully(out)
                return out
            }
        }
    }

    class NcmxException : Exception {
        constructor(m: String?) : super(m) {}

        constructor(m: String?, c: Throwable?) : super(m, c) {}
    }

    class Header {
        @JvmField var format: Format = Format.LEGACY_NCMX
        @JvmField var version: Int = VERSION
        @JvmField var flags: Int = 0
        @JvmField var chunkLog2: Int = 0
        @JvmField var kdfId: Int = 0
        @JvmField var rounds: Int = 0
        @JvmField var metaSize: Int = 0
        /**
         * 这份文件是用「第几把口令」加的密 —— 就是头里偏移 14 那两字节 (原来叫 reserved, 一直没人用)。
         *
         * 放这儿是因为头是**明文可读**的 (不用先有口令), 而且它在 aad 里被 headerTag 认证 —— 改它验签就失败, 不会变成可篡改的元数据。
         *
         * **老文件这里是 0 = 不知道**, 那就退回去逐个试口令。
         */
        @JvmField var keyId: Int = 0
        @JvmField var salt: ByteArray? = null
        @JvmField var noncePrefix: ByteArray? = null
        @JvmField var plainSize: Long = 0
        /** 文件中原有的前 48 字节；v2 在其后绑定记录类型和块号。 */
        @JvmField var aad: ByteArray? = null

        fun chunkSize(): Int {
            return 1 shl chunkLog2
        }

        fun chunkCount(): Long {
            val cs = chunkSize()
            return plainSize / cs + if (plainSize % cs == 0L) 0 else 1
        }

        /** 第 index 块密文在文件里的偏移。 */
        fun chunkOffset(index: Long): Long {
            return HEADER_SIZE.toLong() +
                metaSize.toLong() +
                index * (chunkSize().toLong() + TAG_SIZE)
        }

        /** 整个文件的密文长度。 */
        fun fileSize(): Long {
            return HEADER_SIZE.toLong() + metaSize.toLong() + plainSize + chunkCount() * TAG_SIZE
        }
    }

    // ------------------------------------------------------------- 小工具

    private fun putLe32(b: ByteArray?, p: Int, v: Int) {
        b!![p] = v.toByte()
        b[p + 1] = (v.ushr(8)).toByte()
        b[p + 2] = (v.ushr(16)).toByte()
        b[p + 3] = (v.ushr(24)).toByte()
    }

    private fun le32(b: ByteArray?, p: Int): Int {
        return ((b!![p] and 0xFF) or
            ((b[p + 1] and 0xFF) shl 8) or
            ((b[p + 2] and 0xFF) shl 16) or
            ((b[p + 3] and 0xFF) shl 24))
    }

    private fun putLe16(b: ByteArray?, p: Int, v: Int) {
        b!![p] = v.toByte()
        b[p + 1] = (v.ushr(8)).toByte()
    }

    private fun le16(b: ByteArray?, p: Int): Int {
        return (b!![p] and 0xFF) or ((b[p + 1] and 0xFF) shl 8)
    }

    private fun putLe64(b: ByteArray?, p: Int, v: Long) {
        for (i in 0..7) b!![p + i] = (v.ushr((8 * i))).toByte()
    }

    private fun le64(b: ByteArray?, p: Int): Long {
        var v: Long = 0
        for (i in 7 downTo 0) v = (v shl 8) or (b!![p + i] and 0xFF)
        return v
    }

    private fun nonce(prefix: ByteArray?, index: Long): ByteArray? {
        val n = ByteArray(12)
        System.arraycopy(prefix, 0, n, 0, 8)
        n[8] = (index.ushr(24)).toByte()
        n[9] = (index.ushr(16)).toByte()
        n[10] = (index.ushr(8)).toByte()
        n[11] = index.toByte()
        return n
    }

    @Throws(NcmxException::class)
    private fun gcm(mode: Int, key: ByteArray?, nonce: ByteArray?, aad: ByteArray?): Cipher? {
        try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c!!.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            if (aad != null) c.updateAAD(aad)
            return c
        } catch (e: Exception) {
            throw NcmxException("GCM 初始化失败: " + e.message, e)
        }
    }

    private fun isV2(header: Header): Boolean =
        header.format == Format.CHUNKSEAL && header.version == CHUNKSEAL_VERSION

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(data)
        } catch (e: Exception) {
            throw NcmxException("HKDF 派生失败: " + e.message, e)
        }
    }

    private fun recordId(kind: Int, counter: Long): ByteArray {
        if (counter !in 0..NONCE_HEADER) throw NcmxException("记录编号超出范围")
        return byteArrayOf(
            kind.toByte(),
            (counter ushr 24).toByte(),
            (counter ushr 16).toByte(),
            (counter ushr 8).toByte(),
            counter.toByte(),
        )
    }

    /** v1 的 key 是 AES key，v2 的 key 是该容器的 HKDF PRK。 */
    private fun recordKey(header: Header, key: ByteArray?, kind: Int, counter: Long): ByteArray? {
        if (!isV2(header)) return key
        if (key?.size != 32) throw NcmxException("V2 派生密钥必须是 32 字节")
        return hmac(key, RECORD_KEY_LABEL + header.aad!! + recordId(kind, counter) + byteArrayOf(1))
    }

    private fun recordAad(header: Header, kind: Int, counter: Long): ByteArray? =
        if (isV2(header)) header.aad!! + recordId(kind, counter)
        else if (kind == 1) null else header.aad

    private fun recordCipher(
        mode: Int,
        header: Header,
        key: ByteArray?,
        kind: Int,
        counter: Long,
    ): Cipher =
        gcm(
            mode,
            recordKey(header, key, kind, counter),
            nonce(header.noncePrefix, counter),
            recordAad(header, kind, counter),
        )!!

    /**
     * 派生结果的**落盘**缓存。进程内的 KEY_CACHE 只省得掉同一进程里的重复派生, 冷启动进程重建后就没了 —— 600k 轮在 ART 上 4.5 秒, 本地密文库一启动就要
     * 遍历一遍, 首屏因此空十几秒。
     *
     * 口令本来就明文存在 App 私有目录 (cloudKey + 旧口令表), 能读这份缓存的本来 就能读到口令, 所以落盘不降低安全性。Ncmx 是纯格式层, 落盘交给上层实现 ——
     * JVM 测试里保持 null。
     */
    interface KeyStore {
        fun load(cacheKey: String?): String?

        fun save(cacheKey: String?, value: String?)

        fun clear()
    }

    private fun cacheKey(passphrase: CharArray?, salt: ByteArray?, rounds: Int): String? {
        return rounds + "|" + b64(salt) + "|" + b64(sha256(passphrase))
    }

    /** 派生 32 字节密钥。结果按 (salt, 轮数, 口令) 缓存: 先查内存, 再查落盘。 */
    @JvmStatic
    @Throws(NcmxException::class)
    fun deriveKey(passphrase: CharArray?, salt: ByteArray?, rounds: Int): ByteArray? {
        val ck = cacheKey(passphrase, salt, rounds)
        val hit = KEY_CACHE.get(ck)
        if (hit != null) return hit
        val store = keyStore
        if (store != null) {
            try {
                val saved = store.load(ck)
                if (saved != null && saved.length > 0) {
                    val key = java.util.Base64.getDecoder()!!.decode(saved)
                    if (key!!.size == 32) {
                        KEY_CACHE.put(ck, key)
                        return key
                    }
                }
            } catch (ignored: Exception) {
                // 缓存坏了就当没有, 重新派生
            }
        }
        val t0 = System.currentTimeMillis()
        try {
            val spec = PBEKeySpec(passphrase, salt, rounds, 256)
            val key =
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")!!.generateSecret(spec)!!
                    .getEncoded()
            val dt = System.currentTimeMillis() - t0
            KEY_CACHE.put(ck, key)
            if (store != null) store.save(ck, b64(key))
            val tr = trace
            if (tr != null) tr.line("派生密钥 " + rounds + " 轮 = " + dt + " ms")
            return key
        } catch (e: Exception) {
            throw NcmxException("密钥派生失败: " + e.message, e)
        }
    }

    /** 密码输入只用于 v1 或显式的 v2 密码模式，不尝试解码原始密钥。 */
    @JvmStatic
    @Throws(NcmxException::class)
    fun deriveKeyForHeader(passphrase: CharArray?, header: Header?): ByteArray? {
        val hd = header ?: throw NcmxException("缺少文件头")
        if (isV2(hd) && hd.kdfId != KDF_PBKDF2_SHA256) {
            throw NcmxException("此 V2 文件需要原始主密钥，不能使用口令")
        }
        validatePassphrase(hd.format, passphrase)
        if (hd.rounds <= 0 || (hd.format == Format.CHUNKSEAL && hd.rounds > MAX_CHUNKSEAL_ROUNDS)) {
            throw NcmxException("密钥派生轮数不合理")
        }
        val key = deriveKey(passphrase, hd.salt, hd.rounds)
        return if (isV2(hd)) hmac(hd.salt!!, key!!) else key
    }

    /** 原始主密钥输入只用于 v2 HKDF 模式；返回该容器的 PRK。 */
    @JvmStatic
    @Throws(NcmxException::class)
    fun deriveMasterKey(header: Header?, masterKey: ByteArray?): ByteArray? {
        val hd = header ?: throw NcmxException("缺少文件头")
        if (!isV2(hd) || hd.kdfId != KDF_HKDF_SHA256 || hd.rounds != 0) {
            throw NcmxException("此文件不使用 V2 原始主密钥模式")
        }
        if (masterKey == null || masterKey.size !in listOf(16, 32)) {
            throw NcmxException("V2 主密钥必须是 16 或 32 字节")
        }
        return hmac(hd.salt!!, masterKey)
    }

    /**
     * 诊断用的回调。Ncmx 是纯格式层, 不能依赖 Android 的 MainActivity, 所以由上层 (Cloud/MainActivity) 挂一个进来 —— JVM 测试里保持
     * null。
     */
    interface Trace {
        fun line(s: String?)
    }

    /** 这把口令 + salt 派生过没有 (给诊断用, 不触发派生)。 */
    @JvmStatic
    fun keyCached(passphrase: CharArray?, salt: ByteArray?, rounds: Int): Boolean {
        return KEY_CACHE.containsKey(cacheKey(passphrase, salt, rounds))
    }

    @JvmStatic
    fun clearKeyCache() {
        KEY_CACHE.clear()
        val store = keyStore
        if (store != null) store.clear()
    }

    /** 只清进程内存那份, 落盘那份留着 —— 用来模拟「冷启动」, 见 KeyCacheTest。 */
    @JvmStatic
    fun clearKeyCacheMemoryOnly() {
        KEY_CACHE.clear()
    }

    private fun sha256(s: CharArray?): ByteArray? {
        try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            for (c in s!!) {
                md.update((c.toInt().ushr(8)).toByte())
                md.update(c.toByte())
            }
            return md.digest()
        } catch (e: Exception) {
            return ByteArray(0)
        }
    }

    private fun b64(b: ByteArray?): String? {
        return java.util.Base64.getEncoder()!!.encodeToString(b)
    }

    // ------------------------------------------------------------- 读头部

    @JvmStatic
    @Throws(NcmxException::class, IOException::class)
    fun readHeader(src: Source?): Header? {
        val size = src!!.length()
        if (size < HEADER_SIZE) throw NcmxException("文件太短, 不是加密容器")
        val h = src.read(0, HEADER_SIZE) ?: throw NcmxException("文件头读取失败")
        if (h.size != HEADER_SIZE) throw NcmxException("文件头不完整")
        val magic = String(h, 0, 4, StandardCharsets.US_ASCII)
        val format =
            Format.entries.firstOrNull { it.magic == magic }
                ?: throw NcmxException("不是 ChunkSeal 或 NCMX 文件")
        val hd = Header()
        hd.format = format
        val ver = h[4] and 0xFF
        if (ver != VERSION && !(format == Format.CHUNKSEAL && ver == CHUNKSEAL_VERSION)) {
            throw NcmxException("版本不支持: " + ver)
        }
        hd.version = ver
        hd.flags = h[5] and 0xFF
        hd.chunkLog2 = h[6] and 0xFF
        hd.kdfId = h[7] and 0xFF
        if (hd.kdfId != KDF_PBKDF2_SHA256 && !(isV2(hd) && hd.kdfId == KDF_HKDF_SHA256)) {
            throw NcmxException("KDF 不支持: " + hd.kdfId)
        }
        if (hd.chunkLog2 < 12 || hd.chunkLog2 > 26) throw NcmxException("块大小不合理")
        hd.rounds = le32(h, 8)
        hd.metaSize = le16(h, 12)
        hd.keyId = le16(h, 14) // reserved 位, 老文件是 0 (不知道) -> 退回逐个试口令
        hd.salt = Arrays.copyOfRange(h, 16, 32)
        hd.noncePrefix = Arrays.copyOfRange(h, 32, 40)
        hd.plainSize = le64(h, 40)
        hd.aad = Arrays.copyOfRange(h, 0, AAD_SIZE)
        if (isV2(hd) && hd.kdfId == KDF_HKDF_SHA256) {
            if (hd.rounds != 0) throw NcmxException("随机密钥模式的派生轮数必须是 0")
        } else if (hd.rounds <= 0) {
            throw NcmxException("密钥派生轮数不合理")
        }
        if (hd.plainSize < 0 || hd.chunkCount() > NONCE_META) {
            throw NcmxException("文件大小超过容器上限")
        }
        val overhead = HEADER_SIZE.toLong() + hd.metaSize + hd.chunkCount() * TAG_SIZE
        if (hd.plainSize > Long.MAX_VALUE - overhead) throw NcmxException("文件大小超出范围")
        if (format == Format.CHUNKSEAL) {
            if (hd.rounds > MAX_CHUNKSEAL_ROUNDS) throw NcmxException("密钥派生轮数超过读取上限")
            if (
                (hd.flags and 0xFE) != 0 ||
                    (hd.flags == 0 && hd.metaSize != 0) ||
                    (hd.flags == 1 && hd.metaSize < TAG_SIZE)
            ) {
                throw NcmxException("ChunkSeal 元数据标识或长度不合理")
            }
            // 目录索引只持有前缀；完整的本地或远端 Source 必须提供准确的容器长度。
            if (src !is PrefixSource) validateLength(hd, size)
        }
        return hd
    }

    /** 前缀读取器打开后，调用方须用传输层得到的总长度补做校验。 */
    @JvmStatic
    @Throws(NcmxException::class)
    fun validateLength(header: Header?, size: Long) {
        if (header!!.format == Format.CHUNKSEAL && header.fileSize() != size) {
            throw NcmxException("ChunkSeal 文件被截断或包含多余数据")
        }
    }

    private fun validatePassphrase(format: Format, passphrase: CharArray?) {
        if (format != Format.CHUNKSEAL) return
        val chars = passphrase ?: throw NcmxException("没有可用口令")
        var index = 0
        while (index < chars.size) {
            val char = chars[index++]
            if (Character.isHighSurrogate(char)) {
                if (index >= chars.size || !Character.isLowSurrogate(chars[index++])) {
                    throw NcmxException("口令包含无效的 Unicode 字符")
                }
            } else if (Character.isLowSurrogate(char)) {
                throw NcmxException("口令包含无效的 Unicode 字符")
            }
        }
    }

    /** 验证头部认证标签。这是判断「口令对不对」的唯一地方 —— 数据块用 GCM, 口令错了会在第一块解到一半才炸, 头部的能立刻判出来。 */
    @JvmStatic
    @Throws(NcmxException::class, IOException::class)
    fun verifyHeader(src: Source?, hd: Header?, key: ByteArray?) {
        val tag = src!!.read(AAD_SIZE.toLong(), TAG_SIZE)
        if (tag == null || tag.size != TAG_SIZE) throw NcmxException("头部认证标签不完整")
        try {
            recordCipher(Cipher.DECRYPT_MODE, hd!!, key, 0, NONCE_HEADER).doFinal(tag)
        } catch (e: Exception) {
            throw NcmxException("密钥不对, 或者文件被改过", e)
        }
    }

    /** 一次搞定: 读头 + 派生密钥 + 验签。 */
    @JvmStatic
    @Throws(NcmxException::class, IOException::class)
    fun open(src: Source?, passphrase: CharArray?): Header? {
        val hd = readHeader(src)
        val key = deriveKeyForHeader(passphrase, hd)
        verifyHeader(src, hd, key)
        return hd
    }

    @JvmStatic
    @Throws(NcmxException::class, IOException::class)
    fun openWithMasterKey(src: Source?, masterKey: ByteArray?): Header? {
        val hd = readHeader(src)
        verifyHeader(src, hd, deriveMasterKey(hd, masterKey))
        return hd
    }

    // ------------------------------------------------------------- 解密

    /** 第 index 块的明文。越界返回 null。 */
    @JvmStatic
    @Throws(NcmxException::class, IOException::class)
    fun readChunk(src: Source?, hd: Header?, key: ByteArray?, index: Long): ByteArray? {
        val count = hd!!.chunkCount()
        if (index < 0 || index >= count) return null
        val cs = hd.chunkSize()
        val plainOff = index * cs.toLong()
        val plainLen = Math.min(cs.toLong(), hd.plainSize - plainOff).toInt()
        val ct = src!!.read(hd.chunkOffset(index), plainLen + TAG_SIZE)
        if (ct == null || ct.size != plainLen + TAG_SIZE) throw NcmxException("数据块不完整")
        try {
            return recordCipher(Cipher.DECRYPT_MODE, hd, key, 2, index).doFinal(ct)
        } catch (e: Exception) {
            throw NcmxException("第 " + index + " 块解密失败 (数据损坏或顺序被改)", e)
        }
    }

    /** 随机读明文 [off, off+len)。只解密覆盖到的块 —— 这是整个格式存在的理由。 */
    @JvmStatic
    @Throws(NcmxException::class, IOException::class)
    fun read(src: Source?, hd: Header?, key: ByteArray?, off: Long, len: Int): ByteArray? {
        if (off < 0 || len < 0 || off > hd!!.plainSize || len.toLong() > hd.plainSize - off) {
            throw NcmxException("读取范围超出文件")
        }
        if (len == 0) return ByteArray(0)
        val cs = hd.chunkSize()
        val first = off / cs
        val last = (off + len - 1) / cs
        val out = ByteArray(len)
        var written = 0
        for (i in first..last) {
            val chunk = readChunk(src, hd, key, i)
            val chunkStart = i * cs.toLong()
            val from = Math.max(0, off - chunkStart).toInt()
            val to = Math.min(chunk!!.size.toLong(), off + len - chunkStart).toInt()
            if (to > from) {
                System.arraycopy(chunk, from, out, written, to - from)
                written += to - from
            }
        }
        return out
    }

    /** 打开一个加密容器时一次性拿到的全部东西。 */
    class Info {
        @JvmField var header: Header? = null
        @JvmField var key: ByteArray? = null
        @JvmField var rawMeta: String? = null
        @JvmField var fileName: String? = ""
        @JvmField var title: String? = ""
        @JvmField var artist: String? = ""
        /** Meta 里带的小封面 (JPEG 字节)。老文件没有, 为 null。 */
        @JvmField var cover: ByteArray? = null
        /** Meta 里带的歌词 (原文, 非 base64)。老文件没有, 为空串。 */
        @JvmField var lyrics: String? = ""
        /** Meta 里记的时长 (秒)。老文件没有, 为 0。 */
        @JvmField var durationSec: Int = 0
    }

    /** Meta 块里第一行是文件名, 形如「歌名 - 艺人.ext」; 第二行是 JSON。 */
    @JvmStatic
    fun parseName(info: Info?, raw: String?) {
        if (raw == null) return
        val nl = raw.indexOf('\n')
        var first: String? = if (nl < 0) raw else raw.substring(0, nl)
        info!!.fileName = first
        val dot = first!!.lastIndexOf('.')
        if (dot > 0) first = first.substring(0, dot)
        val dash = first.indexOf(" - ")
        if (dash > 0) {
            info.title = (first.substring(0, dash).trim({ it <= ' ' })).orEmpty()
            info.artist = (first.substring(dash + 3).trim({ it <= ' ' })).orEmpty()
        } else {
            info.title = (first).orEmpty()
        }
        val json = if (nl < 0) null else raw.substring(nl + 1)
        info.cover = metaCover(json)
        info.lyrics = (metaLyrics(json)).orEmpty()
        info.durationSec = metaInt(json, "duration")
    }

    /** 从 Meta 的 JSON 里取一个整数字段 (存成字符串了)。取不到返回 0。 */
    private fun metaInt(json: String?, key: String?): Int {
        try {
            return Integer.parseInt(metaString(json, key)!!.trim({ it <= ' ' }))
        } catch (e: Exception) {
            return 0
        }
    }

    /** 从整段 rawMeta (第一行文件名 + 第二行 JSON) 里取一个字符串字段。 */
    @JvmStatic
    fun metaFieldOf(raw: String?, key: String?): String? {
        val nl = if (raw == null) -1 else raw.indexOf('\n')
        if (nl < 0) return ""
        return metaString(raw!!.substring(nl + 1), key)
    }

    /** 从整段 rawMeta (第一行文件名 + 第二行 JSON) 里取时长 (秒)。 */
    @JvmStatic
    fun metaDurationOf(raw: String?): Int {
        if (raw == null) return 0
        val nl = raw.indexOf('\n')
        return if (nl < 0) 0 else metaInt(raw.substring(nl + 1), "duration")
    }

    /**
     * 从 Meta 的 JSON 里取歌词。
     *
     * 存的时候是 base64 的 —— 歌词里有换行和引号, 直接塞进 JSON 要么得转义, 要么会把「第一行是文件名」这个约定弄坏。
     */
    private fun metaLyrics(json: String?): String? {
        val v = metaString(json, "lyrics")
        if (v!!.length == 0) return ""
        try {
            return String((java.util.Base64.getDecoder()!!.decode(v))!!, Charsets.UTF_8)
        } catch (e: Exception) {
            return ""
        }
    }

    /**
     * 从 Meta 的 JSON 里取内嵌小封面。
     *
     * 有它列表就能秒出封面 —— 不必为了封面去下音频头部那一两兆 (元数据排在 FLAC 头部最后, 而封面本身又很大)。 取不到就返回 null, 调用方退回按需解音频。
     */
    /** 从整段 rawMeta (第一行文件名 + 第二行 JSON) 里取封面。 */
    @JvmStatic
    fun metaCoverOf(raw: String?): ByteArray? {
        if (raw == null) return null
        val nl = raw.indexOf('\n')
        return if (nl < 0) null else metaCover(raw.substring(nl + 1))
    }

    private fun metaCover(json: String?): ByteArray? {
        val v = metaString(json, "cover")
        if (v!!.length == 0) return null
        try {
            return java.util.Base64.getDecoder()!!.decode(v)
        } catch (e: Exception) {
            return null
        }
    }

    /** 取 JSON 里某个键的字符串值。值里保证不含引号 (base64 或转义过的)。 */
    private fun metaString(json: String?, key: String?): String? {
        if (json == null || json.length < key!!.length + 6) return ""
        val i = json.indexOf('"'.toString() + key + '"'.toString())
        if (i < 0) return ""
        val c = json.indexOf(':', i + key.length + 2)
        if (c < 0) return ""
        val q = json.indexOf('"', c + 1)
        if (q < 0) return ""
        val end = json.indexOf('"', q + 1)
        if (end < 0) return ""
        return json.substring(q + 1, end)
    }

    /**
     * 读头部 + 派生密钥 + 验签 + 解 Meta。
     *
     * 这是格式层的活, 不该由某个存储后端来提供 —— 否则 HttpBackend 就得依赖 Cloud, 而 Cloud 依赖 Android, JVM 上根本编不了。
     */
    @JvmStatic
    @Throws(NcmxException::class, IOException::class)
    fun inspect(src: Source?, passphrase: CharArray?): Info? {
        return inspect(src, readHeader(src), passphrase)
    }

    @Throws(NcmxException::class, IOException::class)
    private fun inspect(src: Source?, hd: Header?, passphrase: CharArray?): Info? {
        val key = deriveKeyForHeader(passphrase, hd)
        verifyHeader(src, hd, key)
        return inspectUnlocked(src, hd, key)
    }

    private fun inspectUnlocked(src: Source?, hd: Header?, key: ByteArray?): Info =
        Info().apply {
            header = hd
            this.key = key
            rawMeta = readMeta(src, hd, key)
            parseName(this, rawMeta)
        }

    @JvmStatic
    @Throws(NcmxException::class, IOException::class)
    fun inspectWithMasterKey(src: Source?, masterKey: ByteArray?): Info? {
        val hd = readHeader(src)
        val key = deriveMasterKey(hd, masterKey)
        verifyHeader(src, hd, key)
        return inspectUnlocked(src, hd, key)
    }

    /**
     * 读一份文件时按什么顺序试口令。
     *
     * <p>为什么不是一张写死的口令表: 头里的 keyId 就在明处, 认得出来就能把它排到最前, 省掉一次白试的 PBKDF2 (ART 上 4.5 秒)。换过口令的库里新老文件混着,
     * 这一下省的是 每次列表的固定开销。
     */
    interface Keys {
        fun forKeyId(keyId: Int): List<String?>?

        /** 应用显式解码带类型标记的密钥导出；格式层不猜测密码文本。 */
        fun forMasterKeyId(keyId: Int): List<ByteArray?>? = emptyList()
    }

    /** 仅认证头部，按文件声明的模式尝试同一类型的候选秘密。 */
    @JvmStatic
    @Throws(NcmxException::class, IOException::class)
    fun unlock(src: Source?, hd: Header?, keys: Keys?): ByteArray? {
        val header = hd ?: throw NcmxException("缺少文件头")
        val candidates = keys ?: throw NcmxException("没有可用密钥")
        var last: NcmxException? = null
        if (isV2(header) && header.kdfId == KDF_HKDF_SHA256) {
            for (master in candidates.forMasterKeyId(header.keyId).orEmpty()) {
                if (master == null) continue
                try {
                    val key = deriveMasterKey(header, master)
                    verifyHeader(src, header, key)
                    return key
                } catch (e: NcmxException) {
                    last = e
                }
            }
        } else {
            for (password in candidates.forKeyId(header.keyId).orEmpty()) {
                if (password == null) continue
                try {
                    val key = deriveKeyForHeader(password.toCharArray(), header)
                    verifyHeader(src, header, key)
                    return key
                } catch (e: NcmxException) {
                    last = e
                }
            }
        }
        throw last
            ?: NcmxException(
                "没有可用的" + if (isV2(header) && header.kdfId == KDF_HKDF_SHA256) "V2 主密钥" else "口令"
            )
    }

    @JvmStatic
    @Throws(NcmxException::class, IOException::class)
    fun inspect(src: Source?, keys: Keys?): Info? {
        val hd = readHeader(src)
        return inspectUnlocked(src, hd, unlock(src, hd, keys))
    }

    /** 只有一把口令时的便捷实现。 */
    @JvmStatic
    fun oneKey(passphrase: String?): Keys? {
        return object : Keys {
            public override fun forKeyId(keyId: Int): List<String?>? {
                return listOf<String?>(passphrase)
            }
        }
    }

    @JvmStatic
    fun oneMasterKey(masterKey: ByteArray?): Keys? =
        object : Keys {
            override fun forKeyId(keyId: Int): List<String?> = emptyList()

            override fun forMasterKeyId(keyId: Int): List<ByteArray?> = listOf(masterKey)
        }

    /** 只读一段前缀就把头部和 Meta 都拿到，调用方最多取 META_PREFIX 字节。 */
    @JvmStatic
    @Throws(NcmxException::class, IOException::class)
    fun inspectPrefix(prefix: ByteArray?, passphrase: CharArray?): Info? {
        return inspect(prefixSource(prefix), passphrase)
    }

    /**
     * 同上, 但口令给的是一串候选。
     *
     * <p>为什么列表这条路上必须给候选: 换过口令之后, 同一个目录里老文件还是旧口令加的密。 只试当前那把的话这些文件全都验签失败, 歌名就没了 —— 界面上只剩
     * &lt;内容哈希&gt;.ncmx。 头是明文可读的, 先拿 keyId 再决定试哪把, 顺序由 {@link Keys} 定。
     */
    @JvmStatic
    @Throws(NcmxException::class, IOException::class)
    fun inspectPrefix(prefix: ByteArray?, keys: Keys?): Info? {
        return inspect(prefixSource(prefix), keys)
    }

    /**
     * 把一段前缀包成 Source。
     *
     * 64 字节头部包含验签标签，随后最多是 65535 字节 Meta；一次 Range 请求即可拿到完整前缀。
     */
    @JvmStatic
    fun prefixSource(prefix: ByteArray?): Source? {
        return PrefixSource(prefix!!)
    }

    private class PrefixSource(private val prefix: ByteArray) : Source {
        override fun length(): Long = prefix.size.toLong()

        @Throws(NcmxException::class)
        override fun read(offset: Long, length: Int): ByteArray {
            if (
                offset < 0 ||
                    length < 0 ||
                    offset > prefix.size ||
                    length.toLong() > prefix.size - offset
            ) {
                throw NcmxException("超出前缀")
            }
            return prefix.copyOfRange(offset.toInt(), offset.toInt() + length)
        }
    }

    /** 读 Meta 块 (加密的文件名 + 标签 JSON)。没有返回 null。 */
    @JvmStatic
    @Throws(NcmxException::class, IOException::class)
    fun readMeta(src: Source?, hd: Header?, key: ByteArray?): String? {
        return readMetaBytes(src, hd, key)?.let { String(it, StandardCharsets.UTF_8) }
    }

    /** 通用 ChunkSeal 元数据是字节串，UTF-8 音乐标签只是上层约定。 */
    @JvmStatic
    @Throws(NcmxException::class, IOException::class)
    fun readMetaBytes(src: Source?, hd: Header?, key: ByteArray?): ByteArray? {
        if ((hd!!.flags and 1) == 0 || hd.metaSize == 0) return null
        val ct = src!!.read(HEADER_SIZE.toLong(), hd.metaSize)
        if (ct == null || ct.size != hd.metaSize) throw NcmxException("Meta 块不完整")
        try {
            return recordCipher(Cipher.DECRYPT_MODE, hd, key, 1, NONCE_META).doFinal(ct)
        } catch (e: Exception) {
            throw NcmxException("Meta 块解密失败", e)
        }
    }
}
