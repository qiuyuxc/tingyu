package com.qiuyuxc.tingyu

import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** AWS Signature V4 header signing, including streamed file payloads. */
class SigV4(
    private val accessKey: String,
    private val secretKey: String,
    private val region: String,
    private val service: String = "s3",
) {
    fun signHash(
        method: String,
        host: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        payloadHash: String = EMPTY_HASH,
        extraHeaders: Map<String, String> = emptyMap(),
        now: Date = Date(),
    ): Map<String, String> {
        val format =
            SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
        val timestamp = format.format(now)
        val day = timestamp.take(8)
        val headers =
            sortedMapOf(
                "host" to host,
                "x-amz-content-sha256" to payloadHash,
                "x-amz-date" to timestamp,
            )
        for ((name, value) in extraHeaders) {
            headers[name.lowercase(Locale.US)] = value.trim().replace(Regex("\\s+"), " ")
        }
        val signedHeaders = headers.keys.joinToString(";")
        val canonicalHeaders = headers.entries.joinToString("") { "${it.key}:${it.value}\n" }
        val canonicalQuery =
            query.entries
                .map { StorageHttp.encode(it.key) to StorageHttp.encode(it.value) }
                .sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
                .joinToString("&") { "${it.first}=${it.second}" }
        val request =
            "$method\n${StorageHttp.encodePath(path)}\n$canonicalQuery\n$canonicalHeaders\n$signedHeaders\n$payloadHash"
        val scope = "$day/$region/$service/aws4_request"
        val stringToSign =
            "AWS4-HMAC-SHA256\n$timestamp\n$scope\n${hex(sha256(request.toByteArray(Charsets.UTF_8)))}"
        var key = hmac(("AWS4$secretKey").toByteArray(Charsets.UTF_8), day)
        key = hmac(key, region)
        key = hmac(key, service)
        key = hmac(key, "aws4_request")
        return headers +
            ("Authorization" to
                "AWS4-HMAC-SHA256 Credential=$accessKey/$scope, SignedHeaders=$signedHeaders, Signature=${hex(hmac(key, stringToSign))}")
    }

    companion object {
        const val EMPTY_HASH = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

        fun hex(bytes: ByteArray): String =
            bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

        fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

        private fun hmac(key: ByteArray, value: String): ByteArray =
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key, "HmacSHA256"))
                doFinal(value.toByteArray(Charsets.UTF_8))
            }
    }
}
