package com.qiuyuxc.tingyu

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import java.io.IOException

/** Application information and locally bundled license texts. */
internal object AboutPage {
    private data class LicenseEntry(
        val title: String,
        val summary: String,
        val files: List<String>,
    )

    private val projectLicense =
        LicenseEntry(
            "GNU GPLv3",
            "听屿 · GPL-3.0-only",
            listOf("Project-NOTICE.txt", "GPL-3.0.txt"),
        )

    private val thirdPartyLicenses =
        listOf(
            LicenseEntry("MIUIX 0.9.4", "界面组件 · Apache 2.0", listOf("Miuix.txt")),
            LicenseEntry(
                "AndroidLiquidGlass",
                "液态玻璃导航 · Apache 2.0",
                listOf("LiquidNavigation-NOTICE.txt", "AndroidLiquidGlass.txt"),
            ),
            LicenseEntry(
                "LrcView",
                "歌词显示 · Apache 2.0",
                listOf("LrcView-NOTICE.txt", "LrcView.txt"),
            ),
            LicenseEntry("Media3 1.11.1", "平台音频播放 · Apache 2.0", listOf("Media3.txt")),
            LicenseEntry("ZXing 3.5.3", "二维码生成 · Apache 2.0 / BSD", listOf("ZXing.txt")),
            LicenseEntry(
                "AndroidX 与 Kotlin",
                "基础组件与运行库 · Apache 2.0",
                listOf("AndroidX-Kotlin-NOTICE.txt", "Apache-2.0.txt"),
            ),
            LicenseEntry(
                "Java 兼容运行库",
                "GPLv2 + Classpath Exception",
                listOf("Desugar-JDK-NOTICE.txt", "Desugar-JDK.txt"),
            ),
        )

    fun create(context: Context, versionName: String?): View =
        UI.column(context).apply {
            addView(identity(context, versionName))
            addView(
                section(context, "听歌与收藏").apply {
                    addView(description(context, "自己的曲库", "播放本地与在线音乐，收藏歌曲、整理歌单，查看听歌记录与排行。"))
                    addView(description(context, "发现音乐", "连接网易云音乐与酷狗概念版，听推荐、找歌曲，同步歌单，也可以参与歌曲评论。"))
                    addView(
                        description(context, "本地音乐", "读取音频标签、专辑封面与本地歌词，将收藏导出为 M3U 歌单。")
                    )
                }
            )
            addView(
                section(context, "开源与致谢").apply {
                    addView(
                        MiuixPreferenceView(context, "开源协议", "GPLv3 · 查看协议全文") {
                            showLicense(context, projectLicense)
                        }
                    )
                    addView(
                        MiuixPreferenceView(context, "第三方致谢", "查看组件许可、版权与来源") {
                            showThirdPartyLicenses(context)
                        }
                    )
                    addView(
                        UI.muted(context, "听屿遵循 GPLv3 开源协议。你可以依协议使用、修改和分发，第三方组件保留各自许可。").apply {
                            setPadding(UI.dp(16), UI.dp(4), UI.dp(16), UI.dp(16))
                        }
                    )
                }
            )
        }

    private fun identity(context: Context, versionName: String?): View =
        UI.column(context).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(UI.dp(16), UI.dp(12), UI.dp(16), UI.dp(28))
            addView(
                ImageView(context).apply {
                    setImageResource(R.mipmap.ic_launcher)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(UI.dp(80), UI.dp(80)),
            )
            addView(
                UI.text(
                        context,
                        context.getString(R.string.app_name),
                        26f,
                        Theme.p().ink,
                        Typeface.BOLD,
                    )
                    .apply {
                        gravity = Gravity.CENTER
                        setPadding(0, UI.dp(16), 0, UI.dp(6))
                    }
            )
            val edition = "开源版"
            addView(
                UI.muted(context, "版本 ${versionName ?: "未知"} · $edition").apply {
                    gravity = Gravity.CENTER
                }
            )
            addView(
                UI.body(context, "听歌、收藏，整理喜欢的音乐。").apply {
                    gravity = Gravity.CENTER
                    setPadding(0, UI.dp(12), 0, 0)
                }
            )
        }

    private fun section(context: Context, title: String): LinearLayout =
        UI.column(context).apply {
            UI.applySurface(this, UI.RADIUS_CARD.toFloat())
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = UI.dp(12) }
            addView(
                UI.text(context, title, 15f, Theme.p().ink, Typeface.BOLD).apply {
                    setPadding(UI.dp(16), UI.dp(16), UI.dp(16), UI.dp(6))
                }
            )
        }

    private fun description(context: Context, title: String, summary: String): View =
        UI.column(context).apply {
            setPadding(UI.dp(16), UI.dp(10), UI.dp(16), UI.dp(14))
            addView(UI.strong(context, title))
            addView(UI.muted(context, summary).apply { setPadding(0, UI.dp(5), 0, 0) })
        }

    private fun showThirdPartyLicenses(context: Context) {
        val content = UI.column(context)
        thirdPartyLicenses.forEach { entry ->
            content.addView(
                MiuixPreferenceView(context, entry.title, entry.summary) {
                    showLicense(context, entry)
                }
            )
        }
        content.addView(
            MiuixPreferenceView(context, "完整来源说明", "组件版本、修改说明与协议参考项目") {
                showLicense(
                    context,
                    LicenseEntry("第三方来源说明", "听屿 · 第三方组件与参考资料", listOf("ThirdParty-NOTICE.txt")),
                )
            }
        )
        Sheet.of(context, "第三方致谢").size(SheetSize.EXPANDED).content(content).show()
    }

    private fun showLicense(context: Context, entry: LicenseEntry) {
        val text =
            try {
                entry.files.joinToString("\n\n") { name ->
                    context.assets.open("licenses/$name").bufferedReader(Charsets.UTF_8).use {
                        it.readText()
                    }
                }
            } catch (_: IOException) {
                Toast.makeText(context, "协议文件读取失败", Toast.LENGTH_LONG).show()
                return
            }
        val content = UI.column(context)
        content.addView(UI.muted(context, entry.summary).apply { setPadding(0, 0, 0, UI.dp(16)) })
        content.addView(UI.body(context, text).apply { setTextIsSelectable(true) })
        Sheet.of(context, entry.title).size(SheetSize.EXPANDED).content(content).show()
    }
}
