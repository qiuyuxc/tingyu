package com.qiuyuxc.tingyu

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class SearchSource(
    val id: String,
    val label: String,
    val icon: Int,
    val platform: MusicPlatform? = null,
) {
    ALL("all", "全部", R.drawable.ic_action_search),
    LOCAL("local", "本地", R.drawable.ic_ent_songs),
    NETEASE("netease", "网易云", R.drawable.ic_platform_netease, MusicPlatform.NETEASE),
    KUGOU("kugou", "酷狗", R.drawable.ic_platform_kugou, MusicPlatform.KUGOU);

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: ALL
    }
}

/** Search and discovery use real platform data and retain the page that opened them. */
internal class MusicBrowsePage(private val act: MainActivity, state: Bundle) :
    MusicRouteView(act), Player.Listener {
    override val returnToPlayer = state.getBoolean("return.player", false)
    private val screen = state.getString("screen") ?: "search"
    override val showMiniPlayer: Boolean = screen != "comments"
    private var platform by
        mutableStateOf(MusicPlatform.fromId(state.getString("platform")) ?: MusicPlatform.NETEASE)
    private var source by mutableStateOf(SearchSource.fromId(state.getString("source")))
    private var query by mutableStateOf(state.getString("query").orEmpty())
    private var submitted by mutableStateOf(query.trim())
    private var chart by mutableStateOf(state.getBundle("chart")?.let { readChart(it) })
    private val track = state.getBundle("track")?.let { PlatformState.track(it) }
    private var chartParent = state.getBoolean("chart.parent", false)
    private var chartRootPosition =
        state.getInt("chart.root.index") to state.getInt("chart.root.offset")
    private var commentsHot by mutableStateOf(state.getBoolean("comments.hot", true))
    private var commentDraft by mutableStateOf(state.getString("comment.draft").orEmpty())
    private var commentEditorOpen by mutableStateOf(state.getBoolean("comment.editor"))
    private var commentReply by
        mutableStateOf(
            state.getBundle("comment.reply")?.let {
                PlatformComment(
                    it.getString("id").orEmpty(),
                    it.getString("author").orEmpty(),
                    it.getString("content").orEmpty(),
                    resourceId = it.getString("resource").orEmpty(),
                )
            }
        )
    private var lastSearch: String? = null
    private var refresh by mutableIntStateOf(0)
    private var playbackRevision by mutableIntStateOf(0)
    private var listIndex = state.getInt("list.index")
    private var listOffset = state.getInt("list.offset")
    private var restoredPosition: Pair<Int, Int>? =
        if (state.containsKey("list.index")) listIndex to listOffset else null

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

    override fun handleBack(): Boolean {
        if (screen == "comments" && commentEditorOpen) {
            commentEditorOpen = false
            return true
        }
        if (screen == "charts" && chart != null && chartParent) {
            chart = null
            listIndex = chartRootPosition.first
            listOffset = chartRootPosition.second
            restoredPosition = chartRootPosition
            return true
        }
        return false
    }

    override fun saveRoute() =
        Bundle().apply {
            putString("kind", "browse")
            putString("screen", screen)
            putString("platform", platform.id)
            putString("source", source.id)
            putString("query", query)
            chart?.let { putBundle("chart", chartState(it)) }
            track?.let { putBundle("track", PlatformState.track(it)) }
            putBoolean("chart.parent", chartParent)
            putInt("list.index", listIndex)
            putInt("list.offset", listOffset)
            putBoolean("return.player", returnToPlayer)
            putBoolean("comments.hot", commentsHot)
            putString("comment.draft", commentDraft)
            putBoolean("comment.editor", commentEditorOpen)
            commentReply?.let { reply ->
                putBundle(
                    "comment.reply",
                    Bundle().apply {
                        putString("id", reply.id)
                        putString("author", reply.author)
                        putString("content", reply.content)
                        putString("resource", reply.resourceId)
                    },
                )
            }
            putInt("chart.root.index", chartRootPosition.first)
            putInt("chart.root.offset", chartRootPosition.second)
        }

    @Composable
    override fun Render() {
        when (screen) {
            "charts" -> Charts()
            "comments" -> Comments()
            else -> Search()
        }
    }

    @Composable
    private fun Heading(title: String, subtitle: String = "", search: Boolean = false) {
        PageHeader(
            title = title,
            subtitle = subtitle.takeIf { it.isNotBlank() },
            back = { if (!handleBack()) act.closeMusicRoute() },
        ) {
            if (search)
                MiuixIconButton(R.drawable.ic_action_search, "搜索音乐") {
                    act.openMusicSearch(platform = platform)
                }
            MiuixIconButton(R.drawable.ic_action_refresh, "重新获取") { refresh++ }
        }
    }

    @Composable
    private fun rememberList(
        loading: Boolean,
        routeKey: String,
    ): androidx.compose.foundation.lazy.LazyListState {
        val list = rememberLazyListState(listIndex, listOffset)
        LaunchedEffect(list, loading, routeKey) {
            if (loading) return@LaunchedEffect
            restoredPosition?.let {
                list.scrollToItem(it.first, it.second)
                restoredPosition = null
            }
            snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
                .collect { (index, offset) ->
                    listIndex = index
                    listOffset = offset
                }
        }
        return list
    }

    private fun padding() =
        PaddingValues(
            start = UI.pageInset(act).dp,
            end = UI.pageInset(act).dp,
            top = 14.dp,
            bottom = UI.CONTENT_BOTTOM.dp + PageInsets.bottom,
        )

    @Composable
    private fun Search() {
        val colors = MiuixTheme.colorScheme
        var files by remember { mutableStateOf<List<Song>>(emptyList()) }
        var songs by remember { mutableStateOf<List<PlatformTrack>>(emptyList()) }
        var problems by remember { mutableStateOf<List<String>>(emptyList()) }
        var loading by remember { mutableStateOf(submitted.isNotBlank()) }
        var more by remember { mutableStateOf(false) }
        var page by remember { mutableIntStateOf(0) }
        var sourcePages by remember { mutableStateOf<Map<MusicPlatform, Int>>(emptyMap()) }
        var sourceMore by remember { mutableStateOf<Map<MusicPlatform, Boolean>>(emptyMap()) }
        var pagingProblem by remember { mutableStateOf<String?>(null) }
        var history by remember { mutableStateOf(searchHistory()) }
        var requestEpoch by remember { mutableIntStateOf(0) }
        val list = rememberList(loading, "search:${source.id}:$submitted")
        val current = remember(playbackRevision) { Player.get().current()?.key }
        LaunchedEffect(query) {
            delay(450)
            submitted = query.trim()
        }
        LaunchedEffect(submitted, source, refresh) {
            val epoch = ++requestEpoch
            val key = "${source.id}:$submitted"
            if (lastSearch != null && lastSearch != key) {
                listIndex = 0
                listOffset = 0
                restoredPosition = 0 to 0
            }
            lastSearch = key
            files = emptyList()
            songs = emptyList()
            problems = emptyList()
            more = false
            page = 0
            sourcePages = emptyMap()
            sourceMore = emptyMap()
            pagingProblem = null
            if (submitted.isBlank()) {
                loading = false
                return@LaunchedEffect
            }
            val phrase = submitted
            val selectedSource = source
            saveSearch(phrase)
            history = searchHistory()
            loading = true
            try {
                files = withContext(Dispatchers.IO) { fileSearch(phrase, selectedSource) }
                val sources =
                    if (selectedSource == SearchSource.ALL) MusicPlatform.entries.toList()
                    else listOfNotNull(selectedSource.platform)
                val responses =
                    withContext(Dispatchers.IO) {
                        coroutineScope {
                            sources
                                .map { provider ->
                                    async {
                                        try {
                                            Triple(
                                                provider,
                                                PlatformDiscoveryClient.forPlatform(act, provider)
                                                    .search(phrase),
                                                null as String?,
                                            )
                                        } catch (error: CancellationException) {
                                            throw error
                                        } catch (error: Exception) {
                                            Triple(
                                                provider,
                                                PlatformPage<PlatformTrack>(emptyList(), false),
                                                error.message ?: "读取失败",
                                            )
                                        }
                                    }
                                }
                                .map { it.await() }
                        }
                    }
                kotlin.coroutines.coroutineContext.ensureActive()
                songs = responses.flatMap { it.second.items }.distinctBy { it.key }
                sourcePages = responses.associate { it.first to 0 }
                sourceMore = responses.associate { it.first to it.second.more }
                more = sourceMore.values.any { it }
                problems = responses.mapNotNull {
                    it.third?.let { error -> "${it.first.label}：$error" }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (epoch == requestEpoch) problems = listOf(error.message ?: "搜索失败，请重试")
            } finally {
                if (epoch == requestEpoch) loading = false
            }
        }
        suspend fun loadMore() {
            if (loading || !more || pagingProblem != null) return
            val epoch = requestEpoch
            val phrase = submitted
            val selected = source
            val providers = sourceMore.filterValues { it }.keys
            loading = true
            try {
                val responses =
                    withContext(Dispatchers.IO) {
                        coroutineScope {
                            providers
                                .map { provider ->
                                    async {
                                        provider to
                                            PlatformDiscoveryClient.forPlatform(act, provider)
                                                .search(phrase, (sourcePages[provider] ?: 0) + 1)
                                    }
                                }
                                .map { it.await() }
                        }
                    }
                kotlin.coroutines.coroutineContext.ensureActive()
                if (epoch != requestEpoch || phrase != submitted || selected != source) return
                val additions = responses.flatMap { it.second.items }
                require(additions.isNotEmpty() || responses.none { it.second.more }) {
                    "搜索暂未返回下一页，请重试"
                }
                songs = (songs + additions).distinctBy { it.key }
                sourcePages =
                    sourcePages +
                        responses.associate { it.first to ((sourcePages[it.first] ?: 0) + 1) }
                sourceMore = sourceMore + responses.associate { it.first to it.second.more }
                more = sourceMore.values.any { it }
                page++
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (epoch == requestEpoch) pagingProblem = error.message ?: "加载失败，请重试"
            } finally {
                if (epoch == requestEpoch) loading = false
            }
        }
        LoadWhenNearEnd(
            list,
            more && pagingProblem == null,
            loading,
            "search:${source.id}:$submitted:$refresh",
        ) {
            loadMore()
        }
        Column(Modifier.fillMaxSize()) {
            Heading("搜索")
            Column(
                Modifier.padding(horizontal = UI.pageInset(act).dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MusicSearchField(query, { query = it }, { submitted = query.trim() })
                PageTabs(
                    SearchSource.entries.map { it.label },
                    source.ordinal,
                    { source = SearchSource.entries[it] },
                    Modifier.fillMaxWidth(),
                )
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = list,
                contentPadding = padding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (submitted.isBlank()) {
                    if (history.isNotEmpty()) {
                        item {
                            MusicSectionTitle("搜索历史", "清空") {
                                act.getSharedPreferences(MainActivity.PREFS, 0)
                                    .edit()
                                    .remove("search.history")
                                    .apply()
                                history = emptyList()
                            }
                        }
                        item {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(history, key = { it }) { word ->
                                    BrowseChip(word, 0, false) {
                                        query = word
                                        submitted = word
                                    }
                                }
                            }
                        }
                    }
                    val provider = source.platform ?: MusicPlatform.NETEASE
                    val hot = DiscoveryStore.get(provider)?.keywords.orEmpty()
                    if (hot.isNotEmpty()) {
                        item { MusicSectionTitle("${provider.label}热搜") }
                        items(hot, key = { "hot:$it" }) { word ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable(onClickLabel = "搜索$word") {
                                        query = word
                                        submitted = word
                                    }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                UiText(
                                    (hot.indexOf(word) + 1).toString(),
                                    Modifier.width(28.dp),
                                    size = 12,
                                    color = colors.primary,
                                )
                                UiText(word, Modifier.weight(1f), size = 14)
                                UiIcon(R.drawable.ic_nav_chevron, colors.onSurfaceVariantActions)
                            }
                        }
                    } else item { MusicStatus("找一首想听的歌", "输入歌曲或歌手，选择要搜索的音乐来源。") }
                } else {
                    if (source == SearchSource.LOCAL && !act.storageAvailable())
                        item {
                            MusicStatus("允许读取本地音乐", "开启文件权限后可以搜索本地曲库。", "允许访问") {
                                act.requestMusicStorageAccess()
                            }
                        }
                    if (loading)
                        item { UiText("正在搜索…", size = 12, color = colors.onSurfaceVariantSummary) }
                    if (files.isNotEmpty()) {
                        item {
                            MusicSectionTitle("本地曲库")
                        }
                        items(files, key = { "file:${it.key}" }) { song ->
                            FileSearchRow(song, song.key == current) {
                                act.playFileSongs(files, files.indexOf(song))
                            }
                        }
                    }
                    MusicPlatform.entries.forEach { provider ->
                        val found = songs.filter { it.platform == provider }
                        if (found.isNotEmpty()) {
                            item {
                                MusicSectionTitle(
                                    provider.label,
                                    if (source == SearchSource.ALL) "查看更多" else null,
                                    if (source == SearchSource.ALL)
                                        ({
                                            source =
                                                SearchSource.entries.first {
                                                    it.platform == provider
                                                }
                                        })
                                    else null,
                                )
                            }
                            items(found, key = { it.key }) { song ->
                                PlatformSongRow(
                                    song,
                                    song.key == current,
                                    onClick = {
                                        val phrase = submitted
                                        val reader =
                                            PlatformDiscoveryClient.forPlatform(
                                                act.applicationContext,
                                                provider,
                                            )
                                        val continuation =
                                            if (sourceMore[provider] == true)
                                                PagedTrackSource(
                                                    "search:${provider.id}:$phrase",
                                                    "搜索结果",
                                                    provider,
                                                    (sourcePages[provider] ?: 0) + 1,
                                                ) {
                                                    reader.search(phrase, it)
                                                }
                                            else null
                                        act.playPlatformTracks(
                                            found,
                                            found.indexOf(song),
                                            continuation,
                                        )
                                    },
                                    onMore = { act.showPlatformTrackActions(song) },
                                )
                            }
                        }
                    }
                    problems.forEach { error ->
                        item { MusicStatus("部分来源暂时不可用", error, "重试") { refresh++ } }
                    }
                    if (!loading && files.isEmpty() && songs.isEmpty() && problems.isEmpty())
                        item {
                            MusicStatus("没有找到匹配的音乐", "换个关键词，或切换音乐来源再试。")
                        }
                    if (songs.isNotEmpty())
                        item {
                            MusicFeedFooter(loading, more, pagingProblem) { pagingProblem = null }
                        }
                }
            }
        }
    }

    @Composable
    private fun Charts() {
        val colors = MiuixTheme.colorScheme
        var charts by
            remember(platform) { mutableStateOf(DiscoveryStore.get(platform)?.charts.orEmpty()) }
        var songs by remember(chart?.id) { mutableStateOf<List<PlatformTrack>>(emptyList()) }
        var loading by remember(chart?.id, platform) { mutableStateOf(true) }
        var problem by remember(chart?.id, platform) { mutableStateOf<String?>(null) }
        var more by remember(chart?.id) { mutableStateOf(false) }
        var page by remember(chart?.id) { mutableIntStateOf(0) }
        var pagingProblem by remember(chart?.id, platform) { mutableStateOf<String?>(null) }
        var client by remember(platform) { mutableStateOf<PlatformDiscoveryClient?>(null) }
        val selection = chart
        val list = rememberList(loading, "charts:${platform.id}:${selection?.id}")
        val current = remember(playbackRevision) { Player.get().current()?.key }
        LaunchedEffect(platform, selection?.id, refresh) {
            loading = true
            problem = null
            pagingProblem = null
            page = 0
            try {
                val result =
                    withContext(Dispatchers.IO) {
                        val source = PlatformDiscoveryClient.forPlatform(act, platform)
                        source to
                            if (selection == null) source.charts() else source.chartSongs(selection)
                    }
                client = result.first
                if (selection == null) {
                    @Suppress("UNCHECKED_CAST")
                    charts = result.second as List<PlatformChart>
                } else {
                    @Suppress("UNCHECKED_CAST")
                    val tracks = result.second as PlatformPage<PlatformTrack>
                    songs = tracks.items
                    more = tracks.more
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                problem = error.message ?: "榜单读取失败，请重试"
            } finally {
                loading = false
            }
        }
        suspend fun loadMore() {
            val reader = client ?: return
            val selected = selection ?: return
            if (loading || !more || pagingProblem != null) return
            loading = true
            try {
                val result = withContext(Dispatchers.IO) { reader.chartSongs(selected, page + 1) }
                kotlin.coroutines.coroutineContext.ensureActive()
                if (chart?.id != selected.id || platform != selected.platform || client !== reader)
                    return
                val merged = (songs + result.items).distinctBy { it.key }
                require(merged.size > songs.size || !result.more) { "榜单暂未返回下一页，请重试" }
                songs = merged
                more = result.more
                page++
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                pagingProblem = error.message ?: "加载失败，请重试"
            } finally {
                loading = false
            }
        }
        LoadWhenNearEnd(
            list,
            selection != null && more && problem == null && pagingProblem == null,
            loading,
            "charts:${platform.id}:${selection?.id}:$refresh",
        ) {
            loadMore()
        }
        Column(Modifier.fillMaxSize()) {
            Heading(selection?.name ?: "排行榜", platform.label, search = true)
            if (selection == null)
                Row(
                    Modifier.padding(horizontal = UI.pageInset(act).dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MusicPlatform.entries.forEach { provider ->
                        BrowseChip(provider.label, provider.icon, platform == provider) {
                            platform = provider
                        }
                    }
                }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = list,
                contentPadding = padding(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (loading)
                    item { UiText("正在读取榜单…", size = 12, color = colors.onSurfaceVariantSummary) }
                if (problem != null)
                    item { MusicStatus("暂时无法获取", problem.orEmpty(), "重试") { refresh++ } }
                if (selection == null)
                    items(charts, key = { it.id }) { item ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable(onClickLabel = "打开${item.name}") {
                                    chartRootPosition = listIndex to listOffset
                                    chartParent = true
                                    chart = item
                                    listIndex = 0
                                    listOffset = 0
                                    restoredPosition = 0 to 0
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PlatformImage(item.cover, 64.dp)
                            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                                UiText(
                                    item.name,
                                    size = 16,
                                    weight = FontWeight.SemiBold,
                                    maxLines = 2,
                                )
                                UiText(
                                    item.description.ifBlank { platform.label },
                                    size = 12,
                                    color = colors.onSurfaceVariantSummary,
                                    maxLines = 2,
                                )
                            }
                            UiIcon(R.drawable.ic_nav_chevron, colors.onSurfaceVariantActions)
                        }
                    }
                else {
                    item {
                        Card(
                            Modifier.fillMaxWidth(),
                            insideMargin = PaddingValues(20.dp),
                            cornerRadius = 24.dp,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PlatformImage(selection.cover, 86.dp)
                                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                                    UiText(
                                        selection.name,
                                        size = 22,
                                        weight = FontWeight.SemiBold,
                                        maxLines = 2,
                                    )
                                    UiText(
                                        selection.description,
                                        size = 12,
                                        color = colors.onSurfaceVariantSummary,
                                        maxLines = 3,
                                    )
                                }
                            }
                            Spacer(Modifier.height(17.dp))
                            MiuixButton(
                                "播放榜单",
                                Modifier.fillMaxWidth(),
                                icon = R.drawable.ic_media_play,
                                enabled = songs.isNotEmpty(),
                            ) {
                                act.playChartTracks(selection, songs, 0, page, more)
                            }
                        }
                    }
                    items(songs, key = { it.key }) { song ->
                        PlatformSongRow(
                            song,
                            song.key == current,
                            songs.indexOf(song) + 1,
                            onClick = {
                                act.playChartTracks(
                                    selection,
                                    songs,
                                    songs.indexOf(song),
                                    page,
                                    more,
                                )
                            },
                            onMore = { act.showPlatformTrackActions(song) },
                        )
                    }
                    if (songs.isNotEmpty())
                        item {
                            MusicFeedFooter(loading, more, pagingProblem) { pagingProblem = null }
                        }
                }
            }
        }
    }

    @Composable
    private fun Comments() {
        val song = track ?: return
        val colors = MiuixTheme.colorScheme
        val hot = commentsHot
        val accountRevision = AccountStore.revision
        val profile =
            remember(accountRevision) {
                AccountStore.accounts(act).firstOrNull { it.platform == song.platform }
            }
        val scope = rememberCoroutineScope()
        var sending by remember { mutableStateOf(false) }
        val liking = remember { mutableStateMapOf<String, Boolean>() }
        var result by remember(hot) { mutableStateOf(CommentPage(emptyList(), false)) }
        var loading by remember(hot) { mutableStateOf(true) }
        var problem by remember(hot) { mutableStateOf<String?>(null) }
        var page by remember(hot) { mutableIntStateOf(0) }
        var pagingProblem by remember(hot) { mutableStateOf<String?>(null) }
        var client by remember { mutableStateOf<PlatformDiscoveryClient?>(null) }
        val list = rememberList(loading, "comments:$hot")
        LaunchedEffect(song.key, hot, refresh, profile?.userId, profile?.connectedAt) {
            loading = true
            problem = null
            pagingProblem = null
            page = 0
            try {
                val response =
                    withContext(Dispatchers.IO) {
                        val source = PlatformDiscoveryClient.forPlatform(act, song.platform)
                        source to source.comments(song, hot = hot)
                    }
                client = response.first
                result = response.second
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                problem = error.message ?: "评论暂时不可用"
            } finally {
                loading = false
            }
        }
        suspend fun loadMore() {
            val reader = client ?: return
            if (loading || !result.more || pagingProblem != null) return
            loading = true
            try {
                val next =
                    withContext(Dispatchers.IO) {
                        reader.comments(song, page + 1, hot, result.cursor)
                    }
                kotlin.coroutines.coroutineContext.ensureActive()
                if (commentsHot != hot || client !== reader) return
                val merged = (result.items + next.items).distinctBy { it.id to it.time }
                require(merged.size > result.items.size || !next.more) { "评论暂未返回下一页，请重试" }
                result =
                    next.copy(items = merged, total = next.total.takeIf { it > 0 } ?: result.total)
                page++
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                pagingProblem = error.message ?: "加载失败，请重试"
            } finally {
                loading = false
            }
        }
        LoadWhenNearEnd(
            list,
            result.more && problem == null && pagingProblem == null,
            loading,
            "comments:${song.key}:$hot:$refresh",
        ) {
            loadMore()
        }

        fun sendComment() {
            val account = profile ?: return act.connectPlatform(song.platform)
            if (sending || commentDraft.isBlank()) return
            val draft = commentDraft
            val reply = commentReply
            sending = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val authorization =
                            AccountStore.authorization(act, song.platform) ?: error("请重新登录后发送")
                        require(authorization.profile.userId == account.userId) { "账号已变更，请确认后重新发送" }
                        PlatformDiscoveryClient(song.platform, authorization.session)
                            .postComment(song, draft, reply)
                    }
                    ensureActive()
                    if (
                        commentDraft == draft &&
                            commentReply == reply &&
                            AccountStore.accounts(act).any {
                                it.platform == song.platform &&
                                    it.userId == account.userId &&
                                    it.connectedAt == account.connectedAt
                            }
                    ) {
                        commentDraft = ""
                        commentReply = null
                        commentEditorOpen = false
                    }
                    commentsHot = false
                    refresh++
                    android.widget.Toast.makeText(act, "评论已提交", android.widget.Toast.LENGTH_SHORT)
                        .show()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    android.widget.Toast.makeText(
                            act,
                            error.message ?: "提交失败，输入内容已保留",
                            android.widget.Toast.LENGTH_LONG,
                        )
                        .show()
                } finally {
                    sending = false
                }
            }
        }

        fun like(comment: PlatformComment) {
            val account = profile ?: return act.connectPlatform(song.platform)
            if (comment.id in liking) return
            val target = comment.liked != true
            liking[comment.id] = target
            scope.launch {
                try {
                    val confirmed =
                        withContext(Dispatchers.IO) {
                            val authorization =
                                AccountStore.authorization(act, song.platform) ?: error("请重新登录后点赞")
                            require(authorization.profile.userId == account.userId) {
                                "账号已变更，请刷新评论"
                            }
                            PlatformDiscoveryClient(song.platform, authorization.session)
                                .likeComment(song, comment, target)
                        }
                    ensureActive()
                    if (
                        AccountStore.accounts(act).none {
                            it.platform == song.platform &&
                                it.userId == account.userId &&
                                it.connectedAt == account.connectedAt
                        }
                    )
                        return@launch
                    result =
                        result.copy(
                            items =
                                result.items.map { item ->
                                    if (item.id != comment.id) item
                                    else
                                        item.copy(
                                            liked = confirmed.liked,
                                            likes =
                                                confirmed.likes
                                                    ?: if (
                                                        item.liked != null &&
                                                            item.liked != confirmed.liked
                                                    )
                                                        (item.likes +
                                                                if (confirmed.liked) 1 else -1)
                                                            .coerceAtLeast(0)
                                                    else item.likes,
                                        )
                                }
                        )
                    if (comment.liked == null && confirmed.likes == null) refresh++
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    android.widget.Toast.makeText(
                            act,
                            error.message ?: "点赞失败，请重试",
                            android.widget.Toast.LENGTH_LONG,
                        )
                        .show()
                } finally {
                    liking.remove(comment.id)
                }
            }
        }
        Column(Modifier.fillMaxSize()) {
            Heading("评论", song.platform.label)
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = list,
                contentPadding = PaddingValues(horizontal = UI.pageInset(act).dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                item {
                    Card(
                        Modifier.fillMaxWidth()
                            .padding(bottom = 20.dp)
                            .border(
                                1.dp,
                                colors.onSurface.copy(alpha = 0.12f),
                                RoundedCornerShape(22.dp),
                            ),
                        insideMargin = PaddingValues(17.dp),
                        cornerRadius = 22.dp,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PlatformSongImage(song, 62.dp)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                UiText(
                                    song.title,
                                    size = 17,
                                    weight = FontWeight.SemiBold,
                                    maxLines = 2,
                                )
                                UiText(
                                    song.artist,
                                    size = 12,
                                    color = colors.onSurfaceVariantSummary,
                                    maxLines = 1,
                                )
                                SourceBadge(song.platform, access = song.access)
                            }
                            MiuixIconButton(
                                R.drawable.ic_media_play,
                                "播放${song.title}",
                                accent = true,
                                diameter = 44.dp,
                            ) {
                                act.playPlatformTracks(listOf(song), 0)
                            }
                        }
                    }
                }
                item {
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        UiText(
                            if (result.total > 0) "${result.total} 条评论" else "听友评论",
                            Modifier.fillMaxWidth(),
                            size = 16,
                            weight = FontWeight.Medium,
                            maxLines = 1,
                        )
                        if (song.platform == MusicPlatform.NETEASE)
                            PageTabs(
                                listOf("热门", "最新"),
                                if (hot) 0 else 1,
                                onSelect = {
                                    if (commentsHot != (it == 0)) {
                                        commentsHot = it == 0
                                        restoredPosition = 0 to 0
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                    }
                }
                if (loading)
                    item { UiText("正在读取评论…", size = 12, color = colors.onSurfaceVariantSummary) }
                if (problem != null)
                    item { MusicStatus("暂时无法读取", problem.orEmpty(), "重试") { refresh++ } }
                items(result.items, key = { "${it.id}:${it.time}" }) { comment ->
                    CommentRow(
                        comment,
                        comment.id in liking,
                        like = { like(comment) },
                        reply = {
                            commentReply = comment
                            commentEditorOpen = true
                        },
                    )
                }
                if (!loading && problem == null && result.items.isEmpty())
                    item { MusicStatus("还没有评论", "这首歌暂时没有可展示的评论。") }
                if (result.items.isNotEmpty())
                    item {
                        MusicFeedFooter(loading, result.more, pagingProblem) {
                            pagingProblem = null
                        }
                    }
            }
            CommentComposer(
                commentDraft,
                commentEditorOpen,
                profile?.nickname,
                commentReply,
                sending,
                change = { commentDraft = it.take(500) },
                expand = { commentEditorOpen = true },
                cancelReply = { commentReply = null },
                collapse = { commentEditorOpen = false },
                login = { act.connectPlatform(song.platform) },
                send = { sendComment() },
            )
        }
    }

    private fun fileSearch(phrase: String, selected: SearchSource): List<Song> {
        if (selected.platform != null) return emptyList()
        return Playlist.get(act)?.entries().orEmpty().filterNotNull().mapNotNull { entry ->
            val path = entry.path?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val file = File(path)
            if (!file.isFile || !LocalMetadata.isAudio(file)) return@mapNotNull null
            val title = entry.title.orEmpty().ifBlank { file.nameWithoutExtension }
            if (
                !listOf(title, entry.artist.orEmpty(), entry.album.orEmpty()).any {
                    it.contains(phrase, ignoreCase = true)
                }
            )
                return@mapNotNull null
            Song(file).apply {
                this.title = title
                artist = entry.artist.orEmpty()
                album = entry.album.orEmpty()
                LocalMetadata.enrich(this)
            }
        }
    }

    private fun searchHistory(): List<String> = runCatching {
        val array =
            JSONArray(
                act.getSharedPreferences(MainActivity.PREFS, 0).getString("search.history", "[]")
            )
        (0 until array.length())
            .map { array.getString(it) }
            .filter { it.isNotBlank() }
            .distinct()
            .take(10)
    }
        .getOrDefault(emptyList())

    private fun saveSearch(value: String) {
        act.getSharedPreferences(MainActivity.PREFS, 0)
            .edit()
            .putString(
                "search.history",
                JSONArray((listOf(value) + searchHistory()).distinct().take(10)).toString(),
            )
            .apply()
    }

    companion object {
        fun searchState(query: String, platform: MusicPlatform?) =
            Bundle().apply {
                putString("screen", "search")
                putString("query", query)
                putString("source", platform?.id ?: "all")
                putString("platform", platform?.id)
            }

        fun chartsState(platform: MusicPlatform, chart: PlatformChart?) =
            Bundle().apply {
                putString("screen", "charts")
                putString("platform", platform.id)
                chart?.let { putBundle("chart", chartState(it)) }
            }

        fun commentsState(track: PlatformTrack) =
            Bundle().apply {
                putString("screen", "comments")
                putString("platform", track.platform.id)
                putBundle("track", PlatformState.track(track))
            }

        private fun chartState(chart: PlatformChart) =
            Bundle().apply {
                putString("platform", chart.platform.id)
                putString("id", chart.id)
                putString("name", chart.name)
                putString("cover", chart.cover)
                putString("description", chart.description)
                putLong("cycle", chart.cycleId)
            }

        private fun readChart(state: Bundle): PlatformChart? =
            MusicPlatform.fromId(state.getString("platform"))?.let {
                PlatformChart(
                    it,
                    state.getString("id").orEmpty(),
                    state.getString("name").orEmpty(),
                    state.getString("cover").orEmpty(),
                    state.getString("description").orEmpty(),
                    state.getLong("cycle"),
                )
            }
    }
}

