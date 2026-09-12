package msgtrans.transport

import kotlinx.coroutines.CoroutineScope
import neton.io.net.TcpListener
import neton.io.net.connect as netConnect
import neton.io.net.listen as netListen

/** Entry points for opening connections. Everything runs inside a neton-io reactor. */
object Transport {

    /** Connect to [host]:[port] and start the connection actor. Set [Connection.onRequest] as needed. */
    suspend fun connect(scope: CoroutineScope, host: String, port: Int, config: ConnectionConfig = ConnectionConfig()): Connection =
        Connection(netConnect(host, port), scope, config).also { it.start() }

    /**
     * Bind a listener. [onConnection] configures each accepted connection synchronously before it
     * starts (set its request handler, launch event collection or a server push).
     */
    suspend fun bind(
        scope: CoroutineScope,
        host: String,
        port: Int,
        config: ConnectionConfig = ConnectionConfig(),
        onConnection: (Connection) -> Unit,
    ): TransportServer = TransportServer(netListen(host, port), scope, config, onConnection)
}

/** A bound server. Each accepted connection becomes its own [Connection] actor. */
class TransportServer internal constructor(
    private val listener: TcpListener,
    private val scope: CoroutineScope,
    private val config: ConnectionConfig,
    private val onConnection: (Connection) -> Unit,
) {
    suspend fun acceptLoop() {
        while (true) {
            val conn = Connection(listener.accept(), scope, config)
            onConnection(conn)
            conn.start()
        }
    }

    fun close() = listener.close()
}
