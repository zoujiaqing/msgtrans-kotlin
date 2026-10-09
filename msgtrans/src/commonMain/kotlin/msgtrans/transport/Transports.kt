package msgtrans.transport

import neton.io.core.IoStream
import neton.io.net.TcpListener
import neton.io.net.TcpServerGroup
import neton.io.net.listenGroup
import neton.io.net.connect as netConnect
import neton.io.net.listen as netListen

/**
 * Protocol binding, mirroring the Rust msgtrans surface: pick a transport, then use the same
 * send / request API over it. The transport produces the byte [IoStream] a [Connection] wraps;
 * the connection/session logic (framing, correlation, timeouts) is protocol-independent.
 *
 * TCP is implemented here; QUIC is `QuicClientTransport` / `QuicServerTransport` in the separate
 * `com.netonstream:msgtrans-quic` artifact (it brings QUIC and OpenSSL); WebSocket is a declared binding
 * point. msgtrans-rust exposes `TcpClientTransport` / `WebSocketClientTransport` / `QuicClientTransport`
 * and the same `client.send` / `client.request`; the Kotlin surface keeps that shape so business code
 * does not change when a protocol is added.
 */
/**
 * How packets are delimited on a transport's byte stream: as they are on TCP ([Packets], the packet header carries the
 * lengths), or each preceded by its length as a u32 ([LengthPrefixed], msgtrans-rust's QUIC framing; see
 * [msgtrans.core.LengthPrefixedPacketCodec]).
 */
enum class Framing { Packets, LengthPrefixed }

interface ClientTransport {
    /** Open one client byte stream. */
    suspend fun open(): IoStream

    /** How packets are delimited on the streams this transport opens. */
    val framing: Framing get() = Framing.Packets
}

interface ServerTransport {
    /** How packets are delimited on the streams this transport accepts. */
    val framing: Framing get() = Framing.Packets

    /** Bind and start listening. */
    suspend fun listen(): Acceptor

    /**
     * Bind and spread connections over [reactors] reactors (SPEC §10). A transport that cannot
     * do that rejects `reactors > 1`; `reactors == 1` is [listen].
     */
    suspend fun listen(reactors: Int): Acceptor =
        if (reactors == 1) listen() else throw UnsupportedOperationException("${this::class.simpleName} cannot spread connections over reactors")

    interface Acceptor {
        suspend fun accept(): IoStream
        fun close()
    }

    /**
     * An acceptor whose connections are born on several reactors (SPEC §10): instead of handing
     * streams out through [accept], it runs [serve]'s handler on each connection's own reactor.
     */
    interface SpreadingAcceptor : Acceptor {
        val reactors: Int
        suspend fun serve(handler: suspend (IoStream) -> Unit)
        /** Suspend until the worker reactors have exited (after [close] and their connections ended). */
        suspend fun awaitWorkers()
    }
}

/** TCP client transport. */
class TcpClientTransport(private val host: String, private val port: Int) : ClientTransport {
    override suspend fun open(): IoStream = netConnect(host, port)
}

/** TCP server transport. */
class TcpServerTransport(private val host: String, private val port: Int) : ServerTransport {
    override suspend fun listen(): ServerTransport.Acceptor = TcpAcceptor(netListen(host, port))

    override suspend fun listen(reactors: Int): ServerTransport.Acceptor {
        require(reactors >= 1) { "reactors must be >= 1" }
        return if (reactors == 1) listen() else TcpGroupAcceptor(listenGroup(host, port, reactors))
    }

    private class TcpAcceptor(private val listener: TcpListener) : ServerTransport.Acceptor {
        override suspend fun accept(): IoStream = listener.accept()
        override fun close() = listener.close()
    }

    private class TcpGroupAcceptor(private val group: TcpServerGroup) : ServerTransport.SpreadingAcceptor {
        override val reactors: Int get() = group.reactors
        override suspend fun serve(handler: suspend (IoStream) -> Unit) = group.serve(handler)
        override suspend fun awaitWorkers() = group.awaitWorkers()
        override suspend fun accept(): IoStream =
            throw UnsupportedOperationException("a multi-reactor acceptor serves connections on their own reactors; use serve()")
        override fun close() = group.close()
    }
}

/** Declared binding point; not implemented yet (needs the WebSocket codec/handshake in neton-io). */
class WebSocketClientTransport(@Suppress("UNUSED_PARAMETER") url: String) : ClientTransport {
    override suspend fun open(): IoStream =
        throw NotImplementedError("WebSocket transport is not implemented yet (roadmap: neton-io WS codec)")
}
