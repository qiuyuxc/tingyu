package com.qiuyuxc.tingyu

import java.io.File

/** Preserve available loudness metadata; PlaybackGain applies its existing attenuation policy. */
internal object AudioLoudness {
    fun fromFile(file: File?): Double? {
        if (file == null) return null
        val tags = runCatching { Tags.readVorbis(file) }.getOrNull() ?: return null
        return parse(tags["NCMGAIN"]) ?: parse(tags["REPLAYGAIN_TRACK_GAIN"])
    }

    private fun parse(value: String?): Double? =
        value?.trim()?.replace(Regex("(?i)\\s*dB$"), "")?.trim()?.toDoubleOrNull()?.takeIf {
            it.isFinite()
        }
}
