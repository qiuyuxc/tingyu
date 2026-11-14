package com.qiuyuxc.tingyu

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** The default local collection preserves insertion order and deduplicates file paths. */
class Playlist private constructor(context: Context) :
    SQLiteOpenHelper(context, "playlist.db", null, 1) {
    class Entry internal constructor(
        @JvmField val path: String,
        @JvmField val title: String,
        @JvmField val artist: String,
        @JvmField val album: String,
        @JvmField val addedAt: Long,
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE tracks (path TEXT PRIMARY KEY,title TEXT,artist TEXT,album TEXT,added_at INTEGER)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun add(song: Song?): Boolean {
        val target = requireNotNull(song)
        val path = requireNotNull(target.file).absolutePath
        val values = ContentValues().apply {
            put("path", path); put("title", target.title); put("artist", target.artist)
            put("album", target.album); put("added_at", System.currentTimeMillis())
        }
        val added = writableDatabase.insertWithOnConflict("tracks", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
        if (added) changes.incrementAndGet()
        return added
    }
    fun remove(path: String?) {
        writableDatabase.delete("tracks", "path = ?", arrayOf(path))
        changes.incrementAndGet()
    }
    fun clear() {
        writableDatabase.delete("tracks", null, null)
        changes.incrementAndGet()
    }
    fun size(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM tracks", null).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }
    fun entries(): MutableList<Entry?> = readableDatabase.query(
        "tracks", arrayOf("path", "title", "artist", "album", "added_at"),
        null, null, null, null, "added_at ASC,rowid ASC",
    ).use { cursor ->
        mutableListOf<Entry?>().apply {
            while (cursor.moveToNext()) add(Entry(cursor.getString(0), cursor.getString(1).orEmpty(),
                cursor.getString(2).orEmpty(), cursor.getString(3).orEmpty(), cursor.getLong(4)))
        }
    }
    fun exportM3u(out: File?): Int {
        val list = entries().filterNotNull()
        val target = requireNotNull(out)
        target.parentFile?.let { require(it.isDirectory || it.mkdirs()) { "无法创建歌单目录" } }
        val pending = File.createTempFile("playlist-", ".m3u", target.absoluteFile.parentFile)
        try {
            pending.writeText(M3u.write(list.map { M3u.Track(it.path, it.title, it.artist) as M3u.Track? }.toMutableList()))
            check(pending.renameTo(target)) { "无法替换歌单文件" }
        } finally { pending.delete() }
        return list.size
    }
    companion object {
        private val changes = AtomicLong()
        private var instance: Playlist? = null
        fun revision(): Long = changes.get()
        @JvmStatic @Synchronized fun get(context: Context?): Playlist =
            instance ?: Playlist(requireNotNull(context).applicationContext).also { instance = it }
        @JvmStatic fun m3uFile(context: Context?): File = File(Dirs.out(context), "默认歌单.m3u")
        @JvmStatic fun exportM3uFor(context: Context?): Int = get(context).exportM3u(m3uFile(context))
        @JvmStatic fun syncM3u(context: Context?) {
            if (Prefs.exportM3u(context)) runCatching { exportM3uFor(context) }
        }
    }
}
