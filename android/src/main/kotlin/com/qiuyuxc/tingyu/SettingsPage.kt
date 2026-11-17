package com.qiuyuxc.tingyu

import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.ComponentDialog
import java.io.File
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 设置页。 */
class SettingsPage(private val act: MainActivity) {

    /** MIUIX heading shared with the scrollable settings groups. */
    private var topBar: TopBar? = null
    private val navigation = SettingsNavigation()
    private val section
        get() = navigation.page

    private var retainedScroll: ScrollView? = null
    private val scrollPositions = HashMap<String, Int>()

    private var retained: View? = null
    private var retainedSection: String? = null
    private var retainedPreferences: Map<String, *>? = null
    private var retainedLibraryRevision = -1L
    private var mine: AccountCentreView? = null
    private var mineState: android.os.Bundle? = null

    fun show(force: Boolean = false): View {
        val preferences =
            act.getSharedPreferences(MainActivity.PREFS, 0).all.filterKeys { it != "tab" }
        if (
            force ||
                retained == null ||
                retainedSection != section ||
                retainedPreferences != preferences ||
                section == "home" && retainedLibraryRevision != Playlist.revision()
        ) {
            rememberScroll()
            retained = build()
            retainedSection = section
            retainedPreferences = preferences
            retainedLibraryRevision = Playlist.revision()
        }
        return requireNotNull(retained)
    }

    fun openSection(next: String) {
        rememberScroll()
        navigation.open(next, act.currentTab)
        act.showTab(1)
        retained?.let { PageMotion.enter(it, 1) }
    }

    fun goHome() {
        rememberScroll()
        navigation.reset()
    }

    fun handleBack(): Boolean {
        rememberScroll()
        if (!navigation.back()) return false
        val origin = navigation.takeReturnTab()
        if (origin != null) act.showTab(origin)
        else {
            act.rebuild()
            retained?.let { PageMotion.enter(it, -1) }
        }
        return true
    }

    private fun rememberScroll() {
        retainedSection?.let { previous ->
            retainedScroll?.let { scrollPositions[previous] = it.scrollY }
        }
    }

    fun saveState(state: android.os.Bundle) {
        rememberScroll()
        state.putString("settings.page", section)
        state.putStringArrayList("settings.history", ArrayList(navigation.history))
        navigation.returnTab?.let { state.putInt("settings.origin", it) }
        state.putBundle("mine.state", mine?.saveState() ?: mineState)
        state.putBundle(
            "settings.scroll",
            android.os.Bundle().apply {
                scrollPositions.forEach { (page, position) -> putInt(page, position) }
            },
        )
    }

    fun restoreState(state: android.os.Bundle) {
        navigation.restore(
            state.getString("settings.page"),
            state.getStringArrayList("settings.history").orEmpty(),
            if (state.containsKey("settings.origin")) state.getInt("settings.origin") else null,
        )
        mineState = state.getBundle("mine.state")
        state.getBundle("settings.scroll")?.let { saved ->
            saved.keySet().forEach { scrollPositions[it] = saved.getInt(it) }
        }
    }


    fun build(): View {
        if (section == "home") {
            retainedScroll = null; topBar = null
            return mine ?: AccountCentreView(act, mineState).also { mine = it }
        }
        val root = FrameLayout(act)
        val scroll = ScrollView(act)
        val body = UI.column(act).apply {
            setPadding(UI.dp(UI.pageInset(act)), UI.dp(12), UI.dp(UI.pageInset(act)), UI.dp(UI.CONTENT_BOTTOM))
        }
        scroll.addView(body)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        val heading = TopBar(act).also { topBar = it }
        root.addView(heading, TopBar.params(act))
        retainedScroll = scroll; heading.attachScroll(scroll)
        UI.restoreScroll(scroll, scrollPositions[section] ?: 0)
        val title = when (section) {
            "preferences" -> "设置"; "accounts" -> "平台账号"; "appearance" -> "外观"
            "playback" -> "播放"; "storage", "directories" -> "音乐与文件"
            "cloud" -> "云端存储"; "cache" -> "缓存管理"; else -> "关于"
        }
        heading.set(title, true, { handleBack() }, null, null)
        body.addView(when (section) {
            "preferences" -> settingsIndex(); "accounts" -> PlatformAccountsView(act)
            "appearance" -> appearanceSettings(); "playback" -> playbackSettings()
            "storage", "directories" -> directorySettings(); "cloud" -> CloudSettingsView(act).build()
            "cache" -> cacheSettings()
            else -> AboutPage.create(act, versionName())
        })
        return root
    }

