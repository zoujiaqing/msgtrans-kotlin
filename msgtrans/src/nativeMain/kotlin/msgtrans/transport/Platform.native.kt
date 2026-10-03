@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package msgtrans.transport

import kotlinx.cinterop.toKString

internal actual fun envVar(name: String): String? = platform.posix.getenv(name)?.toKString()

internal actual fun writeStderrLine(line: String) {
    platform.posix.fprintf(platform.posix.stderr, "%s\n", line)
    platform.posix.fflush(platform.posix.stderr)
}
