package msgtrans.transport

/** An inbound one-way message: application biz type plus payload. */
class Message(val bizType: Int, val payload: ByteArray)

/** Raised when a request cannot complete because the connection closed. */
class ConnectionClosedException(message: String = "connection closed") : Exception(message)

/** Raised when a request gets no response within its timeout; the connection stays open. */
class RequestTimeoutException(val messageId: UInt, val timeoutMillis: Long) :
    Exception("request $messageId timed out after ${timeoutMillis}ms")
