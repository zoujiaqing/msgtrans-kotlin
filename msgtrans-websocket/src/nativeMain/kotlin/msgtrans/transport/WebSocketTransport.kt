package msgtrans.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import msgtrans.core.Packet
import msgtrans.core.PacketCodec
import msgtrans.core.ProtocolException
import neton.http.Response
import neton.http.header.HeaderValue
import neton.io.bytes.Buffer
import neton.io.bytes.Bytes
import neton.io.core.ClosedException
import neton.io.core.IoException
import neton.io.core.IoStream
import neton.io.net.ConnectException
import neton.io.net.SocketAddress
import neton.io.net.TcpListener
import neton.io.net.listen
import neton.websocket.Message
import neton.websocket.TlsConnector
import neton.websocket.WebSocket
import neton.websocket.WebSocketConfig
import neton.websocket.WebSocketException
import neton.websocket.WebSocketReader
import neton.websocket.WebSocketWriter
import neton.websocket.accept
import neton.websocket.connect
import neton.websocket.frame.CloseCode
import neton.websocket.frame.CloseFrame
import neton.websocket.handshake.CallbackResult
import neton.websocket.intoClientRequest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The WebSocket subprotocol of msgtrans (msgtrans-rust `WS_SUBPROTOCOL_MSGTRANS`): offered by the client, echoed by a
 * server that supports it; a client offering none is accepted.
 */
const val MSGTRANS_WEBSOCKET_SUBPROTOCOL: String = "msgtrans.v1"

/**
 * WebSocket settings shared by both sides, with msgtrans-rust's defaults (`WebSocketClientConfig` /
 * `WebSocketServerConfig`).
 */
class WebSocketTransportOptions(
    /** Send a ping after this long; `null` sends none (the peer's pings are always answered). */
    val pingInterval: Duration? = 30.seconds,
    /** Close the session when a ping is not answered within this long. */
    val pongTimeout: Duration = 10.seconds,
    /** Client: give up on the TCP connection and the handshake after this long. Server: on the handshake. */
    val handshakeTimeout: Duration = 10.seconds,
    /**
     * The largest message accepted, which holds one packet: by default the largest packet [PacketCodec] accepts. Lower
     * it to match a smaller `ConnectionConfig.maxPayloadLength`.
     */
    val maxMessageSize: Int = (Packet.FIXED_HEADER_SIZE + 0xFFFF + PacketCodec.DEFAULT_MAX_PAYLOAD).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
) {
    internal fun webSocketConfig(): WebSocketConfig = WebSocketConfig(maxMessageSize = maxMessageSize, maxFrameSize = maxMessageSize)
}

/**
 * msgtrans over WebSocket, client side — wire-compatible with msgtrans-rust's WebSocket transport: one binary message
 * per packet, subprotocol `msgtrans.v1` offered. [url] is `ws://` or, with a [tlsConnector] (`WssConnector` from
 * `com.netonstream:tls-websocket`), `wss://`.
 */
class WebSocketClientTransport(
    private val url: String,
    private val options: WebSocketTransportOptions = WebSocketTransportOptions(),
    private val tlsConnector: TlsConnector? = null,
) : ClientTransport {

    override suspend fun open(): IoStream {
        val request = url.intoClientRequest()
        request.headers.append("Sec-WebSocket-Protocol", HeaderValue.fromStr(MSGTRANS_WEBSOCKET_SUBPROTOCOL))
        val ws = try {
            withTimeout(options.handshakeTimeout) {
                connect(request, options.webSocketConfig(), tlsConnector = tlsConnector).first
            }
        } catch (e: CancellationException) {
            if (currentCoroutineContext()[Job]?.isActive == false) throw e
            throw ConnectException("WebSocket connect to $url timed out after ${options.handshakeTimeout}")
        } catch (e: WebSocketException) {
            throw ConnectException("WebSocket connect to $url failed: ${e.message}")
        }
        return WebSocketPacketStream.start(ws, options)
    }
}

/**
 * msgtrans over WebSocket, server side — wire-compatible with msgtrans-rust's WebSocket transport. Accepts upgrades on
 * [path] only (404 otherwise, as msgtrans-rust); echoes `msgtrans.v1` when the client offers it. Each handshake runs
 * beside the accept loop with [WebSocketTransportOptions.handshakeTimeout] (msgtrans-rust does the same), so a slow
 * client holds up nobody.
 *
 * [tlsAcceptor] turns each accepted TCP stream into a TLS one for `wss://` (e.g. `{ tlsAccept(it, context) }` with
 * `com.netonstream:tls`); msgtrans-rust's WebSocket server has no TLS of its own.
 */
