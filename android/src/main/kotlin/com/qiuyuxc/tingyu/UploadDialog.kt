package com.qiuyuxc.tingyu

import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

/** Uploads one batch at a time and owns its temporary document copies. */
internal class UploadDialog(private val act: MainActivity, private val uploaded: () -> Unit) {
    private sealed interface Source {
        data class Local(val file: File) : Source

        data class Document(val uri: Uri) : Source
    }

    private var job: Job? = null
    private var sheet: Sheet.Builder? = null

    fun documents(uris: List<Uri>) = start(uris.distinct().map { Source.Document(it) })

    fun local(file: File) = start(listOf(Source.Local(file)))

    fun busy(): Boolean = job?.isCompleted == false

    fun dismiss() {
        job?.cancel()
        sheet?.dismiss()
        sheet = null
    }

    private fun start(sources: List<Source>) {
        if (sources.isEmpty()) return
        if (busy()) {
            Toast.makeText(act, "已有文件正在上传，请稍候。", Toast.LENGTH_SHORT).show()
            return
        }
        if (!Cloud.enabled(act) || !Cloud.configured(act)) {
            act.openSettings("cloud")
            return
        }
        sheet?.dismiss()
        val destination = Cloud.configuration(act)
        var closed = false
        val status = UI.strong(act, "准备上传 ${sources.size} 个文件")
        val detail = UI.muted(act, "保留原文件名，上传至 ${Cloud.description(act)}")
        val progress =
            ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000
                isIndeterminate = true
            }
        val result = UI.muted(act, "")
        val body =
            UI.column(act).apply {
                addView(status)
                addView(detail)
                addView(
                    progress,
                    LinearLayout.LayoutParams(-1, UI.dp(12)).apply {
                        topMargin = UI.dp(16)
                        bottomMargin = UI.dp(16)
                    },
                )
                addView(result)
            }
        val panel = Sheet.of(act, "上传文件").size(SheetSize.MEDIUM).content(body)
        val batchJob =
            act.lifecycleScope.launch(start = CoroutineStart.LAZY) {
                val finished = mutableListOf<String>()
                var successful = 0
                try {
                    val store = runInterruptible(Dispatchers.IO) { Cloud.backend(act) }
                    sources.forEachIndexed { index, source ->
                        ensureActive()
                        status.text = "上传 ${index + 1} / ${sources.size}"
                        progress.isIndeterminate = true
                        val name =
                            runInterruptible(Dispatchers.IO) {
                                when (source) {
                                    is Source.Local -> source.file.name
                                    is Source.Document -> documentName(source.uri)
                                }
                            }
                        detail.text = "准备 $name"
                        try {
                            runInterruptible(Dispatchers.IO) {
                                var temporary: File? = null
                                try {
                                    val file =
                                        when (source) {
                                            is Source.Local -> source.file
                                            is Source.Document -> {
                                                val folder =
                                                    File.createTempFile(
                                                        "upload-",
                                                        ".tmp",
                                                        act.cacheDir,
                                                    )
                                                if (!folder.delete() || !folder.mkdir())
                                                    throw IOException("无法准备上传缓存")
                                                temporary = folder
                                                File(folder, name).also { target ->
                                                    val input =
                                                        act.contentResolver.openInputStream(
                                                            source.uri
                                                        ) ?: throw IOException("无法读取所选文件")
                                                    input.use { original ->
                                                        target.outputStream().use { output ->
                                                            val buffer = ByteArray(64 * 1024)
                                                            while (true) {
                                                                ensureActive()
                                                                val count = original.read(buffer)
                                                                if (count < 0) break
                                                                output.write(buffer, 0, count)
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    var lastUpdate = 0L
                                    FileUploader.upload(
                                        store,
                                        file,
                                        Storage.UploadProgress { done, total ->
                                            ensureActive()
                                            val now = SystemClock.elapsedRealtime()
                                            if (done == total || now - lastUpdate >= 100) {
                                                lastUpdate = now
                                                act.runOnUiThread {
                                                    if (!closed) {
                                                        progress.isIndeterminate = total <= 0
                                                        if (total > 0)
                                                            progress.progress =
                                                                ((done.toDouble() / total) * 1000)
                                                                    .toInt()
                                                                    .coerceIn(0, 1000)
                                                        detail.text =
                                                            "$name · ${bytes(done)} / ${bytes(total)}"
                                                    }
                                                }
                                            }
                                        },
                                    )
                                } finally {
                                    temporary?.deleteRecursively()
                                }
                            }
                            ensureActive()
                            successful++
                            finished += "✓ $name"
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            finished += "$name：${error.message ?: "上传失败，请稍后重试。"}"
                        }
                        if (!closed) result.text = finished.joinToString("\n")
                    }
                    if (!closed) {
                        status.text = "上传完成 · $successful / ${sources.size}"
                        detail.text =
                            if (successful == sources.size) "所选文件已上传"
                            else "${sources.size - successful} 个文件未完成，请查看下方结果"
                        progress.isIndeterminate = false
                        progress.progress = 1000
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (!closed) {
                        status.text = "暂时无法上传"
                        detail.text = error.message ?: "请检查云端连接设置。"
                        progress.isIndeterminate = false
                    }
                } finally {
                    if (
                        successful > 0 &&
                            !act.isFinishing &&
                            !act.isDestroyed &&
                            Cloud.configuration(act) == destination
                    )
                        uploaded()
                }
            }
        panel.onDismiss {
            closed = true
            batchJob.cancel()
            if (sheet === panel) sheet = null
        }
        sheet = panel
        job = batchJob
        panel.show()
        batchJob.start()
    }

    private fun documentName(uri: Uri): String {
        val display =
            try {
                act.contentResolver
                    .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            } catch (_: Exception) {
                null
            }
        return safeName(display ?: uri.lastPathSegment.orEmpty())
    }

    private fun safeName(value: String): String {
        var name =
            value
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .filterNot { it.isISOControl() }
                .trim()
                .ifBlank { "文件" }
        if (name == "." || name == "..") name = "文件"
        while (name.toByteArray(Charsets.UTF_8).size > 240) {
            val last = name.offsetByCodePoints(name.length, -1)
            name = name.substring(0, last)
        }
        return name
    }

    private fun bytes(value: Long): String =
        if (value < 0) "未知" else String.format(Locale.US, "%.1f MB", value / 1048576.0)
}
