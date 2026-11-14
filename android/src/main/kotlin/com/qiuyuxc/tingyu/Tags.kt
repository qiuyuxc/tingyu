package com.qiuyuxc.tingyu

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.ArrayList

/** 往 flac / mp3 里写标签。 */
object Tags {

    // ================= FLAC =================

    /** FLAC 头解析结果: 保留哪些块, 音频帧从哪开始。 */
    class FlacHead {
        @JvmField var streamInfo: ByteArray? = null
        @JvmField var keep: MutableList<ByteArray?>? = ArrayList<ByteArray?>()
        @JvmField var framesOffset: Int = 0
    }

    @JvmStatic
    @Throws(IOException::class)
    fun parseFlacHead(head: ByteArray?): FlacHead? {
        if (
            head!!.size < 4 ||
                head[0] != 'f'.code.toByte() ||
                head[1] != 'L'.code.toByte() ||
                head[2] != 'a'.code.toByte() ||
                head[3] != 'C'.code.toByte()
        ) {
            throw IOException("不是 FLAC 流")
        }
        val h = FlacHead()
        var pos = 4
        while (true) {
            if (pos + 4 > head.size) throw IOException("FLAC 头部被截断")
            val flag = head[pos] and 0xFF
            val last = (flag and 0x80) != 0
            val type = flag and 0x7F
            val len =
                ((head[pos + 1] and 0xFF) shl 16) or
                    ((head[pos + 2] and 0xFF) shl 8) or
                    (head[pos + 3] and 0xFF)
            if (pos + 4 + len > head.size) throw IOException("FLAC 块被截断")
            val block = ByteArray(4 + len)
            System.arraycopy(head, pos, block, 0, block.size)
            if (type == 0) {
                h.streamInfo = block
            } else if (type != 4 && type != 6) {
                h.keep!!.add(block) // 丢掉旧的注释块和图片块, 待会重写
            }
            pos += 4 + len
            if (last) break
        }
        h.framesOffset = pos
        return h
    }

    private fun flacBlock(type: Int, data: ByteArray?): ByteArray? {
        val out = ByteArray(4 + data!!.size)
        out[0] = type.toByte()
        out[1] = ((data.size shr 16) and 0xFF).toByte()
        out[2] = ((data.size shr 8) and 0xFF).toByte()
        out[3] = (data.size and 0xFF).toByte()
        System.arraycopy(data, 0, out, 4, data.size)
        return out
    }

    private fun le32(b: ByteArrayOutputStream?, v: Int) {
        b!!.write(v and 0xFF)
        b.write((v shr 8) and 0xFF)
        b.write((v shr 16) and 0xFF)
        b.write((v shr 24) and 0xFF)
    }

    private fun be32(b: ByteArrayOutputStream?, v: Int) {
        b!!.write((v shr 24) and 0xFF)
        b.write((v shr 16) and 0xFF)
        b.write((v shr 8) and 0xFF)
        b.write(v and 0xFF)
    }

    private fun vorbisComment(comments: MutableList<String?>?): ByteArray? {
        val body = ByteArrayOutputStream()
        val vendor = "Tingyu".toByteArray(StandardCharsets.UTF_8)
        le32(body, vendor.size)
        body.write(vendor, 0, vendor.size)
        le32(body, comments!!.size)
        for (c in comments) {
            val b = c!!.toByteArray(StandardCharsets.UTF_8)
            le32(body, b.size)
            body.write(b, 0, b.size)
        }
        return flacBlock(4, body.toByteArray())
    }

    private fun picture(image: ByteArray?): ByteArray? {
        val body = ByteArrayOutputStream()
        be32(body, 3) // front cover
        val mime =
            if ((image!!.size > 1 && (image[0] and 0xFF) == 0x89)) "image/png" else "image/jpeg"
        val m = mime.toByteArray(StandardCharsets.US_ASCII)
        be32(body, m.size)
        body.write(m, 0, m.size)
        val desc = "Cover".toByteArray(StandardCharsets.UTF_8)
        be32(body, desc.size)
        body.write(desc, 0, desc.size)
        be32(body, 0)
        be32(body, 0)
        be32(body, 0)
        be32(body, 0)
        be32(body, image.size)
        body.write(image, 0, image.size)
        return flacBlock(6, body.toByteArray())
    }

