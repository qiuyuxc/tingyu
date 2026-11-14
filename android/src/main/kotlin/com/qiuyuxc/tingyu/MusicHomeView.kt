package com.qiuyuxc.tingyu

import android.view.View
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Discovery, the library and listening history are separate destinations. */
internal class MusicHomeView(
    private val act: MainActivity,
    private val playFile: (Song) -> Unit,
    private val scan: () -> Unit,
    initialExpanded: Boolean = false,
    private val initialSection: Int = 0,
    private val initialListIndex: Int = 0,
    private val initialListOffset: Int = 0,
) : MiuixView(act) {
    private var library by mutableStateOf<List<Song>>(emptyList())
    private var recent by mutableStateOf<List<Song>>(emptyList())
    private var platform by
        mutableStateOf(
            MusicPlatform.fromId(
                act.getSharedPreferences(MainActivity.PREFS, 0).getString("discoveryPlatform", null)
            ) ?: MusicPlatform.NETEASE
        )
    private var refresh by mutableIntStateOf(0)
    private var expanded by mutableStateOf(initialExpanded)
    private var section by mutableIntStateOf(initialSection.coerceIn(0, 3))
    var listIndex = initialListIndex
        private set

    var listOffset = initialListOffset
        private set

    private var resetScroll by mutableIntStateOf(0)
    private var attached by mutableStateOf(false)

    val top10Expanded: Boolean
        get() = expanded

    val selectedSection: Int
        get() = section

    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    }

    fun update(songs: List<Song>, history: List<Song>) {
        library = songs
        recent = history.distinctBy { it.key }.take(20)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
    }

    override fun onDetachedFromWindow() {
        attached = false
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        post { attached = isAttachedToWindow && isShown }
    }

    @Composable
    override fun Render() {
        val lists =
            listOf(
                rememberLazyListState(
                    if (initialSection == 0) initialListIndex else 0,
                    if (initialSection == 0) initialListOffset else 0,
                ),
                rememberLazyListState(
                    if (initialSection == 1) initialListIndex else 0,
                    if (initialSection == 1) initialListOffset else 0,
                ),
                rememberLazyListState(
                    if (initialSection == 2) initialListIndex else 0,
                    if (initialSection == 2) initialListOffset else 0,
                ),
            )
        val list = lists.getOrNull(section) ?: lists[0]
        val scope = rememberCoroutineScope()
        DisposableEffect(Unit) {
            onDispose { act.setHeartPageVisible(false) }
        }
        LaunchedEffect(list) {
            snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
                .collect { (index, offset) ->
                    listIndex = index
                    listOffset = offset
                }
        }
        LaunchedEffect(resetScroll) { if (resetScroll > 0) list.scrollToItem(0) }
        val orderedSections = listOf(3, 0, 1, 2)
        Box(Modifier.fillMaxSize().clipToBounds().background(MiuixTheme.colorScheme.surface)) {
            if (section == 3 && attached && Theme.artworkBackground()) HeartBackground()
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = UI.pageInset(act).dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PageTabs(
                        listOf("心动", "发现", "曲库", "最近"),
                        orderedSections.indexOf(section),
                        { index ->
                            val next = orderedSections[index]
                            if (section != next) {
                                section = next
                                lists.getOrNull(next)?.let { target ->
                                    scope.launch { target.scrollToItem(0) }
                                }
                            }
                        },
                        Modifier.weight(1f),
                    )
                    MiuixIconButton(R.drawable.ic_action_search, "搜索音乐", diameter = 44.dp) {
                        act.openMusicSearch()
                    }
                }
                Crossfade(
                    targetState = section,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    animationSpec = tween(180),
                    label = "MusicSection",
                ) { selected ->
                    when (selected) {
                        3 ->
                            HeartRadioView(
                                act,
                                platform,
                                attached && section == 3 && !act.fullPlayerVisible,
                                ::choosePlatform,
                            )
                        1 ->
                            LazyColumn(
                                Modifier.fillMaxSize(),
                                state = lists[selected],
                                contentPadding = contentPadding(),
                            ) {
                                item { Library() }
                            }
                        2 ->
                            LazyColumn(
                                Modifier.fillMaxSize(),
                                state = lists[selected],
                                contentPadding = contentPadding(),
                            ) {
                                item { History() }
                            }
                        else -> Discovery(lists[selected], Modifier.fillMaxSize())
                    }
                }
            }
        }
    }

    private fun contentPadding() =
        PaddingValues(
            start = UI.pageInset(act).dp,
            end = UI.pageInset(act).dp,
            top = 18.dp,
            bottom = UI.CONTENT_BOTTOM.dp + PageInsets.bottom,
        )

    @Composable
    private fun Discovery(list: LazyListState, modifier: Modifier) {
        val colors = MiuixTheme.colorScheme
        val accountRevision = AccountStore.revision
        val account =
            remember(accountRevision, platform) {
                AccountStore.accounts(act).firstOrNull { it.platform == platform }
            }
        val accountKey = "${account?.userId}:${account?.connectedAt}"
        var discovery by remember(platform) { mutableStateOf(DiscoveryStore.get(platform)) }
        var loading by remember(platform) { mutableStateOf(discovery == null) }
        var problem by remember(platform) { mutableStateOf<String?>(null) }
        var recommendations by
            remember(platform) { mutableStateOf(discovery?.recommendations.orEmpty()) }
        var recommendationPage by
            remember(platform) { mutableIntStateOf(discovery?.recommendationPage ?: 0) }
        var loadingMore by remember(platform) { mutableStateOf(false) }
        var pagingProblem by remember(platform) { mutableStateOf<String?>(null) }
        LaunchedEffect(platform, refresh, accountKey) {
            loading = true
            problem = null
            pagingProblem = null
            try {
                val result =
                    withContext(Dispatchers.IO) {
                        PlatformDiscoveryClient.forPlatform(act.applicationContext, platform)
                            .home(force = refresh > 0)
                    }
                discovery = result
                recommendations = result.recommendations
                recommendationPage = result.recommendationPage
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                problem = error.message ?: "暂时无法读取推荐"
            } finally {
                loading = false
            }
        }
        LoadWhenNearEnd(
            list,
            attached && !loading && recommendations.isNotEmpty() && pagingProblem == null,
            loadingMore,
            "home:${platform.id}:$refresh:$accountKey",
        ) {
            val selected = platform
            val excluded = recommendations.map { it.key }.toSet()
            loadingMore = true
            try {
                val next =
                    withContext(Dispatchers.IO) {
                        PlatformDiscoveryClient.forPlatform(act.applicationContext, selected)
                            .recommendations(recommendationPage, excluded)
                    }
                if (selected != platform) return@LoadWhenNearEnd
                val merged = (recommendations + next).distinctBy { it.key }
                require(merged.size > recommendations.size) { "暂时没有新的推荐歌曲，请稍后重试" }
                recommendations = merged
                recommendationPage++
                discovery =
                    discovery?.copy(
                        recommendations = merged,
                        recommendationPage = recommendationPage,
                    )
                discovery?.let { DiscoveryStore.publish(selected, it) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                pagingProblem = error.message ?: "推荐暂时无法继续获取"
            } finally {
                loadingMore = false
            }
        }
        val snapshot = discovery
        LazyColumn(modifier.fillMaxWidth(), state = list, contentPadding = contentPadding()) {
            item(key = "chart-heading") {
                FlowRow(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    UiText(
                        "今日榜单",
                        Modifier.align(Alignment.CenterVertically),
                        size = 18,
                        weight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            Modifier.clip(RoundedCornerShape(10.dp))
                                .clickable(onClickLabel = "切换推荐平台") { choosePlatform() }
                                .heightIn(min = 44.dp)
                                .padding(horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            UiIcon(platform.icon, colors.primary, Modifier.size(15.dp))
                            UiText(
                                if (platform == MusicPlatform.NETEASE) "网易云" else "酷狗概念",
                                size = 12,
                                color = colors.primary,
                                maxLines = 1,
                            )
                            UiIcon(
                                R.drawable.ic_chevron_down,
                                colors.primary,
                                Modifier.size(12.dp),
                            )
                        }
                        UiText("·", size = 13, color = colors.onSurfaceVariantSummary)
                        TextAction("全部榜单") { act.openCharts(platform) }
                    }
                }
            }
            if (snapshot?.chart != null) {
                item(key = "chart") { FeaturedChart(snapshot) }
                item(key = "chart-preview-heading") {
                    MusicSectionTitle("榜单预览", if (expanded) "收起" else "展开十首") {
                        expanded = !expanded
                    }
                }
                items(snapshot.songs.take(if (expanded) 10 else 3), key = { "chart:${it.key}" }) {
                    track ->
                    PlatformSongRow(
                        track,
                        track.key == Player.get().current()?.key,
                        snapshot.songs.indexOf(track) + 1,
                        onClick = {
                            act.playChartTracks(
                                snapshot.chart,
                                snapshot.songs,
                                snapshot.songs.indexOf(track),
                            )
                        },
                        onMore = { act.showPlatformTrackActions(track) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
            if (!snapshot?.keywords.isNullOrEmpty())
                item(key = "hot-search") {
                    LazyRow(
                        Modifier.padding(vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(snapshot.keywords, key = { it }) { word ->
                            TextAction(word) { act.openMusicSearch(word, platform) }
                        }
                    }
                }
            if (recommendations.isNotEmpty()) {
                item(key = "recommendation-heading") {
                    MusicSectionTitle(snapshot?.recommendationLabel ?: "为你推荐", "换一批") { refresh++ }
                }
                items(recommendations, key = { "recommendation:${it.key}" }) { track ->
                    PlatformSongRow(
                        track,
                        track.key == Player.get().current()?.key,
                        onClick = {
                            act.playRecommendedTracks(
                                recommendations,
                                recommendations.indexOf(track),
                            )
                        },
                        onMore = { act.showPlatformTrackActions(track) },
                        modifier = Modifier.animateItem(),
                    )
                }
                item(key = "recommendation-footer") {
                    MusicFeedFooter(loadingMore, true, pagingProblem) { pagingProblem = null }
                }
            } else
                item(key = "empty") {
                    PageEmptyState(
                        R.drawable.ic_tab_music,
                        if (loading) "正在寻找好音乐" else "推荐暂时不可用",
                        if (loading) "正在读取${platform.label}的推荐…" else problem ?: "检查网络后再试。",
                        action = if (loading) null else "重新获取",
                        onClick = { refresh++ },
                    )
                }
            if (problem != null && recommendations.isNotEmpty())
                item(key = "problem") {
                    MusicFeedFooter(false, true, problem) { refresh++ }
                }
        }
    }

    @Composable
    private fun FeaturedChart(snapshot: DiscoveryHome) {
        val chart = snapshot.chart ?: return
        val colors = MiuixTheme.colorScheme
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(colors.tertiaryContainer)
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlatformImage(chart.cover, 68.dp)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                UiText(chart.name, size = 19, weight = FontWeight.SemiBold, maxLines = 2)
                UiText(
                    chart.description.ifBlank { platform.label },
                    size = 12,
                    color = colors.onSurfaceVariantSummary,
                    maxLines = 2,
                )
            }
            MiuixIconButton(
                R.drawable.ic_media_play,
                "播放${chart.name}",
                accent = true,
                diameter = 48.dp,
            ) {
                act.playChartTracks(chart, snapshot.songs, 0)
            }
        }
    }

    private fun choosePlatform() {
        val sheet = Sheet.of(act, if (section == 3) "心动音源" else "发现音乐").size(SheetSize.COMPACT)
        MusicPlatform.entries.forEach { source ->
            sheet.item(source.icon, source.label + if (source == platform) " · 当前" else "") {
                platform = source
                expanded = false
                resetScroll++
                act.getSharedPreferences(MainActivity.PREFS, 0)
                    .edit()
                    .putString("discoveryPlatform", source.id)
                    .apply()
            }
        }
        sheet.show()
    }

    @Composable
    private fun Library() {
        val colors = MiuixTheme.colorScheme
        val revision = AccountStore.revision
        val accounts = remember(revision) { AccountStore.accounts(act) }
        val local = library.filter { !it.isPlatform }
        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Column {
                MusicSectionTitle("本地收藏", "扫描音乐", scan)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HomeLibraryTile(
                        "本地歌曲",
                        "${local.size} 首",
                        R.drawable.ic_ent_songs,
                        Modifier.weight(1f),
                    ) {
                        act.openLocalCollection(false)
                    }
                    HomeLibraryTile(
                        "收藏歌单",
                        "${library.size} 首已收藏",
                        R.drawable.ic_ent_playlist,
                        Modifier.weight(1f),
                    ) {
                        act.openLocalCollection(true)
                    }
                }
            }
            Column {
                MusicSectionTitle("平台曲库")
                MusicPlatform.entries.forEach { source ->
                    val account = accounts.firstOrNull { it.platform == source }
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(onClickLabel = "打开${source.label}曲库") {
                                act.openPlatformMusic(source)
                            }
                            .padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PlatformMark(source, 42.dp)
                        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                            UiText(
                                source.label,
                                size = 15,
                                weight = FontWeight.Medium,
                                maxLines = 1,
                            )
                            UiText(
                                account?.nickname ?: "连接账号，同步歌单与喜欢",
                                size = 12,
                                color = colors.onSurfaceVariantSummary,
                                maxLines = 1,
                            )
                        }
                        if (account != null) {
                            MiuixIconButton(
                                R.drawable.ic_favorite_outline,
                                "${source.label}喜欢的歌曲",
                                diameter = 44.dp,
                            ) {
                                act.openPlatformMusic(source, favorites = true)
                            }
                        } else
                            UiIcon(
                                R.drawable.ic_nav_chevron,
                                colors.onSurfaceVariantActions,
                                Modifier.size(18.dp),
                            )
                    }
                }
            }
            if (local.isNotEmpty()) {
                Column {
                    MusicSectionTitle("新加入的音乐")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(local.takeLast(6).reversed(), key = { it.key }) { song ->
                            RecentMusicCard(song, Modifier.width(140.dp)) { playFile(song) }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun History() {
        val revision = ListeningHistory.revision
        val recent =
            remember(revision, this.recent) {
                (listOfNotNull(Player.get().current()?.takeIf { Player.get().isPlaying }) +
                        ListeningHistory.cachedRecentSongs() +
                        this.recent)
                    .distinctBy { it.key }
                    .take(20)
            }
        if (recent.isEmpty()) {
            PageEmptyState(
                R.drawable.ic_ent_recent,
                "从一首喜欢的歌开始",
                "听过的音乐会留在这里，随时回来继续听。",
                action = "打开曲库",
                onClick = {
                    section = 1
                    resetScroll++
                },
            )
            return
        }
        Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
            UiText("最近听过", size = 18, weight = FontWeight.SemiBold)
            recent.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    pair.forEach { song ->
                        RecentMusicCard(song, Modifier.weight(1f)) { playFile(song) }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun HomeLibraryTile(
    title: String,
    summary: String,
    icon: Int,
    modifier: Modifier,
    action: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surfaceContainer)
            .clickable(onClickLabel = title, onClick = action)
            .padding(18.dp)
    ) {
        UiIcon(icon, colors.primary, Modifier.size(26.dp))
        Spacer(Modifier.height(22.dp))
        UiText(title, size = 16, weight = FontWeight.Medium, maxLines = 2)
        Spacer(Modifier.height(4.dp))
        UiText(summary, size = 12, color = colors.onSurfaceVariantSummary, maxLines = 2)
    }
}

@Composable
private fun RecentMusicCard(song: Song, modifier: Modifier, action: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    val artwork by rememberSongArtwork(song, 320)
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClickLabel = "播放${song.title}", onClick = action)
    ) {
        Box(
            Modifier.fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(18.dp))
                .background(colors.tertiaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            val image = artwork
            if (image != null)
                Image(
                    image.asImageBitmap(),
                    null,
                    Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            else UiIcon(R.drawable.ic_tab_music, colors.primary, Modifier.size(30.dp))
        }
        Spacer(Modifier.height(10.dp))
        UiText(song.title, size = 14, weight = FontWeight.Medium, maxLines = 1)
        UiText(
            song.artist.ifBlank { "未知艺人" },
            size = 12,
            color = colors.onSurfaceVariantSummary,
            maxLines = 1,
        )
    }
}
