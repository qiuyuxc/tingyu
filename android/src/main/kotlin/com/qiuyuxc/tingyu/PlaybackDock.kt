package com.qiuyuxc.tingyu

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal data class DockPlacement(
    val navigationWidth: Float,
    val x: Float,
    val coverCenterY: Float,
)

internal fun dockNavigationWidth(width: Float, gap: Float, progress: Float): Float {
    val available = width.coerceAtLeast(0f)
    return available - gap.coerceIn(0f, available / 2f) * progress.coerceIn(0f, 1f)
}

/** The right edge stays fixed while tab spacing changes; text and icons keep their natural size. */
internal fun dockPlacement(
    width: Float,
    navHeight: Float,
    miniHeight: Float,
    gap: Float,
    progress: Float,
): DockPlacement {
    val navigationWidth = dockNavigationWidth(width, gap, progress)
    return DockPlacement(
        navigationWidth,
        width.coerceAtLeast(0f) - navigationWidth,
        miniHeight + navHeight / 2f,
    )
}

@Stable
private class DockMotion(initial: Float) {
    val progress = Animatable(initial)
    var dragOffset by mutableFloatStateOf(0f)
    var returnJob: Job? = null
    var activeDrags = 0
}

/** Translate pointer deltas to screen distances; a moving cover cannot distort its own velocity. */
private fun Modifier.dockDrag(
    enabled: Boolean,
    songKey: String?,
    collapsed: Boolean,
    motion: DockMotion,
    miniHeight: Float,
    expandDistance: Float,
    flingVelocity: Float,
    coordinateScale: () -> Float,
    collapse: (Boolean) -> Unit,
    returnCard: () -> Unit,
): Modifier =
    if (!enabled) this
    else
        pointerInput(songKey, collapsed, miniHeight) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val velocity = VelocityTracker()
                velocity.addPosition(down.uptimeMillis, Offset.Zero)
                var distance = 0f
                var travel = 0f
                var active = false
                try {
                    val drag =
                        awaitVerticalTouchSlopOrCancellation(down.id) { change, overSlop ->
                            active = true
                            motion.activeDrags++
                            motion.returnJob?.cancel()
                            distance =
                                motion.dragOffset /
                                    (1f - motion.dragOffset / miniHeight).coerceAtLeast(0.01f)
                            travel = overSlop * coordinateScale()
                            if (!collapsed) {
                                distance = (distance + travel).coerceAtLeast(0f)
                                motion.dragOffset = dampedSheetOffset(distance, miniHeight)
                            }
                            velocity.addPosition(change.uptimeMillis, Offset(0f, travel))
                            change.consume()
                        } ?: return@awaitEachGesture
                    val released =
                        verticalDrag(drag.id) { change ->
                            val delta = change.positionChange().y * coordinateScale()
                            travel += delta
                            if (!collapsed) {
                                distance = (distance + delta).coerceAtLeast(0f)
                                motion.dragOffset = dampedSheetOffset(distance, miniHeight)
                            }
                            velocity.addPosition(change.uptimeMillis, Offset(0f, travel))
                            change.consume()
                        }
                    motion.activeDrags--
                    active = false
                    if (collapsed) {
                        if (released && travel < -expandDistance) collapse(false)
                    } else if (
                        released &&
                            shouldDismissSheet(
                                motion.dragOffset,
                                miniHeight,
                                velocity.calculateVelocity().y,
                                flingVelocity,
                            )
                    ) {
                        collapse(true)
                    } else returnCard()
                } finally {
                    // Song changes, disappearing pages and interrupted gestures cannot leave a
                    // shifted card.
                    if (active) {
                        motion.activeDrags--
                        if (!collapsed) returnCard()
                    }
                }
            }
        }

