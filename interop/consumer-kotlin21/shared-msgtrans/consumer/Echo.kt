package consumer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import msgtrans.core.Compression
import msgtrans.transport.Connection
import kotlin.random.Random

/** msgtrans echo checks on [conn]: every size in [SIZES], zstd and zlib compressed, 100 concurrent; mismatches. */
suspend fun CoroutineScope.echo(name: String, conn: Connection): Int {
    var failures = 0
    for (size in SIZES) {
        val payload = Random(size).nextBytes(size)
        if (!conn.request(payload, bizType = 1).contentEquals(payload)) failures++
    }
    val compressible = ByteArray(200_000) { (it % 7).toByte() }
    for (compression in listOf(Compression.Zstd, Compression.Zlib)) {
        if (!conn.request(compressible, bizType = 1, compression = compression).contentEquals(compressible)) failures++
    }
    val replies = List(100) { i -> async { conn.request("r$i".encodeToByteArray()).decodeToString() } }.awaitAll()
    if (replies != List(100) { "r$it" }) failures++
    println("$name: ${if (failures == 0) "ok" else "$failures mismatches"}")
    conn.close()
    return failures
}
