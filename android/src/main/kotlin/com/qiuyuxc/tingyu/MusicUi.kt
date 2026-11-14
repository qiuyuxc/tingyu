package com.qiuyuxc.tingyu

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun MusicSearchField(
    value: String,
    onChange: (String) -> Unit,
    onSearch: () -> Unit,
    placeholder: String = "搜索歌曲、歌手",
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(17.dp))
            .background(colors.surfaceContainer)
            .padding(start = 15.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        UiIcon(R.drawable.ic_action_search, colors.onSurfaceVariantSummary, Modifier.size(20.dp))
        BasicTextField(
            value = value,
            onValueChange = { onChange(it.take(100).replace('\n', ' ')) },
            singleLine = true,
            textStyle = TextStyle(color = colors.onSurface, fontSize = 16.sp, lineHeight = 23.sp),
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            modifier =
                Modifier.weight(1f).padding(vertical = 16.dp).semantics {
                    contentDescription = placeholder
                },
            decorationBox = { input ->
                Box {
                    if (value.isBlank())
                        UiText(
                            placeholder,
                            size = 14,
                            color = colors.onSurfaceVariantSummary,
                            maxLines = 1,
                        )
                    input()
                }
            },
        )
        if (value.isNotEmpty())
            MiuixIconButton(R.drawable.ic_nav_close, "清空搜索", diameter = 44.dp) { onChange("") }
    }
}

