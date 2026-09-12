package msgtrans.transport

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import neton.io.net.runReactor
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Outbound path contract that must hold for every [WriteMode]: FIFO order across concurrent
 * senders on one connection, bounded queue (senders suspend, nothing dropped), and request/response
 * interleaved with one-way traffic. Runs for both modes in one process.
 */
class WriteModeTest {

    private fun exercise(mode: WriteMode, port: Int) = runReactor {
        val received = ArrayList<String>()
        val server = Transport.bind(this, "127.0.0.1", port) { conn ->
            conn.onRequest { payload, _ -> payload }
            conn.launch { conn.events().collect { received.add(it.payload.decodeToString()) } }
        }
        val serverJob = launch { server.acceptLoop() }

        val conn = Connection(neton.io.net.connect("127.0.0.1", port), this, mailboxCapacity = 4, writeMode = mode)
        conn.start()
        val senders = (0 until 4).map { s ->
            launch { repeat(50) { i -> conn.send("s$s-$i".encodeToByteArray()) } }
        }
        // Requests interleave with the one-way flood and must still complete correctly.
        repeat(20) { i -> assertEquals("r$i", conn.request("r$i".encodeToByteArray()).decodeToString()) }
        senders.forEach { it.join() }
        conn.request("sync".encodeToByteArray()) // one round trip after the flood lands the events

        assertEquals(200, received.size, "mode=$mode received=${received.size}")
        for (s in 0 until 4) {
            val mine = received.filter { it.startsWith("s$s-") }
            assertEquals((0 until 50).map { "s$s-$it" }, mine, "per-sender FIFO broken in mode=$mode")
        }
        conn.close()
        serverJob.cancelAndJoin()
        server.close()
    }

    @Test fun channelMode() = exercise(WriteMode.CHANNEL, 39510)
    @Test fun inlineMode() = exercise(WriteMode.INLINE, 39511)
}
