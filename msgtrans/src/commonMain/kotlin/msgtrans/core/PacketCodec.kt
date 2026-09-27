package msgtrans.core

import neton.io.bytes.Buffer
import neton.io.codec.Decoder
import neton.io.codec.Encoder

/**
 * Encodes/decodes [Packet]s in the msgtrans wire format (protocol version 1).
 *
 * All multi-byte integers are big-endian. The decoder returns null until a full packet is
 * buffered, so it composes with neton-io's framed read loop. Malformed packets raise
 * [ProtocolException].
 *
 * v1 does not compress in-transport: [Compression] other than None is carried on the wire but
 * this codec does not de/compress the payload itself (that belongs to a higher layer).
 *
 * [maxPayloadLength] is per instance (a header claiming a larger payload is rejected before the
 * bytes are buffered). It is a constructor value, not shared mutable state, so each connection
 * bounds its own inbound frame size — the piece that makes a connection's inbound memory bound
 * (queue depth × maxPayloadLength) meaningful. [Default] keeps the 64 MiB default for callers that
 * do not care.
 */
class PacketCodec(
    /** Reject a header claiming a payload larger than this before buffering it (overload guard). */
    val maxPayloadLength: Long = DEFAULT_MAX_PAYLOAD,
) : Decoder<Packet>, Encoder<Packet> {

    companion object {
        const val DEFAULT_MAX_PAYLOAD: Long = 64L * 1024 * 1024
        /** Shared stateless default instance (encode/decode with the default limit). */
        val Default = PacketCodec()
    }

    override fun encode(item: Packet, out: Buffer) {
        out.writeByte(Packet.PROTOCOL_VERSION.toByte())
        out.writeByte(item.compression.code.toByte())
        out.writeByte(item.type.code.toByte())
        out.writeByte(item.bizType.toByte())
        out.writeU32(item.messageId)
        out.writeU16(item.extHeader.size)
        out.writeU32(item.payload.size.toUInt())
        out.writeU16(item.reserved)
        if (item.extHeader.isNotEmpty()) out.writeBytes(item.extHeader)
        if (item.payload.isNotEmpty()) out.writeBytes(item.payload)
    }

    override fun decode(buf: Buffer): Packet? {
        if (buf.readableBytes < Packet.FIXED_HEADER_SIZE) return null

        val version = buf.getUnsignedByte(0)
        if (version != Packet.PROTOCOL_VERSION) throw ProtocolException("unsupported version=$version")

        val compression = Compression.fromCode(buf.getUnsignedByte(1))
        val type = PacketType.fromCode(buf.getUnsignedByte(2))
        val bizType = buf.getUnsignedByte(3)
        val messageId = buf.getU32(4)
        val extHeaderLen = buf.getU16(8)
        val payloadLen = buf.getU32(10).toLong() and 0xFFFFFFFFL
        val reserved = buf.getU16(14)

        if (payloadLen > maxPayloadLength) throw ProtocolException("payload_len=$payloadLen exceeds max=$maxPayloadLength")

        val total = Packet.FIXED_HEADER_SIZE + extHeaderLen + payloadLen
        if (buf.readableBytes < total) return null

        buf.skip(Packet.FIXED_HEADER_SIZE)
        val extHeader = if (extHeaderLen > 0) buf.readBytes(extHeaderLen) else Packet.EMPTY
        val payload = if (payloadLen > 0) buf.readBytes(payloadLen.toInt()) else Packet.EMPTY

        return Packet(type, messageId, bizType, payload, extHeader, compression, reserved)
    }
}

// ---- big-endian helpers: neton-io's Buffer primitives, one bounds check per value (neton-io SPEC §24.10) ----

private fun Buffer.writeU16(value: Int) = writeShort(value)

private fun Buffer.writeU32(value: UInt) = writeInt(value.toInt())

private fun Buffer.getU16(offset: Int): Int = getUnsignedShort(offset)

private fun Buffer.getU32(offset: Int): UInt = getInt(offset).toUInt()
