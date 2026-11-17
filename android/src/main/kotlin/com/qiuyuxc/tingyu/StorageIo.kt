package com.qiuyuxc.tingyu

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible

/** Cancelling the caller also closes HTTP connections blocked on server responses. */
internal suspend fun <T> storageIo(block: () -> T): T = coroutineScope {
    val requests = StorageHttp.Requests()
    try {
        val pending = async {
            runInterruptible(Dispatchers.IO) {
                try {
                    requests.run(block)
                } catch (error: Exception) {
                    ensureActive()
                    throw error
                }
            }
        }
        pending.await()
    } finally {
        requests.cancel()
    }
}
