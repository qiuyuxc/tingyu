package com.qiuyuxc.tingyu

import android.os.Bundle
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal class PersonalMusicPage(private val act: MainActivity, state: Bundle) :
    MusicRouteView(act), Player.Listener {
    private val ranking = state.getBoolean("ranking")
    private var listIndex = state.getInt("index")
    private var listOffset = state.getInt("offset")
    private var playbackRevision by mutableIntStateOf(0)

    override fun saveRoute() =
        Bundle().apply {
            putString("kind", "personal")
            putBoolean("ranking", ranking)
            putInt("index", listIndex)
            putInt("offset", listOffset)
        }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Player.get().addListener(this)
    }

    override fun onDetachedFromWindow() {
        Player.get().removeListener(this)
        super.onDetachedFromWindow()
    }

    override fun onPlayerChanged() {
        playbackRevision++
    }

    @Composable
    override fun Render() {
        val revision = ListeningHistory.revision
        var entries by remember { mutableStateOf<List<ListeningEntry>>(emptyList()) }
        var loading by remember { mutableStateOf(true) }
        val list = rememberLazyListState(listIndex, listOffset)
        val current = remember(playbackRevision) { Player.get().current()?.key }
        LaunchedEffect(revision) {
            entries = withContext(Dispatchers.IO) { ListeningHistory.entries(ranking) }
            loading = false
        }
        LaunchedEffect(list) {
            snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
                .collect { (index, offset) ->
                    listIndex = index
                    listOffset = offset
                }
        }
        Column(Modifier.fillMaxSize()) {
            PageHeader(
                if (ranking) "听歌排行" else "历史记录",
                subtitle = if (ranking) "在听屿中的累计播放" else "最近听过的音乐",
                back = { act.closeMusicRoute() },
            )
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = list,
                contentPadding =
                    PaddingValues(
                        start = UI.pageInset(act).dp,
                        end = UI.pageInset(act).dp,
                        top = 14.dp,
                        bottom = UI.CONTENT_BOTTOM.dp + PageInsets.bottom,
                    ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(entries, key = { _, entry -> entry.track.key }) { index, entry ->
                    val song = remember(entry.track) { entry.track.song() }
                    PersonalSongRow(
                        song,
                        entry,
                        if (ranking) index + 1 else null,
                        song.key == current,
                    ) {
                        act.playSavedSongs(entries.map { it.track.song() }, index)
                    }
                }
                if (entries.isEmpty())
                    item {
                        PageEmptyState(
                            if (ranking) R.drawable.ic_chart else R.drawable.ic_ent_recent,
                            if (loading) "正在读取" else if (ranking) "听歌排行从这里开始" else "还没有播放记录",
                            if (ranking) "播放音乐后，这里会按实际播放次数排序。" else "听过的歌曲会保存在这里。",
                        )
                    }
            }
        }
    }
}

@Composable
private fun PersonalSongRow(
    song: Song,
    entry: ListeningEntry,
    rank: Int?,
    playing: Boolean,
    play: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val artwork by rememberSongArtwork(song, 160)
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClickLabel = "播放${song.title}", onClick = play)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (rank != null)
            UiText(
                rank.toString().padStart(2, '0'),
                Modifier.width(30.dp),
                size = 12,
                color = if (rank <= 3) colors.primary else colors.onSurfaceVariantSummary,
            )
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)),
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
            else
                UiIcon(
                    song.platformTrack?.platform?.icon ?: R.drawable.ic_tab_music,
                    colors.primary,
                )
        }
        Column(
            Modifier.weight(1f).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            UiText(
                song.title,
                color = if (playing) colors.primary else colors.onSurface,
                weight = FontWeight.Medium,
                maxLines = 1,
            )
            UiText(
                song.artist.ifBlank { "未知艺人" },
                size = 12,
                color = colors.onSurfaceVariantSummary,
                maxLines = 1,
            )
            SourceBadge(
                song.platformTrack?.platform,
                access = song.platformTrack?.access ?: TrackAccess.UNKNOWN,
            )
        }
        UiText(
            if (rank != null) "${entry.plays} 次"
            else if (entry.lastPlayed > 0)
                SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(entry.lastPlayed))
            else "旧播放记录",
            size = 11,
            color = if (playing) colors.primary else colors.onSurfaceVariantSummary,
            maxLines = 1,
        )
    }
}
