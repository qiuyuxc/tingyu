package com.qiuyuxc.tingyu

import android.net.Uri
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ForwardingExtractor
import androidx.media3.extractor.ForwardingExtractorOutput
import androidx.media3.extractor.ForwardingExtractorsFactory
import androidx.media3.extractor.ForwardingTrackOutput
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.flac.FlacExtractor
import androidx.media3.extractor.text.SubtitleParser

/** STREAMINFO may legally omit the compressed frame size; MediaCodec still needs a byte limit. */
internal fun flacInputSize(header: ByteArray?, declaredSize: Int): Int {
    if (declaredSize > 0) return declaredSize
    if (
        header == null ||
            header.size < 42 ||
            header[0].toInt() != 0x66 ||
            header[1].toInt() != 0x4c ||
            header[2].toInt() != 0x61 ||
            header[3].toInt() != 0x43
    )
        return 128 * 1024
    val block = ((header[10].toInt() and 0xff) shl 8) or (header[11].toInt() and 0xff)
    if (block < 16) return 128 * 1024
    val channels = ((header[20].toInt() ushr 1) and 7) + 1
    val bits = (((header[20].toInt() and 1) shl 4) or ((header[21].toInt() and 0xff) ushr 4)) + 1
    // Stereo side channels can need one extra bit; include subframe headers and codec CSD padding.
    return maxOf(32 * 1024, block * channels * ((bits + 8) / 8) + 1024)
}

/**
 * Keep Media3's extractors and seek maps, supplying a size only for FLAC with an unknown maximum.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
internal class FlacExtractorsFactory : ForwardingExtractorsFactory(DefaultExtractorsFactory()) {
    override fun experimentalSetTextTrackTranscodingEnabled(enabled: Boolean): ExtractorsFactory {
        super.experimentalSetTextTrackTranscodingEnabled(enabled)
        return this
    }

    override fun setSubtitleParserFactory(factory: SubtitleParser.Factory): ExtractorsFactory {
        super.setSubtitleParserFactory(factory)
        return this
    }

    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(
        codecs: Int
    ): ExtractorsFactory {
        super.experimentalSetCodecsToParseWithinGopSampleDependencies(codecs)
        return this
    }

    override fun setParseHagcMetadata(parse: Boolean): ExtractorsFactory {
        super.setParseHagcMetadata(parse)
        return this
    }

    override fun createExtractors(): Array<Extractor> =
        super.createExtractors().map(::wrap).toTypedArray()

    override fun createExtractors(uri: Uri, headers: Map<String, List<String>>): Array<Extractor> =
        super.createExtractors(uri, headers).map(::wrap).toTypedArray()

    private fun wrap(extractor: Extractor): Extractor =
        if (extractor !is FlacExtractor) extractor
        else
            object : ForwardingExtractor(extractor) {
                override fun init(output: ExtractorOutput) {
                    super.init(
                        object : ForwardingExtractorOutput(output) {
                            override fun track(id: Int, type: Int): TrackOutput =
                                object : ForwardingTrackOutput(super.track(id, type)) {
                                    override fun format(format: Format) {
                                        val size =
                                            flacInputSize(
                                                format.initializationData.firstOrNull(),
                                                format.maxInputSize,
                                            )
                                        super.format(
                                            if (size == format.maxInputSize) format
                                            else format.buildUpon().setMaxInputSize(size).build()
                                        )
                                    }
                                }
                        }
                    )
                }
            }
}
