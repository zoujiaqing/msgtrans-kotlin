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
 */
object PacketCodec : Decoder<Packet>, Encoder<Packet> {

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

        val version = buf.getByte(0).toInt() and 0xFF
        if (version != Packet.PROTOCOL_VERSION) throw ProtocolException("unsupported version=$version")

        val compression = Compression.fromCode(buf.getByte(1).toInt() and 0xFF)
        val type = PacketType.fromCode(buf.getByte(2).toInt() and 0xFF)
        val bizType = buf.getByte(3).toInt() and 0xFF
        val messageId = buf.getU32(4)
        val extHeaderLen = buf.getU16(8)
        val payloadLen = buf.getU32(10).toLong() and 0xFFFFFFFFL
        val reserved = buf.getU16(14)

        val total = Packet.FIXED_HEADER_SIZE + extHeaderLen + payloadLen
        if (buf.readableBytes < total) return null

        buf.skip(Packet.FIXED_HEADER_SIZE)
        val extHeader = if (extHeaderLen > 0) buf.readBytes(extHeaderLen) else Packet.EMPTY
        val payload = if (payloadLen > 0) buf.readBytes(payloadLen.toInt()) else Packet.EMPTY

        return Packet(type, messageId, bizType, payload, extHeader, compression, reserved)
    }
}

// ---- big-endian helpers on Buffer ----

private fun Buffer.writeU16(value: Int) {
    writeByte(((value ushr 8) and 0xFF).toByte())
    writeByte((value and 0xFF).toByte())
}

private fun Buffer.writeU32(value: UInt) {
    val v = value.toInt()
    writeByte(((v ushr 24) and 0xFF).toByte())
    writeByte(((v ushr 16) and 0xFF).toByte())
    writeByte(((v ushr 8) and 0xFF).toByte())
    writeByte((v and 0xFF).toByte())
}

private fun Buffer.getU16(offset: Int): Int =
    ((getByte(offset).toInt() and 0xFF) shl 8) or (getByte(offset + 1).toInt() and 0xFF)

private fun Buffer.getU32(offset: Int): UInt {
    val b0 = getByte(offset).toInt() and 0xFF
    val b1 = getByte(offset + 1).toInt() and 0xFF
    val b2 = getByte(offset + 2).toInt() and 0xFF
    val b3 = getByte(offset + 3).toInt() and 0xFF
    return ((b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3).toUInt()
}
