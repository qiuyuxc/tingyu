package com.qiuyuxc.tingyu

import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class PlaybackDockTest {
    @Test
    fun navigationKeepsItsRightEdgeThroughoutTheTransition() {
        for (width in listOf(240f, 312f, 512f)) {
            for (height in listOf(80f, 108f)) {
                for (step in 0..20) {
                    val placement = dockPlacement(width, height, 90f, 68f, step / 20f)
                    assertEquals(width, placement.x + placement.navigationWidth, 0.001f)
                    assertEquals(90f + height / 2f, placement.coverCenterY, 0.001f)
                }
            }
        }
    }

    @Test
    fun collapsedArtworkHasRoomBesideTheBarAndSharesItsVerticalCenter() {
        for (width in listOf(240f, 312f, 512f)) {
            val placement = dockPlacement(width, 80f, 74f, 68f, 1f)
            assertTrue("56 dp artwork needs 12 dp of separation", placement.x >= 67.999f)
            assertEquals(74f + 40f, placement.coverCenterY, 0.001f)
            assertTrue(placement.coverCenterY + 28f <= 74f + 80f)
        }
    }

    @Test
    fun expandedBarHasItsOriginalSizeAndPosition() {
        val placement = dockPlacement(312f, 80f, 74f, 68f, 0f)
        assertEquals(312f, placement.navigationWidth, 0f)
        assertEquals(0f, placement.x, 0f)
    }

    @Test
    fun widthChangesContinuouslyWhenCollapsingAndRestoring() {
        for (width in listOf(240f, 312f, 512f)) {
            val progress = (0..100).toList() + (99 downTo 0).toList()
            var previous = dockNavigationWidth(width, 68f, 0f)
            for (step in progress) {
                val current = dockNavigationWidth(width, 68f, step / 100f)
                assertTrue("No width jump at either end", abs(current - previous) < 0.681f)
                previous = current
            }
            assertEquals(width, previous, 0f)
            assertEquals(width - 68f, dockNavigationWidth(width, 68f, 1f), 0.001f)
        }
    }

    @Test
    fun smallWidthsAndOversizedGapsStayWithinTheAvailableSpace() {
        for (width in listOf(0f, 1f, 48f, 120f, 240f)) {
            for (gap in listOf(-20f, 0f, 68f, 300f)) {
                for (progress in listOf(0f, 0.5f, 1f)) {
                    val placement = dockPlacement(width, 80f, 74f, gap, progress)
                    assertTrue(placement.navigationWidth >= width / 2f)
                    assertTrue(placement.navigationWidth <= width)
                    assertTrue(placement.x >= 0f)
                    assertEquals(width, placement.x + placement.navigationWidth, 0.001f)
                }
            }
        }
    }

    @Test
    fun unmeasuredWidthAndInterruptedAnimationsCannotProduceInvalidBounds() {
        for (progress in listOf(-0.1f, 0f, 0.5f, 1f, 1.1f)) {
            val placement = dockPlacement(0f, 80f, 74f, 68f, progress)
            assertTrue(placement.navigationWidth.isFinite())
            assertTrue(placement.x.isFinite())
            assertTrue(placement.coverCenterY.isFinite())
        }
        assertEquals(
            dockPlacement(312f, 80f, 74f, 68f, 0f),
            dockPlacement(312f, 80f, 74f, 68f, -1f),
        )
        assertEquals(dockPlacement(312f, 80f, 74f, 68f, 1f), dockPlacement(312f, 80f, 74f, 68f, 2f))
    }
}
