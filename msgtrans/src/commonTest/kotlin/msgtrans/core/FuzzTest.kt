package msgtrans.core

import neton.io.bytes.Buffer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

// Fuzzing of what reads peer bytes: the packet decoder and payload decompression. Inputs are valid encodings
// mutated byte-wise, and random bytes, from fixed seeds (reproducible). Each must either succeed or throw
// ProtocolException (CompressionException is one), never another exception; unmutated inputs must round-trip.
// The packet decoder must also leave the buffer untouched when it needs more bytes and consume exactly one
// packet when it returns one.

private const val CASES = 5_000

/** One of the usual mutations of [seed]: bit flips, overwrites, insertions, deletions, truncation, duplication. */
private fun Random.mutate(seed: ByteArray): ByteArray {
    var b = seed.copyOf()
    repeat(nextInt(1, 4)) {
        b = when (nextInt(6)) {
            0 -> b.also { if (it.isNotEmpty()) { val i = nextInt(it.size); it[i] = (it[i].toInt() xor (1 shl nextInt(8))).toByte() } }
            1 -> b.also { if (it.isNotEmpty()) it[nextInt(it.size)] = nextInt(256).toByte() }
            2 -> { val i = nextInt(b.size + 1); b.copyOfRange(0, i) + nextBytes(nextInt(1, 9)) + b.copyOfRange(i, b.size) }
            3 -> if (b.isEmpty()) b else { val i = nextInt(b.size); val n = nextInt(1, minOf(8, b.size - i) + 1); b.copyOfRange(0, i) + b.copyOfRange(i + n, b.size) }
            4 -> b.copyOfRange(0, nextInt(b.size + 1))
            else -> if (b.isEmpty()) b else { val i = nextInt(b.size); val n = nextInt(1, b.size - i + 1); b.copyOfRange(0, i + n) + b.copyOfRange(i, b.size) }
        }
    }
    return b
}

private fun Random.packet(): Packet = Packet(
    type = PacketType.entries.random(this),
    messageId = nextInt().toUInt(),
    bizType = nextInt(256),
    payload = nextBytes(if (nextInt(8) == 0) nextInt(0, 5000) else nextInt(0, 64)),
    extHeader = nextBytes(if (nextBoolean()) 0 else nextInt(0, 32)),
    compression = Compression.entries.random(this),
    reserved = nextInt(65536),
)

private fun assertSamePacket(expected: Packet, actual: Packet) {
    assertEquals(expected.type, actual.type)
    assertEquals(expected.messageId, actual.messageId)
    assertEquals(expected.bizType, actual.bizType)
    assertContentEquals(expected.payload, actual.payload)
    assertContentEquals(expected.extHeader, actual.extHeader)
    assertEquals(expected.compression, actual.compression)
    assertEquals(expected.reserved, actual.reserved)
}

class FuzzTest {
    /** Feeds [input] in random chunks; returns the packets decoded, or null at the first ProtocolException. */
    private fun decodeAll(random: Random, codec: PacketCodec, input: ByteArray): List<Packet>? {
        val buf = Buffer()
        val out = ArrayList<Packet>()
        var i = 0
        try {
            while (i < input.size) {
                val n = random.nextInt(1, minOf(input.size - i, 256) + 1)
                buf.writeBytes(input, i, n)
                i += n
                while (true) {
                    val before = buf.readableBytes
                    val p = codec.decode(buf)
                    if (p == null) {
                        assertEquals(before, buf.readableBytes, "a null result consumes nothing")
                        break
                    }
                    assertEquals(Packet.FIXED_HEADER_SIZE + p.extHeader.size + p.payload.size, before - buf.readableBytes)
                    assertTrue(p.payload.size <= codec.maxPayloadLength)
                    out += p
                }
            }
        } catch (e: ProtocolException) {
            return null
        } catch (e: Throwable) {
            fail("unexpected ${e::class.simpleName}: $e")
        }
        return out
    }

    @Test
    fun packets() {
        val random = Random(0x6d7367)
        val codecs = listOf(PacketCodec.Default, PacketCodec(maxPayloadLength = 100))
        var ok = 0
        repeat(CASES) {
            val packets = List(random.nextInt(1, 5)) { random.packet() }
            val buf = Buffer()
            for (p in packets) PacketCodec.Default.encode(p, buf)
            val seed = buf.readBytes(buf.readableBytes)
            val decoded = decodeAll(random, PacketCodec.Default, seed)!!
            assertEquals(packets.size, decoded.size, "round trip")
            packets.zip(decoded).forEach { (e, a) -> assertSamePacket(e, a) }
            for (codec in codecs) {
                if (decodeAll(random, codec, random.mutate(seed)) != null) ok++
                if (decodeAll(random, codec, random.nextBytes(random.nextInt(0, 64))) != null) ok++
            }
        }
        assertTrue(ok > 0, "some mutated inputs still decode")
    }

    @Test
    fun decompression() {
        val random = Random(0x7a1b)
        repeat(CASES / 5) {
            val compression = if (random.nextBoolean()) Compression.Zlib else Compression.Zstd
            val plain = when (random.nextInt(3)) {
                0 -> random.nextBytes(random.nextInt(0, 2000))
                1 -> ByteArray(random.nextInt(1, 100_000)) { 'a'.code.toByte() }
                else -> List(random.nextInt(1, 200)) { "msgtrans-$it " }.joinToString("").encodeToByteArray()
            }
            val packed = PayloadCompression.compress(plain, compression)
            assertContentEquals(plain, PayloadCompression.decompress(packed, compression), "round trip")
            val max = random.nextInt(0, 4000)
            for (input in listOf(random.mutate(packed), random.nextBytes(random.nextInt(0, 64)), packed)) {
                try {
                    val out = PayloadCompression.decompress(input, compression, max)
                    assertTrue(out.size <= max, "${out.size} bytes past the limit $max")
                } catch (e: CompressionException) {
                } catch (e: Throwable) {
                    fail("unexpected ${e::class.simpleName} from $compression: $e")
                }
            }
        }
    }
}
