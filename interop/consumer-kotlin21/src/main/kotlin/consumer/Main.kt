package consumer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import msgtrans.transport.Connection
import msgtrans.transport.QuicClientTransport
import msgtrans.transport.QuicServerTransport
import msgtrans.transport.Transport
import msgtrans.transport.WebSocketClientTransport
import msgtrans.transport.WebSocketServerTransport
import neton.io.core.IoStream
import neton.io.net.runReactor
import neton.openssl.OpenSsl
import neton.openssl.TlsContext
import neton.quic.proto.Certificates
import neton.quic.proto.PrivateKey
import neton.tls.tlsAccept
import neton.tls.websocket.WssConnector
import kotlin.random.Random
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

/**
 * Written and compiled the way a Kotlin 2.1 application would use the libraries: msgtrans echo over QUIC, ws and wss
 * on loopback, every payload checked. Exit status 0 when all of them match.
 */
fun main() {
    println("Kotlin ${KotlinVersion.CURRENT}, ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}")
    var failures = 0
    runReactor {
        withTimeout(60.seconds) {
            println(OpenSsl.version)
            failures += quic()
            failures += round("ws", WebSocketServerTransport("127.0.0.1", 0)) { port ->
                WebSocketClientTransport("ws://127.0.0.1:$port/")
            }
            val serverTls = TlsContext(server = true, certificateChainPem = CERTIFICATE.encodeToByteArray(),
                privateKeyPem = KEY.encodeToByteArray(), alpnProtocols = listOf("http/1.1"))
            val clientTls = TlsContext(server = false, trustRootsPem = CERTIFICATE.encodeToByteArray(), alpnProtocols = listOf("http/1.1"))
            val wss = WebSocketServerTransport("127.0.0.1", 0, tlsAcceptor = { tcp: IoStream -> tlsAccept(tcp, serverTls) })
            failures += round("wss", wss) { port -> WebSocketClientTransport("wss://localhost:$port/", tlsConnector = WssConnector(clientTls)) }
            serverTls.close()
            clientTls.close()
        }
    }
    println(if (failures == 0) "PASS" else "FAIL ($failures)")
    exitProcess(if (failures == 0) 0 else 1)
}

private suspend fun CoroutineScope.quic(): Int {
    val transport = QuicServerTransport("127.0.0.1", 0, Certificates.pem(CERTIFICATE), PrivateKey.pem(KEY))
    val server = Transport.bind(this, transport) { conn -> conn.onRequest { payload, _ -> payload } }
    val accepting = launch { server.acceptLoop() }
    val failures = QuicClientTransport("127.0.0.1", transport.localAddress!!.port, Certificates.pem(CERTIFICATE)).use { client ->
        echo("quic", Transport.connect(this, client))
    }
    accepting.cancelAndJoin()
    server.close()
    transport.close()
    return failures
}

private suspend fun CoroutineScope.round(name: String, server: WebSocketServerTransport, client: (Int) -> WebSocketClientTransport): Int {
    val bound = Transport.bind(this, server) { conn -> conn.onRequest { payload, _ -> payload } }
    val accepting = launch { bound.acceptLoop() }
    val failures = echo(name, Transport.connect(this, client(server.localAddress!!.port)))
    accepting.cancelAndJoin()
    bound.close()
    return failures
}

private suspend fun CoroutineScope.echo(name: String, conn: Connection): Int {
    var failures = 0
    for (size in listOf(0, 1, 1200, 70_000, 1_000_000)) {
        val payload = Random(size).nextBytes(size)
        if (!conn.request(payload, bizType = 1).contentEquals(payload)) failures++
    }
    val replies = List(100) { i -> async { conn.request("r$i".encodeToByteArray()).decodeToString() } }.awaitAll()
    if (replies != List(100) { "r$it" }) failures++
    println("$name: ${if (failures == 0) "ok" else "$failures mismatches"}")
    conn.close()
    return failures
}

// A self-signed P-256 certificate for localhost (SAN DNS:localhost, IP:127.0.0.1), valid until 2125; tests only.
private const val CERTIFICATE = """
-----BEGIN CERTIFICATE-----
MIIBmzCCAUGgAwIBAgIULG2s9UnTRxtCMyvJpHmJ9RJOlIUwCgYIKoZIzj0EAwIw
FDESMBAGA1UEAwwJbG9jYWxob3N0MCAXDTI2MDEwMTAwMDAwMFoYDzIxMjUxMjMx
MDAwMDAwWjAUMRIwEAYDVQQDDAlsb2NhbGhvc3QwWTATBgcqhkjOPQIBBggqhkjO
PQMBBwNCAAREcrIqlX250aqRcnA9L6zTi87ChZ1byp0B/vPgn6zsikmM2QkMmFfz
Di3OOv1/uQogxqjp3MAYhNyfGx+uWorOo28wbTAdBgNVHQ4EFgQUeqT0AcLE56Vs
T6miR1Tsn452PJUwHwYDVR0jBBgwFoAUeqT0AcLE56VsT6miR1Tsn452PJUwDwYD
VR0TAQH/BAUwAwEB/zAaBgNVHREEEzARgglsb2NhbGhvc3SHBH8AAAEwCgYIKoZI
zj0EAwIDSAAwRQIgE8UptQolcbP+rsJvP2Ip2M4D6g31ddqnoXD+eHHzxJECIQCo
BPvyRrFNxICEsrPluRCn72kLejlWCI0CfWjrTYZ9JA==
-----END CERTIFICATE-----
"""

private const val KEY = """
-----BEGIN PRIVATE KEY-----
MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgxdQDKpOe7dh4VzxW
5QgRwZxaCqB0Hmjg5DLTv4YFZ6ihRANCAAREcrIqlX250aqRcnA9L6zTi87ChZ1b
yp0B/vPgn6zsikmM2QkMmFfzDi3OOv1/uQogxqjp3MAYhNyfGx+uWorO
-----END PRIVATE KEY-----
"""
