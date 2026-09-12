package msgtrans.transport

import kotlinx.coroutines.CoroutineScope

/**
 * Entry points for opening connections, over any [ClientTransport] / [ServerTransport]. Everything
 * runs inside a neton-io reactor. The protocol is chosen by the transport; the session API
 * (send / request / onRequest / events) is the same across protocols.
 */
object Transport {

    /** Connect over [transport] and start the connection actor. */
    suspend fun connect(scope: CoroutineScope, transport: ClientTransport, config: ConnectionConfig = ConnectionConfig()): Connection =
        Connection(transport.open(), scope, config).also { it.start() }

    /** Convenience: connect over TCP. */
    suspend fun connect(scope: CoroutineScope, host: String, port: Int, config: ConnectionConfig = ConnectionConfig()): Connection =
        connect(scope, TcpClientTransport(host, port), config)

    /**
     * Bind [transport]. [onConnection] configures each accepted connection synchronously before it
     * starts (set its request handler, launch event collection or a server push).
     */
    suspend fun bind(
        scope: CoroutineScope,
        transport: ServerTransport,
        config: ConnectionConfig = ConnectionConfig(),
        onConnection: (Connection) -> Unit,
    ): TransportServer = TransportServer(transport.listen(), scope, config, onConnection)

    /** Convenience: bind over TCP. */
    suspend fun bind(
        scope: CoroutineScope,
        host: String,
        port: Int,
        config: ConnectionConfig = ConnectionConfig(),
        onConnection: (Connection) -> Unit,
    ): TransportServer = bind(scope, TcpServerTransport(host, port), config, onConnection)
}

/** A bound server. Each accepted connection becomes its own [Connection] actor. */
class TransportServer internal constructor(
    private val acceptor: ServerTransport.Acceptor,
    private val scope: CoroutineScope,
    private val config: ConnectionConfig,
    private val onConnection: (Connection) -> Unit,
) {
    suspend fun acceptLoop() {
        while (true) {
            val conn = Connection(acceptor.accept(), scope, config)
            onConnection(conn)
            conn.start()
        }
    }

    fun close() = acceptor.close()
}
