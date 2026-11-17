package com.qiuyuxc.tingyu

import java.io.File

class OpenListBackend(private val client: OpenList, directory: String = "/") : Storage {
    private val directory =
        directory.trim().trim('/').let {
            if (it.isEmpty()) "/" else "/${StorageHttp.relativePath(it)}"
        }

    override fun describe(): String = "OpenList $directory"

    override fun pathFor(name: String): String {
        val path = StorageHttp.relativePath(name)
        val root = directory.trim('/')
        return if (root.isEmpty() || path.startsWith("$root/")) "/$path" else "$directory/$path"
    }

    override fun list(progress: Storage.Progress?): List<Storage.Item> {
        val entries =
            client.list(directory).map { Storage.Item(it.path, it.name, it.size, it.isDir) }
        progress?.onTotal(entries.size)
        entries.forEach { progress?.onOne() }
        return entries
    }

    override fun sizeOf(path: String): Long = client.stat(pathFor(path)).size

    override fun readRange(path: String, offset: Long, length: Int): ByteArray =
        client.readRange(pathFor(path), offset, length)

    override fun upload(path: String, local: File, progress: Storage.UploadProgress?) {
        client.upload(pathFor(path), local, progress)
    }

    override fun downloadUrl(path: String): String = client.rawUrl(pathFor(path))

    override fun supportsDelete(): Boolean = true

    override fun delete(path: String) {
        client.remove(pathFor(path))
    }
}
