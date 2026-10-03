package msgtrans.transport

/**
 * Open-addressing map from request id to value, for the pending-request registry (SPEC §13 step B).
 * `HashMap<UInt, …>` boxed every id and allocated an entry per request; this stores ids as Ints in
 * a flat table (linear probing, backward-shift deletion). Reactor-thread only.
 */
internal class IdMap<V : Any>(initial: Int = 16) {
    private var keys = IntArray(tableSize(initial))
    private var vals = arrayOfNulls<Any>(keys.size)
    var size = 0
        private set

    private fun tableSize(n: Int): Int { var s = 16; while (s < n * 2) s = s shl 1; return s }
    private fun slot(k: Int, mask: Int): Int = ((k * -0x61c88647) ushr 7) and mask   // Fibonacci hashing

    operator fun set(id: UInt, v: V) {
        if ((size + 1) * 2 > keys.size) grow()
        val k = id.toInt(); val mask = keys.size - 1
        var i = slot(k, mask)
        while (vals[i] != null) {
            if (keys[i] == k) { vals[i] = v; return }
            i = (i + 1) and mask
        }
        keys[i] = k; vals[i] = v; size++
    }

    @Suppress("UNCHECKED_CAST")
    fun remove(id: UInt): V? {
        val k = id.toInt(); val mask = keys.size - 1
        var i = slot(k, mask)
        while (true) {
            val v = vals[i] ?: return null
            if (keys[i] == k) {
                vals[i] = null; size--
                // Backward-shift the cluster after i so later probes still find their keys.
                var j = (i + 1) and mask
                while (vals[j] != null) {
                    val home = slot(keys[j], mask)
                    val between = if (i <= j) (home in (i + 1)..j) else (home in (i + 1)..mask || home <= j)
                    if (!between) { keys[i] = keys[j]; vals[i] = vals[j]; vals[j] = null; i = j }
                    j = (j + 1) and mask
                }
                return v as V
            }
            i = (i + 1) and mask
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun forEachValue(action: (V) -> Unit) { for (v in vals) if (v != null) action(v as V) }

    fun clear() { vals.fill(null); size = 0 }

    private fun grow() {
        val oldK = keys; val oldV = vals
        keys = IntArray(oldK.size * 2); vals = arrayOfNulls(keys.size); size = 0
        @Suppress("UNCHECKED_CAST")
        for (i in oldK.indices) { val v = oldV[i]; if (v != null) set(oldK[i].toUInt(), v as V) }
    }
}
