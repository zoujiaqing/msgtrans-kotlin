@file:OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class, DelicateCoroutinesApi::class)

package msgtrans.transport.bench

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import neton.io.net.runReactor
import platform.posix.getenv
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.system.exitProcess
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Shared closed-loop client harness for the three comparable layers (raw echo, framed echo,
 * full msgtrans RPC). The layer supplies how to open a connection and do one validated round
 * trip; the harness owns everything that must be identical across layers so the numbers are
 * comparable: connection count, payload, one outstanding request per connection, the
 * connect / warmup / measure / exit phases, validation accounting, latency histogram, timeouts
 * and the JSON result.
 *
 * Timing: the measure window starts only after every connection is open and warmed up, so
 * connect cost never leaks into throughput (the first draft of the framed client started its
 * clock before connecting).
 *
 * Timeouts: the harness never cancels a coroutine parked in I/O (cancelling an in-flight
 * io_uring op is a known-unsafe path in neton-io). Instead a watchdog thread outside the
 * reactor checks that each phase finishes within its budget; on overrun it writes a partial
 * result with status "timeout" and exits the process with code 2. A stuck connection is
 * therefore reported as a failed run, not silently excluded.
 */

/** One layer under test. */
interface BenchLayer {
    val name: String

    /** Open one connection. Throw on failure. */
    suspend fun open(scope: CoroutineScope, cfg: BenchConfig): BenchConn
}

/** One open connection of a layer. */
interface BenchConn {
    /**
     * One request/response with validation. Returns null on success, otherwise a failure kind
     * prefix ("eof", "mismatch", "error") optionally followed by ": detail". After a non-null
     * result the connection is considered dead and is closed by the harness.
     */
    suspend fun roundTrip(): String?

    suspend fun close()
}

class BenchConfig(
    val mode: String,
    val host: String,
    val port: Int,
    val connections: Int,
    val payloadSize: Int,
    val warmup: Duration,
    val duration: Duration,
    val connectTimeout: Duration,
    /** Slack added to warmup and measure budgets before the watchdog declares a stall. */
    val requestTimeout: Duration,
    val exitTimeout: Duration,
    val out: String?,
    val label: String,
) {
    val payload: ByteArray = ByteArray(payloadSize) { (it % 251).toByte() }

    companion object {
        const val BIZ_TYPE = 7

        fun parse(args: Array<String>): BenchConfig {
            val kv = HashMap<String, String>()
            for (a in args) {
                val i = a.indexOf('=')
                require(i > 0) { "expected key=value, got '$a'" }
                kv[a.substring(0, i)] = a.substring(i + 1)
            }
            fun str(k: String, d: String) = kv[k] ?: d
            fun int(k: String, d: Int) = kv[k]?.toInt() ?: d
            fun secs(k: String, d: Double) = ((kv[k]?.toDouble() ?: d) * 1000).toLong().milliseconds
            return BenchConfig(
                mode = str("mode", "framed"),
                host = str("host", "127.0.0.1"),
                port = int("port", 9000),
                connections = int("conns", 50),
                payloadSize = int("payload", 64),
                warmup = secs("warmup", 2.0),
                duration = secs("duration", 5.0),
                connectTimeout = secs("connect_timeout", 30.0),
                requestTimeout = secs("timeout", 10.0),
                exitTimeout = secs("exit_timeout", 10.0),
                out = kv["out"],
                label = str("label", ""),
            )
        }

        const val USAGE = "benchClient mode=raw|framed|rpc [host=127.0.0.1] [port=9000] [conns=50] " +
            "[payload=64] [warmup=2] [duration=5] [timeout=10] [connect_timeout=30] [exit_timeout=10] " +
            "[out=result.json] [label=..]"
    }
}

/** Log-linear latency histogram (32 sub-buckets per power of two, ~3% value resolution). */
class LatencyHistogram {
    private val counts = LongArray(64 * 32)
    var count = 0L; private set
    var sum = 0L; private set
    var max = 0L; private set
    var min = Long.MAX_VALUE; private set

