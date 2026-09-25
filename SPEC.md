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

It is the long-connection / RPC / event channel for the Kotlin/Native stack (for example the
Pulse analytics/APM ingest and remote-config channel). It does not contain any business logic.

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
- The codec (package `msgtrans.core`) is wire-exact and unit-tested against the exact byte layout. Payload
  compression is a separate transform: send compresses before framing; the connection read loop
  decompresses once before dispatch and resets the marker to None. Zstd uses level 3; zlib uses
  its default level. Expanded payloads above 16 MiB are rejected as protocol errors.

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

| Module | Contents | Targets | Published |
|---|---|---|---|
| `msgtrans` | package `msgtrans.core`: `Packet`, `PacketCodec`, zstd/zlib transforms, types and flags; package `msgtrans.transport`: `Connection` (actor), `Transport` client/server, `Message` | Apple + Linux (neton-io reactor) | `com.netonstream:msgtrans` |
| `msgtrans-bench` | the harness behind `bench/run.sh` | Apple + Linux | no |

One artifact rather than core/transport: the codec has no consumer without the session (the Rust
implementation is likewise one crate), and splitting before the first release would have fixed a
coordinate nobody needs. Future modules layer on top of `msgtrans`: `msgtrans-rpc` (declarative
`@MsgRpc` with KSP-generated stubs), `msgtrans-ws` (WebSocket transport), `msgtrans-quic`,
`msgtrans-testkit` (cross-language conformance fixtures).

---

## 6. Public API (v1)

Multi-protocol binding follows msgtrans-rust: a `ClientTransport` / `ServerTransport` chooses the
protocol (TCP now; `WebSocketClientTransport` / `QuicClientTransport` are declared binding points),
and the session API (`send`, `request`, `requestOrNull`, `onRequest`, `events`) is identical across
protocols. `request` throws on timeout; `requestOrNull` returns null (mirrors the Rust
`request(...).data: Option`). A `Connection` is callable from any thread (the call is posted to the
owning reactor).


