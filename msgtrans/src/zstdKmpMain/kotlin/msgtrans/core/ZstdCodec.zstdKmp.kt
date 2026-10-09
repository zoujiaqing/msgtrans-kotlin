package msgtrans.core

import com.squareup.zstd.ZSTD_e_end
import com.squareup.zstd.getErrorName
import com.squareup.zstd.zstdCompressor
import com.squareup.zstd.zstdDecompressor

/**
 * zstd through zstd-kmp, on every target it publishes a variant for.
 *
 * zstd-kmp 0.4.0 reports `inputBytesProcessed` minus `inputStart` (it hands zstd the input from `inputStart` and
 * subtracts the start again), so a call with a non-zero `inputStart` reports a wrong, even negative, count. Every
 * call here therefore passes its unread input at index 0 ([unread]). It also takes the address of element 0, which an
 * empty array does not have, so no input left is a one-byte array with an end of 0.
 */
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
                val pending = unread(input, inputOffset)
                remaining = compressor.compressStream2(
                    outputByteArray = chunk,
                    outputEnd = chunk.size,
                    outputStart = 0,
                    inputByteArray = pending,
                    inputEnd = if (pending === NO_INPUT) 0 else pending.size,
                    inputStart = 0,
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
                val pending = unread(input, inputOffset)
                remaining = decompressor.decompressStream(
                    outputByteArray = chunk,
                    outputEnd = chunk.size,
                    outputStart = 0,
                    inputByteArray = pending,
                    inputEnd = if (pending === NO_INPUT) 0 else pending.size,
                    inputStart = 0,
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

    private val NO_INPUT = ByteArray(1)

    /** [input] from [offset] on, at index 0: the array itself while nothing was consumed, else a copy, or [NO_INPUT]. */
    private fun unread(input: ByteArray, offset: Int): ByteArray = when {
        offset >= input.size -> NO_INPUT
        offset == 0 -> input
        else -> input.copyOfRange(offset, input.size)
    }

    private fun checkZstd(code: Long) {
        getErrorName(code)?.let { throw CompressionException("zstd: $it") }
    }
}
