package msgtrans.transport

import kotlinx.coroutines.CoroutineScope
import neton.io.net.TcpListener
import neton.io.net.connect as netConnect
import neton.io.net.listen as netListen

/** Entry points for opening connections. Everything runs inside a neton-io reactor. */
object Transport {

    /** Connect to [host]:[port] and start the connection actor. */
    suspend fun connect(
        scope: CoroutineScope,
        host: String,
        port: Int,
        handler: SessionHandler = object : SessionHandler {},
    ): Connection {
        val stream = netConnect(host, port)
        return Connection(stream, handler, scope).also { it.start() }
    }

    /** Bind a listener; call [TransportServer.acceptLoop] to start serving. */
    suspend fun bind(
        scope: CoroutineScope,
        host: String,
        port: Int,
        handlerFactory: () -> SessionHandler,
    ): TransportServer = TransportServer(netListen(host, port), scope, handlerFactory)
}

/** A bound server. Each accepted connection becomes its own [Connection] actor. */
class TransportServer internal constructor(
    private val listener: TcpListener,
    private val scope: CoroutineScope,
    private val handlerFactory: () -> SessionHandler,
) {
    /** Accept connections until cancelled, starting one actor per connection. */
    suspend fun acceptLoop() {
        while (true) {
            val stream = listener.accept()
            Connection(stream, handlerFactory(), scope).start()
        }
    }

    fun close() = listener.close()
}
