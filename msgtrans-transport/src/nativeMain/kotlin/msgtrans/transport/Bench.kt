package msgtrans.transport

import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import neton.io.net.runReactor
import kotlin.time.TimeSource

/**
 * Request/response echo server: onRequest returns the payload unchanged. Measures the full
 * msgtrans path (wire codec + actor connection) on top of the neton-io reactor.
 *
 * Usage: requestServer [host=0.0.0.0] [port=9000]
 */
fun requestServerMain(args: Array<String>) {
    val host = args.getOrNull(0) ?: "0.0.0.0"
    val port = args.getOrNull(1)?.toIntOrNull() ?: 9000
    println("msgtrans request-server listening on $host:$port")
    runReactor {
        val server = Transport.bind(this, host, port) { conn ->
            conn.onRequest { payload, _ -> payload }
        }
        server.acceptLoop()
    }
}

/**
 * Closed-loop request load generator: N connections, each doing request/response round-trips for
 * the duration; reports aggregate throughput.
 *
 * Usage: requestClient [host=127.0.0.1] [port=9000] [connections=50] [seconds=5] [payload=64]
 */
fun requestClientMain(args: Array<String>) {
    val host = args.getOrNull(0) ?: "127.0.0.1"
    val port = args.getOrNull(1)?.toIntOrNull() ?: 9000
    val connections = args.getOrNull(2)?.toIntOrNull() ?: 50
    val seconds = args.getOrNull(3)?.toIntOrNull() ?: 5
    val payloadSize = args.getOrNull(4)?.toIntOrNull() ?: 64

    val payload = ByteArray(payloadSize) { 'x'.code.toByte() }
    val deadlineMs = seconds * 1000L
    val clock = TimeSource.Monotonic.markNow()
    var totalRequests = 0L

    runReactor {
        val jobs = ArrayList<Job>(connections)
        repeat(connections) {
            jobs.add(launch {
                val conn = Transport.connect(this, host, port)
                var requests = 0L
                while (clock.elapsedNow().inWholeMilliseconds < deadlineMs) {
                    conn.request(payload)
                    requests++
                }
                conn.close()
                totalRequests += requests
            })
        }
        jobs.forEach { it.join() }
    }

    val elapsedSec = clock.elapsedNow().inWholeMilliseconds / 1000.0
    val qps = if (elapsedSec > 0) (totalRequests / elapsedSec).toLong() else 0
    println("connections=$connections duration=${elapsedSec}s payload=${payloadSize}B")
    println("total_requests=$totalRequests")
    println("throughput=$qps req/s")
}
