package com.qiuyuxc.tingyu

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AccountAvatar(
    identity: DisplayIdentity,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
) {
    val context = LocalContext.current
    val colors = MiuixTheme.colorScheme
    val pixels = with(androidx.compose.ui.platform.LocalDensity.current) { size.roundToPx() }
    val avatarKey = avatarKey(identity.avatar)
    var avatar by
        remember(avatarKey, pixels) {
            mutableStateOf(cachedAvatar(identity.avatar, avatarKey, pixels))
        }
    LaunchedEffect(avatarKey, pixels) {
        if (avatar == null)
            avatar =
                withContext(Dispatchers.IO) {
                    runCatching { loadAvatar(context, identity.avatar, avatarKey, pixels) }
                        .getOrNull()
                }
    }
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(listOf(colors.surfaceContainer, colors.tertiaryContainer))
            )
            .border(1.dp, colors.primary.copy(alpha = 0.12f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = avatar
        if (bitmap != null)
            Image(
                bitmap.asImageBitmap(),
                null,
                Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        else UiIcon(R.drawable.ic_account, colors.primary, Modifier.size(size * 0.48f))
    }
}

private fun avatarKey(value: String): String =
    if (value.startsWith("file:")) {
        val file = File(Uri.parse(value).path.orEmpty())
        "avatar:$value:${file.lastModified()}:${file.length()}"
    } else value

private fun cachedAvatar(value: String, key: String, pixels: Int): Bitmap? =
    if (value.startsWith("file:")) ArtworkBitmaps.cache.cached(key, pixels)
    else PlatformArtwork.cachedThumbnail(value, pixels)

private fun loadAvatar(context: Context, value: String, key: String, pixels: Int): Bitmap? {
    if (value.isBlank()) return null
    if (!value.startsWith("file:")) return PlatformArtwork.thumbnail(value, pixels)
    return ArtworkBitmaps.cache.get(key, pixels) { target ->
        val file = File(Uri.parse(value).path.orEmpty())
        if (
            !file.canonicalPath.startsWith(context.filesDir.canonicalPath + "/") ||
                file.length() > 3145728
        )
            return@get null
        PlatformArtwork.decode(file.readBytes(), target)
    }
}

@Composable
internal fun PlatformMark(platform: MusicPlatform, size: Dp = 42.dp) {
    val tint =
        if (Theme.palette() == 2) MiuixTheme.colorScheme.onSurface
        else if (platform == MusicPlatform.NETEASE) Color(0xFFC96564) else Color(0xFF5098BA)
    Box(
        Modifier.size(size)
            .clip(RoundedCornerShape(size * 0.32f))
            .background(tint.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        UiIcon(platform.icon, tint)
    }
}

internal class ListeningIntroView(context: Context) : MiuixView(context) {
    @Composable
    override fun Render() {
        val revision = AccountStore.revision
        val identity = remember(revision) { AccountStore.identity(context) }
        val colors = MiuixTheme.colorScheme
        val greeting =
            when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
                in 5..10 -> "早上好"
                in 11..13 -> "中午好"
                in 14..17 -> "下午好"
                else -> "晚上好"
            }
        Column(Modifier.fillMaxWidth().padding(bottom = 22.dp)) {
            UiText(
                "$greeting，${identity.nickname}",
                size = 12,
                color = colors.primary,
                maxLines = 1,
            )
            Spacer(Modifier.height(7.dp))
            UiText("今天，听点喜欢的。", size = 27, weight = FontWeight.SemiBold)
        }
    }
}

internal class MusicSourcesView(
    context: Context,
    private val count: Int,
    private val local: () -> Unit,
    private val platform: (MusicPlatform) -> Unit,
    private val scan: () -> Unit,
) : MiuixView(context) {
    @Composable
    override fun Render() {
        val revision = AccountStore.revision
        val accounts = remember(revision) { AccountStore.accounts(context) }
        val colors = MiuixTheme.colorScheme
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                UiText("音乐来源", Modifier.weight(1f), size = 14, weight = FontWeight.Medium)
                UiText(
                    "扫描本地",
                    size = 12,
                    color = colors.primary,
                    modifier =
                        Modifier.clickable(onClickLabel = "扫描本地音乐", onClick = scan)
                            .padding(vertical = 14.dp, horizontal = 8.dp),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(
                    Modifier.weight(1f)
                        .clip(RoundedCornerShape(20.dp))
                        .background(colors.tertiaryContainer)
                        .clickable(onClickLabel = "打开本地曲库", onClick = local)
                        .padding(14.dp)
                ) {
                    UiIcon(R.drawable.ic_tab_music, colors.primary)
                    Spacer(Modifier.height(9.dp))
                    UiText("本地曲库", size = 13, weight = FontWeight.Medium, maxLines = 1)
                    UiText(
                        "$count 首收藏",
                        size = 11,
                        color = colors.onSurfaceVariantSummary,
                        maxLines = 1,
                    )
                }
                MusicPlatform.entries.forEach { source ->
                    val account = accounts.firstOrNull { it.platform == source }
                    Column(
                        Modifier.weight(1f)
                            .clip(RoundedCornerShape(20.dp))
                            .background(colors.surfaceContainer)
                            .clickable(
                                onClickLabel = "管理${source.label}账号",
                                onClick = { platform(source) },
                            )
                            .padding(14.dp)
                    ) {
                        UiIcon(
                            source.icon,
                            if (source == MusicPlatform.NETEASE) Color(0xFFC96564)
                            else Color(0xFF5098BA),
                        )
                        Spacer(Modifier.height(9.dp))
                        UiText(
                            if (source == MusicPlatform.NETEASE) "网易云" else "酷狗",
                            size = 13,
                            weight = FontWeight.Medium,
                            maxLines = 1,
                        )
                        UiText(
                            account?.nickname ?: "连接账号",
                            size = 11,
                            color = colors.onSurfaceVariantSummary,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountStat(value: String, label: String, modifier: Modifier, action: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClickLabel = label, onClick = action)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        UiText(value, size = 24, weight = FontWeight.SemiBold)
        UiText(
            label,
            size = 11,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
        )
    }
}

@Composable
private fun AccountPreference(title: String, summary: String, icon: Int, action: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClickLabel = title, onClick = action)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.tertiaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            UiIcon(icon, colors.primary)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            UiText(title, size = 14, weight = FontWeight.Medium)
            UiText(summary, size = 12, color = colors.onSurfaceVariantSummary)
        }
        UiIcon(R.drawable.ic_nav_chevron, colors.onSurfaceVariantActions)
    }
}
