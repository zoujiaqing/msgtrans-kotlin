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
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import msgtrans.core.Packet
import msgtrans.core.PacketCodec
import msgtrans.core.PacketType
import neton.io.core.Framed
import neton.io.core.ClosedException
import neton.io.core.Io
import neton.io.core.IoException
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
/**
 * Bounds and timeouts for one connection. All are finite by default: memory cannot grow without
 * bound and a request cannot wait forever. See the class doc for the terminal-state contract.
 */
class ConnectionConfig(
    /** Outbound queue depth; a sender suspends when it is full (backpressure). */
    val mailboxCapacity: Int = 256,
    /** Max requests awaiting a response at once; request() suspends when reached, never unbounded. */
    val maxInFlightRequests: Int = 1024,
    /** Inbound request queue depth handed to the handler loop; the read loop stalls when full. */
    val inboundCapacity: Int = 256,
    /** Default per-request timeout in ms; 0 disables. request(timeoutMillis=…) overrides. */
    val requestTimeoutMillis: Long = 30_000,
    /**
     * Max inbound frame size. A connection's memory ceiling is bounded and tunable:
     * roughly (inboundCapacity + mailboxCapacity + maxInFlightRequests) × maxPayloadLength.
     */
    val maxPayloadLength: Long = PacketCodec.DEFAULT_MAX_PAYLOAD,
    val writeMode: WriteMode = WriteMode.default,
)

/**
 * A per-connection actor (see SPEC section 3).
 *
 * Threading: the connection and all its state (pending registry, id counters, send queue) are
 * owned by the reactor thread; every method is a suspend/`launch` on that reactor and must be
 * called from it. request/send are enqueue-confirmed: the call returns (for send) or begins
 * waiting (for request) once the packet is queued, not once bytes reach the socket.
 *
 * Terminal states — every one releases all resources exactly once (see [shutdown]):
 * - close(): pending requests fail with [ConnectionClosedException]; queued outbound is dropped;
 *   room/in-flight waiters are released; the fd is closed and the loops cancelled.
 * - peer EOF / read error: the read loop ends and runs the same shutdown.
 * - request timeout: that one request fails with [RequestTimeoutException] and is removed from the
 *   registry; the connection stays up.
 * - request cancellation: the awaiting coroutine's cancellation removes its registry entry; a late
 *   response for it is dropped.
 *
 * A local request reaches **at most one** terminal state — success, timeout, cancellation or
 * connection failure — and its registry entry, in-flight slot and deadline are released exactly
 * once (CompletableDeferred.completeExceptionally / the pending.remove guard make the transition
 * single-shot). The network may deliver a duplicate, a late, or no response: a response with no
 * matching pending id is dropped, and no response simply lets the timeout fire.
 */
