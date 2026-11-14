package com.qiuyuxc.tingyu

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/** Only audible time counts; pauses, seeks and quality reloads keep the same listening session. */
internal class ListeningSession {
    private var previousTime: Long? = null
    private var previouslyPlaying = false
    private var heard = 0L
    private var counted = false

    fun reset() {
        previousTime = null
        previouslyPlaying = false
        heard = 0
        counted = false
    }

    fun sample(playing: Boolean, now: Long, duration: Long): Boolean {
        previousTime?.let { if (previouslyPlaying) heard += (now - it).coerceAtLeast(0) }
        previousTime = now
        previouslyPlaying = playing
        val threshold =
            if (duration > 0) minOf(30_000L, (duration / 2).coerceAtLeast(1)) else 30_000L
        if (!counted && heard >= threshold) {
            counted = true
            return true
        }
        return false
    }
}

/** Persist enough public song metadata to reopen history after process death. */
internal data class HeardSong(
    val key: String,
    val title: String,
    val artist: String,
    val album: String,
    val platform: MusicPlatform? = null,
    val id: String = "",
    val cover: String = "",
    val duration: Long = 0,
    val albumId: String = "0",
    val audioId: String = "0",
    val fileId: String = "",
    val filePath: String = "",
    val access: TrackAccess = TrackAccess.UNKNOWN,
) {
    fun song(): Song =
        if (platform != null) {
            Song(
                PlatformTrack(
                    platform,
                    id,
                    title,
                    artist,
                    album,
                    cover,
                    duration,
                    albumId,
                    audioId,
                    fileId,
                    access,
                )
            )
        } else {
            Song(File(filePath.ifBlank { key })).also {
                it.title = title
                it.artist = artist
                it.album = album
                it.durationSec = (duration / 1000).toInt()
            }
        }

    fun json(): JSONObject =
        JSONObject().apply {
            put("key", key)
            put("title", title)
            put("artist", artist)
            put("album", album)
            put("platform", platform?.id ?: JSONObject.NULL)
            put("id", id)
            put("cover", cover)
            put("duration", duration)
            put("albumId", albumId)
            put("audioId", audioId)
            put("fileId", fileId)
            put("filePath", filePath)
            put("access", access.name)
        }

    companion object {
        fun from(song: Song): HeardSong {
            val track = song.platformTrack
            return HeardSong(
                song.key,
                song.title,
                song.artist,
                song.album,
                track?.platform,
                track?.id.orEmpty(),
                track?.cover.orEmpty(),
                track?.durationMs?.takeIf { it > 0 } ?: song.durationSec * 1000L,
                track?.albumId ?: "0",
                track?.audioId ?: "0",
                track?.fileId.orEmpty(),
                song.file?.absolutePath.orEmpty(),
                track?.access ?: TrackAccess.UNKNOWN,
            )
        }

        fun from(json: JSONObject): HeardSong =
            HeardSong(
                json.getString("key"),
                json.getString("title"),
                json.optString("artist"),
                json.optString("album"),
                MusicPlatform.fromId(json.optString("platform")),
                json.optString("id"),
                json.optString("cover"),
                json.optLong("duration"),
                json.optString("albumId", "0"),
                json.optString("audioId", "0"),
                json.optString("fileId"),
                json.optString("filePath"),
                runCatching { TrackAccess.valueOf(json.optString("access")) }
                    .getOrDefault(TrackAccess.UNKNOWN),
            )
    }
}

internal data class ListeningEntry(val track: HeardSong, val plays: Long, val lastPlayed: Long)

