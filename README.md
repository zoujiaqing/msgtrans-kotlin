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
    val server = Transport.bind(this, "0.0.0.0", 9000) {
        object : SessionHandler {
            override suspend fun onRequest(payload: ByteArray, bizType: Int) =
                ("reply:" + payload.decodeToString()).encodeToByteArray()
        }
    }
    launch { server.acceptLoop() }

    val conn = Transport.connect(this, "127.0.0.1", 9000)
    val reply = conn.request("ping".encodeToByteArray(), bizType = 7)
    // reply == "reply:ping"
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
(request/response and one-way over TCP) pass on macOS (kqueue) and Linux (epoll and poll).

Next: compression (Zstd/Zlib payloads), WebSocket transport, request timeouts (needs a reactor
timer), the ext-header/route-tag path, and shared cross-language conformance fixtures against the
Rust and TypeScript implementations.