    /** 拼出新的 FLAC 头 (fLaC + 各元数据块), 音频帧由调用方接着写。 */
    @JvmStatic
    fun buildFlacHeader(
        h: FlacHead?,
        comments: MutableList<String?>?,
        cover: ByteArray?,
    ): ByteArray? {
        val blocks = ArrayList<ByteArray?>()
        if (h!!.streamInfo != null) blocks.add(h.streamInfo)
        blocks.addAll(h.keep!!)
        blocks.add(vorbisComment(comments))
        if (cover != null && cover.size > 0) blocks.add(picture(cover))

        val out = ByteArrayOutputStream()
        out.write('f'.code)
        out.write('L'.code)
        out.write('a'.code)
        out.write('C'.code)
        for (i in blocks.indices) {
            val blk = blocks.get(i)
            val type = blk!![0] and 0x7F
            blk[0] = ((if (i == blocks.size - 1) 0x80 else 0x00) or type).toByte()
            out.write(blk, 0, blk.size)
        }
        return out.toByteArray()
    }

    // ================= ID3v2.3 =================

    private fun syncsafe(v: Int): ByteArray? {
        return byteArrayOf(
            ((v shr 21) and 0x7F).toByte(),
            ((v shr 14) and 0x7F).toByte(),
            ((v shr 7) and 0x7F).toByte(),
            (v and 0x7F).toByte(),
        )
    }

    private fun utf16(s: String?): ByteArray? {
        val raw = s!!.toByteArray(StandardCharsets.UTF_16) // 自带 BOM
        return raw
    }

    private fun id3TextFrame(id: String?, value: String?): ByteArray? {
        val body = ByteArrayOutputStream()
        body.write(0x01) // UTF-16 with BOM
        val t = utf16(value)
        body.write(t, 0, t!!.size)
        return id3Frame(id, body.toByteArray())
    }

    private fun id3Frame(id: String?, data: ByteArray?): ByteArray? {
        val b = ByteArrayOutputStream()
        val idb = id!!.toByteArray(StandardCharsets.US_ASCII)
        b.write(idb, 0, 4)
        be32(b, data!!.size)
        b.write(0)
        b.write(0)
        b.write(data, 0, data.size)
        return b.toByteArray()
    }

    private fun id3Uslt(text: String?): ByteArray? {
        val body = ByteArrayOutputStream()
        body.write(0x01) // UTF-16
        body.write('c'.code)
        body.write('h'.code)
        body.write('i'.code)
        body.write(0)
        body.write(0) // 空描述 (UTF-16 下两个 0 字节)
        val t = utf16(text)
        body.write(t, 0, t!!.size)
        return id3Frame("USLT", body.toByteArray())
    }

    private fun id3Apic(image: ByteArray?): ByteArray? {
        val body = ByteArrayOutputStream()
        body.write(0x00) // ISO-8859-1
        val mime =
            if ((image!!.size > 1 && (image[0] and 0xFF) == 0x89)) "image/png" else "image/jpeg"
        val m = mime.toByteArray(StandardCharsets.US_ASCII)
        body.write(m, 0, m.size)
        body.write(0)
        body.write(3) // front cover
        body.write(0) // 空描述
        body.write(image, 0, image.size)
        return id3Frame("APIC", body.toByteArray())
    }

    /** 生成完整的 ID3v2.3 标签。 */
    @JvmStatic
    fun buildId3(
        title: String?,
        artists: String?,
        album: String?,
        lyric: String?,
        cover: ByteArray?,
    ): ByteArray? {
        val frames = ByteArrayOutputStream()
        try {
            if (title != null && title.length > 0) frames.write(id3TextFrame("TIT2", title))
            if (artists != null && artists.length > 0) frames.write(id3TextFrame("TPE1", artists))
            if (album != null && album.length > 0) frames.write(id3TextFrame("TALB", album))
            if (lyric != null && lyric.length > 0) frames.write(id3Uslt(lyric))
            if (cover != null && cover.size > 0) frames.write(id3Apic(cover))
        } catch (ignored: IOException) {
            // ByteArrayOutputStream 不会抛
        }

        val body = frames.toByteArray()
        val out = ByteArrayOutputStream()
        out.write('I'.code)
        out.write('D'.code)
        out.write('3'.code)
        out.write(3)
        out.write(0)
        out.write(0)
        val size = syncsafe(body.size)
        out.write(size, 0, 4)
        out.write(body, 0, body.size)
        return out.toByteArray()
    }

