package com.qiuyuxc.tingyu

import org.junit.Assert.*
import org.junit.Test

class HeartLayoutTest {
    @Test
    fun aPhoneViewportFitsWithoutAddingAnOuterScrollRange() {
        val layout = heartLayout(352f, 650f, 1f, false)
        assertFalse(layout.wide)
        assertEquals(650f, layout.bodyHeight, 0f)
        assertTrue(visualHeight(layout) >= 160f)
    }

    @Test
    fun phoneMetadataSharesTheActionsRowWithoutMovingPlaybackControls() {
        val cover = heartLayout(352f, 650f, 1f, false)
        val lyrics = heartLayout(352f, 650f, 1f, true)
        assertTrue(cover.inlineActions)
        assertEquals(56f, cover.actionsHeight, 0f)
        assertEquals(lyrics.progressHeight, cover.progressHeight, 0f)
        assertEquals(lyrics.transportHeight, cover.transportHeight, 0f)
        assertEquals(650f, cover.bodyHeight, 0f)
    }

    @Test
    fun narrowColumnsAndLargeFontsStackContextAboveTheActionButtons() {
        val phone = heartLayout(280f, 330f, 1f, false)
        val largePhone = heartLayout(352f, 330f, 2f, false)
        val largeLandscape = heartLayout(600f, 180f, 2f, false)
        for (layout in listOf(phone, largePhone, largeLandscape)) {
            assertFalse(layout.inlineActions)
            assertTrue(layout.actionsHeight > 56f)
            assertTrue(layout.progressHeight >= 56f)
            assertTrue(layout.transportHeight >= 48f)
            assertTrue(
                layout.bodyHeight >=
                    layout.infoHeight + layout.actionsHeight + layout.controlsViewportHeight
            )
        }
        assertTrue(visualHeight(phone) >= 159.999f)
        assertTrue(visualHeight(largePhone) >= 159.999f)
        assertTrue(largeLandscape.wide)
    }

    @Test
    fun shortLandscapeWindowsScrollInsteadOfClippingPlaybackControls() {
        val layout = heartLayout(720f, 150f, 1f, false)
        assertTrue(layout.wide)
        assertTrue(layout.bodyHeight > 150f)
        assertTrue(
            layout.bodyHeight >=
                layout.infoHeight +
                    layout.actionsHeight +
                    layout.progressHeight +
                    layout.transportHeight
        )
        assertTrue(layout.progressHeight >= 56f)
        assertTrue(layout.transportHeight >= 48f)
    }

    @Test
    fun largeFontsKeepTimeLabelsAndArtworkSpaceAvailable() {
        val layout = heartLayout(280f, 330f, 3f, false)
        assertFalse(layout.wide)
        assertTrue(layout.bodyHeight > 330f)
        assertTrue(layout.progressHeight >= 36f + 11f * 1.4f * 3f)
        assertTrue(visualHeight(layout) >= 159.999f)
    }

    @Test
    fun lyricsRecoverTheMetadataSpaceWhileTransportSizesStayTheSame() {
        val cover = heartLayout(352f, 650f, 1.5f, false)
        val lyrics = heartLayout(352f, 650f, 1.5f, true)
        assertEquals(0f, lyrics.infoHeight, 0f)
        assertEquals(0f, lyrics.actionsHeight, 0f)
        assertEquals(cover.progressHeight, lyrics.progressHeight, 0f)
        assertEquals(cover.transportHeight, lyrics.transportHeight, 0f)
        assertTrue(visualHeight(lyrics) > visualHeight(cover))
    }

    @Test
    fun lyricsKeepTheirOwnViewportInShortWindowsAndAtLargeFontSizes() {
        for ((width, height) in listOf(720f to 150f, 280f to 230f, 352f to 650f)) {
            for (scale in listOf(1f, 1.5f, 3f)) {
                val layout = heartLayout(width, height, scale, true)
                assertEquals(height, layout.bodyHeight, 0f)
                assertEquals(0f, layout.infoHeight, 0f)
                assertEquals(0f, layout.actionsHeight, 0f)
                assertTrue(
                    "Lyrics must retain space outside the controls' scroll area",
                    visualHeight(layout) >= height * 0.349f,
                )
                assertTrue(layout.controlsViewportHeight > 0f)
                assertTrue(
                    layout.controlsViewportHeight <= layout.progressHeight + layout.transportHeight
                )
            }
        }
    }

    private fun visualHeight(layout: HeartLayout): Float =
        layout.bodyHeight - layout.infoHeight - layout.actionsHeight - layout.controlsViewportHeight
}
