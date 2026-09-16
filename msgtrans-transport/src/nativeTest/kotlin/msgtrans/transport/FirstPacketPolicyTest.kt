package msgtrans.transport

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import neton.io.net.runReactor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FirstPacketPolicyTest {
    @Test
    fun wrongFirstBizTypeClosesOnlyThatConnection() = runReactor {
        val port = 19532
        val serverConfig = ConnectionConfig(
            requestTimeoutMillis = 2_000,
            requiredFirstRequestBizType = 1,
        )
        val clientConfig = ConnectionConfig(requestTimeoutMillis = 2_000)
        val server = Transport.bind(this, "127.0.0.1", port, serverConfig) { connection ->
            connection.onRequest { payload, _ -> payload }
        }
        val serverJob = launch { server.acceptLoop() }

        val rejected = Transport.connect(this, "127.0.0.1", port, clientConfig)
        assertFailsWith<Exception> {
            rejected.request("wrong-first-packet".encodeToByteArray(), bizType = 5)
        }

        // The policy fault is isolated to the offending connection. A correctly admitted client
        // can connect immediately and its first request reaches the application handler.
        val accepted = Transport.connect(this, "127.0.0.1", port, clientConfig)
        assertEquals(
            "client-connect",
            accepted.request("client-connect".encodeToByteArray(), bizType = 1).decodeToString(),
        )

        accepted.close()
        rejected.close()
        serverJob.cancelAndJoin()
        server.close()
    }

    @Test
    fun oneWayFrameCannotBypassTheFirstRequestRule() = runReactor {
        val port = 19533
        val server = Transport.bind(
            this,
            "127.0.0.1",
            port,
            ConnectionConfig(requiredFirstRequestBizType = 1),
        ) { connection ->
            connection.onRequest { payload, _ -> payload }
        }
        val serverJob = launch { server.acceptLoop() }
        val client = Transport.connect(
            this,
            "127.0.0.1",
            port,
            ConnectionConfig(requestTimeoutMillis = 2_000),
        )

        // Even a OneWay frame carrying biz_type=1 is invalid: admission must be a Request so the
        // client receives an explicit connect result before it can treat the connection as ready.
        client.send("not-a-request".encodeToByteArray(), bizType = 1)
        assertFailsWith<Exception> {
            client.request("too-late".encodeToByteArray(), bizType = 1)
        }

        client.close()
        serverJob.cancelAndJoin()
        server.close()
    }
}
