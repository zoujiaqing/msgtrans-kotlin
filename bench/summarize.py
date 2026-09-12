#!/usr/bin/env python3
"""Summarize one bench/run.sh results directory (or several) as a markdown table.

Reports per mode: runs, ok runs, median/min/max throughput, median p50/p99/p999 latency,
client CPU utilization, server RSS/CPU time (ps sample), and error/timeout counts.
"""
import glob, json, os, statistics, sys

def load(d):
    meta = json.load(open(os.path.join(d, "meta.json")))
    runs = {}
    for f in sorted(glob.glob(os.path.join(d, "run-*.json"))):
        tag = os.path.basename(f)[4:-5]
        mode = tag.rsplit("-", 1)[0]
        r = json.load(open(f))
        sf = os.path.join(d, f"server-{tag}.json")
        r["server"] = json.load(open(sf)) if os.path.exists(sf) and os.path.getsize(sf) else {}
        runs.setdefault(mode, []).append(r)
    return meta, runs

def med(xs): return statistics.median(xs) if xs else 0

def main():
    for d in sys.argv[1:]:
        meta, runs = load(d)
        v, p, h = meta["versions"], meta["params"], meta["host"]
        print(f"### {os.path.basename(d)}")
        print(f"- host: {h['cpu']} ({h['ncpu']} cpu), {h['os']}; {meta['topology']}; load avg at start {h.get('load_avg_at_start', '?')}")
        note = os.path.join(d, "NOTE.md")
        if os.path.exists(note): print(f"- NOTE: {open(note).read().strip()}")
        print(f"- versions: msgtrans-kotlin {v['msgtrans-kotlin']}, neton-io {v['neton-io']}, kotlin {v['kotlin']}, {v['build_type']} {v['target']}")
        print(f"- params: conns={p['connections']} payload={p['payload_bytes']}B inflight=1 warmup={p['warmup_s']}s duration={p['duration_s']}s repeat={p['repeat']}; "
              f"env NETON_IO_DRIVER={meta['env']['NETON_IO_DRIVER'] or '(default)'} NETON_IO_URING_DEPTH={meta['env']['NETON_IO_URING_DEPTH'] or '(default)'}")
        print()
        print("| mode | runs ok | throughput req/s median (min..max) | p50 us | p99 us | p999 us | max us | client cpu | per-conn min/max (worst run) | server rss / cpu time | errors |")
        print("|---|---|---|---|---|---|---|---|---|---|---|")
        for mode in ("raw", "framed", "rpc"):
            rs = runs.get(mode)
            if not rs: continue
            ok = [r for r in rs if r["status"] == "ok"]
            thr = [r["results"]["throughput_req_s"] for r in ok]
            lat = lambda k: med([r["results"]["latency_us"][k] for r in ok])
            cpu = med([r["client_resources"]["cpu_utilization"] for r in ok])
            rss = med([r["server"].get("server_rss_kb", 0) for r in ok])
            ct = ", ".join(sorted({r["server"].get("server_cputime", "?") for r in ok}))
            errs = {}
            for r in rs:
                if r["status"] != "ok": errs[r["status"]] = errs.get(r["status"], 0) + 1
                for k, n in r["results"]["errors"].items(): errs[k] = errs.get(k, 0) + n
            pcs = [r["progress"].get("per_connection_measured") for r in rs if r["progress"].get("per_connection_measured")]
            worst = min(pcs, key=lambda x: x["min"]) if pcs else None
            pc = f"{worst['min']}/{worst['max']}" + (f" (zero={worst['zero']})" if worst and worst["zero"] else "") if worst else "n/a"
            print(f"| {mode} | {len(ok)}/{len(rs)} | {med(thr):,.0f} ({min(thr) if thr else 0:,.0f}..{max(thr) if thr else 0:,.0f}) | "
                  f"{lat('p50'):.1f} | {lat('p99'):.1f} | {lat('p999'):.1f} | {lat('max'):.1f} | {cpu:.2f} | {pc} | {rss/1024:.0f} MiB / {ct} | {errs or '-'} |")
        print()
        print("Not collected: server-side allocation/GC counters, per-core utilization, packet counts; "
              "client CPU is getrusage over the whole process (all phases), server CPU/RSS is a single ps sample at run end.")
        print()

if __name__ == "__main__":
    main()
