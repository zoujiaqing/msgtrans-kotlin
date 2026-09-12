### 20260912T200817Z-macos-kqueue-c50
- host: Apple M1 Pro (10 cpu), Darwin 25.2.0; same-host (server and client share CPU, memory and the loopback stack); load avg at start 13.28 15.70 13.24
- versions: msgtrans-kotlin 3e57f2f-dirty, neton-io a3cdfa4, kotlin 2.4.0, release macosArm64
- params: conns=50 payload=64B inflight=1 warmup=2s duration=5s repeat=3; env NETON_IO_DRIVER=(default) NETON_IO_URING_DEPTH=(default)

| mode | runs ok | throughput req/s median (min..max) | p50 us | p99 us | p999 us | max us | client cpu | server rss / cpu time | errors |
|---|---|---|---|---|---|---|---|---|---|
| raw | 3/3 | 108,937 (106,777..110,682) | 442.4 | 639.0 | 1835.0 | 5077.2 | 0.99 | 12 MiB / 0:06.60, 0:06.75, 0:06.81 | - |
| framed | 3/3 | 104,903 (103,732..106,738) | 458.8 | 671.7 | 1638.4 | 8177.8 | 0.98 | 12 MiB / 0:06.75, 0:06.85 | - |
| rpc | 3/3 | 91,949 (89,965..93,874) | 540.7 | 933.9 | 2687.0 | 9212.7 | 0.99 | 13 MiB / 0:06.35, 0:06.43 | - |

Not collected: server-side allocation/GC counters, per-core utilization, packet counts; client CPU is getrusage over the whole process (all phases), server CPU/RSS is a single ps sample at run end.

