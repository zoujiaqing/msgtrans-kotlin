package msgtrans.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import neton.io.net.runReactor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// Test ports are deliberately below 32768, outside the kernel's ephemeral range
// (/proc/sys/net/ipv4/ip_local_port_range, typically 32768-60999). A fixed listen port
// inside that range intermittently loses the bind to some other process's outbound
// connection, which SO_REUSEADDR does not help with — it surfaces as a flaky EADDRINUSE.
/**
 * P1-6 acceptance: the outbound path is bounded in bytes, not just in packet count.
 *
 * mailboxCapacity counts elements, so with variable-size payloads it bounded nothing — the
 * documented ceiling of mailboxCapacity × maxPayloadLength is 16 GiB at the defaults. And
 * maxPayloadLength was checked only on decode, so any size at all could be queued outbound.
 */
class OutboundBudgetTest {

    /** A peer that accepts and never reads, so nothing drains and the budget is what stops us. */
    private fun jammed(mode: WriteMode, port: Int, budget: Long, block: suspend CoroutineScope.(Connection) -> Unit) = runReactor {
        val listener = neton.io.net.listen("127.0.0.1", port)
        val acceptJob = launch {
            val peer = listener.accept()
            delay(60_000)
            peer.close()
        }
        val conn = Connection(
            neton.io.net.connect("127.0.0.1", port),
            this,
            ConnectionConfig(
                mailboxCapacity = 1024,      // deliberately generous: bytes must be the limit
                writeMode = mode,
                maxOutboundBytes = budget,
                requestTimeoutMillis = 500,
            ),
        )
        conn.start()
        try {
            block(this, conn)
        } finally {
            conn.close()
            acceptJob.cancelAndJoin()
            listener.close()
        }
    }

    @Test
    fun oversizePayloadIsRejectedNotQueued() = jammed(WriteMode.INLINE, 19560, budget = 1L * 1024 * 1024) { conn ->
        val tooBig = ByteArray(2 * 1024 * 1024)
        val ex = assertFailsWith<PayloadTooLargeException> { conn.send(tooBig) }
        assertEquals(tooBig.size.toLong(), ex.bytes)
        // The connection is still usable: rejecting a payload is not a connection fault.
        conn.send(ByteArray(16))
    }

    private suspend fun CoroutineScope.assertSendersParkOnBytes(conn: Connection, budget: Long) {
        // One shared buffer, smaller than the budget so the "an empty queue always accepts one
        // packet" escape hatch never applies and the ceiling holds strictly. The total offered is
        // far larger than any loopback socket buffer, so senders must eventually pile up:
        // inferring the queue depth from how many senders are parked does not work, because how
        // much the kernel absorbs before that differs per platform.
        val chunk = ByteArray(64 * 1024)
        val senders = (0 until 512).map { launch { conn.send(chunk) } }

        var peak = 0L
        repeat(20) {
            delay(25)
            if (conn.outboundQueuedBytes > peak) peak = conn.outboundQueuedBytes
        }

        assertTrue(
            peak <= budget,
            "outbound queue held $peak bytes, above the $budget byte budget",
        )
        assertTrue(
            senders.any { !it.isCompleted },
            "no sender was held back after offering ${512 * chunk.size} bytes; " +
                "the byte budget is not being enforced",
        )
        senders.forEach { it.cancel() }
    }

    @Test
    fun sendersParkOnTheByteBudgetInline() =
        jammed(WriteMode.INLINE, 19561, budget = 256L * 1024) { conn ->
            withTimeout(10_000) { assertSendersParkOnBytes(conn, 256L * 1024) }
        }

    @Test
    fun sendersParkOnTheByteBudgetChannel() =
        jammed(WriteMode.CHANNEL, 19562, budget = 256L * 1024) { conn ->
            withTimeout(10_000) { assertSendersParkOnBytes(conn, 256L * 1024) }
        }
}
