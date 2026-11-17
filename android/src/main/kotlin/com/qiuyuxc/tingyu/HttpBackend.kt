package com.qiuyuxc.tingyu

import java.io.File
import java.io.IOException
import java.net.URL
import org.json.JSONArray

/** HTTP file storage. Listing uses index.json; uploads require a server supporting PUT. */
class HttpBackend(baseUrl: String) : Storage {
    private val base = baseUrl.trim().trimEnd('/')

    init {
        val url = URL(base)
        require(
            url.protocol in setOf("http", "https") &&
                url.userInfo == null &&
                url.query == null &&
                url.ref == null
        ) {
            "HTTP 地址无效"
        }
    }

    override fun describe(): String = base

    override fun pathFor(name: String): String = StorageHttp.relativePath(name)

    override fun downloadUrl(path: String): String =
        "$base/${StorageHttp.encodePath(pathFor(path))}"

    private fun indexUrl(): String = "$base/index.json?v=${System.currentTimeMillis()}"

    private fun readIndex(): List<String> {
        val connection = StorageHttp.open(indexUrl())
        try {
            if (connection.responseCode == 404) return emptyList()
            StorageHttp.requireSuccess(connection, "读取云端列表失败")
            val array = JSONArray(StorageHttp.body(connection.inputStream))
            return (0 until array.length())
                .mapNotNull {
                    array
                        .optString(it)
                        .takeIf { name -> name.isNotBlank() }
                        ?.let { name -> pathFor(name) }
                }
                .distinct()
        } finally {
            StorageHttp.disconnect(connection)
        }
    }

    override fun list(progress: Storage.Progress?): List<Storage.Item> {
        val names = readIndex()
        progress?.onTotal(names.size)
        return names.map { name ->
            val size = sizeOf(name)
            progress?.onOne()
            Storage.Item(name, name.substringAfterLast('/'), size)
        }
    }

    override fun sizeOf(path: String): Long {
        val connection =
            StorageHttp.open(downloadUrl(path)).apply { setRequestProperty("Range", "bytes=0-0") }
        try {
            return StorageHttp.size(connection)
        } finally {
            StorageHttp.disconnect(connection)
        }
    }

    override fun exists(path: String): Boolean {
        val connection =
            StorageHttp.open(downloadUrl(path)).apply { setRequestProperty("Range", "bytes=0-0") }
        try {
            if (connection.responseCode == 404) return false
            if (
                connection.responseCode == 416 &&
                    connection.getHeaderField("Content-Range") == "bytes */0"
            )
                return true
            StorageHttp.requireSuccess(connection, "检查云端文件失败")
            return true
        } finally {
            StorageHttp.disconnect(connection)
        }
    }

    override fun readRange(path: String, offset: Long, length: Int): ByteArray {
        val range = StorageHttp.rangeHeader(offset, length)
        if (length == 0) return ByteArray(0)
        val connection =
            StorageHttp.open(downloadUrl(path)).apply {
                if (range != null) setRequestProperty("Range", range)
            }
        try {
            return StorageHttp.readRange(connection, offset, length)
        } finally {
            StorageHttp.disconnect(connection)
        }
    }

    override fun upload(path: String, local: File, progress: Storage.UploadProgress?) {
        put(path, local, progress, onlyIfAbsent = true)
    }

    private fun put(
        path: String,
        local: File,
        progress: Storage.UploadProgress? = null,
        onlyIfAbsent: Boolean,
    ) {
        val connection =
            StorageHttp.open(downloadUrl(path), "PUT").apply {
                setRequestProperty("Content-Type", "application/octet-stream")
                if (onlyIfAbsent) setRequestProperty("If-None-Match", "*")
            }
        try {
            StorageHttp.writeFile(connection, local, progress)
            StorageHttp.requireSuccess(connection, "上传失败")
            connection.inputStream.close()
        } finally {
            StorageHttp.disconnect(connection)
        }
    }

    override fun supportsDelete(): Boolean = false

    override fun delete(path: String) {
        throw IOException("HTTP 直链不提供删除接口")
    }

    override fun usesIndex(): Boolean = true

    override fun addToIndex(name: String) {
        // Shared lock protects concurrent uploads made by this app. A server must coordinate other
        // writers.
        synchronized(indexLock) {
            val names = readIndex().toMutableList()
            val path = pathFor(name)
            if (path in names) return
            names += path
            val temporary = File.createTempFile("tingyu-index-", ".json")
            try {
                temporary.writeText(JSONArray(names).toString(), Charsets.UTF_8)
                put("index.json", temporary, onlyIfAbsent = false)
            } finally {
                temporary.delete()
            }
        }
    }

    companion object {
        private val indexLock = Any()
    }
}