```kotlin
runReactor {
    val server = Transport.bind(this, host, port) { conn ->
        conn.onRequest { payload, bizType -> response }          // answer inbound requests
        conn.launch { conn.events().collect { msg -> /* … */ } } // inbound one-way, optional
    }
    launch { server.acceptLoop() }

    val conn = Transport.connect(this, host, port)
    val response: ByteArray = conn.request(payload, bizType, compression = Compression.Zstd)
    conn.send(payload, bizType, compression = Compression.Zlib)
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
| v0 (done) | wire-exact codec; Connection session with the contracts; request/response (timeouts, in-flight cap), one-way events, server push; over TCP; suite verified on macOS (kqueue) and Linux (io_uring/epoll, 2026-09-13) |
| v1 | request timeout (needs a reactor timer); the Diagnostic plane (send confirmations); connection registry / broadcast on the server |
| v1.5 | WebSocket transport; ext-header / route tag (payload compression is complete) |
| v2 | declarative `@MsgRpc` + KSP-generated client stub / server dispatcher / route ids |
| v2+ | cross-language conformance fixtures; multi-reactor (thread-per-core) once neton-io provides it |

Low-level reactor completion/optimization (io_uring batching, registered buffers, multi-reactor)
belongs to neton-io and is out of scope here; see the neton-io SPEC.

---

## 8. Non-goals

- No business/IM logic (conversations, sync, presence, media) — msgtrans is a generic transport; such logic belongs to its consumers.
- No fan-out event bus — backpressure is per-connection.
- No lock-based concurrency — the single-reactor ownership is the concurrency model.

---

## 9. Cross-language conformance and benchmark

- **Conformance**: the Kotlin codec must produce and accept the exact bytes defined by
  `WIRE_FORMAT.md`; a shared fixture suite (with the Rust and TypeScript implementations) is the
  acceptance gate for the wire. Status (2026-09-13): the codec is unit-tested against the byte
  layout, and the transport passes its suite on macOS (kqueue) and Linux (io_uring/epoll, incl.
  SQ depth 8) — see the READMEs. **Cross-language interop against Rust: verified** (2026-09-13) both directions over TCP against the
  Rust `echo_server` passive example — Kotlin client -> Rust server (framed/rpc, type/id/biz_type/
  payload exact, 0 errors) and Rust `echo_client_tcp` -> Kotlin server (correct echoed responses).
  Still open: a shared **fixture-based** conformance suite (run the Rust `wire_format_fixtures`
  vectors in a Kotlin test) and TS interop.
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

---

## 10. Multi-reactor server (2026-09-26; neton-io SPEC §18.1)

The server accepted every connection on the reactor that called `bind`, so ingest used one core
regardless of load; neton-io's four-core result (§16/§17c there: 311k vs geario 267k) did not reach
msgtrans. `bind` gains a reactor count:

```kotlin
suspend fun Transport.bind(scope, transport, config = ConnectionConfig(), reactors: Int = 1, onConnection: (Connection) -> Unit): TransportServer
```

- `reactors = 1` (default): unchanged — the calling reactor accepts and owns every connection.
- `reactors > 1` (TCP only; other transports reject it): built on neton-io `listenGroup`. The
  calling reactor accepts; each connection is handed to a reactor round-robin and owned by it for
  life. Its scope is **that reactor's dispatcher + a `SupervisorJob` whose parent is the server
  scope's `Job`**, so (1) the connection's owner thread (§3 "dual entry") is its reactor, (2)
  cancelling the server scope still tears down every connection on every reactor, and (3) one
  connection's failure stays local (P1-4).
- `onConnection` runs **on the connection's reactor thread**. With `reactors > 1` that is not the
  caller's thread: shared state it touches must be thread-safe. This is the only contract change,
  and it is opt-in.
- `acceptLoop()` serves until `close()`; `close()` stops accepting, then the worker reactors exit
  once their connections are gone.

Acceptance: existing tests unchanged; new test — `reactors = 2`, 8 clients, request/response works,
handlers observed on ≥ 2 threads, cancelling the server scope closes connections on both reactors.
Bench on 153 (framed + rpc, 12 conns): `reactors = 4` ≥ 2× `reactors = 1`, paired rounds.

**Result (2026-09-26, 153, epoll, 128 B, 8 paired rounds, 0 errors; raw `bench/results/2026-09-26-153-multireactor-raw.txt`).**
Load = 3 `benchClient` processes (one reactor each) on the same 4-core host.

| mode | conns | 1 reactor | 4 reactors | paired 4/1 (min..max) |
|---|---|---|---|---|
| framed | 12 | 141,782 | 196,108 | 1.46 (1.25..1.77) |
| framed | 48 | 133,049 | 209,433 | 1.69 (1.37..1.99) |
| rpc | 12 | 117,081 | 128,378 | 1.13 (1.08..1.33) |
| rpc | 48 | 120,374 | 148,478 | 1.20 (1.16..1.49) |

The ≥ 2× acceptance is **not met on this host**. Working hypothesis, not yet proven: the load generator,
not the server — three Kotlin client processes doing the same framing/actor work as the server share the
4 cores with a 4-reactor server, and rpc (the heaviest client) scales worst. A per-process CPU measurement
(`run-mtcpu.sh`) is queued to confirm or refute it before anything is changed.

**CPU accounting (raw `bench/results/2026-09-26-153-multireactor-cpu-raw.txt`; utime+stime over 3 s mid-run).**

| run | req/s | server cores | client cores | server req/s per core |
|---|---|---|---|---|
| framed, 1 reactor | 123k / 147k | 1.01 | 1.5–1.6 | ≈ 135k |
| framed, 4 reactors | 194k / 201k | 1.75–1.78 | 1.8 | ≈ 112k |
| rpc, 1 reactor | 115k / 118k | 1.00 | 2.0–2.1 | ≈ 116k |
| rpc, 4 reactors | 130k / 115k | 1.64–1.68 | 1.8–1.9 | **≈ 70–78k** |

1. The host is saturated: with 4 reactors the server gets ≈ 1.7 cores because the clients use ≈ 1.9. The 2×
   acceptance cannot be measured on a 4-core host with this load generator; it needs a separate client host.
2. **rpc loses about a third of its per-core efficiency at 4 reactors** (≈ 116k → ≈ 70–78k req/s per server
   core); framed loses much less. rpc allocates far more per request (Packet, payload arrays, channel hops), so
   the leading suspect is GC coordination across four allocating threads. A per-thread profile is queued.

## 11. Reactor-local hot path (2026-09-26)

**Evidence** (per-thread profile, rpc, 1 vs 4 reactors, raw `bench/results/2026-09-26-153-rpcprof-raw.txt`): the
GC thread is ≈ 1% in both, so GC coordination is *not* why rpc loses per-core efficiency at 4 reactors. What
grows is user time in the reactor threads (32.5% → 40%), led by the actor machinery: kotlinx `BufferedChannel`
(its `AtomicArray` bounds checks alone 1.3–1.4%), `CancellableContinuationImpl.dispatchResume`, context
lookups. (That bench binary predates neton-io §19.3/§19.5; re-profile after these steps.)

Per request the server path is: decode → `inboundRequests` Channel → handler coroutine → response →
`outbound` Channel → writer coroutine → socket — two thread-safe channel hops and three coroutines. Every
mutating entry point already runs on the owning reactor (`withContext(owner)`, §3 "dual entry"), so these
queues need no atomics.

Steps, one variable each (153, rpc, 1 and 4 reactors, paired rounds, `echo-client-fair`-style fairness check
where applicable):
1. `WriteMode.INLINE` (single-writer deque, no outbound Channel; exists since B1) vs `CHANNEL` — no code change,
   `MSGTRANS_WRITE_MODE=inline`. B1's "no stable benefit" was measured on the old stack at one reactor on macOS.
2. Inbound requests through a reactor-local bounded queue and a parked handler (neton-io §19.3 style) instead of
   the `inboundRequests` Channel. Same contracts: bounded (`inboundCapacity`, read loop stalls when full), handler
   off the read path, close/cancel semantics unchanged.
3. `events()` keeps its Channel: its collector may live on another thread, and it is not on the rpc path.

Contracts (§3, §4) must hold in every step; all existing tests must pass.
