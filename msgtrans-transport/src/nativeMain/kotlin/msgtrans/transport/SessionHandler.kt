package msgtrans.transport

/**
 * Application callbacks for one connection. Both run on the connection's own reactor coroutine,
 * so a slow handler backpressures only its own connection (never a shared bus).
 */
interface SessionHandler {
    /** Handle an inbound Request and return the response payload. Default: empty. */
    suspend fun onRequest(payload: ByteArray, bizType: Int): ByteArray = ByteArray(0)

    /** Handle an inbound one-way message. Default: ignore. */
    suspend fun onMessage(payload: ByteArray, bizType: Int) {}
}

/** Raised when a request cannot complete because the connection closed. */
class ConnectionClosedException(message: String = "connection closed") : Exception(message)