@Composable
private fun BrowseChip(label: String, icon: Int, selected: Boolean, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Row(
        Modifier.clip(RoundedCornerShape(12.dp))
            .background(if (selected) colors.tertiaryContainer else colors.surfaceContainer)
            .clickable(onClickLabel = label, onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != 0)
            UiIcon(
                icon,
                if (selected) colors.primary else colors.onSurfaceVariantSummary,
                Modifier.size(16.dp),
            )
        UiText(
            label,
            size = 12,
            maxLines = 1,
            color = if (selected) colors.primary else colors.onSurfaceSecondary,
        )
    }
}

@Composable
private fun FileSearchRow(song: Song, playing: Boolean, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    val artwork by rememberSongArtwork(song, 160)
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .clickable(onClickLabel = "播放${song.title}", onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(50.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.tertiaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            val image = artwork
            if (image != null)
                androidx.compose.foundation.Image(
                    image.asImageBitmap(),
                    null,
                    Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
            else UiIcon(R.drawable.ic_tab_music, colors.primary)
        }
        Column(
            Modifier.weight(1f).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            UiText(
                song.title,
                size = 14,
                weight = FontWeight.Medium,
                color = if (playing) colors.primary else colors.onSurface,
                maxLines = 2,
            )
            UiText(
                song.artist.ifBlank { "未知艺人" },
                size = 12,
                color = colors.onSurfaceVariantSummary,
                maxLines = 1,
            )
            SourceBadge()
        }
        UiIcon(R.drawable.ic_media_play, colors.onSurfaceVariantSummary)
    }
}

@Composable
private fun CommentRow(
    comment: PlatformComment,
    busy: Boolean,
    like: () -> Unit,
    reply: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    var expanded by remember(comment.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PlatformImage(comment.avatar, 38.dp)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    UiText(comment.author, size = 14, weight = FontWeight.SemiBold, maxLines = 1)
                    if (comment.time > 0)
                        UiText(
                            SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(comment.time)),
                            size = 12,
                            color = colors.onSurfaceVariantSummary,
                            maxLines = 1,
                        )
                }
                Row(
                    Modifier.clip(RoundedCornerShape(12.dp))
                        .background(
                            if (comment.liked == true) colors.tertiaryContainer
                            else colors.surfaceContainer
                        )
                        .border(
                            1.dp,
                            colors.onSurface.copy(alpha = 0.12f),
                            RoundedCornerShape(12.dp),
                        )
                        .clickable(
                            enabled = !busy,
                            onClickLabel = if (comment.liked == true) "取消点赞" else "点赞",
                            onClick = like,
                        )
                        .heightIn(min = 44.dp)
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    UiIcon(
                        if (comment.liked == true) R.drawable.ic_favorite
                        else R.drawable.ic_favorite_outline,
                        if (comment.liked == true) colors.primary
                        else colors.onSurfaceVariantSummary,
                        Modifier.size(14.dp),
                    )
                    UiText(
                        if (busy) "…" else comment.likes.toString(),
                        Modifier.widthIn(max = 88.dp),
                        size = 11,
                        color =
                            if (comment.liked == true) colors.primary
                            else colors.onSurfaceVariantSummary,
                        maxLines = 1,
                    )
                }
            }
            UiText(
                comment.content,
                Modifier.padding(start = 48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClickLabel = "回复 ${comment.author}", onClick = reply)
                    .padding(vertical = 10.dp),
                size = 15,
                maxLines = if (expanded) Int.MAX_VALUE else 8,
            )
            if (comment.content.length > 200 && !expanded)
                UiText(
                    "展开",
                    Modifier.padding(start = 48.dp)
                        .clickable(onClickLabel = "展开完整评论") { expanded = true }
                        .padding(vertical = 8.dp),
                    size = 12,
                    color = colors.primary,
                )
        }
        Box(
            Modifier.fillMaxWidth()
                .padding(start = 48.dp)
                .height(1.dp)
                .background(colors.onSurface.copy(alpha = 0.12f))
        )
    }
}

