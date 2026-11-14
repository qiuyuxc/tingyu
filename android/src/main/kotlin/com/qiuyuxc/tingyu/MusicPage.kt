package com.qiuyuxc.tingyu

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Local collection routes share the existing discovery, account and online playback home. */
class MusicPage(private val act: MainActivity) {
    private val configuredRoot = Dirs.src(act)
    private val songs = mutableListOf<Song>()
    private var root: FrameLayout? = null
    private var home: MusicHomeView? = null
    private var route = "home"
    private var restored: Bundle? = null
    private var visible = true
    private var disposed = false
    private var generation = 0L
    private var loadedRevision = -1L
    private var loadJob: Job? = null
    private var scanJob: Job? = null
    private var query = ""
    private var sort = 0
    private var results: LinearLayout? = null
    private var scroll: ScrollView? = null
    private var savedScroll = 0

    fun matchesConfiguration(): Boolean = configuredRoot == Dirs.src(act)
    fun restoreState(state: Bundle?) {
        restored = state
        route = state?.getString("route")?.takeIf { it in setOf("home", "songs", "playlists", "playlist") } ?: "home"
        query = state?.getString("query").orEmpty()
        sort = state?.getInt("sort", 0)?.coerceIn(0, 2) ?: 0
        savedScroll = state?.getInt("scroll", 0) ?: 0
    }
    fun saveState(): Bundle = Bundle().apply {
        putString("route", route); putString("query", query); putInt("sort", sort)
        putInt("scroll", scroll?.scrollY ?: savedScroll)
        putBoolean("home.expanded", home?.top10Expanded ?: restored?.getBoolean("home.expanded", false) ?: false)
        putInt("home.section", home?.selectedSection ?: restored?.getInt("home.section", 0) ?: 0)
        putInt("home.index", home?.listIndex ?: restored?.getInt("home.index", 0) ?: 0)
        putInt("home.offset", home?.listOffset ?: restored?.getInt("home.offset", 0) ?: 0)
    }
    fun build(): View {
        root = FrameLayout(act)
        render()
        loadAsync()
        return requireNotNull(root)
    }
    fun show(): View {
        visible = true
        if (loadedRevision != Playlist.revision()) loadAsync() else invalidateContent()
        return root ?: build()
    }
    fun hide() { visible = false }
    fun dispose() { disposed = true; generation++; loadJob?.cancel(); scanJob?.cancel() }
    fun invalidateLibrary() { loadAsync() }
    fun invalidateContent() {
        if (!visible || disposed) return
        home?.update(songs, ListeningHistory.cachedRecentSongs())
        if (loadedRevision != Playlist.revision()) loadAsync()
    }
    fun goHome() {
        if (route != "home") { route = "home"; query = ""; savedScroll = 0; render() }
    }
    fun handleBack(): Boolean {
        if (route == "home") return false
        route = if (route == "playlist") "playlists" else "home"
        query = ""; savedScroll = 0; render()
        return true
    }
    fun openCollection(playlists: Boolean) {
        route = if (playlists) "playlists" else "songs"
        query = ""; savedScroll = 0; render()
    }
    fun playExternal(queue: List<Song>, index: Int) {
        if (index !in queue.indices) return
        Player.get().play(queue.map { it as Song? }.toMutableList(), index)
        act.openPlayer(null, null)
    }

