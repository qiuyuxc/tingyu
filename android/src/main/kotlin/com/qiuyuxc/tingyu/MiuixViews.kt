package com.qiuyuxc.tingyu

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Reusable MIUIX actions for pages that own their existing Android layout and work. */
internal class MiuixActionView(
    context: Context,
    label: String,
    private val icon: Int = 0,
    private val accent: Boolean = true,
    private val action: () -> Unit,
) : MiuixView(context) {
    private var caption by mutableStateOf(label)
    private var available by mutableStateOf(true)

    fun setLabel(value: String) {
        caption = value
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        available = enabled
    }

    @Composable
    override fun Render() {
        MiuixButton(caption, Modifier.fillMaxWidth(), icon, accent, available, onClick = action)
    }
}

internal class MiuixIconView(
    context: Context,
    private val icon: Int,
    private val label: String,
    private val action: () -> Unit,
) : MiuixView(context) {
    @Composable
    override fun Render() {
        MiuixIconButton(icon, label, diameter = 44.dp, onClick = action)
    }
}

internal class MiuixPreferenceView(
    context: Context,
    private val title: String,
    private val summary: String? = null,
    private val icon: Int = 0,
    private val arrow: Boolean = true,
    private val expanded: Boolean = false,
    private val action: () -> Unit,
) : MiuixView(context) {
    @Composable
    override fun Render() {
        BasicComponent(
            title = title,
            summary = summary?.takeIf { it.isNotBlank() },
            modifier = Modifier.fillMaxWidth(),
            startAction =
                if (icon != 0) {
                    { UiIcon(icon, MiuixTheme.colorScheme.primary) }
                } else null,
            endActions = {
                if (arrow)
                    UiIcon(
                        R.drawable.ic_nav_chevron,
                        MiuixTheme.colorScheme.onSurfaceVariantActions,
                        Modifier.rotate(if (expanded) 90f else 0f),
                    )
            },
            onClick = action,
            onClickLabel = title,
        )
    }
}

internal class MiuixSwitchPreferenceView(
    context: Context,
    private val title: String,
    private val summary: String?,
    checked: Boolean,
    private val onChange: (Boolean) -> Unit,
) : MiuixView(context) {
    private var checked by mutableStateOf(checked)

    @Composable
    override fun Render() {
        SwitchPreference(
            title = title,
            summary = summary,
            checked = checked,
            modifier = Modifier.fillMaxWidth(),
            onCheckedChange = {
                checked = it
                onChange(it)
            },
        )
    }
}

internal class MiuixChoicePreferenceView(
    context: Context,
    private val title: String,
    private val summary: String?,
    private val options: List<String>,
    selected: Int,
    private val onChange: (Int) -> Unit,
) : MiuixView(context) {
    private var selected by mutableIntStateOf(selected)

    @Composable
    override fun Render() {
        BasicComponent(
            title = title,
            summary = summary,
            modifier = Modifier.fillMaxWidth(),
            bottomAction = {
                MiuixChoices(
                    options = options,
                    selected = selected,
                    onSelect = {
                        selected = it
                        onChange(it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    minWidth = 56.dp,
                )
            },
        )
    }
}

internal class LibraryFeatureView(
    context: Context,
    private val song: Song,
    private val continuing: Boolean,
    private val action: () -> Unit,
) : MiuixView(context) {
    @Composable
    override fun Render() {
        val colors = MiuixTheme.colorScheme
        val artwork by rememberSongArtwork(song, 384)
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(
                    Brush.linearGradient(listOf(colors.tertiaryContainer, colors.surfaceContainer))
                )
                .border(1.dp, colors.primary.copy(alpha = 0.08f), RoundedCornerShape(28.dp))
                .padding(20.dp)
        ) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val artSize = if (maxWidth < 280.dp) 88.dp else 116.dp
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(artSize)
                            .clip(RoundedCornerShape(22.dp))
                            .background(colors.tertiaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        val bitmap = artwork
                        if (bitmap != null) {
                            Image(
                                bitmap.asImageBitmap(),
                                null,
                                Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            UiIcon(R.drawable.ic_tab_music, colors.primary)
                        }
                    }
                    Column(Modifier.weight(1f).padding(start = 16.dp)) {
                        UiText(
                            if (continuing) "继续聆听" else "为你留住的旋律",
                            size = 11,
                            color = colors.primary,
                        )
                        Spacer(Modifier.height(6.dp))
                        UiText(
                            song.title.ifBlank { "未命名音乐" },
                            size = 20,
                            weight = FontWeight.SemiBold,
                            maxLines = 2,
                        )
                        Spacer(Modifier.height(4.dp))
                        UiText(
                            song.artist.ifBlank { "未知艺人" },
                            size = 13,
                            color = colors.onSurfaceVariantSummary,
                            maxLines = 1,
                        )
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            MiuixButton(
                if (continuing) "继续播放" else "播放音乐",
                Modifier.fillMaxWidth(),
                icon = R.drawable.ic_media_play,
                onClick = action,
            )
        }
    }
}
