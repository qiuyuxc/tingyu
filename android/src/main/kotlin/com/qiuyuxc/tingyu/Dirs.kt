package com.qiuyuxc.tingyu

import android.content.Context

/** Local library and playlist export locations. */
object Dirs {
    const val DEFAULT_SRC = "/storage/emulated/0/Music"
    const val DEFAULT_OUT = "/storage/emulated/0/Music/听屿"

    private fun preferences(context: Context?) =
        requireNotNull(context).getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)

    @JvmStatic fun src(context: Context?): String =
        preferences(context).getString("src", DEFAULT_SRC).orEmpty().ifBlank { DEFAULT_SRC }
    @JvmStatic fun out(context: Context?): String =
        preferences(context).getString("out", DEFAULT_OUT).orEmpty().ifBlank { DEFAULT_OUT }
    @JvmStatic fun setSrc(context: Context?, value: String?) {
        preferences(context).edit().putString("src", value.orEmpty().trim()).apply()
    }
    @JvmStatic fun setOut(context: Context?, value: String?) {
        preferences(context).edit().putString("out", value.orEmpty().trim()).apply()
    }
}
