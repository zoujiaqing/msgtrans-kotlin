package msgtrans.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PayloadCompressionTest {
    private val repetitive = "msgtrans:".repeat(2_000).encodeToByteArray()

    @Test
    fun zstdAndZlibRoundTrip() {
        for (compression in listOf(Compression.Zstd, Compression.Zlib)) {
            val encoded = PayloadCompression.compress(repetitive, compression)
            assertTrue(encoded.size < repetitive.size, "$compression should shrink repetitive data")
            assertContentEquals(repetitive, PayloadCompression.decompress(encoded, compression))
        }
    }

    @Test
    fun inboundPacketIsNormalizedToPlaintext() {
        val original = Packet.request(repetitive, bizType = 7, messageId = 42u)
        val encoded = PayloadCompression.compress(original, Compression.Zstd)
        assertEquals(Compression.Zstd, encoded.compression)

        val normalized = PayloadCompression.decompress(encoded)
        assertEquals(Compression.None, normalized.compression)
        assertEquals(original.type, normalized.type)
        assertEquals(original.messageId, normalized.messageId)
        assertEquals(original.bizType, normalized.bizType)
        assertContentEquals(repetitive, normalized.payload)
    }

    @Test
    fun decodesFixturesProducedByRustMsgtrans() {
        val expected = "msgtrans cross language compression fixture: 0123456789 0123456789 0123456789"
            .encodeToByteArray()
        val rustZstd = hex("28b52ffd204dfd010074036d73677472616e732063726f7373206c616e677561676520636f6d7072657373696f6e20666978747572653a2030313233343536373839010067a54001")
        val rustZlib = hex("789c6dc6db09c0300805d0551ca1efd73612ac041a2dde043a7e17c8df29d01a6ca0140ed0c3a68d552879794380ec4677fe6a0bb96818a77959b7fd38fbfc012fe817d8")

        assertContentEquals(expected, PayloadCompression.decompress(rustZstd, Compression.Zstd))
        assertContentEquals(expected, PayloadCompression.decompress(rustZlib, Compression.Zlib))
    }

    @Test
    fun refusesMalformedAndOversizedOutput() {
        for (compression in listOf(Compression.Zstd, Compression.Zlib)) {
            assertFailsWith<CompressionException> {
                PayloadCompression.decompress(byteArrayOf(1, 2, 3), compression)
            }
            val encoded = PayloadCompression.compress(ByteArray(2_048) { 1 }, compression)
            assertFailsWith<CompressionException> {
                PayloadCompression.decompress(encoded, compression, maxOutputSize = 1_024)
            }
        }

        // This limit bounds expansion by a codec. Plain packets retain the existing frame limit.
        val plain = ByteArray(2_048) { 1 }
        assertContentEquals(plain, PayloadCompression.decompress(plain, Compression.None, 1_024))
    }

    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