    private fun loadAsync() {
        if (disposed) return
        val request = ++generation
        loadedRevision = Playlist.revision()
        loadJob?.cancel()
        loadJob = act.lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                Playlist.get(act).entries().filterNotNull().mapNotNull { entry ->
                    ensureActive()
                    val file = File(entry.path)
                    if (!file.isFile || !LocalMetadata.isAudio(file)) null
                    else Song(file).also { song ->
                        song.title = entry.title.ifBlank { song.title }
                        song.artist = entry.artist; song.album = entry.album
                        LocalMetadata.enrich(song)
                    }
                }
            }
            if (disposed || request != generation) return@launch
            songs.clear(); songs.addAll(loaded)
            if (visible) {
                if (route == "home") home?.update(songs, ListeningHistory.cachedRecentSongs())
                else render()
            }
        }
    }

    internal fun scanLibrary() {
        if (scanJob?.isActive == true) return
        if (!act.storageAvailable()) { act.requestMusicStorageAccess(); return }
        val directory = File(Dirs.src(act))
        if (!directory.isDirectory) { toast("音乐目录不存在，请在设置中选择目录"); return }
        toast("正在扫描音乐目录…")
        scanJob = act.lifecycleScope.launch {
            try {
                val added = withContext(Dispatchers.IO) {
                    var count = 0
                    directory.walkTopDown().maxDepth(20).filter { it.isFile && LocalMetadata.isAudio(it) }.forEach {
                        ensureActive()
                        val song = Song(it)
                        LocalMetadata.enrich(song)
                        if (Playlist.get(act).add(song)) count++
                    }
                    Playlist.syncM3u(act)
                    count
                }
                if (!disposed) { toast("新增 $added 首音乐"); loadAsync() }
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (_: Exception) { if (!disposed) toast("扫描失败，请检查目录访问权限") }
        }
    }

    private fun render() {
        val target = root ?: return
        if (disposed) return
        savedScroll = scroll?.scrollY ?: savedScroll
        scroll = null; results = null
        target.removeAllViews()
        if (route == "home") {
            val view = home ?: MusicHomeView(
                act,
                playFile = { song ->
                    val queue = songs.takeIf { it.any { entry -> entry.key == song.key } } ?: listOf(song)
                    playExternal(queue, queue.indexOfFirst { it.key == song.key }.coerceAtLeast(0))
                },
                scan = { act.scanLocalLibrary() },
                initialExpanded = restored?.getBoolean("home.expanded", false) ?: false,
                initialSection = restored?.getInt("home.section", 0) ?: 0,
                initialListIndex = restored?.getInt("home.index", 0) ?: 0,
                initialListOffset = restored?.getInt("home.offset", 0) ?: 0,
            ).also { home = it }
            (view.parent as? ViewGroup)?.removeView(view)
            view.update(songs, ListeningHistory.cachedRecentSongs())
            target.addView(view, FrameLayout.LayoutParams(-1, -1))
            return
        }
        val body = UI.column(act).apply {
            setPadding(UI.dp(UI.pageInset(act)), UI.dp(12), UI.dp(UI.pageInset(act)), UI.dp(UI.CONTENT_BOTTOM))
        }
        val viewport = ScrollView(act).also { scroll = it; it.addView(body) }
        val heading = TopBar(act)
        target.addView(viewport, FrameLayout.LayoutParams(-1, -1))
        target.addView(heading, TopBar.params(act))
        heading.attachScroll(viewport)
        val title = when (route) { "playlists" -> "收藏歌单"; "playlist" -> "默认歌单"; else -> "本地歌曲" }
        heading.set(title, true, { handleBack() }, null, null)
        heading.secondary(R.drawable.ic_action_more, "音乐库选项", { collectionOptions() })
        if (route == "playlists") {
            body.addView(MiuixPreferenceView(act, "默认歌单", "${songs.size} 首本地音乐", R.drawable.ic_ent_playlist) {
                route = "playlist"; render()
            })
        } else {
            val input = UI.input(act, "搜索歌名或艺人").apply { setText(query) }
            body.addView(input)
            val rows = UI.column(act)
            results = rows; body.addView(rows)
            input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    query = s.toString(); renderRows()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
            renderRows()
        }
        UI.restoreScroll(viewport, savedScroll)
    }

    private fun renderRows() {
        val rows = results ?: return
        rows.removeAllViews()
        val filtered = songs.filter { it.title.contains(query, true) || it.artist.contains(query, true) }
        val shown = when (sort) {
            1 -> filtered.sortedWith(compareBy<Song> { it.artist.lowercase() }.thenBy { it.title.lowercase() })
            2 -> filtered.sortedBy { it.title.lowercase() }
            else -> filtered
        }
        if (shown.isEmpty()) {
            rows.addView(UI.muted(act, if (query.isBlank()) "还没有本地音乐，扫描目录后会显示在这里。" else "没有匹配的歌曲"))
            if (query.isBlank()) rows.addView(MiuixActionView(act, "扫描音乐目录") { act.scanLocalLibrary() })
            return
        }
        shown.forEachIndexed { index, song ->
            val row = UI.row(act).apply { setPadding(0, UI.dp(12), 0, UI.dp(12)) }
            val cover = android.widget.ImageView(act).apply {
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                setImageResource(R.drawable.ic_tab_music)
                contentDescription = null
            }
            row.addView(cover, LinearLayout.LayoutParams(UI.dp(48), UI.dp(48)))
            val key = SongArtwork.key(song, UI.dp(48))
            cover.tag = key
            SongArtwork.load(act, song, UI.dp(48)) { bitmap ->
                if (!disposed && cover.tag == key && bitmap != null) cover.setImageBitmap(bitmap)
            }
            val labels = UI.column(act).apply { setPadding(UI.dp(12), 0, UI.dp(8), 0) }
            labels.addView(UI.strong(act, song.title))
            labels.addView(UI.muted(act, song.subtitle()))
            row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(MiuixIconView(act, R.drawable.ic_action_more, "${song.title}的选项") { songOptions(song) })
            row.setOnClickListener { playExternal(shown, index) }
            rows.addView(row)
        }
    }

    private fun collectionOptions() {
        Sheet.of(act, "音乐库选项").size(SheetSize.COMPACT)
            .item(R.drawable.ic_action_refresh, "扫描音乐目录") { act.scanLocalLibrary() }
            .item(R.drawable.ic_ent_playlist, "导出 M3U 歌单") {
                act.lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { Playlist.exportM3uFor(act) } }
                    toast(result.fold({ "已导出 $it 首音乐" }, { "导出失败，请检查目录权限" }))
                }
            }
            .item(R.drawable.ic_action_more, "按加入顺序排序") { sort = 0; renderRows() }
            .item(R.drawable.ic_action_more, "按艺人排序") { sort = 1; renderRows() }
            .item(R.drawable.ic_action_more, "按歌名排序") { sort = 2; renderRows() }
            .show()
    }

    private fun songOptions(song: Song) {
        Sheet.of(act, song.title).size(SheetSize.COMPACT)
            .content(UI.muted(act, listOf(song.subtitle(), song.durationText(), song.sizeText(), song.file?.absolutePath.orEmpty())
                .filter { it.isNotBlank() }.joinToString("\n")))
            .item(R.drawable.ic_media_play, "播放") { playExternal(listOf(song), 0) }
            .item(R.drawable.ic_nav_close, "从收藏移除") {
                act.lifecycleScope.launch {
                    withContext(Dispatchers.IO) { Playlist.get(act).remove(song.key); Playlist.syncM3u(act) }
                    loadAsync()
                }
            }.show()
    }
    private fun toast(message: String) { Toast.makeText(act, message, Toast.LENGTH_SHORT).show() }
}
