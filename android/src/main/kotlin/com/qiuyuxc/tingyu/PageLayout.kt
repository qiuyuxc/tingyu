package com.qiuyuxc.tingyu

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal object PageInsets {
    var bottom by mutableStateOf(0.dp)
}

/** Headers occupy space in the page; only the content below them scrolls. */
@Composable
internal fun PageHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    large: Boolean = false,
    back: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = MiuixTheme.colorScheme
    val inset = UI.pageInset(LocalContext.current).dp
    Row(
        modifier
            .fillMaxWidth()
            .background(colors.surface)
            .heightIn(min = 64.dp)
            .padding(start = if (back == null) inset else inset - 8.dp, end = inset - 8.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (back != null) {
            MiuixIconButton(R.drawable.ic_chevron_left, "返回", onClick = back)
            Spacer(Modifier.width(4.dp))
        }
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            UiText(title, size = if (large) 28 else 20, weight = FontWeight.SemiBold, maxLines = 1)
            if (!subtitle.isNullOrBlank()) {
                UiText(subtitle, size = 12, color = colors.onSurfaceVariantSummary, maxLines = 1)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** Text tabs keep navigation visually separate from cards and actions. */
@Composable
internal fun PageTabs(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    val scroll = rememberScrollState()
    val widths = remember(labels) { mutableStateMapOf<Int, Int>() }
    val gap = with(LocalDensity.current) { 24.dp.roundToPx() }
    val start = (0 until selected).sumOf { widths[it] ?: 0 } + selected * gap
    val width = widths[selected]
    var viewport by remember { mutableIntStateOf(0) }
    // Restore a selected source into this horizontal viewport without moving the page vertically.
    LaunchedEffect(selected, start, width, viewport) {
        if (viewport <= 0 || width == null || (0 until selected).any { it !in widths })
            return@LaunchedEffect
        val target =
            when {
                start < scroll.value -> start
                start + width > scroll.value + viewport -> start + width - viewport
                else -> scroll.value
            }
        scroll.scrollTo(target.coerceIn(0, scroll.maxValue))
    }
    Row(
        modifier.onSizeChanged { viewport = it.width }.horizontalScroll(scroll).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val active = selected == index
            Column(
                Modifier.widthIn(min = 48.dp)
                    .onSizeChanged { widths[index] = it.width }
                    .selectable(active, role = Role.Tab, onClick = { onSelect(index) })
                    .padding(top = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                UiText(
                    label,
                    size = 16,
                    color = if (active) colors.onSurface else colors.onSurfaceVariantSummary,
                    weight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier.width(22.dp)
                        .height(3.dp)
                        .clip(CircleShape)
                        .background(if (active) colors.primary else Color.Transparent)
                )
                Spacer(Modifier.height(7.dp))
            }
        }
    }
}

@Composable
internal fun TextAction(label: String, modifier: Modifier = Modifier, action: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClickLabel = label, role = Role.Button, onClick = action)
            .heightIn(min = 48.dp)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        UiText(label, size = 13, color = MiuixTheme.colorScheme.primary, maxLines = 1)
    }
}

@Composable
internal fun PageEmptyState(
    icon: Int,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = MiuixTheme.colorScheme
    Column(
        modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(72.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(colors.tertiaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            UiIcon(icon, colors.primary, Modifier.size(30.dp))
        }
        Spacer(Modifier.height(24.dp))
        UiText(title, size = 22, weight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        UiText(
            message,
            Modifier.widthIn(max = 320.dp),
            size = 13,
            color = colors.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
        )
        if (action != null && onClick != null) {
            Spacer(Modifier.height(24.dp))
            MiuixButton(action, onClick = onClick)
        }
    }
}

@Composable
internal fun InsetDivider(start: Int = 20) {
    Box(
        Modifier.fillMaxWidth()
            .padding(start = start.dp, end = 20.dp)
            .height(1.dp)
            .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.06f))
    )
}
