package com.qiuyuxc.tingyu

import android.os.Bundle
import android.widget.Toast
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** One source at a time; platform tracks never enter the local file playlist. */
internal class PlatformMusicPage(
    private val act: MainActivity,
    val platform: MusicPlatform,
    state: Bundle? = null,
    openFavorites: Boolean = false,
    openPlaylist: PlatformPlaylist? = null,
) : MusicRouteView(act), Player.Listener {
    private var selected by
        mutableStateOf(
            state?.getBundle("selected")?.let { PlatformState.playlist(it) } ?: openPlaylist
        )
    private val direct = state?.getBoolean("direct") ?: (openPlaylist != null)
    private var favoriteRequested = openFavorites
    private var previousUser =
        AccountStore.accounts(act).firstOrNull { it.platform == platform }?.userId
    private var listIndex = state?.getInt("list.index") ?: 0
    private var listOffset = state?.getInt("list.offset") ?: 0
    private val positions =
        HashMap<String, Pair<Int, Int>>().apply {
            state?.getBundle("positions")?.let { saved ->
                saved.keySet().forEach { key ->
                    saved
                        .getIntArray(key)
                        ?.takeIf { it.size == 2 }
                        ?.let { put(key, it[0] to it[1]) }
                }
            }
        }
    private var refreshRequest by mutableIntStateOf(0)
    private var playbackRevision by mutableIntStateOf(0)

    override fun handleBack(): Boolean {
        if (selected == null) return false
        if (direct) return false
        select(null)
        return true
    }

    private fun select(playlist: PlatformPlaylist?) {
        positions[selected?.id ?: "home"] = listIndex to listOffset
        selected = playlist
        val position = positions[playlist?.id ?: "home"] ?: (0 to 0)
        listIndex = position.first
        listOffset = position.second
    }

    override fun saveRoute() =
        Bundle().apply {
            putString("kind", "platform")
            putString("platform", platform.id)
            putBoolean("direct", direct)
            selected?.let { putBundle("selected", PlatformState.playlist(it)) }
            putInt("list.index", listIndex)
            putInt("list.offset", listOffset)
            putBundle(
                "positions",
                Bundle().apply {
                    positions.forEach { (key, value) ->
                        putIntArray(key, intArrayOf(value.first, value.second))
                    }
                },
            )
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
        val colors = MiuixTheme.colorScheme
        val accountRevision = AccountStore.revision
        val profile =
            remember(accountRevision) {
                AccountStore.accounts(act).firstOrNull { it.platform == platform }
            }
        val storeRevision = PlatformLibraryStore.revision
        val stored =
            remember(storeRevision, profile?.userId) {
                profile?.let { PlatformLibraryStore.get(it) }
            }
        var library by remember(profile?.userId) { mutableStateOf(stored) }
        var client by remember(profile?.userId) { mutableStateOf<PlatformLibraryClient?>(null) }
        var loading by remember { mutableStateOf(false) }
        var problem by remember { mutableStateOf<String?>(null) }
        var tracks by remember(selected?.id) { mutableStateOf<List<PlatformTrack>>(emptyList()) }
        var more by remember(selected?.id) { mutableStateOf(false) }
        var trackPage by remember(selected?.id) { mutableIntStateOf(0) }
        var tracksLoading by remember(selected?.id, profile?.userId) { mutableStateOf(false) }
        var pagingProblem by
            remember(selected?.id, profile?.userId) { mutableStateOf<String?>(null) }
        var favoriteBusy by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()
        val listState = rememberLazyListState(listIndex, listOffset)
        val selection = selected
        val directPlaylist = direct && selection?.favorites == false

        LaunchedEffect(profile?.userId) {
            if (previousUser != null && previousUser != profile?.userId) select(null)
            previousUser = profile?.userId
        }
        LaunchedEffect(selection?.id) { listState.scrollToItem(listIndex, listOffset) }
        LaunchedEffect(selection?.id, listState) {
            snapshotFlow {
                listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            }
                .collect { (index, offset) ->
                    listIndex = index
                    listOffset = offset
                }
        }

        LaunchedEffect(profile?.userId, profile?.connectedAt, refreshRequest) {
            client = null
            problem = null
            if (profile == null) {
                library = null
                return@LaunchedEffect
            }
            loading = true
            try {
                val authorization =
                    withContext(Dispatchers.IO) {
                        val account =
                            AccountStore.authorization(act, platform) ?: error("登录凭据已失效，请重新扫码")
                        require(account.profile.userId == profile.userId) {
                            "账号已变更，请重新打开曲库"
                        }
                        account
                    }
                kotlin.coroutines.coroutineContext.ensureActive()
                val previousLibrary = PlatformLibraryStore.get(profile)
                if (directPlaylist) {
                    // Read the requested playlist while another client synchronizes favorites.
                    client = PlatformLibraryClient(authorization.profile, authorization.session)
                }
                val result =
                    withContext(Dispatchers.IO) {
                        val source =
                            PlatformLibraryClient(authorization.profile, authorization.session)
                        source to source.loadLibrary()
                    }
                kotlin.coroutines.coroutineContext.ensureActive()
                if (!directPlaylist) client = result.first
                val latest = PlatformLibraryStore.get(profile)
                val loadedLibrary =
                    if (
                        directPlaylist &&
                            latest?.favoritesReady == true &&
                            latest.favorites !== previousLibrary?.favorites
                    )
                        latest
                    else result.second
                library = loadedLibrary
                PlatformLibraryStore.publish(profile, loadedLibrary)
                if (favoriteRequested) {
                    result.second.favoritePlaylist?.let {
                        select(it)
                        favoriteRequested = false
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                problem = error.message ?: "同步失败，请稍后重试"
            } finally {
                loading = false
            }
        }

        LaunchedEffect(storeRevision) { if (stored != null) library = stored }
        LaunchedEffect(profile?.userId, profile?.connectedAt) {
            val account = profile ?: return@LaunchedEffect
            val cached = AccountStore.membership(act, account)
            if (cached != null && System.currentTimeMillis() - cached.checkedAt < 15 * 60 * 1000)
                return@LaunchedEffect
            try {
                val member =
                    withContext(Dispatchers.IO) {
                        val authorization =
                            AccountStore.authorization(act, platform) ?: return@withContext null
                        PlatformLibraryClient(authorization.profile, authorization.session)
                            .membership()
                    }
                if (member != null) AccountStore.updateMembership(act, account, member)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {}
        }
        val selectedFavorites =
            if (
                selection?.favorites == true &&
                    (library?.favoritesReady == true || library?.favorites?.isNotEmpty() == true)
            )
                library?.favorites
            else null
        LaunchedEffect(client, selectedFavorites, selection?.id) {
            more = false
            trackPage = 0
            pagingProblem = null
            val current = selection ?: return@LaunchedEffect
            if (selectedFavorites != null) {
                tracks = selectedFavorites.values.toList()
                return@LaunchedEffect
            }
            val source = client ?: return@LaunchedEffect
            tracksLoading = true
            try {
                val result =
                    withContext(Dispatchers.IO) {
                        if (current.favorites) PlatformPage(source.favoriteTracks(current), false)
                        else source.tracks(current)
                    }
                kotlin.coroutines.coroutineContext.ensureActive()
                tracks = result.items
                more = result.more
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                problem = error.message ?: "歌曲读取失败，请刷新重试"
            } finally {
                tracksLoading = false
            }
        }

        LaunchedEffect(tracks.map { it.key }, selection?.id, refreshRequest) {
            val missing = tracks.filter { it.access == TrackAccess.UNKNOWN }.take(40)
            if (missing.isEmpty()) return@LaunchedEffect
            try {
                val details =
                    withContext(Dispatchers.IO) {
                            PlatformDiscoveryClient.forPlatform(act, platform).details(missing)
                        }
                        .associateBy { it.key }
                tracks = tracks.map { details[it.key] ?: it }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {}
        }

        val currentTrack = remember(playbackRevision) { Player.get().current()?.key }
        val reversed = remember(accountRevision) { AccountStore.reverseFavorites(act, platform) }
        val displayedTracks =
            remember(tracks, selection?.favorites, reversed) {
                if (selection?.favorites == true && reversed) tracks.asReversed() else tracks
            }

        fun play(index: Int) {
            if (displayedTracks.isEmpty()) return
            val reader = client
            val current = selection
            val source =
                if (more && reader != null && current != null)
                    PagedTrackSource(
                        "playlist:${platform.id}:${current.id}",
                        current.name,
                        platform,
                        trackPage + 1,
                    ) {
                        reader.tracks(current, it)
                    }
                else null
            act.playPlatformTracks(displayedTracks, index, source)
        }

        suspend fun loadMoreTracks() {
            val source = client ?: return
            val current = selection ?: return
            if (tracksLoading || !more || pagingProblem != null) return
            tracksLoading = true
            try {
                val result = withContext(Dispatchers.IO) { source.tracks(current, trackPage + 1) }
                kotlin.coroutines.coroutineContext.ensureActive()
                if (selected?.id != current.id || client !== source) return
                val merged = (tracks + result.items).distinctBy { it.key }
                require(merged.size > tracks.size || !result.more) { "歌单暂未返回下一页，请重试" }
                tracks = merged
                more = result.more
                trackPage++
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                pagingProblem = error.message ?: "加载失败，请重试"
            } finally {
                tracksLoading = false
            }
        }
        LoadWhenNearEnd(
            listState,
            selection != null &&
                client != null &&
                (!loading || directPlaylist) &&
                more &&
                pagingProblem == null,
            tracksLoading,
            "library:${platform.id}:${profile?.userId}:${selection?.id}:$refreshRequest",
        ) {
            loadMoreTracks()
        }

        fun favorite(track: PlatformTrack) {
            if (client == null) return
            val account = profile ?: return
            val currentLibrary = library ?: return
            if (favoriteBusy != null) return
            favoriteBusy = track.key
            scope.launch {
                try {
                    val result =
                        withContext(Dispatchers.IO) {
                            val authorization =
                                AccountStore.authorization(act, platform) ?: error("登录已失效，请重新扫码")
                            require(authorization.profile.userId == account.userId) {
                                "账号已变更，请重新打开曲库"
                            }
                            val source =
                                PlatformLibraryClient(authorization.profile, authorization.session)
                            source.setFavorite(
                                track,
                                track.key !in currentLibrary.favorites,
                                currentLibrary,
                            )
                            source.loadLibrary().also { synchronized ->
                                val target = track.key !in currentLibrary.favorites
                                require(
                                    synchronized.favoritesReady &&
                                        (track.key in synchronized.favorites) == target
                                ) {
                                    "平台尚未确认收藏状态，请刷新后重试"
                                }
                            }
                        }
                    kotlin.coroutines.coroutineContext.ensureActive()
                    if (
                        AccountStore.accounts(act)
                            .firstOrNull { it.platform == platform }
                            ?.userId == profile?.userId
                    ) {
                        library = result
                        profile?.let { PlatformLibraryStore.publish(it, result) }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Toast.makeText(act, error.message ?: "收藏同步失败", Toast.LENGTH_LONG).show()
                } finally {
                    favoriteBusy = null
                }
            }
        }

        Column(Modifier.fillMaxSize().background(colors.surface)) {
            PageHeader(
                title = selection?.name ?: platform.label,
                subtitle = if (selection == null) profile?.nickname else platform.label,
                back = { if (!handleBack()) act.closePlatformMusic() },
            ) {
                MiuixIconButton(R.drawable.ic_action_search, "搜索${platform.label}歌曲") {
                    act.openMusicSearch(platform = platform)
                }
                MiuixIconButton(R.drawable.ic_action_refresh, "同步歌单与收藏") {
                    if (!loading && favoriteBusy == null) refreshRequest++
                }
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding =
                    PaddingValues(
                        start = UI.pageInset(act).dp,
                        end = UI.pageInset(act).dp,
                        top = 14.dp,
                        bottom = UI.CONTENT_BOTTOM.dp + PageInsets.bottom,
                    ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (profile == null)
                    item {
                        StatusCard("连接${platform.label}", "同步喜欢的歌曲和歌单，在这里直接播放。") {
                            MiuixButton("扫码连接") { act.connectPlatform(platform) }
                        }
                    }
                if (loading || tracksLoading)
                    item {
                        UiText(
                            if (loading) "正在同步歌单与收藏…" else "正在读取歌曲…",
                            color = colors.onSurfaceVariantSummary,
                        )
                    }
                if (problem != null)
                    item {
                        StatusCard("暂时无法同步", problem.orEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                MiuixButton("重试", enabled = !loading) { refreshRequest++ }
                                MiuixButton("重新登录", accent = false) {
                                    act.connectPlatform(platform)
                                }
                            }
                        }
                    }
                library?.notices?.forEach { notice ->
                    item { MusicStatus("喜欢的歌曲待同步", notice, "重新获取") { refreshRequest++ } }
                }
                if (selection == null) {
                    if (profile != null)
                        item {
                            val member = AccountStore.membership(act, profile)?.displayAt()
                            Row(
                                Modifier.fillMaxWidth().padding(bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AccountAvatar(
                                    DisplayIdentity(profile.nickname, profile.avatar, platform),
                                    size = 40.dp,
                                )
                                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                    UiText(
                                        profile.nickname,
                                        size = 14,
                                        weight = FontWeight.Medium,
                                        maxLines = 1,
                                    )
                                    UiText(
                                        member?.let { "${it.tier} · ${it.expiryText()}" }
                                            ?: "会员状态待同步",
                                        size = 11,
                                        color = colors.onSurfaceVariantSummary,
                                        maxLines = 2,
                                    )
                                }
                                VipBadge(member)
                                MiuixIconButton(R.drawable.ic_quality, "调整音质", diameter = 44.dp) {
                                    act.showAudioQualityOptions(platform)
                                }
                            }
                        }
                    val snapshot = library
                    if (snapshot != null) {
                        snapshot.favoritePlaylist?.let { liked ->
                            item(key = "favorites") {
                                Card(
                                    Modifier.fillMaxWidth().clickable(onClickLabel = "打开喜欢的歌曲") {
                                        select(liked)
                                    },
                                    insideMargin = PaddingValues(22.dp),
                                    cornerRadius = 24.dp,
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        UiIcon(
                                            R.drawable.ic_favorite,
                                            colors.primary,
                                            Modifier.size(38.dp),
                                        )
                                        Column(Modifier.weight(1f).padding(start = 16.dp)) {
                                            UiText(
                                                if (platform == MusicPlatform.KUGOU) "我喜欢"
                                                else "喜欢的歌曲",
                                                size = 20,
                                                weight = FontWeight.SemiBold,
                                            )
                                            UiText(
                                                if (snapshot.favoritesReady)
                                                    "${snapshot.favorites.size} 首"
                                                else "尚未同步，请刷新重试",
                                                size = 12,
                                                color = colors.onSurfaceVariantSummary,
                                            )
                                        }
                                        UiIcon(
                                            R.drawable.ic_nav_chevron,
                                            colors.onSurfaceVariantActions,
                                        )
                                    }
                                }
                            }
                        }
                        if (platform == MusicPlatform.KUGOU && snapshot.favoritePlaylist == null)
                            item(key = "favorites-pending") {
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clickable(onClickLabel = "重新同步我喜欢") {
                                            favoriteRequested = true
                                            refreshRequest++
                                        }
                                        .padding(vertical = 18.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    UiIcon(
                                        R.drawable.ic_favorite,
                                        colors.primary,
                                        Modifier.size(38.dp),
                                    )
                                    Column(Modifier.weight(1f).padding(start = 16.dp)) {
                                        UiText("我喜欢", size = 20, weight = FontWeight.SemiBold)
                                        UiText(
                                            if (loading) "正在同步…" else "尚未同步，点按重新获取",
                                            size = 12,
                                            color = colors.onSurfaceVariantSummary,
                                        )
                                    }
                                    UiIcon(
                                        R.drawable.ic_action_refresh,
                                        colors.onSurfaceVariantActions,
                                    )
                                }
                            }
                        listOf(true to "创建的歌单", false to "收藏的歌单").forEach { (owned, title) ->
                            val playlists =
                                snapshot.playlists.filter { !it.favorites && it.owned == owned }
                            if (playlists.isNotEmpty()) {
                                item {
                                    UiText(
                                        title,
                                        Modifier.padding(top = 12.dp),
                                        size = 17,
                                        weight = FontWeight.SemiBold,
                                    )
                                }
                                items(playlists, key = { "${it.platform.id}:${it.id}" }) { playlist
                                    ->
                                    PlaylistRow(playlist) { select(playlist) }
                                }
                            }
                        }
                        if (snapshot.playlists.isEmpty() && !loading)
                            item {
                                UiText("这个账号还没有歌单。", color = colors.onSurfaceVariantSummary)
                            }
                    }
                } else {
                    item {
                        Card(
                            Modifier.fillMaxWidth(),
                            insideMargin = PaddingValues(20.dp),
                            cornerRadius = 24.dp,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PlatformImage(selection.cover, 72.dp)
                                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                                    UiText(
                                        selection.name,
                                        size = 21,
                                        weight = FontWeight.SemiBold,
                                        maxLines = 2,
                                    )
                                    UiText(
                                        "${maxOf(selection.count, tracks.size)} 首",
                                        size = 12,
                                        color = colors.onSurfaceVariantSummary,
                                    )
                                }
                            }
                        }
                    }
                    if (tracks.isNotEmpty())
                        item {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                UiText(
                                    "歌曲",
                                    Modifier.weight(1f),
                                    color = colors.onSurfaceVariantSummary,
                                )
                                MiuixButton(
                                    "播放全部",
                                    icon = R.drawable.ic_media_play,
                                ) {
                                    play(0)
                                }
                            }
                        }
                    if (selection.favorites)
                        item(key = "favorites-order") {
                            MiuixChoices(
                                listOf("正序", "倒序"),
                                if (reversed) 1 else 0,
                                onSelect = { index ->
                                    AccountStore.setReverseFavorites(act, platform, index == 1)
                                    scope.launch { listState.scrollToItem(0) }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    items(displayedTracks, key = { it.key }) { track ->
                        TrackRow(
                            track,
                            track.key == currentTrack,
                            library?.favorites?.containsKey(track.key) == true,
                            pending = favoriteBusy == track.key || favoritePending(track),
                            favoriteEnabled =
                                favoriteBusy == null &&
                                    !loading &&
                                    client != null &&
                                    library?.favoritesReady == true,
                            play = { play(displayedTracks.indexOf(track)) },
                            favorite = { favorite(track) },
                            more = { act.showPlatformTrackActions(track) },
                            modifier =
                                Modifier.animateItem(
                                    fadeInSpec = tween(160),
                                    placementSpec = tween(260),
                                    fadeOutSpec = tween(180),
                                ),
                        )
                    }
                    if (tracks.isEmpty() && !loading && !tracksLoading && problem == null)
                        item {
                            if (selection.favorites && library?.favoritesReady != true)
                                MusicStatus("喜欢的歌曲尚未同步", "刷新后继续读取平台歌单。", "重新同步") {
                                    refreshRequest++
                                }
                            else UiText("歌单里还没有歌曲。", color = colors.onSurfaceVariantSummary)
                        }
                    if (tracks.isNotEmpty())
                        item {
                            MusicFeedFooter(tracksLoading, more, pagingProblem) {
                                pagingProblem = null
                            }
                        }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(title: String, message: String, action: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(20.dp), cornerRadius = 24.dp) {
        UiText(title, size = 19, weight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        UiText(message, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Spacer(Modifier.height(16.dp))
        action()
    }
}

@Composable
private fun PlaylistRow(playlist: PlatformPlaylist, action: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClickLabel = "打开${playlist.name}", onClick = action)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlatformImage(playlist.cover)
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            UiText(playlist.name, size = 15, weight = FontWeight.Medium, maxLines = 2)
            UiText("${playlist.count} 首", size = 12, color = colors.onSurfaceVariantSummary)
        }
        UiIcon(R.drawable.ic_nav_chevron, colors.onSurfaceVariantActions)
    }
}

@Composable
private fun TrackRow(
    track: PlatformTrack,
    playing: Boolean,
    liked: Boolean,
    pending: Boolean,
    favoriteEnabled: Boolean,
    play: () -> Unit,
    favorite: () -> Unit,
    more: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClickLabel = "播放${track.title}", onClick = play)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlatformSongImage(track)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            UiText(
                track.title,
                color = if (playing) colors.primary else colors.onSurface,
                weight = FontWeight.Medium,
                maxLines = 2,
            )
            UiText(
                listOf(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" · "),
                size = 12,
                color = colors.onSurfaceVariantSummary,
                maxLines = 1,
            )
            Spacer(Modifier.height(4.dp))
            SourceBadge(track.platform, access = track.access)
        }
        MiuixIconButton(
            R.drawable.ic_action_more,
            "${track.title}的更多操作",
            diameter = 40.dp,
            onClick = more,
        )
        if (pending)
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp)
            }
        else
            MiuixIconButton(
                if (liked) R.drawable.ic_favorite else R.drawable.ic_favorite_outline,
                if (liked) "取消收藏${track.title}" else "收藏${track.title}",
                diameter = 44.dp,
                enabled = favoriteEnabled,
                onClick = favorite,
            )
    }
}
