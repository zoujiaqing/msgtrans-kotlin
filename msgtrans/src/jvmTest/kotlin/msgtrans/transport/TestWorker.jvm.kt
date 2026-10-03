package msgtrans.transport

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal actual fun startTestWorker(name: String): TestWorker = object : TestWorker {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, name).apply { isDaemon = true } }

    override fun <T> submit(block: () -> T): () -> T {
        val future = executor.submit(Callable { block() })
        return { try { future.get() } catch (e: ExecutionException) { throw e.cause ?: e } }
    }

    override fun stop() {
        executor.shutdown()
        check(executor.awaitTermination(60, TimeUnit.SECONDS)) { "test worker $name did not finish" }
    }
}

@Suppress("DEPRECATION")   // Thread.threadId() is Java 19
internal actual fun testThreadKey(): Long = Thread.currentThread().id
