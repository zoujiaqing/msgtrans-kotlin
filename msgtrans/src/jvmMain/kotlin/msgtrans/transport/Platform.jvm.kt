package msgtrans.transport

internal actual fun envVar(name: String): String? = System.getenv(name)

internal actual fun writeStderrLine(line: String) {
    System.err.println(line)
    System.err.flush()
}
