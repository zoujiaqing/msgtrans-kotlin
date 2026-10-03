package msgtrans.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import neton.io.core.IoStream
import kotlin.coroutines.CoroutineContext

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
     *
     * [reactors] (SPEC §10): with more than one, connections are spread over that many reactors,
     * each owned by its reactor for life, and **[onConnection] runs on the connection's reactor
     * thread** — not the caller's — so shared state it touches must be thread-safe. Cancelling
     * [scope] still tears down every connection on every reactor.
     */
    suspend fun bind(
        scope: CoroutineScope,
        transport: ServerTransport,
        config: ConnectionConfig = ConnectionConfig(),
        reactors: Int = 1,
        onConnection: (Connection) -> Unit,
    ): TransportServer = TransportServer(transport.listen(reactors), scope, config, onConnection)

    /** Convenience: bind over TCP. */
    suspend fun bind(
        scope: CoroutineScope,
        host: String,
        port: Int,
        config: ConnectionConfig = ConnectionConfig(),
        reactors: Int = 1,
        onConnection: (Connection) -> Unit,
    ): TransportServer = bind(scope, TcpServerTransport(host, port), config, reactors, onConnection)
}

/** A bound server. Each accepted connection becomes its own [Connection] actor. */
class TransportServer internal constructor(
    private val acceptor: ServerTransport.Acceptor,
    private val scope: CoroutineScope,
    private val config: ConnectionConfig,
    private val onConnection: (Connection) -> Unit,
) {
    /** Number of reactors connections are spread over (SPEC §10). */
    val reactors: Int get() = (acceptor as? ServerTransport.SpreadingAcceptor)?.reactors ?: 1

    suspend fun acceptLoop() {
        val spreading = acceptor as? ServerTransport.SpreadingAcceptor
        if (spreading != null) {
            // Each handler runs on its connection's reactor; it lives as long as the connection so
            // the worker reactor only exits once its connections have ended.
            spreading.serve { stream -> startConnection(stream, currentCoroutineContext().minusKey(Job))?.join() }
            return
        }
        while (true) {
            val stream = acceptor.accept()
            startConnection(stream, scope.coroutineContext.minusKey(Job))
        }
    }

    /**
     * Wrap [stream] in a started [Connection] whose scope is [dispatcherContext] (its owning
     * reactor) plus its own SupervisorJob. Returns that job, or null if [onConnection] threw.
     */
    private suspend fun startConnection(stream: IoStream, dispatcherContext: CoroutineContext): Job? {
        // Every connection runs under its own SupervisorJob, parented to the server scope so
        // closing the server still tears all of them down — on every reactor (SPEC §10). Sharing
        // one scope meant a single connection's failure cancelled its siblings — one bad peer took
        // out the server (P1-4). A supervisor child's failure stays local.
        val connJob = SupervisorJob(scope.coroutineContext[Job])
        val connScope = CoroutineScope(dispatcherContext + connJob)
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
            return null
        }
        return connJob
    }

    fun close() = acceptor.close()

    /** After [close]: suspend until the extra reactors have exited (no-op with one reactor). */
    suspend fun awaitReactors() { (acceptor as? ServerTransport.SpreadingAcceptor)?.awaitWorkers() }
}
