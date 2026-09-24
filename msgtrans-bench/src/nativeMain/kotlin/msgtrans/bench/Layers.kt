package msgtrans.bench

import kotlinx.coroutines.CoroutineScope
import msgtrans.core.Packet
import msgtrans.core.PacketCodec
import msgtrans.core.PacketType
import msgtrans.transport.Connection
import msgtrans.transport.ConnectionClosedException
import msgtrans.transport.ConnectionConfig
import kotlinx.cinterop.toKString
import msgtrans.transport.Transport
import neton.io.bytes.Buffer
import neton.io.core.Framed
import neton.io.core.Io
import neton.io.core.IoStream
import neton.io.core.serve
import neton.io.net.connect
import neton.io.net.listen
import kotlinx.coroutines.launch

/**
 * The three comparable layers. Each pair (server, client layer) uses the same connection
 * count, payload and one-outstanding-request cadence via the shared harness; only the amount
 * of protocol machinery differs:
 *
 * | layer  | wire                                 | server                                  |
 * |--------|--------------------------------------|-----------------------------------------|
 * | raw    | payload bytes only                   | read buffer, write it back              |
 * | framed | msgtrans Packet (16 B header+payload)| Framed/serve: Request -> Response, same id |
 * | rpc    | msgtrans Packet                      | full Connection actor + onRequest        |
 *
 * The difference between two layers is the combined cost of everything that layer adds (extra
 * bytes, decode/encode, allocations, coroutine hops, queues); it is not attributable to any one
 * of those without a finer experiment.
 */

// ---------------------------------------------------------------- raw byte echo

object RawLayer : BenchLayer {
    override val name get() = "raw"

    override suspend fun open(scope: CoroutineScope, cfg: BenchConfig): BenchConn =
        RawConn(connect(cfg.host, cfg.port), cfg.payload)

    suspend fun serve(scope: CoroutineScope, host: String, port: Int) {
        val server = listen(host, port)
        println("READY mode=raw host=$host port=$port")
        while (true) {
            val conn = server.accept()
            scope.launch { echoBytes(conn) }
        }
    }

    private suspend fun echoBytes(conn: IoStream) {
        val buf = Buffer(64 * 1024)
        try {
            while (true) {
                buf.clear()
                if (conn.read(buf) < 0) break
                conn.write(buf)
            }
        } catch (_: Throwable) {
        } finally {
            conn.close()
        }
    }
}

private class RawConn(private val stream: IoStream, private val payload: ByteArray) : BenchConn {
    private val writeBuf = Buffer(payload.size.coerceAtLeast(16))
    private val readBuf = Buffer(payload.size.coerceAtLeast(16))

    override suspend fun roundTrip(): String? {
        writeBuf.clear()
        writeBuf.writeBytes(payload)
        stream.write(writeBuf)

        readBuf.clear()
        while (readBuf.readableBytes < payload.size) {
            if (stream.read(readBuf) < 0) return "eof"
        }
        if (readBuf.readableBytes != payload.size) return "mismatch: got ${readBuf.readableBytes} bytes, want ${payload.size}"
        val arr = readBuf.backingArray()
        val base = readBuf.readerIndex()
        for (i in payload.indices) if (arr[base + i] != payload[i]) return "mismatch: payload byte $i"
        return null
    }

    override suspend fun close() = stream.close()
}

// ---------------------------------------------------------------- framed echo (msgtrans wire, no actor)

/**
 * The msgtrans wire codec over neton-io's Framed/serve, echoing a Response for each Request with
 * the same id, bizType and payload — and none of the actor machinery (no pending registry, no
 * CompletableDeferred, no mailbox/handler queues, no extra per-connection coroutines).
 */
object FramedLayer : BenchLayer {
    override val name get() = "framed"

    override suspend fun open(scope: CoroutineScope, cfg: BenchConfig): BenchConn =
        FramedConn(Io(connect(cfg.host, cfg.port)), cfg.payload)

    suspend fun serve(scope: CoroutineScope, host: String, port: Int) {
        val server = listen(host, port)
        println("READY mode=framed host=$host port=$port")
        while (true) {
            val conn = server.accept()
            scope.launch { echoFrames(conn) }
        }
    }

