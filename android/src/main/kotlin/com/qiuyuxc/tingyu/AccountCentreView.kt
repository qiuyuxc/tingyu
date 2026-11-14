package com.qiuyuxc.tingyu

import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Personal music is reachable here; account and file administration live in Settings. */
internal class AccountCentreView(private val act: MainActivity, state: Bundle? = null) :
    MiuixView(act) {
    private var listIndex = state?.getInt("index") ?: 0
    private var listOffset = state?.getInt("offset") ?: 0
    private var attached by mutableStateOf(false)

    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
    }

    override fun onDetachedFromWindow() {
        attached = false
        super.onDetachedFromWindow()
    }

    fun saveState() =
        Bundle().apply {
            putInt("index", listIndex)
            putInt("offset", listOffset)
        }

    @Composable
    override fun Render() {
        val colors = MiuixTheme.colorScheme
        val accountRevision = AccountStore.revision
        val accounts = remember(accountRevision) { AccountStore.accounts(act) }
        val identity = remember(accountRevision) { AccountStore.identity(act) }
        val membership =
            accounts
                .firstOrNull { it.platform == identity.platform }
                ?.let { AccountStore.membership(act, it) }
        val catalogRevision = PlatformLibraryStore.revision
        val catalogs =
            remember(catalogRevision, accounts) {
                accounts.associateWith { PlatformLibraryStore.get(it)?.playlists.orEmpty() }
            }
        var refresh by remember { mutableIntStateOf(0) }
        val loading = remember { mutableStateListOf<MusicPlatform>() }
        val problems = remember { mutableStateMapOf<MusicPlatform, String>() }
        val historyRevision = ListeningHistory.revision
        var heard by remember { mutableStateOf<List<ListeningEntry>>(emptyList()) }
        val list = rememberLazyListState(listIndex, listOffset)
        LaunchedEffect(list) {
            snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
                .collect { (index, offset) ->
                    listIndex = index
                    listOffset = offset
                }
        }
        LaunchedEffect(historyRevision, attached) {
            if (attached) heard = withContext(Dispatchers.IO) { ListeningHistory.entries() }
        }
        LaunchedEffect(
            accounts.map { "${it.platform.id}:${it.userId}:${it.connectedAt}" },
            refresh,
            attached,
        ) {
            if (!attached) return@LaunchedEffect
            for (profile in accounts) {
                loading += profile.platform
                problems.remove(profile.platform)
                try {
                    val catalog =
                        withContext(Dispatchers.IO) {
                            val authorization =
                                AccountStore.authorization(act, profile.platform)
                                    ?: error("请在设置中重新连接账号")
                            require(authorization.profile.userId == profile.userId) {
                                "账号已变更，请重新同步"
                            }
                            PlatformLibraryClient(authorization.profile, authorization.session)
                                .playlistCatalog()
                        }
                    ensureActive()
                    if (
                        AccountStore.accounts(act).any {
                            it.platform == profile.platform && it.userId == profile.userId
                        }
                    )
                        PlatformLibraryStore.publishCatalog(profile, catalog)
                    val cached = AccountStore.membership(act, profile)
                    if (cached == null || !cached.freshAt()) {
                        val member =
                            withContext(Dispatchers.IO) {
                                val authorization =
                                    AccountStore.authorization(act, profile.platform)
                                        ?: return@withContext null
                                PlatformLibraryClient(authorization.profile, authorization.session)
                                    .membership()
                            }
                        ensureActive()
                        if (member != null) AccountStore.updateMembership(act, profile, member)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    problems[profile.platform] = error.message ?: "歌单同步失败"
                } finally {
                    loading.remove(profile.platform)
                }
            }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            state = list,
            contentPadding =
                PaddingValues(
                    start = UI.pageInset(act).dp,
                    end = UI.pageInset(act).dp,
                    top = 12.dp,
                    bottom = UI.CONTENT_BOTTOM.dp + PageInsets.bottom,
                ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "profile") {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(64.dp)
                            .clickable(
                                onClickLabel = "编辑头像与昵称",
                                role = Role.Button,
                                onClick = act::showIdentityOptions,
                            )
                    ) {
                        AccountAvatar(identity, size = 64.dp)
                        Box(
                            Modifier.align(Alignment.BottomEnd)
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(colors.surfaceContainer)
                                .border(1.dp, colors.dividerLine, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            UiIcon(R.drawable.ic_edit, colors.primary, Modifier.size(13.dp))
                        }
                    }
                    FlowRow(
                        Modifier.weight(1f).padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        UiText(
                            identity.nickname,
                            size = 24,
                            weight = FontWeight.SemiBold,
                            maxLines = 2,
                        )
                        VipBadge(membership)
                    }
                    MiuixIconButton(R.drawable.ic_ent_gear, "设置", diameter = 44.dp) {
                        act.openSettings("preferences")
                    }
                }
            }
            item(key = "listening") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PersonalMusicTile(
                        "历史记录",
                        "${heard.size} 首听过的音乐",
                        R.drawable.ic_ent_recent,
                        Modifier.weight(1f),
                    ) {
                        act.openPersonalMusic(false)
                    }
                    PersonalMusicTile(
                        "听歌排行",
                        "累计 ${heard.sumOf { it.plays }} 次",
                        R.drawable.ic_chart,
                        Modifier.weight(1f),
                    ) {
                        act.openPersonalMusic(true)
                    }
                }
            }
            item(key = "playlists-heading") {
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    UiText("歌单", Modifier.weight(1f), size = 20, weight = FontWeight.SemiBold)
                    MiuixIconButton(
                        R.drawable.ic_action_refresh,
                        "同步平台歌单",
                        enabled = loading.isEmpty(),
                        diameter = 44.dp,
                    ) {
                        refresh++
                    }
                }
            }
            if (accounts.isEmpty())
                item(key = "connect") {
                    PageEmptyState(
                        R.drawable.ic_ent_playlist,
                        "连接你的音乐账号",
                        "平台歌单会直接显示在这里。",
                        action = "设置平台账号",
                        onClick = { act.openSettings("accounts") },
                    )
                }
            for (profile in accounts) {
                val playlists = catalogs[profile].orEmpty()
                item(key = "platform:${profile.platform.id}") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        UiIcon(
                            profile.platform.icon,
                            colors.onSurfaceVariantSummary,
                            Modifier.size(17.dp),
                        )
                        UiText(
                            profile.platform.label,
                            size = 13,
                            color = colors.onSurfaceVariantSummary,
                        )
                        if (profile.platform in loading)
                            UiText("同步中…", size = 12, color = colors.onSurfaceVariantSummary)
                    }
                }
                items(playlists, key = { "playlist:${it.platform.id}:${it.id}" }) { playlist ->
                    PersonalPlaylistRow(playlist) { act.openPlatformPlaylist(playlist) }
                }
                problems[profile.platform]?.let { problem ->
                    item(key = "problem:${profile.platform.id}") {
                        MusicStatus("暂时无法同步", problem, "账号设置") { act.openSettings("accounts") }
                    }
                }
                if (
                    playlists.isEmpty() &&
                        profile.platform !in loading &&
                        problems[profile.platform] == null
                )
                    item(key = "empty:${profile.platform.id}") {
                        UiText("这个账号还没有歌单。", size = 13, color = colors.onSurfaceVariantSummary)
                    }
            }
        }
    }
}