    // ================= 读 / 重写已有文件 =================

    /** 读 FLAC 的 Vorbis 注释, 键统一转大写。 */
    @JvmStatic
    @Throws(IOException::class)
    fun readVorbis(f: File?): MutableMap<String?, String?>? {
        val out = java.util.HashMap<String?, String?>()
        val raf = RandomAccessFile(f, "r")
        try {
            val magic = ByteArray(4)
            raf.readFully(magic)
            if (
                magic[0] != 'f'.code.toByte() ||
                    magic[1] != 'L'.code.toByte() ||
                    magic[2] != 'a'.code.toByte() ||
                    magic[3] != 'C'.code.toByte()
            ) {
                throw IOException("不是 FLAC")
            }
            while (true) {
                val flag = raf.read()
                if (flag < 0) break
                val last = (flag and 0x80) != 0
                val type = flag and 0x7F
                val b1 = raf.read()
                val b2 = raf.read()
                val b3 = raf.read()
                if (b1 < 0 || b2 < 0 || b3 < 0) break
                val len = (b1 shl 16) or (b2 shl 8) or b3
                if (type == 4) {
                    val data = ByteArray(len)
                    raf.readFully(data)
                    parseVorbis(data, out)
                } else {
                    raf.seek(raf.getFilePointer() + len)
                }
                if (last) break
            }
        } finally {
            raf.close()
        }
        return out
    }

    /** 是不是 FLAC (看头四个字节)。 */
    private fun isFlac(f: File?): Boolean {
        try {
            val raf = RandomAccessFile(f, "r")
            try {
                val m = ByteArray(4)
                raf.readFully(m)
                return m[0] == 'f'.code.toByte() &&
                    m[1] == 'L'.code.toByte() &&
                    m[2] == 'a'.code.toByte() &&
                    m[3] == 'C'.code.toByte()
            } finally {
                raf.close()
            }
        } catch (e: Exception) {
            return false
        }
    }

    // ---------------------------------------------------------------- MP4 (m4a)

    /**
     * 在 MP4 的 box 树里按类型找一个 box, 返回 [内容起点, 内容终点); 找不到返回 null。
     *
     * 用 RandomAccessFile 跳着读 —— moov 可能几十 KB, 也可能因为没做 faststart 排在 文件尾巴上, 整个读进内存不合适。
     */
    @Throws(IOException::class)
    private fun seekBox(raf: RandomAccessFile?, from: Long, to: Long, type: String?): LongArray? {
        var p = from
        val hdr = ByteArray(16)
        while (p + 8 <= to) {
            raf!!.seek(p)
            raf.readFully(hdr, 0, 8)
            var size = u32(hdr, 0)
            var hlen = 8
            if (size == 1L) { // 64 位长度
                raf.readFully(hdr, 8, 8)
                size = 0
                for (i in 0..7) size = (size shl 8) or (hdr[8 + i] and 0xFF)
                hlen = 16
            } else if (size == 0L) { // 一直到最后
                size = to - p
            }
            if (size < hlen || p + size > to) return null
            if (type == String((hdr), 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1)) {
                return longArrayOf(p + hlen, p + size)
            }
            p += size
        }
        return null
    }

    private fun u32(b: ByteArray?, o: Int): Long {
        return (((b!![o] and 0xFF).toLong() shl 24) or
            ((b[o + 1] and 0xFF) shl 16).toLong() or
            ((b[o + 2] and 0xFF) shl 8).toLong() or
            (b[o + 3] and 0xFF).toLong())
    }

