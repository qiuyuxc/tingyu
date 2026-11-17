package com.qiuyuxc.tingyu

import org.junit.Assert.*
import org.junit.Test

class ImportedMusicKeysTest {
    @Test
    fun importedPasswordsRetainEmptyTextWhitespaceUnicodeAndTheirType() {
        val values = listOf("", "  口令🔒\n ", "cseal2:" + "ab".repeat(32), "ab".repeat(32))
        val keys = ImportedMusicKeys(values.map { ImportedMusicKeys.Entry(false, it) })
        val restored = ImportedMusicKeys.parse(keys.json())
        assertEquals(values, restored.forKeyId(0))
        assertTrue(restored.forMasterKeyId(0).isEmpty())
    }

    @Test
    fun masterKeyImportStrictlyDecodesBothSupportedLengths() {
        for (size in listOf(16, 32)) {
            val bytes = ByteArray(size) { (it * 3).toByte() }
            val keys =
                ImportedMusicKeys().add(ImportedMusicKeys.Entry(true, ChunkSealKeys.encode(bytes)))
            val restored = ImportedMusicKeys.parse(keys.json())
            assertTrue(restored.forKeyId(0).isEmpty())
            assertArrayEquals(bytes, restored.forMasterKeyId(65535).single())
        }
        for (value in
            listOf(
                "ab".repeat(32),
                "cseal2:abcd",
                "cseal2:" + "zz".repeat(16),
                "cseal2:" + "ab".repeat(16) + " ",
            )) {
            assertThrows(IllegalArgumentException::class.java) {
                ImportedMusicKeys.Entry(true, value)
            }
        }
    }

    @Test
    fun importingAnotherCredentialKeepsTheOldOneAndDeduplicatesRepeatedImports() {
        val password = ImportedMusicKeys.Entry(false, "old password")
        val master = ImportedMusicKeys.Entry(true, "cseal2:" + "01".repeat(32))
        val keys = ImportedMusicKeys().add(password).add(master).add(password)
        assertEquals(listOf(password, master), ImportedMusicKeys.parse(keys.json()).entries)
    }

    @Test
    fun cloudHistoryRetainsItsSourceAndDoesNotBecomeALocalFile() {
        val song =
            Song.cloud(
                Storage.Item("/音乐/测试.cseal", "测试.cseal", 123),
                "openlist:https://example.test|/音乐",
            )
        val restored = HeardSong.from(song).song()
        assertTrue(restored.isCloud)
        assertTrue(restored.isEncrypted)
        assertEquals(song.key, restored.key)
        assertEquals(song.cloudFile?.path, restored.cloudFile?.path)
        assertNull(restored.file)
    }

    @Test
    fun encryptedMusicParticipatesInTheAudioLibrary() {
        assertTrue(LocalMetadata.isAudioName("音乐.CSEAL"))
        assertTrue(LocalMetadata.isAudioName("old.NCMX"))
        assertTrue(LocalMetadata.isAudioName("music.flac"))
        assertFalse(LocalMetadata.isAudioName("notes.txt"))
    }
}
