package msgtrans.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import msgtrans.core.Packet
import msgtrans.core.PacketCodec
import msgtrans.core.PacketType
import neton.io.core.Framed
import neton.io.core.Io
import neton.io.core.IoStream

/**
 * A per-connection actor.
 *
 * Following the msgtrans model, one connection is owned by its own coroutines with a bounded
 * outbound mailbox: a read loop that dispatches inbound packets and a write loop that drains
 * the mailbox. All connection state (the pending-request registry and the id counters) is
 * touched only from these coroutines on the single reactor thread, so it is serialized without
 * locks — the actor discipline, realized with coroutines.
 *
 * Backpressure is per-connection: a slow handler or a full mailbox stalls only this connection.
 */
class Connection internal constructor(
    stream: IoStream,
    private val handler: SessionHandler,
    private val scope: CoroutineScope,
    mailboxCapacity: Int = 256,
) {
    private val io = Io(stream)
    private val framed = Framed(io, PacketCodec, PacketCodec)
    private val outbound = Channel<Packet>(mailboxCapacity)

    // Registry: exactly one response per request. Per-session, only touched on the reactor thread.
    private val pending = HashMap<UInt, CompletableDeferred<Packet>>()

    // Per-session request id: monotonic, refuses to wrap (a reconnect gets a fresh connection).
    private var requestId: UInt = 0u
    // One-way id: separate counter, free to wrap (nothing matches on it).
    private var oneWayId: UInt = 0u

    private var closed = false
    private var readJob: Job? = null
    private var writeJob: Job? = null

    internal fun start() {
        writeJob = scope.launch { writeLoop() }
        readJob = scope.launch { readLoop() }
    }

    private suspend fun writeLoop() {
        for (packet in outbound) {
            framed.send(packet)
        }
    }

    private suspend fun readLoop() {
        try {
            framed.incoming().collect { packet ->
                when (packet.type) {
                    PacketType.Response -> pending.remove(packet.messageId)?.complete(packet)
                    PacketType.Request -> {
                        val response = handler.onRequest(packet.payload, packet.bizType)
                        outbound.send(Packet.response(response, packet.bizType, packet.messageId))
                    }
                    PacketType.OneWay -> handler.onMessage(packet.payload, packet.bizType)
                }
            }
        } finally {
            shutdown()
        }
    }

    /** Send a Request and suspend until the matching Response arrives; returns its payload. */
    suspend fun request(payload: ByteArray, bizType: Int = 0): ByteArray {
        check(!closed) { "connection closed" }
        val id = nextRequestId()
        val deferred = CompletableDeferred<Packet>()
        pending[id] = deferred
        outbound.send(Packet.request(payload, bizType, id))
        return deferred.await().payload
    }

    /** Send a one-way message (no response expected). */
    suspend fun send(payload: ByteArray, bizType: Int = 0) {
        check(!closed) { "connection closed" }
        outbound.send(Packet.oneWay(payload, bizType, nextOneWayId()))
    }

    suspend fun close() = shutdown()

    private fun shutdown() {
        if (closed) return
        closed = true
        outbound.close()
        io.close()
        pending.values.forEach { it.completeExceptionally(ConnectionClosedException()) }
        pending.clear()
        // Cancel the loops explicitly: closing the fd does not reliably wake a coroutine
        // parked in epoll/poll (a closed fd is dropped silently), so the reactor would never
        // see these children finish otherwise.
        readJob?.cancel()
        writeJob?.cancel()
    }

    private fun nextRequestId(): UInt {
        check(requestId != UInt.MAX_VALUE) { "request id space exhausted" }
        requestId += 1u
        return requestId
    }

    private fun nextOneWayId(): UInt {
        oneWayId += 1u
        if (oneWayId == 0u) oneWayId = 1u // skip 0, wrap
        return oneWayId
    }
}
