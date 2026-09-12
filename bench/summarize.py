#!/usr/bin/env python3
"""Summarize one bench/run.sh results directory (or several) as a markdown table.

Reports per mode: runs, ok runs, median/min/max throughput, median p50/p99/p999 latency,
client CPU utilization, server RSS/CPU time (ps sample), and error/timeout counts.
"""
import glob, json, os, statistics, sys

def stats_line(path):
    """NETON_IO_STATS {...} line from a process log, or None."""
    if not os.path.exists(path): return None
    for line in open(path, errors="replace"):
        if line.startswith("NETON_IO_STATS "):
            try: return json.loads(line[len("NETON_IO_STATS "):])
            except ValueError: return None
    return None

def load(d):
    meta = json.load(open(os.path.join(d, "meta.json")))
    runs = {}
    for f in sorted(glob.glob(os.path.join(d, "run-*.json"))):
        tag = os.path.basename(f)[4:-5]
        mode = tag.rsplit("-", 1)[0]
        r = json.load(open(f))
        sf = os.path.join(d, f"server-{tag}.json")
        r["server"] = json.load(open(sf)) if os.path.exists(sf) and os.path.getsize(sf) else {}
        r["client_stats"] = stats_line(os.path.join(d, f"client-{tag}.log"))
        r["server_stats"] = stats_line(os.path.join(d, f"server-{tag}.log"))
        runs.setdefault(mode, []).append(r)
    return meta, runs

def per_request(st, total):
    """Reactor counters normalized per request (whole process lifetime: connect+warmup+measure+exit)."""
    if not st or not total: return None
    t = float(total)
    return {"polls": st["polls"]/t, "reads": st["reads"]/t, "reads_eagain": st["reads_would_block"]/t,
            "writes": st["writes"]/t, "ev_per_poll": (st["events"]/st["polls"]) if st["polls"] else 0,
            "zero_to": st["polls_zero_timeout"]/t, "no_ev": st["polls_no_events"]/max(st["polls"],1),
            "tasks": st["tasks_run"]/t, "budget": st.get("task_budget", 0)}

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
        order = sorted(runs, key=lambda m: (["raw", "framed", "rpc"].index(m.split("-")[0]), m))
        for mode in order:
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
        # Reactor counters (NETON_IO_STATS=1), per request over the whole process lifetime.
        rows = []
        for mode in order:
            for r in runs[mode]:
                total = r["results"]["warmup_requests"] + r["results"]["measured_requests"]
                for side in ("client", "server"):
                    pr = per_request(r.get(f"{side}_stats"), total)
                    if pr: rows.append((mode, side, pr))
        if rows:
            print()
            print("Reactor counters per request (NETON_IO_STATS=1; whole process lifetime, medians over runs):")
            print("| mode | side | budget | polls/req | events/poll | zero-timeout polls/req | empty polls | reads/req | reads EAGAIN/req | writes/req | tasks/req |")
            print("|---|---|---|---|---|---|---|---|---|---|---|")
            for mode in order:
                for side in ("client", "server"):
                    sel = [pr for m, s_, pr in rows if m == mode and s_ == side]
                    if not sel: continue
                    m = lambda k: med([x[k] for x in sel])
                    print(f"| {mode} | {side} | {int(m('budget'))} | {m('polls'):.3f} | {m('ev_per_poll'):.2f} | {m('zero_to'):.3f} | {m('no_ev'):.3f} | {m('reads'):.3f} | {m('reads_eagain'):.3f} | {m('writes'):.3f} | {m('tasks'):.2f} |")
        print()
        print("Not collected: server-side allocation/GC counters, per-core utilization, packet counts; "
              "client CPU is getrusage over the whole process (all phases), server CPU/RSS is a single ps sample at run end.")
        print()

if __name__ == "__main__":
    main()
