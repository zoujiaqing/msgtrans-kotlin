package msgtrans.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import neton.io.net.runReactor
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.Worker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// Port below 32768, outside the ephemeral range (see FaultIsolationTest).
/**
 * SPEC §10: `bind(..., reactors = 2)` spreads connections over two reactor threads, request /
 * response works on both, and cancelling the server scope closes the connections on *both*
 * reactors — the per-connection SupervisorJob is parented to the server scope across threads.
 */
@OptIn(ObsoleteWorkersApi::class)
class MultiReactorServerTest {

    @Test
    fun connectionsSpreadOverReactorsAndServerScopeCancelClosesThemAll() = runReactor {
        val port = 19560
        val workerIds = AtomicReference<Set<Int>>(emptySet())
        val closedOnServer = AtomicInt(0)
        val serverScope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))

        val server = Transport.bind(serverScope, "127.0.0.1", port, reactors = 2) { conn ->
            // Runs on the connection's reactor thread (SPEC §10), so shared state is atomic.
            val id = Worker.current.id
            while (true) { val cur = workerIds.value; if (workerIds.compareAndSet(cur, cur + id)) break }
            conn.onRequest { payload, _ -> ("echo:" + payload.decodeToString()).encodeToByteArray() }
            conn.onClose { closedOnServer.incrementAndGet() }
        }
        assertEquals(2, server.reactors)
        serverScope.launch { server.acceptLoop() }

        val cfg = ConnectionConfig(requestTimeoutMillis = 3_000)
        val clients = (0 until 8).map { Transport.connect(this, "127.0.0.1", port, cfg) }
        clients.forEachIndexed { i, c ->
            assertEquals("echo:$i", c.request("$i".encodeToByteArray()).decodeToString())
        }
        assertTrue(workerIds.value.size >= 2, "expected connections on >= 2 reactor threads, saw ${workerIds.value}")

        // Stop accepting, then cancel the server scope: every connection on every reactor closes.
        server.close()
        serverScope.cancel()
        server.awaitReactors()
        for (i in 0 until 100) { if (closedOnServer.value == 8) break; delay(10) }
        assertEquals(8, closedOnServer.value, "every server-side connection must have closed")
        for (c in clients) {
            assertFailsWith<Exception> { c.request("after".encodeToByteArray()) }
            c.close()
        }
    }
}
