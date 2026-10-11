package consumer

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import msgtrans.transport.Transport
import msgtrans.transport.WebSocketClientTransport
import msgtrans.transport.WebSocketServerTransport
import neton.io.net.runReactor
import kotlin.time.Duration.Companion.seconds

/** com.netonstream:msgtrans-websocket alone (no TLS artifact on the classpath): msgtrans echo over ws on loopback. */
fun main() {
    var failures = 0
    runReactor {
        withTimeout(60.seconds) {
            val transport = WebSocketServerTransport("127.0.0.1", 0)
            val server = Transport.bind(this, transport) { conn -> conn.onRequest { payload, _ -> payload } }
            val accepting = launch { server.acceptLoop() }
            failures += echo("ws", Transport.connect(this, WebSocketClientTransport("ws://127.0.0.1:${transport.localAddress!!.port}/")))
            accepting.cancelAndJoin()
            server.close()
        }
    }
    finish("ws", failures)
}
