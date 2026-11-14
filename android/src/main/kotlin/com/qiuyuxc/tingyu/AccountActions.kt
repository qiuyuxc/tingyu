package com.qiuyuxc.tingyu

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.text.InputFilter
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal fun MainActivity.showAudioQualityOptions(
    platform: MusicPlatform,
    track: PlatformTrack? = null,
) {
    val sheet = Sheet.of(this, "${platform.label}音质").size(SheetSize.MEDIUM)
    sheet
        .content(
            object : MiuixView(this) {
                @Composable
                override fun Render() {
                    var song by remember { mutableStateOf(track) }
                    var loading by remember { mutableStateOf(track != null) }
                    var problem by remember { mutableStateOf<String?>(null) }
                    var refresh by remember { mutableIntStateOf(0) }
                    LaunchedEffect(track?.key, refresh) {
                        val target = track ?: return@LaunchedEffect
                        loading = true
                        problem = null
                        try {
                            song =
                                withContext(Dispatchers.IO) {
                                    PlatformDiscoveryClient.forPlatform(
                                            applicationContext,
                                            platform,
                                        )
                                        .details(listOf(target))
                                        .firstOrNull() ?: target
                                }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            problem = error.message ?: "音质信息暂时无法获取"
                        } finally {
                            loading = false
                        }
                    }
                    val preference = AccountStore.quality(context, platform)
                    val selected = song?.let { AudioQuality.resolve(it, preference) } ?: preference
                    val choices = AudioQuality.choices(platform, song)
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        UiText(song?.title ?: "默认音质", size = 15)
                        UiText(
                            if (song == null) "播放时优先使用所选音质。" else "切换音质会保留播放进度。",
                            size = 12,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        if (loading) UiText("正在获取可用音质…", size = 12)
                        choices.forEach { option ->
                            val tier =
                                if (
                                    platform == MusicPlatform.KUGOU &&
                                        option.access == TrackAccess.FREE
                                )
                                    null
                                else
                                    option.quality.membershipTier(platform)
                                        ?: MembershipTier.VIP.takeIf {
                                            option.access == TrackAccess.VIP ||
                                                option.access == TrackAccess.VIP_QUALITY
                                        }
                            val note =
                                listOf(
                                        option.quality.label(platform),
                                        tier?.label,
                                        option.access.label.takeIf {
                                            it.isNotBlank() &&
                                                option.access != TrackAccess.VIP &&
                                                option.access != TrackAccess.VIP_QUALITY
                                        },
                                        "当前".takeIf { option.quality == selected },
                                    )
                                    .filterNotNull()
                                    .joinToString(" · ")
                            MiuixButton(
                                note,
                                Modifier.fillMaxWidth(),
                                icon =
                                    when (tier) {
                                        MembershipTier.SVIP -> R.drawable.ic_svip
                                        MembershipTier.VIP -> R.drawable.ic_vip
                                        else -> 0
                                    },
                                accent = option.quality == selected,
                                enabled = option.access != TrackAccess.UNAVAILABLE,
                                iconColor =
                                    tier?.let {
                                        val colors = MiuixTheme.colorScheme
                                        val background =
                                            if (option.quality == selected) colors.primary
                                            else colors.secondaryVariant
                                        membershipColor(it, background.luminance() < 0.5f)
                                    },
                            ) {
                                AccountStore.setQuality(context, platform, option.quality)
                                if (
                                    Player.get().current()?.platformTrack?.platform == platform &&
                                        (track == null || Player.get().current()?.key == track.key)
                                )
                                    Player.get().reloadQuality()
                                sheet.dismiss()
                            }
                        }
                        if (!loading && choices.isEmpty()) UiText("平台暂未提供这首歌的音质档位。", size = 13)
                        if (problem != null || (!loading && choices.isEmpty()))
                            TextAction("重新获取音质") { refresh++ }
                    }
                }
            }
        )
        .show()
}

