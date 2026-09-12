#!/usr/bin/env bash
# Repeatable three-layer benchmark driver: raw echo / framed echo / full msgtrans RPC.
#
# Fixes the build (release), records versions and parameters, runs each layer N times with the
# server and client on this host, and stores raw JSON results plus run metadata under
# bench/results/<timestamp>-<label>/. The new binaries are only run if the build succeeds.
#
# Usage: bench/run.sh [options]
#   --modes raw,framed,rpc   layers to run (default all three)
#   --conns N                connections (default 50)
#   --payload N              payload bytes (default 64)
#   --warmup S               warmup seconds (default 2)
#   --duration S             measure seconds (default 5)
#   --repeat N               repeats per mode (default 3)
#   --timeout S              stall slack before the watchdog fails a run (default 10)
#   --driver NAME            NETON_IO_DRIVER for server and client (Linux: epoll|polling|iouring)
#   --depth N                NETON_IO_URING_DEPTH for server and client (Linux io_uring)
#   --port N                 server port (default 9000)
#   --label TEXT             suffix for the results directory
#   --host-client H          client connect host (default 127.0.0.1)
#   --ab VAR=v1,v2           A/B: run each repeat once per value of env VAR, interleaved (e.g.
#                            --ab MSGTRANS_WRITE_MODE=channel,inline); the tag becomes <mode>-<value>-<i>
#   --no-build               skip the gradle build (use only with binaries you just built)
set -euo pipefail

MODES="raw,framed,rpc"; CONNS=50; PAYLOAD=64; WARMUP=2; DURATION=5; REPEAT=3; TIMEOUT=10
DRIVER=""; DEPTH=""; PORT=9000; LABEL=""; BUILD=1; CLIENT_HOST=127.0.0.1; AB=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --modes) MODES="$2"; shift 2;; --conns) CONNS="$2"; shift 2;; --payload) PAYLOAD="$2"; shift 2;;
    --warmup) WARMUP="$2"; shift 2;; --duration) DURATION="$2"; shift 2;; --repeat) REPEAT="$2"; shift 2;;
    --timeout) TIMEOUT="$2"; shift 2;; --driver) DRIVER="$2"; shift 2;; --depth) DEPTH="$2"; shift 2;;
    --port) PORT="$2"; shift 2;; --label) LABEL="$2"; shift 2;; --host-client) CLIENT_HOST="$2"; shift 2;;
    --ab) AB="$2"; shift 2;;
    --no-build) BUILD=0; shift;;
    -h|--help) sed -n '2,22p' "$0"; exit 0;;
    *) echo "unknown option $1" >&2; exit 64;;
  esac
done

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NETON="$ROOT/../neton-io"
cd "$ROOT"

case "$(uname -s)-$(uname -m)" in
  Darwin-arm64) TARGET=macosArm64;; Darwin-x86_64) TARGET=macosX64;;
  Linux-x86_64) TARGET=linuxX64;; Linux-aarch64) TARGET=linuxArm64;;
  *) echo "unsupported host $(uname -s)-$(uname -m)" >&2; exit 1;;
esac
TARGET_CAP="$(tr '[:lower:]' '[:upper:]' <<< "${TARGET:0:1}")${TARGET:1}"
BIN="$ROOT/msgtrans-transport/build/bin/$TARGET"
SERVER="$BIN/benchServerReleaseExecutable/benchServer.kexe"
CLIENT="$BIN/benchClientReleaseExecutable/benchClient.kexe"

if [[ $BUILD -eq 1 ]]; then
  echo "== building release binaries ($TARGET)"
  ./gradlew ":msgtrans-transport:linkBenchServerReleaseExecutable$TARGET_CAP" \
            ":msgtrans-transport:linkBenchClientReleaseExecutable$TARGET_CAP" --no-daemon -q
fi
[[ -x "$SERVER" && -x "$CLIENT" ]] || { echo "binaries missing: $SERVER / $CLIENT" >&2; exit 1; }

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="$ROOT/bench/results/$STAMP${LABEL:+-$LABEL}"
mkdir -p "$OUT"

sha() { if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }
gitinfo() { ( cd "$1" && printf '%s%s' "$(git rev-parse --short HEAD)" "$( [[ -n "$(git status --porcelain --untracked-files=no)" ]] && echo '-dirty' )" ); }
cpu() { if [[ "$(uname -s)" == Darwin ]]; then sysctl -n machdep.cpu.brand_string; else grep -m1 'model name' /proc/cpuinfo | cut -d: -f2 | sed 's/^ //'; fi; }
ncpu() { if [[ "$(uname -s)" == Darwin ]]; then sysctl -n hw.ncpu; else nproc; fi; }
loadavg() { uptime | sed 's/.*load average[s]*: //'; }

