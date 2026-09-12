# Benchmark harness

Three comparable layers, one harness, one server and one client binary:

| mode     | wire                                        | server                                            |
|----------|---------------------------------------------|---------------------------------------------------|
| `raw`    | payload bytes only                          | read into a buffer, write it back                 |
| `framed` | msgtrans Packet (16 B header + payload)     | neton-io `Framed`/`serve`: Request → Response, same id/bizType/payload |
| `rpc`    | msgtrans Packet                             | full `Connection` actor + `onRequest` echo        |

The client is a closed loop: every connection has exactly **one outstanding request**, so the
three modes share connection count, payload, and in-flight window. Every response is validated
(type, message id, bizType, payload for `framed`; bytes for `raw`; payload for `rpc`, whose actor
already matches id/type); a failed validation ends that connection and is counted as an error, not
as throughput.

## Phases

`connect` (open all N connections; nothing else starts until every one is open) →
`warmup` (closed loop, not counted) → `measure` (counted) → `exit` (each connection finishes its
in-flight request, then closes). Throughput = measured requests / (last measured completion −
measure start). The JSON reports each phase's duration separately.

## Failure and timeout behaviour

- EOF, decode error, or validation mismatch: that connection stops, the error is counted by kind
  (`eof`, `mismatch`, `error`, `connect`, `close`); the run's status becomes `errors`, exit code 1.
- The harness never cancels a coroutine parked in I/O (cancelling an in-flight io_uring op is a
  known-unverified path in neton-io). A **watchdog thread** outside the reactor fails the run if a
  phase exceeds its budget: `connect_timeout` for connect, `warmup + timeout` and
  `duration + timeout` for the loop phases, `exit_timeout` for exit. It writes a partial JSON with
  status `timeout` and exits with code 2. A single stuck connection therefore fails the run
  loudly rather than being silently excluded.
- Both binaries ignore SIGPIPE so a vanished peer surfaces as an error instead of killing the
  process.

## Running

```bash
# builds release binaries first; runs only if the build succeeds
bench/run.sh --conns 50 --payload 64 --warmup 2 --duration 5 --repeat 3 --label mylabel
# Linux: pin the driver and SQ depth
bench/run.sh --driver iouring --depth 4096 --label linux-iouring
bench/run.sh --driver epoll --label linux-epoll
```

Options: `--modes raw,framed,rpc --conns N --payload N --warmup S --duration S --repeat N
--timeout S --driver NAME --depth N --port N --label TEXT --host-client H --no-build`.

Each run writes `bench/results/<utc-stamp>-<label>/`:

- `meta.json` — host, OS, CPU, git revisions of both repos (with `-dirty` when uncommitted),
  Kotlin version, build type, binary checksums, all parameters and driver env.
- `run-<mode>-<i>.json` — raw client result: config, phases, throughput, latency (mean/min/p50/
  p90/p99/p999/max, log-linear histogram, ~3% resolution), errors, client CPU/RSS (`getrusage`).
- `server-<mode>-<i>.json` — server RSS and cumulative CPU time (`ps` sample at run end).
- `*.log` — stdout/stderr of both processes; `summary.md` — `bench/summarize.py` table.

`bench/summarize.py <dir>...` re-renders the table for any results directories.

## Binaries directly

```
benchServer mode=raw|framed|rpc [host=0.0.0.0] [port=9000]
benchClient mode=raw|framed|rpc [host=127.0.0.1] [port=9000] [conns=50] [payload=64] [warmup=2]
            [duration=5] [timeout=10] [connect_timeout=30] [exit_timeout=10] [out=result.json] [label=..]
```

The server prints `READY mode=… port=…` once listening.

## Reading the numbers

The gap between two layers is the **combined** cost of everything the upper layer adds (extra
header bytes, encode/decode, allocations, coroutine hops, queues). It locates cost; it does not
attribute it to a particular queue or object — that needs a finer experiment (e.g. an A/B of the
connection execution model with everything else fixed).

Same-host runs share CPU, memory and the loopback stack between client and server; absolute
numbers are only comparable within one host and one results directory.

Not collected yet: allocation/GC counters, per-core utilization, server-side latency, multi-host
runs.
