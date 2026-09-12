### 20260912T200352Z-macos-kqueue-c200
- host: Apple M1 Pro (10 cpu), Darwin 25.2.0; same-host (server and client share CPU, memory and the loopback stack)
- versions: msgtrans-kotlin c707a55-dirty, neton-io 8667675-dirty, kotlin 2.4.0, release macosArm64
- params: conns=200 payload=64B inflight=1 warmup=2s duration=5s repeat=3; env NETON_IO_DRIVER=(default) NETON_IO_URING_DEPTH=(default)

| mode | runs ok | throughput req/s median (min..max) | p50 us | p99 us | p999 us | max us | client cpu | server rss / cpu time | errors |
|---|---|---|---|---|---|---|---|---|---|
| raw | 3/3 | 85,016 (54,836..103,863) | 1966.1 | 9437.2 | 22544.4 | 30118.6 | 0.86 | 24 MiB / 0:04.18, 0:05.80, 0:06.58 | - |
| framed | 3/3 | 62,761 (43,114..72,965) | 2228.2 | 17825.8 | 46137.3 | 49742.0 | 0.60 | 24 MiB / 0:02.79, 0:04.09, 0:04.31 | - |
| rpc | 3/3 | 63,576 (45,931..69,890) | 2490.4 | 14417.9 | 31981.6 | 53107.8 | 0.61 | 27 MiB / 0:03.40, 0:03.98, 0:05.32 | - |

Not collected: server-side allocation/GC counters, per-core utilization, packet counts; client CPU is getrusage over the whole process (all phases), server CPU/RSS is a single ps sample at run end.

