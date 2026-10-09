package msgtrans.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import msgtrans.core.Compression
import msgtrans.core.Packet
import msgtrans.core.PacketCodec
import neton.io.bytes.Buffer
import neton.io.bytes.Bytes
import neton.io.core.IoStream
import neton.io.net.ConnectException
import neton.io.net.runReactor
import neton.openssl.TlsContext
import neton.quic.testkit.TestCa
import neton.tls.tlsAccept
import neton.tls.websocket.WssConnector
import neton.websocket.Message
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The msgtrans session over WebSocket between two Kotlin endpoints: the same API and contracts as over TCP. */
class WebSocketTransportTest {

    /** An echo server on an OS-chosen port: requests answered with their payload, one-way echoed back one-way. */
    private suspend fun CoroutineScope.echoServer(
        transport: WebSocketServerTransport = WebSocketServerTransport("127.0.0.1", 0),
        closed: Channel<Unit>? = null,
        block: suspend (port: Int) -> Unit,
    ) {
        val server = Transport.bind(this, transport) { conn ->
            conn.onRequest { payload, _ -> payload }
            conn.launch { conn.events().collect { conn.send(it.payload, it.bizType) } }
            if (closed != null) conn.onClose { closed.trySend(Unit) }
        }
        val accepting = launch { server.acceptLoop() }
        try {
            block(transport.localAddress!!.port)
        } finally {
            accepting.cancelAndJoin()
            server.close()
        }
    }

    private fun test(block: suspend CoroutineScope.() -> Unit) = runReactor { withTimeout(60.seconds) { block() } }

    @Test
    fun requestResponseAndPushOverWebSocket() = test {
        echoServer { port ->
            val conn = Transport.connect(this, WebSocketClientTransport("ws://127.0.0.1:$port/"))
            assertEquals("ping", conn.request("ping".encodeToByteArray(), bizType = 7).decodeToString())
            val pushed = Channel<Pair<Int, String>>(Channel.UNLIMITED)
            conn.launch { conn.events().collect { pushed.send(it.bizType to it.payload.decodeToString()) } }
            conn.send("one-way".encodeToByteArray(), bizType = 42)
            assertEquals(42 to "one-way", pushed.receive())
            conn.close()
        }
    }

    @Test
    fun largeAndCompressedPayloads() = test {
        echoServer { port ->
            val conn = Transport.connect(this, WebSocketClientTransport("ws://localhost:$port/"))
            for (size in listOf(0, 1, 1200, 70_000, 3_000_000)) {
                val payload = Random(size).nextBytes(size)
                assertContentEquals(payload, conn.request(payload, bizType = 1), "$size bytes")
            }
            val text = "msgtrans over WebSocket ".repeat(5000).encodeToByteArray()
            for (compression in listOf(Compression.Zstd, Compression.Zlib)) {
                assertContentEquals(text, conn.request(text, bizType = 2, compression = compression), "$compression")
            }
            conn.close()
        }
    }

    @Test
    fun manyConcurrentRequestsAndSessions() = test {
        echoServer { port ->
            val sessions = List(4) { Transport.connect(this, WebSocketClientTransport("ws://127.0.0.1:$port/")) }
            val replies = sessions.flatMapIndexed { s, conn ->
                List(50) { i -> async { conn.request("s$s-r$i".encodeToByteArray()).decodeToString() } }
            }.awaitAll()
            assertEquals(sessions.indices.flatMap { s -> List(50) { i -> "s$s-r$i" } }, replies)
            sessions.forEach { it.close() }
        }
    }

    @Test
    fun anotherPathIsNotFound() = test {
        echoServer(WebSocketServerTransport("127.0.0.1", 0, path = "/msgtrans")) { port ->
            val e = assertFailsWith<ConnectException> { Transport.connect(this, WebSocketClientTransport("ws://127.0.0.1:$port/other")) }
            assertEquals(true, "404" in e.message!!, e.message)
            val conn = Transport.connect(this, WebSocketClientTransport("ws://127.0.0.1:$port/msgtrans"))
            assertEquals("ok", conn.request("ok".encodeToByteArray()).decodeToString())
            conn.close()
        }
    }

    @Test
    fun wss() = test {
        val ca = TestCa.create("msgtrans websocket test CA")
        val identity = ca.issue("localhost", dnsNames = listOf("localhost"), ipAddresses = listOf("127.0.0.1"))
        val serverTls = TlsContext(server = true, certificateChainPem = identity.certPem.encodeToByteArray(),
            privateKeyPem = identity.keyPem.encodeToByteArray(), alpnProtocols = listOf("http/1.1"))
        val clientTls = TlsContext(server = false, trustRootsPem = ca.identity.certPem.encodeToByteArray(), alpnProtocols = listOf("http/1.1"))
        val transport = WebSocketServerTransport("127.0.0.1", 0, tlsAcceptor = { tlsAccept(it, serverTls) })
        echoServer(transport) { port ->
            val conn = Transport.connect(this, WebSocketClientTransport("wss://localhost:$port/", tlsConnector = WssConnector(clientTls)))
            val payload = Random(1).nextBytes(100_000)
            assertContentEquals(payload, conn.request(payload))
            conn.close()
        }
        clientTls.close()
        serverTls.close()
    }

    /** A peer that never reads never answers pings: the server ends its session after the pong timeout. */
    @Test
    fun anUnansweredPingEndsTheSession() = test {
        val closed = Channel<Unit>(1)
        val options = WebSocketTransportOptions(pingInterval = 100.milliseconds, pongTimeout = 200.milliseconds)
        echoServer(WebSocketServerTransport("127.0.0.1", 0, options = options), closed) { port ->
            val (silent, _) = neton.websocket.connect("ws://127.0.0.1:$port/")
            withTimeout(5.seconds) { closed.receive() }
            silent.abort()
        }
    }

    /** A live session is kept: pings are answered while the session reads. */
    @Test
    fun answeredPingsKeepTheSession() = test {
        val options = WebSocketTransportOptions(pingInterval = 50.milliseconds, pongTimeout = 200.milliseconds)
        echoServer(WebSocketServerTransport("127.0.0.1", 0, options = options)) { port ->
            val conn = Transport.connect(this, WebSocketClientTransport("ws://127.0.0.1:$port/", options))
            repeat(10) {
                kotlinx.coroutines.delay(100)
                assertEquals("still", conn.request("still".encodeToByteArray()).decodeToString())
            }
            conn.close()
        }
    }

    @Test
    fun aTextMessageOrAPartialPacketEndsOnlyThatSession() = test {
        val closed = Channel<Unit>(Channel.UNLIMITED)
        echoServer(closed = closed) { port ->
            val good = Transport.connect(this, WebSocketClientTransport("ws://127.0.0.1:$port/"))
            val bad = listOf(
                Message.Text("not a packet"),
                Message.Binary(Bytes.wrap(Buffer().also { PacketCodec.Default.encode(Packet.request("abc".encodeToByteArray(), 1, 1u), it) }.peekAll().copyOf(10))),
            )
            for (message in bad) {
                val (raw, _) = neton.websocket.connect("ws://127.0.0.1:$port/")
                raw.send(message)
                withTimeout(5.seconds) { closed.receive() }
                raw.abort()
            }
            assertEquals("alive", good.request("alive".encodeToByteArray()).decodeToString())
            good.close()
        }
    }
}
