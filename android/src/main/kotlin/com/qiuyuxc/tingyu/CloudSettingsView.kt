package com.qiuyuxc.tingyu

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import androidx.activity.ComponentDialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Cloud connection settings using the same preference controls as the local library. */
internal class CloudSettingsView(private val act: MainActivity) {
    fun build(): View =
        UI.column(act).apply {
            addView(
                group("云端存储").apply {
                    addView(
                        MiuixSwitchPreferenceView(
                            act,
                            "启用云端存储",
                            "连接自己的存储空间，上传本地文件",
                            Cloud.enabled(act),
                        ) {
                            Cloud.setEnabled(act, it)
                            act.rebuild()
                        }
                    )
                    val modes = listOf(Cloud.MODE_S3, Cloud.MODE_OPENLIST, Cloud.MODE_HTTP)
                    addView(
                        MiuixChoicePreferenceView(
                            act,
                            "连接方式",
                            null,
                            listOf("S3", "OpenList", "HTTP"),
                            modes.indexOf(Cloud.mode(act)),
                        ) {
                            Cloud.setMode(act, modes[it])
                            act.rebuild()
                        }
                    )
                }
            )
            when (Cloud.mode(act)) {
                Cloud.MODE_S3 ->
                    addView(
                        group("S3 连接").apply {
                            addView(hint("连接 S3 兼容存储；文件上传到指定桶和目录。"))
                            addView(
                                field(
                                    "S3 端点",
                                    Cloud.S3_ENDPOINT,
                                    "https://s3.example.com",
                                    url = true,
                                )
                            )
                            addView(field("桶名", Cloud.S3_BUCKET, "音乐文件所在的桶"))
                            addView(field("区域", Cloud.S3_REGION, "例如 auto、us-east-1"))
                            addView(field("目录前缀", Cloud.S3_PREFIX, "可选，例如 music/"))
                            addView(field("Access Key", Cloud.S3_ACCESS_KEY, "访问密钥", secret = true))
                            addView(
                                field("Secret Key", Cloud.S3_SECRET_KEY, "访问密钥密码", secret = true)
                            )
                            addView(field("公开访问地址", Cloud.S3_PUBLIC_BASE, "可选，文件访问域名", url = true))
                        }
                    )
                Cloud.MODE_OPENLIST ->
                    addView(
                        group("OpenList 连接").apply {
                            addView(hint("连接 OpenList 或 AList，使用实例提供的目录与文件接口。"))
                            addView(
                                field(
                                    "服务器地址",
                                    Cloud.OPENLIST_URL,
                                    "https://files.example.com",
                                    url = true,
                                )
                            )
                            addView(field("用户名", Cloud.OPENLIST_USER, "允许匿名访问时可留空"))
                            addView(field("密码", Cloud.OPENLIST_PASSWORD, "登录密码", secret = true))
                            addView(field("云端目录", Cloud.OPENLIST_DIR, "例如 /音乐"))
                        }
                    )
                else ->
                    addView(
                        group("HTTP 连接").apply {
                            addView(hint("填写文件目录的根地址。读取列表使用 index.json；上传需要服务器允许 HTTP PUT。"))
                            addView(
                                field(
                                    "文件根地址",
                                    Cloud.HTTP_BASE,
                                    "https://files.example.com/music",
                                    url = true,
                                )
                            )
                        }
                    )
            }
            addView(
                group("连接检查").apply {
                    val result = hint("检查连接和文件列表。上传所需的写入权限由存储服务配置。")
                    lateinit var test: MiuixActionView
                    test =
                        MiuixActionView(act, "测试连接", accent = false) {
                            if (!Cloud.configured(act)) {
                                result.text = "请先填写完整的云端连接信息"
                                return@MiuixActionView
                            }
                            test.isEnabled = false
                            test.setLabel("连接中…")
                            val configuration = Cloud.configuration(act)
                            act.lifecycleScope.launch {
                                try {
                                    val count =
                                        storageIo {
                                            Cloud.backend(act).list().size
                                        }
                                    if (configuration == Cloud.configuration(act))
                                        result.text = "连接成功 · $count 个文件"
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    if (configuration == Cloud.configuration(act)) {
                                        result.text = "连接失败：${error.message ?: "请检查连接信息"}"
                                    }
                                } finally {
                                    test.isEnabled = true
                                    test.setLabel("测试连接")
                                }
                            }
                        }
                    addView(test.apply { setPadding(UI.dp(16), UI.dp(8), UI.dp(16), UI.dp(8)) })
                    addView(result)
                }
            )
        }

    private fun group(title: String): LinearLayout =
        UI.column(act).apply {
            UI.applySurface(this, UI.RADIUS_CARD.toFloat())
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = UI.dp(12) }
            addView(
                UI.strong(act, title).apply {
                    setPadding(UI.dp(16), UI.dp(14), UI.dp(16), UI.dp(8))
                }
            )
        }

    private fun hint(text: String) =
        UI.muted(act, text).apply {
            setPadding(UI.dp(16), UI.dp(4), UI.dp(16), UI.dp(14))
        }

    private fun field(
        title: String,
        key: String,
        hint: String,
        secret: Boolean = false,
        url: Boolean = false,
    ): View {
        val value = Cloud.get(act, key)
        val summary = if (secret && value.isNotEmpty()) "已填写" else value.ifBlank { hint }
        return MiuixPreferenceView(act, title, summary) { edit(title, key, hint, secret, url) }
    }

    private fun edit(title: String, key: String, hint: String, secret: Boolean, url: Boolean) {
        val dialog = ComponentDialog(act)
        val body =
            UI.column(act).apply {
                background = UI.surface(28f)
                setPadding(UI.dp(24), UI.dp(24), UI.dp(24), UI.dp(24))
            }
        body.addView(UI.strong(act, title))
        val input =
            UI.input(act, hint).apply {
                inputType =
                    when {
                        secret ->
                            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                        url -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                        else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    }
                setText(Cloud.get(act, key))
                setSelection(text.length)
            }
        body.addView(input)
        body.addView(UI.spacer(act, 16))
        body.addView(
            MiuixActionView(act, "保存") {
                val value = input.text.toString().let { if (secret) it else it.trim() }
                if (url && value.isNotBlank() && !Cloud.validUrl(value)) {
                    input.error = "请输入完整的 HTTP 或 HTTPS 地址"
                    return@MiuixActionView
                }
                if (key == Cloud.OPENLIST_DIR && value.isNotBlank() && !value.startsWith('/')) {
                    input.error = "请输入以 / 开头的目录"
                    return@MiuixActionView
                }
                Cloud.set(act, key, value)
                dialog.dismiss()
                act.rebuild()
            }
        )
        dialog.setContentView(body)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                UI.dp(
                    minOf(560, act.resources.configuration.screenWidthDp - 32).coerceAtLeast(240)
                ),
                -2,
            )
        }
    }
}