cat > "$OUT/meta.json" <<JSON
{
  "timestamp_utc": "$STAMP",
  "label": "$LABEL",
  "topology": "same-host (server and client share CPU, memory and the loopback stack)",
  "host": {"os": "$(uname -s) $(uname -r)", "arch": "$(uname -m)", "cpu": "$(cpu)", "ncpu": $(ncpu),
           "load_avg_at_start": "$(loadavg)", "note": "compare load_avg with ncpu: other work on the host contaminates same-host results"},
  "versions": {
    "msgtrans-kotlin": "$(gitinfo "$ROOT")",
    "neton-io": "$(gitinfo "$NETON")",
    "kotlin": "$(grep -o 'version "[0-9.]*"' "$ROOT/build.gradle.kts" | head -1 | cut -d'"' -f2)",
    "build_type": "release",
    "target": "$TARGET",
    "benchServer_sha256": "$(sha "$SERVER")",
    "benchClient_sha256": "$(sha "$CLIENT")"
  },
  "params": {"modes": "$MODES", "connections": $CONNS, "payload_bytes": $PAYLOAD, "inflight_per_connection": 1,
             "warmup_s": $WARMUP, "duration_s": $DURATION, "repeat": $REPEAT, "timeout_s": $TIMEOUT, "port": $PORT,
             "client_host": "$CLIENT_HOST"},
  "env": {"NETON_IO_DRIVER": "$DRIVER", "NETON_IO_URING_DEPTH": "$DEPTH"},
  "ab": "$AB"
}
JSON
echo "== results: $OUT"; cat "$OUT/meta.json"

export NETON_IO_DRIVER="$DRIVER" NETON_IO_URING_DEPTH="$DEPTH"
[[ -z "$DRIVER" ]] && unset NETON_IO_DRIVER
[[ -z "$DEPTH" ]] && unset NETON_IO_URING_DEPTH

wait_port() { for _ in $(seq 1 100); do nc -z 127.0.0.1 "$1" 2>/dev/null && return 0; sleep 0.1; done; return 1; }

FAILED=0
AB_VAR=""; AB_VALUES=("")
if [[ -n "$AB" ]]; then AB_VAR="${AB%%=*}"; IFS=',' read -ra AB_VALUES <<< "${AB#*=}"; fi
IFS=',' read -ra MODE_LIST <<< "$MODES"
for mode in "${MODE_LIST[@]}"; do
  for i in $(seq 1 "$REPEAT"); do
  for ab in "${AB_VALUES[@]}"; do
    tag="$mode-$i"
    if [[ -n "$AB_VAR" ]]; then tag="$mode-$ab-$i"; export "$AB_VAR=$ab"; fi
    "$SERVER" mode="$mode" host=0.0.0.0 port="$PORT" > "$OUT/server-$tag.log" 2>&1 &
    spid=$!
    if ! wait_port "$PORT"; then echo "server ($mode) did not come up" >&2; kill "$spid" 2>/dev/null || true; FAILED=1; continue; fi
    set +e
    "$CLIENT" mode="$mode" host="$CLIENT_HOST" port="$PORT" conns="$CONNS" payload="$PAYLOAD" \
      warmup="$WARMUP" duration="$DURATION" timeout="$TIMEOUT" label="$LABEL${ab:+ $AB_VAR=$ab}" out="$OUT/run-$tag.json" \
      > "$OUT/client-$tag.log" 2>&1
    rc=$?
    set -e
    # Server CPU time (cumulative) and resident size, sampled just before shutdown.
    ps -o rss=,time= -p "$spid" 2>/dev/null | awk '{printf "{\"server_rss_kb\": %s, \"server_cputime\": \"%s\", \"note\": \"ps sample at end of run\"}\n", $1, $2}' > "$OUT/server-$tag.json" || true
    kill "$spid" 2>/dev/null || true; wait "$spid" 2>/dev/null || true
    status="$(jq -r .status "$OUT/run-$tag.json" 2>/dev/null || echo 'no-json')"
    thr="$(jq -r .results.throughput_req_s "$OUT/run-$tag.json" 2>/dev/null || echo '?')"
    p99="$(jq -r .results.latency_us.p99 "$OUT/run-$tag.json" 2>/dev/null || echo '?')"
    echo "$tag: exit=$rc status=$status throughput=$thr req/s p99=${p99}us"
    [[ $rc -eq 0 ]] || FAILED=1
    sleep 0.5
  done
  done
done

echo "{\"load_avg_at_end\": \"$(loadavg)\"}" > "$OUT/load-end.json"
python3 "$ROOT/bench/summarize.py" "$OUT" | tee "$OUT/summary.md"
[[ $FAILED -eq 0 ]] || { echo "one or more runs failed; see $OUT" >&2; exit 1; }
