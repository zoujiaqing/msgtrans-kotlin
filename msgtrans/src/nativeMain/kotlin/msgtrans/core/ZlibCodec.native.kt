@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package msgtrans.core

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.zlib.Z_BUF_ERROR
import platform.zlib.Z_DEFAULT_COMPRESSION
import platform.zlib.Z_FINISH
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.deflate
import platform.zlib.deflateEnd
import platform.zlib.deflateInit_
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit_
import platform.zlib.z_stream
import platform.zlib.zlibVersion

internal actual object ZlibCodec {
    /**
     * deflate streaming at the default level — the format and level compress2() uses. Streaming
     * rather than compress2 because compress2 sizes its buffers in zlib's uLong, which is 32-bit on
     * the 32-bit Android ABIs and 64-bit elsewhere, so it cannot be called from code shared by both;
     * the stream API counts in uInt everywhere.
     */
    actual fun compress(input: ByteArray): ByteArray = kotlinx.cinterop.memScoped {
        val stream = alloc<z_stream>()
        val version = zlibVersion()?.toKString() ?: throw CompressionException("zlib version unavailable")
        val initialized = deflateInit_(stream.ptr, Z_DEFAULT_COMPRESSION, version, sizeOf<z_stream>().toInt())
        if (initialized != Z_OK) throw CompressionException("zlib initialization failed with code $initialized")
        try {
            val output = ByteCollector()
            val chunkSize = workingChunkSize(input.size)
            input.usePinnedOrEmpty { source ->
                stream.next_in = source?.reinterpret()
                stream.avail_in = input.size.toUInt()
                do {
                    val chunk = ByteArray(chunkSize)
                    val result = chunk.usePinned { sink ->
                        stream.next_out = sink.addressOf(0).reinterpret()
                        stream.avail_out = chunk.size.toUInt()
                        deflate(stream.ptr, Z_FINISH)
                    }
                    if (result != Z_OK && result != Z_STREAM_END && result != Z_BUF_ERROR) {
                        throw CompressionException("zlib compression failed with code $result")
                    }
                    output.append(chunk, chunk.size - stream.avail_out.toInt())
                } while (result != Z_STREAM_END)
            }
            output.toByteArray()
        } finally {
            deflateEnd(stream.ptr)
        }
    }

    private inline fun <R> ByteArray.usePinnedOrEmpty(block: (kotlinx.cinterop.CPointer<kotlinx.cinterop.ByteVar>?) -> R): R =
        if (isEmpty()) block(null) else usePinned { block(it.addressOf(0)) }

    actual fun decompress(input: ByteArray, maxOutputSize: Int): ByteArray {
        if (input.isEmpty()) throw CompressionException("empty zlib payload")
        return kotlinx.cinterop.memScoped {
            val stream = alloc<z_stream>()
            val version = zlibVersion()?.toKString()
                ?: throw CompressionException("zlib version unavailable")
            val initialized = inflateInit_(stream.ptr, version, sizeOf<z_stream>().toInt())
            if (initialized != Z_OK) throw CompressionException("zlib initialization failed with code $initialized")
            try {
                val chunks = ArrayList<ByteArray>()
                var total = 0
                var decoded: ByteArray? = null
                input.usePinned { inputPinned ->
                    stream.next_in = inputPinned.addressOf(0).reinterpret()
                    stream.avail_in = input.size.toUInt()
                    while (decoded == null) {
                        // One extra byte makes an output exactly over the limit observable without
                        // allocating an attacker-controlled buffer.
                        val chunkSize = ((maxOutputSize - total).coerceAtMost(64 * 1024) + 1)
                            .coerceAtLeast(1)
                        val chunk = ByteArray(chunkSize)
                        val result = chunk.usePinned { outputPinned ->
                            stream.next_out = outputPinned.addressOf(0).reinterpret()
                            stream.avail_out = chunkSize.toUInt()
                            inflate(stream.ptr, Z_NO_FLUSH)
                        }
                        val produced = chunkSize - stream.avail_out.toInt()
                        if (total + produced > maxOutputSize) {
                            throw CompressionException("decompressed payload exceeds $maxOutputSize bytes")
                        }
                        if (produced > 0) {
                            chunks += chunk.copyOf(produced)
                            total += produced
                        }
                        when (result) {
                            Z_STREAM_END -> {
                                if (stream.avail_in != 0u) throw CompressionException("trailing bytes after zlib stream")
                                val output = ByteArray(total)
                                var offset = 0
                                for (part in chunks) {
                                    part.copyInto(output, offset)
                                    offset += part.size
                                }
                                decoded = output
                            }
                            Z_OK -> if (produced == 0 && stream.avail_in == 0u) {
                                throw CompressionException("truncated zlib payload")
                            }
                            else -> throw CompressionException("invalid zlib payload (code $result)")
                        }
                    }
                }
                decoded ?: throw CompressionException("zlib stream ended without output")
            } finally {
                inflateEnd(stream.ptr)
            }
        }
    }
}
