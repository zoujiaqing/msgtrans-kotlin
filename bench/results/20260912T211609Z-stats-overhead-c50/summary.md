### 20260912T211609Z-stats-overhead-c50
- host: Apple M1 Pro (10 cpu), Darwin 25.2.0; same-host (server and client share CPU, memory and the loopback stack); load avg at start 81.93 40.33 23.88
- versions: msgtrans-kotlin 81ec8ab-dirty, neton-io 03f1cc1, kotlin 2.4.0, release macosArm64
- params: conns=50 payload=64B inflight=1 warmup=2s duration=5s repeat=5; env NETON_IO_DRIVER=(default) NETON_IO_URING_DEPTH=(default)

| mode | runs ok | throughput req/s median (min..max) | p50 us | p99 us | p999 us | max us | client cpu | per-conn min/max (worst run) | server rss / cpu time | errors |
|---|---|---|---|---|---|---|---|---|---|---|
| rpc-0 | 5/5 | 87,482 (83,518..89,815) | 557.1 | 1212.4 | 2949.1 | 13874.0 | 0.96 | 8354/8355 | 13 MiB / 0:05.95, 0:06.02, 0:06.22, 0:06.31, 0:06.37 | - |
| rpc-1 | 5/5 | 90,329 (75,808..91,231) | 557.1 | 901.1 | 1409.0 | 4385.0 | 0.98 | 7584/7585 | 13 MiB / 0:05.80, 0:06.21, 0:06.37, 0:06.41, 0:06.53 | - |

Reactor counters per request (NETON_IO_STATS=1; whole process lifetime, medians over runs):
| mode | side | budget | polls/req | events/poll | zero-timeout polls/req | empty polls | reads/req | reads EAGAIN/req | writes/req | tasks/req |
|---|---|---|---|---|---|---|---|---|---|---|
| rpc-1 | client | 0 | 0.044 | 22.94 | 0.000 | 0.000 | 2.000 | 1.000 | 1.000 | 3.00 |
| rpc-1 | server | 0 | 0.078 | 12.76 | 0.000 | 0.000 | 2.000 | 1.000 | 1.000 | 3.00 |

Not collected: server-side allocation/GC counters, per-core utilization, packet counts; client CPU is getrusage over the whole process (all phases), server CPU/RSS is a single ps sample at run end.

