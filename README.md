# msgtrans-kotlin

A Kotlin/Native implementation of the [msgtrans](https://github.com/zoujiaqing/msgtrans) wire
protocol and transport, built on the [neton-io](../neton-io) reactor.

It is the language mapping of the msgtrans Rust surface and the TypeScript client: same Packet
semantics, same request/response correlation, same wire bytes. The wire format is the single
source of truth and cross-language conformance is the contract.

## Architecture

Per-connection **actor**, following the msgtrans model:

- One connection is owned by its own coroutines with a **bounded outbound mailbox** — a read
  loop that dispatches inbound packets and a write loop that drains the mailbox.
- All connection state (the pending-request registry, the id counters) is touched only from
  these coroutines on the single reactor thread, so it is serialized without locks. That is the
  actor discipline realized with coroutines, not a mailbox-and-message framework.
- Backpressure is **per-connection**: a slow handler or a full mailbox stalls only that
  connection — there is no fan-out bus.
- The request registry is the sole arbiter of "exactly one response per request". The request id
  is per-session and monotonic; one-way messages use a separate counter.

```
Connection (actor)
├── read loop   → dispatch: Response → complete pending; Request → handler → reply; OneWay → handler
├── write loop  → drain bounded mailbox → encode → socket
└── registry    → messageId → pending response
        │
   neton-io reactor (kqueue / epoll / poll)
```

## Modules

- `msgtrans-core` — `Packet`, `PacketCodec` (wire-exact, big-endian), types. All native targets.
- `msgtrans-transport` — the actor `Connection`, `Transport` client/server, `SessionHandler`. Runs
  on the neton-io reactor (Apple + Linux).

## Usage

```kotlin
runReactor {
    val server = Transport.bind(this, "0.0.0.0", 9000) { conn ->
        conn.onRequest { payload, _ -> ("reply:" + payload.decodeToString()).encodeToByteArray() }
    }
    launch { server.acceptLoop() }

    val conn = Transport.connect(this, "127.0.0.1", 9000)
    val reply = conn.request("ping".encodeToByteArray(), bizType = 7)   // reply == "reply:ping"
    conn.events().collect { msg -> /* inbound one-way / server push */ }
}
```

## Build and test

```bash
./gradlew :msgtrans-core:macosArm64Test
./gradlew :msgtrans-transport:macosArm64Test   # request/response over real TCP
./gradlew :msgtrans-transport:linkDebugTestLinuxX64   # cross-compile for Linux
```

## Status

P0: wire-exact Packet codec (verified against the exact byte layout) and the actor transport
(request/response, one-way events, server push) pass on macOS (kqueue) and Linux (io_uring / epoll).

Next: compression (Zstd/Zlib payloads), WebSocket transport, request timeouts (needs a reactor
timer), the ext-header/route-tag path, and shared cross-language conformance fixtures against the
Rust and TypeScript implementations.

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

**CPU hotspots** (`sample(1)`, `bench/results/20260912T2100Z-hotspots-macos/`): on this host the
reactor thread spends most of its samples in `recvfrom`/`sendto`/`kevent`, one of each per request;
Kotlin user code (codec, channels, continuations, allocator, GC) is the small remainder in both
`framed` and `rpc`. `rpc` shows about twice the `kevent` samples of `framed`: the reactor loop polls
with a zero timeout whenever dispatched tasks are pending, so each extra coroutine hop the actor adds
becomes an extra `kevent` call. That points the next experiment at the reactor's poll/dispatch
policy and syscall count (arm-once / edge-triggered, deferring the poll while runnable tasks remain,
and io_uring on Linux) rather than at a user-space rewrite of the connection (B2). B2 remains a
candidate if a Linux/io_uring profile disagrees.

The optimization stays benchmark-driven (see SPEC): one variable per experiment, results kept under
`bench/results/`, contracts (ordering, backpressure, cancellation, close) checked by tests in both
variants.
