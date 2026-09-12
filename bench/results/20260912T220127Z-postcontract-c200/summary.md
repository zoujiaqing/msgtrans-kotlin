### 20260912T220127Z-postcontract-c200
- host: Apple M1 Pro (10 cpu), Darwin 25.2.0; same-host (server and client share CPU, memory and the loopback stack); load avg at start 17.65 10.54 10.10
- versions: msgtrans-kotlin f257735, neton-io 033a979, kotlin 2.4.0, release macosArm64
- params: conns=200 payload=64B inflight=1 warmup=2s duration=5s repeat=3; env NETON_IO_DRIVER=(default) NETON_IO_URING_DEPTH=(default)

| mode | runs ok | throughput req/s median (min..max) | p50 us | p99 us | p999 us | max us | client cpu | per-conn min/max (worst run) | server rss / cpu time | errors |
|---|---|---|---|---|---|---|---|---|---|---|
| raw | 3/3 | 102,423 (101,649..107,822) | 1900.5 | 3014.7 | 6946.8 | 37863.5 | 0.96 | 2541/2544 | 25 MiB / 0:06.34, 0:06.45, 0:06.57 | - |
| framed | 3/3 | 100,931 (98,421..101,704) | 1966.1 | 2555.9 | 5636.1 | 6727.8 | 0.99 | 2461/2463 | 24 MiB / 0:06.68, 0:06.75, 0:06.78 | - |
| rpc | 3/3 | 73,712 (73,060..75,700) | 2555.9 | 4587.5 | 15466.5 | 32685.5 | 0.98 | 1833/1834 | 28 MiB / 0:05.70, 0:05.75, 0:05.85 | - |

Not collected: server-side allocation/GC counters, per-core utilization, packet counts; client CPU is getrusage over the whole process (all phases), server CPU/RSS is a single ps sample at run end.

