package msgtrans.transport

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import neton.io.core.ClosedException
import kotlin.coroutines.Continuation
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.intercepted
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * A bounded single-producer / single-consumer queue owned by one reactor thread (SPEC §11 step 2).
 *
 * Replaces a kotlinx `Channel` on the connection's inbound request path. Every operation runs on
 * the owning reactor (the connection's dual-entry contract), so no atomics are needed; parked
 * coroutines are stored as their own continuations and resumed through their dispatcher
 * (`intercepted()`, cached per coroutine — no allocation, no re-entry into the resumer's stack).
 *
 * Contract, same as the Channel it replaces: [send] suspends while the queue is full (backpressure),
 * [receive] suspends while it is empty and returns null once the queue is closed and drained, and
 * both wake with [kotlinx.coroutines.CancellationException] when their coroutine is cancelled.
 * [send] on a closed queue throws [ClosedException].
 *
 * Cancellation is watched once per coroutine Job (the read and handler loops live as long as the
 * connection), not once per park. The handler may run on any thread, so it hops to [owner] before
 * touching a waiter slot; every path that resumes a waiter takes it out of its slot first, so a
 * waiter is never resumed twice.
 */
@OptIn(InternalCoroutinesApi::class)
internal class ReactorQueue<T : Any>(private val capacity: Int, private val owner: CoroutineContext) {
    private val items = ArrayDeque<T>(minOf(capacity, 16))
    private var takeWaiter: Continuation<T?>? = null
    private var putWaiter: Continuation<Unit>? = null
    private var closed = false

    private var watchedA: Job? = null; private var handleA: DisposableHandle? = null
    private var watchedB: Job? = null; private var handleB: DisposableHandle? = null

    suspend fun send(item: T) {
        while (true) {
            if (closed) throw ClosedException()
            val c = takeWaiter
            if (c != null) { takeWaiter = null; c.intercepted().resume(item); return }
            if (items.size < capacity) { items.addLast(item); return }
            parkPut()
        }
    }

    suspend fun receive(): T? {
        if (items.isNotEmpty()) {
            val x = items.removeFirst()
            putWaiter?.let { putWaiter = null; it.intercepted().resume(Unit) }
            return x
        }
        if (closed) return null
        return parkTake()                       // an item handed over by send(), or null on close
    }

    /** Close: a parked receiver gets null (after draining), a parked sender gets [ClosedException]. */
    fun close() {
        if (closed) return
        closed = true
        if (items.isEmpty()) takeWaiter?.let { takeWaiter = null; it.intercepted().resume(null) }
        putWaiter?.let { putWaiter = null; it.intercepted().resumeWithException(ClosedException()) }
        handleA?.dispose(); handleB?.dispose()
        handleA = null; handleB = null; watchedA = null; watchedB = null
    }

    private suspend fun parkTake(): T? = suspendCoroutineUninterceptedOrReturn { cont ->
        watch(cont)
        takeWaiter = cont
        COROUTINE_SUSPENDED
    }

    private suspend fun parkPut(): Unit = suspendCoroutineUninterceptedOrReturn { cont ->
        watch(cont)
        putWaiter = cont
        COROUTINE_SUSPENDED
    }

    private fun watch(cont: Continuation<*>) {
        val job = cont.context[Job] ?: return
        if (!job.isActive) throw job.getCancellationException()
        if (job === watchedA || job === watchedB) return
        val handle = job.invokeOnCompletion(onCancelling = true, invokeImmediately = false) {
            val d = owner[ContinuationInterceptor] as CoroutineDispatcher
            d.dispatch(owner, Runnable { onCancelled(job) })
        }
        // Two slots: the producer's and the consumer's loop. A third job evicts the older watch.
        if (watchedA == null) { watchedA = job; handleA = handle }
        else if (watchedB == null) { watchedB = job; handleB = handle }
        else { handleA?.dispose(); watchedA = watchedB; handleA = handleB; watchedB = job; handleB = handle }
    }

    private fun onCancelled(job: Job) {
        val t = takeWaiter
        if (t != null && t.context[Job] === job) { takeWaiter = null; t.intercepted().resumeWithException(job.getCancellationException()) }
        val p = putWaiter
        if (p != null && p.context[Job] === job) { putWaiter = null; p.intercepted().resumeWithException(job.getCancellationException()) }
    }
}
