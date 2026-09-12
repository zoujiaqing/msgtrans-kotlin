### 20260912T205624Z-ab-b1-c50
- host: Apple M1 Pro (10 cpu), Darwin 25.2.0; same-host (server and client share CPU, memory and the loopback stack); load avg at start 12.57 21.44 17.42
- versions: msgtrans-kotlin 5a535ba, neton-io f32e685, kotlin 2.4.0, release macosArm64
- params: conns=50 payload=64B inflight=1 warmup=2s duration=5s repeat=5; env NETON_IO_DRIVER=(default) NETON_IO_URING_DEPTH=(default)

| mode | runs ok | throughput req/s median (min..max) | p50 us | p99 us | p999 us | max us | client cpu | per-conn min/max (worst run) | server rss / cpu time | errors |
|---|---|---|---|---|---|---|---|---|---|---|
| rpc-channel | 5/5 | 91,139 (86,478..92,616) | 540.7 | 852.0 | 1015.8 | 4090.8 | 1.00 | 8652/8653 | 13 MiB / 0:06.25, 0:06.33, 0:06.41, 0:06.50 | - |
| rpc-inline | 5/5 | 89,647 (84,287..92,483) | 540.7 | 1310.7 | 3276.8 | 9790.5 | 0.99 | 8430/8431 | 13 MiB / 0:06.10, 0:06.23, 0:06.29, 0:06.36, 0:06.47 | - |

Not collected: server-side allocation/GC counters, per-core utilization, packet counts; client CPU is getrusage over the whole process (all phases), server CPU/RSS is a single ps sample at run end.

