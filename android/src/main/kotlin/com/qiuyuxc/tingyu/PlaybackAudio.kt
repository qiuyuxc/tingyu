package com.qiuyuxc.tingyu

import android.content.Context
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player as Media3Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import java.io.File

/** Files, encrypted sources and platform streams share high-resolution float PCM playback. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class PlaybackAudio {
    interface Listener {
        fun onPrepared()

        fun onCompletion()

        fun onSeekComplete()

        fun onError(what: Int, extra: Int)
    }

    var listener: Listener? = null
    var failure: PlaybackFailure? = null
        private set

    private val native = MediaPlayer()
    private var streaming: ExoPlayer? = null
    private var streamingListener: Media3Player.Listener? = null
    private var modern = false
    private var active = false
    private var generation = 0L
    private var volume = 1f

    val isPlaying: Boolean
        get() = if (modern) streaming?.isPlaying == true else native.isPlaying

    val currentPosition: Int
        get() = if (modern) streaming?.currentPosition.asPosition() else native.currentPosition

    val duration: Int
        get() = if (modern) streaming?.duration.asPosition() else native.duration

    init {
        native.setOnPreparedListener { if (active && !modern) listener?.onPrepared() }
        native.setOnCompletionListener { if (active && !modern) listener?.onCompletion() }
        native.setOnSeekCompleteListener { if (active && !modern) listener?.onSeekComplete() }
        native.setOnErrorListener { _, what, extra ->
            if (active && !modern) {
                failure =
                    PlaybackFailure(
                        "系统播放器出错（$what / $extra）",
                        "MediaPlayer what=$what extra=$extra",
                    )
                listener?.onError(what, extra)
            }
            true
        }
    }

    fun setDataSource(context: Context, source: MediaDataSource) {
        failure = null
        if (Build.VERSION.SDK_INT < 27) {
            modern = false
            native.setDataSource(source)
            active = true
            return
        }
        val uri = Uri.parse("tingyu://audio/${generation + 1}")
        setProgressiveSource(context, uri, DataSource.Factory { BorrowedMediaDataSource(source) })
    }

    fun setDataSource(context: Context, path: String) {
        failure = null
        if (Build.VERSION.SDK_INT < 27) {
            modern = false
            native.setDataSource(path)
            active = true
            return
        }
        setProgressiveSource(
            context,
            Uri.fromFile(File(path)),
            DefaultDataSource.Factory(context.applicationContext),
        )
    }

    fun setDataSource(context: Context, uri: Uri, headers: Map<String, String>) {
        failure = null
        if (Build.VERSION.SDK_INT < 27) {
            modern = false
            native.setDataSource(context, uri, headers)
            active = true
            return
        }
        val http =
            DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(headers)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(15000)
        setProgressiveSource(context, uri, http)
    }

    private fun setProgressiveSource(context: Context, uri: Uri, source: DataSource.Factory) {
        failure = null
        modern = true
        active = true
        val request = ++generation
        val player =
            streaming ?: createStreamingPlayer(context.applicationContext).also { streaming = it }
        val callbacks =
            object : Media3Player.Listener {
                private var prepared = false

                private fun current() = active && modern && request == generation

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (!current()) return
                    when (playbackState) {
                        Media3Player.STATE_READY ->
                            if (!prepared) {
                                prepared = true
                                listener?.onPrepared()
                            }
                        Media3Player.STATE_ENDED -> listener?.onCompletion()
                    }
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Media3Player.PositionInfo,
                    newPosition: Media3Player.PositionInfo,
                    reason: Int,
                ) {
                    if (current() && reason == Media3Player.DISCONTINUITY_REASON_SEEK)
                        listener?.onSeekComplete()
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (current()) {
                        failure = playbackFailure(error)
                        listener?.onError(error.errorCode, 0)
                    }
                }
            }
        streamingListener?.let(player::removeListener)
        streamingListener = callbacks
        player.addListener(callbacks)
        player.volume = volume
        val item = MediaItem.Builder().setUri(uri).setMediaId(request.toString()).build()
        player.setMediaSource(
            ProgressiveMediaSource.Factory(source, FlacExtractorsFactory()).createMediaSource(item)
        )
    }

    private fun createStreamingPlayer(context: Context): ExoPlayer {
        val renderers =
            DefaultRenderersFactory(context)
                .setMediaCodecSelector(MediaCodecSelector.PREFER_SOFTWARE)
                .setEnableDecoderFallback(true)
                .setEnableAudioFloatOutput(true)
        return ExoPlayer.Builder(context, renderers)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                false, // PlaybackService owns audio focus and its ducking multiplier.
            )
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
    }

    fun prepareAsync() {
        if (modern) requireNotNull(streaming).prepare() else native.prepareAsync()
    }

    fun start() {
        if (modern) requireNotNull(streaming).play() else native.start()
    }

    fun pause() {
        if (modern) requireNotNull(streaming).pause() else native.pause()
    }

    fun seekTo(position: Int) {
        if (modern) requireNotNull(streaming).seekTo(position.toLong()) else native.seekTo(position)
    }

    fun setVolume(value: Float) {
        volume = value
        if (modern) streaming?.volume = value else native.setVolume(value, value)
    }

    fun reset() {
        generation++
        failure = null
        active = false
        modern = false
        streaming?.let { player ->
            streamingListener?.let(player::removeListener)
            streamingListener = null
            player.playWhenReady = false
            player.stop()
            player.clearMediaItems()
        }
        native.reset()
    }

    private fun Long?.asPosition() = (this ?: 0L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
}
