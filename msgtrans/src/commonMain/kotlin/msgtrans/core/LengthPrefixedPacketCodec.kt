package msgtrans.core

import neton.io.bytes.Buffer
import neton.io.codec.Decoder
import neton.io.codec.Encoder

/**
 * The msgtrans packets of a QUIC stream: each [Packet] (the wire format of [PacketCodec]) preceded by its length as a
 * u32, big-endian — msgtrans-rust's `QuicAdapter` framing (`[4-byte length] + [packet data]` on one bidirectional
 * stream). TCP and WebSocket carry packets without the prefix.
 *
 * Decoding is strict: a frame must hold exactly one packet (msgtrans-rust's `Packet::decode_exact_from`), and a frame
 * longer than the largest packet [maxPayloadLength] allows is rejected from its prefix, before it is buffered. Either
 * raises [ProtocolException], as msgtrans-rust's default `FramePolicy::Strict` does (its opt-in `Lenient` policy turns
 * such a frame into a OneWay packet with the raw bytes as payload).
 */
class LengthPrefixedPacketCodec(
    /** Reject a packet claiming a payload larger than this (as [PacketCodec.maxPayloadLength]). */
    val maxPayloadLength: Long = PacketCodec.DEFAULT_MAX_PAYLOAD,
) : Decoder<Packet>, Encoder<Packet> {
    private val packets = PacketCodec(maxPayloadLength)

    /** The longest frame a valid packet can need: the fixed header, the largest ext header and payload. */
    private val maxFrameLength: Long = Packet.FIXED_HEADER_SIZE + 0xFFFFL + maxPayloadLength

    override fun encode(item: Packet, out: Buffer) {
        out.writeInt(Packet.FIXED_HEADER_SIZE + item.extHeader.size + item.payload.size)
        packets.encode(item, out)
    }

    override fun decode(buf: Buffer): Packet? {
        if (buf.readableBytes < PREFIX) return null
        val length = buf.getInt(0).toLong() and 0xFFFFFFFFL
        if (length > maxFrameLength) throw ProtocolException("frame_len=$length exceeds max=$maxFrameLength")
        if (buf.readableBytes - PREFIX < length) return null
        if (length < Packet.FIXED_HEADER_SIZE) throw ProtocolException("frame_len=$length is shorter than a packet header")
        // The packet the header announces must fill the frame exactly.
        val packetLength = Packet.FIXED_HEADER_SIZE + buf.getUnsignedShort(PREFIX + 8) +
            (buf.getInt(PREFIX + 10).toLong() and 0xFFFFFFFFL)
        if (packetLength != length) throw ProtocolException("frame_len=$length holds a packet of $packetLength bytes")
        buf.skip(PREFIX)
        return packets.decode(buf) ?: throw ProtocolException("incomplete packet in a complete frame")
    }

    private companion object {
        const val PREFIX = 4
    }
}
