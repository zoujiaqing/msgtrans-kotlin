package msgtrans.core

/** Packet type (wire byte at offset 2). */
enum class PacketType(val code: Int) {
    OneWay(0),
    Request(1),
    Response(2);

    companion object {
        fun fromCode(code: Int): PacketType = when (code) {
            0 -> OneWay
            1 -> Request
            2 -> Response
            else -> throw ProtocolException("unknown packet_type=$code")
        }
    }
}

/** Payload compression (wire byte at offset 1). Value 1 is Zstd, 2 is Zlib (deliberately not alphabetical). */
enum class Compression(val code: Int) {
    None(0),
    Zstd(1),
    Zlib(2);

    companion object {
        fun fromCode(code: Int): Compression = when (code) {
            0 -> None
            1 -> Zstd
            2 -> Zlib
            else -> throw ProtocolException("unknown compression=$code")
        }
    }
}

/** Reserved flag bits (offset 14, u16). */
object Flags {
    const val FRAGMENTED = 0x0001
    const val HIGH_PRIORITY = 0x0002
    const val HAS_ROUTE_TAG = 0x0004
}

open class ProtocolException(message: String) : Exception(message)

/**
 * One msgtrans packet. Layout is defined by the wire spec (16-byte big-endian fixed header,
 * then ext header, then payload). This is the language-neutral contract shared with the Rust
 * and TypeScript implementations, so field semantics must not drift.
 *
 * [messageId] is a u32 (represented as [UInt]); the sender allocates it from a monotonic
 * counter. A Response reuses the matching Request's id.
 */
class Packet(
    val type: PacketType,
    val messageId: UInt,
    val bizType: Int = 0,
    val payload: ByteArray = EMPTY,
    val extHeader: ByteArray = EMPTY,
    val compression: Compression = Compression.None,
    val reserved: Int = 0,
) {
    init {
        require(bizType in 0..255) { "biz_type out of range: $bizType" }
        require(reserved in 0..0xFFFF) { "reserved out of range: $reserved" }
    }

    /** Total serialized size in bytes. */
    val size: Int get() = FIXED_HEADER_SIZE + extHeader.size + payload.size

    internal fun withPayload(newPayload: ByteArray, newCompression: Compression): Packet =
        Packet(type, messageId, bizType, newPayload, extHeader, newCompression, reserved)

    companion object {
        const val PROTOCOL_VERSION = 1
        const val FIXED_HEADER_SIZE = 16
        val EMPTY = ByteArray(0)

        fun oneWay(payload: ByteArray, bizType: Int, messageId: UInt): Packet =
            Packet(PacketType.OneWay, messageId, bizType, payload)

        fun request(payload: ByteArray, bizType: Int, messageId: UInt): Packet =
            Packet(PacketType.Request, messageId, bizType, payload)

        /** A response reuses the request's [messageId]. */
        fun response(payload: ByteArray, bizType: Int, messageId: UInt): Packet =
            Packet(PacketType.Response, messageId, bizType, payload)
    }
}
