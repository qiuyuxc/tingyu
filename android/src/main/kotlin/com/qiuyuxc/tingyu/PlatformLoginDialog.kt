package com.qiuyuxc.tingyu

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun PlatformLoginDialog(
    platform: MusicPlatform,
    dismiss: () -> Unit,
    connected: (AuthorizedAccount) -> Unit,
) {
    var attempt by remember(platform) { mutableIntStateOf(0) }
    var bitmap by remember(platform, attempt) { mutableStateOf<Bitmap?>(null) }
    var status by remember(platform, attempt) { mutableStateOf("正在获取登录二维码…") }
    var retry by remember(platform, attempt) { mutableStateOf(false) }
    var retainedChallenge by remember(platform) { mutableStateOf<LoginChallenge?>(null) }
    LaunchedEffect(platform, attempt) {
        try {
            val client = PlatformLoginClient()
            val challenge =
                retainedChallenge?.takeIf { it.authorized }
                    ?: withContext(Dispatchers.IO) { client.create(platform) }
                        .also { retainedChallenge = it }
            bitmap = withContext(Dispatchers.Default) { qrBitmap(challenge.url) }
            status = "使用${platform.label} App 扫码"
            val expires = System.currentTimeMillis() + 180000
            var failures = 0
            while (System.currentTimeMillis() < expires) {
                delay(if (failures == 0) 3000 else 8000)
                val poll =
                    try {
                        withContext(Dispatchers.IO) { client.poll(platform, challenge) }
                            .also { failures = 0 }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: IllegalArgumentException) {
                        throw error
                    } catch (error: Exception) {
                        failures++
                        status =
                            if (challenge.authorized) "已授权，正在重试读取账号资料…"
                            else if (error is IOException) "网络暂时中断，正在重试…"
                            else error.message?.take(100) ?: "平台暂时无响应，正在重试…"
                        if (failures >= 5) throw error
                        continue
                    }
                when (poll.stage) {
                    LoginStage.WAITING -> status = "等待扫码"
                    LoginStage.CONFIRMING -> status = "已扫码，请在平台 App 内确认"
                    LoginStage.EXPIRED -> {
                        status = "二维码已过期，请重新获取"
                        retry = true
                        return@LaunchedEffect
                    }
                    LoginStage.AUTHORIZED -> {
                        ensureActive()
                        status = "正在保存账号…"
                        connected(requireNotNull(poll.account))
                        return@LaunchedEffect
                    }
                }
            }
            status = if (challenge.authorized) "已授权，暂时无法读取资料，请重试" else "二维码已过期，请重新获取"
            retry = true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            status =
                if (error is IOException) "网络连接失败，请重试"
                else error.message?.take(100) ?: "暂时无法连接平台，请重试"
            retry = true
        }
    }
    Dialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val colors = MiuixTheme.colorScheme
        Column(
            Modifier.padding(24.dp)
                .widthIn(max = 380.dp)
                .fillMaxWidth()
                .heightIn(max = 580.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(colors.surfaceContainer)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PlatformMark(platform)
                UiText(
                    "连接${platform.label}",
                    Modifier.weight(1f).padding(start = 12.dp),
                    size = 19,
                    weight = FontWeight.SemiBold,
                )
                MiuixIconButton(
                    R.drawable.ic_nav_close,
                    "关闭登录",
                    diameter = 44.dp,
                    onClick = dismiss,
                )
            }
            Spacer(Modifier.height(24.dp))
            val qr = bitmap
            if (qr != null && !retry)
                Image(
                    qr.asImageBitmap(),
                    "${platform.label}登录二维码",
                    Modifier.size(204.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White)
                        .padding(8.dp),
                )
            else
                Box(
                    Modifier.size(204.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.tertiaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    UiIcon(platform.icon, colors.primary)
                }
            Spacer(Modifier.height(20.dp))
            UiText(status, size = 14, weight = FontWeight.Medium)
            Spacer(Modifier.height(10.dp))
            UiText("用另一台设备扫码，在平台 App 中确认登录。", size = 12, color = colors.onSurfaceVariantSummary)
            Spacer(Modifier.height(22.dp))
            if (retry)
                MiuixButton(
                    if (retainedChallenge?.authorized == true) "重试读取账号" else "重新获取二维码",
                    Modifier.fillMaxWidth(),
                    icon = R.drawable.ic_action_refresh,
                ) {
                    attempt++
                }
            else MiuixButton("取消", Modifier.fillMaxWidth(), accent = false, onClick = dismiss)
        }
    }
}

private fun qrBitmap(value: String): Bitmap {
    val size = 512
    val qr =
        QRCodeWriter()
            .encode(
                value,
                BarcodeFormat.QR_CODE,
                size,
                size,
                mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 2),
            )
    val pixels =
        IntArray(size * size) { index ->
            if (qr[index % size, index / size]) android.graphics.Color.BLACK
            else android.graphics.Color.WHITE
        }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}
