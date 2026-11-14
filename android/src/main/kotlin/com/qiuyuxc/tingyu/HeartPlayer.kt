package com.qiuyuxc.tingyu

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal data class HeartLayout(
    val wide: Boolean,
    val bodyHeight: Float,
    val infoHeight: Float,
    val actionsHeight: Float,
    val progressHeight: Float,
    val transportHeight: Float,
    val controlsViewportHeight: Float,
    val inlineActions: Boolean,
)

/**
 * Short windows scroll inside the page instead of squeezing controls over either navigation bar.
 */
internal fun heartLayout(
    width: Float,
    height: Float,
    fontScale: Float,
    lyrics: Boolean,
): HeartLayout {
    val scale = fontScale.coerceAtLeast(1f)
    val wide = width >= 480f && width > height
    val detailsWidth = if (wide) (width - 16f) * 0.55f else width
    val inlineActions = detailsWidth >= 188f + 8f + 116f * scale
    val info = if (lyrics) 0f else minOf(176f * scale, maxOf(112f, height * 0.30f))
    val contextHeight = 33.6f * scale + 8f
    val actions =
        if (lyrics) 0f
        else if (inlineActions) maxOf(56f, contextHeight)
        else contextHeight + 8f + maxOf(44f, 16.8f * scale + 16f)
    val progress = maxOf(56f, 40f + 15.4f * scale)
    val transport = if (wide || height < 400f) 56f else 76f
    val minimum = info + actions + progress + transport + if (wide) 0f else 160f
    return HeartLayout(
        wide,
        if (lyrics) height else maxOf(height, minimum),
        info,
        actions,
        progress,
        transport,
        if (lyrics) minOf(progress + transport, height * 0.65f) else progress + transport,
        inlineActions,
    )
}