    private fun settingsIndex(): View = UI.column(act).apply {
        listOf(
            "账号" to listOf(Triple("平台账号", "登录、会员与默认音质", "accounts")),
            "偏好" to listOf(Triple("外观", "主题与配色", "appearance"), Triple("播放", "播放页背景", "playback")),
            "音乐库" to listOf(
                Triple("音乐与文件", "本地目录与歌单导出", "directories"),
                Triple("云端存储", "S3、OpenList 与 HTTP 连接", "cloud"),
            ),
            "应用" to listOf(Triple("缓存管理", "封面缓存与清理", "cache"), Triple("关于", "版本、开源协议与致谢", "about")),
        ).forEach { (label, items) ->
            addView(group(label).apply {
                items.forEach { (name, description, page) ->
                    addView(MiuixPreferenceView(act, name, description) { openSection(page) })
                }
            })
        }
    }

    private fun appearanceSettings(): View = group("主题和配色").apply {
        addView(MiuixChoicePreferenceView(act, "主题", null, listOf("跟随系统", "亮色", "暗色"), Theme.mode()) {
            act.setThemeMode(it)
        })
        addView(AppearancePreviewView(act))
        addView(MiuixChoicePreferenceView(act, "配色", "柔和底色与分层卡片", listOf("松绿", "晴蓝", "雾灰"), Theme.palette()) {
            Theme.setPalette(act, it); act.recreate()
        })
    }

    private fun playbackSettings(): View = group("播放界面").apply {
        addView(MiuixSwitchPreferenceView(act, "播放页封面背景", "用专辑封面作为背景", Theme.artworkBackground()) {
            Theme.setArtworkBackground(act, it)
        })
    }

    private fun directorySettings(): View = group("音乐目录").apply {
        addView(MiuixPreferenceView(act, "本地音乐目录", Dirs.src(act)) { askDirectory(true) })
        addView(MiuixPreferenceView(act, "歌单导出目录", Dirs.out(act)) { askDirectory(false) })
        addView(MiuixSwitchPreferenceView(act, "自动导出歌单", "收藏变更后更新本地 M3U 文件", Prefs.exportM3u(act)) {
            Prefs.set(act, "m3u", it)
        })
    }

    private fun cacheSettings(): View = group("封面缓存").apply {
        addView(MiuixPreferenceView(act, "清除封面缓存",
            String.format("%.1f MB · 已保存在设备", PlatformArtwork.diskBytes() / 1048576.0)) {
            act.lifecycleScope.launch {
                withContext(kotlinx.coroutines.Dispatchers.IO) { PlatformArtwork.clear() }
                act.rebuild()
            }
        })
    }

    private fun group(title: String): LinearLayout = UI.column(act).apply {
        UI.applySurface(this, UI.RADIUS_CARD.toFloat())
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = UI.dp(12) }
        addView(UI.strong(act, title).apply { setPadding(UI.dp(16), UI.dp(14), UI.dp(16), UI.dp(8)) })
    }

    private fun versionName(): String? = runCatching {
        @Suppress("DEPRECATION")
        act.packageManager.getPackageInfo(act.packageName, 0).versionName
    }.getOrNull()

    private fun askDirectory(source: Boolean) {
        val dialog = ComponentDialog(act)
        val box = UI.column(act).apply {
            background = UI.surface(28f)
            setPadding(UI.dp(24), UI.dp(24), UI.dp(24), UI.dp(24))
        }
        box.addView(UI.strong(act, if (source) "本地音乐目录" else "歌单导出目录"))
        val input = UI.input(act, "目录绝对路径").apply { setText(if (source) Dirs.src(act) else Dirs.out(act)) }
        box.addView(input); box.addView(UI.spacer(act, 16))
        box.addView(MiuixActionView(act, "保存") {
            val value = input.text.toString().trim()
            if (value.isBlank() || !File(value).isAbsolute) { input.error = "请输入目录绝对路径"; return@MiuixActionView }
            if (source) Dirs.setSrc(act, value) else Dirs.setOut(act, value)
            dialog.dismiss(); act.rebuild()
        })
        dialog.setContentView(box); dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            setLayout(UI.dp(minOf(560, act.resources.configuration.screenWidthDp - 32).coerceAtLeast(240)), -2)
        }
    }
}
