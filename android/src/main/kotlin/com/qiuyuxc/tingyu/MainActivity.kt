package com.qiuyuxc.tingyu

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.qiuyuxc.tingyu.navigation.LiquidGlassNavigationBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** MIUIX owns the app theme and navigation; the Kotlin pages retain their work. */
class MainActivity : ComponentActivity(), Player.Listener {
    private lateinit var host: FrameLayout
    private lateinit var player: PlayerSheet
    private val musicPages = arrayOfNulls<MusicPage>(1)
    private val libraryStates = arrayOfNulls<Bundle>(1)
    private val tabViews = arrayOfNulls<View>(2)
    private val musicPage: MusicPage?
        get() = musicPages.getOrNull(tab)

    private var settingsPage: SettingsPage? = null
    private var tab by mutableIntStateOf(0)
    internal val currentTab: Int
        get() = tab

    private var playbackEpoch by mutableIntStateOf(0)
    private var playerOpen by mutableStateOf(false)
    private var heartPageVisible by mutableStateOf(false)
    private var routeShowsMiniPlayer by mutableStateOf(true)
    private var miniCollapsed by mutableStateOf(false)
    internal val fullPlayerVisible: Boolean
        get() = playerOpen

    internal fun setHeartPageVisible(visible: Boolean) {
        heartPageVisible = visible
    }

