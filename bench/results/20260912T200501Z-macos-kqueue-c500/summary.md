### 20260912T200501Z-macos-kqueue-c500
- host: Apple M1 Pro (10 cpu), Darwin 25.2.0; same-host (server and client share CPU, memory and the loopback stack)
- versions: msgtrans-kotlin c707a55-dirty, neton-io 8667675-dirty, kotlin 2.4.0, release macosArm64
- params: conns=500 payload=64B inflight=1 warmup=2s duration=5s repeat=3; env NETON_IO_DRIVER=(default) NETON_IO_URING_DEPTH=(default)

| mode | runs ok | throughput req/s median (min..max) | p50 us | p99 us | p999 us | max us | client cpu | server rss / cpu time | errors |
|---|---|---|---|---|---|---|---|---|---|
| raw | 3/3 | 87,113 (76,689..88,744) | 5242.9 | 20447.2 | 37748.7 | 40871.5 | 0.80 | 49 MiB / 0:05.10, 0:05.33, 0:05.75 | - |
| framed | 3/3 | 89,339 (86,963..91,262) | 5374.0 | 11534.3 | 22020.1 | 32157.1 | 0.86 | 47 MiB / 0:05.60, 0:05.79, 0:06.20 | - |
| rpc | 3/3 | 75,677 (70,504..82,258) | 6029.3 | 19398.7 | 30933.0 | 48645.2 | 0.91 | 57 MiB / 0:05.78, 0:05.82, 0:06.18 | - |

Not collected: server-side allocation/GC counters, per-core utilization, packet counts; client CPU is getrusage over the whole process (all phases), server CPU/RSS is a single ps sample at run end.