internal fun MainActivity.showPlatformTrackActions(track: PlatformTrack) {
    val profile = AccountStore.accounts(this).firstOrNull { it.platform == track.platform }
    val liked =
        profile?.let { PlatformLibraryStore.get(it)?.favorites?.containsKey(track.key) } == true
    Sheet.of(this, track.title)
        .content(UI.muted(this, "${track.artist} · ${track.platform.label}"))
        .item(R.drawable.ic_media_play, "播放歌曲") { playPlatformTracks(listOf(track), 0) }
        .item(
            if (liked) R.drawable.ic_favorite else R.drawable.ic_favorite_outline,
            if (liked) "取消喜欢" else "喜欢这首歌",
        ) {
            togglePlatformFavorite(track, desired = !liked)
        }
        .item(R.drawable.ic_comment, "查看评论") { openComments(track) }
        .item(R.drawable.ic_quality, "调整音质") { showAudioQualityOptions(track.platform, track) }
        .show()
}

private val favoriteRequests = mutableStateMapOf<String, Unit>()

internal fun favoritePending(track: PlatformTrack) = track.key in favoriteRequests

internal fun MainActivity.togglePlatformFavorite(track: PlatformTrack, desired: Boolean? = null) {
    val profile = AccountStore.accounts(this).firstOrNull { it.platform == track.platform }
    if (profile == null) {
        deferFavoriteLogin(track, desired ?: true)
        return
    }
    if (favoritePending(track)) return
    favoriteRequests[track.key] = Unit
    lifecycleScope.launch {
        try {
            val result =
                withContext(Dispatchers.IO) {
                    val authorization =
                        AccountStore.authorization(this@togglePlatformFavorite, track.platform)
                            ?: error("请重新连接账号")
                    require(authorization.profile.userId == profile.userId) { "账号已变更，请重新打开曲库" }
                    val client = PlatformLibraryClient(profile, authorization.session)
                    val library =
                        PlatformLibraryStore.get(profile)?.takeIf { it.favoritesReady }
                            ?: client.loadLibrary()
                    val target = desired ?: (track.key !in library.favorites)
                    val changed = (track.key in library.favorites) != target
                    if (changed) client.setFavorite(track, target, library)
                    val synchronized = if (changed) client.loadLibrary() else library
                    require(
                        synchronized.favoritesReady &&
                            (track.key in synchronized.favorites) == target
                    ) {
                        "平台尚未确认收藏状态，请刷新后重试"
                    }
                    synchronized to target
                }
            if (
                AccountStore.accounts(this@togglePlatformFavorite).none {
                    it.platform == profile.platform && it.userId == profile.userId
                }
            )
                return@launch
            PlatformLibraryStore.publish(profile, result.first)
            Toast.makeText(
                    this@togglePlatformFavorite,
                    if (result.second) "已加入喜欢" else "已取消喜欢",
                    Toast.LENGTH_SHORT,
                )
                .show()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Toast.makeText(
                    this@togglePlatformFavorite,
                    error.message ?: "同步失败，请重试",
                    Toast.LENGTH_LONG,
                )
                .show()
        } finally {
            favoriteRequests.remove(track.key)
        }
    }
}

internal fun MainActivity.showIdentityOptions() {
    val identity = AccountStore.settings(this)
    val sheet =
        Sheet.of(this, "头像与昵称").item(
            R.drawable.ic_account,
            "使用播放器资料${if (identity.source == IdentitySettings.LOCAL || identity.source == null) " · 当前" else ""}",
        ) {
            AccountStore.selectIdentity(this, null)
        }
    AccountStore.accounts(this).forEach { account ->
        sheet.item(
            account.platform.icon,
            "使用${account.platform.label}资料${if (identity.source == account.platform.id) " · 主账号" else ""}",
        ) {
            AccountStore.selectIdentity(this, account.platform)
        }
    }
    sheet
        .label("自己的播放器资料")
        .item(R.drawable.ic_edit, "修改昵称") { showNicknameEditor() }
        .item(R.drawable.ic_account, "从相册选择头像") { pickProfileAvatar() }
        .show()
}

