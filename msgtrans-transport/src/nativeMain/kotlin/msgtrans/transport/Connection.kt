package msgtrans.transport

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import msgtrans.core.Packet
import msgtrans.core.PacketCodec
import msgtrans.core.PacketType
import neton.io.core.Framed
import neton.io.core.Io
import neton.io.core.IoStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * How outbound packets reach the socket — the single variable of the B1 execution-model experiment.
 *
 * - [CHANNEL] (A, the original): a bounded `Channel<Packet>` mailbox drained by a dedicated write
 *   coroutine; every send is one channel hop plus one coroutine resume.
 * - [INLINE] (B1): a bounded deque and a single-writer state machine; the coroutine that enqueues
 *   while no writer is active becomes the writer and drains the deque itself. Same ordering (FIFO),
 *   same bound (backpressure suspends the sender), one packet per socket write (no batching, to keep
 *   the comparison to one variable), same close semantics. A writer cancelled mid-write hands the
 *   partially written buffer and the remaining queue to a successor so the byte stream stays intact.
 *
 * Selected per connection; default from `MSGTRANS_WRITE_MODE` (`channel` | `inline`), else CHANNEL.
 */
enum class WriteMode {
    CHANNEL, INLINE;

    companion object {
        @OptIn(ExperimentalForeignApi::class)
        val default: WriteMode by lazy {
            when (platform.posix.getenv("MSGTRANS_WRITE_MODE")?.toKString()?.lowercase()) {
                "inline" -> INLINE
                "channel", null, "" -> CHANNEL
                else -> throw IllegalArgumentException("MSGTRANS_WRITE_MODE must be channel|inline")
            }
        }
    }
}

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
    private val mailboxCapacity: Int = 256,
    val writeMode: WriteMode = WriteMode.default,
) {
    private val io = Io(stream)
    private val framed = Framed(io, PacketCodec, PacketCodec)
    private val outbound = Channel<Packet>(mailboxCapacity)

    // INLINE write mode state (reactor-thread only).
    private val sendQueue = ArrayDeque<Packet>()
    private var writerActive = false
    private val roomWaiters = ArrayDeque<CancellableContinuation<Unit>>()
    private val inboundRequests = Channel<Packet>(mailboxCapacity)
    private val inboundEvents = Channel<Message>(mailboxCapacity)

    private val pending = HashMap<UInt, CompletableDeferred<Packet>>()
    private var requestId: UInt = 0u   // per-session, refuses to wrap
    private var oneWayId: UInt = 0u    // separate, free to wrap
    private var requestHandler: (suspend (payload: ByteArray, bizType: Int) -> ByteArray)? = null

    private var closed = false
    private var readJob: Job? = null
    private var writeJob: Job? = null
    private var handlerJob: Job? = null

    /** Set the handler that answers inbound requests. */
    fun onRequest(handler: suspend (payload: ByteArray, bizType: Int) -> ByteArray) {
        requestHandler = handler
    }

    /** Inbound one-way messages (server push, telemetry, etc.). */
    fun events(): Flow<Message> = inboundEvents.receiveAsFlow()

    /** Launch a coroutine on this connection's scope (e.g. to collect [events] or push). */
    fun launch(block: suspend CoroutineScope.() -> Unit): Job = scope.launch(block = block)

    internal fun start() {
        if (writeMode == WriteMode.CHANNEL) writeJob = scope.launch { writeLoop() }
        handlerJob = scope.launch { handlerLoop() }
        readJob = scope.launch { readLoop() }
    }

    private suspend fun writeLoop() {
        for (packet in outbound) framed.send(packet)
    }

    /** Put [packet] on the outbound path according to [writeMode]; suspends under backpressure. */
    private suspend fun enqueue(packet: Packet) {
        if (writeMode == WriteMode.CHANNEL) {
            outbound.send(packet)
            return
        }
        while (!closed && sendQueue.size >= mailboxCapacity) awaitRoom()
        if (closed) throw ConnectionClosedException()
        sendQueue.addLast(packet)
        if (!writerActive) drainAsWriter()
    }

    private suspend fun awaitRoom() = suspendCancellableCoroutine<Unit> { cont ->
        roomWaiters.addLast(cont)
        cont.invokeOnCancellation { roomWaiters.remove(cont) }
    }

    private fun wakeOneRoomWaiter() {
        roomWaiters.removeFirstOrNull()?.resume(Unit)
    }

    /**
     * Single-writer state machine. Exactly one coroutine drains [sendQueue] at a time; it first
     * flushes whatever a cancelled predecessor left in the write buffer (so a packet is never
     * half-sent), then encodes and writes one packet per socket write until the queue is empty.
     * Cancellation mid-write keeps [writerActive] set and launches a successor; any I/O error
     * closes the connection.
     */
    private suspend fun drainAsWriter() {
        writerActive = true
        var handedOff = false
        try {
            if (io.writeBuf.readableBytes > 0) writeAll()
            while (!closed) {
                val packet = sendQueue.removeFirstOrNull() ?: break
                wakeOneRoomWaiter()
                PacketCodec.encode(packet, io.writeBuf)
                writeAll()
            }
        } catch (e: CancellationException) {
            if (!closed && (io.writeBuf.readableBytes > 0 || sendQueue.isNotEmpty())) {
                handedOff = true
                scope.launch { drainAsWriter() }
            }
            throw e
        } catch (t: Throwable) {
            shutdown()
            throw t
        } finally {
            if (!handedOff) writerActive = false
        }
    }

    private suspend fun writeAll() {
        io.stream.write(io.writeBuf)
        if (io.writeBuf.readableBytes > 0) { // short write without suspension = I/O error
            shutdown()
            throw ConnectionClosedException("write failed")
        }
        io.writeBuf.clear()
        io.stream.flush()
    }

    // The read loop never runs the business handler: it completes responses inline (so a reverse
    // request or a pipelined response always advances) and hands requests to the handler loop.
    private suspend fun readLoop() {
        try {
            framed.incoming().collect { packet ->
                when (packet.type) {
                    PacketType.Response -> pending.remove(packet.messageId)?.complete(packet)
                    PacketType.Request -> inboundRequests.send(packet)
                    PacketType.OneWay -> inboundEvents.send(Message(packet.bizType, packet.payload))
                }
            }
        } finally {
            shutdown()
        }
    }

    // Handlers run here, serialized per connection but off the read path, so a slow or reentrant
    // handler cannot block response completion or deadlock on its own reverse request.
    private suspend fun handlerLoop() {
        for (packet in inboundRequests) {
            val response = requestHandler?.invoke(packet.payload, packet.bizType) ?: EMPTY
            enqueue(Packet.response(response, packet.bizType, packet.messageId))
        }
    }

    /** Send a Request and suspend until the matching Response arrives; returns its payload. */
    suspend fun request(payload: ByteArray, bizType: Int = 0): ByteArray {
        check(!closed) { "connection closed" }
        val id = nextRequestId()
        val deferred = CompletableDeferred<Packet>()
        pending[id] = deferred
        enqueue(Packet.request(payload, bizType, id))
        return deferred.await().payload
    }

    /** Send a one-way message (no response expected). */
    suspend fun send(payload: ByteArray, bizType: Int = 0) {
        check(!closed) { "connection closed" }
        enqueue(Packet.oneWay(payload, bizType, nextOneWayId()))
    }

    suspend fun close() = shutdown()

    private fun shutdown() {
        if (closed) return
        closed = true
        outbound.close()
        inboundRequests.close()
        inboundEvents.close()
        io.close()
        pending.values.forEach { it.completeExceptionally(ConnectionClosedException()) }
        pending.clear()
        sendQueue.clear()
        while (roomWaiters.isNotEmpty()) roomWaiters.removeFirst().resumeWithException(ConnectionClosedException())
        // A closed fd is not reliably reported by the reactor, so the loops must be cancelled.
        readJob?.cancel()
        writeJob?.cancel()
        handlerJob?.cancel()
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
