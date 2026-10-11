package consumer

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import msgtrans.transport.QuicClientTransport
import msgtrans.transport.QuicServerTransport
import msgtrans.transport.Transport
import neton.io.net.runReactor
import neton.quic.proto.Certificates
import neton.quic.proto.PrivateKey
import kotlin.time.Duration.Companion.seconds

/** com.netonstream:msgtrans-quic alone: msgtrans echo over QUIC on loopback. */
fun main() {
    var failures = 0
    runReactor {
        withTimeout(60.seconds) {
            val transport = QuicServerTransport("127.0.0.1", 0, Certificates.pem(CERTIFICATE), PrivateKey.pem(KEY))
            val server = Transport.bind(this, transport) { conn -> conn.onRequest { payload, _ -> payload } }
            val accepting = launch { server.acceptLoop() }
            failures += QuicClientTransport("127.0.0.1", transport.localAddress!!.port, Certificates.pem(CERTIFICATE)).use { client ->
                echo("msgtrans-quic", Transport.connect(this, client))
            }
            accepting.cancelAndJoin()
            server.close()
            transport.close()
        }
    }
    finish("msgtrans-quic", failures)
}