/** A page-sized radio layout; its measurements and drawing stay inside the music viewport. */
@Composable
internal fun HeartPlayer(
    act: MainActivity,
    start: () -> Unit,
    lyrics: Boolean,
    showLyrics: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val player = Player.get()
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val listener =
            object : Player.Listener {
                override fun onPlayerChanged() {
                    revision++
                }
            }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    val song = remember(revision) { player.current() }
    val playing = remember(revision) { player.isPlaying }
    val notice = remember(revision) { player.lastError ?: player.playbackNotice }
    val position = remember(song?.key) { HeartPosition() }
    val artwork = song?.let { rememberSongArtwork(it, 720).value }
    LaunchedEffect(song?.key, revision) {
        do {
            position.millis = player.position()
            position.duration = player.duration()
            if (!player.isPlaying) break
            delay(250)
        } while (isActive)
    }
    BackHandler(lyrics) { showLyrics(false) }
    val density = LocalDensity.current
    Box(modifier.fillMaxSize().clipToBounds()) {
        BoxWithConstraints(
            Modifier.fillMaxSize()
                .padding(horizontal = 20.dp)
                .padding(top = 6.dp, bottom = PageInsets.bottom + 8.dp)
                .clipToBounds()
        ) {
            val layout = heartLayout(maxWidth.value, maxHeight.value, density.fontScale, lyrics)
            val flip = { showLyrics(!lyrics) }
            if (lyrics) {
                Column(Modifier.fillMaxSize()) {
                    HeartVisual(
                        artwork,
                        song?.key,
                        song?.lyrics.orEmpty(),
                        position,
                        true,
                        flip,
                        Modifier.weight(1f).fillMaxWidth(),
                    )
                    Column(
                        Modifier.fillMaxWidth()
                            .height(layout.controlsViewportHeight.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        HeartProgress(
                            song?.key,
                            position,
                            Modifier.height(layout.progressHeight.dp),
                        )
                        HeartTransport(
                            act,
                            playing,
                            song != null,
                            revision,
                            layout.transportHeight.dp,
                            start,
                        )
                    }
                }
            } else {
                val body =
                    Modifier.fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .height(layout.bodyHeight.dp)
                if (layout.wide) {
                    Row(body, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        HeartVisual(
                            artwork,
                            song?.key,
                            song?.lyrics.orEmpty(),
                            position,
                            lyrics,
                            flip,
                            Modifier.weight(0.45f).fillMaxHeight(),
                        )
                        Column(Modifier.weight(0.55f).fillMaxHeight()) {
                            Box(Modifier.weight(1f), contentAlignment = Alignment.BottomStart) {
                                HeartInfo(
                                    song?.title,
                                    song?.artist,
                                    notice,
                                    layout.infoHeight.dp,
                                )
                            }
                            HeartActions(
                                act,
                                song,
                                position,
                                flip,
                                layout.inlineActions,
                                Modifier.height(layout.actionsHeight.dp),
                            )
                            HeartProgress(
                                song?.key,
                                position,
                                Modifier.height(layout.progressHeight.dp),
                            )
                            HeartTransport(
                                act,
                                playing,
                                song != null,
                                revision,
                                layout.transportHeight.dp,
                                start,
                            )
                        }
                    }
                } else {
                    Column(body) {
                        HeartVisual(
                            artwork,
                            song?.key,
                            song?.lyrics.orEmpty(),
                            position,
                            lyrics,
                            flip,
                            Modifier.weight(1f).fillMaxWidth(),
                        )
                        HeartInfo(
                            song?.title,
                            song?.artist,
                            notice,
                            layout.infoHeight.dp,
                        )
                        HeartActions(
                            act,
                            song,
                            position,
                            flip,
                            layout.inlineActions,
                            Modifier.height(layout.actionsHeight.dp),
                        )
                        HeartProgress(
                            song?.key,
                            position,
                            Modifier.height(layout.progressHeight.dp),
                        )
                        HeartTransport(
                            act,
                            playing,
                            song != null,
                            revision,
                            layout.transportHeight.dp,
                            start,
                        )
                    }
                }
            }
        }
    }
}

@Stable
private class HeartPosition {
    var millis by mutableIntStateOf(0)
    var duration by mutableIntStateOf(0)
}

@Composable
private fun HeartVisual(
    artwork: Bitmap?,
    songKey: String?,
    rawLyrics: String,
    position: HeartPosition,
    lyrics: Boolean,
    flip: () -> Unit,
    modifier: Modifier,
) {
    val colors = MiuixTheme.colorScheme
    if (lyrics && songKey != null) {
        var lyricView by remember { mutableStateOf<me.wcy.lrcview.LrcView?>(null) }
        val density = LocalDensity.current
        val normal = colors.onSurfaceVariantSummary.toArgb()
        val active = colors.onSurface.toArgb()
        AndroidView(
            factory = { context ->
                (LayoutInflater.from(context).inflate(R.layout.player_lyrics, null, false)
                        as me.wcy.lrcview.LrcView)
                    .apply {
                        clipToOutline = true
                        setNormalColor(normal)
                        setCurrentColor(active)
                        setTimelineTextColor(active)
                        setTimelineColor(normal)
                        setTimeTextColor(normal)
                        setNormalTextSize(with(density) { 18.sp.toPx() })
                        setCurrentTextSize(with(density) { 22.sp.toPx() })
                        setLabel("这首歌没有歌词")
                        setSeekOnRelease(true)
                        setDraggable(true) { _, time ->
                            if (Player.get().duration() <= 0) false
                            else {
                                Player.get()
                                    .seekTo(time.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                                true
                            }
                        }
                        setOnTapListener { _, _, _ -> flip() }
                        setOnTouchListener { view, event ->
                            if (event.actionMasked == MotionEvent.ACTION_DOWN)
                                view.parent?.requestDisallowInterceptTouchEvent(true)
                            else if (
                                event.actionMasked == MotionEvent.ACTION_UP ||
                                    event.actionMasked == MotionEvent.ACTION_CANCEL
                            )
                                view.parent?.requestDisallowInterceptTouchEvent(false)
                            false
                        }
                        contentDescription = "歌词，滑动选择，松手跳转，点按返回封面"
                        lyricView = this
                    }
            },
            update = { view ->
                val key = songKey to rawLyrics
                if (view.tag != key) {
                    val split = Lrc.splitTranslation(rawLyrics)
                    view.loadLrc(split.first, split.second.takeIf { it.isNotBlank() })
                    view.tag = key
                }
                view.updateTime(position.millis.toLong())
            },
            modifier = modifier.clipToBounds(),
        )
        // LrcView parses asynchronously. Keep updating after a paused load as well, so the
        // first parsed frame and returning from another page both find the current verse.
        LaunchedEffect(lyricView, songKey, rawLyrics) {
            val view = lyricView ?: return@LaunchedEffect
            while (isActive) {
                view.updateTime(Player.get().position().toLong())
                delay(250)
            }
        }
    } else {
        BoxWithConstraints(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
            val edge = minOf(maxWidth, maxHeight).coerceAtLeast(0.dp)
            Box(
                Modifier.size(edge)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(colors.tertiaryContainer)
                    .clickable(enabled = songKey != null, onClickLabel = "查看歌词", onClick = flip),
                contentAlignment = Alignment.Center,
            ) {
                if (songKey != null) {
                    val bitmap = artwork
                    if (bitmap != null)
                        Image(
                            bitmap.asImageBitmap(),
                            null,
                            Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    else UiIcon(R.drawable.ic_tab_music, colors.primary, Modifier.size(48.dp))
                } else UiIcon(R.drawable.ic_favorite_outline, colors.primary, Modifier.size(48.dp))
            }
        }
    }
}

@Composable
private fun HeartInfo(
    title: String?,
    artist: String?,
    notice: String?,
    maxHeight: androidx.compose.ui.unit.Dp,
) {
    val colors = MiuixTheme.colorScheme
    Column(
        Modifier.fillMaxWidth()
            .heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        UiText(title ?: "让下一首遇见你", size = 22, weight = FontWeight.SemiBold, maxLines = 2)
        if (artist != null) {
            UiText(
                artist.ifBlank { "未知艺人" },
                size = 12,
                color = colors.onSurfaceVariantSummary,
                maxLines = 1,
            )
            if (!notice.isNullOrBlank())
                UiText(notice, size = 11, color = colors.onSurfaceVariantSummary, maxLines = 2)
        }
    }
}

@Composable
private fun HeartLyricPreview(
    lines: MutableList<Lrc.Line?>?,
    position: HeartPosition,
    flip: () -> Unit,
) {
    val text =
        Lrc.currentText(lines, position.millis.toLong())
            ?: if (lines.isNullOrEmpty()) "暂无歌词" else "前奏"
    UiText(
        text,
        Modifier.clickable(onClickLabel = "查看完整歌词", onClick = flip),
        size = 14,
        color = MiuixTheme.colorScheme.primary,
        maxLines = 1,
    )
}

@Composable
private fun HeartActions(
    act: MainActivity,
    song: Song?,
    position: HeartPosition,
    flip: () -> Unit,
    inline: Boolean,
    modifier: Modifier,
) {
    val track = song?.platformTrack
    val accountRevision = AccountStore.revision
    val account =
        remember(accountRevision, track?.platform) {
            AccountStore.accounts(act).firstOrNull { it.platform == track?.platform }
        }
    val libraryRevision = PlatformLibraryStore.revision
    val library = remember(libraryRevision, account) { account?.let(PlatformLibraryStore::get) }
    LaunchedEffect(account?.platform, account?.userId, account?.connectedAt) {
        val profile = account ?: return@LaunchedEffect
        val previous = PlatformLibraryStore.get(profile)
        if (previous?.favoritesReady == true) return@LaunchedEffect
        val loaded =
            withContext(Dispatchers.IO) {
                runCatching {
                    val auth =
                        AccountStore.authorization(act, profile.platform) ?: return@runCatching null
                    if (
                        auth.profile.userId != profile.userId ||
                            auth.profile.connectedAt != profile.connectedAt
                    )
                        return@runCatching null
                    PlatformLibraryClient(auth.profile, auth.session).loadLibrary()
                }
                    .getOrNull()
            } ?: return@LaunchedEffect
        if (
            AccountStore.accounts(act).any {
                it.platform == profile.platform &&
                    it.userId == profile.userId &&
                    it.connectedAt == profile.connectedAt
            } && PlatformLibraryStore.get(profile)?.favoritesReady != true
        )
            PlatformLibraryStore.publish(profile, loaded)
    }
    var comments by
        remember(track?.key) { mutableStateOf(track?.let(DiscoveryStore::commentCount)) }
    LaunchedEffect(track?.key, account?.userId, account?.connectedAt) {
        if (track != null)
            comments =
                withContext(Dispatchers.IO) {
                    runCatching {
                        PlatformDiscoveryClient.forPlatform(act, track.platform).commentCount(track)
                    }
                        .getOrNull()
                }
    }
    val colors = MiuixTheme.colorScheme
    val buttons: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (track != null) {
                val liked = library?.favorites?.containsKey(track.key) == true
                val pending = favoritePending(track)
                if (pending)
                    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp)
                    }
                else
                    MiuixIconButton(
                        if (liked) R.drawable.ic_favorite else R.drawable.ic_favorite_outline,
                        if (liked) "取消喜欢" else "喜欢这首歌",
                        diameter = 44.dp,
                        iconColor = if (liked) colors.primary else colors.onSurface,
                    ) {
                        act.togglePlatformFavorite(track)
                    }
                MiuixIconButton(R.drawable.ic_quality, "调整音质", diameter = 44.dp) {
                    act.showAudioQualityOptions(track.platform, track)
                }
                MusicCommentsButton(comments) { act.openComments(track) }
            }
        }
    }
    if (inline) {
        Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            HeartContext(song, position, flip, Modifier.weight(1f).padding(end = 8.dp))
            buttons()
        }
    } else {
        Column(
            modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
        ) {
            HeartContext(song, position, flip, Modifier.fillMaxWidth())
            Box(Modifier.align(Alignment.End)) { buttons() }
        }
    }
}