    /** 取 m4a 里某个 ilst 字段的原始字节 (covr / ©lyr / ©nam / ©ART / ©alb)。没有返回 null。 */
    @JvmStatic
    fun mp4Field(f: File?, key: String?): ByteArray? {
        var raf: RandomAccessFile? = null
        try {
            raf = RandomAccessFile(f, "r")
            val moov = seekBox(raf, 0, raf.length(), "moov")
            if (moov == null) return null
            val udta = seekBox(raf, moov[0], moov[1], "udta")
            if (udta == null) return null
            val meta = seekBox(raf, udta[0], udta[1], "meta")
            if (meta == null) return null
            // meta 比别的 box 多 4 字节 version/flags, 子 box 从 +4 起
            val ilst = seekBox(raf, meta[0] + 4, meta[1], "ilst")
            if (ilst == null) return null
            val item = seekBox(raf, ilst[0], ilst[1], key)
            if (item == null) return null
            val data = seekBox(raf, item[0], item[1], "data")
            if (data == null) return null
            // data: 4 字节 version/flags (图片类型也在里面) + 4 字节 locale, 剩下才是内容
            val len = data[1] - data[0] - 8
            if (len <= 0) return null
            val out = ByteArray(len.toInt())
            raf.seek(data[0] + 8)
            raf.readFully(out)
            return out
        } catch (e: Exception) {
            return null
        } finally {
            if (raf != null) {
                try {
                    raf.close()
                } catch (ignored: IOException) {}
            }
        }
    }

    /** m4a 的文本标签 (©lyr / ©nam / ©ART / ©alb)。没有返回 null。 */
    @JvmStatic
    fun readMp4Text(f: File?, key: String?): String? {
        val raw = mp4Field(f, key)
        if (raw == null || raw.size == 0) return null
        val s = String((raw), java.nio.charset.StandardCharsets.UTF_8).trim({ it <= ' ' })
        return if (s.length == 0) null else s
    }

    /** m4a 的内嵌封面 (jpeg/png 字节)。没有返回 null。 */
    @JvmStatic
    fun mp4Cover(f: File?): ByteArray? {
        return mp4Field(f, "covr")
    }

    /**
     * m4a 的音频编码四字符码 (mp4a=AAC / alac / av3a=菁彩声 ...)。
     *
     * 为什么要认它: 网易云的「臻品音质」下来是 av3a (菁彩声), 外面套 MP4 壳, 而 这台机器的系统解码器里没有 av3a —— MediaPlayer 会 prepare
     * 成功、解码时报 what=1 extra=-1007，所以播放前可以先识别编码。
     */
    @JvmStatic
    fun mp4Codec(f: File?): String? {
        var raf: RandomAccessFile? = null
        try {
            raf = RandomAccessFile(f, "r")
            val moov = seekBox(raf, 0, raf.length(), "moov")
            if (moov == null) return ""
            val trak = seekBox(raf, moov[0], moov[1], "trak")
            if (trak == null) return ""
            val mdia = seekBox(raf, trak[0], trak[1], "mdia")
            if (mdia == null) return ""
            val minf = seekBox(raf, mdia[0], mdia[1], "minf")
            if (minf == null) return ""
            val stbl = seekBox(raf, minf[0], minf[1], "stbl")
            if (stbl == null) return ""
            val stsd = seekBox(raf, stbl[0], stbl[1], "stsd")
            if (stsd == null) return ""
            // stsd: 4 字节 version/flags + 4 字节 entry count, 然后每个 sample entry 是
            // [4 字节 size][4 字节 编码], 所以编码在内容起点 +12 处
            raf.seek(stsd[0] + 12)
            val t = ByteArray(4)
            raf.readFully(t)
            return String((t), java.nio.charset.StandardCharsets.ISO_8859_1).trim({ it <= ' ' })
        } catch (e: Exception) {
            return ""
        } finally {
            if (raf != null) {
                try {
                    raf.close()
                } catch (ignored: IOException) {}
            }
        }
    }

    /** 这个音频文件是什么编码。FLAC 直接认, 其他只在 MP4 里认, 认不出返回空串。 */
    @JvmStatic
    fun audioCodec(f: File?): String? {
        if (isFlac(f)) return "flac"
        return mp4Codec(f)
    }

    /** 这个编码这台机器放不了 (系统解码器里没有)。 */
    @JvmStatic
    fun isUndecodable(codec: String?): Boolean {
        return "av3a".equals(codec!!, ignoreCase = true)
    }

