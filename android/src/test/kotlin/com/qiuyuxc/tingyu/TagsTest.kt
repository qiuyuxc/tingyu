package com.qiuyuxc.tingyu

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TagsTest {
    @Rule @JvmField val temporary = TemporaryFolder()
    private val lyrics = "[00:01.00]第一行\n[00:02.50]第二行"

    @Test
    fun flacTagsLyricsAndCoverRoundTrip() {
        val head =
            Tags.FlacHead().apply {
                streamInfo = byteArrayOf(0, 0, 0, 34) + ByteArray(34)
                keep = mutableListOf()
            }
        val cover = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) + ByteArray(20) { it.toByte() }
        val comments =
            mutableListOf<String?>(
                "TITLE=测试歌名",
                "ARTIST=某艺人",
                "ALBUM=某专辑",
                "LYRICS=$lyrics",
                "TRACKNUMBER=1",
            )
        val header = requireNotNull(Tags.buildFlacHeader(head, comments, cover))
        val file = temporary.newFile("tags.flac").apply { writeBytes(header + ByteArray(100)) }
        val tags = requireNotNull(Tags.readVorbis(file))
        assertEquals("测试歌名", tags["TITLE"])
        assertEquals("某艺人", tags["ARTIST"])
        assertEquals(lyrics, tags["LYRICS"])
        assertEquals(lyrics, Tags.readLyrics(header))
        assertArrayEquals(cover, Tags.readPicture(file))
        assertEquals(header.size, requireNotNull(Tags.parseFlacHead(header)).framesOffset)
    }

    @Test
    fun mp3LyricsAndHeaderLengthRemainReadable() {
        val header = requireNotNull(Tags.buildId3("测试歌名", "艺人", "专辑", lyrics, null))
        assertEquals(header.size, Tags.existingId3Size(header))
        assertEquals(lyrics, Tags.readLyrics(header))
        val file = temporary.newFile("lyrics.mp3").apply { writeBytes(header + ByteArray(128)) }
        assertEquals(lyrics, LocalMetadata.lyricsOf(file))
    }

    @Test
    fun mp4LyricsRemainAvailableWhenVorbisParsingDoesNotApply() {
        fun atom(type: String, body: ByteArray): ByteArray =
            java.nio.ByteBuffer.allocate(8 + body.size)
                .putInt(8 + body.size)
                .put(type.toByteArray(Charsets.ISO_8859_1))
                .put(body)
                .array()
        val data =
            atom(
                "data",
                java.nio.ByteBuffer.allocate(8).putInt(1).putInt(0).array() + lyrics.toByteArray(),
            )
        val metadata = atom("meta", ByteArray(4) + atom("ilst", atom("©lyr", data)))
        val file =
            temporary.newFile("lyrics.m4a").apply {
                writeBytes(
                    atom("ftyp", "M4A ".toByteArray() + ByteArray(4)) +
                        atom("moov", atom("udta", metadata))
                )
            }
        assertEquals(lyrics, Tags.readMp4Text(file, "©lyr"))
        assertEquals(lyrics, LocalMetadata.lyricsOf(file))
    }

    @Test
    fun corruptFlacHeaderIsRejected() {
        assertThrows(java.io.IOException::class.java) { Tags.parseFlacHead("fLaC".toByteArray()) }
    }
}