internal fun MainActivity.showPlatformOptions(account: PlatformProfile) {
    Sheet.of(this, account.platform.label)
        .content(UI.muted(this, "${account.nickname} · 账号 ${account.userId}"))
        .item(R.drawable.ic_tab_music, "歌单与喜欢的歌曲") { openPlatformMusic(account.platform) }
        .item(R.drawable.ic_account, "设为主账号") {
            AccountStore.selectIdentity(this, account.platform)
        }
        .item(R.drawable.ic_action_refresh, "重新登录") { connectPlatform(account.platform) }
        .item(account.platform.icon, "打开平台") {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(account.platform.home)))
            } catch (_: Exception) {
                Toast.makeText(this, "没有可用的浏览器", Toast.LENGTH_SHORT).show()
            }
        }
        .item(R.drawable.ic_nav_close, "断开账号连接") {
            Sheet.of(this, "断开${account.platform.label}连接")
                .content(UI.muted(this, "清除这个平台的登录状态，本地收藏会继续保留。"))
                .item(R.drawable.ic_nav_close, "确认断开") {
                    AccountStore.disconnect(this, account.platform)
                }
                .show()
        }
        .show()
}

internal fun MainActivity.openMusicSources() {
    val accounts = AccountStore.accounts(this)
    val sheet =
        Sheet.of(this, "音乐来源")
            .item(R.drawable.ic_tab_music, "本地曲库") { openLocalCollection(false) }
    MusicPlatform.entries.forEach { platform ->
        val account = accounts.firstOrNull { it.platform == platform }
        sheet.item(platform.icon, "${platform.label} · ${account?.nickname ?: "扫码连接"}") {
            openPlatformMusic(platform)
        }
    }
    sheet.show()
}

private fun MainActivity.showNicknameEditor() {
    val dialog =
        ComponentDialog(this, if (Theme.isDark) R.style.SheetDialog_Dark else R.style.SheetDialog)
    val palette = Theme.p()
    val body =
        UI.column(this).apply {
            setPadding(UI.dp(24), UI.dp(24), UI.dp(24), UI.dp(24))
            background = UI.rounded(palette.canvasRaised, 24f)
        }
    body.addView(UI.text(this, "播放器昵称", 22f, palette.ink, Typeface.BOLD))
    body.addView(UI.spacer(this, 10))
    body.addView(UI.muted(this, "保存后使用自己的资料，平台账号仍保留。"))
    body.addView(UI.spacer(this, 18))
    val input =
        EditText(this).apply {
            setText(AccountStore.settings(this@showNicknameEditor).nickname)
            setTextColor(palette.ink)
            setHintTextColor(palette.mute)
            textSize = 16f
            contentDescription = "播放器昵称"
            isSingleLine = true
            filters = arrayOf(InputFilter.LengthFilter(32))
            background = UI.roundedStroke(palette.canvasSoft, 14f, palette.hairline, 1f)
            setPadding(UI.dp(14), UI.dp(14), UI.dp(14), UI.dp(14))
            setSelectAllOnFocus(true)
        }
    body.addView(input, ViewGroup.LayoutParams(-1, -2))
    body.addView(UI.spacer(this, 20))
    body.addView(
        MiuixActionView(this, "保存") {
            val value = input.text.toString().trim()
            if (value.isBlank()) {
                input.error = "请输入昵称"
            } else {
                AccountStore.setNickname(this, value)
                dialog.dismiss()
            }
        }
    )
    body.addView(UI.spacer(this, 8))
    body.addView(MiuixActionView(this, "取消", accent = false) { dialog.dismiss() })
    dialog.setContentView(body)
    dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    dialog.show()
    dialog.window?.setLayout(
        (resources.displayMetrics.widthPixels - UI.dp(48)).coerceAtMost(UI.dp(420)),
        -2,
    )
}