    /**
     * 读 FLAC 的 STREAMINFO, 返回 {采样率, 位深, 声道, 秒数}。不是 FLAC 返回 null。
     *
     * STREAMINFO 是 fLaC 之后的第一个块, 固定 34 字节, 最后 8 字节是位打包的 采样率(20) / 声道-1(3) / 位深-1(5) / 总采样数(36)。
     */
    @JvmStatic
    fun readFlacInfo(f: File?): IntArray? {
        var raf: RandomAccessFile? = null
        try {
            raf = RandomAccessFile(f, "r")
            val h = ByteArray(26)
            raf.readFully(h)
            if (
                h[0] != 'f'.code.toByte() ||
                    h[1] != 'L'.code.toByte() ||
                    h[2] != 'a'.code.toByte() ||
                    h[3] != 'C'.code.toByte()
            )
                return null
            if ((h[4] and 0x7F) != 0) return null // 第一个块必须是 STREAMINFO
            var bits: Long = 0
            for (i in 18..25) bits = (bits shl 8) or (h[i] and 0xFF)
            val rate = ((bits.ushr(44)) and 0xFFFFF).toInt()
            val channels = ((bits.ushr(41)) and 0x7).toInt() + 1
            val bps = ((bits.ushr(36)) and 0x1F).toInt() + 1
            val samples = bits and 0xFFFFFFFFFL
            val seconds = if (rate > 0) (samples / rate).toInt() else 0
            return intArrayOf(rate, bps, channels, seconds)
        } catch (e: Exception) {
            return null
        } finally {
            try {
                if (raf != null) raf.close()
            } catch (ignored: Exception) {}
        }
    }

    /** 取内嵌封面 —— FLAC 读 PICTURE 块, m4a 读 ilst/covr。都没有返回 null。 */
    @JvmStatic
    @Throws(IOException::class)
    fun readPicture(f: File?): ByteArray? {
        if (!isFlac(f)) return mp4Cover(f)
        val raf = RandomAccessFile(f, "r")
        var first: ByteArray? = null
        try {
            val magic = ByteArray(4)
            raf.readFully(magic)
            if (
                magic[0] != 'f'.code.toByte() ||
                    magic[1] != 'L'.code.toByte() ||
                    magic[2] != 'a'.code.toByte() ||
                    magic[3] != 'C'.code.toByte()
            )
                return null
            while (true) {
                val flag = raf.read()
                if (flag < 0) break
                val last = (flag and 0x80) != 0
                val type = flag and 0x7F
                val b1 = raf.read()
                val b2 = raf.read()
                val b3 = raf.read()
                if (b1 < 0 || b2 < 0 || b3 < 0) break
                val len = (b1 shl 16) or (b2 shl 8) or b3
                if (type == 6) {
                    val data = ByteArray(len)
                    raf.readFully(data)
                    val img = pictureData(data)
                    if (img != null) {
                        // picture type 3 是 front cover, 优先
                        if (first == null) first = img
                        if (be32(data, 0) == 3) return img
                    }
                } else {
                    raf.seek(raf.getFilePointer() + len)
                }
                if (last) break
            }
        } finally {
            raf.close()
        }
        return first
    }

    /** 解一张缩略图。没有封面或解不出来返回 null。 */
    @JvmStatic
    fun thumbnail(audio: File?, maxPx: Int): android.graphics.Bitmap? {
        var out: android.graphics.Bitmap? = null
        try {
            val pic = readPicture(audio)
            if (pic != null && pic.size > 0) {
                val o = android.graphics.BitmapFactory.Options()
                o.inJustDecodeBounds = true
                android.graphics.BitmapFactory.decodeByteArray(pic, 0, pic.size, o)
                val size = Math.min(o.outWidth, o.outHeight)
                var sample = 1
                while (size / sample > maxPx) sample *= 2
                o.inJustDecodeBounds = false
                o.inSampleSize = sample
                out = android.graphics.BitmapFactory.decodeByteArray(pic, 0, pic.size, o)
            }
        } catch (ignored: Exception) {}

        return out
    }

