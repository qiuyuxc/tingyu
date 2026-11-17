package com.qiuyuxc.tingyu

import java.io.File
import java.io.IOException

/** Uploads the original file and keeps its filename. */
object FileUploader {
    fun upload(store: Storage, file: File, progress: Storage.UploadProgress? = null): String {
        if (!file.isFile || !file.canRead()) throw IOException("无法读取文件")
        val path = store.pathFor(file.name)
        if (store.usesIndex() && path == "index.json")
            throw IOException("index.json 用于云端列表，请修改文件名后上传")
        if (store.exists(path)) throw IOException("云端已有同名文件，请修改文件名后上传")
        store.upload(path, file, progress)
        if (store.usesIndex()) {
            try {
                store.addToIndex(path)
            } catch (error: Exception) {
                throw IOException("文件已在云端，更新列表失败，请检查 HTTP 存储的 index.json 写入权限", error)
            }
        }
        return path
    }
}
