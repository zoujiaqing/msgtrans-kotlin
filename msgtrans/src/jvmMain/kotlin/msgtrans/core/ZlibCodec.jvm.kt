package msgtrans.core

import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * zlib through java.util.zip: the zlib format (RFC 1950) at the default level, as the native codec
 * produces, with the same output bound and failure messages.
 */
internal actual object ZlibCodec {
    actual fun compress(input: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        try {
            deflater.setInput(input)
            deflater.finish()
            val output = ByteCollector()
            val chunk = ByteArray(workingChunkSize(input.size))
            while (!deflater.finished()) output.append(chunk, deflater.deflate(chunk))
            return output.toByteArray()
        } finally {
            deflater.end()
        }
    }

    actual fun decompress(input: ByteArray, maxOutputSize: Int): ByteArray {
        if (input.isEmpty()) throw CompressionException("empty zlib payload")
        val inflater = Inflater()
        try {
            inflater.setInput(input)
            val output = ByteCollector()
            while (!inflater.finished()) {
                // One extra byte makes an output exactly over the limit observable without
                // allocating an attacker-controlled buffer.
                val chunk = ByteArray((maxOutputSize - output.size).coerceAtMost(64 * 1024) + 1)
                val produced = try { inflater.inflate(chunk) } catch (e: DataFormatException) {
                    throw CompressionException("invalid zlib payload (${e.message})")
                }
                if (output.size + produced > maxOutputSize) {
                    throw CompressionException("decompressed payload exceeds $maxOutputSize bytes")
                }
                output.append(chunk, produced)
                if (produced == 0) {
                    if (inflater.needsDictionary()) throw CompressionException("invalid zlib payload (preset dictionary)")
                    if (inflater.needsInput()) throw CompressionException("truncated zlib payload")
                }
            }
            if (inflater.remaining > 0) throw CompressionException("trailing bytes after zlib stream")
            return output.toByteArray()
        } finally {
            inflater.end()
        }
    }
}
