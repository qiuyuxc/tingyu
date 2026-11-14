package com.qiuyuxc.tingyu

import java.util.ArrayList
import java.util.Collections
import java.util.Comparator
import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern

/** 解析 LRC。写进标签的那份是 lrc + tlyric 合并过的, 翻译行紧跟原文。 */
object Lrc {

    private val TAG = Pattern.compile("\\[(\\d+):(\\d+(?:[.:]\\d+)?)\\]")

    class Line
    internal constructor(
        @JvmField val time: Long,
        @JvmField val text: String?,
        @JvmField val translation: Boolean,
    )

    /** 解析成按时间排序的行。认不出时间戳的行丢掉。 */
    @JvmStatic
    fun parse(text: String?): MutableList<Line?>? {
        val out = ArrayList<Line?>()
        if (text == null || text.length == 0) return out

        val raw = text.split(("\\r?\\n").toRegex()).dropLastWhile({ it.isEmpty() }).toTypedArray()
        var previousTimes = emptySet<Long>()
        for (line in raw) {
            val s = line.trim({ it <= ' ' })
            if (s.length == 0) continue
            val m = TAG.matcher(s)
            val times = mutableListOf<Long>()
            var end = 0
            while (m.find()) {
                times += toMillis(m)
                end = m.end()
            }
            if (times.isEmpty()) continue
            val body = s.substring(end).trim({ it <= ' ' })
            if (body.length == 0) continue

            // Translations can use different decimal precision for the same timestamp.
            times.forEach { time -> out.add(Line(time, body, time in previousTimes)) }
            previousTimes = times.toSet()
        }
        Collections.sort<Line?>(
            out,
            object : Comparator<Line?> {
                public override fun compare(a: Line?, b: Line?): Int {
                    return java.lang.Long.compare(a!!.time, b!!.time)
                }
            },
        )
        return out
    }

    /** Normalize timestamps and separate translated rows for a bilingual lyric component. */
    fun splitTranslation(text: String?): Pair<String, String> {
        val original = StringBuilder()
        val translated = StringBuilder()
        for (line in parse(text).orEmpty().filterNotNull()) {
            val target = if (line.translation) translated else original
            target
                .append(
                    String.format(
                        Locale.ROOT,
                        "[%02d:%02d.%03d]",
                        line.time / 60000,
                        line.time / 1000 % 60,
                        line.time % 1000,
                    )
                )
                .append(line.text)
                .append('\n')
        }
        return original.toString() to translated.toString()
    }

    private fun toMillis(m: Matcher?): Long {
        val min = java.lang.Long.parseLong(m!!.group(1))
        val sec = m.group(2)!!.replace(':', '.')
        val s = java.lang.Double.parseDouble(sec)
        return min * 60000L + Math.round(s * 1000.0)
    }

    /** 二分找出当前该高亮哪一行, 都不匹配返回 -1。 */
    @JvmStatic
    fun indexAt(lines: MutableList<Line?>?, positionMs: Long): Int {
        var lo = 0
        var hi = lines!!.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi).ushr(1)
            if (lines.get(mid)!!.time <= positionMs) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }

    /**
     * Compact players show the original current line, including when a translation shares its time.
     */
    fun currentText(lines: MutableList<Line?>?, positionMs: Long): String? {
        if (lines.isNullOrEmpty()) return null
        var index = indexAt(lines, positionMs)
        if (index < 0) return null
        val time = lines[index]!!.time
        while (index > 0 && lines[index - 1]!!.time == time) index--
        return lines[index]!!.text
    }

    @JvmStatic
    fun mmss(ms: Long): String? {
        var ms = ms
        if (ms < 0) ms = 0
        val total = ms / 1000
        return String.format("%d:%02d", total / 60, total % 60)
    }
}
