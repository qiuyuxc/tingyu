package com.qiuyuxc.tingyu

import java.net.URI
import org.json.JSONObject

internal object PlatformAudioTransport {
    const val NETEASE_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36"

    /** Official CDN paths and query strings remain opaque, including their authorization tokens. */
    fun url(platform: MusicPlatform, value: String): String {
        if (platform != MusicPlatform.NETEASE) return value
        val uri = runCatching { URI(value) }.getOrNull() ?: return value
        val host = uri.host?.lowercase().orEmpty()
        val official =
            host == "music.126.net" ||
                host.endsWith(".music.126.net") ||
                host == "music.163.com" ||
                host.endsWith(".music.163.com")
        if (!official || !uri.scheme.equals("http", ignoreCase = true) || uri.userInfo != null)
            return value
        if (uri.port !in listOf(-1, 80, 443)) return value
        val authority =
            if (uri.port == 80) uri.rawAuthority.removeSuffix(":80") else uri.rawAuthority
        return buildString {
            append("https://").append(authority).append(uri.rawPath.orEmpty())
            uri.rawQuery?.let { append('?').append(it) }
            uri.rawFragment?.let { append('#').append(it) }
        }
    }

    fun headers(platform: MusicPlatform): Map<String, String> =
        if (platform == MusicPlatform.NETEASE)
            mapOf("Referer" to "https://music.163.com/", "User-Agent" to NETEASE_USER_AGENT)
        else mapOf("User-Agent" to "Android15-1070-11440-46-0-DiscoveryDRADProtocol-wifi")
}

/**
 * Only a structured error identifier is displayed; response URLs, resources and tokens are omitted.
 */
internal fun audioServerErrorCode(body: ByteArray): String? {
    val text = body.copyOf(minOf(4096, body.size)).toString(Charsets.UTF_8)
    val xml = Regex("""<Code>\s*([A-Za-z0-9_.-]{1,80})\s*</Code>""").find(text)?.groupValues?.get(1)
    if (xml != null) return xml
    val json = runCatching { JSONObject(text) }.getOrNull()
    val code = (json?.opt("code") ?: json?.opt("error_code"))?.toString()
    if (code != null && code.matches(Regex("[A-Za-z0-9_.-]{1,80}"))) return code
    if (text.contains("Only HTTPS requests are allowed", ignoreCase = true)) return "HTTPSRequired"
    return null
}