    fun record(ns: Long) {
        val v = if (ns < 1) 1L else ns
        count++; sum += v
        if (v > max) max = v
        if (v < min) min = v
        counts[index(v)]++
    }

    private fun index(v: Long): Int {
        if (v < 32) return v.toInt()
        val e = 63 - v.countLeadingZeroBits()
        val sub = ((v ushr (e - 5)) and 31L).toInt()
        return e * 32 + sub
    }

    /** Upper bound of bucket [i] (conservative percentile value). */
    private fun upperBound(i: Int): Long {
        if (i < 32) return i.toLong()
        val e = i / 32
        val sub = (i % 32).toLong()
        val lower = (1L shl e) or (sub shl (e - 5))
        return lower + (1L shl (e - 5)) - 1
    }

    fun percentile(p: Double): Long {
        if (count == 0L) return 0
        val target = kotlin.math.ceil(p / 100.0 * count).toLong().coerceAtLeast(1)
        var cum = 0L
        for (i in counts.indices) {
            cum += counts[i]
            if (cum >= target) return minOf(upperBound(i), max)
        }
        return max
    }

    val mean: Long get() = if (count == 0L) 0 else sum / count
}

/** Phase markers shared with the watchdog (written on the reactor thread, read elsewhere). */
private object Phase {
    const val CONNECT = 0
    const val WARMUP = 1
    const val MEASURE = 2
    const val EXIT = 3
    const val DONE = 4
    val names = arrayOf("connect", "warmup", "measure", "exit", "done")
}

private class RunState(val cfg: BenchConfig) {
    val start = TimeSource.Monotonic.markNow()
    val phase = AtomicInt(Phase.CONNECT)
    val phaseStartNs = AtomicLong(0)
    val connected = AtomicInt(0)
    val finished = AtomicInt(0)
    val measuredTotal = AtomicLong(0)
    /** Measured requests per connection; written on the reactor thread, read racily by the watchdog. */
    val perConn = LongArray(cfg.connections)
    val startedAtUtc: Long = platform.posix.time(null).toLong()

    // Reactor-thread-only state.
    val hist = LatencyHistogram()
    var warmupRequests = 0L
    var connectFailures = 0
    val errorKinds = HashMap<String, Int>()
    val errorSamples = ArrayList<String>()
    var connectDoneAt: Duration = Duration.ZERO   // all connections open
    var warmupEndAt: Duration = Duration.ZERO
    var measureEndAt: Duration = Duration.ZERO     // deadline
    var lastMeasuredCompletionAt: Duration = Duration.ZERO
    var allClosedAt: Duration = Duration.ZERO

    fun now(): Duration = start.elapsedNow()

    fun enter(p: Int) {
        phase.store(p)
        phaseStartNs.store(now().inWholeNanoseconds)
    }

    /** [detail] starts with a kind prefix ("eof", "mismatch", "error", "connect", "close"). */
    fun recordError(connIdx: Int, detail: String) {
        val k = detail.substringBefore(':').trim()
        errorKinds[k] = (errorKinds[k] ?: 0) + 1
        if (errorSamples.size < 10) errorSamples.add("conn#$connIdx $detail")
    }
}

fun runBenchClient(cfg: BenchConfig, layer: BenchLayer): Int {
    require(cfg.connections > 0) { "conns must be > 0" }
    val st = RunState(cfg)
    st.enter(Phase.CONNECT)
    startWatchdog(st)

    var fatal: Throwable? = null
    try {
        runReactor {
            val gate = CompletableDeferred<Unit>()
            var attempted = 0
            val jobs = ArrayList<Job>(cfg.connections)
            repeat(cfg.connections) { idx ->
                jobs.add(launch { runConnection(this, st, layer, idx, gate) { attempted++; if (attempted == cfg.connections) openGate(st, gate) } })
            }
            jobs.forEach { it.join() }
            st.allClosedAt = st.now()
        }
    } catch (t: Throwable) {
        fatal = t
    }
    st.enter(Phase.DONE)

    val status = when {
        fatal != null -> "error"
        st.connectFailures > 0 -> "connect_failed"
        st.errorKinds.isNotEmpty() -> "errors"
        else -> "ok"
    }
    val json = resultJson(st, status, fatal?.toString())
    emitResult(cfg, json)
    return if (status == "ok") 0 else 1
}

