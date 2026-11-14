package com.qiuyuxc.tingyu

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.TabRowDefaults
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** One theme for the activity scene and every Compose control hosted in Android layouts. */
@Composable
internal fun AppMiuixTheme(dark: Boolean = Theme.isDark, content: @Composable () -> Unit) {
    MiuixTheme(colors = Theme.colors(dark), content = content)
}

internal abstract class MiuixView(context: Context, private val dark: Boolean? = null) :
    AbstractComposeView(context) {
    @Composable
    final override fun Content() {
        AppMiuixTheme(dark ?: Theme.isDark) { Render() }
    }

    @Composable protected abstract fun Render()
}

@Composable
internal fun UiText(
    text: String,
    modifier: Modifier = Modifier,
    size: Int = 14,
    color: Color = MiuixTheme.colorScheme.onSurface,
    weight: FontWeight = FontWeight.Normal,
    maxLines: Int = Int.MAX_VALUE,
    textAlign: TextAlign? = null,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = size.sp,
        fontWeight = weight,
        lineHeight = (size * 1.4).sp,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        textAlign = textAlign,
    )
}

@Composable
internal fun UiIcon(resource: Int, color: Color, modifier: Modifier = Modifier) {
    Icon(
        painter = painterResource(resource),
        contentDescription = null,
        modifier = modifier.size(22.dp),
        tint = color,
    )
}

@Composable
internal fun MiuixIconButton(
    resource: Int,
    label: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    diameter: Dp = 48.dp,
    enabled: Boolean = true,
    iconColor: Color? = null,
    onClick: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(diameter).semantics { contentDescription = label },
        minWidth = diameter,
        minHeight = diameter,
        cornerRadius = diameter / 2,
        backgroundColor = if (accent) colors.primary else Color.Transparent,
    ) {
        UiIcon(resource, iconColor ?: if (accent) colors.onPrimary else colors.onSurface)
    }
}

internal class AppNavigationItem(title: String, icon: Int) {
    val title = title
    val icon = icon
}

@Composable
internal fun MiuixToggle(
    checked: Boolean,
    label: String,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = label },
    )
}

/** Transparent gutters and no per-tab outlines around the rounded selection. */
@Composable
internal fun MiuixChoices(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    minWidth: Dp = 56.dp,
) {
    val colors = MiuixTheme.colorScheme
    TabRowWithContour(
        tabs = options,
        selectedTabIndex = selected,
        onTabSelected = onSelect,
        modifier = modifier,
        colors =
            TabRowDefaults.tabRowColors(
                backgroundColor = Color.Transparent,
                selectedBackgroundColor = colors.secondaryVariant,
                selectedContentColor = colors.onSecondaryVariant,
            ),
        height = 48.dp * LocalDensity.current.fontScale.coerceAtLeast(1f) + 10.dp,
        cornerRadius = 16.dp,
        itemSpacing = 8.dp,
        minWidth = minWidth,
    )
}

@Composable
internal fun MiuixButton(
    label: String,
    modifier: Modifier = Modifier,
    icon: Int = 0,
    accent: Boolean = true,
    enabled: Boolean = true,
    iconColor: Color? = null,
    onClick: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val ink =
        if (accent) {
            if (enabled) colors.onPrimary else colors.disabledOnPrimaryButton
        } else {
            if (enabled) colors.onSecondaryVariant else colors.disabledOnSecondaryVariant
        }
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        minHeight = 48.dp,
        colors =
            if (accent) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
    ) {
        if (icon != 0) {
            UiIcon(icon, if (enabled) iconColor ?: ink else ink)
            Spacer(Modifier.width(8.dp))
        }
        UiText(label, size = 15, color = ink, weight = FontWeight.Medium)
    }
}
