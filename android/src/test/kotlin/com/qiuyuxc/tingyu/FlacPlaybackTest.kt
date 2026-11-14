package com.qiuyuxc.tingyu

import org.junit.Assert.*
import org.junit.Test

class FlacPlaybackTest {
    @Test
    fun unknownMaximumFitsLargeMasterFramesIncludingStereoSideChannelSamples() {
        val header = streamInfo(16384, 2, 24)
        val size = flacInputSize(header, -1)
        assertTrue(size > 73076)
        assertTrue(size >= 16384 * (24 + 25) / 8 + 64)
        assertTrue(size < 256 * 1024)
        assertEquals(size, flacInputSize(header, 0))
    }

    @Test
    fun aDeclaredMaximumAndTheCodecInitializationBytesArePreserved() {
        val header = streamInfo(16384, 2, 24)
        val original = header.copyOf()
        assertEquals(73076, flacInputSize(header, 73076))
        flacInputSize(header, -1)
        assertArrayEquals(original, header)
    }

    @Test
    fun theLargestStreamInfoFieldsProduceABoundedPositiveCapacity() {
        val size = flacInputSize(streamInfo(65535, 8, 32), -1)
        assertTrue(size >= 65535 * 8 * 4)
        assertTrue(size < 3 * 1024 * 1024)
    }

    @Test
    fun missingShortOrInvalidHeadersDoNotCrashCapacityCalculation() {
        for (header in listOf(null, byteArrayOf(), ByteArray(41), ByteArray(42))) {
            assertTrue(flacInputSize(header, -1) in (32 * 1024)..(256 * 1024))
            assertEquals(100000, flacInputSize(header, 100000))
        }
    }

    private fun streamInfo(block: Int, channels: Int, bits: Int): ByteArray =
        ByteArray(42).apply {
            "fLaC".toByteArray(Charsets.US_ASCII).copyInto(this)
            this[10] = (block ushr 8).toByte()
            this[11] = block.toByte()
            this[20] = (((channels - 1) shl 1) or ((bits - 1) ushr 4)).toByte()
            this[21] = ((bits - 1) shl 4).toByte()
        }
}
