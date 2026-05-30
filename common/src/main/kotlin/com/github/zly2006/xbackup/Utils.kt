package com.github.zly2006.xbackup

import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.security.MessageDigest

val log = LoggerFactory.getLogger("X Backup")!!

class DontRetryException(cause: Throwable) : RuntimeException(cause)

suspend inline fun <T> retry(times: Int, function: () -> T): T {
    var lastException: Exception? = null
    repeat(times) {
        try {
            return function()
        } catch (e: DontRetryException) {
            throw e.cause!!
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (it + 1 < times) {
                log.warn("Error in retry, attempt ${it + 1}/$times: ${e.javaClass.name}: ${e.message}. Retrying...")
            } else {
                log.error("Error in retry, final attempt ${it + 1}/$times failed", e)
            }
            lastException = e
            delay(1000L shl it)
        }
    }
    throw RuntimeException("Retry failed", lastException)
}

fun InputStream.digest(algorithm: String): String = use { input ->
    val digest = MessageDigest.getInstance(algorithm)
    val buffer = ByteArray(8192)
    var read: Int
    while (input.read(buffer).also { read = it } > 0) {
        digest.update(buffer, 0, read)
    }
    digest.digest()
}.joinToString("") { "%02x".format(it) }
