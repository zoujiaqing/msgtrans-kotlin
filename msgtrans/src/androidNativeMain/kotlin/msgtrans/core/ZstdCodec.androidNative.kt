@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package msgtrans.core

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import msgtrans.zstd.msgtrans_zstd_compress_end
import msgtrans.zstd.msgtrans_zstd_create_cctx
import msgtrans.zstd.msgtrans_zstd_create_dctx
import msgtrans.zstd.msgtrans_zstd_decompress
import msgtrans.zstd.msgtrans_zstd_free_cctx
import msgtrans.zstd.msgtrans_zstd_free_dctx
import msgtrans.zstd.msgtrans_zstd_set_level

/**
 * zstd through the vendored upstream library (src/nativeInterop/zstd). The loops are the zstd-kmp
 * actual's, over the same `ZSTD_compressStream2` / `ZSTD_decompressStream` calls (reached through
 * the fixed-width shims in zstd.def): the same compression level, chunk sizes, output bound and
 * failure messages.
 */
internal actual object ZstdCodec {

    actual fun compress(input: ByteArray): ByteArray {
        val cctx = msgtrans_zstd_create_cctx() ?: throw CompressionException("zstd: cannot allocate a compression context")
        try {
            msgtrans_zstd_set_level(cctx, ZSTD_LEVEL)?.let { throw CompressionException("zstd: ${it.toKString()}") }
            val output = ByteCollector()
            val chunkSize = workingChunkSize(input.size)
            memScoped {
                val inputPos = alloc<IntVar>().apply { value = 0 }
                val outputPos = alloc<IntVar>()
                val remaining = alloc<LongVar>()
                input.withAddress { src ->
                    do {
                        val chunk = ByteArray(chunkSize)
                        val consumedBefore = inputPos.value
                        outputPos.value = 0
                        chunk.usePinned { dst ->
                            msgtrans_zstd_compress_end(
                                cctx, dst.addressOf(0), chunk.size, outputPos.ptr,
                                src, input.size, inputPos.ptr, remaining.ptr,
                            )?.let { throw CompressionException("zstd: ${it.toKString()}") }
                        }
                        val produced = outputPos.value
                        output.append(chunk, produced)
                        if (remaining.value != 0L && inputPos.value == consumedBefore && produced == 0) {
                            throw CompressionException("zstd compressor made no progress")
                        }
                    } while (remaining.value != 0L)
                    if (inputPos.value != input.size) throw CompressionException("zstd compressor did not consume its input")
                }
            }
            return output.toByteArray()
        } finally {
            msgtrans_zstd_free_cctx(cctx)
        }
    }

    actual fun decompress(input: ByteArray, maxOutputSize: Int): ByteArray {
        if (input.isEmpty()) throw CompressionException("empty zstd payload")
        val dctx = msgtrans_zstd_create_dctx() ?: throw CompressionException("zstd: cannot allocate a decompression context")
        try {
            val output = ByteCollector()
            val preferredChunkSize = workingChunkSize(input.size)
            memScoped {
                val inputPos = alloc<IntVar>().apply { value = 0 }
                val outputPos = alloc<IntVar>()
                val remaining = alloc<LongVar>()
                input.withAddress { src ->
                    do {
                        val room = (maxOutputSize - output.size).coerceAtMost(preferredChunkSize) + 1
                        val chunk = ByteArray(room)
                        val consumedBefore = inputPos.value
                        outputPos.value = 0
                        chunk.usePinned { dst ->
                            msgtrans_zstd_decompress(
                                dctx, dst.addressOf(0), chunk.size, outputPos.ptr,
                                src, input.size, inputPos.ptr, remaining.ptr,
                            )?.let { throw CompressionException("zstd: ${it.toKString()}") }
                        }
                        val produced = outputPos.value
                        if (output.size + produced > maxOutputSize) {
                            throw CompressionException("decompressed payload exceeds $maxOutputSize bytes")
                        }
                        output.append(chunk, produced)
                        if (remaining.value != 0L && inputPos.value == consumedBefore && produced == 0) {
                            throw CompressionException("truncated zstd payload")
                        }
                    } while (remaining.value != 0L)
                    if (inputPos.value != input.size) throw CompressionException("trailing bytes after zstd frame")
                }
            }
            return output.toByteArray()
        } finally {
            msgtrans_zstd_free_dctx(dctx)
        }
    }

    /** An empty array has no element to pin; zstd accepts a null source of size 0. */
    private inline fun <R> ByteArray.withAddress(block: (COpaquePointer?) -> R): R =
        if (isEmpty()) block(null) else usePinned { block(it.addressOf(0)) }
}
