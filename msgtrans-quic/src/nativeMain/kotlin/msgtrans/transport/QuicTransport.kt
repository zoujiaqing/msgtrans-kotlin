package msgtrans.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import neton.io.core.ClosedException
import neton.io.core.IoStream
import neton.io.net.ConnectException
import neton.io.net.SocketAddress
import neton.io.net.lookupHost
import neton.quic.Connection as QuicConnection
import neton.quic.Endpoint
import neton.quic.QuicStream
import neton.quic.proto.Certificates
import neton.quic.proto.ClientConfig
import neton.quic.proto.IdleTimeout
import neton.quic.proto.PrivateKey
import neton.quic.proto.ServerConfig
import neton.quic.proto.TlsClientConfig
import neton.quic.proto.TlsServerConfig
import neton.quic.proto.TransportConfig
import neton.quic.proto.withCrypto
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The ALPN protocol of msgtrans over QUIC, offered and required by both sides (msgtrans-rust `ALPN_MSGTRANS`): a
 * msgtrans endpoint completes a handshake only with another msgtrans endpoint.
 */
const val MSGTRANS_QUIC_ALPN: String = "msgtrans/1"

/**
 * QUIC settings shared by both sides, with msgtrans-rust's defaults (`QuicClientConfig` / `QuicServerConfig`): a
 * 30-second idle timeout and a 15-second keep-alive, so an idle session outlives the idle timeout.
 */
class QuicTransportOptions(
    /** Close the connection after this long without any packet from the peer (QUIC max_idle_timeout). */
    val maxIdleTimeout: Duration = 30.seconds,
    /** Send a PING after this long without sending anything; `null` sends none. */
    val keepAliveInterval: Duration? = 15.seconds,
    /** Client only: give up on a handshake after this long (per address tried). */
    val connectTimeout: Duration = 10.seconds,
) {
    internal fun transportConfig(): TransportConfig =
        TransportConfig().maxIdleTimeout(IdleTimeout.of(maxIdleTimeout)).keepAliveInterval(keepAliveInterval)
}

/**
 * msgtrans over QUIC, client side — wire-compatible with msgtrans-rust's QUIC transport. Each [open] makes one QUIC
 * connection (its own UDP socket, as msgtrans-rust's `Endpoint::client` per connect) and opens one bidirectional stream
 * that carries the session's packets, each preceded by its length ([Framing.LengthPrefixed]).
 *
 * The server is verified against [trustAnchors] (its CA, or its self-signed certificate; `Certificates.system()` for
 * public certificates) and [serverName] (a DNS name or an IP address). As in msgtrans-rust, the server only learns of
 * the stream once the client sends on it, so the client should send first.
 *
 * Owns its TLS configuration: [close] it once its connections are no longer needed.
 */
