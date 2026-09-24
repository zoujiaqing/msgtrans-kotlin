@file:OptIn(ExperimentalForeignApi::class)

package msgtrans.bench

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.staticCFunction
import neton.io.net.dumpReactorStats
import neton.io.net.runReactor
import platform.posix.SIGINT
import platform.posix.SIGPIPE
import platform.posix.SIGTERM
import platform.posix.SIG_IGN
import platform.posix._exit
import platform.posix.signal
import kotlin.system.exitProcess

/**
 * neton-io does not yet suppress SIGPIPE (no SO_NOSIGPIPE / MSG_NOSIGNAL), so a write to a peer
 * that has gone away kills the process instead of surfacing an error. The harness must observe
 * such failures, not die from them; ignoring SIGPIPE turns them into EPIPE from send(). The
 * socket-level fix belongs in neton-io as its own change.
 */
private fun ignoreSigpipe() {
    signal(SIGPIPE, SIG_IGN)
}

/**
 * Benchmark entry points. One server binary and one client binary, mode-selected, so all three
 * layers run through byte-identical harness code.
 *
 *   benchServer mode=raw|framed|rpc [host=0.0.0.0] [port=9000]
 *   benchClient mode=raw|framed|rpc [host=127.0.0.1] [port=9000] [conns=50] [payload=64]
 *               [warmup=2] [duration=5] [timeout=10] [connect_timeout=30] [exit_timeout=10]
 *               [out=result.json] [label=..]
 *
 * The client prints one JSON document (and writes it to `out` if given) and exits 0 on a clean
 * run, 1 if any connection failed validation / hit EOF / errored, 2 if the watchdog fired.
 * See bench/run.sh for the repeatable driver that fixes build, driver and parameters.
 */
/**
 * The runner stops the server with SIGTERM. Print the reactor counters (NETON_IO_STATS=1) before
 * dying so the server side of a run is observable too. The handler runs on the reactor thread
 * while it is parked in the poller, so the counters are quiescent; it then exits immediately.
 */
private fun dumpStatsOnTerm() {
    val handler = staticCFunction<Int, Unit> { _ ->
        dumpReactorStats()
        _exit(0)
    }
    signal(SIGTERM, handler)
    signal(SIGINT, handler)
}

fun benchServerMain(args: Array<String>) {
    ignoreSigpipe()
    dumpStatsOnTerm()
    val kv = args.associate { a -> a.substringBefore('=') to a.substringAfter('=', "") }
    val mode = kv["mode"] ?: run {
        println("usage: benchServer mode=raw|framed|rpc [host=0.0.0.0] [port=9000]")
        exitProcess(64)
    }
    val host = kv["host"] ?: "0.0.0.0"
    val port = kv["port"]?.toIntOrNull() ?: 9000
    runReactor {
        when (mode) {
            "raw" -> RawLayer.serve(this, host, port)
            "framed" -> FramedLayer.serve(this, host, port)
            "rpc" -> RpcLayer.serve(this, host, port)
            else -> {
                println("unknown mode '$mode' (raw|framed|rpc)")
                exitProcess(64)
            }
        }
    }
}

fun benchClientMain(args: Array<String>) {
    ignoreSigpipe()
    val cfg = try {
        BenchConfig.parse(args)
    } catch (t: Throwable) {
        println("error: ${t.message}\nusage: ${BenchConfig.USAGE}")
        exitProcess(64)
    }
    val layer = try {
        layerFor(cfg.mode)
    } catch (t: Throwable) {
        println("error: ${t.message}\nusage: ${BenchConfig.USAGE}")
        exitProcess(64)
    }
    exitProcess(runBenchClient(cfg, layer))
}
