@file:OptIn(ExperimentalForeignApi::class)

package msgtrans.transport

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import msgtrans.core.Compression
import neton.io.net.runReactor
import neton.quic.proto.Certificates
import neton.quic.proto.PrivateKey
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Interop with msgtrans-rust over QUIC, driven by interop/run-quic-interop.sh against interop/rust-peer. Does nothing
 * unless MSGTRANS_QUIC_INTEROP is set:
 * - `client`: run the checklist against the server at MSGTRANS_QUIC_INTEROP_ADDR (host:port), trusting
 *   MSGTRANS_QUIC_INTEROP_CA, with server name MSGTRANS_QUIC_INTEROP_SERVER_NAME (default localhost);
 * - `server`: serve MSGTRANS_QUIC_INTEROP_SESSIONS sessions on MSGTRANS_QUIC_INTEROP_ADDR with MSGTRANS_QUIC_INTEROP_CERT
 *   and MSGTRANS_QUIC_INTEROP_KEY, then return.
 * The scenario is the Rust peer's (see its main.rs). Lines for the run's summary start with "[interop]".
 */
class InteropTest {
    private fun env(name: String): String? = getenv(name)?.toKString()?.takeIf { it.isNotEmpty() }

    private fun readFile(path: String): ByteArray {
        val f = fopen(path, "rb") ?: error("cannot open $path")
        val out = ArrayList<Byte>()
        try {
            memScoped {
                val buf = allocArray<ByteVar>(4096)
                while (true) {
                    val n = fread(buf, 1u, 4096u, f).toInt()
                    if (n <= 0) break
                    out.addAll(buf.readBytes(n).toList())
                }
            }
        } finally {
            fclose(f)
        }
        return out.toByteArray()
    }

    private fun hostAndPort(address: String): Pair<String, Int> =
        address.substringBeforeLast(':').removeSurrounding("[", "]") to address.substringAfterLast(':').toInt()

    private fun pattern(size: Int) = ByteArray(size) { ((it * 31 + 7) % 251).toByte() }

    @Test
    fun interop() {
        when (env("MSGTRANS_QUIC_INTEROP")) {
            "client" -> client()
            "server" -> server()
            null -> println("SKIP InteropTest: MSGTRANS_QUIC_INTEROP is not set")
            else -> error("MSGTRANS_QUIC_INTEROP must be client or server")
        }
    }

    private fun client() = runReactor {
        val (host, port) = hostAndPort(env("MSGTRANS_QUIC_INTEROP_ADDR")!!)
        val transport = QuicClientTransport(host, port, Certificates.pem(readFile(env("MSGTRANS_QUIC_INTEROP_CA")!!)),
            serverName = env("MSGTRANS_QUIC_INTEROP_SERVER_NAME") ?: "localhost")
        val conn = try {
            Transport.connect(this, transport)
        } catch (e: Exception) {
            println("[interop] kotlin client: connect failed: ${e.message}")
            transport.close()
            throw e
        }
        println("[interop] kotlin client: connected to $host:$port")
        conn.onRequest { _, _ -> "kotlin-client-answer".encodeToByteArray() }
        val oneWay = Channel<Pair<Int, ByteArray>>(Channel.UNLIMITED)
        val events = conn.launch { conn.events().collect { oneWay.send(it.bizType to it.payload) } }

        var failures = 0
        fun check(ok: Boolean, what: String) {
            if (ok) println("[interop] ok: $what") else { println("[interop] FAIL: $what"); failures++ }
        }
        withTimeout(120.seconds) {
            for (size in listOf(0, 1, 1200, 70_000, 1_000_000, 3_000_000)) {
                val payload = pattern(size)
                check(conn.request(payload, bizType = 1).contentEquals(payload), "request echo, $size bytes")
            }
            val text = "msgtrans over QUIC ".repeat(5000).encodeToByteArray()
            for (compression in listOf(Compression.Zstd, Compression.Zlib)) {
                check(conn.request(text, bizType = 2, compression = compression).contentEquals(text), "compressed request echo, $compression")
            }
            val replies = List(64) { i -> async { conn.request("c$i".encodeToByteArray()).decodeToString() } }.awaitAll()
            check(replies == List(64) { "c$it" }, "64 concurrent requests")

            conn.send("one-way".encodeToByteArray(), bizType = 42)
            val echoed = withTimeoutOrNull(10.seconds) { oneWay.receive() }
            check(echoed != null && echoed.first == 42 && echoed.second.decodeToString() == "one-way", "one-way echoed as one-way")

            val asked = conn.request("ask".encodeToByteArray(), bizType = 200).decodeToString()
            check(asked == "client said: kotlin-client-answer", "server's reverse request answered by the client ($asked)")
        }
        events.cancel()
        conn.close()
        transport.close()
        if (failures == 0) println("[interop] kotlin client: all checks passed")
        assertEquals(0, failures, "interop checks failed")
    }

    private fun server() = runReactor {
        val (host, port) = hostAndPort(env("MSGTRANS_QUIC_INTEROP_ADDR")!!)
        val sessions = env("MSGTRANS_QUIC_INTEROP_SESSIONS")?.toInt() ?: 1
        val transport = QuicServerTransport(host, port,
            Certificates.pem(readFile(env("MSGTRANS_QUIC_INTEROP_CERT")!!)), PrivateKey.pem(readFile(env("MSGTRANS_QUIC_INTEROP_KEY")!!)))
        var closed = 0
        val done = CompletableDeferred<Unit>()
        val server = Transport.bind(this, transport) { conn ->
            println("[interop] kotlin server: session opened")
            conn.onRequest { payload, bizType ->
                if (bizType != 200) payload
                else ("client said: " + conn.request("server-asks".encodeToByteArray()).decodeToString()).encodeToByteArray()
            }
            conn.launch { conn.events().collect { conn.send(it.payload, it.bizType) } }
            conn.onClose {
                println("[interop] kotlin server: session closed")
                if (++closed >= sessions) done.complete(Unit)
            }
        }
        val accepting = launch { server.acceptLoop() }
        println("[interop] kotlin server: listening on $host:$port")
        withTimeout(300.seconds) { done.await() }
        println("[interop] kotlin server: $sessions session(s) served")
        accepting.cancelAndJoin()
        server.close()
        transport.close()
    }
}
