package com.qiuyuxc.tingyu

import org.junit.Assert.*
import org.junit.Test

class SheetDragTest {
    @Test
    fun shortPullsReturnAndDeliberatePullsDismissAtEveryPanelHeight() {
        for (height in listOf(80f, 300f, 600f, 800f)) {
            assertFalse(
                shouldDismissSheet(dampedSheetOffset(height * 0.1f, height), height, 0f, 1000f)
            )
            assertTrue(
                shouldDismissSheet(dampedSheetOffset(height * 0.5f, height), height, 0f, 1000f)
            )
        }
    }

    @Test
    fun aDownwardFlingCanDismissWhileAnUpwardFlingOrAnUnmeasuredPanelCannot() {
        assertTrue(shouldDismissSheet(10f, 600f, 1200f, 1000f))
        assertFalse(shouldDismissSheet(10f, 600f, -1200f, 1000f))
        assertFalse(shouldDismissSheet(200f, 600f, -1200f, 1000f))
        assertFalse(shouldDismissSheet(0f, 600f, 1200f, 1000f))
        assertFalse(shouldDismissSheet(0f, 0f, 1200f, 1000f))
    }
}