internal object ListeningHistory {
    private var database: HistoryDatabase? = null
    @Volatile private var recent = emptyList<ListeningEntry>()
    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "listening-history").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    var revision by mutableIntStateOf(0)
        private set

    @Synchronized
    fun init(context: Context) {
        if (database != null) return
        database = HistoryDatabase(context.applicationContext)
        writer.execute { importLegacy(context.applicationContext) }
    }

    fun record(song: Song, count: Boolean = false, touch: Boolean = false) {
        val snapshot = HeardSong.from(song)
        if (snapshot.key.isBlank()) return
        val at = if (touch) System.currentTimeMillis() else 0
        writer.execute {
            runCatching {
                database?.write(snapshot, if (count) 1 else 0, at)
                recent = database?.entries(false, 20).orEmpty()
            }
                .onSuccess { main.post { revision++ } }
        }
    }

    fun entries(ranking: Boolean = false): List<ListeningEntry> =
        database?.entries(ranking).orEmpty()

    fun cachedRecentSongs(): List<Song> = recent.map { it.track.song() }

    private fun importLegacy(context: Context) {
        val preferences = context.getSharedPreferences("history", Context.MODE_PRIVATE)
        if (preferences.getBoolean("listening.imported", false)) return
        runCatching {
            val raw = JSONArray(preferences.getString("recent", "[]"))
            val saved =
                Playlist.get(context)?.entries().orEmpty().filterNotNull().associateBy { it.path }
            val platformTracks = mutableListOf<Pair<Int, PlatformTrack>>()
            for (index in 0 until raw.length()) {
                val key = raw.optString(index)
                if (key.isBlank()) continue
                val pieces = key.split(':', limit = 3)
                val platform =
                    if (pieces.size == 3 && pieces[0] == "platform") MusicPlatform.fromId(pieces[1])
                    else null
                if (platform != null) {
                    platformTracks += index to PlatformTrack(platform, pieces[2], "歌曲 ${pieces[2]}")
                } else {
                    val entry = saved[key]
                    val song =
                        Song(File(key)).apply {
                            title = entry?.title?.takeIf { it.isNotBlank() } ?: title
                            artist = entry?.artist.orEmpty()
                            album = entry?.album.orEmpty()
                        }
                    database?.write(HeardSong.from(song), 0, -(index + 1L))
                }
            }
            platformTracks.forEach { (index, track) ->
                database?.write(HeardSong.from(Song(track)), 0, -(index + 1L))
            }
            preferences.edit().putBoolean("listening.imported", true).apply()
            recent = database?.entries(false, 20).orEmpty()
            main.post { revision++ }
            // Slow metadata requests must not block the writer's live playback records.
            Thread(
                    {
                        platformTracks
                            .groupBy { it.second.platform }
                            .forEach { (platform, tracks) ->
                                val resolved = runCatching {
                                    PlatformDiscoveryClient.forPlatform(context, platform)
                                        .details(tracks.map { it.second })
                                        .associateBy { it.key }
                                }
                                    .getOrDefault(emptyMap())
                                writer.execute {
                                    runCatching {
                                        tracks.forEach { (index, track) ->
                                            resolved[track.key]?.let {
                                                database?.write(
                                                    HeardSong.from(Song(it)),
                                                    0,
                                                    -(index + 1L),
                                                )
                                            }
                                        }
                                        recent = database?.entries(false, 20).orEmpty()
                                        main.post { revision++ }
                                    }
                                }
                            }
                    },
                    "history-metadata",
                )
                .apply { isDaemon = true }
                .start()
        }
    }

    private class HistoryDatabase(context: Context) :
        SQLiteOpenHelper(context, "listening.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE heard (song_key TEXT PRIMARY KEY, metadata TEXT NOT NULL, plays INTEGER NOT NULL DEFAULT 0, last_played INTEGER NOT NULL DEFAULT 0)"
            )
            db.execSQL("CREATE INDEX heard_recent ON heard(last_played DESC)")
            db.execSQL("CREATE INDEX heard_rank ON heard(plays DESC, last_played DESC)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

        fun write(song: HeardSong, increment: Int, at: Long) {
            val db = writableDatabase
            db.beginTransaction()
            try {
                db.execSQL(
                    "INSERT OR IGNORE INTO heard(song_key,metadata) VALUES(?,?)",
                    arrayOf(song.key, song.json().toString()),
                )
                db.execSQL(
                    "UPDATE heard SET metadata=?, plays=plays+?, last_played=CASE WHEN ?=0 THEN last_played ELSE ? END WHERE song_key=? AND (? >= 0 OR last_played <= 0)",
                    arrayOf<Any>(song.json().toString(), increment, at, at, song.key, at),
                )
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

        fun entries(ranking: Boolean, limit: Int = 0): List<ListeningEntry> {
            val order = if (ranking) "plays DESC,last_played DESC" else "last_played DESC"
            val filter = if (ranking) "plays>0" else "last_played<>0"
            val suffix = if (limit > 0) " LIMIT ?" else ""
            return readableDatabase
                .rawQuery(
                    "SELECT metadata,plays,last_played FROM heard WHERE $filter ORDER BY $order$suffix",
                    if (limit > 0) arrayOf(limit.toString()) else null,
                )
                .use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) runCatching {
                            ListeningEntry(
                                HeardSong.from(JSONObject(cursor.getString(0))),
                                cursor.getLong(1),
                                cursor.getLong(2),
                            )
                        }
                            .getOrNull()
                            ?.let { add(it) }
                    }
                }
        }
    }
}
