package msgtrans.transport

/** An inbound one-way message: application biz type plus payload. */
class Message(val bizType: Int, val payload: ByteArray)

/** Raised when a request cannot complete because the connection closed. */
class ConnectionClosedException(message: String = "connection closed") : Exception(message)
