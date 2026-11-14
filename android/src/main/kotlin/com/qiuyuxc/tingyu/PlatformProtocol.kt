package com.qiuyuxc.tingyu

import java.math.BigInteger
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

internal object PlatformProtocol {
    const val KUGOU_SONG_COMMENT_CODE = "fc4be23b4e972707f36b8a828a93ba8a"

    fun kugouMid(device: String): String = BigInteger(md5(device), 16).toString()

    fun form(values: Map<String, String>): String =
        values.entries.joinToString("&") {
            "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
        }

    fun md5(value: String): String =
        MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8)).joinToString(
            ""
        ) {
            "%02x".format(it)
        }

    fun eapi(path: String, json: String): String {
        val digest = md5("nobody${path}use${json}md5forencrypt")
        val plaintext = "$path-36cd479b6b5-$json-36cd479b6b5-$digest"
        val encrypted =
            Cipher.getInstance("AES/ECB/PKCS5Padding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec("e82ckenh8dichen8".toByteArray(), "AES"))
                doFinal(plaintext.toByteArray(Charsets.UTF_8))
            }
        return "params=" + encrypted.joinToString("") { "%02X".format(it) }
    }

    fun netease(
        path: String,
        data: JSONObject,
        session: String = "",
        device: String = "",
        web: Boolean = false,
        androidClient: Boolean = false,
    ): LoginRequest {
        require(!path.startsWith("/") && !path.contains(".."))
        val cookies = PlatformLoginClient.cookieValues(session)
        data.put("csrf_token", cookies["__csrf"].orEmpty()).put("e_r", false)
        if (web)
            return LoginRequest(
                "https://music.163.com/weapi/$path",
                PlatformLoginClient.weapi(data.toString()),
                session,
            )
        val header =
            JSONObject()
                .put("os", if (androidClient) "android" else "pc")
                .put("appver", if (androidClient) "9.5.61" else "3.1.17.204416")
                .put(
                    "osver",
                    if (androidClient) "14"
                    else "Microsoft-Windows-10-Professional-build-19045-64bit",
                )
                .put("deviceId", device.ifBlank { cookies["deviceId"].orEmpty() })
                .put("versioncode", "140")
                .put("channel", "netease")
                .put("buildver", (System.currentTimeMillis() / 1000).toString())
                .put("resolution", "1920x1080")
                .put("__csrf", cookies["__csrf"].orEmpty())
                .put("requestId", "${System.currentTimeMillis()}_${(1000..9999).random()}")
        listOf("MUSIC_U", "MUSIC_A", "NMTID").forEach { key ->
            cookies[key]?.let { header.put(key, it) }
        }
        data.put("header", header)
        val requestCookie =
            (cookies + header.keys().asSequence().associateWith { header.getString(it) })
                .entries
                .joinToString("; ") { "${it.key}=${it.value}" }
        return LoginRequest(
            "https://interfacepc.music.163.com/eapi/$path",
            eapi("/api/$path", data.toString()),
            requestCookie,
        )
    }

    fun kugouSignature(params: Map<String, String>, body: String = ""): String {
        val salt = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA"
        return md5(
            salt +
                params.toSortedMap().entries.joinToString("") { "${it.key}=${it.value}" } +
                body +
                salt
        )
    }

    fun kugouComment(
        route: String,
        session: JSONObject,
        values: Map<String, String>,
        body: String?,
        now: Long = System.currentTimeMillis(),
    ): LoginRequest {
        val time = (now / 1000).toString()
        val mid = session.optString("mid").ifBlank { kugouMid(session.optString("device")) }
        val salt = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA"
        val keyData = time + mid + if (route == "commentsv3/add") body.orEmpty() else ""
        val parameters =
            linkedMapOf(
                "r" to route,
                "code" to KUGOU_SONG_COMMENT_CODE,
                "kugouid" to session.optString("userid"),
                "clienttoken" to session.optString("token"),
                "ver" to "6",
                "appid" to KugouLite.APP_ID,
                "clientver" to KugouLite.CLIENT_VERSION,
                "mid" to mid,
                "clienttime" to time,
                "uuid" to session.optString("uuid", "-"),
                "dfid" to session.optString("dfid", "-"),
                "key" to md5(KugouLite.APP_ID + salt + KugouLite.CLIENT_VERSION + keyData),
            )
        parameters.putAll(values)
        return LoginRequest(
            "https://gateway.kugou.com/index.php?${form(parameters)}",
            body,
            contentType = "application/json",
            headers = mapOf("x-router" to "m.comment.service.kugou.com"),
        )
    }

    fun kugouCommentLike(
        session: JSONObject,
        commentId: String,
        resourceId: String,
        audioId: String,
        liked: Boolean,
        now: Long = System.currentTimeMillis(),
    ): LoginRequest {
        val time = (now / 1000).toString()
        val mid = session.optString("mid").ifBlank { kugouMid(session.optString("device")) }
        val parameters =
            linkedMapOf(
                "modulecode" to KUGOU_SONG_COMMENT_CODE,
                "timestamp" to time,
                "object" to commentId,
                "childrenid" to resourceId,
                "from" to "1",
                "ver" to "6",
                "assign" to
                    md5(KUGOU_SONG_COMMENT_CODE + time + "949ad1dac503e48ad6e86bb841383485" + mid),
                "kugouid" to session.optString("userid"),
                "clienttoken" to session.optString("token"),
                "userid" to session.optString("userid"),
                "token" to session.optString("token"),
                "appid" to KugouLite.APP_ID,
                "clientver" to KugouLite.CLIENT_VERSION,
                "mid" to mid,
                "clienttime" to time,
                "uuid" to session.optString("uuid", "-"),
                "dfid" to session.optString("dfid", "-"),
                "key" to
                    md5(
                        KugouLite.APP_ID +
                            "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA" +
                            KugouLite.CLIENT_VERSION +
                            time
                    ),
            )
        if (audioId.isNotBlank() && audioId != "0") parameters["mixsongid"] = audioId
        val path = if (liked) "handlelikeV3" else "unlike"
        return LoginRequest("https://gateway.kugou.com/like.service/like/$path?${form(parameters)}")
    }

    fun kugou(
        path: String,
        device: String,
        session: JSONObject,
        values: Map<String, String> = emptyMap(),
        data: JSONObject? = null,
        router: String? = null,
        tracker: Boolean = false,
        host: String = "gateway.kugou.com",
    ): LoginRequest {
        val time = (System.currentTimeMillis() / 1000).toString()
        val mid = session.optString("mid").ifBlank { kugouMid(device) }
        val dfid =
            session.optString("dfid").takeIf { it.matches(Regex("[a-zA-Z0-9]{24}")) }
                ?: if (tracker) UUID.randomUUID().toString().replace("-", "").take(24) else "-"
        val params =
            linkedMapOf(
                "dfid" to dfid,
                "mid" to mid,
                "uuid" to "-",
                "appid" to KugouLite.APP_ID,
                "clientver" to KugouLite.CLIENT_VERSION,
                "clienttime" to time,
            )
        session.optString("token").takeIf { it.isNotBlank() }?.let { params["token"] = it }
        session
            .optString("userid")
            .takeIf { it.isNotBlank() && it != "0" }
            ?.let { params["userid"] = it }
        params.putAll(values)
        if (tracker)
            params["key"] =
                md5(
                    params.getValue("hash") +
                        "185672dd44712f60bb1736df5a377e82" +
                        params.getValue("appid") +
                        mid +
                        session.optString("userid").ifBlank { "0" }
                )
        val body = data?.toString()
        params["signature"] = kugouSignature(params, body.orEmpty())
        val headers =
            linkedMapOf(
                "mid" to mid,
                "dfid" to dfid,
                "clienttime" to time,
                "kg-rc" to "1",
                "kg-thash" to "5d816a0",
                "kg-rec" to "1",
                "kg-rf" to "B9EDA08A64250DEFFBCADDEE00F8F25F",
            )
        router?.let { headers["x-router"] = it }
        return LoginRequest(
            "https://$host$path?${form(params)}",
            body,
            contentType = "application/json",
            headers = headers,
        )
    }
}
