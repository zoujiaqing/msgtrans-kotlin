### 20260912T211957Z-budget-c200-stats
- host: Apple M1 Pro (10 cpu), Darwin 25.2.0; same-host (server and client share CPU, memory and the loopback stack); load avg at start 17.64 26.52 21.73
- versions: msgtrans-kotlin 81ec8ab-dirty, neton-io 03f1cc1, kotlin 2.4.0, release macosArm64
- params: conns=200 payload=64B inflight=1 warmup=2s duration=5s repeat=5; env NETON_IO_DRIVER=(default) NETON_IO_URING_DEPTH=(default)

| mode | runs ok | throughput req/s median (min..max) | p50 us | p99 us | p999 us | max us | client cpu | per-conn min/max (worst run) | server rss / cpu time | errors |
|---|---|---|---|---|---|---|---|---|---|---|
| framed-0 | 5/5 | 102,452 (98,198..104,248) | 1933.3 | 2818.0 | 7733.2 | 10860.5 | 0.96 | 2455/2457 | 24 MiB / 0:06.49, 0:06.61, 0:06.65, 0:06.71, 0:06.72 | - |
| framed-64 | 5/5 | 101,262 (98,167..104,755) | 1933.3 | 3473.4 | 7340.0 | 17141.3 | 0.97 | 2454/2457 | 24 MiB / 0:06.59, 0:06.64, 0:06.70, 0:06.75, 0:06.81 | - |
| rpc-0 | 5/5 | 72,007 (44,186..86,093) | 2293.8 | 7340.0 | 67108.9 | 92938.1 | 0.89 | 1111/1112 | 28 MiB / 0:04.67, 0:05.02, 0:05.87, 0:06.34, 0:06.47 | - |
| rpc-64 | 5/5 | 79,263 (57,834..85,074) | 2424.8 | 6291.5 | 10223.6 | 26705.8 | 0.88 | 1452/1453 | 28 MiB / 0:04.87, 0:04.92, 0:05.60, 0:06.17, 0:06.31 | - |

Reactor counters per request (NETON_IO_STATS=1; whole process lifetime, medians over runs):
| mode | side | budget | polls/req | events/poll | zero-timeout polls/req | empty polls | reads/req | reads EAGAIN/req | writes/req | tasks/req |
|---|---|---|---|---|---|---|---|---|---|---|
| framed-0 | client | 0 | 0.018 | 56.64 | 0.000 | 0.000 | 2.000 | 1.000 | 1.000 | 1.00 |
| framed-0 | server | 0 | 0.185 | 5.40 | 0.000 | 0.000 | 2.001 | 1.000 | 1.000 | 1.00 |
| framed-64 | client | 64 | 0.017 | 58.46 | 0.000 | 0.000 | 2.000 | 1.000 | 1.000 | 1.00 |
| framed-64 | server | 64 | 0.185 | 5.42 | 0.000 | 0.000 | 2.001 | 1.000 | 1.000 | 1.00 |
| rpc-0 | client | 0 | 0.017 | 58.25 | 0.000 | 0.000 | 2.000 | 1.000 | 1.000 | 3.00 |
| rpc-0 | server | 0 | 0.048 | 20.70 | 0.000 | 0.000 | 2.001 | 1.000 | 1.000 | 3.00 |
| rpc-64 | client | 64 | 0.050 | 20.00 | 0.046 | 0.046 | 2.000 | 1.000 | 1.000 | 3.00 |
| rpc-64 | server | 64 | 0.093 | 10.76 | 0.020 | 0.024 | 2.001 | 1.000 | 1.000 | 3.00 |

Not collected: server-side allocation/GC counters, per-core utilization, packet counts; client CPU is getrusage over the whole process (all phases), server CPU/RSS is a single ps sample at run end.

