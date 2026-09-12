### 20260912T202440Z-macos-kqueue-c50-rerun
- host: Apple M1 Pro (10 cpu), Darwin 25.2.0; same-host (server and client share CPU, memory and the loopback stack); load avg at start 7.33 9.20 11.88
- versions: msgtrans-kotlin 3e57f2f-dirty, neton-io a3cdfa4, kotlin 2.4.0, release macosArm64
- params: conns=50 payload=64B inflight=1 warmup=2s duration=5s repeat=3; env NETON_IO_DRIVER=(default) NETON_IO_URING_DEPTH=(default)

| mode | runs ok | throughput req/s median (min..max) | p50 us | p99 us | p999 us | max us | client cpu | server rss / cpu time | errors |
|---|---|---|---|---|---|---|---|---|---|
| raw | 3/3 | 106,712 (106,228..107,630) | 450.6 | 639.0 | 884.7 | 1941.5 | 0.98 | 12 MiB / 0:06.65, 0:06.67, 0:06.68 | - |
| framed | 3/3 | 97,452 (91,304..103,133) | 483.3 | 1048.6 | 2949.1 | 13470.0 | 0.97 | 11 MiB / 0:06.43, 0:06.65, 0:06.76 | - |
| rpc | 3/3 | 82,752 (80,500..91,058) | 573.4 | 1540.1 | 3670.0 | 9571.4 | 0.94 | 13 MiB / 0:05.82, 0:06.12, 0:06.39 | - |

Not collected: server-side allocation/GC counters, per-core utilization, packet counts; client CPU is getrusage over the whole process (all phases), server CPU/RSS is a single ps sample at run end.

