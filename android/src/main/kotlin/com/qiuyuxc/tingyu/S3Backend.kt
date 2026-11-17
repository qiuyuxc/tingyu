package com.qiuyuxc.tingyu

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** S3-compatible object storage with signed requests and optional public playback URLs. */
class S3Backend(
    endpoint: String,
    private val bucket: String,
    accessKey: String,
    secretKey: String,
    region: String = "auto",
    publicBase: String = "",
    prefix: String = "",
) : Storage {
    private val endpoint = endpoint.trim().trimEnd('/')
    private val endpointUrl = URL(this.endpoint)
    private val prefix =
        prefix.trim().trim('/').let { if (it.isEmpty()) "" else "${StorageHttp.relativePath(it)}/" }
    private val publicBase = publicBase.trim().trimEnd('/')
    private val signer = SigV4(accessKey, secretKey, region.ifBlank { "auto" })

    init {
        require(
            endpointUrl.protocol in setOf("http", "https") &&
                endpointUrl.userInfo == null &&
                endpointUrl.query == null &&
                endpointUrl.ref == null
        ) {
            "S3 端点无效"
        }
        require(
            bucket.isNotBlank() &&
                !bucket.contains('/') &&
                accessKey.isNotBlank() &&
                secretKey.isNotBlank()
        ) {
            "请填写桶名和访问密钥"
        }
        if (this.publicBase.isNotEmpty()) {
            val publicUrl = URL(this.publicBase)
            require(
                publicUrl.protocol in setOf("http", "https") &&
                    publicUrl.userInfo == null &&
                    publicUrl.query == null &&
                    publicUrl.ref == null
            ) {
                "公开域名无效"
            }
        }
    }

    override fun describe(): String =
        "S3 $bucket" + if (prefix.isEmpty()) "" else "/${prefix.trimEnd('/')}"

    override fun pathFor(name: String): String {
        val path = StorageHttp.relativePath(name)
        return if (path.startsWith(prefix)) path else prefix + path
    }

    private fun request(
        method: String,
        objectPath: String = "",
        query: Map<String, String> = emptyMap(),
        payloadHash: String = SigV4.EMPTY_HASH,
        headers: Map<String, String> = emptyMap(),
    ): HttpURLConnection {
        val path = endpointUrl.path.trimEnd('/') + "/" + bucket + objectPath
        val queryString =
            query.entries.joinToString("&") {
                "${StorageHttp.encode(it.key)}=${StorageHttp.encode(it.value)}"
            }
        val url =
            "${endpointUrl.protocol}://${endpointUrl.authority}${StorageHttp.encodePath(path)}" +
                if (queryString.isEmpty()) "" else "?$queryString"
        return StorageHttp.open(url, method).apply {
            for ((name, value) in
                signer.signHash(method, endpointUrl.authority, path, query, payloadHash, headers)) {
                setRequestProperty(name, value)
            }
        }
    }

    override fun list(progress: Storage.Progress?): List<Storage.Item> {
        val items = ArrayList<Storage.Item>()
        var token: String? = null
        val seenTokens = HashSet<String>()
        do {
            val query =
                linkedMapOf("list-type" to "2", "max-keys" to "1000", "encoding-type" to "url")
            if (prefix.isNotEmpty()) query["prefix"] = prefix
            if (token != null) query["continuation-token"] = token
            val connection = request("GET", query = query)
            val xml =
                try {
                    StorageHttp.requireSuccess(connection, "列目录失败")
                    StorageHttp.body(connection.inputStream)
                } finally {
                    StorageHttp.disconnect(connection)
                }
            val factory =
                DocumentBuilderFactory.newInstance().apply {
                    isNamespaceAware = true
                    isExpandEntityReferences = false
                    runCatching {
                        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                    }
                    runCatching {
                        setFeature("http://xml.org/sax/features/external-general-entities", false)
                    }
                    runCatching {
                        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                    }
                }
            val builder =
                factory.newDocumentBuilder().apply {
                    setEntityResolver { _, _ -> org.xml.sax.InputSource(java.io.StringReader("")) }
                }
            val root =
                builder.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8))).documentElement
            val encoded = childText(root, "EncodingType") == "url"
            val entries = root.getElementsByTagNameNS("*", "Contents")
            for (index in 0 until entries.length) {
                val entry = entries.item(index) as Element
                val rawKey = childText(entry, "Key")
                val key =
                    if (encoded) URLDecoder.decode(rawKey.replace("+", "%2B"), "UTF-8") else rawKey
                if (key.isEmpty() || !key.startsWith(prefix)) continue
                val directory = key.endsWith('/')
                items +=
                    Storage.Item(
                        key,
                        key.trimEnd('/').substringAfterLast('/'),
                        childText(entry, "Size").toLongOrNull() ?: 0L,
                        directory,
                    )
            }
            token =
                if (childText(root, "IsTruncated") == "true")
                    childText(root, "NextContinuationToken").takeIf { it.isNotEmpty() }
                else null
            if (childText(root, "IsTruncated") == "true" && token == null)
                throw IOException("S3 列表缺少分页标记")
            if (token != null && !seenTokens.add(token)) throw IOException("S3 返回了重复的分页标记")
        } while (token != null)
        progress?.onTotal(items.size)
        items.forEach { progress?.onOne() }
        return items
    }

    override fun downloadUrl(path: String): String? =
        publicBase
            .takeIf { it.isNotEmpty() }
            ?.let { "$it/${StorageHttp.encodePath(pathFor(path))}" }

    private fun publicConnection(path: String, range: String?): HttpURLConnection? =
        downloadUrl(path)?.let { url ->
            StorageHttp.open(url).apply { if (range != null) setRequestProperty("Range", range) }
        }

    override fun sizeOf(path: String): Long {
        val connection = request("HEAD", "/${pathFor(path)}")
        return try {
            StorageHttp.size(connection)
        } finally {
            StorageHttp.disconnect(connection)
        }
    }

    override fun exists(path: String): Boolean {
        val connection = request("HEAD", "/${pathFor(path)}")
        try {
            if (connection.responseCode == 404) return false
            StorageHttp.requireSuccess(connection, "检查云端文件失败")
            return true
        } finally {
            StorageHttp.disconnect(connection)
        }
    }

    override fun readRange(path: String, offset: Long, length: Int): ByteArray {
        val range = StorageHttp.rangeHeader(offset, length)
        if (length == 0) return ByteArray(0)
        val headers = if (range == null) emptyMap() else mapOf("range" to range)
        val connection = request("GET", "/${pathFor(path)}", headers = headers)
        try {
            return StorageHttp.readRange(connection, offset, length)
        } catch (signedError: IOException) {
            val public = publicConnection(path, range) ?: throw signedError
            try {
                return StorageHttp.readRange(public, offset, length)
            } catch (publicError: IOException) {
                publicError.addSuppressed(signedError)
                throw publicError
            } finally {
                StorageHttp.disconnect(public)
            }
        } finally {
            StorageHttp.disconnect(connection)
        }
    }

    override fun upload(path: String, local: File, progress: Storage.UploadProgress?) {
        require(local.isFile && local.canRead()) { "无法读取文件" }
        val digest = MessageDigest.getInstance("SHA-256")
        local.inputStream().buffered(1 shl 16).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw IOException("上传已取消")
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val connection =
            request(
                "PUT",
                "/${pathFor(path)}",
                payloadHash = SigV4.hex(digest.digest()),
                headers =
                    mapOf("content-type" to "application/octet-stream", "if-none-match" to "*"),
            )
        try {
            StorageHttp.writeFile(connection, local, progress)
            StorageHttp.requireSuccess(connection, "上传失败")
            connection.inputStream.close()
        } finally {
            StorageHttp.disconnect(connection)
        }
    }

    override fun supportsDelete(): Boolean = true

    override fun delete(path: String) {
        val connection = request("DELETE", "/${pathFor(path)}")
        try {
            StorageHttp.requireSuccess(connection, "删除失败")
            connection.inputStream.close()
        } finally {
            StorageHttp.disconnect(connection)
        }
    }

    private fun childText(element: Element, name: String): String =
        element.getElementsByTagNameNS("*", name).item(0)?.textContent.orEmpty()
}
