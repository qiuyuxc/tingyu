package com.qiuyuxc.tingyu

import android.content.Context
import android.content.SharedPreferences
import java.io.IOException
import java.net.URI
import org.json.JSONObject

/** Connection preferences for the user's cloud storage. */
object Cloud {
    const val MODE_S3 = "s3"
    const val MODE_OPENLIST = "openlist"
    const val MODE_HTTP = "http"

    const val S3_ENDPOINT = "cloud.s3.endpoint"
    const val S3_BUCKET = "cloud.s3.bucket"
    const val S3_ACCESS_KEY = "cloud.s3.accessKey"
    const val S3_SECRET_KEY = "cloud.s3.secretKey"
    const val S3_REGION = "cloud.s3.region"
    const val S3_PUBLIC_BASE = "cloud.s3.publicBase"
    const val S3_PREFIX = "cloud.s3.prefix"
    const val HTTP_BASE = "cloud.http.base"
    const val OPENLIST_URL = "cloud.openlist.url"
    const val OPENLIST_USER = "cloud.openlist.user"
    const val OPENLIST_PASSWORD = "cloud.openlist.password"
    const val OPENLIST_DIR = "cloud.openlist.directory"

    private const val KEY_ID = "keyId"
    private const val OLD_KEYS = "oldKeys"
    private const val CURRENT_KEY = "cloudKey"
    private const val LEGACY_IMPORTED_KEYS = "music.encryption.keys"

    class CloudException(message: String, cause: Throwable) : Exception(message, cause)

    private val fields =
        setOf(
            S3_ENDPOINT,
            S3_BUCKET,
            S3_ACCESS_KEY,
            S3_SECRET_KEY,
            S3_REGION,
            S3_PUBLIC_BASE,
            S3_PREFIX,
            HTTP_BASE,
            OPENLIST_URL,
            OPENLIST_USER,
            OPENLIST_PASSWORD,
            OPENLIST_DIR,
        )

