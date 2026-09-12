### 20260912T202330Z-macos-kqueue-c500-rerun
- host: Apple M1 Pro (10 cpu), Darwin 25.2.0; same-host (server and client share CPU, memory and the loopback stack); load avg at start 7.56 9.66 12.26
- versions: msgtrans-kotlin 3e57f2f-dirty, neton-io a3cdfa4, kotlin 2.4.0, release macosArm64
- params: conns=500 payload=64B inflight=1 warmup=2s duration=5s repeat=3; env NETON_IO_DRIVER=(default) NETON_IO_URING_DEPTH=(default)

| mode | runs ok | throughput req/s median (min..max) | p50 us | p99 us | p999 us | max us | client cpu | server rss / cpu time | errors |
|---|---|---|---|---|---|---|---|---|---|
| raw | 3/3 | 99,088 (98,902..104,769) | 4980.7 | 7864.3 | 24117.2 | 30009.0 | 0.95 | 49 MiB / 0:06.28, 0:06.46, 0:06.60 | - |
| framed | 3/3 | 96,429 (96,169..99,753) | 5111.8 | 9175.0 | 14417.9 | 17447.7 | 0.97 | 47 MiB / 0:06.58, 0:06.62, 0:06.64 | - |
| rpc | 3/3 | 87,367 (85,050..88,170) | 5767.2 | 8650.8 | 14155.8 | 48958.2 | 0.98 | 57 MiB / 0:06.36, 0:06.45 | - |

Not collected: server-side allocation/GC counters, per-core utilization, packet counts; client CPU is getrusage over the whole process (all phases), server CPU/RSS is a single ps sample at run end.

