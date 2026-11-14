package com.qiuyuxc.tingyu

import kotlin.math.pow

/** Provider loudness metadata leaves headroom; audio-focus ducking multiplies that same gain. */
internal class PlaybackGain {
    private var trackVolume = 1f
    private var ducked = false

    val volume: Float
        get() = trackVolume * if (ducked) 0.3f else 1f

    fun setTrackGain(gainDb: Double?) {
        val decibels = gainDb?.takeIf { it.isFinite() }?.coerceIn(-60.0, 0.0) ?: 0.0
        trackVolume = 10.0.pow(decibels / 20.0).toFloat()
    }

    fun setDucked(value: Boolean) {
        ducked = value
    }

    fun reset() {
        trackVolume = 1f
        ducked = false
    }
}
