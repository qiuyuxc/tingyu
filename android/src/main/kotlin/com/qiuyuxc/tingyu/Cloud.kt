package com.qiuyuxc.tingyu

import android.content.Context
import java.net.URI

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

    fun configuration(context: Context): Map<String, *> =
        preferences(context).all.filterKeys { it.startsWith("cloud.") }

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
