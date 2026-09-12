package msgtrans.transport

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import neton.io.net.runReactor
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * P0/v1 acceptance for the actor transport: request/response, one-way events, and server push
 * over real TCP with the msgtrans wire codec on the neton-io reactor.
 */
class RequestResponseTest {

    @Test
    fun requestResponse() = runReactor {
        val port = 39500
        val server = Transport.bind(this, "127.0.0.1", port) { conn ->
            conn.onRequest { payload, _ -> ("reply:" + payload.decodeToString()).encodeToByteArray() }
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
    fun oneWayEvents() = runReactor {
        val port = 39501
        val received = mutableListOf<String>()
        val server = Transport.bind(this, "127.0.0.1", port) { conn ->
            conn.onRequest { payload, _ -> payload.decodeToString().uppercase().encodeToByteArray() }
            conn.launch { conn.events().collect { received.add(it.payload.decodeToString()) } }
        }
        val serverJob = launch { server.acceptLoop() }

        val conn = Transport.connect(this, "127.0.0.1", port)
        assertEquals("A", conn.request("a".encodeToByteArray()).decodeToString())
        conn.send("notify".encodeToByteArray(), bizType = 1)
        // A round-trip ensures the one-way was processed before we assert.
        conn.request("z".encodeToByteArray())
        assertEquals(listOf("notify"), received)

        conn.close()
        serverJob.cancelAndJoin()
        server.close()
    }

    @Test
    fun serverPush() = runReactor {
        val port = 39502
        val server = Transport.bind(this, "127.0.0.1", port) { conn ->
            conn.launch { conn.send("welcome".encodeToByteArray(), bizType = 9) }
        }
        val serverJob = launch { server.acceptLoop() }

        val conn = Transport.connect(this, "127.0.0.1", port)
        val push = conn.events().first()
        assertEquals("welcome", push.payload.decodeToString())
        assertEquals(9, push.bizType)

        conn.close()
        serverJob.cancelAndJoin()
        server.close()
    }
}
