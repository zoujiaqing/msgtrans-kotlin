### 20260912T220018Z-postcontract-c50
- host: Apple M1 Pro (10 cpu), Darwin 25.2.0; same-host (server and client share CPU, memory and the loopback stack); load avg at start 8.56 8.32 9.38
- versions: msgtrans-kotlin f257735, neton-io 033a979, kotlin 2.4.0, release macosArm64
- params: conns=50 payload=64B inflight=1 warmup=2s duration=5s repeat=3; env NETON_IO_DRIVER=(default) NETON_IO_URING_DEPTH=(default)

| mode | runs ok | throughput req/s median (min..max) | p50 us | p99 us | p999 us | max us | client cpu | per-conn min/max (worst run) | server rss / cpu time | errors |
|---|---|---|---|---|---|---|---|---|---|---|
| raw | 3/3 | 108,573 (105,174..109,061) | 450.6 | 606.2 | 688.1 | 907.3 | 0.97 | 10516/10521 | 12 MiB / 0:06.51, 0:06.62, 0:06.79 | - |
| framed | 3/3 | 100,053 (95,476..101,285) | 466.9 | 1146.9 | 3735.6 | 11169.8 | 0.97 | 9546/9554 | 12 MiB / 0:06.52, 0:06.67, 0:06.72 | - |
| rpc | 3/3 | 72,129 (71,941..83,464) | 622.6 | 2293.8 | 6815.7 | 10783.4 | 0.95 | 7199/7200 | 13 MiB / 0:05.51, 0:05.64, 0:05.77 | - |

Not collected: server-side allocation/GC counters, per-core utilization, packet counts; client CPU is getrusage over the whole process (all phases), server CPU/RSS is a single ps sample at run end.

