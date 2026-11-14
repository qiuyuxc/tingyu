package com.qiuyuxc.tingyu

import java.net.URL
import java.net.URLDecoder
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlatformLoginClientTest {
    @Test
    fun neteaseEncryptionMatchesIndependentOpenSslAndPythonReference() {
        val fields =
            PlatformLoginClient.weapi(
                    "{\"type\":3,\"csrf_token\":\"\",\"e_r\":false}",
                    "abcdefghijklmnop",
                )
                .split('&')
                .associate { field ->
                    field.split('=', limit = 2).let { it[0] to URLDecoder.decode(it[1], "UTF-8") }
                }
        assertEquals(
            "hF6PNHJUfpyQ63BomrD9ixD39dDG3TA2MTKlfGoFDVP6oTxHyiU+Wo7hAfn7Xlxf4U5f/GA5rubV3hKhBOZRXHVILt/h8xo7yzWafvkiepU=",
            fields["params"],
        )
        assertEquals(
            "d15a1683c992095d0c234c19966605c5c5964911268bbeda8cb8d08d834913e59d53b32358903a121b5fca784c1f5ae44951fd02524df58ecc98e52cc7cf8689b42c2e93ddf05b0592512d87f5960467e2f086c018849d76014d323500e30f13ef4cafbb0cf5a66731a3f1776c75ca35d0062dac70a3e33245afabcf47938487",
            fields["encSecKey"],
        )
        assertEquals(
            "40a53a7a76cf0ef698006b537e0386e9",
            PlatformLoginClient.kugouSignature(
                mapOf("qrcode" to "test-key", "appid" to "1001", "plat" to "4")
            ),
        )
    }

    @Test
    fun waitingConfirmationExpirationAndAuthorizationAreDistinct() {
        assertEquals(
            LoginStage.WAITING,
            PlatformLoginClient.neteaseStage(JSONObject("{\"code\":801}")),
        )
        assertEquals(
            LoginStage.CONFIRMING,
            PlatformLoginClient.neteaseStage(JSONObject("{\"code\":802}")),
        )
        assertEquals(
            LoginStage.EXPIRED,
            PlatformLoginClient.neteaseStage(JSONObject("{\"code\":800}")),
        )
        assertEquals(
            LoginStage.AUTHORIZED,
            PlatformLoginClient.neteaseStage(JSONObject("{\"code\":803}")),
        )
        assertEquals(
            LoginStage.WAITING,
            PlatformLoginClient.kugouStage(JSONObject("{\"status\":1}")),
        )
        assertEquals(
            LoginStage.CONFIRMING,
            PlatformLoginClient.kugouStage(JSONObject("{\"status\":2}")),
        )
        assertEquals(
            LoginStage.EXPIRED,
            PlatformLoginClient.kugouStage(JSONObject("{\"status\":0}")),
        )
        assertEquals(
            LoginStage.AUTHORIZED,
            PlatformLoginClient.kugouStage(JSONObject("{\"status\":4}")),
        )
    }

    @Test
    fun unknownStateAndAnonymousProfileAreNeverAcceptedAsLogin() {
        assertThrows(IllegalStateException::class.java) {
            PlatformLoginClient.neteaseStage(JSONObject("{\"code\":200}"))
        }
        assertThrows(IllegalStateException::class.java) {
            PlatformLoginClient.kugouStage(JSONObject("{\"status\":3}"))
        }
        assertThrows(IllegalStateException::class.java) {
            PlatformLoginClient.neteaseProfile(JSONObject("{\"code\":200,\"profile\":null}"))
        }
    }

    @Test
    fun successfulQrWithoutCredentialsCannotConnectAnAccount() {
        val netease =
            PlatformLoginClient(LoginTransport { LoginResponse(JSONObject("{\"code\":803}")) })
        assertThrows(IllegalArgumentException::class.java) {
            netease.poll(MusicPlatform.NETEASE, LoginChallenge("key", "https://music.163.com/"))
        }
        val kugou =
            PlatformLoginClient(
                LoginTransport {
                    LoginResponse(JSONObject("{\"data\":{\"status\":4,\"userid\":42}}"))
                }
            )
        assertThrows(IllegalArgumentException::class.java) {
            kugou.poll(MusicPlatform.KUGOU, LoginChallenge("key", "https://www.kugou.com/"))
        }
    }

    @Test
    fun oneClientKeepsPlatformSessionsSeparateAndReadsAuthorizedProfile() {
        val requests = mutableListOf<LoginRequest>()
        val client =
            PlatformLoginClient(
                LoginTransport { request ->
                    request.validate()
                    requests += request
                    when {
                        request.url.endsWith("/nuser/account/get") -> {
                            assertEquals(
                                "session",
                                PlatformLoginClient.cookieValues(request.cookie)["MUSIC_U"],
                            )
                            LoginResponse(
                                JSONObject(
                                    "{\"code\":200,\"profile\":{\"userId\":42,\"nickname\":\"云朵\",\"avatarUrl\":\"https://example.com/n.jpg\"}}"
                                )
                            )
                        }
                        request.url.contains("/login/qrcode/client/login") ->
                            LoginResponse(
                                JSONObject("{\"code\":803}"),
                                listOf("MUSIC_U=session; Domain=.music.163.com; Secure"),
                            )
                        request.url.contains("get_userinfo_qrcode") -> {
                            assertTrue(request.cookie.isEmpty())
                            assertFalse(request.url.contains("MUSIC_U"))
                            LoginResponse(
                                JSONObject(
                                    "{\"data\":{\"status\":4,\"userid\":42,\"token\":\"dog-session\",\"nickname\":\"晴天\"}}"
                                )
                            )
                        }
                        else -> error("Unexpected test request")
                    }
                }
            )
        val cloud =
            client.poll(MusicPlatform.NETEASE, LoginChallenge("cloud-key", "", "__csrf=csrf"))
        val dog = client.poll(MusicPlatform.KUGOU, LoginChallenge("dog-key", "", device = "device"))
        assertEquals("云朵", cloud.account?.profile?.nickname)
        assertEquals("晴天", dog.account?.profile?.nickname)
        assertEquals("dog-session", JSONObject(dog.account!!.session).getString("token"))
        assertEquals(
            listOf("interfacepc.music.163.com", "music.163.com", "login-user.kugou.com"),
            requests.map { URL(it.url).host },
        )
        assertEquals(KugouLite.APP_ID, JSONObject(dog.account!!.session).getString("appid"))
        assertEquals(KugouLite.EDITION, JSONObject(dog.account!!.session).getString("edition"))
    }

    @Test
    fun cookiesHonorDomainAndDeletionAndRetainPaddedValues() {
        val merged =
            PlatformLoginClient.mergeCookies(
                "__csrf=old; MUSIC_U=old",
                listOf(
                    "MUSIC_U=new==; Domain=.music.163.com; Secure",
                    "__csrf=; Max-Age=0; Domain=.music.163.com",
                    "FOREIGN=other; Domain=.kugou.com",
                    "PATH_COOKIE=valid; Path=/",
                ),
            )
        assertEquals("new==", PlatformLoginClient.cookieValues(merged)["MUSIC_U"])
        assertFalse(PlatformLoginClient.cookieValues(merged).containsKey("__csrf"))
        assertFalse(PlatformLoginClient.cookieValues(merged).containsKey("FOREIGN"))
        assertEquals("valid", PlatformLoginClient.cookieValues(merged)["PATH_COOKIE"])
    }

    @Test
    fun credentialsAreRestrictedToTheFirstPartyHttpsHost() {
        listOf(
                "http://music.163.com/api",
                "https://music.163.com.evil.invalid/api",
                "https://user@music.163.com/api",
                "https://music.163.com:444/api",
            )
            .forEach { url ->
                assertThrows(IllegalArgumentException::class.java) {
                    LoginRequest(url, cookie = "MUSIC_U=test").validate()
                }
            }
        assertThrows(IllegalArgumentException::class.java) {
            LoginRequest("https://login-user.kugou.com/v2/qrcode", cookie = "MUSIC_U=test")
                .validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            LoginRequest("https://music.163.com/api", cookie = "MUSIC_U=test\r\nHeader: value")
                .validate()
        }
        assertEquals("", PlatformLoginClient.safeAvatar("file:///private/file"))
        assertEquals(
            "",
            PlatformLoginClient.safeAvatar("https://user:password@example.com/avatar.jpg"),
        )
        assertEquals(
            "https://example.com/avatar.jpg",
            PlatformLoginClient.safeAvatar("http://example.com/avatar.jpg"),
        )
    }

    @Test
    fun authorizedCookieSurvivesProfileFailureAndRetriesWithoutAnotherQrPoll() {
        var qrChecks = 0
        var profiles = 0
        val client =
            PlatformLoginClient(
                LoginTransport { request ->
                    request.validate()
                    if (request.url.contains("qrcode/client/login")) {
                        qrChecks++
                        LoginResponse(
                            JSONObject(
                                "{\"code\":803,\"cookie\":\"__csrf=csrf; MUSIC_U=authorized==\"}"
                            )
                        )
                    } else {
                        profiles++
                        if (profiles <= 2)
                            LoginResponse(JSONObject("{\"code\":200,\"profile\":null}"))
                        else
                            LoginResponse(
                                JSONObject(
                                    "{\"code\":200,\"profile\":{\"userId\":42,\"nickname\":\"已授权\"}}"
                                )
                            )
                    }
                }
            )
        val challenge = LoginChallenge("key", "")
        assertThrows(IllegalStateException::class.java) {
            client.poll(MusicPlatform.NETEASE, challenge)
        }
        assertTrue(challenge.authorized)
        assertEquals("authorized==", PlatformLoginClient.cookieValues(challenge.cookie)["MUSIC_U"])
        val result = client.poll(MusicPlatform.NETEASE, challenge)
        assertEquals("已授权", result.account!!.profile.nickname)
        assertEquals(1, qrChecks)
    }

    @Test
    fun conceptQrRequestsUseTheConceptAppAndKeepTheDeviceIdentity() {
        val requests = mutableListOf<LoginRequest>()
        val client =
            PlatformLoginClient(
                LoginTransport { request ->
                    request.validate()
                    requests += request
                    if (request.url.contains("get_userinfo_qrcode"))
                        LoginResponse(JSONObject("{\"data\":{\"status\":1}}"))
                    else LoginResponse(JSONObject("{\"data\":{\"qrcode\":\"concept-key\"}}"))
                }
            )
        val challenge = client.create(MusicPlatform.KUGOU)
        client.poll(MusicPlatform.KUGOU, challenge)
        val queries = requests.map {
            URL(it.url).query.split('&').associate { field ->
                field.split('=', limit = 2).let { it[0] to URLDecoder.decode(it[1], "UTF-8") }
            }
        }
        assertTrue(queries.first().getValue("qrcode_txt").contains("appid=3116&"))
        assertEquals("3116", queries.last()["appid"])
        assertEquals("11440", queries.last()["clientver"])
        assertEquals(challenge.device, queries.last()["dev"])
    }
}
