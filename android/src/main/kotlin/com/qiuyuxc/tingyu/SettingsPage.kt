package com.qiuyuxc.tingyu

import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.ComponentDialog
import androidx.lifecycle.lifecycleScope
import java.io.File
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

    private var advancedOpen = false
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
        state.putBoolean("settings.advanced", advancedOpen)
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
        advancedOpen = state.getBoolean("settings.advanced", false)
        mineState = state.getBundle("mine.state")
        state.getBundle("settings.scroll")?.let { saved ->
            saved.keySet().forEach { scrollPositions[it] = saved.getInt(it) }
        }
    }

    private fun securitySettings(): View? {
        // ---- 加密与密钥
        val keys = group("加密与密钥")
        val pw = Cloud.passphrase(act)
        val oldN = Cloud.oldKeyCount(act)
        keys!!.addView(
            row(
                "加密密钥",
                if (pw!!.isEmpty()) "尚未生成 · 可导入已有密钥或口令"
                else if (pw.startsWith(ChunkSealKeys.PREFIX)) "已生成 · 可复制到其他设备" else "已保存旧口令",
                null,
            )
        )
        keys.addView(sep())
        keys.addView(
            actionRow(
                "复制密钥",
                UI.accentInk(),
                object : Runnable {
                    public override fun run() {
                        copyPassphrase()
                    }
                },
                "重新生成",
                Theme.p().success,
                object : Runnable {
                    public override fun run() {
                        regeneratePassphrase()
                    }
                },
            )
        )
        keys.addView(sep())
        keys.addView(foldRow("高级", (if (oldN > 0) oldN + " 把旧密钥或口令" else "导入密钥或口令") + " / 备份"))
        if (advancedOpen) {
            keys.addView(sep())
            keys.addView(row("旧密钥与口令", if (oldN > 0) oldN + " 把 (读已有文件用)" else "没有", null))
            keys.addView(
                blockButton(
                    "导入密钥或旧口令",
                    object : Runnable {
                        public override fun run() {
                            askText("导入密钥或口令 (读取已有文件)", "oldKey", true)
                        }
                    },
                )
            )
            if (oldN > 0) {
                keys.addView(
                    blockButton(
                        "复制一把旧密钥或口令",
                        object : Runnable {
                            override fun run() {
                                copyOldKey()
                            }
                        },
                    )
                )
            }
            keys.addView(
                hint(("重新生成密钥后，旧密钥和口令会继续保留，用于读取已有文件。" + "从其他设备导入密钥时，请完整复制，保留开头的 cseal2: 标记。"))
            )
            keys.addView(sep())
            keys.addView(hint("密钥与口令用于读取加密音乐。换设备或卸载前，请备份当前密钥和仍在使用的旧密钥。"))
            keys.addView(sep())
        }
        return keys
    }

    fun build(): View {
        if (section == "home") {
            retainedScroll = null
            topBar = null
            return mine ?: AccountCentreView(act, mineState).also { mine = it }
        }
        val root = FrameLayout(act)
        val scroll = ScrollView(act)
        val body =
            UI.column(act).apply {
                setPadding(
                    UI.dp(UI.pageInset(act)),
                    UI.dp(12),
                    UI.dp(UI.pageInset(act)),
                    UI.dp(UI.CONTENT_BOTTOM),
                )
            }
        scroll.addView(body)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        val heading = TopBar(act).also { topBar = it }
        root.addView(heading, TopBar.params(act))
        retainedScroll = scroll
        heading.attachScroll(scroll)
        UI.restoreScroll(scroll, scrollPositions[section] ?: 0)
        val title =
            when (section) {
                "preferences" -> "设置"
                "accounts" -> "平台账号"
                "appearance" -> "外观"
                "playback" -> "播放"
                "storage" -> "音乐与文件"
                "directories" -> "音乐目录"
                "cloud" -> "云端存储"
                "cache" -> "缓存管理"
                "security" -> "加密与密钥"
                else -> "关于"
            }
        heading.set(title, true, { handleBack() }, null, null)
        body.addView(
            when (section) {
                "preferences" -> settingsIndex()
                "accounts" -> PlatformAccountsView(act)
                "appearance" -> appearanceSettings()
                "playback" -> playbackSettings()
                "storage" -> storageSettings()
                "directories" -> directorySettings()
                "cloud" -> CloudSettingsView(act).build()
                "security" -> securitySettings()
                "cache" -> cacheSettings()
                else -> AboutPage.create(act, versionName())
            }
        )
        return root
    }

    private fun settingsIndex(): View =
        UI.column(act).apply {
            listOf(
                    "账号" to listOf(Triple("平台账号", "登录、会员与默认音质", "accounts")),
                    "偏好" to
                        listOf(
                            Triple("外观", "主题与配色", "appearance"),
                            Triple("播放", "播放页背景", "playback"),
                        ),
                    "音乐库" to listOf(Triple("音乐与文件", "目录、云端连接与密钥", "storage")),
                    "应用" to
                        listOf(
                            Triple("缓存管理", "封面缓存与清理", "cache"),
                            Triple("关于", "版本、开源协议与致谢", "about"),
                        ),
                )
                .forEach { (label, items) ->
                    addView(
                        group(label).apply {
                            items.forEach { (name, description, page) ->
                                addView(
                                    MiuixPreferenceView(act, name, description) {
                                        openSection(page)
                                    }
                                )
                            }
                        }
                    )
                }
        }

    private fun appearanceSettings(): View =
        group("主题和配色").apply {
            addView(
                MiuixChoicePreferenceView(
                    act,
                    "主题",
                    null,
                    listOf("跟随系统", "亮色", "暗色"),
                    Theme.mode(),
                ) {
                    act.setThemeMode(it)
                }
            )
            addView(AppearancePreviewView(act))
            addView(
                MiuixChoicePreferenceView(
                    act,
                    "配色",
                    "柔和底色与分层卡片",
                    listOf("松绿", "晴蓝", "雾灰"),
                    Theme.palette(),
                ) {
                    Theme.setPalette(act, it)
                    act.recreate()
                }
            )
        }

    private fun playbackSettings(): View =
        group("播放界面").apply {
            addView(
                MiuixSwitchPreferenceView(act, "播放页封面背景", "用专辑封面作为背景", Theme.artworkBackground()) {
                    Theme.setArtworkBackground(act, it)
                }
            )
        }

    private fun storageSettings(): View =
        group("音乐文件").apply {
            addView(MiuixPreferenceView(act, "音乐目录", "读取与歌单导出位置") { openSection("directories") })
            addView(MiuixPreferenceView(act, "云端存储", "连接自己的音乐文件库") { openSection("cloud") })
            addView(MiuixPreferenceView(act, "加密与密钥", "密钥与旧口令") { openSection("security") })
        }

    private fun directorySettings(): View =
        group("音乐目录").apply {
            addView(MiuixPreferenceView(act, "本地音乐目录", Dirs.src(act)) { askDirectory(true) })
            addView(MiuixPreferenceView(act, "歌单导出目录", Dirs.out(act)) { askDirectory(false) })
            addView(
                MiuixSwitchPreferenceView(act, "自动导出歌单", "收藏变更后更新本地 M3U 文件", Prefs.exportM3u(act)) {
                    Prefs.set(act, "m3u", it)
                }
            )
        }

    private fun cacheSettings(): View =
        group("封面缓存").apply {
            addView(
                MiuixPreferenceView(
                    act,
                    "清除封面缓存",
                    String.format("%.1f MB · 已保存在设备", PlatformArtwork.diskBytes() / 1048576.0),
                ) {
                    act.lifecycleScope.launch {
                        withContext(kotlinx.coroutines.Dispatchers.IO) { PlatformArtwork.clear() }
                        act.rebuild()
                    }
                }
            )
        }

    private fun group(title: String): LinearLayout =
        UI.column(act).apply {
            UI.applySurface(this, UI.RADIUS_CARD.toFloat())
            UI.margin(this, 0, 0, 0, 12)
            addView(
                UI.text(act, title, 15f, Theme.p().ink, Typeface.BOLD).apply {
                    setPadding(UI.dp(16), UI.dp(14), UI.dp(16), UI.dp(6))
                }
            )
        }

    private fun versionName(): String? = runCatching {
        @Suppress("DEPRECATION") act.packageManager.getPackageInfo(act.packageName, 0).versionName
    }
        .getOrNull()

    private fun row(title: String?, sub: String?, right: View?): LinearLayout? {
        val p = Theme.p()
        val r = UI.row(act)
        r.setPadding(UI.dp(16f), UI.dp(12f), UI.dp(16f), UI.dp(12f))

        val m = UI.column(act)
        m.addView(UI.text(act, title, 14f, p.ink, Typeface.NORMAL))
        if (sub != null && sub.length > 0) {
            val s = UI.text(act, sub, 12f, p.mute, Typeface.NORMAL)
            s.setMaxLines(4)
            UI.margin(s, 0, 2, 0, 0)
            m.addView(s)
        }
        r.addView(
            m,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            ),
        )
        if (right != null) r.addView(right)
        return r
    }

    private fun foldRow(title: String?, value: String?): View? {
        return MiuixPreferenceView(act, title.orEmpty(), value, expanded = advancedOpen) {
            advancedOpen = !advancedOpen
            act.rebuild()
        }
    }

    private fun actionRow(
        left: String?,
        leftBg: Int,
        leftRun: Runnable?,
        right: String?,
        rightBg: Int,
        rightRun: Runnable?,
    ): View? {
        val box = UI.row(act)
        box.setPadding(UI.dp(14f), UI.dp(4f), UI.dp(14f), UI.dp(13f))
        box.addView(
            actionButton(left, leftBg, leftRun),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (right != null) {
            val rlp =
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f,
                )
            rlp.leftMargin = UI.dp(9f)
            box.addView(actionButton(right, rightBg, rightRun), rlp)
        }
        return box
    }

    private fun actionButton(label: String?, bg: Int, run: Runnable?): View? {
        return MiuixActionView(
            act,
            label.orEmpty(),
            accent = bg == Theme.p().accent || bg == Theme.p().accentInk,
        ) {
            run?.run()
        }
    }

    /** 卡片里的一整块次要按钮, 左右内缩 14。 */
    private fun blockButton(label: String?, run: Runnable?): View? {
        val box = UI.column(act)
        box.setPadding(UI.dp(14f), UI.dp(4f), UI.dp(14f), UI.dp(13f))
        val b = MiuixActionView(act, label.orEmpty(), accent = false) { run?.run() }
        box.addView(b)
        return box
    }

    private fun copyPassphrase() {
        try {
            val pw = ChunkSealKeys.encode(Cloud.encryptionKey(act).masterKey)
            val cm =
                act.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager?
            if (cm == null) return
            cm.setPrimaryClip(android.content.ClipData.newPlainText("加密音频密钥", pw))
            android.widget.Toast.makeText(
                    act,
                    "密钥已复制，可在其他设备的「导入密钥或旧口令」中粘贴",
                    android.widget.Toast.LENGTH_LONG,
                )!!
                .show()
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                    act,
                    "复制失败: " + e.message,
                    android.widget.Toast.LENGTH_LONG,
                )!!
                .show()
        }
    }

    private fun copyOldKey() {
        val current = Cloud.passphrase(act)
        val old = Cloud.keyCandidates(act, 0).orEmpty().filterNotNull().filter { it != current }
        val dialog = ComponentDialog(act)
        val choices = UI.column(act)
        choices.setPadding(UI.dp(16), UI.dp(16), UI.dp(16), UI.dp(16))
        choices.addView(UI.text(act, "复制旧密钥或口令", 20f, Theme.p().ink, Typeface.BOLD))
        old.forEachIndexed { index, secret ->
            val label = if (ChunkSealKeys.isExport(secret)) "旧密钥" else "旧口令"
            choices.addView(
                MiuixActionView(act, "$label ${index + 1}") {
                    val clipboard =
                        act.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as? android.content.ClipboardManager
                    clipboard?.setPrimaryClip(android.content.ClipData.newPlainText(label, secret))
                    android.widget.Toast.makeText(
                            act,
                            "$label 已复制",
                            android.widget.Toast.LENGTH_SHORT,
                        )
                        .show()
                    dialog.dismiss()
                }
            )
        }
        dialog.setContentView(choices)
        dialog.show()
    }

    /** 生成新随机密钥，原密钥与口令继续保留。 */
    private fun regeneratePassphrase() {
        try {
            val old = Cloud.passphrase(act)
            Cloud.setPassphrase(act, Cloud.newMasterKeyExport())
            android.widget.Toast.makeText(
                    act,
                    if (old!!.isNotEmpty()) "已生成新密钥，旧密钥与口令继续保留" else "已生成密钥",
                    android.widget.Toast.LENGTH_LONG,
                )!!
                .show()
            act.rebuild()
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                    act,
                    "生成失败: " + e.message,
                    android.widget.Toast.LENGTH_LONG,
                )
                .show()
        }
    }

    private fun hint(text: String?): View? {
        val box = UI.column(act)
        box.setPadding(UI.dp(16f), 0, UI.dp(16f), UI.dp(12f))
        box.addView(UI.muted(act, text))
        return box
    }

    /** Separate settings and action rows with space, without joining their edges. */
    private fun sep(): View = UI.spacer(act, 4)

    private fun askText(label: String?, key: String?, secret: Boolean) {
        val p = Theme.p()
        val dialog = ComponentDialog(act)
        val box = UI.column(act)
        box.background = UI.surface(28f)
        box.setPadding(
            UI.dp(UI.LG.toFloat()),
            UI.dp(UI.LG.toFloat()),
            UI.dp(UI.LG.toFloat()),
            UI.dp(UI.LG.toFloat()),
        )

        box.addView(UI.text(act, label, 20f, p.ink, Typeface.BOLD))

        val cur = if (key == "oldKey") "" else Cloud.passphrase(act)

        val input = UI.input(act, "")
        input.setText(cur)
        if (secret) {
            input.setInputType(
                (android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
            )
        }
        input.setSelection(input.getText()!!.length)
        UI.margin(input, 0, UI.SM, 0, 0)
        box.addView(input)
        box.addView(UI.spacer(act, 16))

        val actions = UI.row(act)
        val cancel = MiuixActionView(act, "取消", accent = false) { dialog.dismiss() }
        UI.weight(cancel, 1f)
        actions.addView(cancel)

        val save =
            MiuixActionView(act, "保存") {
                val entered = input.text.toString()
                val value =
                    if (key == "cloudKey" || key == "oldKey") entered
                    else entered.trim { it <= ' ' }
                try {
                    if (key == "oldKey") Cloud.addOldKey(act, value)
                    else Cloud.setPassphrase(act, value)
                    dialog.dismiss()
                    act.rebuild()
                } catch (e: Exception) {
                    input.error = e.message ?: "无法保存"
                }
            }
        UI.weight(save, 1f)
        UI.margin(save, UI.SM, 0, 0, 0)
        actions.addView(save)
        box.addView(actions)

        dialog.setContentView(box)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            )
            setLayout(
                UI.dp(
                    minOf(560, act.resources.configuration.screenWidthDp - 32).coerceAtLeast(240)
                ),
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
    }

    private fun askDirectory(source: Boolean) {
        val dialog = ComponentDialog(act)
        val box =
            UI.column(act).apply {
                background = UI.surface(28f)
                setPadding(UI.dp(24), UI.dp(24), UI.dp(24), UI.dp(24))
            }
        box.addView(UI.strong(act, if (source) "本地音乐目录" else "歌单导出目录"))
        val input =
            UI.input(act, "目录绝对路径").apply { setText(if (source) Dirs.src(act) else Dirs.out(act)) }
        box.addView(input)
        box.addView(UI.spacer(act, 16))
        box.addView(
            MiuixActionView(act, "保存") {
                val value = input.text.toString().trim()
                if (value.isBlank() || !File(value).isAbsolute) {
                    input.error = "请输入目录绝对路径"
                    return@MiuixActionView
                }
                if (source) Dirs.setSrc(act, value) else Dirs.setOut(act, value)
                dialog.dismiss()
                act.rebuild()
            }
        )
        dialog.setContentView(box)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            )
            setLayout(
                UI.dp(
                    minOf(560, act.resources.configuration.screenWidthDp - 32).coerceAtLeast(240)
                ),
                -2,
            )
        }
    }
}