class Connection internal constructor(
    stream: IoStream,
    private val scope: CoroutineScope,
    private val config: ConnectionConfig = ConnectionConfig(),
) {
    val writeMode: WriteMode get() = config.writeMode
    private val mailboxCapacity: Int get() = config.mailboxCapacity
    /** Back-compat / explicit constructor used by tests and Transport. */
    internal constructor(stream: IoStream, scope: CoroutineScope, mailboxCapacity: Int = 256, writeMode: WriteMode = WriteMode.default)
        : this(stream, scope, ConnectionConfig(mailboxCapacity = mailboxCapacity, writeMode = writeMode))

    // The reactor that owns this connection. Public mutating entry points (request/send/close) run
    // their body here via withContext, so an external thread's call is posted to the reactor and
    // connection state is only ever touched on the owner thread (see the class doc, "dual entry").
    private val owner: CoroutineContext =
        scope.coroutineContext[ContinuationInterceptor] as? CoroutineContext
            ?: error("connection scope has no dispatcher")

    private val codec = PacketCodec(config.maxPayloadLength)
    private val io = Io(stream)
    private val framed = Framed(io, codec, codec)
    private val outbound = Channel<Packet>(config.mailboxCapacity)

    // INLINE write mode state (reactor-thread only).
    private val sendQueue = ArrayDeque<Packet>()
    private var writerActive = false
    private val roomWaiters = ArrayDeque<CancellableContinuation<Unit>>()
    private val inFlightWaiters = ArrayDeque<Pending>()
    private val inboundRequests = Channel<Packet>(config.inboundCapacity)
    private val inboundEvents = Channel<Message>(config.inboundCapacity)

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
        try {
            for (packet in outbound) framed.send(packet)
        } catch (_: IoException) {
            shutdown()
        }
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
                codec.encode(packet, io.writeBuf)
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
        try {
            io.stream.write(io.writeBuf)
        } catch (e: IoException) {
            shutdown()
            throw ConnectionClosedException("write failed: ${e.message}")
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
                    PacketType.Response -> pending.remove(packet.messageId)?.let { it.complete(packet); wakeOneInFlightWaiter() }
                    PacketType.Request -> inboundRequests.send(packet)
                    PacketType.OneWay -> inboundEvents.send(Message(packet.bizType, packet.payload))
                }
            }
        } catch (_: ClosedException) {
            // close() resumed our parked read; normal termination.
        } catch (_: IoException) {
            // peer reset / socket error; terminate the connection.
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

    /** One outstanding request: its result and (while parked for a slot) its waiter. */
    private class Pending(val id: UInt, val result: CompletableDeferred<Packet>) {
        var slotCont: CancellableContinuation<Unit>? = null
    }

    /**
     * Send a Request and suspend until the matching Response arrives; returns its payload.
     *
     * Runs on the owning reactor regardless of the calling thread. The [timeoutMillis] deadline
     * (default [ConnectionConfig.requestTimeoutMillis]; 0 disables) bounds the whole wait —
     * including time spent waiting for an in-flight slot when [ConnectionConfig.maxInFlightRequests]
     * are already outstanding — and fails the request with [RequestTimeoutException]. The
     * connection stays up after a timeout. A timeout does not mean the peer did not receive or run
     * the request: a request already queued may still be sent, and its later response is dropped.
     * Closing fails the request with [ConnectionClosedException].
     */
    suspend fun request(payload: ByteArray, bizType: Int = 0, timeoutMillis: Long = config.requestTimeoutMillis): ByteArray =
        withContext(owner) {
            check(!closed) { "connection closed" }
            val id = nextRequestId()
            val req = Pending(id, CompletableDeferred())
            // One deadline for the whole call: fails the result and wakes a parked slot-waiter.
            val deadline: DisposableHandle? = if (timeoutMillis > 0) {
                @OptIn(InternalCoroutinesApi::class)
                (owner[ContinuationInterceptor] as Delay).invokeOnTimeout(timeoutMillis, {
                    if (req.result.completeExceptionally(RequestTimeoutException(id, timeoutMillis))) {
                        pending.remove(id)
                        req.slotCont?.let { it.resume(Unit); req.slotCont = null }
                        wakeOneInFlightWaiter()
                    }
                }, owner)
            } else null
            try {
                // Bounded, deadline-abortable wait for an in-flight slot.
                while (!closed && pending.size >= config.maxInFlightRequests && !req.result.isCompleted) {
                    suspendCancellableCoroutine<Unit> { cont ->
                        req.slotCont = cont
                        inFlightWaiters.addLast(req)
                        cont.invokeOnCancellation { inFlightWaiters.remove(req); req.slotCont = null }
                    }
                }
                if (req.result.isCompleted) return@withContext req.result.await().payload // timed out waiting
                if (closed) throw ConnectionClosedException()
                pending[id] = req.result
                enqueue(Packet.request(payload, bizType, id))
                return@withContext req.result.await().payload
            } finally {
                deadline?.dispose()
                inFlightWaiters.remove(req)
                if (pending.remove(id) != null) wakeOneInFlightWaiter() // cancel/error path
            }
        }

    private fun wakeOneInFlightWaiter() {
        val next = inFlightWaiters.removeFirstOrNull() ?: return
        next.slotCont?.let { it.resume(Unit); next.slotCont = null }
    }

    /** Send a one-way message (no response expected). Runs on the owning reactor. */
    suspend fun send(payload: ByteArray, bizType: Int = 0): Unit = withContext(owner) {
        check(!closed) { "connection closed" }
        enqueue(Packet.oneWay(payload, bizType, nextOneWayId()))
    }

    /** Close the connection. Safe to call from any thread; runs on the owning reactor. */
    suspend fun close(): Unit = withContext(owner) { shutdown() }

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
        // Fail slot-waiters: complete their result and wake them; they observe the completed result.
        while (inFlightWaiters.isNotEmpty()) {
            val req = inFlightWaiters.removeFirst()
            req.result.completeExceptionally(ConnectionClosedException())
            req.slotCont?.let { it.resume(Unit); req.slotCont = null }
        }
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