/** Share the actions row so song information sits lower without moving the transport controls. */
@Composable
private fun HeartContext(
    song: Song?,
    position: HeartPosition,
    flip: () -> Unit,
    modifier: Modifier,
) {
    val lines = remember(song?.lyrics) { Lrc.parse(song?.lyrics.orEmpty()) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (song != null) {
            SourceBadge(
                song.platformTrack?.platform,
                song.platformTrack?.access ?: TrackAccess.UNKNOWN,
            )
            HeartLyricPreview(lines, position, flip)
        }
    }
}

@Composable
private fun HeartProgress(songKey: String?, position: HeartPosition, modifier: Modifier) {
    var dragging by remember(songKey) { mutableStateOf(false) }
    var fraction by remember(songKey) { mutableFloatStateOf(0f) }
    var pending by remember(songKey) { mutableIntStateOf(-1) }
    var pendingAt by remember(songKey) { mutableLongStateOf(0L) }
    val actual = position.millis
    val duration = position.duration
    LaunchedEffect(actual, pending) {
        if (
            pending >= 0 &&
                (abs(actual - pending) < 800 ||
                    android.os.SystemClock.uptimeMillis() - pendingAt > 2000)
        )
            pending = -1
    }
    val displayed =
        if (dragging) (duration * fraction).toInt() else if (pending >= 0) pending else actual
    val value =
        if (dragging) fraction
        else if (duration > 0) (displayed.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.Center) {
        Slider(
            value = value,
            enabled = duration > 0,
            modifier =
                Modifier.fillMaxWidth().height(36.dp).semantics { contentDescription = "播放进度" },
            height = 8.dp,
            onValueChange = {
                dragging = true
                fraction = it
            },
            onValueChangeFinished = {
                pending = (duration * fraction).toInt()
                pendingAt = android.os.SystemClock.uptimeMillis()
                Player.get().seekTo(pending)
                dragging = false
            },
        )
        Row(Modifier.fillMaxWidth()) {
            UiText(
                Lrc.mmss(displayed.toLong()).orEmpty(),
                Modifier.weight(1f),
                size = 11,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            UiText(
                Lrc.mmss(duration.toLong()).orEmpty(),
                size = 11,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun HeartTransport(
    act: MainActivity,
    playing: Boolean,
    available: Boolean,
    revision: Int,
    height: androidx.compose.ui.unit.Dp,
    start: () -> Unit,
) {
    val mode = remember(revision) { Player.get().mode() }
    val icon =
        when (mode) {
            Player.MODE_SHUFFLE -> R.drawable.ic_mode_shuffle
            Player.MODE_ONE -> R.drawable.ic_mode_one
            else -> R.drawable.ic_mode_repeat
        }
    Row(
        Modifier.fillMaxWidth().height(height),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        MiuixIconButton(icon, "切换播放模式", diameter = 40.dp, enabled = available) {
            Player.get().cycleMode()
        }
        MiuixIconButton(R.drawable.ic_media_prev, "上一首", diameter = 40.dp, enabled = available) {
            Player.get().prev()
        }
        MiuixIconButton(
            if (playing) R.drawable.ic_media_pause else R.drawable.ic_media_play,
            if (playing) "暂停" else "播放",
            accent = true,
            diameter = minOf(64.dp, height),
        ) {
            if (available) Player.get().toggle() else start()
        }
        MiuixIconButton(R.drawable.ic_media_next, "下一首", diameter = 40.dp, enabled = available) {
            Player.get().next()
        }
        MiuixIconButton(R.drawable.ic_queue, "播放队列", diameter = 40.dp, enabled = available) {
            PlaybackQueue.show(act)
        }
    }
}

/** Blur a tiny image once per cover, rather than filtering the entire player every frame. */
@Composable
internal fun HeartBackground() {
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val listener =
            object : Player.Listener {
                override fun onPlayerChanged() {
                    revision++
                }
            }
        Player.get().addListener(listener)
        onDispose { Player.get().removeListener(listener) }
    }
    val song = remember(revision) { Player.get().current() }
    val artwork = song?.let { rememberSongArtwork(it, 720).value }
    if (artwork != null) RadioAmbient(artwork)
}

@Composable
private fun RadioAmbient(source: Bitmap) {
    var image by remember(source) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(source) {
        val bitmap = source
        image =
            withContext(Dispatchers.Default) {
                val small = Bitmap.createScaledBitmap(bitmap, 32, 32, true)
                var pixels = IntArray(1024)
                small.getPixels(pixels, 0, 32, 0, 0, 32, 32)
                repeat(3) {
                    val next = IntArray(1024)
                    for (y in 0..31) for (x in 0..31) {
                        var r = 0
                        var g = 0
                        var b = 0
                        for (dy in -1..1) for (dx in -1..1) {
                            val p = pixels[(y + dy).coerceIn(0, 31) * 32 + (x + dx).coerceIn(0, 31)]
                            r += p shr 16 and 255
                            g += p shr 8 and 255
                            b += p and 255
                        }
                        next[y * 32 + x] =
                            (255 shl 24) or (r / 9 shl 16) or (g / 9 shl 8) or (b / 9)
                    }
                    pixels = next
                }
                Bitmap.createBitmap(pixels, 32, 32, Bitmap.Config.ARGB_8888)
            }
    }
    val bitmap = image
    if (bitmap != null)
        Image(
            bitmap.asImageBitmap(),
            null,
            Modifier.fillMaxSize().clipToBounds(),
            contentScale = ContentScale.Crop,
            alpha = if (Theme.isDark) 0.17f else 0.10f,
        )
}
