package com.qiuyuxc.tingyu

import android.content.Context
import android.content.res.Configuration
import top.yukonga.miuix.kmp.theme.Colors

/** Persisted appearance preferences shared by the Kotlin pages and Compose controls. */
internal object Theme {
    const val MODE_SYSTEM = 0
    const val MODE_LIGHT = 1
    const val MODE_DARK = 2
    private var current = Palette.MIUIX_LIGHT
    private var mode = MODE_SYSTEM
    private var palette = 0
    private var artworkBackground = true
    val isDark: Boolean
        get() = current.dark

    @JvmStatic
    fun init(context: Context) {
        val prefs = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
        mode = prefs.getInt("themeMode", MODE_SYSTEM).coerceIn(MODE_SYSTEM, MODE_DARK)
        palette = prefs.getInt("themePalette", 0).coerceIn(0, 2)
        artworkBackground = prefs.getBoolean("playerArtworkBackground", true)
        current = resolve(context)
    }

    @JvmStatic fun p(): Palette = current

    @JvmStatic fun mode(): Int = mode

    fun colors(dark: Boolean = isDark): Colors =
        Palette.themed(palette, dark).colors

    fun palette(): Int = palette

    fun resourceStyle(): Int = if (isDark) when (palette) {
        1 -> R.style.AppTheme_Dark_Blue
        2 -> R.style.AppTheme_Dark_Mono
        else -> R.style.AppTheme_Dark
    } else when (palette) {
        1 -> R.style.AppTheme_Blue
        2 -> R.style.AppTheme_Mono
        else -> R.style.AppTheme
    }

    fun setPalette(context: Context, value: Int) {
        palette = value.coerceIn(0, 2)
        current = resolve(context)
        context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
            .edit().putInt("themePalette", palette).apply()
    }

    fun artworkBackground(): Boolean = artworkBackground

    @JvmStatic
    fun setMode(context: Context, value: Int) {
        mode = value.coerceIn(MODE_SYSTEM, MODE_DARK)
        current = resolve(context)
        context
            .getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt("themeMode", mode)
            .apply()
    }

    @JvmStatic
    fun setArtworkBackground(context: Context, value: Boolean) {
        artworkBackground = value
        context
            .getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("playerArtworkBackground", value)
            .apply()
    }

    private fun resolve(context: Context): Palette {
        val systemDark =
            context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        return Palette.themed(palette, mode == MODE_DARK || mode == MODE_SYSTEM && systemDark)
    }
}
