package com.qiuyuxc.tingyu

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.*
import org.junit.Test

class PaletteTest {
    @Test
    fun membershipBadgesStayColoredAndReadableAcrossEveryPalette() {
        for (dark in listOf(false, true)) {
            val vip = membershipColor(MembershipTier.VIP, dark)
            val svip = membershipColor(MembershipTier.SVIP, dark)
            assertNotEquals(vip, svip)
            for (style in 0..2) {
                val palette = Palette.themed(style, dark)
                for (ink in listOf(vip, svip)) {
                    val argb = ink.toArgb()
                    val channels = listOf(argb ushr 16 and 255, argb ushr 8 and 255, argb and 255)
                    assertTrue(channels.max() - channels.min() > 80)
                    for (surface in listOf(palette.canvas, palette.canvasRaised)) {
                        for (alpha in listOf(if (dark) 0.16f else 0.10f, 0.12f)) {
                            val background = ink.copy(alpha = alpha).compositeOver(Color(surface))
                            val foregroundLuminance = ink.luminance()
                            val backgroundLuminance = background.luminance()
                            val contrast =
                                (maxOf(foregroundLuminance, backgroundLuminance) + 0.05f) /
                                    (minOf(foregroundLuminance, backgroundLuminance) + 0.05f)
                            assertTrue(
                                "Small membership text needs contrast >= 4.5: $contrast",
                                contrast >= 4.5f,
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun monochromeTextSurfacesAndControlsUseNeutralChannelsInBothModes() {
        listOf(false, true).forEach { dark ->
            val p = Palette.themed(2, dark)
            listOf(
                    p.canvas,
                    p.canvasRaised,
                    p.ink,
                    p.body,
                    p.mute,
                    p.accent,
                    p.accentInk,
                    p.onAccent,
                    p.accentWash,
                    p.track,
                    p.hairline,
                )
                .forEach { value ->
                    val red = value ushr 16 and 255
                    assertEquals(red, value ushr 8 and 255)
                    assertEquals(red, value and 255)
                }
        }
    }

    @Test
    fun darkGreenUsesNeutralSurfacesAndReadableTextRatherThanAGreenWash() {
        val p = Palette.themed(0, true)
        assertTrue((p.ink and 255) > 230)
        val surface = p.canvasRaised
        val channels = listOf(surface ushr 16 and 255, surface ushr 8 and 255, surface and 255)
        assertTrue(channels.max() - channels.min() <= 4)
        assertNotEquals(Palette.themed(2, true).accent, p.accent)
    }
}
