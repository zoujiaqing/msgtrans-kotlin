package msgtrans.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException

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
            val stream = acceptor.accept()
            // Every connection runs under its own SupervisorJob, parented to the server scope so
            // closing the server still tears all of them down. Sharing one scope meant a single
            // connection's failure cancelled its siblings — one bad peer took out the server
            // (P1-4). A supervisor child's failure stays local.
            val connScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
            val conn = Connection(stream, connScope, config)
            conn.onShutdown = { connScope.cancel() }
            try {
                onConnection(conn)
                conn.start()
            } catch (t: Throwable) {
                // A throwing onConnection must not end the accept loop either.
                connScope.cancel()
                conn.close()
                if (t is CancellationException) throw t
            }
        }
    }

    fun close() = acceptor.close()
}
