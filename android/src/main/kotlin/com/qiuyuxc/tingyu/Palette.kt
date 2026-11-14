package com.qiuyuxc.tingyu

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/** MIUIX color tokens shared by Compose and the existing music/lyrics Android views. */
internal class Palette private constructor(val colors: Colors, val dark: Boolean) {
    val canvas = colors.surface.toArgb()
    val canvasSoft = colors.background.toArgb()
    val canvasSoft2 = colors.secondaryVariant.toArgb()
    val canvasRaised = colors.surfaceContainer.toArgb()
    val tile = canvasRaised
    val ink = colors.onSurface.toArgb()
    val body = colors.onSurfaceSecondary.toArgb()
    val mute = colors.onSurfaceVariantSummary.toArgb()
    val hairline = colors.dividerLine.toArgb()
    val hairlineStrong = colors.outline.toArgb()
    val accent = colors.primary.toArgb()
    val accentInk = colors.onTertiaryContainer.toArgb()
    val onAccent = colors.onPrimary.toArgb()
    val accentWash = colors.tertiaryContainer.toArgb()
    val track = colors.sliderBackground.toArgb()
    val link = accentInk
    val linkHover = accent
    val focus = accent
    val success = if (dark) 0xFF64D9A8.toInt() else 0xFF16834C.toInt()
    val error = colors.error.toArgb()
    val warning = if (dark) 0xFFFFC46B.toInt() else 0xFFA76300.toInt()
    val info = accentInk
    val sidebar = canvas
    val sidebarHover = canvasSoft2
    val sidebarActive = accentInk
    val sidebarActiveBg = accentWash
    val sidebarText = body
    val sidebarTextActive = accentInk
    val sidebarHoverText = ink
    val sidebarDivider = hairline
    val brandIcon = accent
    val headerBg = canvas
    val headerBorder = hairline
    val statusHealthyBg = if (dark) 0xFF163C2B.toInt() else 0xFFE8F6EE.toInt()
    val statusHealthyBorder = success
    val statusHealthyText = success
    val statusDownBg = colors.errorContainer.toArgb()
    val statusDownBorder = error
    val statusDownText = colors.onErrorContainer.toArgb()
    val statusDegradedBg = if (dark) 0xFF3D2C15.toInt() else 0xFFFFF3DF.toInt()
    val statusDegradedBorder = warning
    val statusDegradedText = warning
    val btnPrimaryBg = accent
    val btnPrimaryText = onAccent
    val btnPrimaryHover = colors.primaryContainer.toArgb()
    val btnSecondaryBorder = hairline
    val btnSecondaryHoverBorder = hairlineStrong
    val btnGhostHover = colors.onSurface.copy(alpha = 0.06f).toArgb()
    val bannerWarningBg = statusDegradedBg
    val bannerWarningBorder = statusDegradedBorder
    val bannerWarningText = statusDegradedText
    val bannerInfoBg = accentWash
    val bannerInfoBorder = accent
    val bannerInfoText = accentInk
    val resultErrorBg = statusDownBg
    val resultErrorBorder = statusDownBorder
    val resultErrorText = statusDownText
    val resultSuccessBg = statusHealthyBg
    val resultSuccessBorder = statusHealthyBorder
    val resultSuccessText = statusHealthyText

    companion object {
        private val light = (0..2).map { create(it, false) }
        private val dark = (0..2).map { create(it, true) }
        val MIUIX_LIGHT = light[0]
        val MIUIX_DARK = dark[0]

        fun themed(style: Int, isDark: Boolean): Palette =
            (if (isDark) dark else light)[style.coerceIn(0, 2)]

        /** Soft sage, blue and neutral surfaces inspired by tc, shared by every page. */
        private fun create(style: Int, dark: Boolean): Palette {
            val accent =
                Color(
                    if (dark)
                        when (style) {
                            1 -> 0xFF9ABDED
                            2 -> 0xFFC7C7C7
                            else -> 0xFF9ACBB0
                        }
                    else
                        when (style) {
                            1 -> 0xFF42679B
                            2 -> 0xFF525252
                            else -> 0xFF3F7058
                        }
                )
            val canvas =
                Color(
                    if (dark)
                        when (style) {
                            1 -> 0xFF171A20
                            2 -> 0xFF191919
                            else -> 0xFF191B1A
                        }
                    else
                        when (style) {
                            1 -> 0xFFF3F5F9
                            2 -> 0xFFF4F4F4
                            else -> 0xFFF4F6F3
                        }
                )
            val raised =
                Color(
                    if (dark)
                        when (style) {
                            1 -> 0xFF242830
                            2 -> 0xFF262626
                            else -> 0xFF262927
                        }
                    else
                        when (style) {
                            1 -> 0xFFFFFFFF
                            2 -> 0xFFFFFFFF
                            else -> 0xFFFFFFFF
                        }
                )
            val ink =
                Color(
                    if (dark) {
                        if (style == 2) 0xFFECECEC else 0xFFEEF0EE
                    } else
                        when (style) {
                            1 -> 0xFF282D36
                            2 -> 0xFF2D2D2D
                            else -> 0xFF29312C
                        }
                )
            val muted =
                Color(
                    if (dark) {
                        if (style == 2) 0xFFB4B4B4 else 0xFFB4BAB6
                    } else
                        when (style) {
                            1 -> 0xFF636D80
                            2 -> 0xFF686868
                            else -> 0xFF626D66
                        }
                )
            val soft =
                if (style == 2) Color(if (dark) 0xFF343434 else 0xFFEAEAEA)
                else lerp(raised, accent, if (dark) 0.10f else 0.08f)
            val onAccent = if (dark) canvas else Color.White
            val colors =
                (if (dark) darkColorScheme() else lightColorScheme()).copy(
                    primary = accent,
                    onPrimary = onAccent,
                    primaryVariant = accent,
                    onPrimaryVariant = onAccent,
                    primaryContainer = accent,
                    onPrimaryContainer = onAccent,
                    disabledPrimary = soft,
                    disabledOnPrimary = muted,
                    disabledPrimaryButton = soft,
                    disabledOnPrimaryButton = muted,
                    disabledPrimarySlider = soft,
                    secondary = soft,
                    onSecondary = ink,
                    secondaryVariant = soft,
                    onSecondaryVariant = ink,
                    secondaryContainer = soft,
                    onSecondaryContainer = ink,
                    secondaryContainerVariant = soft,
                    onSecondaryContainerVariant = muted,
                    tertiaryContainer = soft,
                    onTertiaryContainer = accent,
                    tertiaryContainerVariant = soft,
                    background = canvas,
                    onBackground = ink,
                    onBackgroundVariant = muted,
                    surface = canvas,
                    onSurface = ink,
                    surfaceVariant = soft,
                    onSurfaceSecondary = ink.copy(alpha = 0.82f),
                    onSurfaceVariantSummary = muted,
                    onSurfaceVariantActions = muted,
                    surfaceContainer = raised,
                    onSurfaceContainer = ink,
                    onSurfaceContainerVariant = muted,
                    surfaceContainerHigh = raised,
                    onSurfaceContainerHigh = ink,
                    surfaceContainerHighest = raised,
                    onSurfaceContainerHighest = ink,
                    outline = muted.copy(alpha = 0.23f),
                    dividerLine = muted.copy(alpha = 0.12f),
                    sliderKeyPoint = accent.copy(alpha = 0.25f),
                    sliderKeyPointForeground = accent,
                    sliderBackground = accent.copy(alpha = if (dark) 0.20f else 0.12f),
                )
            return Palette(colors, dark)
        }
    }
}
