package msgtrans.transport

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** IdMap against a HashMap reference under random inserts, removals and overwrites (SPEC §13 step B). */
class IdMapTest {
    @Test
    fun matchesAHashMapUnderRandomOperations() {
        val rnd = Random(42)
        val m = IdMap<String>()
        val ref = HashMap<UInt, String>()
        repeat(50_000) { step ->
            val id = rnd.nextInt(0, 3000).toUInt()
            when (rnd.nextInt(3)) {
                0, 1 -> { m[id] = "v$step"; ref[id] = "v$step" }
                else -> assertEquals(ref.remove(id), m.remove(id), "remove($id) at step $step")
            }
            assertEquals(ref.size, m.size, "size at step $step")
        }
        for ((k, v) in ref) assertEquals(v, m.remove(k))
        assertEquals(0, m.size)
    }

    @Test
    fun sequentialIdsAsRequestsUseThem() {
        val m = IdMap<Int>()
        for (i in 1..100_000) { m[i.toUInt()] = i; if (i > 12) assertEquals(i - 12, m.remove((i - 12).toUInt())) }
        assertEquals(12, m.size)
    }
}
