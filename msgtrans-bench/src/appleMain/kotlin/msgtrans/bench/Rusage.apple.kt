@file:OptIn(ExperimentalForeignApi::class)

package msgtrans.bench

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.RUSAGE_SELF
import platform.posix.getrusage
import platform.posix.rusage

/** Apple: getrusage; ru_maxrss is in bytes. */
internal actual fun selfRusage(): Rusage = memScoped {
    val ru = alloc<rusage>()
    getrusage(RUSAGE_SELF, ru.ptr)
    val user = ru.ru_utime.tv_sec.toDouble() + ru.ru_utime.tv_usec.toDouble() / 1e6
    val sys = ru.ru_stime.tv_sec.toDouble() + ru.ru_stime.tv_usec.toDouble() / 1e6
    Rusage(user, sys, ru.ru_maxrss.toLong())
}
