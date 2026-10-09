package msgtrans.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import msgtrans.core.Compression
import neton.io.net.ConnectException
import neton.io.net.runReactor
import neton.quic.testkit.TestCa
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.seconds

/** The msgtrans session over QUIC between two Kotlin endpoints: the same API and contracts as over TCP. */
class QuicTransportTest {
    private val ca = TestCa.create("msgtrans quic test CA")
    private val identity = ca.issue("localhost", dnsNames = listOf("localhost"), ipAddresses = listOf("127.0.0.1"))

    /** An echo server on an OS-chosen port: requests answered with their payload, one-way echoed back one-way. */
    private suspend fun CoroutineScope.echoServer(block: suspend (port: Int) -> Unit) {
        val transport = QuicServerTransport("127.0.0.1", 0, identity.chainWith(ca.identity), identity.privateKey)
        val server = Transport.bind(this, transport) { conn ->
            conn.onRequest { payload, _ -> payload }
            conn.launch { conn.events().collect { conn.send(it.payload, it.bizType) } }
        }
        val accepting = launch { server.acceptLoop() }
        try {
            block(transport.localAddress!!.port)
        } finally {
            accepting.cancelAndJoin()
            server.close()
            transport.close()
        }
    }

    private fun test(block: suspend CoroutineScope.() -> Unit) = runReactor { withTimeout(60.seconds) { block() } }

    @Test
    fun requestResponseAndPushOverQuic() = test {
        echoServer { port ->
            QuicClientTransport("127.0.0.1", port, ca.trustAnchors).use { client ->
                val conn = Transport.connect(this, client)
                assertEquals("ping", conn.request("ping".encodeToByteArray(), bizType = 7).decodeToString())

                val pushed = Channel<Pair<Int, String>>(Channel.UNLIMITED)
                conn.launch { conn.events().collect { pushed.send(it.bizType to it.payload.decodeToString()) } }
                conn.send("one-way".encodeToByteArray(), bizType = 42)
                assertEquals(42 to "one-way", pushed.receive())
                conn.close()
            }
        }
    }

    @Test
    fun largeAndCompressedPayloads() = test {
        echoServer { port ->
            QuicClientTransport("localhost", port, ca.trustAnchors).use { client ->
                val conn = Transport.connect(this, client)
                for (size in listOf(0, 1, 1200, 70_000, 3_000_000)) {
                    val payload = Random(size).nextBytes(size)
                    assertContentEquals(payload, conn.request(payload, bizType = 1), "$size bytes")
                }
                val text = "msgtrans over QUIC ".repeat(5000).encodeToByteArray()
                for (compression in listOf(Compression.Zstd, Compression.Zlib)) {
                    assertContentEquals(text, conn.request(text, bizType = 2, compression = compression), "$compression")
                }
                conn.close()
            }
        }
    }

    @Test
    fun manyConcurrentRequestsAndSessions() = test {
        echoServer { port ->
            QuicClientTransport("127.0.0.1", port, ca.trustAnchors).use { client ->
                val sessions = List(4) { Transport.connect(this, client) }
                val replies = sessions.flatMapIndexed { s, conn ->
                    List(50) { i -> async { conn.request("s$s-r$i".encodeToByteArray()).decodeToString() } }
                }.awaitAll()
                assertEquals(sessions.indices.flatMap { s -> List(50) { i -> "s$s-r$i" } }, replies)
                sessions.forEach { it.close() }
            }
        }
    }

    /**
     * `localhost` may resolve to `::1` first; over UDP nothing refuses that attempt, so the IPv4 address must be tried
     * beside it (RFC 8305) rather than after the whole connect timeout.
     */
    @Test
    fun localhostReachesAnIpv4OnlyServerWithoutWaitingOutTheTimeout() = test {
        echoServer { port ->
            QuicClientTransport("localhost", port, ca.trustAnchors).use { client ->
                val start = TimeSource.Monotonic.markNow()
                val conn = Transport.connect(this, client)
                assertTrue(start.elapsedNow() < 3.seconds, "connecting took ${start.elapsedNow()}")
                assertEquals("hi", conn.request("hi".encodeToByteArray()).decodeToString())
                conn.close()
            }
        }
    }

    @Test
    fun anUntrustedServerIsRefused() = test {
        echoServer { port ->
            val other = TestCa.create("unrelated CA")
            QuicClientTransport("127.0.0.1", port, other.trustAnchors).use { client ->
                assertFailsWith<ConnectException> { Transport.connect(this, client) }
            }
        }
    }

    @Test
    fun theSessionEndsWhenThePeerCloses() = test {
        val closed = Channel<Unit>(1)
        val transport = QuicServerTransport("127.0.0.1", 0, identity.chainWith(ca.identity), identity.privateKey)
        val server = Transport.bind(this, transport) { conn ->
            conn.onRequest { payload, _ -> payload }
            conn.onClose { closed.trySend(Unit) }
        }
        val accepting = launch { server.acceptLoop() }
        QuicClientTransport("127.0.0.1", transport.localAddress!!.port, ca.trustAnchors).use { client ->
            val conn = Transport.connect(this, client)
            conn.request("hello".encodeToByteArray())
            conn.close()
            closed.receive() // the server's session observed the close
        }
        accepting.cancelAndJoin()
        server.close()
        transport.close()
    }
}