    private var accountLogin by mutableStateOf<MusicPlatform?>(null)
    private val musicRoutes = ArrayList<MusicRouteView>()
    private var musicRouteOrigin = 0
    private var pendingPlatformMusic: MusicPlatform? = null
    private var pendingPlatformPlayback: Triple<List<PlatformTrack>, Int, PlatformTrackSource?>? =
        null
    private var pendingPlatformFavorite: Pair<PlatformTrack, Boolean>? = null
    private var reportedPlaybackError: String? = null
    private var storageGranted = false
    private var storageAction: String? = null
    private var exitHintAt = 0L
    private var exitHint: Toast? = null
    private var fileUpload: UploadDialog? = null
    private val cloudFilePicker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) uploadDialog().documents(uris)
        }
    private val avatarPicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null)
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            AccountStore.setAvatar(applicationContext, uri)
                        }
                    } catch (_: Exception) {
                        android.widget.Toast.makeText(
                                this@MainActivity,
                                "无法读取这张图片，请重新选择",
                                android.widget.Toast.LENGTH_SHORT,
                            )
                            .show()
                    }
                }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        Theme.init(this)
        setTheme(Theme.resourceStyle())
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle =
                if (Theme.isDark) SystemBarStyle.dark(0) else SystemBarStyle.light(0, 0),
            navigationBarStyle =
                if (Theme.isDark) SystemBarStyle.dark(0) else SystemBarStyle.light(0, 0),
        )
        Player.init(this)
        miniCollapsed =
            savedInstanceState?.getBoolean("mini.collapsed")
                ?: getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("mini.collapsed", false)
        PlatformArtwork.init(applicationContext)
        host = FrameLayout(this)
        player = PlayerSheet(this)
        tab =
            (savedInstanceState?.getInt("tab")
                    ?: getSharedPreferences(PREFS, MODE_PRIVATE).getInt("tab", 0))
                .coerceIn(0, 1)
        storageGranted = hasStorageAccess()
        storageAction = savedInstanceState?.getString("storage.action")
        libraryStates.indices.forEach {
            libraryStates[it] = savedInstanceState?.getBundle("library.$it")
        }
        if (savedInstanceState?.containsKey("settings.page") == true) {
            settingsPage = SettingsPage(this).apply { restoreState(savedInstanceState) }
        }
        Player.get().addListener(this)
        showTab(tab)
        if (savedInstanceState != null) {
            val restoredOrigin = savedInstanceState.getInt("music.origin", tab)
            @Suppress("DEPRECATION")
            savedInstanceState.getParcelableArrayList<Bundle>("music.routes")?.forEach { state ->
                when (state.getString("kind")) {
                    "platform" ->
                        MusicPlatform.fromId(state.getString("platform"))?.let {
                            openMusicRoute(PlatformMusicPage(this, it, state))
                        }
                    "browse" -> openMusicRoute(MusicBrowsePage(this, state))
                    "personal" -> openMusicRoute(PersonalMusicPage(this, state))
                    "cloud" -> openMusicRoute(CloudFilesPage(this, state))
                }
            }
            if (musicRoutes.isNotEmpty()) musicRouteOrigin = restoredOrigin
        }
        setContent { AppMiuixTheme { AppScene() } }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val previousExitHintAt = exitHintAt
                    clearExitHint()
                    if (player.closeQueueIfOpen()) return
                    if (playerOpen) {
                        player.close()
                        return
                    }
                    musicRoutes.lastOrNull()?.let { page ->
                        if (!page.handleBack()) closeMusicRoute()
                        return
                    }
                    if (tab == 1 && settingsPage?.handleBack() == true) return
                    if (tab == 0) {
                        if (musicPage?.handleBack() == true) return
                    }
                    if (tab != 0) {
                        selectNavigation(0)
                        return
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (previousExitHintAt == 0L || now - previousExitHintAt > 2_000L) {
                        exitHintAt = now
                        exitHint =
                            Toast.makeText(
                                    this@MainActivity,
                                    "再滑一次退出应用",
                                    Toast.LENGTH_SHORT,
                                )
                                .also { it.show() }
                        return
                    }
                    isEnabled = false
                    try {
                        onBackPressedDispatcher.onBackPressed()
                    } finally {
                        isEnabled = true
                    }
                }
            },
        )
        if (
            Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
    }

    @Composable
    private fun AppScene() {
        if (Build.VERSION.SDK_INT >= 33) GlassAppScene()
        else
            AppLayers { modifier ->
                FloatingNavigation(NAVIGATION, tab, ::selectNavigation, modifier)
            }
    }

    @RequiresApi(33)
    @Composable
    private fun GlassAppScene() {
        val surface = MiuixTheme.colorScheme.surface
        val backdrop = rememberLayerBackdrop {
            drawRect(surface)
            drawContent()
        }
        AppLayers(pageModifier = Modifier.layerBackdrop(backdrop)) { modifier ->
            LiquidGlassNavigationBar(
                items = NAVIGATION,
                selectedIndex = tab,
                onItemClick = ::selectNavigation,
                backdrop = backdrop,
                isBlurActive = true,
                modifier = modifier,
            )
        }
    }

    @Composable
    private fun AppLayers(
        pageModifier: Modifier = Modifier,
        navigation: @Composable (Modifier) -> Unit,
    ) {
        val colors = MiuixTheme.colorScheme
        val density = LocalDensity.current
        val keyboardOpen = WindowInsets.ime.getBottom(density) > 0
        var dockHeight by remember { mutableIntStateOf(0) }
        val dockInset = if (keyboardOpen) 0.dp else with(density) { dockHeight.toDp() }
        SideEffect { PageInsets.bottom = dockInset }
        Box(Modifier.fillMaxSize().background(colors.surface)) {
            AndroidView(
                factory = { host },
                update = { applyPageInsets(it, with(density) { dockInset.roundToPx() }) },
                modifier =
                    Modifier.align(Alignment.TopCenter)
                        .widthIn(max = 760.dp)
                        .fillMaxSize()
                        .then(pageModifier)
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(
                                WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                            )
                        )
                        .imePadding()
                        .background(colors.surface),
            )
            val revision = playbackEpoch
            val song = remember(revision) { Player.get().current() }
            PlaybackDock(
                song = song,
                playing = Player.get().isPlaying,
                revision = revision,
                showMini =
                    song != null &&
                        !Player.get().isDismissed &&
                        !heartPageVisible &&
                        routeShowsMiniPlayer,
                visible = !playerOpen && !keyboardOpen,
                collapsed = miniCollapsed,
                collapse = ::updateMiniCollapsed,
                open = { openPlayer(null, null) },
                onInset = { dockHeight = it },
                modifier =
                    Modifier.align(Alignment.BottomCenter).widthIn(max = 560.dp).fillMaxWidth(),
                navigation = navigation,
            )
            AndroidView(factory = { player }, modifier = Modifier.fillMaxSize())
            accountLogin?.let { platform ->
                PlatformLoginDialog(
                    platform,
                    dismiss = {
                        accountLogin = null
                        pendingPlatformMusic = null
                        pendingPlatformPlayback = null
                        pendingPlatformFavorite = null
                    },
                ) { account ->
                    AccountStore.connect(this@MainActivity, account.profile, account.session)
                    accountLogin = null
                    pendingPlatformMusic?.let { source ->
                        pendingPlatformMusic = null
                        openPlatformMusic(source)
                    }
                    pendingPlatformPlayback?.let { (songs, index, source) ->
                        pendingPlatformPlayback = null
                        playPlatformTracks(songs, index, source)
                    }
                    pendingPlatformFavorite?.let { (track, liked) ->
                        pendingPlatformFavorite = null
                        togglePlatformFavorite(track, desired = liked)
                    }
                }
            }
        }
    }

    private fun applyPageInsets(view: View, bottom: Int) {
        if (view is PlayerSheet) return
        if (view is ScrollView) {
            val body = view.getChildAt(0) as? ViewGroup
            val composeHome =
                body != null &&
                    (0 until body.childCount).any {
                        body.getChildAt(it) is MusicHomeView
                    }
            val inset = if (composeHome) 0 else bottom
            if (view.paddingBottom != inset) {
                view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, inset)
            }
            view.clipToPadding = false
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) applyPageInsets(view.getChildAt(index), bottom)
        }
    }

    private fun updateMiniCollapsed(collapsed: Boolean) {
        miniCollapsed = collapsed
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean("mini.collapsed", collapsed)
            .apply()
    }

    internal fun showTab(index: Int) {
        clearMusicRoutes()
        showTab(index, force = false)
    }

    private fun selectNavigation(index: Int) {
        clearMusicRoutes()
        settingsPage?.goHome()
        musicPages.getOrNull(index)?.goHome()
        showTab(index, force = false)
    }

    internal fun openAccountCentre() {
        settingsPage?.goHome()
        showTab(1)
    }

    internal fun connectPlatform(platform: MusicPlatform) {
        accountLogin = platform
    }

    internal fun deferFavoriteLogin(track: PlatformTrack, liked: Boolean) {
        pendingPlatformFavorite = track to liked
        connectPlatform(track.platform)
    }

    internal fun openPlatformMusic(platform: MusicPlatform, favorites: Boolean = false) {
        if (AccountStore.accounts(this).none { it.platform == platform }) {
            pendingPlatformMusic = platform
            connectPlatform(platform)
            return
        }
        openMusicRoute(PlatformMusicPage(this, platform, openFavorites = favorites))
    }

    internal fun closePlatformMusic() {
        closeMusicRoute()
    }

    internal fun openPlatformPlaylist(playlist: PlatformPlaylist) {
        if (AccountStore.accounts(this).none { it.platform == playlist.platform }) {
            openSettings("accounts")
            return
        }
        openMusicRoute(PlatformMusicPage(this, playlist.platform, openPlaylist = playlist))
    }

    internal fun openPersonalMusic(ranking: Boolean) {
        openMusicRoute(PersonalMusicPage(this, Bundle().apply { putBoolean("ranking", ranking) }))
    }

    internal fun playSavedSongs(songs: List<Song>, index: Int) {
        val song = songs.getOrNull(index) ?: return
        val source = song.platformTrack?.platform
        if (source != null && AccountStore.accounts(this).none { it.platform == source }) {
            openSettings("accounts")
            return
        }
        val page = musicPages[0] ?: MusicPage(this).also { musicPages[0] = it }
        page.playExternal(songs, index)
    }

    internal fun openMusicSearch(query: String = "", platform: MusicPlatform? = null) {
        openMusicRoute(MusicBrowsePage(this, MusicBrowsePage.searchState(query, platform)))
    }

    internal fun openCharts(platform: MusicPlatform, chart: PlatformChart? = null) {
        openMusicRoute(MusicBrowsePage(this, MusicBrowsePage.chartsState(platform, chart)))
    }

    internal fun openComments(track: PlatformTrack) {
        val state =
            MusicBrowsePage.commentsState(track).apply { putBoolean("return.player", playerOpen) }
        if (playerOpen) player.close(animated = false)
        openMusicRoute(MusicBrowsePage(this, state))
    }

    internal fun playPlatformTracks(
        tracks: List<PlatformTrack>,
        index: Int,
        source: PlatformTrackSource? = null,
    ) {
        val track = tracks.getOrNull(index) ?: return
        if (AccountStore.accounts(this).none { it.platform == track.platform }) {
            pendingPlatformPlayback = Triple(tracks, index, source)
            connectPlatform(track.platform)
            return
        }
        Player.get().playPlatform(tracks, index, source)
        openPlayer(null, null)
    }

    internal fun playRecommendedTracks(tracks: List<PlatformTrack>, index: Int) {
        val platform = tracks.getOrNull(index)?.platform ?: return
        val context = applicationContext
        playPlatformTracks(
            tracks,
            index,
            RecommendationTrackSource(platform) {
                PlatformDiscoveryClient.forPlatform(context, platform)
            },
        )
    }

    internal fun playHeartTracks(tracks: List<PlatformTrack>, request: Long, pauseRevision: Long) {
        val source = tracks.firstOrNull()?.platform ?: return
        if (!Player.get().isQueueRequestCurrent(request)) return
        Player.get()
            .playPlatform(
                tracks,
                0,
                RecommendationTrackSource(source, heart = true) {
                    PlatformDiscoveryClient.forPlatform(applicationContext, source)
                },
            )
        Player.get().setMode(Player.MODE_SHUFFLE)
        if (Player.get().pauseRevision != pauseRevision) Player.get().pause()
    }

    internal fun playChartTracks(
        chart: PlatformChart,
        tracks: List<PlatformTrack>,
        index: Int,
        loadedPage: Int = 0,
        more: Boolean = true,
    ) {
        val reader = PlatformDiscoveryClient.forPlatform(applicationContext, chart.platform)
        val source =
            if (more)
                PagedTrackSource(
                    "chart:${chart.platform.id}:${chart.id}",
                    chart.name,
                    chart.platform,
                    loadedPage + 1,
                ) {
                    reader.chartSongs(chart, it)
                }
            else null
        playPlatformTracks(tracks, index, source)
    }

    internal fun playFileSongs(songs: List<Song>, index: Int) {
        val page = musicPages[0] ?: return
        page.playExternal(songs, index)
    }

    private fun openMusicRoute(page: MusicRouteView) {
        routeShowsMiniPlayer = page.showMiniPlayer
        if (musicRoutes.isEmpty()) {
            musicRouteOrigin = tab
            showTab(tab, force = false)
        }
        musicRoutes.lastOrNull()?.visibility = View.GONE
        musicRoutes += page
        tabViews[tab]?.visibility = View.GONE
        host.addView(page, FrameLayout.LayoutParams(-1, -1))
        if (page.returnToPlayer) PageMotion.reset(page) else PageMotion.enter(page, 1)
    }

    internal fun closeMusicRoute() {
        val page = musicRoutes.removeLastOrNull() ?: return
        val returningToPlayer = page.returnToPlayer && Player.get().current() != null
        if (returningToPlayer) {
            player.restoreFromRoute()
            playerOpen = true
            updateSystemBars()
        }
        page.animate().cancel()
        val parent = musicRoutes.lastOrNull()
        routeShowsMiniPlayer = parent?.showMiniPlayer ?: true
        if (parent != null) {
            parent.visibility = View.VISIBLE
            if (returningToPlayer) PageMotion.reset(parent) else PageMotion.enter(parent, -1)
        } else {
            showTab(musicRouteOrigin, force = false)
            tabViews.getOrNull(musicRouteOrigin)?.let {
                if (returningToPlayer) PageMotion.reset(it) else PageMotion.enter(it, -1)
            }
        }
        host.removeView(page)
    }

    private fun clearMusicRoutes() {
        musicRoutes.forEach {
            it.animate().cancel()
            host.removeView(it)
        }
        musicRoutes.clear()
        routeShowsMiniPlayer = true
    }

    internal fun pickProfileAvatar() {
        avatarPicker.launch("image/*")
    }

    internal fun openCloudFiles() {
        openMusicRoute(CloudFilesPage(this))
    }

    internal fun pickCloudFiles() {
        if (!Cloud.enabled(this) || !Cloud.configured(this)) {
            openSettings("cloud")
            return
        }
        if (fileUpload?.busy() == true) {
            Toast.makeText(this, "已有文件正在上传，请稍候。", Toast.LENGTH_SHORT).show()
            return
        }
        cloudFilePicker.launch(arrayOf("*/*"))
    }

    internal fun uploadLocalFile(file: java.io.File) {
        uploadDialog().local(file)
    }

    private fun uploadDialog(): UploadDialog =
        fileUpload ?: UploadDialog(this) {
            musicRoutes.filterIsInstance<CloudFilesPage>().forEach { it.refresh() }
        }.also { fileUpload = it }

    internal fun openLocalCollection(playlists: Boolean) {
        if (!playlists && !hasStorageAccess()) {
            storageAction = "songs"
            ensurePermission()
            return
        }
        showTab(0)
        musicPages[0]?.openCollection(playlists)
    }

    internal fun storageAvailable(): Boolean = hasStorageAccess()

    internal fun requestMusicStorageAccess() = ensurePermission()

    internal fun scanLocalLibrary() {
        if (!hasStorageAccess()) {
            storageAction = "scan"
            ensurePermission()
            return
        }
        musicPages[0]?.scanLibrary()
    }

    private fun completeStorageAction() {
        if (!hasStorageAccess()) return
        val action = storageAction ?: return
        storageAction = null
        if (action == "songs") openLocalCollection(false)
        if (action == "scan") {
            showTab(0)
            scanLocalLibrary()
        }
    }

    private fun showTab(index: Int, force: Boolean) {
        val previousTab = tab
        val previousPage = tabViews.getOrNull(tab)
        musicPages.forEach { it?.hide() }
        tab = index.coerceIn(0, 1)
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("tab", tab).apply()
        host.setBackgroundColor(Theme.p().canvas)
        val view =
            try {
                when {
                    tab == 0 -> {
                        var page = musicPages[tab]
                        if (page == null || !page.matchesConfiguration()) {
                            page?.dispose()
                            page = MusicPage(this)
                            page.restoreState(libraryStates[tab])
                            libraryStates[tab] = null
                            musicPages[tab] = page
                            page.build()
                        } else {
                            if (force) page.invalidateLibrary()
                            page.show()
                        }
                    }
                    else ->
                        (settingsPage ?: SettingsPage(this).also { settingsPage = it }).show(force)
                }
            } catch (error: Throwable) {
                diag("构建页面失败", error)
                messageView("暂时无法打开", error.message ?: "请稍后重试")
            }
        val pageView = view ?: messageView("暂时无法打开", "页面尚未准备好，请稍后重试")
        val previous = tabViews[tab]
        if (previous !== pageView) {
            if (previous?.parent === host) host.removeView(previous)
            tabViews[tab] = pageView
        }
        if (pageView.parent !== host) {
            (pageView.parent as? ViewGroup)?.removeView(pageView)
            host.addView(pageView, FrameLayout.LayoutParams(-1, -1))
        }
        tabViews.forEachIndexed { position, cached ->
            if (position != tab && cached != null) PageMotion.reset(cached)
            cached?.visibility = if (position == tab) View.VISIBLE else View.GONE
        }
        if (musicRoutes.isNotEmpty()) tabViews[0]?.visibility = View.GONE
        if (previousTab != tab || previousPage !== pageView && !force) {
            PageMotion.enter(pageView, (tab - previousTab).coerceIn(-1, 1))
        }
    }

    fun rebuild() {
        showTab(tab, force = true)
    }

    fun openSettings(section: String) {
        val page = settingsPage ?: SettingsPage(this).also { settingsPage = it }
        page.openSection(section)
    }

    fun setPageBackground(color: Int) {
        host.setBackgroundColor(color)
    }

    fun openPlayer(origin: View?, screenLoc: IntArray?) {
        if (Player.get().current() == null) return
        playerOpen = true
        updateSystemBars()
        player.openFrom(origin, screenLoc)
    }

    fun onPlayerClosed() {
        playerOpen = false
        playbackEpoch++
        updateSystemBars()
    }

    fun setThemeMode(mode: Int) {
        Theme.setMode(this, mode)
        recreate()
    }

    private fun updateSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !playerOpen && !Theme.isDark
            isAppearanceLightNavigationBars = !playerOpen && !Theme.isDark
        }
    }

    override fun onPlayerChanged() {
        runOnUiThread {
            playbackEpoch++
            val failure = Player.get().lastError
            if (failure != null && failure != reportedPlaybackError) {
                android.widget.Toast.makeText(this, failure, android.widget.Toast.LENGTH_LONG)
                    .show()
            }
            reportedPlaybackError = failure
            musicPages.forEach { it?.invalidateContent() }
            showQualitySuggestionIfReady()
        }
    }

    private fun refreshStorageViews() {
        val retainedRoutes = musicRoutes.toList()
        musicPages.forEach { it?.dispose() }
        musicPages.fill(null)
        tabViews.fill(null)
        host.removeAllViews()
        showTab(tab, force = false)
        retainedRoutes.forEach { host.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        if (retainedRoutes.isNotEmpty()) tabViews[tab]?.visibility = View.GONE
    }

    override fun onResume() {
        super.onResume()
        if (::host.isInitialized && storageGranted != hasStorageAccess()) {
            storageGranted = hasStorageAccess()
            refreshStorageViews()
        }
        if (::host.isInitialized) completeStorageAction()
        showQualitySuggestionIfReady()
    }

    override fun onPause() {
        clearExitHint()
        super.onPause()
    }

    private fun clearExitHint() {
        exitHintAt = 0L
        exitHint?.cancel()
        exitHint = null
    }

    private fun showQualitySuggestionIfReady() {
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
        val player = Player.get()
        val suggestion = player.takeQualitySuggestion() ?: return
        if (
            player.current()?.key != suggestion.songKey ||
                AccountStore.accounts(this).none {
                    it.platform == suggestion.platform && it.userId == suggestion.userId
                } ||
                AccountStore.quality(this, suggestion.platform) != suggestion.requested
        )
            return
        val label = suggestion.suggested.label(suggestion.platform)
        Sheet.of(this, "默认使用 $label？")
            .size(SheetSize.COMPACT)
            .content(UI.muted(this, "这首歌已切换至 $label。是否将它设为酷狗的默认音质？"))
            .item(R.drawable.ic_quality, "设为默认音质") {
                if (
                    AccountStore.accounts(this).any {
                        it.platform == suggestion.platform && it.userId == suggestion.userId
                    } && AccountStore.quality(this, suggestion.platform) == suggestion.requested
                ) {
                    AccountStore.setQuality(this, suggestion.platform, suggestion.suggested)
                }
            }
            .item(R.drawable.ic_nav_close, "仅本次使用") {}
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            storageGranted = hasStorageAccess()
            refreshStorageViews()
            completeStorageAction()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("tab", tab)
        outState.putBoolean("mini.collapsed", miniCollapsed)
        outState.putString("storage.action", storageAction)
        settingsPage?.saveState(outState)
        libraryStates.indices.forEach { index ->
            (musicPages[index]?.saveState() ?: libraryStates[index])?.let {
                outState.putBundle("library.$index", it)
            }
        }
        outState.putInt("music.origin", musicRouteOrigin)
        outState.putParcelableArrayList(
            "music.routes",
            ArrayList(musicRoutes.map { it.saveRoute() }),
        )
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        fileUpload?.dismiss()
        Player.get().removeListener(this)
        player.dispose()
        musicPages.forEach { it?.dispose() }
        super.onDestroy()
    }

    private fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED

    private fun ensurePermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName"),
                    )
                )
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else
            requestPermissions(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ),
                1,
            )
    }

    private fun permissionView(): View = messageView("让音乐回到你的曲库", "允许读取本地音乐文件后，就可以扫描和播放本地曲库。", true)

    private fun messageView(title: String, description: String, permission: Boolean = false): View {
        val body = UI.column(this)
        body.setPadding(UI.dp(24), UI.dp(48), UI.dp(24), UI.dp(UI.CONTENT_BOTTOM))
        body.addView(UI.pageTitle(this, title))
        body.addView(UI.spacer(this, 16))
        body.addView(UI.body(this, description))
        if (permission) {
            body.addView(UI.spacer(this, 24))
            body.addView(MiuixActionView(this, "允许访问音乐文件") { ensurePermission() })
        }
        return ScrollView(this).apply { addView(body) }
    }

    companion object {
        const val PREFS = "tingyu"
        private val NAVIGATION =
            listOf(
                AppNavigationItem("音乐", R.drawable.ic_tab_music),
                AppNavigationItem("我的", R.drawable.ic_account),
            )

        @JvmStatic
        fun diagLine(line: String?) {
            Log.d("Tingyu", line.orEmpty())
        }

        @JvmStatic
        fun diag(tag: String, error: Throwable) {
            Log.e("Tingyu", tag, error)
        }
    }
}
