### 20260912T211725Z-budget-c50-stats
- host: Apple M1 Pro (10 cpu), Darwin 25.2.0; same-host (server and client share CPU, memory and the loopback stack); load avg at start 36.11 35.95 23.65
- versions: msgtrans-kotlin 81ec8ab-dirty, neton-io 03f1cc1, kotlin 2.4.0, release macosArm64
- params: conns=50 payload=64B inflight=1 warmup=2s duration=5s repeat=5; env NETON_IO_DRIVER=(default) NETON_IO_URING_DEPTH=(default)

| mode | runs ok | throughput req/s median (min..max) | p50 us | p99 us | p999 us | max us | client cpu | per-conn min/max (worst run) | server rss / cpu time | errors |
|---|---|---|---|---|---|---|---|---|---|---|
| framed-0 | 5/5 | 100,706 (63,624..104,922) | 475.1 | 1179.6 | 3276.8 | 11656.0 | 0.97 | 6354/6384 | 12 MiB / 0:04.97, 0:05.84, 0:06.66, 0:06.71, 0:06.84 | - |
| framed-64 | 5/5 | 87,094 (58,881..104,813) | 483.3 | 1507.3 | 7077.9 | 59785.0 | 0.89 | 5880/5906 | 12 MiB / 0:04.38, 0:04.90, 0:06.12, 0:06.66, 0:06.74 | - |
| rpc-0 | 5/5 | 90,211 (86,336..91,499) | 557.1 | 868.4 | 1032.2 | 3212.7 | 1.00 | 8639/8640 | 13 MiB / 0:06.32, 0:06.41, 0:06.50, 0:06.52, 0:06.55 | - |
| rpc-64 | 5/5 | 88,516 (86,515..90,798) | 540.7 | 999.4 | 2687.0 | 4364.5 | 0.98 | 8655/8656 | 13 MiB / 0:06.30, 0:06.31, 0:06.39, 0:06.43 | - |

Reactor counters per request (NETON_IO_STATS=1; whole process lifetime, medians over runs):
| mode | side | budget | polls/req | events/poll | zero-timeout polls/req | empty polls | reads/req | reads EAGAIN/req | writes/req | tasks/req |
|---|---|---|---|---|---|---|---|---|---|---|
| framed-0 | client | 0 | 0.029 | 34.98 | 0.000 | 0.000 | 2.000 | 1.000 | 1.000 | 1.00 |
| framed-0 | server | 0 | 0.181 | 5.51 | 0.000 | 0.000 | 2.000 | 1.000 | 1.000 | 1.00 |
| framed-64 | client | 64 | 0.037 | 27.17 | 0.000 | 0.000 | 2.000 | 1.000 | 1.000 | 1.00 |
| framed-64 | server | 64 | 0.172 | 5.82 | 0.000 | 0.000 | 2.000 | 1.000 | 1.000 | 1.00 |
| rpc-0 | client | 0 | 0.043 | 23.12 | 0.000 | 0.000 | 2.000 | 1.000 | 1.000 | 3.00 |
| rpc-0 | server | 0 | 0.076 | 13.08 | 0.000 | 0.000 | 2.000 | 1.000 | 1.000 | 3.00 |
| rpc-64 | client | 64 | 0.054 | 18.52 | 0.035 | 0.003 | 2.000 | 1.000 | 1.000 | 3.00 |
| rpc-64 | server | 64 | 0.102 | 9.80 | 0.008 | 0.001 | 2.000 | 1.000 | 1.000 | 3.00 |

Not collected: server-side allocation/GC counters, per-core utilization, packet counts; client CPU is getrusage over the whole process (all phases), server CPU/RSS is a single ps sample at run end.