    /**
     * Reads lyrics from an available FLAC or ID3 header; incomplete tags return an empty string.
     */
    @JvmStatic
    fun readLyrics(head: ByteArray?): String? {
        if (head == null || head.size < 8) return ""
        if (
            head.size >= 10 &&
                head[0] == 'I'.code.toByte() &&
                head[1] == 'D'.code.toByte() &&
                head[2] == '3'.code.toByte()
        )
            return readId3Lyrics(head)
        if (
            head!!.size < 8 ||
                head[0] != 'f'.code.toByte() ||
                head[1] != 'L'.code.toByte() ||
                head[2] != 'a'.code.toByte() ||
                head[3] != 'C'.code.toByte()
        ) {
            return ""
        }
        var pos = 4
        while (true) {
            if (pos + 4 > head.size) return ""
            val flag = head[pos] and 0xFF
            val last = (flag and 0x80) != 0
            val type = flag and 0x7F
            val len =
                (((head[pos + 1] and 0xFF) shl 16) or
                    ((head[pos + 2] and 0xFF) shl 8) or
                    (head[pos + 3] and 0xFF))
            if (pos + 4 + len > head.size) return ""
            if (type == 4) {
                val data = ByteArray(len)
                System.arraycopy(head, pos + 4, data, 0, len)
                val out = java.util.HashMap<String?, String?>()
                parseVorbis(data, out)
                val v = out.get("LYRICS")
                return if (v == null) "" else v
            }
            pos += 4 + len
            if (last) return ""
        }
    }

    private fun readId3Lyrics(head: ByteArray): String {
        val version = head[3].toInt() and 0xFF
        if (version !in 3..4 || head[5].toInt() and 0x80 != 0) return ""
        fun syncSize(position: Int): Int =
            ((head[position].toInt() and 0x7F) shl 21) or
                ((head[position + 1].toInt() and 0x7F) shl 14) or
                ((head[position + 2].toInt() and 0x7F) shl 7) or
                (head[position + 3].toInt() and 0x7F)
        val limit = minOf(head.size, existingId3Size(head))
        var position = 10
        if (head[5].toInt() and 0x40 != 0) {
            if (position + 4 > limit) return ""
            position += if (version == 4) syncSize(position) else 4 + be32(head, position)
        }
        while (position >= 10 && position + 10 <= limit) {
            val name = String(head, position, 4, Charsets.US_ASCII)
            val size = if (version == 4) syncSize(position + 4) else be32(head, position + 4)
            val payload = position + 10
            if (size <= 0 || size > limit - payload) return ""
            if (name == "USLT" && size >= 6) {
                val encoding = head[payload].toInt() and 0xFF
                val step = if (encoding == 1 || encoding == 2) 2 else 1
                var start = payload + 4
                val end = payload + size
                while (start + step <= end) {
                    if (head[start] == 0.toByte() && (step == 1 || head[start + 1] == 0.toByte())) {
                        start += step
                        val charset =
                            when (encoding) {
                                0 -> Charsets.ISO_8859_1
                                1 -> Charsets.UTF_16
                                2 -> Charsets.UTF_16BE
                                3 -> Charsets.UTF_8
                                else -> return ""
                            }
                        return String(head, start, end - start, charset).trimEnd('\u0000')
                    }
                    start += step
                }
                return ""
            }
            position = payload + size
        }
        return ""
    }

    /**
     * 从一段 FLAC 头部字节里取封面。
     *
     * 元数据不完整或超出给定字节范围时返回 null。
     */
    @JvmStatic
    fun readPicture(head: ByteArray?): ByteArray? {
        if (
            head!!.size < 8 ||
                head[0] != 'f'.code.toByte() ||
                head[1] != 'L'.code.toByte() ||
                head[2] != 'a'.code.toByte() ||
                head[3] != 'C'.code.toByte()
        ) {
            return null
        }
        var first: ByteArray? = null
        var pos = 4
        while (true) {
            if (pos + 4 > head.size) return first // 元数据超出这段了
            val flag = head[pos] and 0xFF
            val last = (flag and 0x80) != 0
            val type = flag and 0x7F
            val len =
                ((head[pos + 1] and 0xFF) shl 16) or
                    ((head[pos + 2] and 0xFF) shl 8) or
                    (head[pos + 3] and 0xFF)
            if (pos + 4 + len > head.size) return first // 同上
            if (type == 6) {
                val data = ByteArray(len)
                System.arraycopy(head, pos + 4, data, 0, len)
                val img = pictureData(data)
                if (img != null) {
                    if (first == null) first = img
                    if (be32(data, 0) == 3) return img
                }
            }
            pos += 4 + len
            if (last) return first
        }
    }

