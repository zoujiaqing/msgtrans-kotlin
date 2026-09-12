### 20260912T220422Z-rpc-timeout-cost-c50
- host: Apple M1 Pro (10 cpu), Darwin 25.2.0; same-host (server and client share CPU, memory and the loopback stack); load avg at start 9.28 9.87 9.90
- versions: msgtrans-kotlin f257735-dirty, neton-io 033a979, kotlin 2.4.0, release macosArm64
- params: conns=50 payload=64B inflight=1 warmup=2s duration=5s repeat=6; env NETON_IO_DRIVER=(default) NETON_IO_URING_DEPTH=(default)

| mode | runs ok | throughput req/s median (min..max) | p50 us | p99 us | p999 us | max us | client cpu | per-conn min/max (worst run) | server rss / cpu time | errors |
|---|---|---|---|---|---|---|---|---|---|---|
| rpc-0 | 6/6 | 90,040 (81,554..91,706) | 548.9 | 999.4 | 2818.0 | 9512.4 | 0.99 | 8158/8159 | 13 MiB / 0:06.12, 0:06.29, 0:06.35, 0:06.39, 0:06.40, 0:06.47 | - |
| rpc-30000 | 6/6 | 79,202 (74,159..82,428) | 622.6 | 1474.6 | 4128.8 | 12340.0 | 0.99 | 7420/7421 | 13 MiB / 0:05.66, 0:05.68, 0:05.74, 0:05.85, 0:05.95 | - |

Not collected: server-side allocation/GC counters, per-core utilization, packet counts; client CPU is getrusage over the whole process (all phases), server CPU/RSS is a single ps sample at run end.

