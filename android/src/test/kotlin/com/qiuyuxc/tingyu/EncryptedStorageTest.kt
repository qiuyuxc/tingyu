package com.qiuyuxc.tingyu

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class EncryptedStorageTest {
    @Test fun httpListsAndPlaysEncryptedMusicUsingRanges() = checkStorage { HttpBackend(it) }

    @Test
    fun s3ListsAndPlaysEncryptedMusicUsingRanges() = checkStorage {
        S3Backend(it, "bucket", "test", "secret")
    }

    @Test
    fun openListListsAndPlaysEncryptedMusicUsingRanges() = checkStorage {
        OpenListBackend(OpenList(it))
    }

    private fun checkStorage(factory: (String) -> Storage) {
        val fixture = EncryptedFixture("chunkseal-v2.properties", "raw_boundary")
        val ranges = mutableListOf<Pair<Int, Int>>()
        serve({ exchange, base ->
            when (exchange.requestURI.path) {
                "/index.json" -> respond(exchange, "[\"vector.cseal\"]")
                "/bucket",
                "/bucket/" ->
                    respond(
                        exchange,
                        "<ListBucketResult><Contents><Key>vector.cseal</Key><Size>${fixture.container.size}</Size></Contents><IsTruncated>false</IsTruncated></ListBucketResult>",
                    )
                "/api/fs/list" ->
                    respond(
                        exchange,
                        """{"code":200,"data":{"content":[{"name":"vector.cseal","size":${fixture.container.size},"is_dir":false}],"total":1}}""",
                    )
                "/api/fs/get" ->
                    respond(
                        exchange,
                        """{"code":200,"data":{"name":"vector.cseal","size":${fixture.container.size},"raw_url":"$base/vector.cseal"}}""",
                    )
                else -> {
                    if (exchange.requestMethod == "HEAD") {
                        exchange.responseHeaders.set(
                            "Content-Length",
                            fixture.container.size.toString(),
                        )
                        exchange.sendResponseHeaders(200, -1)
                        exchange.close()
                    } else {
                        val range =
                            requireNotNull(exchange.requestHeaders.getFirst("Range"))
                                .removePrefix("bytes=")
                                .split('-')
                        val first = range[0].toInt()
                        val last = range[1].toInt()
                        ranges += first to last
                        exchange.responseHeaders.set(
                            "Content-Range",
                            "bytes $first-$last/${fixture.container.size}",
                        )
                        val bytes = fixture.container.copyOfRange(first, last + 1)
                        exchange.sendResponseHeaders(206, bytes.size.toLong())
                        exchange.responseBody.use { it.write(bytes) }
                    }
                }
            }
        }) { base ->
            val storage = factory(base)
            val item = storage.list().single()
            assertEquals("vector.cseal", item.name)
            val source = EncryptedMusic.remoteSource(storage, item)
            val opening = StorageHttp.Requests()
            val reader = opening.run {
                EncryptedReader.open(source, fixture.keys) { (source as Closeable).close() }
            }
            // The short opening operation ends; the playback source must continue working.
            opening.cancel()
            reader.use {
                val audio = ByteArray(20)
                assertEquals(20, it.readAt(4090, audio, 0, audio.size))
                assertArrayEquals(fixture.plaintext.copyOfRange(4090, 4110), audio)
                assertEquals(7, it.readAt(8192, audio, 0, audio.size))
                assertArrayEquals(
                    fixture.plaintext.copyOfRange(8192, 8199),
                    audio.copyOfRange(0, 7),
                )
            }
            assertTrue(ranges.isNotEmpty())
            assertTrue(
                "A request downloaded the complete container",
                ranges.none { it.first == 0 && it.second == fixture.container.lastIndex },
            )
        }
    }

    @Test
    fun closingThePlaybackSourceCancelsAnActiveHttpRequest() {
        val started = CountDownLatch(1)
        val finishServer = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        serve({ exchange, _ ->
            started.countDown()
            finishServer.await(5, TimeUnit.SECONDS)
            runCatching { respond(exchange, "unused") }
        }) { base ->
            val source =
                EncryptedMusic.remoteSource(
                    HttpBackend(base),
                    Storage.Item("slow.cseal", "slow.cseal", 100),
                )
            Thread {
                try {
                    source.read(0, 64)
                } catch (error: Throwable) {
                    failure.set(error)
                } finally {
                    finished.countDown()
                }
            }
                .start()
            try {
                assertTrue(started.await(3, TimeUnit.SECONDS))
                (source as Closeable).close()
                assertTrue(
                    "The cancelled request kept running",
                    finished.await(3, TimeUnit.SECONDS),
                )
                assertTrue(failure.get() is IOException)
            } finally {
                (source as Closeable).close()
                finishServer.countDown()
            }
        }
    }

    private fun respond(exchange: HttpExchange, text: String) {
        val bytes = text.toByteArray()
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun serve(handle: (HttpExchange, String) -> Unit, test: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val workers = Executors.newCachedThreadPool()
        val failure = AtomicReference<Throwable>()
        val base = "http://127.0.0.1:${server.address.port}"
        server.executor = workers
        server.createContext("/") { exchange ->
            try {
                handle(exchange, base)
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
                exchange.close()
            }
        }
        server.start()
        try {
            test(base)
            assertNull("Local server failed", failure.get())
        } finally {
            server.stop(0)
            workers.shutdownNow()
        }
    }
}