    private suspend fun echoFrames(conn: IoStream) {
        try {
            serve(Framed(Io(conn), PacketCodec.Default, PacketCodec.Default)) { req ->
                if (req.type != PacketType.Request) throw IllegalStateException("framed echo expects Request, got ${req.type}")
                Packet.response(req.payload, req.bizType, req.messageId)
            }
        } catch (_: Throwable) {
            // decode error or unexpected packet: drop the connection (the client reports eof)
        } finally {
            conn.close()
        }
    }
}

private class FramedConn(private val io: Io, private val payload: ByteArray) : BenchConn {
    private var id = 0u

    override suspend fun roundTrip(): String? {
        id += 1u
        val out = io.writeBuf
        PacketCodec.Default.encode(Packet.request(payload, BenchConfig.BIZ_TYPE, id), out)
        io.stream.write(out)
        io.stream.flush()
        out.clear()

        val buf = io.readBuf
        var response: Packet? = null
        try {
            while (response == null) {
                response = PacketCodec.Default.decode(buf)
                if (response == null) {
                    buf.discardReadBytes()
                    if (io.stream.read(buf) < 0) return "eof"
                }
            }
        } catch (t: Throwable) {
            return "error: $t"
        }
        return validate(response, id, payload)
    }

    override suspend fun close() = io.close()
}

internal fun validate(p: Packet, id: UInt, payload: ByteArray): String? = when {
    p.type != PacketType.Response -> "mismatch: type ${p.type} for id $id"
    p.messageId != id -> "mismatch: id ${p.messageId}, want $id"
    p.bizType != BenchConfig.BIZ_TYPE -> "mismatch: bizType ${p.bizType}"
    !p.payload.contentEquals(payload) -> "mismatch: payload (${p.payload.size} bytes) for id $id"
    else -> null
}

// ---------------------------------------------------------------- full msgtrans RPC (Connection actor)

object RpcLayer : BenchLayer {
    override val name get() = "rpc"

    override suspend fun open(scope: CoroutineScope, cfg: BenchConfig): BenchConn =
        RpcConn(Transport.connect(scope, cfg.host, cfg.port,
            ConnectionConfig(requestTimeoutMillis = rpcTimeoutMs())), cfg.payload)

    suspend fun serve(scope: CoroutineScope, host: String, port: Int) {
        val server = Transport.bind(scope, host, port) { conn ->
            conn.onRequest { payload, _ -> payload }
        }
        println("READY mode=rpc host=$host port=$port")
        server.acceptLoop()
    }
}

private class RpcConn(private val conn: Connection, private val payload: ByteArray) : BenchConn {
    override suspend fun roundTrip(): String? {
        val response = try {
            conn.request(payload, BenchConfig.BIZ_TYPE)
        } catch (_: ConnectionClosedException) {
            return "eof"
        } catch (t: Throwable) {
            return "error: $t"
        }
        // The actor matches type and id internally (a mismatched id never completes this request);
        // payload equality is checked here like the other layers. bizType is not surfaced by request().
        return if (response.contentEquals(payload)) null else "mismatch: payload (${response.size} bytes)"
    }

    // Connection.close() cancels the actor's read/write coroutines; on Linux/io_uring that is the
    // known-unverified cancel-while-in-flight path (neton-io SPEC 15.3). The harness itself never
    // cancels anything; this is the layer's own contract being exercised in the exit phase.
    override suspend fun close() = conn.close()
}

/** Per-request timeout for the rpc layer, MSGTRANS_REQUEST_TIMEOUT_MS (default 30000; 0 = off). */
@kotlin.OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun rpcTimeoutMs(): Long =
    platform.posix.getenv("MSGTRANS_REQUEST_TIMEOUT_MS")?.toKString()?.toLongOrNull() ?: 30_000L

fun layerFor(mode: String): BenchLayer = when (mode) {
    "raw" -> RawLayer
    "framed" -> FramedLayer
    "rpc" -> RpcLayer
    else -> throw IllegalArgumentException("unknown mode '$mode' (raw|framed|rpc)")
}
