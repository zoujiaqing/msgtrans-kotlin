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
import kotlinx.cinterop.value
import platform.zlib.Z_DEFAULT_COMPRESSION
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.compress2
import platform.zlib.compressBound
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit_
import platform.zlib.z_stream
import platform.zlib.zlibVersion
import platform.zlib.uLongfVar

internal actual object ZlibCodec {
    actual fun compress(input: ByteArray): ByteArray {
        val bound = compressBound(input.size.convert())
        if (bound.toULong() > Int.MAX_VALUE.toULong()) throw CompressionException("zlib output is too large")
        val capacity = bound.toInt()
        val output = ByteArray(capacity)
        return kotlinx.cinterop.memScoped {
            val outputSize = alloc<uLongfVar>()
            outputSize.value = capacity.convert()
            val result = output.usePinned { outputPinned ->
                if (input.isEmpty()) {
                    compress2(outputPinned.addressOf(0).reinterpret(), outputSize.ptr, null, 0.convert(), Z_DEFAULT_COMPRESSION)
                } else {
                    input.usePinned { inputPinned ->
                        compress2(
                            outputPinned.addressOf(0).reinterpret(), outputSize.ptr,
                            inputPinned.addressOf(0).reinterpret(), input.size.convert(), Z_DEFAULT_COMPRESSION,
                        )
                    }
                }
            }
            if (result != Z_OK) throw CompressionException("zlib compression failed with code $result")
            output.copyOf(outputSize.value.toInt())
        }
    }

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
