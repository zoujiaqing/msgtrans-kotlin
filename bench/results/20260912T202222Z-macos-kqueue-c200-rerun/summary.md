### 20260912T202222Z-macos-kqueue-c200-rerun
- host: Apple M1 Pro (10 cpu), Darwin 25.2.0; same-host (server and client share CPU, memory and the loopback stack); load avg at start 7.48 10.13 12.62
- versions: msgtrans-kotlin 3e57f2f-dirty, neton-io a3cdfa4, kotlin 2.4.0, release macosArm64
- params: conns=200 payload=64B inflight=1 warmup=2s duration=5s repeat=3; env NETON_IO_DRIVER=(default) NETON_IO_URING_DEPTH=(default)

| mode | runs ok | throughput req/s median (min..max) | p50 us | p99 us | p999 us | max us | client cpu | server rss / cpu time | errors |
|---|---|---|---|---|---|---|---|---|---|
| raw | 3/3 | 106,087 (102,948..106,714) | 1867.8 | 3014.7 | 7995.4 | 11900.5 | 0.98 | 24 MiB / 0:06.54, 0:06.66, 0:06.68 | - |
| framed | 3/3 | 98,931 (87,134..100,632) | 1933.3 | 4128.8 | 7995.4 | 16063.7 | 0.97 | 24 MiB / 0:06.04, 0:06.65, 0:06.71 | - |
| rpc | 3/3 | 91,436 (89,305..93,246) | 2162.7 | 3080.2 | 3342.3 | 20707.4 | 0.99 | 28 MiB / 0:06.46, 0:06.57, 0:06.61 | - |

Not collected: server-side allocation/GC counters, per-core utilization, packet counts; client CPU is getrusage over the whole process (all phases), server CPU/RSS is a single ps sample at run end.

