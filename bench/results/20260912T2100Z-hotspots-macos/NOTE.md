# CPU hotspots, same-host macOS kqueue, 50 conns, 64 B, 1 in-flight (sample(1), 3 s during measure)

Method: `sample <pid> 3` on the release benchServer/benchClient while the client was in its
measure phase; "Sort by top of stack" counts. `__psynch_cvwait` / `__workq_kernreturn` /
`mach_msg2_trap` are idle helper threads (GC, coroutine Default dispatcher, watchdog), not the
reactor. Numbers in summary.txt. Release binaries keep Kotlin symbol names; there is no inlining
map, so attribution is by top frame only. Sampling noise: one 3 s window per mode, unrepeated.

What it shows (for this cadence and host):
- The reactor thread spends the large majority of its samples in three syscalls: recvfrom, sendto,
  kevent — roughly one of each per request. Kotlin user code (codec, coroutines, channels,
  allocator, GC) is the small remainder in every process, in both modes.
- server-rpc has about twice the kevent samples of server-framed. The reactor loop polls with a zero
  timeout whenever dispatched tasks are pending, so every extra coroutine hop the actor adds turns
  into an extra kevent call — the actor's cost shows up mostly as reactor syscalls, not as its own
  user-space frames.
- The user-space frames that do appear in rpc but not framed are the expected ones: BufferedChannel
  (tryResumeReceiver / expandBuffer / iterator), CancellableContinuationImpl, HashMap (pending
  registry), Pinned, allocator/GC. Each is a few samples out of thousands.

What it does not show: allocation counts, GC pause time, Linux/io_uring behaviour, behaviour at
higher in-flight depth or near saturation.

CORRECTION (later the same day): sample(1) counts a thread parked in kevent as a sample in kevent.
With 1 in-flight per connection on one host, much of that is waiting for the peer, so the shares
above are not CPU shares, and "rpc has 2x kevent because of extra coroutine hops" does not hold:
the reactor drains its task queue completely before polling (polls_zero_timeout=0 in the counters),
so hops never add polls. Use the NETON_IO_STATS counters, not these samples, for syscall claims.
