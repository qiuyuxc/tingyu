package com.qiuyuxc.tingyu

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

internal class EncryptedFixture(file: String, val name: String) {
    val properties =
        Properties().apply {
            requireNotNull(
                    EncryptedFixture::class.java.classLoader.getResourceAsStream("fixtures/$file")
                )
                .reader(StandardCharsets.UTF_8)
                .use { load(it) }
        }

    fun hex(field: String): ByteArray = decode(properties.getProperty("$name.$field"))

    val container = hex("container_hex")
    val plaintext = hex("plaintext_hex")
    val keys =
        ImportedMusicKeys(
            listOf(
                if (properties.getProperty("$name.kdf_id") == "2")
                    ImportedMusicKeys.Entry(true, ChunkSealKeys.encode(hex("master_key_hex")))
                else
                    ImportedMusicKeys.Entry(
                        false,
                        String(hex("password_utf8_hex"), StandardCharsets.UTF_8),
                    )
            )
        )

    fun source(bytes: ByteArray = container): Ncmx.Source =
        object : Ncmx.Source {
            override fun length(): Long = bytes.size.toLong()

            override fun read(offset: Long, length: Int): ByteArray =
                bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
        }

    companion object {
        fun decode(value: String): ByteArray =
            ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}

@RunWith(Parameterized::class)
class EncryptedKnownAnswerTest(private val file: String, private val vector: String) {
    @Test
    fun independentContainerDeliversOriginalAudioAndMetadata() {
        val fixture = EncryptedFixture(file, vector)
        EncryptedReader.open(fixture.source(), fixture.keys).use { reader ->
            val actual = ByteArray(fixture.plaintext.size)
            assertEquals(actual.size, reader.readAt(0, actual, 0, actual.size))
            assertArrayEquals(fixture.plaintext, actual)
            val metadata = fixture.properties.getProperty("$vector.metadata_hex")
            assertArrayEquals(
                if (metadata == "-") null else EncryptedFixture.decode(metadata),
                Ncmx.readMetaBytes(fixture.source(), reader.info.header, reader.info.key),
            )
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}/{1}")
        fun vectors(): List<Array<String>> =
            listOf(
                arrayOf("chunkseal-v1.properties", "empty"),
                arrayOf("chunkseal-v1.properties", "boundary"),
                arrayOf("chunkseal-v1.properties", "unicode"),
                arrayOf("chunkseal-v2.properties", "raw_empty"),
                arrayOf("chunkseal-v2.properties", "raw_boundary"),
                arrayOf("chunkseal-v2.properties", "raw_empty_metadata"),
                arrayOf("chunkseal-v2.properties", "password_unicode"),
                arrayOf("ncmx-read.properties", "legacy"),
            )
    }
}

class EncryptedReaderTest {
    private val boundary
        get() = EncryptedFixture("chunkseal-v2.properties", "raw_boundary")

    @Test
    fun seekingAcrossChunksAndEofPreservesBytesAndReusesVerifiedChunks() {
        val fixture = boundary
        val reads = AtomicInteger()
        val source =
            object : Ncmx.Source {
                override fun length() = fixture.container.size.toLong()

                override fun read(offset: Long, length: Int): ByteArray {
                    reads.incrementAndGet()
                    return fixture.container.copyOfRange(offset.toInt(), offset.toInt() + length)
                }
            }
        EncryptedReader.open(source, fixture.keys).use { reader ->
            val cross = ByteArray(32) { 0x55 }
            assertEquals(20, reader.readAt(4090, cross, 4, 20))
            assertArrayEquals(fixture.plaintext.copyOfRange(4090, 4110), cross.copyOfRange(4, 24))
            val fetched = reads.get()
            assertEquals(20, reader.readAt(4090, cross, 4, 20))
            assertEquals(fetched, reads.get())
            val tail = ByteArray(16)
            assertEquals(7, reader.readAt(8192, tail, 0, tail.size))
            assertArrayEquals(fixture.plaintext.copyOfRange(8192, 8199), tail.copyOfRange(0, 7))
            assertEquals(-1, reader.readAt(reader.size, tail, 0, 1))
            assertEquals(0, reader.readAt(reader.size, tail, 0, 0))
        }
    }

