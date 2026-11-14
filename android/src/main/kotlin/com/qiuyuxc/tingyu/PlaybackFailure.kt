package com.qiuyuxc.tingyu

import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer.DecoderInitializationException

internal data class PlaybackFailure(val message: String, val diagnostic: String)

/** Keep actionable player codes without copying signed URLs, request headers or response bodies. */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun playbackFailure(error: PlaybackException): PlaybackFailure {
    val causes = generateSequence(error.cause) { it.cause }.take(6).toList()
    val http = causes.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
    val kind =
        when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "读取音频超时"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "音频连接失败"
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "音频服务器返回错误"
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "音频格式读取失败"
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED -> "音频解码器启动失败"
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> "音频解码失败"
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED -> "音频输出启动失败"
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> "音频输出失败"
            else -> "音频播放失败"
        }
    val status = http?.let { " · HTTP ${it.responseCode}" }.orEmpty()
    val endpoint =
        http
            ?.dataSpec
            ?.uri
            ?.let { uri ->
                listOfNotNull(uri.scheme?.uppercase(), uri.host).joinToString(" ")
            }
            .orEmpty()
    val serverCode = http?.responseBody?.let(::audioServerErrorCode)
    val context = listOfNotNull(endpoint.takeIf { it.isNotBlank() }, serverCode).joinToString(" · ")
    val reason =
        if (context.isNotBlank()) "\n$context"
        else causes.lastOrNull()?.javaClass?.simpleName?.let { "：$it" }.orEmpty()
    val details = mutableListOf("${error.errorCodeName} (${error.errorCode})$status")
    if (context.isNotBlank()) details += context
    if (causes.isNotEmpty()) details += causes.joinToString(" → ") { it.javaClass.simpleName }
    for (cause in causes) {
        val codec =
            when (cause) {
                is MediaCodecDecoderException ->
                    listOfNotNull(cause.codecInfo?.name, cause.diagnosticInfo)
                is DecoderInitializationException ->
                    listOfNotNull(cause.codecInfo?.name, cause.diagnosticInfo)
                else -> emptyList()
            }
        details += codec.filter { it.matches(Regex("[A-Za-z0-9_.:-]{1,160}")) }
    }
    return PlaybackFailure(
        "$kind（${error.errorCode}$status）$reason",
        details.joinToString("; "),
    )
}
