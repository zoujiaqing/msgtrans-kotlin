package msgtrans.transport

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import neton.io.net.runReactor
import kotlin.test.Test
import kotlin.test.assertFailsWith

// Test ports are deliberately below 32768, outside the kernel's ephemeral range
// (/proc/sys/net/ipv4/ip_local_port_range, typically 32768-60999). A fixed listen port
// inside that range intermittently loses the bind to some other process's outbound
// connection, which SO_REUSEADDR does not help with — it surfaces as a flaky EADDRINUSE.
/**
 * P1-1 acceptance: the request deadline covers the whole call, including the wait to get the
 * packet onto the outbound path.
 *
 * request() documents one deadline for the entire call, but the timer only ever woke a coroutine
 * parked for an in-flight slot. A caller parked in enqueue — waiting for mailbox room in INLINE
 * mode, or inside a full outbound channel in CHANNEL mode — was not woken at all, so against a
 * peer that stopped reading the call blocked indefinitely regardless of its timeout.
 */
class RequestDeadlineTest {

    private fun jammedConnection(mode: WriteMode, port: Int) = runReactor {
        val listener = neton.io.net.listen("127.0.0.1", port)
        // Accept the connection and then never read from it. The peer's receive buffer and our
        // send buffer fill, the writer stops draining, and the outbound path backs up for real.
        val acceptJob = launch {
            val peer = listener.accept()
            delay(60_000)
            peer.close()
        }

        val conn = Connection(
            neton.io.net.connect("127.0.0.1", port),
            this,
            ConnectionConfig(mailboxCapacity = 1, writeMode = mode, requestTimeoutMillis = 300),
        )
        conn.start()

        val big = ByteArray(64 * 1024)
        // Concurrent senders, not one sequential loop: the first to arrive becomes the writer and
        // parks inside the socket write, and only *other* coroutines can then pile up in the
        // mailbox. A single sequential sender would block on its own first write and never queue
        // anything, leaving the outbound queue empty and this test green for the wrong reason.
        val flood = (0 until 8).map { launch { repeat(50) { conn.send(big) } } }
        // Let the kernel buffers and then the mailbox fill up.
        delay(500)

        try {
            // The outer timeout turns the old behaviour into a reported failure rather than a
            // hang: without the fix the request never returns at all.
            withTimeout(5_000) {
                assertFailsWith<RequestTimeoutException> {
                    conn.request("ping".encodeToByteArray(), timeoutMillis = 300)
                }
            }
        } finally {
            flood.forEach { it.cancel() }
            conn.close()
            acceptJob.cancelAndJoin()
            listener.close()
        }
    }

    @Test fun sendQueueFullRequestTimesOutInline() = jammedConnection(WriteMode.INLINE, 19550)
    @Test fun sendQueueFullRequestTimesOutChannel() = jammedConnection(WriteMode.CHANNEL, 19551)
}
