package msgtrans.core

import neton.io.bytes.Buffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** msgtrans-rust's QUIC framing: `[u32 BE length] + [packet]`, one packet per frame. */
class LengthPrefixedPacketCodecTest {
    private val codec = LengthPrefixedPacketCodec()

    private fun frame(vararg bytes: Int) = Buffer().also { b -> bytes.forEach { b.writeByte(it.toByte()) } }

    @Test
    fun exactWireLayout() {
        val buf = Buffer()
        codec.encode(Packet.request(payload = "hi".encodeToByteArray(), bizType = 100, messageId = 0x01020304u), buf)
        val expected = byteArrayOf(
            0, 0, 0, 18,            // frame length: 16-byte header + 2-byte payload
            1, 0, 1, 100,           // version, compression, Request, biz_type
            1, 2, 3, 4,             // message_id
            0, 0, 0, 0, 0, 2, 0, 0, // ext_header_len, payload_len, reserved
            'h'.code.toByte(), 'i'.code.toByte(),
        )
        assertContentEquals(expected, buf.peekAll())
    }

    @Test
    fun roundTripsAndConsumesExactlyOneFrame() {
        val buf = Buffer()
        val a = Packet(PacketType.Response, 7u, 3, "payload".encodeToByteArray(), byteArrayOf(9, 8), Compression.Zlib, 5)
        val b = Packet.oneWay(ByteArray(0), bizType = 1, messageId = 8u)
        codec.encode(a, buf); codec.encode(b, buf)
        val first = codec.decode(buf)!!
        assertEquals(PacketType.Response, first.type); assertEquals(7u, first.messageId)
        assertContentEquals(a.payload, first.payload); assertContentEquals(a.extHeader, first.extHeader)
        assertEquals(Compression.Zlib, first.compression); assertEquals(5, first.reserved)
        assertEquals(4 + 16, buf.readableBytes)
        assertEquals(8u, codec.decode(buf)!!.messageId)
        assertEquals(0, buf.readableBytes)
    }

    @Test
    fun waitsForTheWholeFrameWithoutConsuming() {
        val full = Buffer().also { codec.encode(Packet.request("abc".encodeToByteArray(), 1, 1u), it) }.peekAll()
        for (n in 0 until full.size) {
            val buf = Buffer().also { it.writeBytes(full, 0, n) }
            assertNull(codec.decode(buf), "$n bytes")
            assertEquals(n, buf.readableBytes)
        }
    }

    @Test
    fun aFrameLongerThanAnyPacketIsRefusedFromItsPrefix() {
        val small = LengthPrefixedPacketCodec(maxPayloadLength = 100)
        assertFailsWith<ProtocolException> { small.decode(frame(0, 1, 1, 0)) } // 65,792 > 16 + 65,535 + 100
        assertNull(small.decode(frame(0, 1, 0, 0x73)))                         // 65,651: the limit, still incomplete
    }

    @Test
    fun aFrameMustHoldExactlyOnePacket() {
        // Shorter than a header.
        assertFailsWith<ProtocolException> { codec.decode(frame(0, 0, 0, 2, 1, 0)) }
        // A header announcing 2 payload bytes in a frame with room for 3.
        assertFailsWith<ProtocolException> {
            codec.decode(frame(0, 0, 0, 19, 1, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 2, 0, 0, 1, 2, 3))
        }
        // A header announcing 4 payload bytes in a frame with room for 2.
        assertFailsWith<ProtocolException> {
            codec.decode(frame(0, 0, 0, 18, 1, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 4, 0, 0, 1, 2))
        }
    }
}