private fun openGate(st: RunState, gate: CompletableDeferred<Unit>) {
    if (st.connectFailures > 0) {
        gate.completeExceptionally(IllegalStateException("${st.connectFailures} connection(s) failed to open"))
        return
    }
    st.connectDoneAt = st.now()
    st.warmupEndAt = st.connectDoneAt + st.cfg.warmup
    st.measureEndAt = st.warmupEndAt + st.cfg.duration
    st.enter(Phase.WARMUP)
    gate.complete(Unit)
}

private suspend fun runConnection(
    scope: CoroutineScope,
    st: RunState,
    layer: BenchLayer,
    idx: Int,
    gate: CompletableDeferred<Unit>,
    onAttempted: () -> Unit,
) {
    val cfg = st.cfg
    val conn: BenchConn? = try {
        layer.open(scope, cfg)
    } catch (t: Throwable) {
        st.connectFailures++
        st.recordError(idx, "connect: $t")
        null
    }
    if (conn != null) st.connected.addAndFetch(1)
    onAttempted()
    if (conn == null) return
    try {
        gate.await()
    } catch (t: Throwable) {
        conn.close()
        return
    }

    var measured = 0L
    var warm = 0L
    var enteredMeasure = false
    try {
        while (true) {
            val startedAt = st.now()
            if (startedAt >= st.measureEndAt) break
            val measuring = startedAt >= st.warmupEndAt
            if (measuring && !enteredMeasure) {
                enteredMeasure = true
                if (st.phase.load() == Phase.WARMUP) st.enter(Phase.MEASURE)
            }
            val t0 = TimeSource.Monotonic.markNow()
            val err = conn.roundTrip()
            if (err != null) {
                st.recordError(idx, err)
                break
            }
            if (measuring) {
                st.hist.record(t0.elapsedNow().inWholeNanoseconds)
                measured++
                st.perConn[idx] = measured
                val done = st.now()
                if (done > st.lastMeasuredCompletionAt) st.lastMeasuredCompletionAt = done
            } else {
                warm++
            }
        }
    } catch (t: Throwable) {
        st.recordError(idx, "error: $t")
    } finally {
        // Exit phase begins once the measure deadline has passed; an early per-connection failure
        // must not shrink the watchdog budget for the connections still measuring.
        if (st.phase.load() == Phase.MEASURE && st.now() >= st.measureEndAt) st.enter(Phase.EXIT)
        st.warmupRequests += warm
        st.measuredTotal.addAndFetch(measured)
        try { conn.close() } catch (t: Throwable) { st.recordError(idx, "close: $t") }
        st.finished.addAndFetch(1)
    }
}

/** Runs off the reactor thread; only touches atomics and, on overrun, exits the process. */
private fun startWatchdog(st: RunState) {
    val cfg = st.cfg
    GlobalScope.launch(Dispatchers.Default) {
        while (true) {
            delay(100)
            val p = st.phase.load()
            if (p == Phase.DONE) return@launch
            val inPhase = (st.now().inWholeNanoseconds - st.phaseStartNs.load()).coerceAtLeast(0)
            val budget = when (p) {
                Phase.CONNECT -> cfg.connectTimeout
                Phase.WARMUP -> cfg.warmup + cfg.requestTimeout
                Phase.MEASURE -> cfg.duration + cfg.requestTimeout
                else -> cfg.exitTimeout
            }
            if (inPhase > budget.inWholeNanoseconds) {
                val prog = progressStats(st)
                val msg = "watchdog: phase '${Phase.names[p]}' exceeded ${budget} " +
                    "(connected=${st.connected.load()}/${cfg.connections} finished=${st.finished.load()} " +
                    "measured=${st.measuredTotal.load()} per-conn min=${prog.min} max=${prog.max} zero=${prog.zero})"
                System_err(msg)
                emitResult(cfg, resultJson(st, "timeout", msg, partial = true))
                exitProcess(2)
            }
        }
    }
}

