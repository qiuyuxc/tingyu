package com.qiuyuxc.tingyu

import android.content.SharedPreferences
import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** The connected directory contains original files and shares the library's navigation. */
internal class CloudFilesPage(private val act: MainActivity, state: Bundle? = null) :
    MusicRouteView(act) {
    private var refresh by mutableIntStateOf(0)
    private var requestGeneration = 0L
    private var configuration by mutableStateOf(Cloud.configuration(act))
    private var listIndex = state?.getInt("list.index") ?: 0
    private var listOffset = state?.getInt("list.offset") ?: 0
    private val preferences = act.getSharedPreferences(MainActivity.PREFS, 0)
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        configuration = Cloud.configuration(act)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        configuration = Cloud.configuration(act)
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    override fun onDetachedFromWindow() {
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
        super.onDetachedFromWindow()
    }

    fun refresh() {
        refresh++
    }

    override fun saveRoute() =
        Bundle().apply {
            putString("kind", "cloud")
            putInt("list.index", listIndex)
            putInt("list.offset", listOffset)
        }

    @Composable
    override fun Render() {
        val colors = MiuixTheme.colorScheme
        val enabled = remember(configuration) { Cloud.enabled(act) }
        val configured = remember(configuration) { Cloud.configured(act) }
        var files by remember { mutableStateOf<List<Storage.Item>>(emptyList()) }
        var loading by remember { mutableStateOf(false) }
        var problem by remember { mutableStateOf<String?>(null) }
        val list = rememberLazyListState(listIndex, listOffset)
        LaunchedEffect(list) {
            snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
                .collect { (index, offset) ->
                    listIndex = index
                    listOffset = offset
                }
        }
        LaunchedEffect(configuration, refresh) {
            val request = ++requestGeneration
            files = emptyList()
            problem = null
            if (!enabled || !configured) {
                loading = false
                return@LaunchedEffect
            }
            loading = true
            try {
                val loaded =
                    storageIo {
                        val storage = Cloud.backend(act)
                        ensureActive()
                        storage
                            .list()
                            .filterNot { it.isDirectory }
                            .distinctBy { it.path }
                            .sortedBy { it.name.lowercase() }
                    }
                if (request == requestGeneration) files = loaded
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (request == requestGeneration) problem = error.message ?: "文件列表读取失败，请稍后重试。"
            } finally {
                if (request == requestGeneration) loading = false
            }
        }
        Column(Modifier.fillMaxSize().background(colors.surface)) {
            PageHeader(
                "云端文件",
                subtitle = Cloud.description(act),
                back = { act.closeMusicRoute() },
            ) {
                MiuixIconButton(R.drawable.ic_ent_gear, "云端连接设置") { act.openSettings("cloud") }
                MiuixIconButton(R.drawable.ic_action_refresh, "刷新文件列表") { refresh++ }
            }
            LazyColumn(
                state = list,
                modifier = Modifier.weight(1f),
                contentPadding =
                    PaddingValues(
                        start = UI.pageInset(act).dp,
                        end = UI.pageInset(act).dp,
                        top = 14.dp,
                        bottom = UI.CONTENT_BOTTOM.dp + PageInsets.bottom,
                    ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!enabled || !configured) {
                    item {
                        MusicStatus(
                            "连接自己的存储",
                            "连接 S3、OpenList 或 HTTP 存储后，可以上传本地文件并查看云端目录。",
                            "设置云端连接",
                        ) {
                            act.openSettings("cloud")
                        }
                    }
                } else {
                    item {
                        MiuixButton("上传文件", Modifier.fillMaxWidth(), R.drawable.ic_ent_cloud) {
                            act.pickCloudFiles()
                        }
                    }
                    item {
                        MusicSectionTitle(if (loading) "正在读取文件…" else "${files.size} 个文件")
                    }
                    if (problem != null) {
                        item { MusicStatus("暂时无法读取", problem.orEmpty(), "重试") { refresh++ } }
                    } else if (!loading && files.isEmpty()) {
                        item { MusicStatus("还没有文件", "上传文件后，它们会显示在这里。") }
                    }
                    items(files, key = { it.path }) { file ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable(onClickLabel = "查看${file.name}") { details(file) }
                                .padding(vertical = 14.dp, horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            UiIcon(R.drawable.ic_ent_cloud, colors.primary, Modifier.size(30.dp))
                            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                UiText(
                                    file.name,
                                    size = 15,
                                    weight = FontWeight.Medium,
                                    maxLines = 2,
                                )
                                UiText(
                                    fileSize(file.size),
                                    size = 12,
                                    color = colors.onSurfaceVariantSummary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun details(file: Storage.Item) {
        Sheet.of(act, file.name)
            .size(SheetSize.COMPACT)
            .content(
                UI.muted(act, "${fileSize(file.size)}\n${file.path}").apply {
                    setTextIsSelectable(true)
                }
            )
            .show()
    }

    private fun fileSize(bytes: Long): String =
        when {
            bytes < 0 -> "大小未知"
            bytes < 1024 -> "$bytes B"
            bytes < 1048576 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
            else -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
        }
}
