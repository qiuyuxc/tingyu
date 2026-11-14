package com.qiuyuxc.tingyu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Radio content stays below the music tabs and shares only the playback engine. */
@Composable
internal fun HeartRadioView(
    act: MainActivity,
    platform: MusicPlatform,
    visible: Boolean,
    choosePlatform: () -> Unit,
) {
    val accountRevision = AccountStore.revision
    val profile =
        remember(accountRevision, platform) {
            AccountStore.accounts(act).firstOrNull { it.platform == platform }
        }
    var lyrics by remember(platform, profile?.userId) { mutableStateOf(false) }
    DisposableEffect(visible, profile?.userId) {
        act.setHeartPageVisible(visible && profile != null)
        onDispose { act.setHeartPageVisible(false) }
    }
    var loading by remember(platform) { mutableStateOf(false) }
    var problem by remember(platform) { mutableStateOf<String?>(null) }
    var retry by remember(platform) { mutableIntStateOf(0) }
    LaunchedEffect(visible, platform, profile?.userId, profile?.connectedAt, retry) {
        if (!visible || profile == null) return@LaunchedEffect
        if (
            retry == 0 &&
                Player.get().queueSourceId == "heart:${platform.id}" &&
                Player.get().current() != null
        )
            return@LaunchedEffect
        val request = Player.get().reserveQueueRequest()
        val pauseRevision = Player.get().pauseRevision
        loading = true
        problem = null
        try {
            val tracks =
                withContext(Dispatchers.IO) {
                    PlatformDiscoveryClient.forPlatform(act, platform).radioSongs()
                }
            ensureActive()
            require(
                AccountStore.accounts(act).any {
                    it.platform == platform && it.userId == profile.userId
                }
            ) {
                "账号已变更，请重新开始心动播放"
            }
            act.playHeartTracks(tracks.shuffled(), request, pauseRevision)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            problem = error.message ?: "心动歌曲暂时不可用"
        } finally {
            loading = false
        }
    }
    Column(Modifier.fillMaxSize().clipToBounds()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UiText(
                if (lyrics) "歌词" else if (loading) "正在寻找好音乐…" else "随心听",
                Modifier.weight(1f),
                size = 13,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            if (!lyrics)
                Row(
                    Modifier.clickable(onClickLabel = "切换心动平台", onClick = choosePlatform)
                        .heightIn(min = 44.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    UiIcon(platform.icon, MiuixTheme.colorScheme.primary, Modifier.size(17.dp))
                    UiText(if (platform == MusicPlatform.NETEASE) "网易云" else "酷狗", size = 12)
                    UiIcon(
                        R.drawable.ic_chevron_down,
                        MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        Modifier.size(14.dp),
                    )
                }
        }
        if (profile == null) {
            PageEmptyState(
                R.drawable.ic_favorite,
                "让下一首遇见你",
                "连接音乐账号，开始心动随机播放。",
                action = "设置平台账号",
                onClick = { act.openSettings("accounts") },
            )
        } else {
            problem?.let { message ->
                MusicStatus("心动暂时不可用", message, "重试") { retry++ }
            }
            if (visible)
                HeartPlayer(
                    act = act,
                    start = { retry++ },
                    lyrics = lyrics,
                    showLyrics = { lyrics = it },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            else Spacer(Modifier.weight(1f))
        }
    }
}
