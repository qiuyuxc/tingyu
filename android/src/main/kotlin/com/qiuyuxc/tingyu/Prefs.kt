package com.qiuyuxc.tingyu

import android.content.Context

/** Local playlist preferences. */
object Prefs {
    @JvmStatic fun exportM3u(context: Context?): Boolean =
        requireNotNull(context).getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
            .getBoolean("m3u", true)

    @JvmStatic fun set(context: Context?, key: String?, value: Boolean) {
        require(key == "m3u")
        requireNotNull(context).getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(key, value).apply()
    }
}
