package msgtrans.core

import com.squareup.zstd.ZSTD_e_end
import com.squareup.zstd.getErrorName
import com.squareup.zstd.zstdCompressor
import com.squareup.zstd.zstdDecompressor

/** Payload codecs used by the transport before send and immediately after frame decode. */
object PayloadCompression {
    const val DEFAULT_MAX_DECOMPRESSED_SIZE: Int = 16 * 1024 * 1024
    private const val MAX_CHUNK_SIZE = 64 * 1024
    private const val MIN_CHUNK_SIZE = 1024
    private const val ZSTD_C_COMPRESSION_LEVEL = 100
    private const val ZSTD_LEVEL = 3

    fun compress(payload: ByteArray, compression: Compression): ByteArray = when (compression) {
        Compression.None -> payload
        Compression.Zstd -> compressZstd(payload)
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
            Compression.Zstd -> decompressZstd(payload, maxOutputSize)
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

    private fun compressZstd(input: ByteArray): ByteArray {
        val output = ByteCollector()
        val chunkSize = workingChunkSize(input.size)
        zstdCompressor().use { compressor ->
            checkZstd(compressor.setParameter(ZSTD_C_COMPRESSION_LEVEL, ZSTD_LEVEL))
            var inputOffset = 0
            var remaining: Long
            do {
                val chunk = ByteArray(chunkSize)
                remaining = compressor.compressStream2(
                    outputByteArray = chunk,
                    outputEnd = chunk.size,
                    outputStart = 0,
                    inputByteArray = input,
                    inputEnd = input.size,
                    inputStart = inputOffset,
                    mode = ZSTD_e_end,
                )
                checkZstd(remaining)
                inputOffset += compressor.inputBytesProcessed
                output.append(chunk, compressor.outputBytesProcessed)
                if (remaining != 0L && compressor.inputBytesProcessed == 0 && compressor.outputBytesProcessed == 0) {
                    throw CompressionException("zstd compressor made no progress")
                }
            } while (remaining != 0L)
            if (inputOffset != input.size) throw CompressionException("zstd compressor did not consume its input")
        }
        return output.toByteArray()
    }

    private fun decompressZstd(input: ByteArray, maxOutputSize: Int): ByteArray {
        if (input.isEmpty()) throw CompressionException("empty zstd payload")
        val output = ByteCollector()
        val preferredChunkSize = workingChunkSize(input.size)
        zstdDecompressor().use { decompressor ->
            var inputOffset = 0
            var remaining: Long
            do {
                val room = (maxOutputSize - output.size).coerceAtMost(preferredChunkSize) + 1
                val chunk = ByteArray(room)
                remaining = decompressor.decompressStream(
                    outputByteArray = chunk,
                    outputEnd = chunk.size,
                    outputStart = 0,
                    inputByteArray = input,
                    inputEnd = input.size,
                    inputStart = inputOffset,
                )
                checkZstd(remaining)
                inputOffset += decompressor.inputBytesProcessed
                val produced = decompressor.outputBytesProcessed
                if (output.size + produced > maxOutputSize) {
                    throw CompressionException("decompressed payload exceeds $maxOutputSize bytes")
                }
                output.append(chunk, produced)
                if (remaining != 0L && decompressor.inputBytesProcessed == 0 && produced == 0) {
                    throw CompressionException("truncated zstd payload")
                }
            } while (remaining != 0L)
            if (inputOffset != input.size) throw CompressionException("trailing bytes after zstd frame")
        }
        return output.toByteArray()
    }

    private fun workingChunkSize(inputSize: Int): Int =
        inputSize.coerceAtLeast(MIN_CHUNK_SIZE).coerceAtMost(MAX_CHUNK_SIZE)

    private fun checkZstd(code: Long) {
        getErrorName(code)?.let { throw CompressionException("zstd: $it") }
    }
}

class CompressionException(message: String) : ProtocolException(message)

internal expect object ZlibCodec {
    fun compress(input: ByteArray): ByteArray
    fun decompress(input: ByteArray, maxOutputSize: Int): ByteArray
}

private class ByteCollector {
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
