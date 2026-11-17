package com.qiuyuxc.tingyu

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal object StorageHttp {
    private val currentRequests = ThreadLocal<Requests>()

    /** Owns connections for one cancellable operation, including its pagination requests. */
    class Requests {
        private val connections = HashSet<HttpURLConnection>()
        private var cancelled = false

        internal fun register(connection: HttpURLConnection) {
            synchronized(this) {
                if (cancelled) {
                    connection.disconnect()
                    throw IOException("操作已取消")
                }
                connections += connection
            }
        }

        internal fun release(connection: HttpURLConnection) {
            synchronized(this) { connections.remove(connection) }
        }

        fun <T> run(block: () -> T): T {
            val previous = currentRequests.get()
            currentRequests.set(this)
            try {
                return block()
            } finally {
                if (previous == null) currentRequests.remove() else currentRequests.set(previous)
            }
        }

        fun cancel() {
            val active = synchronized(this) {
                cancelled = true
                connections.toList().also { connections.clear() }
            }
            active.forEach { it.disconnect() }
        }

    }

    fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8")
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~")

    fun encodePath(value: String): String = value.split('/').joinToString("/") { encode(it) }

    fun relativePath(value: String): String {
        val path = value.trimStart('/')
        require(path.isNotEmpty() && path.split('/').none { it == "." || it == ".." }) {
            "文件路径无效"
        }
        require(path.none { it.code < 32 || it.code == 127 }) { "文件路径无效" }
        return path
    }

    fun open(url: String, method: String = "GET"): HttpURLConnection {
        val parsed = URL(url)
        require(parsed.protocol == "https" || parsed.protocol == "http") { "请填写 HTTP 或 HTTPS 地址" }
        return (parsed.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 30_000
            readTimeout = if (method == "PUT") 600_000 else 30_000
            instanceFollowRedirects = method == "GET" || method == "HEAD"
            setRequestProperty("User-Agent", "Tingyu")
            currentRequests.get()?.register(this)
        }
    }

    fun disconnect(connection: HttpURLConnection) {
        try {
            connection.disconnect()
        } finally {
            currentRequests.get()?.release(connection)
        }
    }

    fun body(input: InputStream?, limit: Int = 8 shl 20): String {
        if (input == null) return ""
        return input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (output.size() + count > limit) throw IOException("服务器响应过大")
                output.write(buffer, 0, count)
            }
            output.toString("UTF-8")
        }
    }

    fun requireSuccess(connection: HttpURLConnection, operation: String) {
        val code = connection.responseCode
        if (code !in 200..299) {
            val detail = runCatching { body(connection.errorStream, 4096) }.getOrDefault("")
            throw IOException(
                "$operation HTTP $code" + if (detail.isEmpty()) "" else ": ${detail.take(200)}"
            )
        }
    }

    fun writeFile(connection: HttpURLConnection, local: File, progress: Storage.UploadProgress?) {
        require(local.isFile && local.canRead()) { "无法读取文件" }
        val total = local.length()
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(total)
        local.inputStream().buffered(1 shl 16).use { input ->
            connection.outputStream.use { output ->
                val buffer = ByteArray(1 shl 16)
                var done = 0L
                progress?.onBytes(0, total)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw IOException("上传已取消")
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    done += count
                    progress?.onBytes(done, total)
                }
            }
        }
    }

    fun rangeHeader(offset: Long, length: Int): String? {
        require(offset >= 0 && length >= -1) { "读取范围无效" }
        return when {
            length == -1 && offset == 0L -> null
            length == -1 -> "bytes=$offset-"
            length == 0 -> null
            else -> {
                val end = Math.addExact(offset, length.toLong() - 1)
                "bytes=$offset-$end"
            }
        }
    }

    fun readRange(connection: HttpURLConnection, offset: Long, length: Int): ByteArray {
        requireSuccess(connection, "读取失败")
        val fullResponse = connection.responseCode == 200
        connection.inputStream.buffered(1 shl 16).use { input ->
            if (fullResponse) {
                var left = offset
                while (left > 0) {
                    val skipped = input.skip(left)
                    if (skipped > 0) left -= skipped
                    else if (input.read() < 0) return ByteArray(0) else left--
                }
            } else {
                val start =
                    connection
                        .getHeaderField("Content-Range")
                        ?.substringAfter("bytes ")
                        ?.substringBefore('-')
                        ?.toLongOrNull()
                if (start != offset) throw IOException("服务器返回了错误的读取范围")
            }
            val limit = if (length < 0) 64 shl 20 else length
            val output = ByteArrayOutputStream(minOf(limit, 1 shl 16))
            val buffer = ByteArray(1 shl 16)
            while (output.size() < limit) {
                val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size()))
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            if (length < 0 && output.size() == limit && input.read() >= 0) {
                throw IOException("响应过大，请分段读取")
            }
            return output.toByteArray()
        }
    }

    fun size(connection: HttpURLConnection): Long {
        if (
            connection.responseCode == 416 &&
                connection.getHeaderField("Content-Range") == "bytes */0"
        )
            return 0L
        requireSuccess(connection, "读取文件大小失败")
        if (connection.responseCode == 206) {
            return connection
                .getHeaderField("Content-Range")
                ?.substringAfterLast('/')
                ?.toLongOrNull() ?: throw IOException("服务器未返回文件大小")
        }
        val length = connection.contentLengthLong
        if (length < 0) throw IOException("服务器未返回文件大小")
        return length
    }
}
