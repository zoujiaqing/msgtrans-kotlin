package msgtrans.transport

// The transport is shared by native and the JVM; these are the only two things it asks of the platform.

/** An environment variable, or null (the MSGTRANS_* switches). */
internal expect fun envVar(name: String): String?

/** One line on standard error, flushed (connection faults must be visible without failing the process). */
internal expect fun writeStderrLine(line: String)