/** Artwork travels on its own layer; navigation width is animated only in its measure phase. */
@Composable
internal fun PlaybackDock(
    song: Song?,
    playing: Boolean,
    revision: Int,
    showMini: Boolean,
    visible: Boolean = true,
    collapsed: Boolean,
    collapse: (Boolean) -> Unit,
    open: () -> Unit,
    onInset: (Int) -> Unit,
    modifier: Modifier = Modifier,
    navigation: @Composable (Modifier) -> Unit,
) {
    val density = LocalDensity.current
    val miniDp = maxOf(74f, 36.4f * density.fontScale + 32f)
    val miniPx = with(density) { miniDp.dp.toPx() }
    val gapPx = with(density) { 68.dp.toPx() }
    val spritePx = with(density) { 56.dp.toPx() }
    val coverStartX = with(density) { 29.dp.toPx() }
    val coverStartY = (miniPx - with(density) { 6.dp.toPx() }) / 2f
    val expandDistance = with(density) { 24.dp.toPx() }
    val flingVelocity = with(density) { 1000.dp.toPx() }
    var navHeight by remember { mutableIntStateOf(with(density) { 80.dp.roundToPx() }) }
    var width by remember { mutableIntStateOf(0) }
    val motion = remember { DockMotion(if (showMini && collapsed) 1f else 0f) }
    var reserveMini by remember { mutableStateOf(showMini && !collapsed) }
    val scope = rememberCoroutineScope()
    val onCollapse by rememberUpdatedState(collapse)
    val safeBottom = WindowInsets.safeDrawing.getBottom(density)
    val effectiveInset = safeBottom + navHeight + if (showMini && reserveMini) miniPx.toInt() else 0
    SideEffect { onInset(effectiveInset) }
    LaunchedEffect(showMini, collapsed) {
        if (!showMini) {
            motion.returnJob?.cancel()
            motion.dragOffset = 0f
            motion.progress.snapTo(0f)
            reserveMini = false
            return@LaunchedEffect
        }
        if (showMini && !collapsed) reserveMini = true
        motion.returnJob?.cancel()
        val target = if (collapsed) 1f else 0f
        if (motion.progress.value != target) motion.progress.animateTo(target, tween(300))
        if (motion.activeDrags == 0 && motion.returnJob?.isActive != true) motion.dragOffset = 0f
        reserveMini = showMini && !collapsed
    }
    val rotation = remember(song?.key) { Animatable(0f) }
    LaunchedEffect(song?.key, playing, showMini, collapsed, visible) {
        if (showMini && collapsed && playing && visible) {
            while (isActive) {
                // Disabled system animations must not turn this repeating animation into a loop
                // of zero-duration jobs.
                if ((coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f) <= 0f) break
                val remaining = (15000 * (1f - rotation.value / 360f)).toInt().coerceAtLeast(1)
                rotation.animateTo(360f, tween(remaining, easing = LinearEasing))
                rotation.snapTo(0f)
            }
        }
    }
    fun returnCard() {
        motion.returnJob?.cancel()
        motion.returnJob = scope.launch {
            Animatable(motion.dragOffset).animateTo(0f, spring(0.9f, 650f)) {
                motion.dragOffset = value
            }
        }
    }
    fun dragModifier(cover: Boolean) =
        Modifier.dockDrag(
            enabled = cover || !collapsed,
            songKey = song?.key,
            collapsed = collapsed,
            motion = motion,
            miniHeight = miniPx,
            expandDistance = expandDistance,
            flingVelocity = flingVelocity,
            coordinateScale = { if (cover) 0.75f + 0.25f * motion.progress.value else 1f },
            collapse = { onCollapse(it) },
            returnCard = ::returnCard,
        )
    Box(
        modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) {
                    if (visible) placeable.placeRelative(0, 0)
                }
            }
            .then(if (visible) Modifier else Modifier.clearAndSetSemantics {})
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal
                )
            )
            .padding(horizontal = 24.dp)
            .height(miniDp.dp + with(density) { navHeight.toDp() })
            .onSizeChanged { width = it.width }
    ) {
        if (showMini && reserveMini && song != null) {
            Box(
                Modifier.fillMaxWidth()
                    .height(miniDp.dp)
                    .then(dragModifier(false))
                    .then(if (collapsed) Modifier.clearAndSetSemantics {} else Modifier)
            ) {
                DockCard(
                    song,
                    playing,
                    revision,
                    Modifier.padding(vertical = 4.dp).fillMaxSize().graphicsLayer {
                        val p = motion.progress.value
                        translationY = motion.dragOffset * (1f - p) + miniPx * 0.35f * p
                        alpha = (1f - p * 1.8f).coerceIn(0f, 1f)
                    },
                    enabled = !collapsed && visible,
                    open = open,
                    collapse = { collapse(true) },
                )
            }
        }
        Box(
            Modifier.align(Alignment.BottomEnd)
                .fillMaxWidth()
                .layout { measurable, constraints ->
                    val barWidth =
                        dockNavigationWidth(
                                constraints.maxWidth.toFloat(),
                                gapPx,
                                motion.progress.value,
                            )
                            .roundToInt()
                    val placeable =
                        measurable.measure(
                            constraints.copy(minWidth = barWidth, maxWidth = barWidth)
                        )
                    layout(constraints.maxWidth, placeable.height) {
                        placeable.placeRelative(constraints.maxWidth - placeable.width, 0)
                    }
                }
                .onSizeChanged { navHeight = it.height }
        ) {
            navigation(Modifier.fillMaxWidth())
        }
        if (showMini && song != null) {
            val artwork by rememberSongArtwork(song, 160)
            val colors = MiuixTheme.colorScheme
            Box(
                Modifier.size(56.dp)
                    .graphicsLayer {
                        val p = motion.progress.value
                        val placement =
                            dockPlacement(width.toFloat(), navHeight.toFloat(), miniPx, gapPx, p)
                        translationX =
                            (coverStartX + (spritePx / 2f - coverStartX) * p) - spritePx / 2f
                        translationY =
                            coverStartY + (placement.coverCenterY - coverStartY) * p -
                                spritePx / 2f + motion.dragOffset * (1f - p)
                        scaleX = 0.75f + 0.25f * p
                        scaleY = scaleX
                        shape = RoundedCornerShape((16f + 12f * p).dp)
                        clip = true
                        shadowElevation = with(density) { 6.dp.toPx() }
                    }
                    .then(dragModifier(true))
                    .combinedClickable(
                        onClickLabel = "打开正在播放",
                        onClick = open,
                        onLongClickLabel = if (collapsed) "暂停播放" else null,
                        onLongClick =
                            if (collapsed) {
                                { Player.get().pause() }
                            } else null,
                    )
                    .semantics {
                        customActions =
                            listOf(
                                CustomAccessibilityAction(if (collapsed) "展开小播放器" else "收起为圆形播放器") {
                                    collapse(!collapsed)
                                    true
                                }
                            )
                    }
                    .background(colors.tertiaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                val image = artwork
                if (image != null)
                    Image(
                        image.asImageBitmap(),
                        null,
                        Modifier.fillMaxSize().graphicsLayer {
                            rotationZ = rotation.value * motion.progress.value
                        },
                        contentScale = ContentScale.Crop,
                    )
                else UiIcon(R.drawable.ic_tab_music, colors.primary)
            }
        }
    }
}

