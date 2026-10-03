package msgtrans.core

/** Payload codecs used by the transport before send and immediately after frame decode. */
object PayloadCompression {
    const val DEFAULT_MAX_DECOMPRESSED_SIZE: Int = 16 * 1024 * 1024

    fun compress(payload: ByteArray, compression: Compression): ByteArray = when (compression) {
        Compression.None -> payload
        Compression.Zstd -> ZstdCodec.compress(payload)
        Compression.Zlib -> ZlibCodec.compress(payload)
    }

    fun decompress(
        payload: ByteArray,
        compression: Compression,
        maxOutputSize: Int = DEFAULT_MAX_DECOMPRESSED_SIZE,
    ): ByteArray {
        require(maxOutputSize >= 0) { "maxOutputSize must not be negative" }
        return when (compression) {
            Compression.None -> payload
            Compression.Zstd -> ZstdCodec.decompress(payload, maxOutputSize)
            Compression.Zlib -> ZlibCodec.decompress(payload, maxOutputSize)
        }
    }

    /** Return a new wire packet whose body and compression marker are self-consistent. */
    fun compress(packet: Packet, compression: Compression): Packet {
        if (packet.compression != Compression.None) {
            throw CompressionException("packet is already marked ${packet.compression}")
        }
        if (compression == Compression.None) return packet
        return packet.withPayload(compress(packet.payload, compression), compression)
    }

    /** Normalize an inbound packet to plaintext so no application handler sees compressed bytes. */
    fun decompress(packet: Packet, maxOutputSize: Int = DEFAULT_MAX_DECOMPRESSED_SIZE): Packet {
        if (packet.compression == Compression.None) return packet
        return packet.withPayload(decompress(packet.payload, packet.compression, maxOutputSize), Compression.None)
    }

}

class CompressionException(message: String) : ProtocolException(message)

internal expect object ZlibCodec {
    fun compress(input: ByteArray): ByteArray
    fun decompress(input: ByteArray, maxOutputSize: Int): ByteArray
}

/**
 * zstd behind one seam, like zlib: zstd-kmp provides it on Apple and Linux, the vendored upstream
 * library on Android Native (zstd-kmp publishes no variant there). Both sides stream through the
 * same `ZSTD_compressStream2` / `ZSTD_decompressStream` calls with [ZSTD_LEVEL] and the same output
 * bound, so the bytes on the wire and the failure messages do not depend on the target.
 */
internal expect object ZstdCodec {
    fun compress(input: ByteArray): ByteArray
    fun decompress(input: ByteArray, maxOutputSize: Int): ByteArray
}

internal const val ZSTD_LEVEL = 3
private const val MAX_CHUNK_SIZE = 64 * 1024
private const val MIN_CHUNK_SIZE = 1024

internal fun workingChunkSize(inputSize: Int): Int =
    inputSize.coerceAtLeast(MIN_CHUNK_SIZE).coerceAtMost(MAX_CHUNK_SIZE)

internal class ByteCollector {
    private val chunks = ArrayList<ByteArray>()
    var size: Int = 0
        private set

    fun append(source: ByteArray, count: Int) {
        if (count <= 0) return
        chunks += source.copyOf(count)
        size += count
    }

    fun toByteArray(): ByteArray {
        val result = ByteArray(size)
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(result, offset)
            offset += chunk.size
        }
        return result
    }
}
