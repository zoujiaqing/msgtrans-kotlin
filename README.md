# msgtrans-kotlin

A Kotlin/Native implementation of the [msgtrans](https://github.com/zoujiaqing/msgtrans) wire
protocol and transport, built on the [neton-io](../neton-io) reactor.

It is the language mapping of the msgtrans Rust surface and the TypeScript client: same Packet
semantics, same request/response correlation, same wire bytes. The wire format is the single
source of truth and cross-language conformance is the contract.

## Architecture

The **contracts** (stable) and the **current implementation** (replaceable) are kept separate on
purpose, so the execution model can change without touching callers.

Contracts:
- **Single ownership.** A connection and all its state (pending-request registry, id counters,
  send queue) are owned by one reactor thread and touched only there — no locks. Calling a
  connection's I/O from another thread is rejected, not silently raced.
- **Handler off the read path.** Inbound requests are handled off the read loop, so a slow or
  reentrant handler (e.g. one issuing a reverse request) never blocks response completion or
  deadlocks.
- **Finite everything.** The outbound queue is byte-bounded (senders suspend), in-flight requests
  are count-bounded, and every request has a timeout. Memory cannot grow without bound; a request
  cannot wait forever.
- **One response per request.** The registry is the sole arbiter; the request id is per-session
  and monotonic, one-way messages use a separate counter.
- **Terminal states release once.** close, peer EOF, socket error, timeout and cancellation each
  free the registry, queues and waiters exactly once (see the class doc on `Connection`).
- **Compression is normalized at one boundary.** Outbound zstd/zlib is applied before framing;
  compressed input may expand to at most 16 MiB and is decompressed before any handler or pending
  request. Uncompressed input keeps the existing frame-size limit.

Current implementation (a per-connection actor with coroutines; may be replaced if a benchmark
justifies it — the contracts above will not change):

```
Connection
├── read loop    → Response → complete pending; Request → handler queue; OneWay → events
├── handler loop → drain request queue → onRequest → enqueue reply   (off the read path)
├── write path   → CHANNEL: mailbox + write coroutine (default) | INLINE: single-writer (experiment)
└── registry     → messageId → pending response (+ in-flight cap, timeouts)
        │
   neton-io reactor (kqueue / epoll / poll / io_uring), single thread
```

## Public API and compatibility

The public surface is `Transport`, `Connection`, `ConnectionConfig`, `Message`, the exceptions,
and neton-io's `runReactor` / `IoStream` / `Buffer`. It deliberately exposes **no** file
descriptor, `Channel`, `CompletableDeferred`, reactor or driver type, so the internal execution
model and buffer implementation can be replaced without a source change for callers. `WriteMode`
is an experimental performance knob and may change or disappear.

Toolchain boundary: during development neton-io is consumed as a sibling `includeBuild` under the
same coordinates it is published with, so both compile with one Kotlin/Native version. KLIB binary
compatibility across Kotlin versions is not guaranteed; a consumer pins the Kotlin version of the
release it depends on.

## Modules

One published artifact, `com.netonstream:msgtrans`, the way the Rust crate is one crate:

- package `msgtrans.core` — `Packet`, `PacketCodec` (wire-exact, big-endian), zstd/zlib payload
  codecs, types.
- package `msgtrans.transport` — the `Connection` session, `Transport` client/server, the
  `ClientTransport`/`ServerTransport` protocol seam (TCP; WebSocket/QUIC declared). Runs on the
  neton-io reactor.

Additional protocols (`msgtrans-ws`, `msgtrans-quic`) and the RPC layer are add-on modules that
depend on `msgtrans`; nothing needs the codec without the session, so the two are not split.
`msgtrans-bench` (the harness behind `bench/run.sh`) is not published.

```kotlin
dependencies { implementation("com.netonstream:msgtrans:0.1.0") }
```

Consumers must compile with the same Kotlin version as the release (2.4.0): the artifacts are
klibs, and klib binary compatibility is bound to the compiler version.

## Usage

The API mirrors the Rust msgtrans surface: pick a transport, then use the same `send` / `request`
over it. TCP is implemented; WebSocket and QUIC are declared binding points (`WebSocketClientTransport`,
`QuicClientTransport`) so business code will not change when they land.

```kotlin
runReactor {
    val server = Transport.bind(this, TcpServerTransport("0.0.0.0", 9000)) { conn ->
        conn.onRequest { payload, _ -> ("reply:" + payload.decodeToString()).encodeToByteArray() }
    }
    launch { server.acceptLoop() }

    val conn = Transport.connect(this, TcpClientTransport("127.0.0.1", 9000),
        ConnectionConfig(requestTimeoutMillis = 5_000, maxInFlightRequests = 256))

    conn.send("hello".encodeToByteArray(), compression = Compression.Zstd)

    val reply = conn.request(
        "ping".encodeToByteArray(), bizType = 7, compression = Compression.Zlib,
    )
    // request throws RequestTimeoutException on timeout, ConnectionClosedException on close;
    // requestOrNull returns null on timeout (mirrors Rust `request(...).data: Option`):
    val data: ByteArray? = conn.requestOrNull("what time is it?".encodeToByteArray())
    if (data != null) println(data.decodeToString()) else println("request timed out")

    conn.events().collect { msg -> /* inbound one-way / server push */ }
}
```

`Transport.connect(scope, host, port, …)` / `bind(scope, host, port, …)` remain as TCP
conveniences. `Connection` is callable from any thread — a call off the owning reactor is posted to
it — so a connection reference can be shared with worker threads safely.

## Build and test

```bash
./gradlew :msgtrans:macosArm64Test              # codec + request/response over real TCP
./gradlew :msgtrans:linkDebugTestLinuxX64       # cross-compile for Linux
```

## Status

Wire-exact Packet codec (verified against the exact byte layout), zstd/zlib compression, and transport
(request/response with timeouts and an in-flight cap, one-way events, server push) with the
contracts above. Request timeouts use the neton-io reactor timer.

Verified (2026-09-15), reproducible: `msgtrans:linuxX64Test` — 22 cases (ContractTest 8,
FaultIsolation 1, OutboundBudget 3, RequestDeadline 2, RequestResponse 6, WriteMode 2) — passes
with 0 failures on a Rocky Linux 9.8 / kernel 5.14 / x86_64 host under **epoll**; the codec cases
passes there too, and macOS (kqueue) passes the same cases. The pre-compression 20-case suite also
passed under io_uring and io_uring at SQ depth 8 on that host on 2026-09-13; the host currently has
io_uring disabled at the kernel level, so the two new compression transport cases have not been
re-run with that driver. This is test-case pass over the
contracts (dual call entry incl. a request issued from another thread, timeout incl. the in-flight
slot wait, terminal-state cleanup, backpressure, the transport binding, requestOrNull), not a
guarantee of every path or of scalability/tail-latency/memory behaviour under sustained load.

Cross-language wire interop with the **Rust** msgtrans (2026-09-13, reproducible): verified both
directions against the Rust `echo_server` example in passive mode (byte-for-byte echo, biz_type/id
preserved) over TCP. Kotlin client -> Rust server: `benchClient mode=framed|rpc host=127.0.0.1
port=18091` — 30k+ request/responses, 0 errors, and the framed layer checks type/id/biz_type/payload
exactly. Rust client -> Kotlin server: the Rust `echo_client_tcp` example against `benchServer
mode=rpc port=8001` receives the correct echoed responses. This exercises encode/decode in both
languages. Reproduce with `scripts/interop-rust.sh` (builds the Rust echo
example and both Kotlin bench binaries, runs both directions, asserts 0 errors). Still open: a shared **fixture-based** conformance suite (the Rust repo has
`wire_format_fixtures`; running the same vectors in a Kotlin test is the remaining gate), and TS
interop. API/toolchain boundary above covers the supported range (TCP; WebSocket/QUIC declared,
unimplemented).

Compression interoperability is verified in both directions against msgtrans-rust with `flate2`
and `zstd`: Rust fixtures decode in Kotlin, and Kotlin output decodes in Rust. The zstd codec is
Square's native KMP packaging of libzstd; zlib uses the platform library. Next: the remaining wire
fixtures against Rust/TS, WebSocket transport, and the ext-header/route-tag path.

## Benchmark

`bench/run.sh` measures three comparable layers with one harness (`bench/README.md`): `raw`
(neton-io byte echo), `framed` (msgtrans wire over neton-io `Framed`/`serve`, no actor) and `rpc`
(the full `Connection` actor). Same connection count, same payload, one outstanding request per
connection; connect / warmup / measure / exit are timed separately; every response is validated and
a failure is counted as an error, never as throughput. Raw results plus host, load, driver, git
revisions and binary checksums are stored under `bench/results/<stamp>-<label>/`.

Same-host macOS kqueue (Apple M1 Pro, release, 64 B, 1 in-flight/conn, 3 repeats, median):

| conns | raw | framed | rpc | results dir |
|---|---|---|---|---|
| 50 | 106,712 req/s (p99 0.64 ms) | 97,452 (p99 1.05 ms) | 82,752 (p99 1.54 ms) | `20260912T202440Z-macos-kqueue-c50-rerun` |
| 200 | 106,087 (p99 3.0 ms) | 98,931 (p99 4.1 ms) | 91,436 (p99 3.1 ms) | `20260912T202222Z-macos-kqueue-c200-rerun` |
| 500 | 99,088 (p99 7.9 ms) | 96,429 (p99 9.2 ms) | 87,367 (p99 8.7 ms) | `20260912T202330Z-macos-kqueue-c500-rerun` |

All runs validated every response (0 errors, 0 timeouts). Server RSS at 500 connections: 47–57 MiB.

Limitations: the host carried unrelated load throughout (load average ~7.5 on 10 cores during the
`-rerun` directories, 11–43 during the earlier ones). In the earlier directories the 200-connection
`rpc` and 500-connection `raw` runs collapsed with both processes mostly idle; host load is a
plausible cause and the reruns were tight, but the cause is **not confirmed** (an idle-both-sides
collapse is also what a wakeup/wait defect looks like). Those runs are kept with a `NOTE.md` as
anomalous runs of unconfirmed cause; the harness now records per-connection progress so a stuck
connection can be told from a global slowdown next time. Check `load_avg_at_start` in `meta.json`
before reading any directory. What these numbers support: under this load and cadence (1 in-flight,
not saturated) `rpc` throughput is below `framed` and the reported p99 values are close. They do not
isolate the actor, the request table or client-side cost, and say nothing about tail latency near
saturation. No Linux
(epoll / io_uring) numbers were produced with this harness yet; the previous README table (Linux,
`requestClient`) used a different client and is not comparable.

What the gaps mean: `framed − raw` is the combined cost of the 16-byte header, encode/decode and
`Packet` allocation; `rpc − framed` is the combined cost of the actor (pending registry,
`CompletableDeferred`, request queue, outbound mailbox, three coroutines per connection). The gap
locates cost; it does not attribute it to any single queue or object — that needs a controlled A/B.

An earlier "does not complete at ~200 connections" observation was an io_uring SQ-ring overflow in
neton-io (fixed there); the previous engineer reports the neton-io 400-connection regression passing
at ring depth 4/8. That report has not been re-run here.

### Execution-model experiments

Single-variable A/B runs, interleaved per repeat (`bench/run.sh --ab`), same host and cadence as above.

**B1 — outbound path: bounded Channel + write coroutine (A) vs inline single-writer state machine
(`MSGTRANS_WRITE_MODE=inline`).** Read loop, serial handler loop, pending registry and
`CompletableDeferred` unchanged; one packet per socket write in both.

| conns | A channel (5 runs, median, min..max) | B1 inline | dir |
|---|---|---|---|
| 50 | 91,139 (86,478..92,616) | 89,647 (84,287..92,483) | `20260912T205624Z-ab-b1-c50` |
| 200 | 89,478 (79,897..92,470) | 89,063 (86,927..91,603) | `20260912T205740Z-ab-b1-c200` |

Result: **no stable benefit** at 1 in-flight per connection; the difference is inside the run-to-run
spread. B1 stays available behind `WriteMode` for other cadences (pipelined sends, many senders per
connection) but is not adopted as default.

**CPU samples** (`sample(1)`, `bench/results/20260912T2100Z-hotspots-macos/`): most reactor-thread
samples sit in `recvfrom`/`sendto`/`kevent`, Kotlin user code is the small remainder, in both
`framed` and `rpc`. Caveat: a sample stopped in `kevent` may be *waiting* (same-host, 1 in-flight,
the peer's turn), so these shares are not CPU shares and the earlier reading that `rpc` "doubles
the kevent calls" was not supported — the reactor drains all tasks before it polls, so extra
coroutine hops do not add polls. Syscall behaviour is now **counted** instead (`NETON_IO_STATS=1`,
per-request table in `summary.md`): e.g. 2 `recv` per request on the client, one of them EAGAIN
(speculative read before readiness), 1 `send`, and ~0.3 `kevent` at 8 connections. The next
single-variable experiment is the reactor's local task budget (`NETON_IO_TASK_BUDGET`), compared
on poll count, throughput and tail latency; arm-once / edge-triggered is a separate experiment
because it changes the event contract. B2 remains a candidate if a profile shows user-space
dominating.

**Cost of the request-timeout contract** (`MSGTRANS_REQUEST_TIMEOUT_MS`, dir
`20260912T220422Z-rpc-timeout-cost-c50`, 6 interleaved repeats, host load ~9): wrapping every
request in `withTimeout` (default 30s) costs about 12% throughput and raises p99.

| rpc, 50 conns | timeout off | timeout 30s (default) |
|---|---|---|
| throughput req/s (median) | 90,040 | 79,202 |
| p99 (us) | 999 | 1,475 |

The timeout is on by default because "a request cannot wait forever" is a contract. It first
used `withTimeout` (a TimeoutCoroutine per request); replacing that with one deadline registered
directly on the reactor timer closed the gap to within the run-to-run spread:

| rpc, 50 conns | timeout off | timeout 30s |
|---|---|---|
| withTimeout (old) | 90,040 | 79,202 |
| direct deadline (now) | 84,612 | 83,821 |

(`20260912T220833Z-rpc-timeout-cheap-c50`; rpc-0 vs the earlier run differs by host-load noise, but
the on-vs-off gap is what the interleaved pair isolates.) The knob still lets an application drop
the guarantee explicitly.

**Reactor counters and the task-budget experiment** (`NETON_IO_STATS=1`, `NETON_IO_TASK_BUDGET`;
dirs `20260912T211609Z-stats-overhead-c50`, `…-budget-c50-stats`, `…-budget-c200-stats`, host load
17–82 during these runs, so only the counters and the interleaved pairs are meaningful):

| per request (medians, client and server alike) | framed | rpc |
|---|---|---|
| recv calls / of which EAGAIN | 2.00 / 1.00 | 2.00 / 1.00 |
| send calls | 1.00 | 1.00 |
| kevent calls (50 conns, server) | 0.18 | 0.08 |
| dispatched tasks | 1.00 | 3.00 |
| zero-timeout polls (budget 0) | 0 | 0 |

- The actor costs exactly two extra continuation dispatches per request on each side; it does not
  add polls (the loop drains every task before polling, zero-timeout polls are 0).
- Every request pays one `recv` that returns EAGAIN: the reactor reads speculatively before
  arming readiness, and at 1 in-flight the response is never there yet. That is the one clearly
  wasted syscall per request in this cadence; changing the read-before-arm policy is a candidate
  single-variable experiment (it trades that syscall for latency when data is already present).
- Task budget 64 vs unbounded: adds zero-timeout polls (0.03–0.05/req) and no stable throughput or
  tail-latency benefit within the spread; default stays unbounded.
- Enabling the counters: no measurable throughput cost within the spread (5 interleaved pairs).

The optimization stays benchmark-driven (see SPEC): one variable per experiment, results kept under
`bench/results/`, contracts (ordering, backpressure, cancellation, close) checked by tests in both
variants.

## License

[Apache License 2.0](LICENSE).