@Suppress("FunctionName")
private fun System_err(msg: String) {
    platform.posix.fprintf(platform.posix.stderr, "%s\n", msg)
    platform.posix.fflush(platform.posix.stderr)
}

private fun emitResult(cfg: BenchConfig, json: String) {
    println(json)
    cfg.out?.let { path -> writeFile(path, json) }
}

private fun writeFile(path: String, content: String) {
    val f = platform.posix.fopen(path, "w") ?: run { System_err("cannot open $path"); return }
    platform.posix.fputs(content, f)
    platform.posix.fclose(f)
}

/** Process CPU time and peak RSS. Collected per platform (getrusage on Apple, /proc on Linux). */
internal class Rusage(val userSec: Double, val sysSec: Double, val maxRssBytes: Long)

/** Whole-process resource usage. Apple: getrusage; Linux: /proc/self (getrusage is not in the KN binding). */
internal expect fun selfRusage(): Rusage

private class ProgressStats(val min: Long, val max: Long, val zero: Int)

/** Distribution of measured requests across connections: tells a global slowdown from stuck connections. */
private fun progressStats(st: RunState): ProgressStats {
    var min = Long.MAX_VALUE; var max = 0L; var zero = 0
    for (v in st.perConn) { if (v < min) min = v; if (v > max) max = v; if (v == 0L) zero++ }
    return ProgressStats(if (min == Long.MAX_VALUE) 0 else min, max, zero)
}

private fun jsonStr(s: String?): String =
    if (s == null) "null" else "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

private fun ms(d: Duration): String = (d.inWholeMicroseconds / 1000.0).toString()

/**
 * Result document. `partial=true` is used by the watchdog (reactor-thread fields may be mid-update;
 * they are read best-effort for diagnostics only).
 */
