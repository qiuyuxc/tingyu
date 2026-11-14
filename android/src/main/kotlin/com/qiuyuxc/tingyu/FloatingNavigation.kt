package com.qiuyuxc.tingyu

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Shader-free floating navigation for Android 7–12; no blur classes are loaded here. */
@Composable
internal fun FloatingNavigation(
    items: List<AppNavigationItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    val height = maxOf(64f, 42f + 12f * 1.4f * LocalDensity.current.fontScale).dp
    val selection by
        animateFloatAsState(
            targetValue = selected.toFloat(),
            animationSpec = spring(dampingRatio = 0.75f, stiffness = 500f),
            label = "navigation selection",
        )
    Box(
        modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .height(height)
            .shadow(10.dp, CircleShape, clip = false)
            .clip(CircleShape)
            .background(colors.surfaceContainer, CircleShape)
            .padding(4.dp)
    ) {
        Box(
            Modifier.fillMaxHeight()
                .layout { measurable, constraints ->
                    val tabWidth = constraints.maxWidth.toFloat() / items.size
                    val measuredWidth = tabWidth.roundToInt()
                    val placeable =
                        measurable.measure(
                            constraints.copy(minWidth = measuredWidth, maxWidth = measuredWidth)
                        )
                    layout(placeable.width, placeable.height) {
                        placeable.placeRelative((tabWidth * selection).roundToInt(), 0)
                    }
                }
                .background(colors.primary.copy(alpha = 0.15f), CircleShape)
        )
        Row(Modifier.fillMaxSize().selectableGroup()) {
            items.forEachIndexed { index, item ->
                val ink = if (index == selected) colors.primary else colors.onSurface
                Column(
                    Modifier.weight(1f)
                        .fillMaxHeight()
                        .selectable(
                            index == selected,
                            role = Role.Tab,
                            onClick = { onSelect(index) },
                        ),
                    verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    UiIcon(item.icon, ink)
                    UiText(
                        item.title,
                        size = 11,
                        color = ink,
                        weight = FontWeight.Normal,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
