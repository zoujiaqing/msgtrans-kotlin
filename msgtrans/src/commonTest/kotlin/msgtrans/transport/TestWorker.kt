package msgtrans.transport

/**
 * "Another thread" for tests of the cross-thread contract. A Kotlin/Native Worker on native, a
 * single-thread executor on the JVM; jobs run in order.
 */
internal interface TestWorker {
    /** Run [block] on the worker; the returned function waits for it and returns its result. */
    fun <T> submit(block: () -> T): () -> T

    /** Finish the queued jobs, then end the thread; returns once it has. */
    fun stop()
}

internal expect fun startTestWorker(name: String = "test-worker"): TestWorker

/** An identity for the calling thread, equal for calls on the same thread. */
internal expect fun testThreadKey(): Long
