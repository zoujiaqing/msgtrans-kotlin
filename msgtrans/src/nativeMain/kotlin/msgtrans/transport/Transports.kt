package msgtrans.transport

import neton.io.core.IoStream
import neton.io.net.TcpListener
import neton.io.net.connect as netConnect
import neton.io.net.listen as netListen

/**
 * Protocol binding, mirroring the Rust msgtrans surface: pick a transport, then use the same
 * send / request API over it. The transport produces the byte [IoStream] a [Connection] wraps;
 * the connection/session logic (framing, correlation, timeouts) is protocol-independent.
 *
 * TCP is implemented. WebSocket and QUIC are declared binding points — msgtrans-rust exposes
 * `TcpClientTransport` / `WebSocketClientTransport` / `QuicClientTransport` and the same
 * `client.send` / `client.request`; the Kotlin surface keeps that shape so business code does not
 * change when a protocol is added.
 */
interface ClientTransport {
    /** Open one client byte stream. */
    suspend fun open(): IoStream
}

interface ServerTransport {
    /** Bind and start listening. */
    suspend fun listen(): Acceptor

    interface Acceptor {
        suspend fun accept(): IoStream
        fun close()
    }
}

/** TCP client transport. */
class TcpClientTransport(private val host: String, private val port: Int) : ClientTransport {
    override suspend fun open(): IoStream = netConnect(host, port)
}

/** TCP server transport. */
class TcpServerTransport(private val host: String, private val port: Int) : ServerTransport {
    override suspend fun listen(): ServerTransport.Acceptor = TcpAcceptor(netListen(host, port))

    private class TcpAcceptor(private val listener: TcpListener) : ServerTransport.Acceptor {
        override suspend fun accept(): IoStream = listener.accept()
        override fun close() = listener.close()
    }
}

/** Declared binding point; not implemented yet (needs the WebSocket codec/handshake in neton-io). */
class WebSocketClientTransport(@Suppress("UNUSED_PARAMETER") url: String) : ClientTransport {
    override suspend fun open(): IoStream =
        throw NotImplementedError("WebSocket transport is not implemented yet (roadmap: neton-io WS codec)")
}

/** Declared binding point; not implemented yet. */
class QuicClientTransport(@Suppress("UNUSED_PARAMETER") host: String, @Suppress("UNUSED_PARAMETER") port: Int) : ClientTransport {
    override suspend fun open(): IoStream =
        throw NotImplementedError("QUIC transport is not implemented yet")
}
