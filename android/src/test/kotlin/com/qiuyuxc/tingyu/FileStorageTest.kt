package com.qiuyuxc.tingyu

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.Date
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FileStorageTest {
    @Test
    fun signatureMatchesAwsPublishedGetObjectExample() {
        val signer =
            SigV4("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "us-east-1")
        val signed =
            signer.signHash(
                "GET",
                "examplebucket.s3.amazonaws.com",
                "/test.txt",
                extraHeaders = mapOf("Range" to "bytes=0-9"),
                now = Date(1369353600000),
            )
        assertEquals("20130524T000000Z", signed["x-amz-date"])
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41",
            signed["Authorization"],
        )
    }

    @Test
    fun s3ListsPlainFilesAcrossPagesAndPreservesSpecialCharacters() {
        val requests = ArrayList<String>()
        server({ exchange ->
            requests += exchange.requestURI.rawQuery
            assertTrue(exchange.requestHeaders.getFirst("Authorization").contains("/auto/s3/"))
            val second = exchange.requestURI.rawQuery.contains("continuation-token=")
            val body =
                if (!second)
                    """<ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/"><EncodingType>url</EncodingType><Contents><Key>music/%E6%AD%8C%20%2B%20%26.flac</Key><Size>123</Size></Contents><IsTruncated>true</IsTruncated><NextContinuationToken>next+&amp;/page</NextContinuationToken></ListBucketResult>"""
                else
                    """<ListBucketResult><EncodingType>url</EncodingType><Contents><Key>music/plain.mp3</Key><Size>456</Size></Contents><IsTruncated>false</IsTruncated></ListBucketResult>"""
            respond(exchange, 200, body)
        }) { base ->
            val storage = S3Backend(base, "bucket", "test", "secret", prefix = "/music/")
            val items = storage.list()
            assertEquals(listOf("歌 + &.flac", "plain.mp3"), items.map { it.name })
            assertEquals(listOf(123L, 456L), items.map { it.size })
            assertEquals("music/歌 + &.flac", items.first().path)
            assertEquals("music/plain.mp3", storage.pathFor(storage.pathFor("plain.mp3")))
            assertEquals(2, requests.size)
            assertTrue(requests.last().contains("continuation-token=next%2B%26%2Fpage"))
        }
    }

    @Test
    fun s3StreamsOriginalBytesSignsTheirHashAndReportsProgress() {
        val bytes = ByteArray(180_000) { (it * 19).toByte() }
        var uploaded = ByteArray(0)
        val progress = ArrayList<Pair<Long, Long>>()
        server({ exchange ->
            assertEquals("PUT", exchange.requestMethod)
            assertEquals("/bucket/music/%E6%AD%8C%20%2B.flac", exchange.requestURI.rawPath)
            assertEquals("*", exchange.requestHeaders.getFirst("If-None-Match"))
            assertEquals(
                SigV4.hex(SigV4.sha256(bytes)),
                exchange.requestHeaders.getFirst("X-Amz-Content-Sha256"),
            )
            uploaded = exchange.requestBody.use { it.readBytes() }
            respond(exchange, 200, "")
        }) { base ->
            temporary(bytes) { file ->
                S3Backend(base, "bucket", "test", "secret", prefix = "music")
                    .upload(
                        "歌 +.flac",
                        file,
                        Storage.UploadProgress { done, total -> progress += done to total },
                    )
            }
        }
        assertArrayEquals(bytes, uploaded)
        assertEquals(0L to bytes.size.toLong(), progress.first())
        assertEquals(bytes.size.toLong() to bytes.size.toLong(), progress.last())
        assertTrue(progress.zipWithNext().all { (before, after) -> before.first <= after.first })
    }

    @Test
    fun s3ChecksZeroLengthObjectsWithHeadAndRejectsAccessErrors() {
        server({ exchange ->
            assertEquals("HEAD", exchange.requestMethod)
            val status =
                when (exchange.requestURI.path.substringAfterLast('/')) {
                    "empty" -> 200
                    "missing" -> 404
                    else -> 403
                }
            if (status == 200) exchange.responseHeaders.set("Content-Length", "0")
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }) { base ->
            val storage = S3Backend(base, "bucket", "test", "secret")
            assertEquals(0, storage.sizeOf("empty"))
            assertTrue(storage.exists("empty"))
            assertFalse(storage.exists("missing"))
            assertThrows(IOException::class.java) { storage.exists("denied") }
        }
    }

    @Test
    fun httpRangeReadsSkipBytesWhenServerIgnoresRangeAndRejectHttpErrors() {
        server({ exchange ->
            if (exchange.requestURI.path == "/missing") respond(exchange, 404, "not found")
            else respond(exchange, 200, "0123456789")
        }) { base ->
            val storage = HttpBackend(base)
            assertArrayEquals("3456".toByteArray(), storage.readRange("track.flac", 3, 4))
            assertEquals(10L, storage.sizeOf("track.flac"))
            assertThrows(IOException::class.java) { storage.sizeOf("missing") }
        }
    }

    @Test
    fun httpListsConfirmedEmptyFilesWithoutAcceptingOtherRangeFailures() {
        server({ exchange ->
            if (exchange.requestURI.path == "/index.json") {
                respond(exchange, 200, """["empty.bin"]""")
            } else {
                assertEquals("bytes=0-0", exchange.requestHeaders.getFirst("Range"))
                val empty = exchange.requestURI.path == "/empty.bin"
                exchange.responseHeaders.set(
                    "Content-Range",
                    if (empty) "bytes */0" else "bytes */7",
                )
                respond(exchange, 416, "Range Not Satisfiable")
            }
        }) { base ->
            val storage = HttpBackend(base)
            assertEquals(Storage.Item("empty.bin", "empty.bin", 0L), storage.list().single())
            assertThrows(IOException::class.java) { storage.sizeOf("invalid.bin") }
        }
    }

    @Test
    fun httpAddsOriginalFilenameToIndexWithoutLosingExistingFiles() {
        var index = """["old.mp3"]"""
        var uploaded = ByteArray(0)
        server({ exchange ->
            val path = exchange.requestURI.path
            when {
                path == "/index.json" && exchange.requestMethod == "GET" ->
                    respond(exchange, 200, index)
                path == "/index.json" && exchange.requestMethod == "PUT" -> {
                    index = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
                    respond(exchange, 200, "")
                }
                exchange.requestMethod == "PUT" -> {
                    assertEquals("/new +.flac", path)
                    uploaded = exchange.requestBody.use { it.readBytes() }
                    respond(exchange, 200, "")
                }
                else -> respond(exchange, 404, "")
            }
        }) { base ->
            val folder = kotlin.io.path.createTempDirectory("tingyu-storage-test-").toFile()
            try {
                val file = File(folder, "new +.flac").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
                assertEquals("new +.flac", FileUploader.upload(HttpBackend(base), file))
                assertArrayEquals(file.readBytes(), uploaded)
                val array = JSONArray(index)
                assertEquals(
                    listOf("old.mp3", "new +.flac"),
                    (0 until array.length()).map { array.getString(it) },
                )
            } finally {
                folder.deleteRecursively()
            }
        }
    }

    @Test
    fun damagedHttpIndexIsNotOverwrittenAfterSuccessfulFileUpload() {
        var indexWrites = 0
        var uploaded = false
        server({ exchange ->
            when {
                exchange.requestURI.path == "/index.json" && exchange.requestMethod == "GET" ->
                    respond(exchange, 200, "damaged index")
                exchange.requestURI.path == "/index.json" -> {
                    indexWrites++
                    respond(exchange, 200, "")
                }
                exchange.requestMethod == "PUT" -> {
                    exchange.requestBody.close()
                    uploaded = true
                    respond(exchange, 200, "")
                }
                else -> respond(exchange, 404, "")
            }
        }) { base ->
            temporary(byteArrayOf(1, 2, 3)) { file ->
                val error =
                    assertThrows(IOException::class.java) {
                        FileUploader.upload(HttpBackend(base), file)
                    }
                assertTrue(error.message!!.contains("文件已在云端"))
            }
        }
        assertTrue(uploaded)
        assertEquals(0, indexWrites)
    }

    @Test
    fun openListListsPlainFilesAndUploadsToConfiguredDirectory() {
        var uploadPath = ""
        var uploaded = ByteArray(0)
        server({ exchange ->
            when (exchange.requestURI.path) {
                "/api/fs/list" -> {
                    val body =
                        JSONObject(
                            exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
                        )
                    assertEquals("/音乐", body.getString("path"))
                    assertTrue(body.getBoolean("refresh"))
                    respond(
                        exchange,
                        200,
                        """{"code":200,"data":{"total":1,"content":[{"name":"plain.flac","is_dir":false,"size":20}]}}""",
                    )
                }
                "/api/fs/put" -> {
                    uploadPath =
                        URLDecoder.decode(exchange.requestHeaders.getFirst("File-Path"), "UTF-8")
                    uploaded = exchange.requestBody.use { it.readBytes() }
                    respond(exchange, 200, """{"code":200,"message":"success"}""")
                }
                else -> respond(exchange, 404, "")
            }
        }) { base ->
            val storage = OpenListBackend(OpenList(base), "/音乐")
            assertEquals("/音乐/plain.flac", storage.list().single().path)
            assertEquals("/音乐/歌 +.mp3", storage.pathFor(storage.pathFor("歌 +.mp3")))
            temporary(byteArrayOf(8, 9)) { file -> storage.upload("歌 +.mp3", file) }
        }
        assertEquals("/音乐/歌 +.mp3", uploadPath)
        assertArrayEquals(byteArrayOf(8, 9), uploaded)
    }

    @Test
    fun openListFollowsDeclaredTotalWhenServerReturnsSmallerPages() {
        val pages = ArrayList<Int>()
        server({ exchange ->
            val body =
                JSONObject(exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) })
            val page = body.getInt("page")
            pages += page
            assertEquals(1000, body.getInt("per_page"))
            val name = if (page == 1) "first.flac" else "second.mp3"
            respond(
                exchange,
                200,
                """{"code":200,"data":{"total":2,"content":[{"name":"$name","is_dir":false,"size":10}]}}""",
            )
        }) { base ->
            val items = OpenListBackend(OpenList(base), "/music").list()
            assertEquals(listOf("first.flac", "second.mp3"), items.map { it.name })
            assertEquals(listOf("/music/first.flac", "/music/second.mp3"), items.map { it.path })
        }
        assertEquals(listOf(1, 2), pages)
    }

    @Test
    fun openListRejectsEmptyOrRepeatedPagesBeforeDeclaredTotal() {
        for (repeatFirst in listOf(false, true)) {
            server({ exchange ->
                val body =
                    JSONObject(exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) })
                val includeFile = body.getInt("page") == 1 || repeatFirst
                val content =
                    if (includeFile) """[{"name":"first.flac","is_dir":false,"size":10}]"""
                    else "[]"
                respond(exchange, 200, """{"code":200,"data":{"total":2,"content":$content}}""")
            }) { base ->
                val error =
                    assertThrows(OpenList.OpenListException::class.java) {
                        OpenList(base).list("/music")
                    }
                assertTrue(error.message!!.contains(if (repeatFirst) "重复" else "缺少"))
            }
        }
    }

    @Test
    fun openListApiFailureIsNotReportedAsUploadSuccess() {
        server({ exchange ->
            exchange.requestBody.use { it.readBytes() }
            respond(exchange, 200, """{"code":403,"message":"write permission denied"}""")
        }) { base ->
            temporary(byteArrayOf(1)) { file ->
                assertThrows(OpenList.OpenListException::class.java) {
                    OpenList(base).upload("/track.mp3", file)
                }
            }
        }
    }

    @Test
    fun uploaderDoesNotOverwriteExistingFilesOrTheHttpIndex() {
        server({ exchange -> respond(exchange, 200, "existing") }) { base ->
            temporary(byteArrayOf(1)) { file ->
                val error =
                    assertThrows(IOException::class.java) {
                        FileUploader.upload(HttpBackend(base), file)
                    }
                assertTrue(error.message!!.contains("同名"))
            }
            val folder = kotlin.io.path.createTempDirectory("tingyu-index-test-").toFile()
            try {
                val file = File(folder, "index.json").apply { writeText("[]") }
                val error =
                    assertThrows(IOException::class.java) {
                        FileUploader.upload(HttpBackend(base), file)
                    }
                assertTrue(error.message!!.contains("云端列表"))
            } finally {
                folder.deleteRecursively()
            }
        }
    }

    private fun server(handler: (HttpExchange) -> Unit, block: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> handler(exchange) }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }

    private fun respond(exchange: HttpExchange, status: Int, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    private fun temporary(bytes: ByteArray, block: (File) -> Unit) {
        val file = File.createTempFile("tingyu-upload-test-", ".flac")
        try {
            file.writeBytes(bytes)
            block(file)
        } finally {
            file.delete()
        }
    }
}
