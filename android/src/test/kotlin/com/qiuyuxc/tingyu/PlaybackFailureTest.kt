package com.qiuyuxc.tingyu

import androidx.media3.common.PlaybackException
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class PlaybackFailureTest {
    @Test
    fun decoderFailuresKeepTheirCodeAndDeepestCause() {
        val failure =
            playbackFailure(
                PlaybackException(
                    "renderer failure",
                    IllegalStateException("decoder", IOException("input")),
                    PlaybackException.ERROR_CODE_DECODING_FAILED,
                )
            )
        assertTrue(failure.message.contains("音频解码失败"))
        assertTrue(failure.message.contains("4003"))
        assertTrue(failure.message.contains("IOException"))
        assertTrue(failure.diagnostic.contains("IllegalStateException → IOException"))
    }

    @Test
    fun networkDiagnosticsExcludeSignedUrlsAndCredentialsInExceptionMessages() {
        val secret = "MUSIC_U=private; https://cdn.example.com/song.flac?token=private"
        val failure =
            playbackFailure(
                PlaybackException(
                    secret,
                    IOException(secret),
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                )
            )
        assertTrue(failure.message.contains("音频连接失败"))
        assertTrue(failure.message.contains("2001"))
        for (text in listOf(failure.message, failure.diagnostic)) {
            assertFalse(text.contains("private"))
            assertFalse(text.contains("MUSIC_U"))
            assertFalse(text.contains("https://"))
        }
    }

    @Test
    fun missingAndCyclicCausesRemainBoundedAndKeepTheActualErrorCode() {
        val unknown = playbackFailure(PlaybackException("unknown", null, 123456))
        assertTrue(unknown.message.contains("123456"))
        val first = IOException("first")
        val second = IllegalStateException("second", first)
        first.initCause(second)
        val failure = playbackFailure(PlaybackException("cycle", first, 5001))
        assertTrue(failure.message.contains("音频输出启动失败"))
        assertTrue(failure.message.contains("5001"))
        assertTrue(failure.diagnostic.length < 300)
    }
}