class WebSocketServerTransport(
    private val host: String,
    private val port: Int,
    private val path: String = "/",
    private val options: WebSocketTransportOptions = WebSocketTransportOptions(),
    private val tlsAcceptor: (suspend (IoStream) -> IoStream)? = null,
) : ServerTransport {

    /** The TCP address the last [listen] bound (the port the OS chose for port 0). */
    var localAddress: SocketAddress? = null
        private set

    override suspend fun listen(): ServerTransport.Acceptor {
        val listener = listen(host, port)
        localAddress = listener.localAddress
        val context = currentCoroutineContext()
        return WebSocketAcceptor(listener, CoroutineScope(context + Job(context[Job])))
    }

    private inner class WebSocketAcceptor(private val listener: TcpListener, private val scope: CoroutineScope) :
        ServerTransport.Acceptor {
        /** Sessions whose handshake is done, waiting for [accept]. */
        private val ready = Channel<IoStream>(Channel.UNLIMITED) { it.close() }

        init {
            scope.launch {
                try {
                    while (true) {
                        val tcp = try { listener.accept() } catch (e: IoException) { break }
                        launch { handshake(tcp) }
                    }
                } finally {
                    ready.close()
                }
            }
        }

        private suspend fun handshake(tcp: IoStream) {
            var stream = tcp
            val ws = try {
                withTimeout(options.handshakeTimeout) {
                    tlsAcceptor?.let { stream = it(tcp) }
                    accept(stream, options.webSocketConfig()) { request, response ->
                        if (request.uri.path != path) {
                            return@accept CallbackResult.Reject(Response.builder().status(404).body<String?>("Not Found"))
                        }
                        val offered = request.headers["Sec-WebSocket-Protocol"]?.toStr()
                        if (offered != null && offered.split(',').any { it.trim() == MSGTRANS_WEBSOCKET_SUBPROTOCOL }) {
                            response.headers.append("Sec-WebSocket-Protocol", HeaderValue.fromStr(MSGTRANS_WEBSOCKET_SUBPROTOCOL))
                        }
                        CallbackResult.Accept(response)
                    }
                }
            } catch (e: Throwable) {
                stream.close()
                if (e is CancellationException && scope.coroutineContext[Job]?.isActive == false) throw e
                return
            }
            val session = WebSocketPacketStream.start(ws, options)
            if (ready.trySend(session).isFailure) session.close()
        }

        override suspend fun accept(): IoStream = ready.receiveCatching().getOrNull() ?: throw ClosedException()

        /** Stop accepting; sessions already handed out carry on. */
        override fun close() {
            listener.close()
            scope.cancel()
            ready.close()
            while (true) ready.tryReceive().getOrNull()?.close() ?: break
        }
    }
}

/**
 * A WebSocket carrying msgtrans packets, as the byte [IoStream] a [Connection] wraps (msgtrans-rust's
 * `WebSocketAdapter`):
 * - writing: the bytes written are cut at packet boundaries (from each packet's header), and each packet goes out as
 *   one binary message, however the bytes were split across writes;
 * - reading: each binary message must be exactly one packet (msgtrans-rust `decode_exact_from`) and is handed on as its
 *   bytes; a text message, or a binary one that is not exactly one packet, is a [ProtocolException] (msgtrans-rust's
 *   default `FramePolicy::Strict`; its opt-in `Lenient` policy wraps such a message as a OneWay);
 * - pings are answered by the WebSocket; with [WebSocketTransportOptions.pingInterval] this side pings too, and a ping
 *   not answered within [WebSocketTransportOptions.pongTimeout] ends the session (msgtrans-rust's keepalive);
 * - [close] sends a Close frame (normal closure), then closes the connection.
 */
