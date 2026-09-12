@file:OptIn(ExperimentalForeignApi::class)

package msgtrans.transport.bench

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.FILE
import platform.posix._SC_CLK_TCK
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.sysconf

/**
 * Linux: getrusage is not in the Kotlin/Native binding, so read /proc/self.
 * CPU: utime+stime from /proc/self/stat (fields 14,15, in clock ticks). RSS: VmHWM from
 * /proc/self/status (peak resident, in kB).
 */
internal actual fun selfRusage(): Rusage {
    val stat = readFile("/proc/self/stat") ?: ""
    val ticks = sysconf(_SC_CLK_TCK).toDouble().let { if (it > 0) it else 100.0 }
    // stat: "pid (comm) state ppid ..." — comm may contain spaces/parens, so split after the last ')'.
    var user = 0.0; var sys = 0.0
    val rp = stat.lastIndexOf(')')
    if (rp > 0) {
        val rest = stat.substring(rp + 2).split(' ')
        // rest[0] = state (field 3); utime = field 14 -> rest[11], stime = field 15 -> rest[12]
        user = (rest.getOrNull(11)?.toLongOrNull() ?: 0L) / ticks
        sys = (rest.getOrNull(12)?.toLongOrNull() ?: 0L) / ticks
    }
    var maxRssBytes = 0L
    readFile("/proc/self/status")?.lineSequence()?.forEach { line ->
        if (line.startsWith("VmHWM:")) {
            val kb = line.filter { it.isDigit() }.toLongOrNull() ?: 0L
            maxRssBytes = kb * 1024
        }
    }
    return Rusage(user, sys, maxRssBytes)
}

private fun readFile(path: String): String? = memScoped {
    val f: kotlinx.cinterop.CPointer<FILE> = fopen(path, "r") ?: return null
    try {
        val sb = StringBuilder()
        val buf = allocArray<kotlinx.cinterop.ByteVar>(4096)
        while (true) {
            val line = fgets(buf, 4096, f) ?: break
            sb.append(line.toKString())
        }
        sb.toString()
    } finally {
        fclose(f)
    }
}
