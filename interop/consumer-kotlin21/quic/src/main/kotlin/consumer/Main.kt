package consumer

import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import neton.io.net.SocketAddress
import neton.io.net.runReactor
import neton.quic.Endpoint
import neton.quic.proto.Certificates
import neton.quic.proto.ClientConfig
import neton.quic.proto.PrivateKey
import neton.quic.proto.ServerConfig
import neton.quic.proto.TlsClientConfig
import neton.quic.proto.TlsServerConfig
import neton.quic.proto.withCrypto
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * com.netonstream:quic alone (with only the io and openssl it declares): one echo server and client on loopback, a
 * bidirectional stream per payload size, real TLS 1.3 through the JNI library.
 */
fun main() {
    var failures = 0
    runReactor {
        withTimeout(60.seconds) {
            val alpn = listOf("consumer".encodeToByteArray())
            val serverTls = TlsServerConfig(Certificates.pem(CERTIFICATE), PrivateKey.pem(KEY), alpn)
            val server = Endpoint.server(ServerConfig.withCrypto(serverTls), SocketAddress.ipv4(127, 0, 0, 1, 0))
            val serving = launch {
                while (true) {
                    val incoming = server.accept() ?: break
                    launch {
                        val connection = incoming.await()
                        while (true) {
                            val (send, recv) = try { connection.acceptBi() } catch (_: Exception) { break }
                            launch {
                                send.writeAll(recv.readToEnd())
                                send.finish()
                            }
                        }
                    }
                }
            }
            val clientTls = TlsClientConfig(Certificates.pem(CERTIFICATE), alpn)
            val client = Endpoint.client(SocketAddress.ipv4(0, 0, 0, 0, 0))
            val connection = client.connectWith(ClientConfig(clientTls), SocketAddress.ipv4(127, 0, 0, 1, server.localAddr().port), "localhost").await()
            for (size in SIZES) {
                val payload = Random(size).nextBytes(size)
                val (send, recv) = connection.openBi()
                send.writeAll(payload)
                send.finish()
                if (!recv.readToEnd().contentEquals(payload)) failures++
            }
            println("quic streams: ${if (failures == 0) "ok" else "$failures mismatches"}")
            connection.close()
            client.close()
            serving.cancel()
            server.close()
            clientTls.close()
            serverTls.close()
        }
    }
    finish("quic", failures)
}
