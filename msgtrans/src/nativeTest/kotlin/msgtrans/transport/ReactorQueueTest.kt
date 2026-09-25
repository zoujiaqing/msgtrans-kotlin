package msgtrans.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import neton.io.core.ClosedException
import neton.io.net.runReactor
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SPEC §11 step 2: the reactor-local queue keeps the Channel contract it replaces. */
@OptIn(ObsoleteWorkersApi::class)
class ReactorQueueTest {

    private suspend fun queue(capacity: Int) = ReactorQueue<Int>(capacity, coroutineContext[ContinuationInterceptor]!!)

    @Test
    fun fifoAndBackpressure() = runReactor {
        val q = queue(2)
        q.send(1); q.send(2)
        var sentThird = false
        val sender = launch { q.send(3); sentThird = true }
        yield(); yield()
        assertEquals(false, sentThird, "send must suspend while the queue is full")
        assertEquals(1, q.receive())                 // makes room, wakes the sender
        yield(); yield()
        assertEquals(true, sentThird)
        assertEquals(2, q.receive()); assertEquals(3, q.receive())
        sender.join()
    }

    @Test
    fun receiverParksUntilSend() = runReactor {
        val q = queue(4)
        val r = async { q.receive() }
        yield()
        q.send(42)
        assertEquals(42, withTimeout(2_000) { r.await() })
    }

    @Test
    fun closeDrainsThenReturnsNullAndFailsAParkedSender() = runReactor {
        val q = queue(1)
        q.send(7)
        val blocked = async { runCatching { q.send(8) }.exceptionOrNull() }
        yield()
        q.close()
        assertTrue(withTimeout(2_000) { blocked.await() } is ClosedException)
        assertEquals(7, q.receive())                 // what was queued before close is still delivered
        assertEquals(null, q.receive())
        assertTrue(runCatching { q.send(9) }.exceptionOrNull() is ClosedException)
    }

    @Test
    fun parkedReceiverWakesOnCloseWithNull() = runReactor {
        val q = queue(4)
        val r = async { q.receive() }
        yield()
        q.close()
        assertEquals(null, withTimeout(2_000) { r.await() })
    }

    @Test
    fun parkedReceiverCancelledFromAnotherThread() = runReactor {
        val q = queue(4)
        var outcome: Throwable? = null
        val r = launch { try { q.receive() } catch (t: Throwable) { outcome = t; throw t } }
        yield()
        val w = Worker.start(name = "canceller")
        w.execute(TransferMode.SAFE, { r }) { it.cancel() }
        withTimeout(2_000) { r.join() }
        w.requestTermination().result
        assertTrue(outcome is CancellationException, "expected CancellationException, got $outcome")
        q.send(1)                                     // the queue still works after a cancelled receiver
        assertEquals(1, q.receive())
    }

    @Test
    fun parkedSenderCancelled() = runReactor {
        val q = queue(1)
        q.send(1)
        val s = launch { q.send(2) }
        yield()
        withTimeout(2_000) { s.cancelAndJoin() }
        assertEquals(1, q.receive())
        q.send(3)                                     // room again; the cancelled send left nothing behind
        assertEquals(3, q.receive())
    }

    @Test
    fun alreadyCancelledCoroutineDoesNotPark() = runReactor {
        val q = queue(4)
        var outcome: Throwable? = null
        val j = launch(start = CoroutineStart.UNDISPATCHED) {
            coroutineContext.job.cancel()
            try { q.receive() } catch (t: Throwable) { outcome = t }
        }
        withTimeout(2_000) { j.join() }
        assertTrue(outcome is CancellationException, "expected CancellationException, got $outcome")
    }
}
