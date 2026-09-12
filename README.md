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
`-rerun` directories, 11–43 during the earlier ones, whose 200/500-connection numbers collapsed from
scheduling noise and are kept only with a `NOTE.md`). A single reactor thread in a 1-in-flight
ping-pong is latency-bound, so any preemption shows up as a throughput collapse that looks like a
protocol problem — check `load_avg_at_start` in `meta.json` before reading a directory. No Linux
(epoll / io_uring) numbers were produced with this harness yet; the previous README table (Linux,
`requestClient`) used a different client and is not comparable.

What the gaps mean: `framed − raw` is the combined cost of the 16-byte header, encode/decode and
`Packet` allocation; `rpc − framed` is the combined cost of the actor (pending registry,
`CompletableDeferred`, request queue, outbound mailbox, three coroutines per connection). The gap
locates cost; it does not attribute it to any single queue or object — that needs a controlled A/B.

An earlier "does not complete at ~200 connections" observation was an io_uring SQ-ring overflow in
neton-io (fixed there); the previous engineer reports the neton-io 400-connection regression passing
at ring depth 4/8. That report has not been re-run here.

The optimization is benchmark-driven (see SPEC): compare the coroutine model against a
reactor-driven connection state machine, cut allocations and channel hops, then scale to
multiple reactors — targeting gnet/ntex-class throughput.
