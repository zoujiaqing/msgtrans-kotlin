# msgtrans-kotlin — SPEC

> Kotlin/Native implementation of the [msgtrans](https://github.com/zoujiaqing/msgtrans) wire
> protocol and transport, on the [neton-io](../neton-io) reactor. Per-connection **actor**,
> **lock-free**, wire-compatible with the Rust and TypeScript implementations.
>
> Status: draft. The wire is stable and testable; the transport is P0.

---

## 1. Scope and positioning

msgtrans-kotlin is the language mapping of the msgtrans Rust surface and the TypeScript client:
the same Packet semantics, the same request/response correlation, the same wire bytes. The wire
format is the single source of truth; cross-language conformance is the contract (§9).

It is the long-connection / RPC / event channel for the Kotlin/Native stack (for example
`privchat-server ↔ privchat-application`, and Pulse telemetry). It does not contain any business
or IM logic.

The I/O foundation is neton-io; msgtrans-kotlin owns the **protocol and the actor connection
model**, neton-io owns the reactor and the sockets.

---

## 2. Wire format (contract)

The byte layout is defined by `msgtrans/docs/WIRE_FORMAT.md` (protocol version 1) and must not
drift. A 16-byte big-endian fixed header, then an optional ext header, then the payload:

```
 off  len  field            type
 0    1    version          u8   (= 1)
 1    1    compression      u8   (0 None, 1 Zstd, 2 Zlib)
 2    1    packet_type      u8   (0 OneWay, 1 Request, 2 Response)
 3    1    biz_type         u8   application-defined, passed through
 4    4    message_id       u32 BE
 8    2    ext_header_len   u16 BE
 10   4    payload_len      u32 BE
 14   2    reserved         u16 BE (flags)
```

- `message_id` is allocated by the sender from a monotonic counter; a Response reuses the
  matching Request's id. OneWay and Request share the counter; the per-session request counter
  **refuses to wrap** (saturates and reports exhaustion — a reconnect gets a fresh session), and
  the one-way counter is separate and free to wrap.
- The codec (`msgtrans-core`) is wire-exact and unit-tested against the exact byte layout so it
  stays interoperable. v1 does not de/compress payloads inside the transport layer.

---

## 3. Actor architecture (the core requirement)

Every connection is an **actor**: it is owned by its own coroutines, holds its own state, and
processes work through a **bounded outbound mailbox**. This mirrors msgtrans-rust's
per-connection actor model.

```
Connection (actor)
├── read loop   — decode inbound packets and dispatch:
│                 Response  -> complete the pending request
│                 Request   -> handler -> enqueue Response
│                 OneWay    -> handler
├── write loop  — drain the bounded outbound mailbox -> encode -> socket
└── registry    — messageId -> pending response (sole arbiter of one-response-per-request)
        │
   neton-io reactor (one thread; io_uring / epoll / kqueue)
```

### 3.1 Lock-free

The connection state — the pending-request registry and the id counters — is touched **only**
by the connection's own coroutines, and those coroutines run on a **single reactor thread**. So
access is serialized by construction: **no mutexes, no atomics, no CAS on the hot path**.

This is the same guarantee msgtrans-rust gets from a single-owner actor, reached differently:
- Rust: one task owns the connection; `Send`/`Sync` and the borrow checker enforce single access.
- Kotlin/Native: the reactor is single-threaded, so all of a connection's coroutines (read loop,
  write loop, and the application coroutines calling `request`/`send`) run on one thread and
  never race.

The actor discipline is realized with coroutines and a bounded `Channel` mailbox — not a separate
actor framework with message enums.

### 3.2 Bounded mailbox and per-connection backpressure

The outbound mailbox is a bounded `Channel<Packet>`. A slow handler or a full mailbox stalls
**only its own connection** — there is deliberately no fan-out bus (a shared bus makes one slow
consumer lose messages for everyone).

### 3.3 Event planes

Following msgtrans-rust, a connection's events belong to planes with different guarantees:

