package msgtrans.transport

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import neton.io.net.runReactor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// Test ports are deliberately below 32768, outside the kernel's ephemeral range
// (/proc/sys/net/ipv4/ip_local_port_range, typically 32768-60999). A fixed listen port
// inside that range intermittently loses the bind to some other process's outbound
// connection, which SO_REUSEADDR does not help with — it surfaces as a flaky EADDRINUSE.
/**
 * P1-4 acceptance: one connection's failure is contained to that connection.
 *
 * Every accepted connection used to share the server's [kotlinx.coroutines.CoroutineScope], so an
 * exception escaping a read or handler coroutine cancelled the shared job and with it *every other
 * connection on the server*. A single peer sending a malformed frame, or a single business handler
 * throwing, was enough to take the whole server down.
 */
class FaultIsolationTest {

    @Test
    fun oneConnectionCrashDoesNotKillOthers() = runReactor {
        val port = 19530
        val server = Transport.bind(this, "127.0.0.1", port) { conn ->
            conn.onRequest { payload, _ ->
                if (payload.decodeToString() == "boom") error("handler exploded")
                ("ok:" + payload.decodeToString()).encodeToByteArray()
            }
        }
        val serverJob = launch { server.acceptLoop() }

        // A short timeout keeps the test quick if the victim's connection is torn down without
        // a response, which is the expected outcome — the protocol has no error packet.
        val cfg = ConnectionConfig(requestTimeoutMillis = 3_000)
        val victim = Transport.connect(this, "127.0.0.1", port, cfg)
        val bystander = Transport.connect(this, "127.0.0.1", port, cfg)

        try {
            // Both connections are healthy to begin with.
            assertEquals("ok:hi", bystander.request("hi".encodeToByteArray()).decodeToString())

            // The victim's handler throws. Its own request must fail...
            assertFailsWith<Exception> { victim.request("boom".encodeToByteArray()) }

            // ...and the bystander must be completely unaffected. Before the fix this request
            // failed too, because the victim's exception had cancelled the shared server scope.
            assertEquals(
                "ok:still-here",
                bystander.request("still-here".encodeToByteArray()).decodeToString(),
            )

            // The server also keeps accepting brand-new connections after the crash.
            val latecomer = Transport.connect(this, "127.0.0.1", port, cfg)
            assertEquals("ok:late", latecomer.request("late".encodeToByteArray()).decodeToString())
            latecomer.close()
        } finally {
            bystander.close()
            victim.close()
            serverJob.cancelAndJoin()
            server.close()
        }
    }
}
