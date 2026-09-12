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

## Benchmark (baseline)

The current implementation is a correctness prototype; these are the day-one baseline numbers the
optimization work is measured against. `requestServer`/`requestClient`, both on the same 2-core
Linux box, localhost, release, 64 B, request/response round-trips:

| Layer | 50 conns |
|---|---|
| msgtrans req/resp (io_uring) | 54,010 req/s |
| msgtrans req/resp (epoll) | 52,061 req/s |
| raw neton-io echo (io_uring), for reference | ~78,000 req/s |

So the actor + wire layer costs ~30% over a raw byte echo — the budget to reclaim. Known cost
sources on the hot path: a `CompletableDeferred` per request, two channel hops (request queue +
outbound mailbox), `Packet`/`ByteArray` allocations, and three coroutines per connection. At 200
connections the current model does not complete the run cleanly — a scalability item to fix.

The optimization is benchmark-driven (see SPEC): compare the coroutine model against a
reactor-driven connection state machine, cut allocations and channel hops, then scale to
multiple reactors — targeting gnet/ntex-class throughput.
