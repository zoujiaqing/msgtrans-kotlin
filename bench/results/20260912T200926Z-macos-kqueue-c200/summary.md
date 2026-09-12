### 20260912T200926Z-macos-kqueue-c200
- host: Apple M1 Pro (10 cpu), Darwin 25.2.0; same-host (server and client share CPU, memory and the loopback stack); load avg at start 11.28 14.65 13.03
- versions: msgtrans-kotlin 3e57f2f-dirty, neton-io a3cdfa4, kotlin 2.4.0, release macosArm64
- params: conns=200 payload=64B inflight=1 warmup=2s duration=5s repeat=3; env NETON_IO_DRIVER=(default) NETON_IO_URING_DEPTH=(default)

| mode | runs ok | throughput req/s median (min..max) | p50 us | p99 us | p999 us | max us | client cpu | server rss / cpu time | errors |
|---|---|---|---|---|---|---|---|---|---|
| raw | 3/3 | 107,021 (102,926..107,320) | 1867.8 | 2293.8 | 2949.1 | 4652.2 | 0.97 | 24 MiB / 0:06.50, 0:06.52, 0:06.67 | - |
| framed | 3/3 | 100,010 (99,688..104,148) | 1900.5 | 3407.9 | 4718.6 | 5609.6 | 0.97 | 24 MiB / 0:06.62, 0:06.68, 0:06.79 | - |
| rpc | 3/3 | 21,550 (20,799..42,652) | 2883.6 | 81788.9 | 144347.7 | 167670.7 | 0.26 | 28 MiB / 0:01.52, 0:01.70, 0:04.18 | - |

Not collected: server-side allocation/GC counters, per-core utilization, packet counts; client CPU is getrusage over the whole process (all phases), server CPU/RSS is a single ps sample at run end.

