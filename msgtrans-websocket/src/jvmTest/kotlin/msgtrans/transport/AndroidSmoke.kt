package msgtrans.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import neton.io.core.IoStream
import neton.io.net.runReactor
import neton.openssl.OpenSsl
import neton.openssl.TlsContext
import neton.tls.tlsAccept
import neton.tls.websocket.WssConnector
import kotlin.random.Random
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * msgtrans over WebSocket on an Android device or emulator, outside an app (interop/android-smoke.sh): ws and wss (TLS
 * through the NDK-built libneton_openssl.so), each an echo server and a client in one process over loopback; requests
 * of 0 B to 1 MB and 100 concurrent ones, followed by lifecycle regressions; exit status 0 when all pass. No zstd: zstd-kmp's JVM artifact
 * carries desktop native libraries only.
 */
fun main() {
    val start = TimeSource.Monotonic.markNow()
    var failures = 0
    runReactor {
        withTimeout(60.seconds) {
            println("vm ${System.getProperty("java.vm.name")} ${System.getProperty("java.vm.version")}, ${OpenSsl.version}")
            failures += round("ws", WebSocketServerTransport("127.0.0.1", 0)) { port -> WebSocketClientTransport("ws://127.0.0.1:$port/") }
            val pki = wssTestPki()
            val serverTls = TlsContext(server = true, certificateChainPem = pki.certificatePem.encodeToByteArray(),
                privateKeyPem = pki.keyPem.encodeToByteArray(), alpnProtocols = listOf("http/1.1"))
            val clientTls = TlsContext(server = false, trustRootsPem = pki.trustPem.encodeToByteArray(), alpnProtocols = listOf("http/1.1"))
            val wssServer = WebSocketServerTransport("127.0.0.1", 0, tlsAcceptor = { tcp: IoStream -> tlsAccept(tcp, serverTls) })
            failures += round("wss", wssServer) { port -> WebSocketClientTransport("wss://localhost:$port/", tlsConnector = WssConnector(clientTls)) }
            serverTls.close(); clientTls.close()
        }
    }
    WebSocketLifecycleTest().apply {
        handshakeAdmissionIsBoundedAndPermitsAreReused()
        pendingQueueRejectsOverflowAndCloseReleasesQueuedSockets()
        cancellingTheOwnerReleasesQueuedAndHandshakingConnections()
        blockedPingAndBlockedApplicationWriteAreAborted()
        failedPingIsContainedAndCloseIsIdempotent()
    }
    println("lifecycle regressions: ok")
    println("${if (failures == 0) "PASS" else "FAIL ($failures)"} in ${start.elapsedNow()}")
    exitProcess(if (failures == 0) 0 else 1)
}

/** One echo server and client over [server]; returns the number of mismatches. */
private suspend fun CoroutineScope.round(name: String, server: WebSocketServerTransport, client: (Int) -> WebSocketClientTransport): Int {
    var failures = 0
    val bound = Transport.bind(this, server) { conn -> conn.onRequest { payload, _ -> payload } }
    val accepting = launch { bound.acceptLoop() }
    val conn = Transport.connect(this, client(server.localAddress!!.port))
    for (size in listOf(0, 1, 1200, 70_000, 1_000_000)) {
        val payload = Random(size).nextBytes(size)
        val ok = conn.request(payload, bizType = 1).contentEquals(payload)
        if (!ok) failures++
        println("$name request $size bytes: ${if (ok) "ok" else "MISMATCH"}")
    }
    val replies = List(100) { i -> async { conn.request("r$i".encodeToByteArray()).decodeToString() } }.awaitAll()
    val concurrentOk = replies == List(100) { "r$it" }
    if (!concurrentOk) failures++
    println("$name 100 concurrent requests: ${if (concurrentOk) "ok" else "MISMATCH"}")
    conn.close()
    accepting.cancelAndJoin()
    bound.close()
    return failures
}
