package msgtrans.transport

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import msgtrans.core.ProtocolException
import msgtrans.core.Packet
import msgtrans.core.PacketCodec
import msgtrans.core.PacketType
import msgtrans.core.Compression
import msgtrans.core.PayloadCompression
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
 * Selected per connection; default from `MSGTRANS_WRITE_MODE` (`channel` | `inline`), else INLINE
 * (SPEC §12: with the allocation-free path INLINE uses 8–10 % less server CPU per request than
 * CHANNEL at 1 and 4 reactors, at equal or better throughput).
 */
enum class WriteMode {
    CHANNEL, INLINE;

    companion object {
        val default: WriteMode by lazy {
            when (envVar("MSGTRANS_WRITE_MODE")?.lowercase()) {
                "inline", null, "" -> INLINE
                "channel" -> CHANNEL
                else -> throw IllegalArgumentException("MSGTRANS_WRITE_MODE must be channel|inline")
            }
        }
    }
}

/**
 * A per-connection actor (see SPEC section 3).
 *
 * Memory ceiling. What the connection itself holds is bounded by [ConnectionConfig]:
 * outbound is capped at maxOutboundBytes; inbound is capped at
 * (inboundCapacity + maxInFlightRequests) × maxPayloadLength. What it does *not* bound is the
 * callers: every coroutine parked in send/request keeps its own payload alive until it is queued,
 * and how many of those exist is the application's choice, not the connection's. Bounding that
 * would mean dropping or rejecting user data, which this layer does not do silently.
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
     * Max frame size, enforced on decode *and* on everything the connection queues outbound.
     * A larger outbound payload is rejected with [PayloadTooLargeException] rather than queued:
     * the peer would refuse it on decode anyway.
    */
    val maxPayloadLength: Long = PacketCodec.DEFAULT_MAX_PAYLOAD,
    /**
     * Ceiling on the bytes the connection itself holds on the outbound path, across both write
     * modes. Element counts alone bound nothing when payloads vary in size: [mailboxCapacity]
     * packets of [maxPayloadLength] is 16 GiB at the defaults. A sender waits until its payload
     * fits; one packet is always allowed through an empty queue so a large-but-legal payload
     * cannot deadlock.
    */
    val maxOutboundBytes: Long = 8L * 1024 * 1024,
    val writeMode: WriteMode = WriteMode.default,
    /** Maximum plaintext produced by inbound decompression; bounds decompression bombs. */
    val maxDecompressedPayloadLength: Int = PayloadCompression.DEFAULT_MAX_DECOMPRESSED_SIZE,
    /** Compression applied to automatic handler responses. Requests and one-way sends choose per call. */
    val responseCompression: Compression = Compression.None,
    /**
     * Optional application admission rule for servers. When set, the first inbound frame must be
     * a Request with exactly this biz type. Any Response, OneWay frame or different Request is a
     * protocol fault and closes only that connection without running an application handler.
     */
    val requiredFirstRequestBizType: Int? = null,
) {
    init {
        require(maxDecompressedPayloadLength >= 0) {
            "maxDecompressedPayloadLength must not be negative"
        }
        require(requiredFirstRequestBizType == null || requiredFirstRequestBizType in 0..255) {
            "requiredFirstRequestBizType must fit the one-byte biz_type field"
        }
    }
}

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

    private val ownerInterceptor = owner[ContinuationInterceptor]
    private val codec = PacketCodec(config.maxPayloadLength)
    private val io = Io(stream)
    private val framed = Framed(io, codec, codec)
    private val outbound = Channel<Packet>(config.mailboxCapacity)

    // INLINE write mode state (reactor-thread only).
    private val sendQueue = Ring<Packet>()
    private var writerActive = false
    private val roomWaiters = ArrayDeque<CancellableContinuation<Unit>>()

    /** Bytes currently queued outbound (sendQueue in INLINE mode, the channel in CHANNEL mode). */
    private var queuedBytes = 0L

    /** The live outbound byte count, so tests can assert the budget invariant directly. */
    internal val outboundQueuedBytes: Long get() = queuedBytes
    private val inFlightWaiters = ArrayDeque<Pending>()
    // SPEC §11 step 2: reactor-local, allocation-free handoff from the read loop to the handler
    // loop (same bound and backpressure as the Channel it replaces).
    private val inboundRequests = ReactorQueue<Packet>(config.inboundCapacity, owner)
    private val inboundEvents = Channel<Message>(config.inboundCapacity)

    private val pending = IdMap<Pending>()
    private var requestId: UInt = 0u   // per-session, refuses to wrap
    private var oneWayId: UInt = 0u    // separate, free to wrap
    private var requestHandler: RequestHandler? = null
    private var closeHandler: (() -> Unit)? = null

    private var closed = false
    private var readJob: Job? = null
    private var writeJob: Job? = null
    private var handlerJob: Job? = null

    /**
     * Set the handler that answers inbound requests. It runs on the reactor thread, serialized per
     * connection and off the read path. "A slow handler stalls only its own connection" holds only
     * for a handler that suspends and yields the thread: a **CPU-bound or blocking handler must
     * offload explicitly** (e.g. `withContext(Dispatchers.Default) { … }`), or it blocks the whole
     * reactor — the task budget bounds queue draining, not time spent inside one handler call.
     */
    fun onRequest(handler: RequestHandler) {
        requestHandler = handler
    }

    /**
     * Observe the connection terminal state exactly once on its owning reactor thread.
     * Applications use this to expire routing/presence state; the callback must stay non-blocking.
     */
    fun onClose(handler: () -> Unit) {
        closeHandler = handler
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
            for (packet in outbound) {
                framed.send(packet)
                releaseOutboundBytes(packetBytes(packet))
            }
        } catch (_: IoException) {
            shutdown()
        }
    }

    private fun packetBytes(packet: Packet): Long = packet.payload.size.toLong()

    private fun prepareOutboundPayload(payload: ByteArray, compression: Compression): ByteArray {
        if (compression != Compression.None && payload.size > config.maxDecompressedPayloadLength) {
            throw PayloadTooLargeException(payload.size.toLong(), config.maxDecompressedPayloadLength.toLong())
        }
        return PayloadCompression.compress(payload, compression)
    }

    /**
     * Reject what could never be queued: a payload the peer would refuse on decode, or one larger
     * than the entire outbound budget, which no amount of draining would make room for. Failing
     * loudly here beats parking the caller forever.
     */
    private fun checkOutboundSize(packet: Packet) {
        val bytes = packetBytes(packet)
        if (bytes > config.maxPayloadLength) throw PayloadTooLargeException(bytes, config.maxPayloadLength)
        if (bytes > config.maxOutboundBytes) throw PayloadTooLargeException(bytes, config.maxOutboundBytes)
    }

    /**
     * Wait until [bytes] fit on the outbound path: a free mailbox slot (INLINE) and room in the
     * byte budget (both modes). Slot counts alone bound nothing once payloads vary in size, which
     * is what made the documented memory ceiling untrue.
     *
     * When [req] is given, the wait also ends as soon as that request settles, so a deadline can
     * abort it; returns false in that case, meaning nothing should be queued.
     */
    private suspend fun awaitOutboundRoom(bytes: Long, req: Pending?): Boolean {
        while (!closed) {
            if (req != null && req.isCompleted) return false
            val slotFree = writeMode == WriteMode.CHANNEL || sendQueue.size < mailboxCapacity
            // An empty queue always accepts one packet, so a large but legal payload cannot
            // deadlock against its own budget.
            val byteFree = queuedBytes == 0L || queuedBytes + bytes <= config.maxOutboundBytes
            if (slotFree && byteFree) return true
            suspendCancellableCoroutine<Unit> { cont ->
                req?.roomCont = cont
                roomWaiters.addLast(cont)
                cont.invokeOnCancellation { roomWaiters.remove(cont); req?.roomCont = null }
            }
            req?.roomCont = null
        }
        if (req != null && req.isCompleted) return false
        throw ConnectionClosedException()
    }

    /** Room for [bytes] on the outbound path right now (the non-suspending check of [awaitOutboundRoom]). */
    private fun hasOutboundRoom(bytes: Long): Boolean {
        if (closed) return false
        val slotFree = writeMode == WriteMode.CHANNEL || sendQueue.size < mailboxCapacity
        return slotFree && (queuedBytes == 0L || queuedBytes + bytes <= config.maxOutboundBytes)
    }

    /**
     * Put [packet] on the outbound path according to [writeMode]; suspends under backpressure.
     * SPEC §12 step 3: inline, and [awaitOutboundRoom] is entered only when there is no room, so the
     * common case adds no coroutine frame.
     */
    private suspend inline fun enqueue(packet: Packet) {
        checkOutboundSize(packet)
        if (!hasOutboundRoom(packetBytes(packet))) awaitOutboundRoom(packetBytes(packet), null)
        queuedBytes += packetBytes(packet)
        if (writeMode == WriteMode.CHANNEL) {
            outbound.send(packet)
            return
        }
        sendQueue.addLast(packet)
        if (!writerActive) drainAsWriter()
    }

    /**
     * [enqueue] for a request that has a deadline. Plain enqueue parks either in [roomWaiters]
     * (INLINE) or inside the full outbound channel (CHANNEL), and neither wait used to be covered
     * by the request deadline: a caller could sit in enqueue long past its timeout, which
     * contradicted the "one deadline for the whole call" contract. Both waits now end as soon as
     * [req] is completed.
     */
    private suspend inline fun enqueueForRequest(packet: Packet, req: Pending) {
        checkOutboundSize(packet)
        val bytes = packetBytes(packet)
        // Deadline fired while waiting for room: nothing is queued.
        if (hasOutboundRoom(bytes) || awaitOutboundRoom(bytes, req)) {
            queuedBytes += bytes
            if (writeMode == WriteMode.CHANNEL) {
                if (!outbound.trySend(packet).isSuccess) sendRacingDeadline(packet, bytes, req)
            } else {
                sendQueue.addLast(packet)
                if (!writerActive) drainAsWriter()
            }
        }
    }

    /**
     * CHANNEL mode with a full (or closed) outbound channel. A coroutine parked in Channel.send
     * cannot be woken selectively, so race the send against the request's settlement; if the
     * deadline wins, nothing is queued and the caller's await rethrows the timeout.
     */
    private suspend fun sendRacingDeadline(packet: Packet, bytes: Long, req: Pending) {
        if (req.isCompleted) { releaseOutboundBytes(bytes); return }
        val gate = CompletableDeferred<Unit>()
        req.gate = gate
        var sent = false
        try {
            select {
                outbound.onSend(packet) { sent = true }
                gate.onAwait { }
            }
        } finally {
            req.gate = null
            if (!sent) releaseOutboundBytes(bytes)
        }
    }

    /** Give [bytes] back to the outbound budget and let a waiter through. */
    private fun releaseOutboundBytes(bytes: Long) {
        queuedBytes -= bytes
        if (queuedBytes < 0L) queuedBytes = 0L
        wakeOneRoomWaiter()
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
    // SPEC §12 step 5: inline, so draining from enqueue (the INLINE write mode's common case) adds no
    // coroutine frame; the cancellation successor below calls the non-inline [drainAsSuccessor].
    private suspend inline fun drainAsWriter() {
        writerActive = true
        var handedOff = false
        try {
            if (io.writeBuf.readableBytes > 0) writeAll()
            while (!closed) {
                val packet = sendQueue.removeFirstOrNull() ?: break
                releaseOutboundBytes(packetBytes(packet))
                codec.encode(packet, io.writeBuf)
                writeAll()
            }
        } catch (e: CancellationException) {
            if (!closed && (io.writeBuf.readableBytes > 0 || sendQueue.isNotEmpty())) {
                handedOff = true
                // The successor has no caller to receive an error. Rethrowing out of a bare
                // scope.launch made a perfectly ordinary write failure - the peer went away while
                // we were handing off - an uncaught exception that took the process down.
                scope.launch {
                    try {
                        drainAsSuccessor()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        // drainAsWriter has already run shutdown() for this.
                        reportConnectionFault("outbound write failed", t)
                    }
                }
            }
            throw e
        } catch (t: Throwable) {
            shutdown()
            throw t
        } finally {
            if (!handedOff) writerActive = false
        }
    }

    /** [drainAsWriter] as a real function, for the successor coroutine (an inline function cannot call itself). */
    private suspend fun drainAsSuccessor() = drainAsWriter()

    private suspend inline fun writeAll() {
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
        var firstInboundPacket = true
        try {
            // SPEC §12 step 1: neton-io's inline pull loop — no Flow collector, nothing allocated per frame.
            framed.receiveEach { wirePacket ->
                if (firstInboundPacket) {
                    firstInboundPacket = false
                    config.requiredFirstRequestBizType?.let { required ->
                        if (wirePacket.type != PacketType.Request || wirePacket.bizType != required) {
                            throw ProtocolException(
                                "first packet must be Request with biz_type=$required",
                            )
                        }
                    }
                }
                // This is the single inbound normalization point. Pending requests, application
                // handlers and event collectors always receive plaintext and a None marker.
                val packet = PayloadCompression.decompress(wirePacket, config.maxDecompressedPayloadLength)
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
        } catch (e: ProtocolException) {
            // A peer that speaks garbage kills its own connection and nothing else (P1-4).
            // Left uncaught this propagated out of the read coroutine and, when connections
            // shared one scope, cancelled every sibling connection on the server.
            reportConnectionFault("protocol error", e)
        } finally {
            shutdown()
        }
    }

    // Handlers run here, serialized per connection but off the read path, so a slow or reentrant
    // handler cannot block response completion or deadlock on its own reverse request.
    private suspend fun handlerLoop() {
        try {
            while (true) {
                val packet = inboundRequests.receive() ?: break
                val response = requestHandler?.handle(packet.payload, packet.bizType) ?: EMPTY
                val encoded = prepareOutboundPayload(response, config.responseCompression)
                enqueue(Packet(
                    PacketType.Response, packet.messageId, packet.bizType, encoded,
                    compression = config.responseCompression,
                ))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // The request handler is business code and may throw anything. The protocol has no
            // error packet, so the honest outcome is to fail this connection — the peer sees a
            // disconnect and its pending request times out — while every other connection on the
            // server keeps running (P1-4). Previously this escaped and cancelled the shared scope.
            reportConnectionFault("request handler failed", e)
        } finally {
            shutdown()
        }
    }

    /** One line on stderr; connection faults must be visible without taking the process down. */
    private fun reportConnectionFault(what: String, t: Throwable) {
        writeStderrLine("msgtrans: connection closed after $what: ${t.message ?: t.toString()}")
    }

    /**
     * One outstanding request: its result, its waiters, and its deadline (it is the timer's
     * Runnable). SPEC §13 step C: settling it resumes the parked caller directly, which replaced
     * a CompletableDeferred and its await machinery, and the deadline lambda.
     * Owner-reactor state only.
     */
    private inner class Pending(val id: UInt, val timeoutMillis: Long) : Runnable {
        private var response: Packet? = null
        private var error: Throwable? = null
        /** The caller, while parked for the response. */
        var waiter: CancellableContinuation<Packet>? = null
        /** Only while racing a full outbound channel (CHANNEL mode). */
        var gate: CompletableDeferred<Unit>? = null
        var slotCont: CancellableContinuation<Unit>? = null
        /** Set while this request is parked waiting for outbound mailbox room (INLINE mode). */
        var roomCont: CancellableContinuation<Unit>? = null

        val isCompleted: Boolean get() = response != null || error != null

        fun complete(packet: Packet): Boolean {
            if (isCompleted) return false
            response = packet
            gate?.complete(Unit)
            waiter?.let { waiter = null; it.resume(packet) }
            return true
        }

        fun fail(e: Throwable): Boolean {
            if (isCompleted) return false
            error = e
            gate?.complete(Unit)
            waiter?.let { waiter = null; it.resumeWithException(e) }
            return true
        }

        /** Suspend until settled; returns the response or throws the failure. */
        suspend fun await(): Packet {
            response?.let { return it }
            error?.let { throw it }
            return suspendCancellableCoroutine { cont -> waiter = cont }
        }

        /** The deadline: fails the result and wakes a parked slot- or room-waiter. */
        override fun run() {
            if (fail(RequestTimeoutException(id, timeoutMillis))) {
                pending.remove(id)
                slotCont?.let { it.resume(Unit); slotCont = null }
                roomCont?.let { roomWaiters.remove(it); it.resume(Unit); roomCont = null }
                wakeOneInFlightWaiter()
            }
        }
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
    suspend fun request(
        payload: ByteArray,
        bizType: Int = 0,
        timeoutMillis: Long = config.requestTimeoutMillis,
        compression: Compression = Compression.None,
    ): ByteArray {
        // SPEC §13 step A: a caller already on the owning reactor (the common case) runs the body
        // directly — withContext cost a coroutine, a context merge and a child-job link per request.
        if (kotlin.coroutines.coroutineContext[ContinuationInterceptor] === ownerInterceptor) {
            return requestOnOwner(payload, bizType, timeoutMillis, compression)
        }
        return withContext(owner) { requestOnOwner(payload, bizType, timeoutMillis, compression) }
    }

    private suspend fun requestOnOwner(payload: ByteArray, bizType: Int, timeoutMillis: Long, compression: Compression): ByteArray {
            check(!closed) { "connection closed" }
            val encoded = prepareOutboundPayload(payload, compression)
            val id = nextRequestId()
            val req = Pending(id, timeoutMillis)
            // One deadline for the whole call (Pending.run).
            val deadline: DisposableHandle? = if (timeoutMillis > 0) {
                @OptIn(InternalCoroutinesApi::class)
                (ownerInterceptor as Delay).invokeOnTimeout(timeoutMillis, req, owner)
            } else null
            try {
                // Bounded, deadline-abortable wait for an in-flight slot.
                while (!closed && pending.size >= config.maxInFlightRequests && !req.isCompleted) {
                    suspendCancellableCoroutine<Unit> { cont ->
                        req.slotCont = cont
                        inFlightWaiters.addLast(req)
                        cont.invokeOnCancellation { inFlightWaiters.remove(req); req.slotCont = null }
                    }
                }
                if (req.isCompleted) return req.await().payload // timed out waiting
                if (closed) throw ConnectionClosedException()
                pending[id] = req
                enqueueForRequest(Packet(
                    PacketType.Request, id, bizType, encoded, compression = compression,
                ), req)
                // If the deadline fired while we were parked for mailbox room, the result is
                // already completed with RequestTimeoutException and await() rethrows it here.
                return req.await().payload
            } finally {
                deadline?.dispose()
                req.waiter = null
                inFlightWaiters.remove(req)
                if (pending.remove(id) != null) wakeOneInFlightWaiter() // cancel/error path
            }
        }

    private fun wakeOneInFlightWaiter() {
        val next = inFlightWaiters.removeFirstOrNull() ?: return
        next.slotCont?.let { it.resume(Unit); next.slotCont = null }
    }

    /**
     * Like [request] but returns null on timeout instead of throwing, mirroring msgtrans-rust's
     * `request(...).data: Option<_>` (None = timed out). Connection failures still throw.
     */
    suspend fun requestOrNull(
        payload: ByteArray,
        bizType: Int = 0,
        timeoutMillis: Long = config.requestTimeoutMillis,
        compression: Compression = Compression.None,
    ): ByteArray? = try {
        request(payload, bizType, timeoutMillis, compression)
    } catch (_: RequestTimeoutException) {
        null
    }

    /** Send a one-way message (no response expected). Runs on the owning reactor. */
    suspend fun send(
        payload: ByteArray,
        bizType: Int = 0,
        compression: Compression = Compression.None,
    ) {
        if (kotlin.coroutines.coroutineContext[ContinuationInterceptor] === ownerInterceptor) return sendOnOwner(payload, bizType, compression)
        return withContext(owner) { sendOnOwner(payload, bizType, compression) }
    }

    private suspend fun sendOnOwner(payload: ByteArray, bizType: Int, compression: Compression) {
        check(!closed) { "connection closed" }
        val encoded = prepareOutboundPayload(payload, compression)
        enqueue(Packet(PacketType.OneWay, nextOneWayId(), bizType, encoded, compression = compression))
    }

    /**
     * Invoked once, after the connection has reached its terminal state. The server uses it to
     * cancel the per-connection scope it created: an explicitly constructed [kotlinx.coroutines.Job]
     * never completes on its own just because its children finished, so without this the scope
     * would stay Active forever and its parent would never complete.
     */
    internal var onShutdown: (() -> Unit)? = null

    /** Close the connection. Safe to call from any thread; runs on the owning reactor. */
    suspend fun close(): Unit = withContext(owner) { shutdown() }

    private fun shutdown() {
        if (closed) return
        closed = true
        outbound.close()
        inboundRequests.close()
        inboundEvents.close()
        io.close()
        pending.forEachValue { it.fail(ConnectionClosedException()) }
        pending.clear()
        sendQueue.clear()
        queuedBytes = 0L
        while (roomWaiters.isNotEmpty()) roomWaiters.removeFirst().resumeWithException(ConnectionClosedException())
        // Fail slot-waiters: complete their result and wake them; they observe the completed result.
        while (inFlightWaiters.isNotEmpty()) {
            val req = inFlightWaiters.removeFirst()
            req.fail(ConnectionClosedException())
            req.slotCont?.let { it.resume(Unit); req.slotCont = null }
        }
        // A closed fd is not reliably reported by the reactor, so the loops must be cancelled.
        readJob?.cancel()
        writeJob?.cancel()
        handlerJob?.cancel()
        // Observation must not be able to break transport cleanup or strand the server's
        // per-connection scope if application code throws.
        runCatching { closeHandler?.invoke() }
        closeHandler = null
        onShutdown?.invoke()
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
