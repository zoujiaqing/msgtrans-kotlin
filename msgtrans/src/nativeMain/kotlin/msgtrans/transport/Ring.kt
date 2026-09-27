package msgtrans.transport

/**
 * A minimal FIFO ring for reactor-local queues (SPEC §12 step 6). `ArrayDeque`'s bookkeeping cost
 * ~130 instructions per add/remove pair on the request path; this does the work and nothing else.
 * Single-threaded: callers are on the owning reactor.
 */
internal class Ring<T : Any>(initial: Int = 16) {
    private var items = arrayOfNulls<Any>(initial.coerceAtLeast(4))
    private var head = 0
    var size = 0
        private set

    fun isEmpty(): Boolean = size == 0
    fun isNotEmpty(): Boolean = size != 0

    fun addLast(x: T) {
        if (size == items.size) grow()
        items[(head + size) % items.size] = x
        size++
    }

    @Suppress("UNCHECKED_CAST")
    fun removeFirstOrNull(): T? {
        if (size == 0) return null
        val x = items[head] as T
        items[head] = null
        head = (head + 1) % items.size
        size--
        return x
    }

    fun clear() {
        for (i in 0 until size) items[(head + i) % items.size] = null
        head = 0; size = 0
    }

    private fun grow() {
        val n = items.size
        val bigger = arrayOfNulls<Any>(n * 2)
        for (i in 0 until size) bigger[i] = items[(head + i) % n]
        items = bigger; head = 0
    }
}