@Composable
private fun DockCard(
    song: Song,
    playing: Boolean,
    revision: Int,
    modifier: Modifier,
    enabled: Boolean,
    open: () -> Unit,
    collapse: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val lines = remember(song.key, song.lyrics) { Lrc.parse(song.lyrics) }
    var lyric by remember(song.key) { mutableStateOf<String?>(null) }
    val fraction = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(song.key, song.lyrics, playing, revision, enabled) {
        do {
            val player = Player.get()
            val duration = player.duration()
            val position = player.position()
            fraction.floatValue =
                if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
            lyric = Lrc.currentText(lines, position.toLong())
            if (!player.isPlaying || !enabled) break
            delay(250)
        } while (isActive)
    }
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surfaceContainer.copy(alpha = 0.96f))
            .clickable(enabled = enabled, onClickLabel = "打开正在播放", onClick = open)
            .semantics {
                customActions =
                    listOf(
                        CustomAccessibilityAction("收起为圆形播放器") {
                            collapse()
                            true
                        }
                    )
            }
            .padding(8.dp)
    ) {
        Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.size(42.dp))
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                UiText(song.title, size = 14, weight = FontWeight.SemiBold, maxLines = 1)
                UiText(
                    if (playing) lyric ?: song.artist.ifBlank { "未知艺人" }
                    else song.artist.ifBlank { "未知艺人" },
                    size = 12,
                    color =
                        if (playing && lyric != null) colors.primary
                        else colors.onSurfaceVariantSummary,
                    maxLines = 1,
                )
            }
            MiuixIconButton(R.drawable.ic_media_prev, "上一首", enabled = enabled, diameter = 44.dp) {
                Player.get().prev()
            }
            MiuixIconButton(
                if (playing) R.drawable.ic_media_pause else R.drawable.ic_media_play,
                if (playing) "暂停" else "播放",
                enabled = enabled,
                accent = true,
                diameter = 44.dp,
            ) {
                Player.get().toggle()
            }
            MiuixIconButton(R.drawable.ic_media_next, "下一首", enabled = enabled, diameter = 44.dp) {
                Player.get().next()
            }
        }
        Spacer(Modifier.height(4.dp))
        Canvas(Modifier.fillMaxWidth().height(2.dp).padding(horizontal = 12.dp)) {
            drawRect(colors.sliderBackground)
            drawRect(colors.primary, size = size.copy(width = size.width * fraction.floatValue))
        }
    }
}
