package msgtrans.transport

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import neton.io.net.runReactor
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * P0 acceptance for the actor transport: request/response and one-way over a real TCP
 * connection, with the msgtrans wire codec on top of the neton-io reactor.
 */
class RequestResponseTest {

    @Test
    fun requestResponse() = runReactor {
        val port = 39500
        val server = Transport.bind(this, "127.0.0.1", port) {
            object : SessionHandler {
                override suspend fun onRequest(payload: ByteArray, bizType: Int): ByteArray =
                    ("reply:" + payload.decodeToString()).encodeToByteArray()
            }
        }
        val serverJob = launch { server.acceptLoop() }

        val conn = Transport.connect(this, "127.0.0.1", port)
        val response = conn.request("ping".encodeToByteArray(), bizType = 7)
        assertEquals("reply:ping", response.decodeToString())

        conn.close()
        serverJob.cancelAndJoin()
        server.close()
    }

    @Test
    fun manyRequestsAndOneWay() = runReactor {
        val port = 39501
        val received = mutableListOf<String>()
        val server = Transport.bind(this, "127.0.0.1", port) {
            object : SessionHandler {
                override suspend fun onRequest(payload: ByteArray, bizType: Int): ByteArray =
                    payload.decodeToString().uppercase().encodeToByteArray()

                override suspend fun onMessage(payload: ByteArray, bizType: Int) {
                    received.add(payload.decodeToString())
                }
            }
        }
        val serverJob = launch { server.acceptLoop() }

        val conn = Transport.connect(this, "127.0.0.1", port)
        assertEquals("A", conn.request("a".encodeToByteArray()).decodeToString())
        assertEquals("BB", conn.request("bb".encodeToByteArray()).decodeToString())
        assertEquals("CCC", conn.request("ccc".encodeToByteArray()).decodeToString())
        conn.send("notify".encodeToByteArray(), bizType = 1)
        // Round-trip a final request to ensure the one-way was processed before we assert.
        conn.request("z".encodeToByteArray())
        assertEquals(listOf("notify"), received)

        conn.close()
        serverJob.cancelAndJoin()
        server.close()
    }
}
