#!/usr/bin/env bash
# Cross-language wire interop check against the Rust msgtrans echo example.
#
# Verifies both directions over TCP:
#   1. Kotlin benchClient (framed, then rpc) -> Rust echo_server (passive, byte-for-byte).
#   2. Rust echo_client_tcp -> Kotlin benchServer (rpc echo).
#
# Rust repo defaults to ../../privchat/msgtrans (override with $MSGTRANS_RUST). Needs cargo + a built
# Kotlin toolchain. Only processes this script starts are killed (recorded PIDs).
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUST="${MSGTRANS_RUST:-$ROOT/../../privchat/msgtrans}"
TARGET="${TARGET:-macosArm64}"
TARGET_CAP="$(printf %s "${TARGET:0:1}" | tr "[:lower:]" "[:upper:]")${TARGET:1}"
PASSIVE_PORT=18091
KSERVER_PORT=8001
BIN="$ROOT/msgtrans-bench/build/bin/$TARGET"
CLIENT="$BIN/benchClientReleaseExecutable/benchClient.kexe"
SERVER="$BIN/benchServerReleaseExecutable/benchServer.kexe"
pids=(); cleanup() { for p in "${pids[@]:-}"; do [ -n "$p" ] && kill "$p" 2>/dev/null; done; }
trap cleanup EXIT
fail() { echo "INTEROP FAIL: $*" >&2; exit 1; }

[ -d "$RUST" ] || fail "Rust msgtrans not found at $RUST (set MSGTRANS_RUST)"
command -v cargo >/dev/null || fail "cargo not found"
command -v jq >/dev/null || fail "jq not found"

echo "== building Rust echo_server + Kotlin bench binaries"
( cd "$RUST" && cargo build --example echo_server --example echo_client_tcp ) >/tmp/interop-rustbuild.log 2>&1 || fail "rust build (see /tmp/interop-rustbuild.log)"
( cd "$ROOT" && ./gradlew ":msgtrans-bench:linkBenchClientReleaseExecutable${TARGET_CAP}" ":msgtrans-bench:linkBenchServerReleaseExecutable${TARGET_CAP}" --no-daemon -q ) || fail "kotlin build"

wait_port() { for _ in $(seq 1 80); do nc -z 127.0.0.1 "$1" 2>/dev/null && return 0; sleep 0.25; done; return 1; }
check_ok() { local s; s=$(jq -r .status <<<"$1"); local e; e=$(jq -r '.results.errors | length' <<<"$1"); [ "$s" = ok ] && [ "$e" = 0 ]; }

echo "== direction 1: Kotlin client -> Rust passive echo server (:$PASSIVE_PORT)"
MSGTRANS_ECHO_MODE=passive MSGTRANS_ECHO_TCP_PORT=$PASSIVE_PORT "$RUST/target/debug/examples/echo_server" >/tmp/interop-rustsrv.log 2>&1 &
pids+=($!)
wait_port $PASSIVE_PORT || fail "rust passive server did not come up"
for mode in framed rpc; do
  out=$("$CLIENT" mode=$mode host=127.0.0.1 port=$PASSIVE_PORT conns=4 warmup=0.3 duration=1 payload=64)
  check_ok "$out" || fail "kotlin $mode client vs rust server: $(jq -c '{status,errors:.results.errors}' <<<"$out")"
  echo "   [$mode] ok: $(jq -r '.results.measured_requests' <<<"$out") req/resp, 0 errors"
done

echo "== direction 2: Rust client -> Kotlin rpc echo server (:$KSERVER_PORT)"
"$SERVER" mode=rpc host=127.0.0.1 port=$KSERVER_PORT >/tmp/interop-ksrv.log 2>&1 &
pids+=($!)
wait_port $KSERVER_PORT || fail "kotlin server did not come up"
timeout 20 "$RUST/target/debug/examples/echo_client_tcp" >/tmp/interop-rustcli.log 2>&1
grep -q "Response received: What time is it?" /tmp/interop-rustcli.log || fail "rust client got no correct text response (see /tmp/interop-rustcli.log)"
grep -q "Content: Binary request" /tmp/interop-rustcli.log || fail "rust client got no correct binary response"
echo "   ok: rust client received correct echoed responses from the Kotlin server"

echo "INTEROP PASS (both directions)"
