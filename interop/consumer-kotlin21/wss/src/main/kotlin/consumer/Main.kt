package consumer

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import msgtrans.transport.Transport
import msgtrans.transport.WebSocketClientTransport
import msgtrans.transport.WebSocketServerTransport
import neton.io.core.IoStream
import neton.io.net.runReactor
import neton.openssl.TlsContext
import neton.tls.tlsAccept
import neton.tls.websocket.WssConnector
import kotlin.time.Duration.Companion.seconds

/** com.netonstream:msgtrans-websocket with com.netonstream:tls-websocket: msgtrans echo over wss on loopback. */
fun main() {
    var failures = 0
    runReactor {
        withTimeout(60.seconds) {
            val serverTls = TlsContext(server = true, certificateChainPem = CERTIFICATE.encodeToByteArray(),
                privateKeyPem = KEY.encodeToByteArray(), alpnProtocols = listOf("http/1.1"))
            val clientTls = TlsContext(server = false, trustRootsPem = CERTIFICATE.encodeToByteArray(), alpnProtocols = listOf("http/1.1"))
            val transport = WebSocketServerTransport("127.0.0.1", 0, tlsAcceptor = { tcp: IoStream -> tlsAccept(tcp, serverTls) })
            val server = Transport.bind(this, transport) { conn -> conn.onRequest { payload, _ -> payload } }
            val accepting = launch { server.acceptLoop() }
            val client = WebSocketClientTransport("wss://localhost:${transport.localAddress!!.port}/", tlsConnector = WssConnector(clientTls))
            failures += echo("wss", Transport.connect(this, client))
            accepting.cancelAndJoin()
            server.close()
            serverTls.close()
            clientTls.close()
        }
    }
    finish("wss", failures)
}