    /** 从 PICTURE 块里剥出图片字节。 */
    private fun pictureData(d: ByteArray?): ByteArray? {
        try {
            var p = 4 // picture type
            val mimeLen = be32(d, p)
            p += 4 + mimeLen
            val descLen = be32(d, p)
            p += 4 + descLen
            p += 16 // width/height/depth/colors
            val dataLen = be32(d, p)
            p += 4
            if (dataLen <= 0 || p + dataLen > d!!.size) return null
            val out = ByteArray(dataLen)
            System.arraycopy(d, p, out, 0, dataLen)
            return out
        } catch (e: Exception) {
            return null
        }
    }

    private fun be32(d: ByteArray?, p: Int): Int {
        if (p + 4 > d!!.size) return 0
        return (((d[p] and 0xFF) shl 24) or
            ((d[p + 1] and 0xFF) shl 16) or
            ((d[p + 2] and 0xFF) shl 8) or
            (d[p + 3] and 0xFF))
    }

    private fun parseVorbis(data: ByteArray?, out: MutableMap<String?, String?>?) {
        var pos = 0
        if (pos + 4 > data!!.size) return
        val vendorLen = le32(data, pos)
        pos += 4 + vendorLen
        if (pos + 4 > data.size) return
        val count = le32(data, pos)
        pos += 4
        var i = 0
        while (i < count && pos + 4 <= data.size) {
            val len = le32(data, pos)
            pos += 4
            if (len < 0 || pos + len > data.size) break
            val s = String((data), pos, len, StandardCharsets.UTF_8)
            pos += len
            val eq = s.indexOf('=')
            if (eq > 0) {
                val key = s.substring(0, eq).uppercase(java.util.Locale.US)
                val value = s.substring(eq + 1)
                // 一首歌可能有多条 ARTIST, 直接 put 会把前面的覆盖掉
                if ("ARTIST" == key && out!!.containsKey(key)) {
                    out.put(key, out.get(key) + ", " + value)
                } else {
                    out!!.put(key, value)
                }
            }
            i++
        }
    }

    private fun le32(d: ByteArray?, p: Int): Int {
        return (d!![p] and 0xFF) or
            ((d[p + 1] and 0xFF) shl 8) or
            ((d[p + 2] and 0xFF) shl 16) or
            ((d[p + 3] and 0xFF) shl 24)
    }

    /** Replace FLAC comments and artwork using a temporary file, preserving the audio frames. */
    @JvmStatic
    @Throws(IOException::class)
    fun retagFlac(src: File?, comments: MutableList<String?>?, cover: ByteArray?) {
        val `in` = RandomAccessFile(src, "r")
        val tmp = File(src!!.getParentFile(), "." + src.getName() + ".retag")
        val out = java.io.BufferedOutputStream(java.io.FileOutputStream(tmp), 1 shl 16)
        try {
            val headLen = Math.min(`in`.length(), (2 shl 20).toLong()).toInt()
            val head = ByteArray(headLen)
            `in`.readFully(head)
            val fh = parseFlacHead(head)
            if (fh!!.framesOffset > headLen) throw IOException("元数据段过大")

            out.write(buildFlacHeader(fh, comments, cover))
            out.write(head, fh.framesOffset, headLen - fh.framesOffset)

            val buf = ByteArray(1 shl 16)
            var n: Int
            while (true) {
                n = `in`.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
        } finally {
            try {
                out.close()
            } catch (ignored: IOException) {}

            `in`.close()
        }
        if (!src.delete()) throw IOException("删不掉原文件")
        if (!tmp.renameTo(src)) throw IOException("临时文件改名失败")
    }

    /** 如果流开头已有 ID3v2, 返回整个标签的字节数, 否则 0。 */
    @JvmStatic
    fun existingId3Size(head: ByteArray?): Int {
        if (
            head!!.size < 10 ||
                head[0] != 'I'.code.toByte() ||
                head[1] != 'D'.code.toByte() ||
                head[2] != '3'.code.toByte()
        )
            return 0
        val size =
            (((head[6] and 0x7F) shl 21) or
                ((head[7] and 0x7F) shl 14) or
                ((head[8] and 0x7F) shl 7) or
                (head[9] and 0x7F))
        return size + 10
    }
}
