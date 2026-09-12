package msgtrans.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import msgtrans.core.Packet
import msgtrans.core.PacketCodec
import msgtrans.core.PacketType
import neton.io.core.Framed
import neton.io.core.Io
import neton.io.core.IoStream

/**
 * A per-connection actor (see SPEC section 3).
 *
 * The connection is owned by its own coroutines with a bounded outbound mailbox: a read loop
 * dispatches inbound packets and a write loop drains the mailbox. All connection state (the
 * pending-request registry and the id counters) is touched only from these coroutines on the
 * single reactor thread, so it is serialized without locks.
 *
 * Three inbound kinds: a Response completes a pending request; a Request is answered by the
 * [onRequest] handler; a one-way message is delivered on [events]. Backpressure is per-connection.
 */
class Connection internal constructor(
    stream: IoStream,
    private val scope: CoroutineScope,
    mailboxCapacity: Int = 256,
) {
    private val io = Io(stream)
    private val framed = Framed(io, PacketCodec, PacketCodec)
    private val outbound = Channel<Packet>(mailboxCapacity)
    private val inboundEvents = Channel<Message>(mailboxCapacity)

    private val pending = HashMap<UInt, CompletableDeferred<Packet>>()
    private var requestId: UInt = 0u   // per-session, refuses to wrap
    private var oneWayId: UInt = 0u    // separate, free to wrap
    private var requestHandler: (suspend (payload: ByteArray, bizType: Int) -> ByteArray)? = null

    private var closed = false
    private var readJob: Job? = null
    private var writeJob: Job? = null

    /** Set the handler that answers inbound requests. */
    fun onRequest(handler: suspend (payload: ByteArray, bizType: Int) -> ByteArray) {
        requestHandler = handler
    }

    /** Inbound one-way messages (server push, telemetry, etc.). */
    fun events(): Flow<Message> = inboundEvents.receiveAsFlow()

    /** Launch a coroutine on this connection's scope (e.g. to collect [events] or push). */
    fun launch(block: suspend CoroutineScope.() -> Unit): Job = scope.launch(block = block)

    internal fun start() {
        writeJob = scope.launch { writeLoop() }
        readJob = scope.launch { readLoop() }
    }

    private suspend fun writeLoop() {
        for (packet in outbound) framed.send(packet)
    }

    private suspend fun readLoop() {
        try {
            framed.incoming().collect { packet ->
                when (packet.type) {
                    PacketType.Response -> pending.remove(packet.messageId)?.complete(packet)
                    PacketType.Request -> {
                        val response = requestHandler?.invoke(packet.payload, packet.bizType) ?: EMPTY
                        outbound.send(Packet.response(response, packet.bizType, packet.messageId))
                    }
                    PacketType.OneWay -> inboundEvents.send(Message(packet.bizType, packet.payload))
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
        inboundEvents.close()
        io.close()
        pending.values.forEach { it.completeExceptionally(ConnectionClosedException()) }
        pending.clear()
        // A closed fd is not reliably reported by the reactor, so the loops must be cancelled.
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

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