class QuicClientTransport private constructor(
    private val host: String,
    private val port: Int,
    private val serverName: String,
    private val tls: TlsClientConfig,
    private val options: QuicTransportOptions,
) : ClientTransport, AutoCloseable {

    constructor(
        host: String,
        port: Int,
        trustAnchors: Certificates,
        serverName: String = host.removeSurrounding("[", "]"),
        options: QuicTransportOptions = QuicTransportOptions(),
    ) : this(host, port, serverName, TlsClientConfig(trustAnchors, listOf(MSGTRANS_QUIC_ALPN.encodeToByteArray())), options)

    private val clientConfig = ClientConfig(tls).transportConfig(options.transportConfig())

    override val framing: Framing get() = Framing.LengthPrefixed

    /**
     * Resolve the host and connect, trying its addresses staggered (Happy Eyeballs, RFC 8305 §5): the next address is
     * tried when the previous one has not connected within [ATTEMPT_DELAY] or has failed, and the first handshake to
     * complete wins. Over UDP an address nobody listens on does not refuse: without this, `localhost` resolving to
     * `::1` first held every connection to an IPv4-only server for the whole connect timeout.
     */
    override suspend fun open(): IoStream {
        val addresses = lookupHost(host, port)
        val context = currentCoroutineContext()
        // The racers only wait for handshakes; the endpoints are made here, so that their coroutines belong to the
        // caller (an endpoint lives as long as its connection), not to a scope this function waits for.
        val racers = CoroutineScope(context + Job(context[Job]))
        val outcomes = Channel<Pair<Attempt, Result<QuicConnection>>>(Channel.UNLIMITED)
        val attempts = ArrayList<Attempt>()
        var failure: Throwable? = null
        var winner: Pair<Attempt, QuicConnection>? = null
        try {
            var next = 0
            var running = 0
            // Starts the next address; false (with the failure recorded) if it could not even start.
            suspend fun startNext(): Boolean = try {
                attempts += start(addresses[next++], racers, outcomes)
                running++
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
                false
            }
            while (winner == null) {
                if (running == 0) {
                    if (next == addresses.size) break      // every address failed
                    startNext()
                    continue
                }
                val outcome = if (next < addresses.size) withTimeoutOrNull(ATTEMPT_DELAY) { outcomes.receive() } else outcomes.receive()
                if (outcome == null) {                      // no answer yet: try the next address beside it
                    startNext()
                    continue
                }
                running--
                outcome.second.fold({ winner = outcome.first to it }, { failure = it })
            }
        } finally {
            racers.cancel()
            outcomes.close()
            // Handshakes that completed but lost: their connections are closed with their endpoints.
            while (true) {
                val (attempt, result) = outcomes.tryReceive().getOrNull() ?: break
                if (attempt !== winner?.first) result.getOrNull()?.close()
            }
            for (attempt in attempts) if (attempt !== winner?.first) attempt.endpoint.close()
        }
        val (attempt, connection) = winner ?: throw ConnectException("QUIC connect to ${hostPort()} failed: ${failure?.message ?: "no address"}")
        try {
            val (send, recv) = connection.openBi()
            return QuicSessionStream(QuicStream(send, recv), connection, attempt.endpoint)
        } catch (e: Throwable) {
            connection.close()
            attempt.endpoint.close()
            throw e
        }
    }

    private class Attempt(val endpoint: Endpoint)

    /** Start a handshake with [address] on its own endpoint; its result goes to [outcomes]. */
    private suspend fun start(
        address: SocketAddress,
        racers: CoroutineScope,
        outcomes: Channel<Pair<Attempt, Result<QuicConnection>>>,
    ): Attempt {
        val endpoint = Endpoint.client(if (address.isIpv4) SocketAddress.IPV4_UNSPECIFIED_ANY_PORT else SocketAddress.IPV6_UNSPECIFIED_ANY_PORT)
        val attempt = Attempt(endpoint)
        val connecting = try {
            endpoint.connectWith(clientConfig, address, serverName)
        } catch (e: Throwable) {
            endpoint.close()
            throw e
        }
        racers.launch {
            val result = try {
                Result.success(withTimeout(options.connectTimeout) { connecting.await() })
            } catch (e: TimeoutCancellationException) {
                connecting.close()
                Result.failure(e)
            } catch (e: CancellationException) {
                connecting.close()
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            // A handshake that completed after another won (or after open gave up) is closed, not leaked.
            if (outcomes.trySend(attempt to result).isFailure) result.getOrNull()?.close()
        }
        return attempt
    }

    private fun hostPort() = if (':' in host && !host.startsWith('[')) "[$host]:$port" else "$host:$port"

    /** Release the TLS configuration; connections already made keep working. */
    override fun close() = tls.close()

    companion object {
        /** RFC 8305's recommended Connection Attempt Delay. */
        private val ATTEMPT_DELAY = 250.milliseconds

        /**
         * **Insecure — never in production.** A client that accepts any server certificate and name (msgtrans-rust
         * `danger_skip_verification`): anyone on the path can impersonate the server. For tests against servers with
         * throwaway certificates (msgtrans-rust's self-signed `QuicServerConfig`).
         */
        fun dangerousNoServerVerificationForTestsOnly(
            host: String,
            port: Int,
            serverName: String = "localhost",
            options: QuicTransportOptions = QuicTransportOptions(),
        ): QuicClientTransport = QuicClientTransport(
            host, port, serverName,
            TlsClientConfig.dangerousNoServerVerificationForTestsOnly(listOf(MSGTRANS_QUIC_ALPN.encodeToByteArray())),
            options,
        )
    }
}

/**
 * msgtrans over QUIC, server side — wire-compatible with msgtrans-rust's QUIC transport. Each QUIC connection becomes
 * one session once its client opens the stream (msgtrans-rust's `accept_bi`); handshakes and that wait run beside the
 * accept loop, so a slow client holds up nobody else.
 *
 * Presents [certificateChain] (leaf first) with [privateKey]. Owns its TLS configuration: [close] it after the server.
 */
class QuicServerTransport(
    private val host: String,
    private val port: Int,
    certificateChain: Certificates,
    privateKey: PrivateKey,
    private val options: QuicTransportOptions = QuicTransportOptions(),
) : ServerTransport, AutoCloseable {
    private val tls = TlsServerConfig(certificateChain, privateKey, listOf(MSGTRANS_QUIC_ALPN.encodeToByteArray()))

    override val framing: Framing get() = Framing.LengthPrefixed

    /** The UDP address the last [listen] bound (the port the OS chose for port 0). */
    var localAddress: SocketAddress? = null
        private set

    override suspend fun listen(): ServerTransport.Acceptor {
        val address = lookupHost(host, port).first()
        val endpoint = Endpoint.server(ServerConfig.withCrypto(tls).transportConfig(options.transportConfig()), address)
        localAddress = endpoint.localAddr()
        val context = currentCoroutineContext()
        return QuicAcceptor(endpoint, CoroutineScope(context + Job(context[Job])))
    }

    /** Release the TLS configuration; connections already made keep working. */
    override fun close() = tls.close()

    private class QuicAcceptor(private val endpoint: Endpoint, private val scope: CoroutineScope) : ServerTransport.Acceptor {
        /** Sessions whose stream is open, waiting for [accept]. */
        private val ready = Channel<IoStream>(Channel.UNLIMITED) { it.close() }

        init {
            scope.launch {
                try {
                    while (true) {
                        val incoming = endpoint.accept() ?: break
                        launch {
                            val connection = try { incoming.await() } catch (e: CancellationException) { throw e } catch (e: Exception) { return@launch }
                            val stream = try {
                                val (send, recv) = connection.acceptBi()
                                QuicSessionStream(QuicStream(send, recv), connection, null)
                            } catch (e: CancellationException) {
                                connection.close()
                                throw e
                            } catch (e: Exception) {
                                connection.close()
                                return@launch
                            }
                            if (ready.trySend(stream).isFailure) stream.close()
                        }
                    }
                } finally {
                    ready.close()
                }
            }
        }

        override suspend fun accept(): IoStream = ready.receiveCatching().getOrNull() ?: throw ClosedException()

        /** Stop accepting: new connection attempts are refused; sessions already handed out carry on. */
        override fun close() {
            endpoint.close()
            scope.cancel()
            ready.close()
            while (true) ready.tryReceive().getOrNull()?.close() ?: break
        }
    }
}

/**
 * A session's stream: the QUIC stream as an [IoStream], whose [close] also closes the connection it belongs to (one
 * session per connection, as in msgtrans-rust) and, on the client, the endpoint made for it.
 */
private class QuicSessionStream(
    private val stream: QuicStream,
    private val connection: QuicConnection,
    private val endpoint: Endpoint?,
) : IoStream by stream {
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        try {
            stream.close()      // finishes the send half
        } finally {
            connection.close()  // quinn: dropping the last handle, code 0
            endpoint?.close()   // the endpoint stops once its connection is gone
        }
    }
}
