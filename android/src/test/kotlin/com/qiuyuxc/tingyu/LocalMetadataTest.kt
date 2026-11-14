package com.qiuyuxc.tingyu

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalMetadataTest {
    @Rule @JvmField val temporary = TemporaryFolder()
    @Test fun sidecarLyricsTakePriorityAndRemoveTheUtf8Bom() {
        val audio = temporary.newFile("song.mp3").apply {
            writeBytes(requireNotNull(Tags.buildId3("Song", "Artist", "", "[00:01.00]Embedded", null)))
        }
        temporary.newFile("song.lrc").writeText("\uFEFF[00:02.00]Sidecar")
        assertEquals("[00:02.00]Sidecar", LocalMetadata.lyricsOf(audio))
    }
    @Test fun anEmptySidecarKeepsEmbeddedLyricsAvailable() {
        val audio = temporary.newFile("song.mp3").apply {
            writeBytes(requireNotNull(Tags.buildId3("Song", "Artist", "", "[00:01.00]Embedded", null)))
        }
        temporary.newFile("song.lrc").writeText("  ")
        assertEquals("[00:01.00]Embedded", LocalMetadata.lyricsOf(audio))
    }
    @Test fun localMetadataReadingPreservesTheAudioBytes() {
        val original = requireNotNull(Tags.buildId3("Song", "Artist", "", "[00:01.00]Embedded", null)) + ByteArray(256)
        val audio = temporary.newFile("song.mp3").apply { writeBytes(original) }
        assertEquals("[00:01.00]Embedded", LocalMetadata.lyricsOf(audio))
        assertArrayEquals(original, audio.readBytes())
    }
}
