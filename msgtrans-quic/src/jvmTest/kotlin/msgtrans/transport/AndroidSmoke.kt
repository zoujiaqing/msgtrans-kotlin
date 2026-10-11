package msgtrans.transport

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import neton.io.net.runReactor
import neton.openssl.OpenSsl
import kotlin.random.Random
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * msgtrans over QUIC on an Android device or emulator, outside an app (interop/android-smoke.sh runs it with
 * app_process): ART, Android's NIO and the NDK-built libneton_openssl.so. An echo server and a client in one process
 * over loopback; requests of 0 B to 1 MB and 100 concurrent ones; exit status 0 when every echo matches.
 */
fun main() {
    val start = TimeSource.Monotonic.markNow()
    var failures = 0
    runReactor {
        withTimeout(60.seconds) {
            println("vm ${System.getProperty("java.vm.name")} ${System.getProperty("java.vm.version")}, ${OpenSsl.version}")
            val pki = quicTestPki()
            val transport = QuicServerTransport("127.0.0.1", 0, pki.chain, pki.key)
            val server = Transport.bind(this, transport) { conn -> conn.onRequest { payload, _ -> payload } }
            val accepting = launch { server.acceptLoop() }
            QuicClientTransport("127.0.0.1", transport.localAddress!!.port, pki.trustAnchors).use { client ->
                val conn = Transport.connect(this, client)
                println("connected after ${start.elapsedNow()}")
                for (size in listOf(0, 1, 1200, 70_000, 1_000_000)) {
                    val payload = Random(size).nextBytes(size)
                    val ok = conn.request(payload, bizType = 1).contentEquals(payload)
                    if (!ok) failures++
                    println("request $size bytes: ${if (ok) "ok" else "MISMATCH"}")
                }
                val replies = List(100) { i -> async { conn.request("r$i".encodeToByteArray()).decodeToString() } }.awaitAll()
                val concurrentOk = replies == List(100) { "r$it" }
                if (!concurrentOk) failures++
                println("100 concurrent requests: ${if (concurrentOk) "ok" else "MISMATCH"}")
                conn.close()
            }
            accepting.cancelAndJoin()
            server.close()
            transport.close()
        }
    }
    println("${if (failures == 0) "PASS" else "FAIL ($failures)"} in ${start.elapsedNow()}")
    exitProcess(if (failures == 0) 0 else 1)
}