@Composable
private fun CommentComposer(
    draft: String,
    expanded: Boolean,
    nickname: String?,
    reply: PlatformComment?,
    sending: Boolean,
    change: (String) -> Unit,
    expand: () -> Unit,
    cancelReply: () -> Unit,
    collapse: () -> Unit,
    login: () -> Unit,
    send: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    BackHandler(expanded) {
        keyboard?.hide()
        collapse()
    }
    LaunchedEffect(expanded, reply?.id) {
        if (expanded) {
            focus.requestFocus()
            keyboard?.show()
        } else keyboard?.hide()
    }
    Column(Modifier.fillMaxWidth().background(colors.surfaceContainer)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.onSurface.copy(alpha = 0.18f)))
        Column(
            Modifier.fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(top = 12.dp, bottom = PageInsets.bottom + 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!expanded) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        Modifier.weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(colors.surface)
                            .border(
                                1.dp,
                                colors.onSurface.copy(alpha = 0.24f),
                                RoundedCornerShape(14.dp),
                            )
                            .clickable(onClickLabel = "写评论", role = Role.Button, onClick = expand)
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        UiIcon(
                            R.drawable.ic_edit,
                            colors.onSurfaceVariantSummary,
                            Modifier.size(18.dp),
                        )
                        UiText(
                            draft.ifBlank { reply?.let { "回复 ${it.author}…" } ?: "写评论…" },
                            Modifier.weight(1f),
                            color =
                                if (draft.isBlank()) colors.onSurfaceVariantSummary
                                else colors.onSurface,
                            maxLines = 1,
                        )
                    }
                    MiuixButton(
                        if (nickname == null) "登录" else if (sending) "发送中…" else "发送",
                        enabled = nickname == null || (draft.isNotBlank() && !sending),
                    ) {
                        if (nickname == null) login() else send()
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    UiText(
                        reply?.let { "回复 ${it.author}" }
                            ?: nickname?.let { "以 $it 发布" }
                            ?: "登录后发表评论",
                        Modifier.weight(1f),
                        size = 12,
                        color = colors.onSurface,
                        weight = FontWeight.Medium,
                        maxLines = 1,
                    )
                    if (reply != null) TextAction("取消回复", action = cancelReply)
                    TextAction("收起") {
                        keyboard?.hide()
                        collapse()
                    }
                }
                if (reply != null)
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.secondaryContainer)
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(Modifier.width(3.dp).height(36.dp).background(colors.primary))
                        UiText(
                            reply.content,
                            size = 12,
                            color = colors.onSurfaceVariantSummary,
                            maxLines = 2,
                        )
                    }
                BasicTextField(
                    draft,
                    change,
                    enabled = !sending,
                    modifier =
                        Modifier.fillMaxWidth()
                            .heightIn(min = 76.dp, max = 140.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(colors.surface)
                            .border(
                                1.dp,
                                colors.primary.copy(alpha = 0.55f),
                                RoundedCornerShape(14.dp),
                            )
                            .padding(12.dp)
                            .focusRequester(focus)
                            .semantics { contentDescription = "评论内容，最多 500 字" },
                    textStyle =
                        TextStyle(color = colors.onSurface, fontSize = 15.sp, lineHeight = 22.sp),
                    cursorBrush = SolidColor(colors.primary),
                    decorationBox = { input ->
                        Box {
                            if (draft.isEmpty())
                                UiText("说说你对这首歌的感受…", color = colors.onSurfaceVariantSummary)
                            input()
                        }
                    },
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    UiText(
                        "${draft.length}/500",
                        Modifier.weight(1f),
                        size = 11,
                        color = colors.onSurfaceVariantSummary,
                    )
                    if (nickname == null) MiuixButton("登录", onClick = login)
                    else
                        MiuixButton(
                            if (sending) "提交中…" else "发送",
                            enabled = draft.isNotBlank() && !sending,
                        ) {
                            keyboard?.hide()
                            send()
                        }
                }
            }
        }
    }
}