private class WebSocketPacketStream private constructor(
    private val ws: WebSocket,
    private val options: WebSocketTransportOptions,
    /** The keepalive and the closing handshake run here: the connection's reactor, with their own Job. */
    private val scope: CoroutineScope,
) : IoStream {
    private val reader: WebSocketReader
    private val writer: WebSocketWriter

    init {
        val (r, w) = ws.split()
        reader = r
        writer = w
    }

    /** One write at a time on the WebSocket: the session's packets, the keepalive's pings, the closing frame. */
    private val writeLock = Mutex()

    /** Written bytes not yet forming a whole packet. */
    private val pending = Buffer()

    private var closed = false

    /** When the last pong arrived (set by the reader). */
    private var lastPong = TimeSource.Monotonic.markNow()

    private fun startKeepalive() {
        val interval = options.pingInterval ?: return
        scope.launch {
            while (true) {
                delay(interval)
                val sent = TimeSource.Monotonic.markNow()
                writeLock.withLock { writer.send(Message.Ping(Bytes.EMPTY)) }
                delay(options.pongTimeout)
                if (lastPong < sent) {
                    // No pong since the ping: the peer is gone or stuck (msgtrans-rust closes with CloseReason::Timeout).
                    ws.abort()
                    return@launch
                }
            }
        }
    }

    override suspend fun read(dst: Buffer): Int {
        while (true) {
            val message = try {
                reader.receive() ?: return -1
            } catch (e: WebSocketException) {
                if (closed) throw ClosedException()
                throw IoException("WebSocket: ${e.message}")
            }
            when (message) {
                is Message.Binary -> {
                    val data = message.data
                    checkOnePacket(data)
                    dst.writeBytes(data)
                    return data.size
                }
                is Message.Pong -> lastPong = TimeSource.Monotonic.markNow()
                is Message.Text -> throw ProtocolException("text message on a msgtrans WebSocket")
                else -> {} // pings are answered by the WebSocket; a Close ends the stream on the next receive
            }
        }
    }

    private fun checkOnePacket(data: Bytes) {
        if (data.size < Packet.FIXED_HEADER_SIZE) throw ProtocolException("message of ${data.size} bytes is shorter than a packet header")
        fun u(i: Int) = data[i].toLong() and 0xFF
        val packet = Packet.FIXED_HEADER_SIZE + (u(8) shl 8 or u(9)) + (u(10) shl 24 or (u(11) shl 16) or (u(12) shl 8) or u(13))
        if (packet != data.size.toLong()) throw ProtocolException("message of ${data.size} bytes holds a packet of $packet bytes")
    }

    override suspend fun write(src: Buffer): Int {
        val n = src.readableBytes
        pending.writeBytes(src.backingArray(), src.readerIndex(), n)
        src.consume(n)
        writeLock.withLock {
            var queued = false
            while (pending.readableBytes >= Packet.FIXED_HEADER_SIZE) {
                val length = Packet.FIXED_HEADER_SIZE + pending.getUnsignedShort(8) + (pending.getInt(10).toLong() and 0xFFFFFFFFL)
                if (length > options.maxMessageSize) throw ProtocolException("packet of $length bytes exceeds the message limit ${options.maxMessageSize}")
                if (pending.readableBytes < length) break
                val packet = pending.readSlice(length.toInt())
                try {
                    writer.feed(Message.Binary(packet))
                } catch (e: WebSocketException) {
                    throw IoException("WebSocket: ${e.message}")
                }
                queued = true
            }
            if (queued) try { writer.flush() } catch (e: WebSocketException) { throw IoException("WebSocket: ${e.message}") }
        }
        return n
    }

    override suspend fun flush() {}

    override fun close() {
        if (closed) return
        closed = true
        scope.coroutineContext[Job]?.children?.forEach { it.cancel() }
        // The closing handshake, bounded; then the connection is closed whatever happened.
        scope.launch {
            try {
                withTimeout(CLOSE_TIMEOUT) { writeLock.withLock { writer.close(CloseFrame(CloseCode.Normal, "")) } }
            } catch (_: Throwable) {
            } finally {
                ws.abort()
                scope.cancel()
            }
        }
    }

    companion object {
        private val CLOSE_TIMEOUT = 1.seconds

        /** Wrap [ws], whose keepalive then runs on the calling coroutine's dispatcher (the connection's reactor). */
        suspend fun start(ws: WebSocket, options: WebSocketTransportOptions): WebSocketPacketStream {
            val dispatcher = currentCoroutineContext()[kotlin.coroutines.ContinuationInterceptor]!!
            return WebSocketPacketStream(ws, options, CoroutineScope(dispatcher + Job())).also { it.startKeepalive() }
        }
    }
}
