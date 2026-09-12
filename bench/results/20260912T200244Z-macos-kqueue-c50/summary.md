### 20260912T200244Z-macos-kqueue-c50
- host: Apple M1 Pro (10 cpu), Darwin 25.2.0; same-host (server and client share CPU, memory and the loopback stack)
- versions: msgtrans-kotlin c707a55-dirty, neton-io 8667675, kotlin 2.4.0, release macosArm64
- params: conns=50 payload=64B inflight=1 warmup=2s duration=5s repeat=3; env NETON_IO_DRIVER=(default) NETON_IO_URING_DEPTH=(default)

| mode | runs ok | throughput req/s median (min..max) | p50 us | p99 us | p999 us | max us | client cpu | server rss / cpu time | errors |
|---|---|---|---|---|---|---|---|---|---|
| raw | 3/3 | 105,828 (101,810..108,214) | 450.6 | 852.0 | 2293.8 | 10330.3 | 0.98 | 12 MiB / 0:06.52, 0:06.61, 0:06.63 | - |
| framed | 3/3 | 103,319 (94,625..104,458) | 466.9 | 720.9 | 1245.2 | 3517.3 | 0.96 | 12 MiB / 0:06.51, 0:06.56, 0:06.83 | - |
| rpc | 3/3 | 79,411 (74,586..93,763) | 573.4 | 1572.9 | 7471.1 | 36704.5 | 0.91 | 13 MiB / 0:05.89, 0:05.91, 0:06.34 | - |

Not collected: server-side allocation/GC counters, per-core utilization, packet counts; client CPU is getrusage over the whole process (all phases), server CPU/RSS is a single ps sample at run end.