| Plane | Carries | Guarantee |
|---|---|---|
| Data | inbound Request/OneWay, Response completion, errors | bounded, backpressured, never dropped |
| Diagnostic | send confirmations | droppable under load, never displaces data |
| Control | close | never blocks; exactly one close ends the connection |

v1 implements the Data and Control planes; the Diagnostic plane is on the roadmap.

### 3.4 Connection lifecycle

`close()` cancels the read and write coroutines and fails all pending requests with
`ConnectionClosedException`. Cancellation is required: a closed fd is not reliably reported by
the readiness/completion reactor, so relying on the fd close to wake a parked coroutine would
strand the reactor (a real bug found and fixed in P0).

---

## 4. Request lifecycle

One registry is the sole arbiter of "exactly one response per request".

- `request(payload, bizType)` allocates a new per-session id, registers a `CompletableDeferred`,
  enqueues a Request, and suspends until the matching Response completes the deferred.
- The read loop matches an inbound Response by `message_id` and completes the pending deferred; a
  Response with no pending id is dropped.
- An inbound Request is handled and its Response is enqueued reusing the request's `message_id`.
- On close, every pending request fails deterministically (no scanner needed).

Request ids are matched on the wire by `message_id` alone (the header has no generation), so the
id counter is per-session and refuses to wrap; a reconnect gets a fresh session.

---

## 5. Modules

| Module | Contents | Targets |
|---|---|---|
| `msgtrans-core` | `Packet`, `PacketCodec` (wire-exact), `PacketType`, `Compression`, flags | all native |
| `msgtrans-transport` | `Connection` (actor), `Transport` client/server, `SessionHandler` | Apple + Linux (neton-io reactor) |

Future modules: `msgtrans-rpc` (declarative `@MsgRpc` with KSP-generated stubs), `msgtrans-ws`
(WebSocket transport), `msgtrans-testkit` (cross-language conformance fixtures).

---

## 6. Public API (v1)

```kotlin
runReactor {
    val server = Transport.bind(this, host, port) { handlerFactory() }
    launch { server.acceptLoop() }

    val conn = Transport.connect(this, host, port, handler)
    val response: ByteArray = conn.request(payload, bizType)   // suspends for the Response
    conn.send(payload, bizType)                                 // one-way
    conn.close()
}

interface SessionHandler {
    suspend fun onRequest(payload: ByteArray, bizType: Int): ByteArray
    suspend fun onMessage(payload: ByteArray, bizType: Int)
}
```

---

## 7. Roadmap

| Stage | Item |
|---|---|
| v0 (done) | wire-exact codec; actor Connection; request/response, one-way events (`events(): Flow`), server push; over TCP on macOS and Linux (io_uring default) |
| v1 | request timeout (needs a reactor timer); the Diagnostic plane (send confirmations); connection registry / broadcast on the server |
| v1.5 | WebSocket transport; payload compression (Zstd/Zlib); ext-header / route tag |
| v2 | declarative `@MsgRpc` + KSP-generated client stub / server dispatcher / route ids |
| v2+ | cross-language conformance fixtures; multi-reactor (thread-per-core) once neton-io provides it |

Low-level reactor completion/optimization (io_uring batching, registered buffers, multi-reactor)
belongs to neton-io and is out of scope here; see the neton-io SPEC.

---

## 8. Non-goals

- No business/IM logic (conversations, sync, presence, media) — that is PrivChat.
- No fan-out event bus — backpressure is per-connection.
- No lock-based concurrency — the single-reactor ownership is the concurrency model.

---

## 9. Cross-language conformance and benchmark

- **Conformance**: the Kotlin codec must produce and accept the exact bytes defined by
  `WIRE_FORMAT.md`; a shared fixture suite (with the Rust and TypeScript implementations) is the
  acceptance gate for the wire.
- **Benchmark**: msgtrans-kotlin and msgtrans (Rust) will be benchmarked against each other on
  the same host — request/response throughput and p99 — over the same wire, to quantify the cost
  of the Kotlin/Native + coroutine actor model versus the Rust actor model.
