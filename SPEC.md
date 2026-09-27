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

**Step 1 result (raw `bench/results/2026-09-26-153-writemode-raw.txt`, 8 paired rounds, 0 errors).** Server
`INLINE` vs `CHANNEL`: 1 reactor 1.035 (6/8), 4 reactors 1.010 (7/8) — consistent but small in *throughput*,
because this host is client-bound (the server gets ≈ 1.7 of 4 cores). From here msgtrans rounds also report
**server efficiency (requests per server CPU-second)**; steps 1 and 2 are re-measured together as a 2×2.

**Steps 1–2 as a 2×2 (raw `bench/results/2026-09-26-153-2x2-raw.txt`; 6 paired rounds; efficiency = requests per
server CPU-second, relative to inbound Channel + CHANNEL writes).**

| variant | 1 reactor | 4 reactors |
|---|---|---|
| INLINE writes only | 0.942 (2/6) | 0.988 (2/6) |
| reactor-local inbound queue only (step 2) | 0.998 (3/6) | 1.051 (4/6) |
| both | 1.044 (3/6) | 1.053 (5/6) |

The run-to-run spread for msgtrans on this host is ±10% (one-reactor runs 103k–145k req/s), so ±5% effects are
not resolved by 6 rounds. Decisions: **keep step 2** (neutral to +5%, removes atomics from the hot path, own
contract tests); **do not make INLINE the default** (+3.5% in the previous round, −6% at one reactor here).

The larger per-core drop at 4 reactors (≈ 125k → ≈ 72–82k req per server core-second) is most likely structural
rather than a defect: 12 connections over 4 reactors give each reactor ≈ 3 events per `epoll_wait` instead of ≈ 10,
i.e. more syscalls and wake-ups per request — the usual cost of thread-per-core under light per-reactor load. To be
confirmed with more connections per reactor (48+) when a separate client host is available.

## 12. Allocation-free request path (2026-09-27; neton-io SPEC §24)

**Evidence** (153, 1 reactor pinned, 12 connections, 64 B; raw `bench/results/2026-09-27-153-mt-raw.txt`). On the
zero-allocation neton-io, `raw` runs at ~200k req/s with no GC activity, `framed` at ~172k (3 allocations per
request: decoded Packet, payload copy, response Packet; 0.74 GC `sched_yield` per request) and `rpc` at ~90k
(**8 allocations per request**, 2.0 GC yields per request). callgrind names the rpc allocation sites: the read
loop's Flow collection (the collector's `emit` and its continuation, 2), `awaitOutboundRoom` (a suspend
function called on every enqueue, 1), the write loop (1), the handler loop (2: the response Packet and the
suspend-lambda handler invocation), decode (Packet + payload copy, 2). Kotlin/Native's GC is stop-the-world
with a spinning coordinator, so every allocation eventually costs the reactor thread time.

**Goal:** the rpc path allocates only what the protocol needs per request (decoded Packet, payload, response
Packet), like `framed`. Contracts of §3 / §4 are unchanged; all tests pass.

