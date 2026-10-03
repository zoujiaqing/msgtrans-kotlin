package msgtrans.core

import com.squareup.zstd.ZSTD_e_end
import com.squareup.zstd.getErrorName
import com.squareup.zstd.zstdCompressor
import com.squareup.zstd.zstdDecompressor

/** zstd through zstd-kmp, on every target it publishes a variant for. */
internal actual object ZstdCodec {
    private const val ZSTD_C_COMPRESSION_LEVEL = 100

    actual fun compress(input: ByteArray): ByteArray {
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

    actual fun decompress(input: ByteArray, maxOutputSize: Int): ByteArray {
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

    private fun checkZstd(code: Long) {
        getErrorName(code)?.let { throw CompressionException("zstd: $it") }
    }
}