@Composable
private fun PersonalMusicTile(
    title: String,
    description: String,
    icon: Int,
    modifier: Modifier,
    action: () -> Unit,
) {
    Card(
        modifier.clickable(onClickLabel = title, onClick = action),
        insideMargin = PaddingValues(18.dp),
        cornerRadius = 20.dp,
    ) {
        UiIcon(icon, MiuixTheme.colorScheme.primary, Modifier.size(25.dp))
        Spacer(Modifier.height(18.dp))
        UiText(title, size = 16, weight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        UiText(description, size = 12, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
private fun PersonalPlaylistRow(playlist: PlatformPlaylist, action: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClickLabel = "打开${playlist.name}", onClick = action)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlatformImage(playlist.cover, 56.dp)
        Column(Modifier.weight(1f).padding(horizontal = 13.dp)) {
            UiText(playlist.name, size = 15, weight = FontWeight.Medium, maxLines = 2)
            Spacer(Modifier.height(3.dp))
            UiText(
                "${playlist.count} 首 · ${if (playlist.favorites) "喜欢的音乐" else if (playlist.owned) "创建的歌单" else "收藏的歌单"}",
                size = 12,
                color = colors.onSurfaceVariantSummary,
            )
        }
        UiIcon(playlist.platform.icon, colors.onSurfaceVariantSummary, Modifier.size(18.dp))
        Spacer(Modifier.width(9.dp))
        UiIcon(R.drawable.ic_nav_chevron, colors.onSurfaceVariantActions, Modifier.size(17.dp))
    }
}