Steps (one variable each, measured on 153 with per-request allocations, GC yields and throughput):
1. Read loop: `Framed.receiveEach` (neton-io's inline pull loop) instead of `incoming().collect`.
2. `ReactorQueue.send`: non-suspending fast path (hand to a parked receiver or append), suspend only when full.
3. `enqueue`: the outbound-room check is a plain function; `awaitOutboundRoom` is called only when there is no
   room.
4. Handler as `fun interface RequestHandler { suspend fun handle(payload, bizType): ByteArray }`: a lambda
   passed to `onRequest { … }` still compiles (SAM conversion), and calling it allocates nothing.
5. Write path: in CHANNEL mode the writer parks in the channel once per request (a kotlinx continuation per
   park); INLINE mode writes from the enqueuing coroutine. Re-measure CHANNEL vs INLINE after 1–4; INLINE's
   drain becomes allocation-free (inline body; the cancellation successor stays a separate function).

**Results (153; raw `bench/results/2026-09-27-153-mt{3,4,5}-raw.txt`).** After steps 1–5 both write modes allocate
only the three protocol objects per request (callgrind). Server CPU per request, INLINE vs CHANNEL: **1 reactor
−8 %** (7.10 vs 7.75 µs; throughput 1.016, 6 rounds), **4 reactors / 48 connections −10 %** (8.52 vs 9.43 µs;
throughput 1.006, 4/6). **INLINE becomes the default** (`MSGTRANS_WRITE_MODE=channel` keeps CHANNEL); this revises
§11's decision, which was measured before the path was allocation-free. GC yields per request at 1 reactor: 2.0 → 0.74.
A fixed GC target heap (neton-io `GcTuning`, SPEC §24.6) adds ≈ 5 % at 1 reactor; it is left to the application.


**Client-side regression found and fixed (raw `bench/results/2026-09-27-153-mt10-raw.txt`).** With INLINE writes on io_uring the
*client* ran rpc at ≈ 40k req/s against ≈ 90k for CHANNEL (the server was fine: new server + old client 87–103k). The requesting
coroutine writes the socket itself inside `request()`'s `withContext` job; neton-io's cancellation watch on that job ran on every
normal completion and built a cancellation exception (a stack walk, 7.9 % of client CPU in `_Unwind_Find_FDE`). Fixed in neton-io
(SPEC §24.12) and in `ReactorQueue`. rpc after the fix: INLINE 86k, CHANNEL 94k (4 rounds each, both within the 80–104k run-to-run
spread of this host); before: INLINE 42k. The same bug was present before INLINE became the default (z4 build: 40k with INLINE).
Step 6 (ReactorResumer + Ring on the request path): rpc server 6983 → 6521 instructions per request, throughput 1.036 (6/6).

## 13. Allocation-light client request path (2026-09-27)

**Evidence** (153, callgrind on `benchClient` rpc, 12 connections): the client allocates ≈ 30 objects per request, the server 3.
Sites: `withContext(owner)` (a coroutine, two context merges, a child-job link), `CompletableDeferred` and its `await` (≈ 7:
the deferred, two await frames, a CancellableContinuation, parent handle, cancellation disposer, resumed state), the pending
registry (`HashMap<UInt, …>`: a boxed id and an entry), the deadline (a lambda and a reactor timer), the request/response
Packets and payload copies. For the PulseKit SDK this client runs on devices: allocations there are GC time and battery.

Steps (allocation counts by callgrind; contracts of §3 / §4 unchanged; all tests pass):
- **A.** A caller already on the owning reactor runs the request body directly; `withContext` only for other threads.
- **B.** The pending registry is an open-addressing `IdMap` (Int keys, no boxing, no entries).
- **C.** The request waits on its own raw continuation (resumed through neton-io's `ReactorResumer`, cancellation watched
  once per caller job) instead of a `CompletableDeferred`; CHANNEL mode's deadline race against a full mailbox keeps a
  deferred only on that slow path.

### 13.1 Results (153, 2026-09-27; client allocations by callgrind, epoll, 12 connections, 64 B)

| build | client allocations / request (INLINE / CHANNEL) | rpc INLINE req/s | rpc CHANNEL req/s |
|---|---|---|---|
| z10 (before §13) | ≈ 30 (§12) | 89.1k | 95.9k |
| z11 (A + B) | 18.2 / 27.0 | 116.8k (1.31) | 93.6k (0.98) |
| z12 (C) | 10.2 / 10.3 | 119.3k vs z11 115.9k (1.03) | 120.8k vs z11 101.8k (1.19) |

Throughput: mean of 4 paired rounds per pair (z10/z11 in one run, z11/z12 in the next), server pinned
to one core. The `framed` layer (not touched by §13) read 0.97 and 0.90 in the same runs; its z12
shortfall is two rounds (137k, 117k) in which z11 also fell (177k, 144k): host noise on this VM.

What remains per request (z12, INLINE): the request coroutine's frame (2), `CancellableContinuationImpl`
with its parent handle and dispatcher wrapper (3), the reactor timer (1), `Pending` (1), and the protocol
objects (request payload copy, decoded response Packet, the bench's own result). Those left are the
cost of a cancellable, deadline-bounded coroutine call; removing them would mean bypassing the
coroutine machinery, which §13 does not do.

Step B (`IdMap`), single-variable check (z13 = z12 with `HashMap<UInt, Pending>`, same z12 server, 6 paired
rounds): with `HashMap` the client makes 13.24 allocations and 18578 instructions per request against 10.24 and
17741 (callgrind, deterministic): each of put / get / remove boxes the UInt key. Throughput z12 / z13: INLINE
1.14, CHANNEL 0.97 (noise). `IdMap` stays. Raw: `bench/results/2026-09-27-153-*`.

## 14. GC under high concurrency (2026-09-27)

neton-io's echo path allocates nothing and runs without GC in steady state (neton-io SPEC §26.4). msgtrans does allocate per
request (server 3, client ≈ 10 objects, §13.1), so under load its GC runs continuously; with many connections the heap
(and so each collection's mark work) is larger. This section measures, on the rpc path, what GC costs and whether the
runtime's own lever — a fixed target heap (`GcTuning`, `NETON_IO_GC_TARGET_MB`) — helps.

Setup (153): `benchServer mode=rpc reactors=2` on cores 0,1 with `NETON_IO_GC_STATS=1`; two `benchClient` processes on
cores 2 and 3, each with half the connections; 64 B payload. N ∈ {1000, 10000} connections; GC: autotune (default),
target 64 MiB, target 256 MiB. 4 rounds, order rotated. Reported: summed throughput; server GCs per second, stop-the-world
time and time-to-safepoint per second (neton-io §26.3). A GC setting becomes a documented recommendation only if it
improves throughput in both N without raising p99.

### 14.1 Results (153, 4 rounds each, order rotated; raw `bench/results/2026-09-27-153-results-mt14-gc.txt`)

| conns | GC | req/s | p99 | GCs/s | STW µs/s | time-to-safepoint µs/s | heap after GC |
|---|---|---|---|---|---|---|---|
| 1k | autotune | 171.3k | 15.0 ms | 4.3 | 420 | 12.6k | 24 MiB |
| 1k | target 64 MiB | 147.9k (0.86) | 19.9 ms | 89.5 | 3867 | 43.6k | 16 MiB |
| 1k | target 256 MiB | 149.0k (0.87) | 19.7 ms | 81.1 | 3326 | 39.0k | 16 MiB |
| 10k | autotune | 146.6k | 127 ms | 0.4 | 48 | 1.9k | 192 MiB |
| 10k | target 64 MiB | 129.0k (0.88) | 150 ms | 8.5 | 1331 | 3.4k | 111 MiB |
| 10k | target 256 MiB | 130.8k (0.89) | 154 ms | 8.1 | 1387 | 2.7k | 112 MiB |

- With the runtime's autotuning, GC is not what limits msgtrans at high concurrency: stop-the-world time is 0.04 % of the
  wall clock at 1k connections and ~0 at 10k. Time to safepoint is larger (the coordinator waits for both reactors on two
  shared cores, neton-io §26.4), and it is where the remaining GC cost lies.
- A fixed target heap is **harmful** here: 20× more collections, −11 to −14 % throughput, worse p99, in all 8 pairs.
  This contradicts the +5.5 % measured at 12 connections (neton-io §24.6). Why a 64–256 MiB target collects this often
  (the heap after GC stays at 16 MiB) is not explained yet; until it is, **the recommendation is the default (autotune)**,
  and `GcTuning`'s documentation says so.

### 14.2 Heap floor instead of a fixed target (neton-io SPEC §26.8)
The fixed target was broken by a runtime detail: with autotuning off, Kotlin/Native 2.4.0 never moves its GC trigger from
0.9 × the initial 10 MiB, so above 9 MiB alive it collects continuously. `GcTuning.setMinHeap` keeps autotuning and only
raises its floor (`GC.minHeapBytes`). Same setup as §14.1 (raw `bench/results/2026-09-27-153-results-mt15-gc-minheap.txt`):

| conns | GC | req/s | p99 | GCs/s | STW µs/s | time-to-safepoint µs/s | heap |
|---|---|---|---|---|---|---|---|
| 1k | autotune | 172.1k | 15.0 ms | 4.4 | 317 | 14.4k | 24 MiB |
| 1k | floor 64 MiB | 175.5k (1.02) | 14.8 ms | 0.8 | 52 | 3.1k | 63 MiB |
| 1k | floor 256 MiB | 173.3k (1.01) | 14.8 ms | 0.2 | 66 | 0.8k | 237 MiB |
| 10k | autotune | 153.3k | 124 ms | 0.3 | 42 | 0.6k | 192 MiB |
| 10k | floor 64 MiB | 156.5k (1.02) | 123 ms | 0.5 | 78 | 1.3k | 192 MiB |
| 10k | floor 256 MiB | 150.7k (0.98) | 125 ms | 0.2 | 69 | 0.5k | 251 MiB |

A 64 MiB floor cuts collections and safepoint waiting about 5× at 1k connections; throughput +2 % in both sizes (3 of 4
rounds each, at the edge of this host's noise), p99 not worse; it costs heap (24 → 63 MiB at 1k). 256 MiB adds nothing.
Recommendation: autotune stays the default (GC settings are process-wide, so the library never sets them); servers with
memory to spare may set `NETON_IO_GC_MIN_HEAP_MB=64` / `GcTuning.setMinHeap(64)`.

## 15. msgtrans as neton-io's first consumer (2026-09-27; neton-io SPEC §28, pending review)

neton-io is only the I/O and networking foundation; msgtrans is one of the protocol libraries built on it
(neton-io SPEC §28.1). Obligations, so that msgtrans is evidence that the foundation's public API is enough:
- **Public API only.** Today msgtrans uses 13 public neton-io symbols and no internal one (neton-io §28.1). Anything
  msgtrans cannot do with the public API is reported as a neton-io gap (neton-io SPEC), not worked around here.
- **`ReactorResumer` is optional.** msgtrans uses it in `ReactorQueue` for same-reactor handoffs; the code must stay
  correct with it removed (plain `intercepted().resume`). Its use follows neton-io §28.3: every resume path takes the
  continuation out of its slot first (`ReactorQueue`: offer, close and cancellation all do); cancellation handlers
  hop to the owner reactor before touching a slot; the watch is re-checked after registration (§27.9, done).
- **Stream contract.** msgtrans only relies on `IoStream` behaviour covered by the neton-io conformance suite
  (neton-io §28.6); any other behaviour it needs is added to that suite first.
- **Fairness.** neton-io §28.4's scenarios are also run through msgtrans rpc (hot pipelined connections with many
  small requests + cold connections), since msgtrans' INLINE writer and handler loop add their own scheduling.

### 15.1 Acceptance for the optional resumer (revision 2, after review)
Code inspection is not enough: msgtrans gets a switch (`MSGTRANS_REACTOR_RESUMER=0`, read once at startup) that makes
`ReactorQueue` use plain `intercepted().resume` instead of `ReactorResumer`. The full test suite — correctness,
cancellation and close tests included — must pass in both modes on every platform it runs on (macOS; Linux io_uring /
epoll). When neton-io §28.3 makes `ReactorResumer.resume` return `Boolean`: a reactor that is draining still accepts
every resume, so queue hand-offs keep working while connections finish or are cancelled during a stop. `false` only
happens once the reactor is `CLOSING` / `STOPPED`, when (neton-io §28.3 invariant) no child coroutine of it is still
parked, so it means a lifecycle bug: `ReactorQueue` then releases what the item carries, marks itself closed, and
reports the fault (not silently). Tests: a queue hand-off during a draining stop completes; the misuse path reports.

### 15.2 Coordinates (neton-io SPEC §28.13)
msgtrans keeps `com.netonstream:msgtrans` and its `msgtrans.*` packages; from 0.2.0 it depends on `com.netonstream:io`
(the renamed neton-io artifact; the Kotlin packages `neton.io.*` are unchanged).
