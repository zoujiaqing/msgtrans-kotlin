### 20260912T220833Z-rpc-timeout-cheap-c50
- host: Apple M1 Pro (10 cpu), Darwin 25.2.0; same-host (server and client share CPU, memory and the loopback stack); load avg at start 9.90 9.75 9.81
- versions: msgtrans-kotlin 6d66094-dirty, neton-io 033a979, kotlin 2.4.0, release macosArm64
- params: conns=50 payload=64B inflight=1 warmup=2s duration=5s repeat=6; env NETON_IO_DRIVER=(default) NETON_IO_URING_DEPTH=(default)

| mode | runs ok | throughput req/s median (min..max) | p50 us | p99 us | p999 us | max us | client cpu | per-conn min/max (worst run) | server rss / cpu time | errors |
|---|---|---|---|---|---|---|---|---|---|---|
| rpc-0 | 6/6 | 84,612 (78,825..87,200) | 573.4 | 1327.1 | 4128.8 | 14858.5 | 0.95 | 7886/7887 | 13 MiB / 0:05.88, 0:06.07, 0:06.17, 0:06.31, 0:06.32, 0:06.39 | - |
| rpc-30000 | 6/6 | 83,821 (77,892..86,412) | 589.8 | 1245.2 | 3014.7 | 12070.3 | 0.98 | 7793/7794 | 13 MiB / 0:05.93, 0:05.99, 0:06.15, 0:06.20, 0:06.21, 0:06.23 | - |

Not collected: server-side allocation/GC counters, per-core utilization, packet counts; client CPU is getrusage over the whole process (all phases), server CPU/RSS is a single ps sample at run end.