@Composable
internal fun MusicCommentsButton(total: Long?, action: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    val label = total?.let { "查看评论，共 $it 条" } ?: "查看评论"
    Row(
        Modifier.widthIn(min = 64.dp, max = 100.dp)
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.secondaryContainer)
            .border(1.dp, colors.primary.copy(alpha = 0.28f), RoundedCornerShape(14.dp))
            .clickable(onClickLabel = label, role = Role.Button, onClick = action)
            .semantics { contentDescription = label }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        UiIcon(
            R.drawable.ic_comment,
            colors.primary,
            Modifier.size(18.dp),
        )
        UiText(
            total?.toString() ?: "评论",
            Modifier.widthIn(max = 56.dp),
            size = 12,
            color = colors.primary,
            weight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
internal fun PlatformImage(url: String, size: Dp = 52.dp, modifier: Modifier = Modifier) {
    val colors = MiuixTheme.colorScheme
    val pixels = 160
    val artwork =
        remember(url, pixels) { mutableStateOf(PlatformArtwork.cachedThumbnail(url, pixels)) }
    LaunchedEffect(url, pixels) {
        if (artwork.value == null)
            artwork.value =
                withContext(Dispatchers.IO) {
                    runCatching { PlatformArtwork.thumbnail(url, pixels) }.getOrNull()
                }
    }
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.23f))
            .background(colors.tertiaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = artwork.value
        if (bitmap != null)
            Image(
                bitmap.asImageBitmap(),
                null,
                Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        else UiIcon(R.drawable.ic_tab_music, colors.primary)
    }
}

@Composable
internal fun PlatformSongImage(track: PlatformTrack, size: Dp = 50.dp) {
    val colors = MiuixTheme.colorScheme
    val song = remember(track.key, track.cover) { Song(track) }
    val artwork by rememberSongArtwork(song, 160)
    Box(
        Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(colors.tertiaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = artwork
        if (bitmap != null)
            Image(
                bitmap.asImageBitmap(),
                null,
                Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        else UiIcon(track.platform.icon, colors.primary)
    }
}

@Composable
internal fun SourceBadge(
    platform: MusicPlatform? = null,
    access: TrackAccess = TrackAccess.UNKNOWN,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    val label =
        platform?.let { if (it == MusicPlatform.NETEASE) "网易云" else "酷狗" }
            ?: "本地"
    val icon = platform?.icon ?: R.drawable.ic_ent_songs
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.clip(RoundedCornerShape(5.dp))
                .background(colors.secondaryContainer)
                .padding(horizontal = 5.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            UiIcon(icon, colors.onSurfaceVariantSummary, Modifier.size(11.dp))
            UiText(label, size = 10, color = colors.onSurfaceVariantSummary, maxLines = 1)
        }
        if (access.label.isNotBlank()) {
            val vip = access == TrackAccess.VIP || access == TrackAccess.VIP_QUALITY
            val ink =
                if (vip) membershipColor(MembershipTier.VIP, colors.surface.luminance() < 0.5f)
                else colors.primary
            UiText(
                access.label,
                size = 10,
                color = ink,
                maxLines = 1,
                modifier =
                    Modifier.clip(RoundedCornerShape(5.dp))
                        .background(if (vip) ink.copy(alpha = 0.12f) else colors.tertiaryContainer)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
internal fun VipBadge(membership: PlatformMembership?, modifier: Modifier = Modifier) {
    val kind = membership?.badgeTier()?.takeIf { it != MembershipTier.MUSIC_PACKAGE } ?: return
    MembershipBadge(kind, modifier)
}

/** Membership keeps its own warm accents even when the app uses a neutral palette. */
internal fun membershipColor(kind: MembershipTier, dark: Boolean): Color =
    Color(
        when {
            kind == MembershipTier.SVIP && dark -> 0xFFFFAF73
            kind == MembershipTier.SVIP -> 0xFFA34512
            dark -> 0xFFFFD074
            else -> 0xFF8D5700
        }
    )

@Composable
internal fun MembershipBadge(kind: MembershipTier, modifier: Modifier = Modifier) {
    val colors = MiuixTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    val ink = membershipColor(kind, dark)
    val shape = RoundedCornerShape(7.dp)
    Row(
        modifier
            .clip(shape)
            .background(ink.copy(alpha = if (dark) 0.16f else 0.10f))
            .border(1.dp, ink.copy(alpha = 0.24f), shape)
            .padding(horizontal = 7.dp, vertical = 4.dp)
            .semantics { contentDescription = kind.label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        UiIcon(
            if (kind == MembershipTier.SVIP) R.drawable.ic_svip else R.drawable.ic_vip,
            ink,
            Modifier.size(13.dp),
        )
        UiText(
            kind.label,
            size = 10,
            color = ink,
            weight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
internal fun PlatformSongRow(
    track: PlatformTrack,
    playing: Boolean = false,
    rank: Int? = null,
    onClick: () -> Unit,
    onMore: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (playing) colors.tertiaryContainer.copy(alpha = 0.55f) else Color.Transparent
            )
            .clickable(onClickLabel = "播放${track.title}", onClick = onClick)
            .padding(vertical = 9.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (rank != null)
            UiText(
                rank.toString().padStart(2, '0'),
                Modifier.width(28.dp),
                size = 12,
                color = if (rank <= 3) colors.primary else colors.onSurfaceVariantSummary,
                weight = FontWeight.Medium,
            )
        PlatformSongImage(track)
        Column(
            Modifier.weight(1f).padding(horizontal = 11.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            UiText(
                track.title,
                size = 14,
                weight = FontWeight.Medium,
                maxLines = 1,
                color = if (playing) colors.primary else colors.onSurface,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                UiText(
                    track.artist.ifBlank { "未知艺人" },
                    Modifier.weight(1f, fill = false),
                    size = 12,
                    color = colors.onSurfaceVariantSummary,
                    maxLines = 1,
                )
                SourceBadge(track.platform, access = track.access)
            }
        }
        if (onMore != null)
            MiuixIconButton(
                R.drawable.ic_action_more,
                "${track.title}的更多操作",
                diameter = 44.dp,
                onClick = onMore,
            )
        else if (playing) UiIcon(R.drawable.ic_quality, colors.primary, Modifier.size(17.dp))
    }
}

@Composable
internal fun MusicSectionTitle(
    title: String,
    action: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = MiuixTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UiText(title, Modifier.weight(1f), size = 18, weight = FontWeight.SemiBold)
        if (action != null && onClick != null) TextAction(action, action = onClick)
    }
}

@Composable
internal fun MusicStatus(
    title: String,
    message: String,
    action: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(20.dp), cornerRadius = 22.dp) {
        UiText(title, size = 17, weight = FontWeight.SemiBold)
        Spacer(Modifier.height(7.dp))
        UiText(message, size = 13, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        if (action != null && onClick != null) {
            Spacer(Modifier.height(15.dp))
            MiuixButton(action, accent = false, onClick = onClick)
        }
    }
}

/** One request at a time; a new page or a changed scroll position can trigger the next one. */
@Composable
internal fun LoadWhenNearEnd(
    list: LazyListState,
    enabled: Boolean,
    loading: Boolean,
    routeKey: Any,
    onLoad: suspend () -> Unit,
) {
    val ready by rememberUpdatedState(enabled && !loading)
    val load by rememberUpdatedState(onLoad)
    LaunchedEffect(list, routeKey) {
        snapshotFlow {
            Triple(
                list.layoutInfo.totalItemsCount,
                list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1,
                ready,
            )
        }
            .collect { (count, last, active) ->
                if (active && ready && count > 0 && last >= count - 5) load()
            }
    }
}

@Composable
internal fun MusicFeedFooter(
    loading: Boolean,
    more: Boolean,
    error: String? = null,
    retry: () -> Unit = {},
) {
    val colors = MiuixTheme.colorScheme
    when {
        error != null ->
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                UiText(error, size = 12, color = colors.onSurfaceVariantSummary)
                TextAction("重新获取", action = retry)
            }
        loading ->
            UiText(
                "正在加载…",
                Modifier.padding(vertical = 16.dp),
                size = 12,
                color = colors.onSurfaceVariantSummary,
            )
        !more ->
            UiText(
                "已显示全部内容",
                Modifier.padding(vertical = 16.dp),
                size = 12,
                color = colors.onSurfaceVariantSummary,
            )
        else -> Spacer(Modifier.height(20.dp))
    }
}

/** A compact native badge for file rows and the native playback queue. */
internal fun songSourceLine(context: Context, song: Song, dark: Boolean = false): LinearLayout {
    return musicSourceLine(
        context,
        song.platformTrack?.platform,
        song.platformTrack?.access ?: TrackAccess.UNKNOWN,
        dark,
    )
}

internal fun musicSourceLine(
    context: Context,
    platform: MusicPlatform? = null,
    access: TrackAccess = TrackAccess.UNKNOWN,
    dark: Boolean = false,
): LinearLayout {
    val palette = if (dark) Palette.themed(Theme.palette(), true) else Theme.p()
    val label =
        platform?.let { if (it == MusicPlatform.NETEASE) "网易云" else "酷狗" }
            ?: "本地"
    val line = UI.row(context)
    val badge =
        UI.row(context).apply {
            setPadding(UI.dp(5), UI.dp(2), UI.dp(5), UI.dp(2))
            background = UI.rounded(palette.canvasSoft2, 5f)
            contentDescription = "音乐来源：$label"
        }
    badge.addView(
        android.widget.ImageView(context).apply {
            setImageResource(
                platform?.icon ?: R.drawable.ic_ent_songs
            )
            setColorFilter(palette.mute)
        },
        LinearLayout.LayoutParams(UI.dp(11), UI.dp(11)),
    )
    badge.addView(
        UI.text(context, label, 10f, palette.mute, Typeface.NORMAL).apply {
            setPadding(UI.dp(3), 0, 0, 0)
        }
    )
    line.addView(badge)
    val rights = access.label
    if (rights.isNotBlank())
        line.addView(
            UI.text(context, rights, 10f, palette.accentInk, Typeface.BOLD).apply {
                setPadding(UI.dp(6), 0, UI.dp(5), 0)
            }
        )
    line.gravity = Gravity.CENTER_VERTICAL
    return line
}

internal class AppearancePreviewView(context: Context) : MiuixView(context) {
    @Composable
    override fun Render() {
        val colors = MiuixTheme.colorScheme
        Row(
            Modifier.fillMaxWidth()
                .padding(18.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(colors.surface)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(48.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(colors.tertiaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                UiIcon(R.drawable.ic_tab_music, colors.primary)
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                UiText("音乐随心而动", size = 14, weight = FontWeight.Medium)
                UiText("当前主题预览", size = 12, color = colors.onSurfaceVariantSummary)
            }
            MiuixIconButton(R.drawable.ic_media_play, "主题预览", accent = true, diameter = 40.dp) {}
        }
    }
}
