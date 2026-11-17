package com.qiuyuxc.tingyu

import java.io.File
import java.io.IOException
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/** OpenList/AList file API client. Anonymous access depends on the server configuration. */
class OpenList(baseUrl: String) {
    private val base = baseUrl.trim().trimEnd('/')
    @Volatile private var token = ""

    private data class CachedUrl(val url: String, val expires: Long)

    private val urlCache = ConcurrentHashMap<String, CachedUrl>()

    class OpenListException(message: String, cause: Throwable? = null) : IOException(message, cause)

    data class Entry(val name: String, val isDir: Boolean, val size: Long, val path: String)

    init {
        val url = URL(base)
        require(
            url.protocol in setOf("http", "https") &&
                url.userInfo == null &&
                url.query == null &&
                url.ref == null
        ) {
            "OpenList 地址无效"
        }
    }

    fun baseUrl(): String = base

    fun hasToken(): Boolean = token.isNotEmpty()

    private fun call(path: String, body: JSONObject? = null, auth: Boolean = true): JSONObject {
        val connection = StorageHttp.open(base + path, if (body == null) "GET" else "POST")
        try {
            if (auth && token.isNotEmpty()) connection.setRequestProperty("Authorization", token)
            if (body != null) {
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            StorageHttp.requireSuccess(connection, "请求失败")
            val result = JSONObject(StorageHttp.body(connection.inputStream))
            if (result.optInt("code", -1) != 200)
                throw OpenListException(result.optString("message", "OpenList 请求失败"))
            return result
        } catch (error: OpenListException) {
            throw error
        } catch (error: Exception) {
            throw OpenListException("请求失败：${error.message}", error)
        } finally {
            connection.disconnect()
        }
    }

    fun login(username: String, password: String) {
        val result =
            call(
                "/api/auth/login",
                JSONObject().put("username", username).put("password", password),
                false,
            )
        token = result.optJSONObject("data")?.optString("token").orEmpty()
        if (token.isEmpty()) throw OpenListException("登录成功但未返回令牌")
        urlCache.clear()
    }

    fun list(path: String): List<Entry> {
        val output = ArrayList<Entry>()
        val seen = HashSet<String>()
        var page = 1
        while (true) {
            val body =
                JSONObject()
                    .put("path", path)
                    .put("password", "")
                    .put("page", page)
                    .put("per_page", 1000)
                    .put("refresh", true)
            val data = call("/api/fs/list", body).optJSONObject("data") ?: return output
            val entries = data.optJSONArray("content") ?: JSONArray()
            var added = 0
            for (index in 0 until entries.length()) {
                val file = entries.optJSONObject(index) ?: continue
                val name = file.optString("name")
                if (name.isEmpty()) continue
                val fullPath = joinPath(path, name)
                if (!seen.add(fullPath)) continue
                output += Entry(name, file.optBoolean("is_dir"), file.optLong("size"), fullPath)
                added++
            }
            val total = data.optInt("total", -1)
            if (total >= 0) {
                if (output.size >= total) return output
                if (entries.length() == 0) throw OpenListException("OpenList 列表缺少后续文件")
            } else if (entries.length() < 1000) return output
            if (added == 0) throw OpenListException("OpenList 返回了重复的分页")
            page++
        }
    }

    fun stat(path: String): Entry {
        val data =
            call("/api/fs/get", JSONObject().put("path", path).put("password", ""))
                .optJSONObject("data") ?: throw OpenListException("未返回文件信息")
        return Entry(data.optString("name"), data.optBoolean("is_dir"), data.optLong("size"), path)
    }

    fun rawUrl(path: String): String {
        urlCache[path]
            ?.takeIf { System.currentTimeMillis() < it.expires }
            ?.let {
                return it.url
            }
        val data =
            call("/api/fs/get", JSONObject().put("path", path).put("password", ""))
                .optJSONObject("data")
        val url = data?.optString("raw_url").orEmpty()
        if (url.isEmpty()) throw OpenListException("未返回下载地址")
        urlCache[path] = CachedUrl(url, System.currentTimeMillis() + 300_000)
        return url
    }

    fun readRange(path: String, offset: Long, length: Int): ByteArray {
        val range = StorageHttp.rangeHeader(offset, length)
        if (length == 0) return ByteArray(0)
        for (attempt in 0..1) {
            val connection = StorageHttp.open(rawUrl(path))
            try {
                if (range != null) connection.setRequestProperty("Range", range)
                if (connection.responseCode in setOf(401, 403) && attempt == 0) {
                    urlCache.remove(path)
                    continue
                }
                return StorageHttp.readRange(connection, offset, length)
            } finally {
                connection.disconnect()
            }
        }
        throw OpenListException("读取失败")
    }

    fun upload(remotePath: String, local: File, progress: Storage.UploadProgress? = null): String {
        val connection = StorageHttp.open("$base/api/fs/put", "PUT")
        try {
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.setRequestProperty("File-Path", URLEncoder.encode(remotePath, "UTF-8"))
            connection.setRequestProperty("As-Task", "false")
            if (token.isNotEmpty()) connection.setRequestProperty("Authorization", token)
            StorageHttp.writeFile(connection, local, progress)
            StorageHttp.requireSuccess(connection, "上传失败")
            val response = StorageHttp.body(connection.inputStream)
            if (response.isNotBlank()) {
                val result = JSONObject(response)
                if (result.optInt("code", 200) != 200)
                    throw OpenListException(result.optString("message", "上传失败"))
            }
            urlCache.remove(remotePath)
            return remotePath
        } finally {
            connection.disconnect()
        }
    }

    fun remove(path: String) {
        val body =
            JSONObject()
                .put("dir", parentOf(path))
                .put("names", JSONArray().put(path.substringAfterLast('/')))
        call("/api/fs/remove", body)
        urlCache.remove(path)
    }

    fun mkdir(path: String) {
        call("/api/fs/mkdir", JSONObject().put("path", path))
    }

    fun version(): String =
        call("/api/public/settings", auth = false)
            .optJSONObject("data")
            ?.optString("version")
            .orEmpty()

    companion object {
        fun joinPath(dir: String, name: String): String =
            dir.trimEnd('/') + "/" + StorageHttp.relativePath(name)

        fun parentOf(path: String): String = path.substringBeforeLast('/', "").ifEmpty { "/" }
    }
}
