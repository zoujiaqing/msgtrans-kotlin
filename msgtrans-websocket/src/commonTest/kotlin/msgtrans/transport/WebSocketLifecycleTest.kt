package msgtrans.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import msgtrans.core.Packet
import msgtrans.core.PacketCodec
import neton.io.bytes.Buffer
import neton.io.core.IoException
import neton.io.core.IoStream
import neton.io.core.memoryStreamPair
import neton.io.net.connect
import neton.io.net.runReactor
import neton.websocket.Role
import neton.websocket.WebSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class WebSocketLifecycleTest {
    private fun test(block: suspend CoroutineScope.() -> Unit) = runReactor { withTimeout(10.seconds) { block() } }

    @Test
    fun cancellingTheOwnerReleasesQueuedAndHandshakingConnections() = test {
        val transport = WebSocketServerTransport("127.0.0.1", 0,
            options = WebSocketTransportOptions(pingInterval = null))
        val listening = CompletableDeferred<ServerTransport.Acceptor>()
        val owner = launch {
            listening.complete(transport.listen())
            awaitCancellation()
        }
        val acceptor = listening.await()
        val queued = neton.websocket.connect("ws://127.0.0.1:${transport.localAddress!!.port}/").first
        val slow = connect("127.0.0.1", transport.localAddress!!.port)
        try {
            owner.cancelAndJoin()
            withTimeout(2.seconds) {
                try { while (queued.receive() != null) {} } catch (_: neton.websocket.WebSocketException) {}
                try { assertEquals(-1, slow.read(Buffer())) } catch (_: IoException) {}
            }
        } finally { owner.cancelAndJoin(); acceptor.close(); queued.abort(); slow.close() }
    }

    @Test
    fun handshakeAdmissionIsBoundedAndPermitsAreReused() = test {
        val entered = Channel<Unit>(Channel.UNLIMITED)
        val release = Channel<Unit>(Channel.UNLIMITED)
        var active = 0
        var highWater = 0
        val transport = WebSocketServerTransport("127.0.0.1", 0,
            options = WebSocketTransportOptions(maxConcurrentHandshakes = 2),
            tlsAcceptor = {
                active++
                highWater = maxOf(highWater, active)
                entered.trySend(Unit)
                try { release.receive(); throw IoException("test handshake rejected") }
                finally { active-- }
            })
        val acceptor = transport.listen()
        val peers = mutableListOf<IoStream>()
        try {
            repeat(3) { peers += connect("127.0.0.1", transport.localAddress!!.port) }
            repeat(2) { entered.receive() }
            delay(100)
            assertTrue(entered.tryReceive().isFailure)
            assertEquals(2, active)
            release.send(Unit)
            entered.receive()
            assertEquals(2, highWater)
        } finally { acceptor.close(); peers.forEach { it.close() } }
        withTimeout(2.seconds) { while (active != 0) delay(10) }
    }

    @Test
    fun pendingQueueRejectsOverflowAndCloseReleasesQueuedSockets() = test {
        val transport = WebSocketServerTransport("127.0.0.1", 0,
            options = WebSocketTransportOptions(pingInterval = null, maxPendingConnections = 1))
        val acceptor = transport.listen()
        val peers = mutableListOf<WebSocket>()
        try {
            val url = "ws://127.0.0.1:${transport.localAddress!!.port}/"
            peers += neton.websocket.connect(url).first
            peers += neton.websocket.connect(url).first
            // The second upgrade finishes, but cannot enter the full ready queue.
            withTimeout(2.seconds) {
                try { while (peers[1].receive() != null) {} } catch (_: neton.websocket.WebSocketException) {}
            }
            acceptor.close()
            withTimeout(2.seconds) {
                try { while (peers[0].receive() != null) {} } catch (_: neton.websocket.WebSocketException) {}
            }
        } finally { acceptor.close(); peers.forEach { it.abort() } }
    }

    @Test
    fun blockedPingAndBlockedApplicationWriteAreAborted() = test {
        for (mode in 0..2) {
            val applicationWrite = mode != 0
            val gracefulClose = mode == 2
            val (a, peer) = memoryStreamPair()
            val writing = CompletableDeferred<Unit>()
            val closed = CompletableDeferred<Unit>()
            val blocked = object : IoStream by a {
                override suspend fun write(src: Buffer): Int { writing.complete(Unit); awaitCancellation() }
                override fun close() { closed.complete(Unit); a.close() }
            }
            val ws = WebSocket.fromRawStream(blocked, Role.Server)
            val stream = WebSocketPacketStream.start(ws,
                WebSocketTransportOptions(pingInterval = if (gracefulClose) null else 50.milliseconds, pongTimeout = 100.milliseconds))
            try {
                val writer = if (applicationWrite) async(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        stream.write(Buffer().also { PacketCodec.Default.encode(Packet.request(byteArrayOf(1), 1, 1u), it) })
                        false
                    } catch (_: IoException) { true }
                } else null
                writing.await()
                if (gracefulClose) stream.close()
                withTimeout(2.seconds) { closed.await() }
                if (writer != null) assertTrue(writer.await())
                assertFalse(ws.canWrite)
                stream.close()
                stream.close()
            } finally { stream.close(); peer.close() }
        }
    }

    @Test
    fun failedPingIsContainedAndCloseIsIdempotent() = test {
        val (a, peer) = memoryStreamPair()
        val closed = CompletableDeferred<Unit>()
        val failing = object : IoStream by a {
            override suspend fun write(src: Buffer): Int = throw IoException("connection reset")
            override fun close() { closed.complete(Unit); a.close() }
        }
        val ws = WebSocket.fromRawStream(failing, Role.Server)
        val stream = WebSocketPacketStream.start(ws,
            WebSocketTransportOptions(pingInterval = 20.milliseconds, pongTimeout = 100.milliseconds))
        try {
            closed.await()
            delay(100)
            assertFalse(ws.canWrite)
            stream.close()
            stream.close()
        } finally { stream.close(); peer.close() }
    }
}