    private fun preferences(context: Context) =
        context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)

    @JvmStatic fun passphrase(context: Context): String = readKeyRing(preferences(context)).current

    @JvmStatic fun newMasterKeyExport(): String = ChunkSealKeys.generate()

    class EncryptionKey(@JvmField val masterKey: ByteArray, @JvmField val keyId: Int)

    @JvmStatic
    @Synchronized
    fun encryptionKey(context: Context): EncryptionKey {
        val stored = preferences(context)
        val before = readKeyRing(stored)
        val after = before.ensureMasterKey()
        if (after != before) saveKeyRing(stored, after)
        return EncryptionKey(ChunkSealKeys.decode(after.current), after.currentId)
    }

    /** The original key ring retains the previous credential when the current key changes. */
    @JvmStatic
    @Synchronized
    fun setPassphrase(context: Context, next: String?) {
        val stored = preferences(context)
        val before = readKeyRing(stored)
        val after = before.withCurrent(next.orEmpty())
        if (after != before) saveKeyRing(stored, after)
    }

    @JvmStatic
    @Synchronized
    fun addOldKey(context: Context, value: String?) {
        if (value.isNullOrEmpty()) return
        val stored = preferences(context)
        val before = readKeyRing(stored)
        val after = before.importOld(value)
        if (after != before) saveKeyRing(stored, after)
    }

    @JvmStatic
    fun oldKeyCount(context: Context): Int =
        keyCandidates(context, 0).filter { it != passphrase(context) }.distinct().size

    @JvmStatic
    fun keyCandidates(context: Context, keyId: Int): List<String> {
        val stored = preferences(context)
        return (readKeyRing(stored).candidates(keyId) + legacyImports(stored).forKeyId(keyId))
            .distinct()
    }

    @JvmStatic
    fun keys(context: Context): Ncmx.Keys {
        val stored = preferences(context)
        val ring = readKeyRing(stored)
        val legacyPasswords = legacyImports(stored).forKeyId(0)
        return object : Ncmx.Keys {
            override fun forKeyId(keyId: Int): List<String> =
                (ring.candidates(keyId).filterNot(ChunkSealKeys::isExport) + legacyPasswords)
                    .distinct()

            override fun forMasterKeyId(keyId: Int): List<ByteArray?> =
                ChunkSealKeys.rawCandidates(ring.candidates(keyId))
        }
    }

    /** Existing public imports are migrated without changing their exact password text or type. */
    @Synchronized
    private fun readKeyRing(stored: SharedPreferences): CloudKeyRing {
        try {
            val before =
                CloudKeyRing.fromStored(
                    stored.getString(CURRENT_KEY, ""),
                    stored.getInt(KEY_ID, 0),
                    stored.getString(OLD_KEYS, ""),
                )
            val after =
                legacyImports(stored).entries.fold(before) { ring, entry ->
                    // Empty and explicitly tagged passwords keep their legacy type when reading.
                    if (
                        entry.masterKey ||
                            entry.value.isNotEmpty() && !ChunkSealKeys.isExport(entry.value)
                    )
                        ring.importOld(entry.value)
                    else ring
                }
            if (after != before) saveKeyRing(stored, after)
            return after
        } catch (error: Exception) {
            throw CloudException("本机解密凭据格式不正确: " + error.message, error)
        }
    }

    private fun legacyImports(stored: SharedPreferences): ImportedMusicKeys =
        ImportedMusicKeys.parse(stored.getString(LEGACY_IMPORTED_KEYS, "[]") ?: "[]")

    /** Match the original atomic save and rollback behavior before enabling a new key. */
    private fun saveKeyRing(stored: SharedPreferences, ring: CloudKeyRing) {
        val previousCurrent = stored.getString(CURRENT_KEY, "")
        val previousId = stored.getInt(KEY_ID, 0)
        val previousOld = stored.getString(OLD_KEYS, "")
        try {
            val saved =
                stored
                    .edit()
                    .putString(CURRENT_KEY, ring.current)
                    .putInt(KEY_ID, ring.currentId)
                    .putString(OLD_KEYS, ring.serializedOld())
                    .commit()
            if (!saved) throw IOException("写入存储失败")
        } catch (error: Exception) {
            try {
                stored
                    .edit()
                    .putString(CURRENT_KEY, previousCurrent)
                    .putInt(KEY_ID, previousId)
                    .putString(OLD_KEYS, previousOld)
                    .commit()
            } catch (restore: Exception) {
                error.addSuppressed(restore)
            }
            throw CloudException("保存解密凭据失败，未启用新密钥: " + error.message, error)
        }
    }

    internal fun keyConfiguration(context: Context): String {
        val stored = preferences(context)
        val ring = readKeyRing(stored)
        return JSONObject()
            .put(CURRENT_KEY, ring.current)
            .put(KEY_ID, ring.currentId)
            .put(OLD_KEYS, ring.serializedOld())
            .put(LEGACY_IMPORTED_KEYS, stored.getString(LEGACY_IMPORTED_KEYS, "[]"))
            .toString()
    }

    fun enabled(context: Context): Boolean = preferences(context).getBoolean("cloud.enabled", false)

    fun setEnabled(context: Context, value: Boolean) {
        preferences(context).edit().putBoolean("cloud.enabled", value).apply()
    }

    fun mode(context: Context): String =
        preferences(context).getString("cloud.mode", MODE_S3)?.takeIf {
            it in setOf(MODE_S3, MODE_OPENLIST, MODE_HTTP)
        } ?: MODE_S3

    fun setMode(context: Context, value: String) {
        require(value in setOf(MODE_S3, MODE_OPENLIST, MODE_HTTP))
        preferences(context).edit().putString("cloud.mode", value).apply()
    }

    fun get(context: Context, key: String): String {
        require(key in fields)
        val fallback =
            when (key) {
                S3_REGION -> "auto"
                OPENLIST_DIR -> "/"
                else -> ""
            }
        val value = preferences(context).getString(key, fallback).orEmpty()
        return if (key == OPENLIST_PASSWORD || key == S3_SECRET_KEY) value
        else value.trim().ifBlank { fallback }
    }

    fun set(context: Context, key: String, value: String) {
        require(key in fields)
        preferences(context)
            .edit()
            .putString(
                key,
                if (key == OPENLIST_PASSWORD || key == S3_SECRET_KEY) value else value.trim(),
            )
            .apply()
    }

    fun configured(context: Context): Boolean =
        when (mode(context)) {
            MODE_S3 ->
                validUrl(get(context, S3_ENDPOINT)) &&
                    listOf(S3_BUCKET, S3_ACCESS_KEY, S3_SECRET_KEY).all {
                        get(context, it).isNotBlank()
                    } &&
                    (get(context, S3_PUBLIC_BASE).isBlank() ||
                        validUrl(get(context, S3_PUBLIC_BASE)))
            MODE_HTTP -> validUrl(get(context, HTTP_BASE))
            else -> validUrl(get(context, OPENLIST_URL))
        }

    fun description(context: Context): String =
        when (mode(context)) {
            MODE_S3 ->
                "S3 · " +
                    get(context, S3_BUCKET).ifBlank { "尚未配置" } +
                    get(context, S3_PREFIX).trim('/').let { if (it.isBlank()) "" else "/$it" }
            MODE_HTTP -> "HTTP · " + get(context, HTTP_BASE).ifBlank { "尚未配置" }
            else -> "OpenList · " + get(context, OPENLIST_DIR)
        }

    /** Identifies a directory without including account credentials. */
    fun sourceId(context: Context): String =
        when (mode(context)) {
            MODE_S3 ->
                "s3:${get(context, S3_ENDPOINT)}|${get(context, S3_BUCKET)}|${get(context, S3_PREFIX)}"
            MODE_HTTP -> "http:${get(context, HTTP_BASE)}"
            else -> "openlist:${get(context, OPENLIST_URL)}|${get(context, OPENLIST_DIR)}"
        }

    /** Create the selected backend on an IO dispatcher; OpenList may need to log in. */
    fun backend(context: Context): Storage {
        check(configured(context)) { "请先填写完整的云端连接信息" }
        return when (mode(context)) {
            MODE_S3 ->
                S3Backend(
                    get(context, S3_ENDPOINT),
                    get(context, S3_BUCKET),
                    get(context, S3_ACCESS_KEY),
                    get(context, S3_SECRET_KEY),
                    get(context, S3_REGION),
                    get(context, S3_PUBLIC_BASE),
                    get(context, S3_PREFIX),
                )
            MODE_HTTP -> HttpBackend(get(context, HTTP_BASE))
            else ->
                OpenListBackend(
                    OpenList(get(context, OPENLIST_URL)).apply {
                        val username = get(context, OPENLIST_USER)
                        if (username.isNotBlank()) login(username, get(context, OPENLIST_PASSWORD))
                    },
                    get(context, OPENLIST_DIR),
                )
        }
    }

    fun configuration(context: Context): Map<String, *> {
        val stored = preferences(context)
        readKeyRing(stored)
        return stored.all.filterKeys {
            it.startsWith("cloud.") ||
                it in setOf(CURRENT_KEY, KEY_ID, OLD_KEYS, LEGACY_IMPORTED_KEYS)
        }
    }

    internal fun validUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme in setOf("http", "https") &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null &&
            uri.fragment == null &&
            uri.query == null
    }
        .getOrDefault(false)
}
