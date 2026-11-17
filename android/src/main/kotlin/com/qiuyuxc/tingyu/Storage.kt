package com.qiuyuxc.tingyu

import java.io.File

/** File storage shared by the cloud library and the upload screen. */
interface Storage {
    data class Item(
        val path: String,
        val name: String,
        val size: Long,
        val isDirectory: Boolean = false,
    )

    interface Progress {
        fun onTotal(total: Int)

        fun onOne()
    }

    fun interface UploadProgress {
        fun onBytes(done: Long, total: Long)
    }

    fun describe(): String

    fun list(progress: Progress? = null): List<Item>

    fun pathFor(name: String): String

    fun sizeOf(path: String): Long

    fun exists(path: String): Boolean = list().any { it.path == pathFor(path) }

    fun readRange(path: String, offset: Long, length: Int): ByteArray

    fun upload(path: String, local: File, progress: UploadProgress? = null)

    fun downloadUrl(path: String): String?

    fun supportsDelete(): Boolean

    fun delete(path: String)

    fun usesIndex(): Boolean = false

    fun addToIndex(name: String) {}
}
