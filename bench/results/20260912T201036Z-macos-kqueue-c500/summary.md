### 20260912T201036Z-macos-kqueue-c500
- host: Apple M1 Pro (10 cpu), Darwin 25.2.0; same-host (server and client share CPU, memory and the loopback stack); load avg at start 25.78 17.21 14.06
- versions: msgtrans-kotlin 3e57f2f-dirty, neton-io a3cdfa4, kotlin 2.4.0, release macosArm64
- params: conns=500 payload=64B inflight=1 warmup=2s duration=5s repeat=3; env NETON_IO_DRIVER=(default) NETON_IO_URING_DEPTH=(default)

| mode | runs ok | throughput req/s median (min..max) | p50 us | p99 us | p999 us | max us | client cpu | server rss / cpu time | errors |
|---|---|---|---|---|---|---|---|---|---|
| raw | 3/3 | 35,928 (34,745..48,276) | 6946.8 | 88080.4 | 132120.6 | 132464.4 | 0.38 | 48 MiB / 0:02.38, 0:02.53, 0:02.98 | - |
| framed | 3/3 | 87,499 (70,384..89,664) | 5374.0 | 17301.5 | 42991.6 | 58949.0 | 0.84 | 47 MiB / 0:05.24, 0:05.65, 0:05.72 | - |
| rpc | 3/3 | 75,914 (70,508..86,727) | 6029.3 | 13631.5 | 23068.7 | 50230.4 | 0.92 | 56 MiB / 0:05.59, 0:05.96, 0:06.39 | - |

Not collected: server-side allocation/GC counters, per-core utilization, packet counts; client CPU is getrusage over the whole process (all phases), server CPU/RSS is a single ps sample at run end.

