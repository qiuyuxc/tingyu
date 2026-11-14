package com.qiuyuxc.tingyu

import org.junit.Assert.*
import org.junit.Test

class SettingsNavigationTest {
    @Test
    fun settingsOpenedFromMusicReturnToMusic() {
        val navigation =
            SettingsNavigation().apply {
                open("preferences")
                open("appearance")
            }
        navigation.open("directories", fromTab = 0)
        assertEquals(listOf("home"), navigation.history)
        assertTrue(navigation.back())
        assertEquals(0, navigation.takeReturnTab())
        assertNull(navigation.takeReturnTab())
    }

    @Test
    fun recreationAndNestedPagesPreserveTheExternalReturnTab() {
        val before =
            SettingsNavigation().apply {
                open("directories", fromTab = 0)
                open("cache")
            }
        val after =
            SettingsNavigation().apply { restore(before.page, before.history, before.returnTab) }
        assertTrue(after.back())
        assertNull(after.takeReturnTab())
        assertTrue(after.back())
        assertEquals(0, after.takeReturnTab())
        after.open("directories", fromTab = 0)
        after.reset()
        assertNull(after.returnTab)
    }

    @Test
    fun repeatedPreferenceRebuildsDoNotChangeTheCurrentPageOrBackHistory() {
        val navigation = SettingsNavigation()
        navigation.open("preferences")
        navigation.open("appearance")
        repeat(4) { navigation.open("appearance") }
        assertEquals("appearance", navigation.page)
        assertEquals(listOf("home", "preferences"), navigation.history)
        assertTrue(navigation.back())
        assertEquals("preferences", navigation.page)
        assertTrue(navigation.back())
        assertEquals("home", navigation.page)
        assertFalse(navigation.back())
    }

    @Test
    fun recreationPreservesTheSettingsSectionAndItsReturnPath() {
        val before =
            SettingsNavigation().apply {
                open("preferences")
                open("storage")
                open("cache")
            }
        val after = SettingsNavigation().apply { restore(before.page, before.history) }
        assertEquals("cache", after.page)
        assertTrue(after.back())
        assertEquals("storage", after.page)
        assertTrue(after.back())
        assertEquals("preferences", after.page)
    }

    @Test
    fun restorationRejectsInvalidPagesWithoutAddingBrokenBackDestinations() {
        val navigation =
            SettingsNavigation().apply {
                restore("missing", listOf("home", "invalid", "appearance"))
            }
        assertEquals("home", navigation.page)
        assertEquals(listOf("home", "appearance"), navigation.history)
        assertThrows(IllegalArgumentException::class.java) { navigation.open("invalid") }
    }

    @Test
    fun explicitHomeActionClearsThePreviousSections() {
        val navigation =
            SettingsNavigation().apply {
                open("preferences")
                open("playback")
                reset()
            }
        assertEquals("home", navigation.page)
        assertFalse(navigation.back())
    }
}
