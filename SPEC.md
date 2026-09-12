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

## 3. Connection model — contracts vs implementation

The **contracts** below are stable; the per-connection **actor implementation** that currently
realizes them is replaceable (an execution-model experiment may swap it — the benchmark decides —
without changing these contracts). Contracts: single-thread ownership of connection state (§3.1),
handler off the read path (§3), finite backpressure and timeouts (§3.4), one response per request
(§4), and once-only terminal cleanup. What is *not* a contract: that there is a mailbox, a write
coroutine, or three resident coroutines per connection.

The current implementation makes every connection an **actor**: owned by its own coroutines,
holding its own state, processing outbound work through a **bounded outbound mailbox** (or, behind
`WriteMode.INLINE`, a single-writer state machine). This mirrors msgtrans-rust's per-connection
actor model.

```
Connection (actor)
├── read loop    — decode; Response -> complete pending (inline); Request -> request queue; OneWay -> events()
├── handler loop — drain the bounded request queue -> onRequest -> enqueue Response  (off the read path)
├── write loop   — drain the bounded outbound mailbox -> encode -> socket
└── registry     — messageId -> pending response (sole arbiter of one-response-per-request)
        │
   neton-io reactor (one thread; io_uring / epoll / kqueue)
```

The read loop **never runs the business handler**. It completes responses inline and hands
requests to a separate handler loop over a bounded queue. This is required: an inline handler
that issues a reverse request on the same connection would deadlock (the read loop would be stuck
in the handler and could never read the reverse response), and a slow handler would block response
completion for the whole connection (head-of-line blocking).

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

### 3.4 Overload contract (bounded everything)

A single ordered TCP stream cannot skip ahead to a later Response while an earlier business
message is stuck, so backpressure is honest, not magical:

- The request queue, the outbound mailbox and the events channel are all **bounded**. When a queue
  is full the read loop stalls (backpressure) — it does not drop or reorder.
- The codec rejects a header claiming an oversized payload before buffering it (`maxPayloadLength`).
- A **CPU-bound or blocking handler must offload explicitly** — "a slow handler stalls only its own
  connection" holds only for handlers that suspend and yield the thread.
- Planned (v1): a cap on in-flight requests, and a defined action on sustained overload
  (reject / close) rather than unbounded buffering.

### 3.5 send semantics

`send`/`request` are **enqueue-confirmed** in v0: the call returns once the packet is on the
outbound mailbox, not once the bytes reach the socket. msgtrans-rust's `send` is write-confirmed; a
write-confirmed variant is planned so the two can be compared like for like.

### 3.6 Connection lifecycle

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
| `msgtrans-transport` | `Connection` (actor), `Transport` client/server, `Message` | Apple + Linux (neton-io reactor) |

Future modules: `msgtrans-rpc` (declarative `@MsgRpc` with KSP-generated stubs), `msgtrans-ws`
(WebSocket transport), `msgtrans-testkit` (cross-language conformance fixtures).

---

## 6. Public API (v1)

```kotlin
runReactor {
    val server = Transport.bind(this, host, port) { conn ->
        conn.onRequest { payload, bizType -> response }          // answer inbound requests
        conn.launch { conn.events().collect { msg -> /* … */ } } // inbound one-way, optional
    }
    launch { server.acceptLoop() }

    val conn = Transport.connect(this, host, port)
    val response: ByteArray = conn.request(payload, bizType)     // suspends for the Response
    conn.send(payload, bizType)                                  // one-way
    conn.events().collect { msg -> /* server push */ }           // Flow of inbound one-way
    conn.close()
}
```

`Connection` API: `request` (suspends for the Response), `send` (one-way), `onRequest` (handler),
`events(): Flow<Message>` (inbound one-way / server push), `launch` (a coroutine on the
connection scope), `close`.

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
- **Benchmark (benchmark-driven; harness in `bench/`)**: the current three-coroutine + Channel
  implementation is a correctness prototype, not the performance architecture. The benchmark is
  the arbiter. The harness (`benchServer`/`benchClient`, `bench/run.sh`, see `bench/README.md`)
  measures three comparable layers with identical connection count, payload and one outstanding
  request per connection, with connect / warmup / measure / exit timed separately, every response
  validated (type, id, bizType, payload — failures are counted, never as throughput), a watchdog
  instead of unsafe cancellation, and raw JSON + run metadata (host, load, driver, SQ depth, git
  revisions, binary checksums) stored per run:
  - `raw` — neton-io byte echo (reactor + stream cost);
  - `framed` — the msgtrans wire over neton-io `Framed`/`serve`, Response echoing the Request's id,
    no actor machinery (adds header bytes + encode/decode + `Packet` allocation);
  - `rpc` — the full `Connection` actor (adds pending registry, `CompletableDeferred`, request queue,
    outbound mailbox, three coroutines per connection).
  The gap between two adjacent layers is the **combined** cost of what that layer adds; it locates
  cost, it does not attribute it to a particular queue or object — that needs a controlled A/B with
  everything else fixed. Results and their limitations live in `bench/results/` and the README.
  History: an earlier "does not complete at 200 connections" observation was an io_uring SQ-ring
  overflow in neton-io (fixed there in 7077c92/8667675; the previous engineer reports the 400-conn
  regression passing at ring depth 4/8 — that report has not been re-run here). The current model
  runs at 500 connections in this harness; scalability beyond that is untested.
- **Two layers**: neton-io raw echo vs gnet/ntex; msgtrans req/resp vs Rust msgtrans — same wire,
  same request semantics.
- **Execution-model comparison (in progress, one variable at a time)**: the per-connection actor is
  the correctness reference, not the required architecture. What must hold is single ownership of
  connection state by the reactor thread, the handler off the read path, bounded backpressure, and
  the cancellation/close contracts; mailbox and resident coroutines are implementation choices.
  Done: B1 (outbound Channel + write coroutine vs inline single-writer, `WriteMode`) — no stable
  benefit at 1 in-flight on macOS (README). Hotspots there put the reactor's syscalls
  (recv/send/kevent per request) far above user-space cost; the actor's extra hops appear mostly
  as extra kevent calls. Counted (not sampled): the actor adds exactly 2 dispatches per request and no polls; every
  request pays one EAGAIN recv (speculative read before arming readiness); a bounded task budget
  gave no stable benefit. Next: read-before-arm policy as a single-variable experiment, Linux
  io_uring profile; C (replace `CompletableDeferred` with a stored continuation) and B2 (callback
  driven connection state machine, needs a neton-io interface that fits both readiness and
  completion drivers) only when a profile says user-space is the bottleneck.
- **Objective**: max throughput under a stated tail-latency and memory bound (not raw throughput
  via unbounded backlog / oversized batches); record p99/p999, allocations, RSS, GC pauses, and
  slow-consumer behavior alongside throughput.
- **Contracts stay, implementation is replaceable**: request cancellation, length limits, buffer
  release, close behavior and backpressure hold across any execution-model change.
