package msgtrans.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import neton.io.net.runReactor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Terminal-state and backpressure contract for the transport: request timeout, in-flight cap,
 * close failing pending requests, and cancellation cleaning up the registry.
 */
class ContractTest {

    @Test
    fun requestTimesOutWithoutResponseAndConnectionStaysUp() = runReactor {
        val port = 39520
        // Handler parks forever, so no response is produced (a connection with no onRequest would
        // auto-reply empty, which is not "no answer").
        val server = Transport.bind(this, "127.0.0.1", port) { conn ->
            conn.onRequest { _, _ -> CompletableDeferred<ByteArray>().await() }
        }
        val serverJob = launch { server.acceptLoop() }

        val conn = Transport.connect(this, "127.0.0.1", port,
            ConnectionConfig(requestTimeoutMillis = 100))
        assertFailsWith<RequestTimeoutException> { conn.request("hi".encodeToByteArray()) }
        // A second request also times out (connection still usable, not wedged).
        assertFailsWith<RequestTimeoutException> { conn.request("hi2".encodeToByteArray(), timeoutMillis = 100) }

        conn.close()
        serverJob.cancelAndJoin()
        server.close()
    }

    @Test
    fun closeFailsPendingRequests() = runReactor {
        val port = 39521
        val server = Transport.bind(this, "127.0.0.1", port) { conn ->
            conn.onRequest { _, _ -> CompletableDeferred<ByteArray>().await() }
        }
        val serverJob = launch { server.acceptLoop() }

        val conn = Transport.connect(this, "127.0.0.1", port,
            ConnectionConfig(requestTimeoutMillis = 0)) // no timeout, so close is what ends it
        val pending = launch {
            assertFailsWith<ConnectionClosedException> { conn.request("x".encodeToByteArray()) }
        }
        // Give the request time to be registered, then close.
        withTimeoutOrNull(200) { launch { }.join() }
        conn.close()
        pending.join()

        serverJob.cancelAndJoin()
        server.close()
    }

    @Test
    fun inFlightCapBoundsOutstandingRequests() = runReactor {
        val port = 39522
        var maxSeen = 0
        var concurrent = 0
        // The handler holds each request open until we release it, so requests pile up — but the
        // client cap must keep the number actually outstanding at or below the limit.
        val server = Transport.bind(this, "127.0.0.1", port) { conn ->
            conn.onRequest { payload, _ ->
                concurrent++
                if (concurrent > maxSeen) maxSeen = concurrent
                // brief hold so several can overlap
                kotlinx.coroutines.delay(20)
                concurrent--
                payload
            }
        }
        val serverJob = launch { server.acceptLoop() }

        val conn = Transport.connect(this, "127.0.0.1", port,
            ConnectionConfig(maxInFlightRequests = 3, requestTimeoutMillis = 5_000))
        val callers = (0 until 20).map { launch { conn.request("r".encodeToByteArray()) } }
        callers.forEach { it.join() }
        assertTrue(maxSeen <= 3, "in-flight cap exceeded: sawMax=$maxSeen")

        conn.close()
        serverJob.cancelAndJoin()
        server.close()
    }

    @Test
    fun cancellingRequestRemovesItFromRegistry() = runReactor {
        val port = 39523
        val server = Transport.bind(this, "127.0.0.1", port) { conn ->
            conn.onRequest { _, _ -> CompletableDeferred<ByteArray>().await() }
        }
        val serverJob = launch { server.acceptLoop() }

        val conn = Transport.connect(this, "127.0.0.1", port,
            ConnectionConfig(maxInFlightRequests = 2, requestTimeoutMillis = 0))
        // Fill the in-flight slots with cancellable requests, then cancel them.
        val a = launch { conn.request("a".encodeToByteArray()) }
        val b = launch { conn.request("b".encodeToByteArray()) }
        withTimeoutOrNull(100) { launch { }.join() }
        a.cancelAndJoin()
        b.cancelAndJoin()
        // If the registry was cleaned up, a fresh request can take a slot and time out normally.
        val c = withTimeoutOrNull(1_000) {
            assertFailsWith<RequestTimeoutException> {
                conn.request("c".encodeToByteArray(), timeoutMillis = 100)
            }
            "done"
        }
        assertEquals("done", c, "registry/in-flight slots were not released on cancel")

        conn.close()
        serverJob.cancelAndJoin()
        server.close()
    }
}
