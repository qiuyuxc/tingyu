package com.qiuyuxc.tingyu

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** A compact profile and account list; connection details open only when requested. */
internal class PlatformAccountsView(private val act: MainActivity) : MiuixView(act) {
    @Composable
    override fun Render() {
        val colors = MiuixTheme.colorScheme
        val revision = AccountStore.revision
        val accounts = remember(revision) { AccountStore.accounts(act) }
        val identitySettings = remember(revision) { AccountStore.settings(act) }
        var expanded by remember { mutableStateOf<MusicPlatform?>(null) }
        var refresh by remember { mutableIntStateOf(0) }
        val problems = remember { mutableStateMapOf<MusicPlatform, String>() }
        val loading = remember { mutableStateListOf<MusicPlatform>() }
        LaunchedEffect(
            accounts.map { "${it.platform.id}:${it.userId}:${it.connectedAt}" },
            refresh,
        ) {
            for (account in accounts) {
                val existing = AccountStore.membership(act, account)
                if (
                    refresh == 0 &&
                        existing != null &&
                        System.currentTimeMillis() - existing.checkedAt < 15 * 60 * 1000
                )
                    continue
                loading += account.platform
                problems.remove(account.platform)
                try {
                    val result =
                        withContext(Dispatchers.IO) {
                            val authorization =
                                AccountStore.authorization(act, account.platform)
                                    ?: error("请重新连接账号")
                            require(authorization.profile.userId == account.userId) { "账号已变更" }
                            PlatformLibraryClient(authorization.profile, authorization.session)
                                .membership()
                        }
                    ensureActive()
                    AccountStore.updateMembership(act, account, result)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    problems[account.platform] = error.message ?: "会员信息尚未同步"
                } finally {
                    loading.remove(account.platform)
                }
            }
        }

        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            Column {
                MusicSectionTitle("音乐账号")
                Card(
                    Modifier.fillMaxWidth(),
                    insideMargin = PaddingValues(0.dp),
                    cornerRadius = 20.dp,
                ) {
                    MusicPlatform.entries.forEachIndexed { index, platform ->
                        if (index > 0) InsetDivider(72)
                        val account = accounts.firstOrNull { it.platform == platform }
                        val vip = account?.let { AccountStore.membership(act, it)?.displayAt() }
                        val open = expanded == platform && account != null
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable(onClickLabel = "管理${platform.label}账号") {
                                    if (account == null) act.connectPlatform(platform)
                                    else expanded = if (open) null else platform
                                }
                                .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PlatformMark(platform, 40.dp)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                UiText(
                                    platform.label,
                                    size = 15,
                                    weight = FontWeight.Medium,
                                    maxLines = 1,
                                )
                                Spacer(Modifier.height(3.dp))
                                UiText(
                                    account?.let {
                                        it.nickname +
                                            if (identitySettings.source == platform.id) " · 主账号"
                                            else ""
                                    } ?: "同步歌单与喜欢的歌曲",
                                    size = 12,
                                    color = colors.onSurfaceVariantSummary,
                                    maxLines = 1,
                                )
                            }
                            VipBadge(vip)
                            if (account == null) TextAction("连接") { act.connectPlatform(platform) }
                            else
                                MiuixIconButton(
                                    if (open) R.drawable.ic_chevron_down
                                    else R.drawable.ic_nav_chevron,
                                    if (open) "收起账号详情" else "展开账号详情",
                                    diameter = 44.dp,
                                ) {
                                    expanded = if (open) null else platform
                                }
                        }
                        AnimatedVisibility(
                            visible = open,
                            enter = expandVertically(tween(220)) + fadeIn(tween(160)),
                            exit = shrinkVertically(tween(200)) + fadeOut(tween(140)),
                        ) {
                            Column(
                                Modifier.fillMaxWidth()
                                    .padding(start = 20.dp, end = 20.dp, bottom = 16.dp)
                            ) {
                                InsetDivider(0)
                                Row(
                                    Modifier.fillMaxWidth().padding(top = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(
                                        Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        UiText(
                                            if (platform in loading && vip == null) "正在同步会员信息…"
                                            else
                                                vip?.let {
                                                    it.tier +
                                                        (it.level?.let { level -> " · Lv.$level" }
                                                            ?: "")
                                                } ?: "会员状态待同步",
                                            size = 13,
                                            color = colors.onSurfaceSecondary,
                                        )
                                        if (vip != null) {
                                            if (vip.subscriptions.isEmpty())
                                                UiText(
                                                    vip.expiryText(),
                                                    size = 12,
                                                    color = colors.onSurfaceVariantSummary,
                                                )
                                            else
                                                vip.subscriptions.forEach { subscription ->
                                                    Row(
                                                        horizontalArrangement =
                                                            Arrangement.spacedBy(8.dp),
                                                        verticalAlignment =
                                                            Alignment.CenterVertically,
                                                    ) {
                                                        if (
                                                            subscription.kind ==
                                                                MembershipTier.MUSIC_PACKAGE
                                                        )
                                                            UiText(
                                                                subscription.kind.label,
                                                                size = 11,
                                                            )
                                                        else MembershipBadge(subscription.kind)
                                                        UiText(
                                                            subscription.expiryText(),
                                                            size = 12,
                                                            color = colors.onSurfaceVariantSummary,
                                                        )
                                                    }
                                                }
                                        }
                                        problems[platform]?.let {
                                            UiText(
                                                it,
                                                size = 12,
                                                color = colors.onSurfaceVariantSummary,
                                            )
                                        }
                                    }
                                    MiuixIconButton(
                                        R.drawable.ic_action_refresh,
                                        "更新${platform.label}会员信息",
                                        diameter = 44.dp,
                                    ) {
                                        if (platform !in loading) refresh++
                                    }
                                }
                                FlowRow(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    TextAction("喜欢的歌曲") {
                                        act.openPlatformMusic(platform, favorites = true)
                                    }
                                    TextAction("我的歌单") { act.openPlatformMusic(platform) }
                                }
                                FlowRow(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    TextAction(
                                        "音质 · ${AccountStore.quality(act, platform).label(platform)}"
                                    ) {
                                        act.showAudioQualityOptions(platform)
                                    }
                                    TextAction("账号管理") {
                                        account?.let { act.showPlatformOptions(it) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
