package com.qiuyuxc.tingyu

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.HttpCookie
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

internal data class LoginChallenge(
    val key: String,
    val url: String,
    var cookie: String = "",
    val device: String = "",
    val web: Boolean = false,
    var authorized: Boolean = false,
)

internal enum class LoginStage {
    WAITING,
    CONFIRMING,
    EXPIRED,
    AUTHORIZED,
}

internal data class AuthorizedAccount(val profile: PlatformProfile, val session: String)

internal data class LoginPoll(val stage: LoginStage, val account: AuthorizedAccount? = null)

internal data class LoginRequest(
    val url: String,
    val body: String? = null,
    val cookie: String = "",
    val contentType: String = "application/x-www-form-urlencoded",
    val headers: Map<String, String> = emptyMap(),
) {
    fun validate() {
        val address = URL(url)
        require(address.protocol == "https" && address.userInfo == null && address.port == -1)
        require(
            address.host in
                setOf(
                    "music.163.com",
                    "interfacepc.music.163.com",
                    "login-user.kugou.com",
                    "gateway.kugou.com",
                    "lyrics.kugou.com",
                    "kugouvip.kugou.com",
                )
        )
        require(!cookie.contains('\r') && !cookie.contains('\n'))
        require(cookie.length <= 32768)
        require(
            cookie.isEmpty() || address.host in setOf("music.163.com", "interfacepc.music.163.com")
        )
        require(contentType in setOf("application/json", "application/x-www-form-urlencoded"))
        require(
            headers.keys.all {
                it in
                    setOf(
                        "mid",
                        "dfid",
                        "clienttime",
                        "kg-rc",
                        "kg-thash",
                        "kg-rec",
                        "kg-rf",
                        "x-router",
                        "kg-tid",
                    )
            }
        )
        require(headers.values.all { !it.contains('\r') && !it.contains('\n') })
    }
}

internal data class LoginResponse(val json: JSONObject, val cookies: List<String> = emptyList())

internal fun interface LoginTransport {
    fun execute(request: LoginRequest): LoginResponse
}

