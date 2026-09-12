package msgtrans.core

import neton.io.bytes.Buffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PacketCodecTest {

    @Test
    fun exactWireLayout() {
        val packet = Packet.request(payload = byteArrayOf('h'.code.toByte(), 'i'.code.toByte()), bizType = 100, messageId = 0x01020304u)
        val buf = Buffer()
        PacketCodec.encode(packet, buf)

        val bytes = buf.peekAll()
        val expected = byteArrayOf(
            1,          // version
            0,          // compression = None
            1,          // packet_type = Request
            100.toByte(), // biz_type
            0x01, 0x02, 0x03, 0x04, // message_id (BE)
            0x00, 0x00,             // ext_header_len = 0 (BE)
            0x00, 0x00, 0x00, 0x02, // payload_len = 2 (BE)
            0x00, 0x00,             // reserved
            'h'.code.toByte(), 'i'.code.toByte(),
        )
        assertContentEquals(expected, bytes)
    }

    @Test
    fun roundTrip() {
        val original = Packet(
            type = PacketType.Response,
            messageId = 0xFFFFFFFEu,
            bizType = 7,
            payload = "hello msgtrans".encodeToByteArray(),
            extHeader = byteArrayOf(9, 8, 7),
            compression = Compression.Zlib,
            reserved = Flags.HAS_ROUTE_TAG,
        )
        val buf = Buffer()
        PacketCodec.encode(original, buf)

        val decoded = PacketCodec.decode(buf)!!
        assertEquals(PacketType.Response, decoded.type)
        assertEquals(0xFFFFFFFEu, decoded.messageId)
        assertEquals(7, decoded.bizType)
        assertContentEquals(original.payload, decoded.payload)
        assertContentEquals(original.extHeader, decoded.extHeader)
        assertEquals(Compression.Zlib, decoded.compression)
        assertEquals(Flags.HAS_ROUTE_TAG, decoded.reserved)
        assertEquals(0, buf.readableBytes) // fully consumed
    }

    @Test
    fun needsMoreBytes() {
        val buf = Buffer()
        PacketCodec.encode(Packet.oneWay("data".encodeToByteArray(), bizType = 1, messageId = 5u), buf)
        // Chop the buffer to a partial packet: decode must return null, not throw or consume.
        val full = buf.readAll()
        val partial = Buffer()
        partial.writeBytes(full, 0, full.size - 2)
        assertNull(PacketCodec.decode(partial))
        assertEquals(full.size - 2, partial.readableBytes) // untouched

        // Feeding the rest makes it decodable.
        partial.writeBytes(full, full.size - 2, 2)
        val p = PacketCodec.decode(partial)!!
        assertEquals(PacketType.OneWay, p.type)
        assertContentEquals("data".encodeToByteArray(), p.payload)
    }

    @Test
    fun multipleFramesBackToBack() {
        val buf = Buffer()
        PacketCodec.encode(Packet.oneWay(byteArrayOf(1), 0, 1u), buf)
        PacketCodec.encode(Packet.oneWay(byteArrayOf(2, 2), 0, 2u), buf)
        PacketCodec.encode(Packet.oneWay(byteArrayOf(3, 3, 3), 0, 3u), buf)

        val a = PacketCodec.decode(buf)!!
        val b = PacketCodec.decode(buf)!!
        val c = PacketCodec.decode(buf)!!
        assertEquals(1u, a.messageId)
        assertEquals(2u, b.messageId)
        assertEquals(3u, c.messageId)
        assertNull(PacketCodec.decode(buf))
    }
}
