### 20260912T205740Z-ab-b1-c200
- host: Apple M1 Pro (10 cpu), Darwin 25.2.0; same-host (server and client share CPU, memory and the loopback stack); load avg at start 9.95 18.70 16.72
- versions: msgtrans-kotlin 5a535ba, neton-io f32e685-dirty, kotlin 2.4.0, release macosArm64
- params: conns=200 payload=64B inflight=1 warmup=2s duration=5s repeat=5; env NETON_IO_DRIVER=(default) NETON_IO_URING_DEPTH=(default)

| mode | runs ok | throughput req/s median (min..max) | p50 us | p99 us | p999 us | max us | client cpu | per-conn min/max (worst run) | server rss / cpu time | errors |
|---|---|---|---|---|---|---|---|---|---|---|
| rpc-channel | 5/5 | 89,478 (79,897..92,470) | 2162.7 | 4587.5 | 13893.6 | 21657.4 | 0.97 | 2005/2006 | 28 MiB / 0:06.25, 0:06.35, 0:06.51, 0:06.59, 0:06.62 | - |
| rpc-inline | 5/5 | 89,063 (86,927..91,603) | 2162.7 | 3801.1 | 15990.8 | 38212.0 | 0.97 | 2180/2181 | 27 MiB / 0:06.36, 0:06.39, 0:06.40, 0:06.41 | - |

Not collected: server-side allocation/GC counters, per-core utilization, packet counts; client CPU is getrusage over the whole process (all phases), server CPU/RSS is a single ps sample at run end.