    @Test
    fun aDamagedSecondChunkNeverChangesTheDestinationBuffer() {
        val fixture = boundary
        val header = requireNotNull(Ncmx.readHeader(fixture.source()))
        val damaged =
            fixture.container.copyOf().apply {
                val position = header.chunkOffset(1).toInt()
                this[position] = (this[position].toInt() xor 1).toByte()
            }
        EncryptedReader.open(fixture.source(damaged), fixture.keys).use { reader ->
            val output = ByteArray(20) { 0x55 }
            assertThrows(IOException::class.java) { reader.readAt(4090, output, 0, output.size) }
            assertArrayEquals(ByteArray(20) { 0x55 }, output)
        }
    }

    @Test
    fun missingMasterKeysDoNotReinterpretPasswordsOrAttemptAnotherFormat() {
        val fixture = boundary
        val wrongType =
            ImportedMusicKeys(
                listOf(ImportedMusicKeys.Entry(false, fixture.keys.entries.single().value))
            )
        assertThrows(Ncmx.NcmxException::class.java) {
            EncryptedReader.open(fixture.source(), wrongType)
        }
        val corrupted =
            fixture.container.copyOf().apply { this[48] = (this[48].toInt() xor 1).toByte() }
        assertThrows(Ncmx.NcmxException::class.java) {
            EncryptedReader.open(fixture.source(corrupted), fixture.keys)
        }
    }

    @Test
    fun truncatedContainersAndUnsupportedVersionsAreRejectedBeforePlayback() {
        val fixture = boundary
        assertThrows(Ncmx.NcmxException::class.java) {
            EncryptedReader.open(
                fixture.source(fixture.container.copyOf(fixture.container.size - 1)),
                fixture.keys,
            )
        }
        val future = fixture.container.copyOf().apply { this[4] = 3 }
        assertThrows(Ncmx.NcmxException::class.java) {
            EncryptedReader.open(fixture.source(future), fixture.keys)
        }
    }

    @Test
    fun failedOpenAndRepeatedCloseReleaseTheSourceExactlyOnce() {
        val fixture = boundary
        val releases = AtomicInteger()
        val reader =
            EncryptedReader.open(fixture.source(), fixture.keys) { releases.incrementAndGet() }
        reader.close()
        reader.close()
        assertEquals(1, releases.get())
        assertThrows(IOException::class.java) { reader.readAt(0, ByteArray(1), 0, 1) }
        assertThrows(Ncmx.NcmxException::class.java) {
            EncryptedReader.open(fixture.source(), ImportedMusicKeys()) {
                releases.incrementAndGet()
            }
        }
        assertEquals(2, releases.get())
    }

    @Test
    fun closingAnActiveRemoteReadDoesNotWaitForThatRead() {
        val fixture = boundary
        val started = CountDownLatch(1)
        val released = CountDownLatch(1)
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val source =
            object : Ncmx.Source {
                override fun length() = fixture.container.size.toLong()

                override fun read(offset: Long, length: Int): ByteArray {
                    if (offset >= 64 + 4096) {
                        started.countDown()
                        assertTrue(released.await(3, TimeUnit.SECONDS))
                        throw IOException("closed")
                    }
                    return fixture.container.copyOfRange(offset.toInt(), offset.toInt() + length)
                }
            }
        val reader = EncryptedReader.open(source, fixture.keys) { released.countDown() }
        Thread {
            try {
                assertThrows(IOException::class.java) { reader.readAt(4096, ByteArray(8), 0, 8) }
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                done.countDown()
            }
        }
            .start()
        try {
            assertTrue(started.await(3, TimeUnit.SECONDS))
            val before = System.nanoTime()
            reader.close()
            assertTrue(
                "Closing blocked on the active read",
                System.nanoTime() - before < TimeUnit.SECONDS.toNanos(1),
            )
            assertTrue(done.await(3, TimeUnit.SECONDS))
            assertNull(failure.get())
        } finally {
            reader.close()
            released.countDown()
        }
    }
}
