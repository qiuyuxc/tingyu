package com.qiuyuxc.tingyu

import org.junit.Assert.*
import org.junit.Test

class LrcTest {
    @Test
    fun translationsStayAttachedToTheirTimestamps() {
        val lines =
            requireNotNull(Lrc.parse("[00:02.50]Second\n[00:01.00]First\n[00:01.00]翻译\ninvalid"))
        assertEquals(listOf(1000L, 1000L, 2500L), lines.map { it!!.time })
        assertEquals(listOf(false, true, false), lines.map { it!!.translation })
        assertEquals(-1, Lrc.indexAt(lines, 999))
        assertEquals(1, Lrc.indexAt(lines, 1000))
        assertEquals(2, Lrc.indexAt(lines, 3000))
    }

    @Test
    fun emptyLyricsAndNegativeTimeAreHandled() {
        assertTrue(Lrc.parse(null).orEmpty().isEmpty())
        assertEquals("0:00", Lrc.mmss(-1))
        assertEquals("2:05", Lrc.mmss(125000))
    }

    @Test
    fun bilingualWidgetInputNormalizesTimestampPrecisionWithoutLosingTranslations() {
        val (original, translated) =
            Lrc.splitTranslation("[00:01]First\n[00:01.000]翻译\n[00:02.345]Second\n[00:02.345]第二句")
        assertEquals(listOf(1000L, 2345L), Lrc.parse(original).orEmpty().map { it!!.time })
        assertEquals(listOf(1000L, 2345L), Lrc.parse(translated).orEmpty().map { it!!.time })
        assertEquals(listOf("First", "Second"), Lrc.parse(original).orEmpty().map { it!!.text })
        assertEquals(listOf("翻译", "第二句"), Lrc.parse(translated).orEmpty().map { it!!.text })
        assertTrue(original.contains("[00:01.000]"))
    }

    @Test
    fun repeatedLinesKeepAllTimestampTagsInBothLanguages() {
        val (original, translated) =
            Lrc.splitTranslation("[00:01.20][00:03.20]Repeat\n[00:01.200][00:03.200]译文")
        assertEquals(listOf(1200L, 3200L), Lrc.parse(original).orEmpty().map { it!!.time })
        assertEquals(listOf(1200L, 3200L), Lrc.parse(translated).orEmpty().map { it!!.time })
        assertEquals(listOf("Repeat", "Repeat"), Lrc.parse(original).orEmpty().map { it!!.text })
        assertEquals("" to "", Lrc.splitTranslation(null))
    }

    @Test
    fun compactPreviewKeepsTheOriginalLineAndFollowsBackwardSeeking() {
        val lines = Lrc.parse("[00:01.00]First\n[00:01.000]译文\n[00:03.250]Second\n[00:03.250]第二句")
        assertNull(Lrc.currentText(lines, 999))
        assertEquals("First", Lrc.currentText(lines, 1000))
        assertEquals("First", Lrc.currentText(lines, 3249))
        assertEquals("Second", Lrc.currentText(lines, 3250))
        assertEquals("First", Lrc.currentText(lines, 1500))
        assertNull(Lrc.currentText(lines, 0))
    }

    @Test
    fun compactPreviewSupportsRepeatedTimestampsAndMissingLyrics() {
        val lines = Lrc.parse("[00:01][00:05]Repeat\n[00:01][00:05]翻译\n[00:03]Middle")
        assertEquals("Repeat", Lrc.currentText(lines, 1000))
        assertEquals("Middle", Lrc.currentText(lines, 3000))
        assertEquals("Repeat", Lrc.currentText(lines, 5000))
        assertNull(Lrc.currentText(Lrc.parse("no timestamp"), 5000))
        assertNull(Lrc.currentText(null, 5000))
    }
}