private fun resultJson(st: RunState, status: String, message: String?, partial: Boolean = false): String {
    val cfg = st.cfg
    val ru = selfRusage()
    val total = st.now()
    val measureWindow =
        if (st.lastMeasuredCompletionAt > st.warmupEndAt) st.lastMeasuredCompletionAt - st.warmupEndAt else Duration.ZERO
    val measured = if (partial) st.measuredTotal.load() else st.hist.count
    val throughput = if (measureWindow > Duration.ZERO) measured / (measureWindow.inWholeMicroseconds / 1e6) else 0.0
    val wallSec = total.inWholeMicroseconds / 1e6
    val h = st.hist
    val sb = StringBuilder(2048)
    sb.append("{\n")
    sb.append("  \"status\": ").append(jsonStr(status)).append(",\n")
    sb.append("  \"message\": ").append(jsonStr(message)).append(",\n")
    sb.append("  \"config\": {")
    sb.append("\"mode\": ").append(jsonStr(cfg.mode))
    sb.append(", \"label\": ").append(jsonStr(cfg.label))
    sb.append(", \"host\": ").append(jsonStr(cfg.host)).append(", \"port\": ").append(cfg.port)
    sb.append(", \"connections\": ").append(cfg.connections)
    sb.append(", \"payload_bytes\": ").append(cfg.payloadSize)
    sb.append(", \"inflight_per_connection\": 1")
    sb.append(", \"warmup_s\": ").append(cfg.warmup.inWholeMilliseconds / 1000.0)
    sb.append(", \"duration_s\": ").append(cfg.duration.inWholeMilliseconds / 1000.0)
    sb.append(", \"timeout_s\": ").append(cfg.requestTimeout.inWholeMilliseconds / 1000.0)
    sb.append(", \"connect_timeout_s\": ").append(cfg.connectTimeout.inWholeMilliseconds / 1000.0)
    sb.append(", \"exit_timeout_s\": ").append(cfg.exitTimeout.inWholeMilliseconds / 1000.0)
    sb.append("},\n")
    sb.append("  \"env\": {")
    sb.append("\"NETON_IO_DRIVER\": ").append(jsonStr(getenv("NETON_IO_DRIVER")?.toKString()))
    sb.append(", \"NETON_IO_URING_DEPTH\": ").append(jsonStr(getenv("NETON_IO_URING_DEPTH")?.toKString()))
    sb.append("},\n")
    sb.append("  \"phases_ms\": {")
    sb.append("\"connect\": ").append(ms(st.connectDoneAt))
    sb.append(", \"warmup\": ").append(ms(if (st.warmupEndAt > st.connectDoneAt) st.warmupEndAt - st.connectDoneAt else Duration.ZERO))
    sb.append(", \"measure_window\": ").append(ms(measureWindow))
    sb.append(", \"measure_overrun\": ").append(ms(if (st.lastMeasuredCompletionAt > st.measureEndAt) st.lastMeasuredCompletionAt - st.measureEndAt else Duration.ZERO))
    sb.append(", \"exit\": ").append(ms(if (st.allClosedAt > st.measureEndAt) st.allClosedAt - st.measureEndAt else Duration.ZERO))
    sb.append(", \"total\": ").append(ms(total))
    sb.append("},\n")
    val prog = progressStats(st)
    sb.append("  \"progress\": {")
    sb.append("\"connected\": ").append(st.connected.load())
    sb.append(", \"finished\": ").append(st.finished.load())
    sb.append(", \"phase\": ").append(jsonStr(Phase.names[st.phase.load()]))
    sb.append(", \"per_connection_measured\": {\"min\": ").append(prog.min).append(", \"max\": ").append(prog.max)
    sb.append(", \"zero\": ").append(prog.zero).append("}")
    sb.append(", \"started_at_utc\": ").append(st.startedAtUtc)
    sb.append("},\n")
    sb.append("  \"results\": {")
    sb.append("\"warmup_requests\": ").append(st.warmupRequests)
    sb.append(", \"measured_requests\": ").append(measured)
    sb.append(", \"throughput_req_s\": ").append((throughput * 10).toLong() / 10.0)
    sb.append(", \"latency_us\": {")
    fun us(ns: Long) = ns / 1000.0
    sb.append("\"mean\": ").append(us(h.mean))
    sb.append(", \"min\": ").append(us(if (h.count == 0L) 0 else h.min))
    sb.append(", \"p50\": ").append(us(h.percentile(50.0)))
    sb.append(", \"p90\": ").append(us(h.percentile(90.0)))
    sb.append(", \"p99\": ").append(us(h.percentile(99.0)))
    sb.append(", \"p999\": ").append(us(h.percentile(99.9)))
    sb.append(", \"max\": ").append(us(h.max))
    sb.append(", \"resolution\": \"log-linear, 32 buckets per octave (~3%)\"")
    sb.append("}")
    sb.append(", \"errors\": {")
    var first = true
    for ((k, v) in st.errorKinds) {
        if (!first) sb.append(", ")
        first = false
        sb.append(jsonStr(k)).append(": ").append(v)
    }
    sb.append("}")
    sb.append(", \"error_samples\": [")
    sb.append(st.errorSamples.joinToString(", ") { jsonStr(it) })
    sb.append("]")
    sb.append("},\n")
    sb.append("  \"client_resources\": {")
    sb.append("\"user_cpu_s\": ").append(ru.userSec)
    sb.append(", \"sys_cpu_s\": ").append(ru.sysSec)
    sb.append(", \"cpu_utilization\": ").append(if (wallSec > 0) ((ru.userSec + ru.sysSec) / wallSec * 1000).toLong() / 1000.0 else 0.0)
    sb.append(", \"max_rss_bytes\": ").append(ru.maxRssBytes)
    sb.append(", \"note\": \"getrusage(RUSAGE_SELF) over the whole client process lifetime; server resources are sampled by the runner\"")
    sb.append("}\n")
    sb.append("}\n")
    return sb.toString()
}
