package msgtrans.transport

/**
 * Answers an inbound request (SPEC §12 step 4). A `fun interface` rather than a suspend function
 * type: `conn.onRequest { payload, bizType -> … }` still compiles (SAM conversion), and calling it
 * is a plain interface call — invoking a suspend lambda allocated a coroutine object per request.
 */
fun interface RequestHandler {
    suspend fun handle(payload: ByteArray, bizType: Int): ByteArray
}