/** Direct, first-party QR authorization. No password fields or shared platform cookie jar. */
internal class PlatformLoginClient(
    private val transport: LoginTransport = LoginTransport(::request)
) {
    fun create(platform: MusicPlatform): LoginChallenge =
        when (platform) {
            MusicPlatform.NETEASE -> {
                val device = UUID.randomUUID().toString().replace("-", "")
                var web = false
                val response =
                    try {
                        netease("login/qrcode/unikey", JSONObject().put("type", 3), device = device)
                            .also {
                                require(it.json.optInt("code") == 200)
                            }
                    } catch (_: Exception) {
                        web = true
                        netease(
                            "login/qrcode/unikey",
                            JSONObject().put("type", 3),
                            device = device,
                            web = true,
                        )
                    }
                require(response.json.optInt("code") == 200) { "网易云暂时无法生成登录二维码" }
                val key = response.json.optString("unikey")
                require(key.isNotBlank()) { "网易云返回的二维码无效" }
                LoginChallenge(
                    key,
                    "https://music.163.com/login?codekey=${encode(key)}",
                    mergeCookies("deviceId=$device", response.cookies),
                    device = device,
                    web = web,
                )
            }
            MusicPlatform.KUGOU -> {
                val device = UUID.randomUUID().toString().replace("-", "")
                val response =
                    kugou(
                        "qrcode",
                        device,
                        mapOf(
                            "appid" to "1001",
                            "type" to "1",
                            "plat" to "4",
                            "srcappid" to "2919",
                            "qrcode_txt" to
                                "https://h5.kugou.com/apps/loginQRCode/html/index.html?appid=${KugouLite.APP_ID}&",
                        ),
                    )
                val key = response.json.optJSONObject("data")?.optString("qrcode").orEmpty()
                require(key.isNotBlank()) { "酷狗概念版暂时无法生成登录二维码" }
                LoginChallenge(
                    key,
                    "https://h5.kugou.com/apps/loginQRCode/html/index.html?qrcode=${encode(key)}",
                    device = device,
                )
            }
        }

    fun poll(platform: MusicPlatform, challenge: LoginChallenge): LoginPoll =
        when (platform) {
            MusicPlatform.NETEASE -> {
                if (challenge.authorized) completeNetease(challenge)
                else {
                    val response =
                        netease(
                            "login/qrcode/client/login",
                            JSONObject().put("type", 3).put("key", challenge.key),
                            challenge.cookie,
                            challenge.device,
                            challenge.web,
                        )
                    val bodyCookies =
                        cookieValues(response.json.optString("cookie"))
                            .filterKeys {
                                it in setOf("MUSIC_U", "MUSIC_A", "__csrf", "NMTID")
                            }
                            .map { (key, value) -> "$key=$value; Domain=.music.163.com" }
                    challenge.cookie =
                        mergeCookies(challenge.cookie, response.cookies + bodyCookies)
                    when (val stage = neteaseStage(response.json)) {
                        LoginStage.AUTHORIZED -> {
                            require(
                                cookieValues(challenge.cookie)["MUSIC_U"].orEmpty().isNotBlank()
                            ) {
                                "网易云未返回登录凭据，请重新扫码"
                            }
                            challenge.authorized = true
                            completeNetease(challenge)
                        }
                        else -> LoginPoll(stage)
                    }
                }
            }
            MusicPlatform.KUGOU -> {
                val response =
                    kugou(
                        "get_userinfo_qrcode",
                        challenge.device,
                        mapOf(
                            "plat" to "4",
                            "appid" to KugouLite.APP_ID,
                            "srcappid" to "2919",
                            "qrcode" to challenge.key,
                            "dev" to challenge.device,
                        ),
                    )
                val data = response.json.optJSONObject("data") ?: error("酷狗暂时无法确认登录状态")
                when (val stage = kugouStage(data)) {
                    LoginStage.AUTHORIZED -> {
                        val token = data.optString("token")
                        val id = data.optString("userid")
                        require(token.isNotBlank() && id.toLongOrNull()?.let { it > 0 } == true) {
                            "酷狗未返回完整登录凭据，请重新扫码"
                        }
                        val nickname =
                            listOf("nickname", "user_name", "username").firstNotNullOfOrNull {
                                data.optString(it).takeIf(String::isNotBlank)
                            } ?: "概念版听友"
                        val avatar =
                            listOf("pic", "avatar", "user_pic")
                                .firstNotNullOfOrNull {
                                    data.optString(it).takeIf(String::isNotBlank)
                                }
                                .orEmpty()
                        val session =
                            JSONObject()
                                .put("token", token)
                                .put("userid", id)
                                .put("device", challenge.device)
                                .put("mid", PlatformProtocol.kugouMid(challenge.device))
                                .put("edition", KugouLite.EDITION)
                                .put("appid", KugouLite.APP_ID)
                                .toString()
                        LoginPoll(
                            stage,
                            AuthorizedAccount(
                                PlatformProfile(platform, id, nickname, safeAvatar(avatar)),
                                session,
                            ),
                        )
                    }
                    else -> LoginPoll(stage)
                }
            }
        }

    private fun completeNetease(challenge: LoginChallenge): LoginPoll {
        val web = runCatching {
            netease(
                "nuser/account/get",
                JSONObject(),
                challenge.cookie,
                challenge.device,
                web = true,
            )
        }
            .getOrNull()
        val response =
            if (
                web != null &&
                    web.json.optInt("code") == 200 &&
                    web.json.optJSONObject("profile") != null
            )
                web
            else netease("nuser/account/get", JSONObject(), challenge.cookie, challenge.device)
        challenge.cookie = mergeCookies(challenge.cookie, response.cookies)
        val profile = neteaseProfile(response.json)
        return LoginPoll(LoginStage.AUTHORIZED, AuthorizedAccount(profile, challenge.cookie))
    }

    private fun netease(
        path: String,
        data: JSONObject,
        cookie: String = "",
        device: String = "",
        web: Boolean = false,
    ): LoginResponse {
        return transport.execute(PlatformProtocol.netease(path, data, cookie, device, web))
    }

    private fun kugou(path: String, device: String, values: Map<String, String>): LoginResponse {
        val params =
            linkedMapOf(
                "dfid" to "-",
                "mid" to PlatformProtocol.kugouMid(device),
                "uuid" to "-",
                "appid" to KugouLite.APP_ID,
                "clientver" to KugouLite.CLIENT_VERSION,
                "clienttime" to (System.currentTimeMillis() / 1000).toString(),
            )
        params.putAll(values)
        params["signature"] = kugouSignature(params)
        return transport.execute(
            LoginRequest("https://login-user.kugou.com/v2/$path?${form(params)}")
        )
    }

    companion object {
        fun neteaseStage(json: JSONObject): LoginStage =
            when (json.optInt("code")) {
                800 -> LoginStage.EXPIRED
                801 -> LoginStage.WAITING
                802 -> LoginStage.CONFIRMING
                803 -> LoginStage.AUTHORIZED
                else -> error("网易云暂时无法确认登录状态（${json.optInt("code")}），正在重试")
            }

        fun kugouStage(data: JSONObject): LoginStage =
            when (data.optInt("status", -1)) {
                0 -> LoginStage.EXPIRED
                1 -> LoginStage.WAITING
                2 -> LoginStage.CONFIRMING
                4 -> LoginStage.AUTHORIZED
                else -> error("酷狗暂时无法确认登录状态")
            }

        fun neteaseProfile(json: JSONObject): PlatformProfile {
            require(json.optInt("code") == 200) { "网易云账号资料读取失败，请重新登录" }
            val profile = json.optJSONObject("profile") ?: error("网易云登录尚未完成，请重新扫码")
            val id = profile.optString("userId")
            require(id.toLongOrNull()?.let { it > 0 } == true) { "网易云返回的账号无效" }
            return PlatformProfile(
                MusicPlatform.NETEASE,
                id,
                profile.optString("nickname").ifBlank { "网易云听友" },
                safeAvatar(profile.optString("avatarUrl")),
            )
        }

        fun safeAvatar(value: String): String = runCatching {
            val url =
                URL(
                    if (value.startsWith("http://")) "https://${value.removePrefix("http://")}"
                    else value
                )
            if (url.protocol == "https" && url.userInfo == null && url.port == -1) url.toString()
            else ""
        }
            .getOrDefault("")

        fun cookieValues(value: String): Map<String, String> =
            value
                .split(';')
                .mapNotNull {
                    val split = it.indexOf('=')
                    if (split <= 0) null
                    else it.substring(0, split).trim() to it.substring(split + 1).trim()
                }
                .toMap()

        fun mergeCookies(previous: String, headers: List<String>): String {
            val result = cookieValues(previous).toMutableMap()
            headers.forEach { header ->
                runCatching { HttpCookie.parse(header) }
                    .getOrNull()
                    ?.forEach { cookie ->
                        val domain = cookie.domain
                        if (
                            cookie.name.matches(Regex("[a-zA-Z0-9_]+")) &&
                                (domain == null ||
                                    HttpCookie.domainMatches(domain, "music.163.com")) &&
                                !cookie.value.contains('\r') &&
                                !cookie.value.contains('\n')
                        ) {
                            if (cookie.maxAge == 0L) result.remove(cookie.name)
                            else result[cookie.name] = cookie.value
                        }
                    }
            }
            val cookie = result.entries.joinToString("; ") { "${it.key}=${it.value}" }
            require(cookie.length <= 32768) { "平台登录凭据过长" }
            return cookie
        }

        fun kugouSignature(values: Map<String, String>): String {
            val salt = "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt"
            val content =
                salt +
                    values.toSortedMap().entries.joinToString("") { "${it.key}=${it.value}" } +
                    salt
            return MessageDigest.getInstance("MD5").digest(content.toByteArray()).joinToString("") {
                "%02x".format(it)
            }
        }

        fun weapi(json: String, secret: String = randomSecret()): String {
            require(secret.length == 16)
            fun aes(value: String, key: String): String =
                Base64.getEncoder()
                    .encodeToString(
                        Cipher.getInstance("AES/CBC/PKCS5Padding").run {
                            init(
                                Cipher.ENCRYPT_MODE,
                                SecretKeySpec(key.toByteArray(), "AES"),
                                IvParameterSpec("0102030405060708".toByteArray()),
                            )
                            doFinal(value.toByteArray(Charsets.UTF_8))
                        }
                    )
            val publicKey =
                KeyFactory.getInstance("RSA")
                    .generatePublic(
                        X509EncodedKeySpec(
                            Base64.getDecoder()
                                .decode(
                                    "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDgtQn2JZ34ZC28NWYpAUd98iZ37BUrX/aKzmFbt7clFSs6sXqHauqKWqdtLkF2KexO40H1YTX8z2lSgBBOAxLsvaklV8k4cBFK9snQXE9/DDaFt6Rr7iVZMldczhC0JNgTz+SHXT6CBHuX3e9SdB1Ua44oncaTWz7OBGLbCiK45wIDAQAB"
                                )
                        )
                    ) as RSAPublicKey
            val encryptedKey =
                BigInteger(1, secret.reversed().toByteArray())
                    .modPow(publicKey.publicExponent, publicKey.modulus)
                    .toString(16)
                    .padStart(256, '0')
            return form(
                mapOf(
                    "params" to aes(aes(json, "0CoJUm6Qyw8W8jud"), secret),
                    "encSecKey" to encryptedKey,
                )
            )
        }

        private fun randomSecret(): String {
            val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
            val random = SecureRandom()
            return buildString { repeat(16) { append(chars[random.nextInt(chars.length)]) } }
        }

        private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

        private fun form(values: Map<String, String>) =
            values.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }

        internal fun request(request: LoginRequest): LoginResponse {
            request.validate()
            val conn = URL(request.url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 12000
                conn.readTimeout = 12000
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty(
                    "User-Agent",
                    if (URL(request.url).host.endsWith("music.163.com"))
                        PlatformAudioTransport.NETEASE_USER_AGENT
                    else "Android15-1070-11440-46-0-DiscoveryDRADProtocol-wifi",
                )
                if (URL(request.url).host == "music.163.com")
                    conn.setRequestProperty("Referer", "https://music.163.com/")
                if (request.cookie.isNotEmpty()) conn.setRequestProperty("Cookie", request.cookie)
                request.headers.forEach { (name, value) -> conn.setRequestProperty(name, value) }
                request.body?.let { body ->
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", request.contentType)
                    conn.setFixedLengthStreamingMode(bytes.size)
                    conn.outputStream.use { it.write(bytes) }
                }
                require(conn.responseCode in 200..299) { "平台返回 HTTP ${conn.responseCode}，请稍后重试" }
                val output = ByteArrayOutputStream()
                conn.inputStream.use { input ->
                    val bytes = ByteArray(8192)
                    while (true) {
                        val read = input.read(bytes)
                        if (read < 0) break
                        require(output.size() + read <= 8388608) { "平台响应过长" }
                        output.write(bytes, 0, read)
                    }
                }
                val cookies =
                    conn.headerFields
                        .filterKeys { it?.equals("Set-Cookie", true) == true }
                        .values
                        .flatten()
                return LoginResponse(JSONObject(output.toString("UTF-8")), cookies)
            } finally {
                conn.disconnect()
            }
        }
    }
}
