package msgtrans.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import neton.io.core.ClosedException
import neton.io.net.runReactor
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * SPEC §15.1: a hand-off refused by a stopped reactor (neton-io §28.3) closes the queue and is
 * reported, not lost silently. Only reachable through a lifecycle bug, which this test commits on
 * purpose: a receiver parked in a coroutine outside the reactor's scope, then the reactor stops.
 */
class ReactorQueueRefusedTest {
    @Test
    fun refusedHandOffClosesTheQueue() {
        if (!resumerEnabled) { println("SKIP: MSGTRANS_REACTOR_RESUMER=0 (no resumer, nothing refuses)"); return }
        var queue: ReactorQueue<String>? = null
        runReactor {
            val q = ReactorQueue<String>(4, coroutineContext)
            queue = q
            // Outside the root scope: the reactor does not wait for it and stops with it parked.
            CoroutineScope(coroutineContext[ContinuationInterceptor]!!).launch { q.receive() }
            yield()
        }
        // Same thread, the first reactor stopped: the hand-off to its parked receiver is refused.
        runReactor { queue!!.send("x") }
        val e = runCatching { runReactor { queue!!.send("y") } }.exceptionOrNull()
        assertTrue(e is ClosedException, "the queue must be closed after a refused hand-off, got $e")
    }
}
