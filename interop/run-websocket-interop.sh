#!/bin/bash
# Interop between msgtrans-kotlin over WebSocket (msgtrans-websocket's InteropTest in the Kotlin test binary) and
# msgtrans-rust 2.0.0-beta.2 (rust-peer), both directions over ws://, the Rust client over wss:// to the Kotlin server,
# and the rejection cases.
# Usage: run-websocket-interop.sh <kotlin test.kexe> <rust peer binary> [port base]   (run gen-certs.sh first)
# Only processes started here are stopped (by their recorded PIDs). Exits non-zero if any case has an unexpected result.
set -u
K=$1
R=$2
P=${3:-24633}
D=$(cd "$(dirname "$0")" && pwd)
C=$D/certs
L=$D/logs
F='msgtrans.transport.InteropTest.*'
mkdir -p "$L"
FAILS=0

ok() {
  if [ "$1" = 0 ]; then echo "== PASS $2"; else echo "== FAIL $2 (exit $1)"; FAILS=$((FAILS + 1)); fi
}
rejected() {
  if [ "$1" != 0 ] && grep -q "$3" "$2"; then echo "== PASS $4 (exit $1: $3)"
  else echo "== FAIL $4 (exit $1, expected a failure with '$3' in $2)"; FAILS=$((FAILS + 1)); fi
}
# finish <pid> <seconds>: wait for a server started here to exit on its own, else stop it; its exit code goes to RC.
# Runs in this shell, not in $(...): a subshell cannot wait for this shell's children, and whether it got the code
# depended on whether this shell had already reaped the child (an occasional false failure).
finish() {
  for _ in $(seq 1 "$2"); do kill -0 "$1" 2>/dev/null || break; sleep 1; done
  kill "$1" 2>/dev/null
  wait "$1" 2>/dev/null
  RC=$?
}

# 1. Kotlin client -> Rust server (ws://)
"$R" ws-server 127.0.0.1:$P 1 > "$L/ws1-rust-server.log" 2>&1 &
RPID=$!
sleep 1
MSGTRANS_WS_INTEROP=client MSGTRANS_WS_INTEROP_URL=ws://127.0.0.1:$P/ "$K" --ktest_filter="$F" > "$L/ws1-kotlin-client.log" 2>&1
R1=$?
finish $RPID 15; R1S=$RC
ok $R1 "1. Kotlin client -> Rust server, ws (Kotlin)"
ok $R1S "1. Kotlin client -> Rust server, ws (Rust)"
grep -h "\[interop\]" "$L/ws1-kotlin-client.log" "$L/ws1-rust-server.log"

# 2. Rust client -> Kotlin server (ws://); 2b. another path is refused
MSGTRANS_WS_INTEROP=server MSGTRANS_WS_INTEROP_ADDR=127.0.0.1:$((P + 1)) MSGTRANS_WS_INTEROP_SESSIONS=1 \
  "$K" --ktest_filter="$F" > "$L/ws2-kotlin-server.log" 2>&1 &
KPID=$!
sleep 2
"$R" ws-client ws://127.0.0.1:$((P + 1))/other > "$L/ws2b-rust-client.log" 2>&1
rejected $? "$L/ws2b-rust-client.log" "connect failed" "2b. Rust client on another path -> Kotlin server"
grep -h "\[interop\]" "$L/ws2b-rust-client.log"
"$R" ws-client ws://127.0.0.1:$((P + 1))/ > "$L/ws2-rust-client.log" 2>&1
ok $? "2. Rust client -> Kotlin server, ws (Rust)"
grep -h "\[interop\]" "$L/ws2-rust-client.log"
finish $KPID 45; K2=$RC
ok $K2 "2. Rust client -> Kotlin server, ws (Kotlin)"
grep -h "\[interop\]\|OK \]\|FAILED \]" "$L/ws2-kotlin-server.log"

# 3. Rust client -> Kotlin server over wss://; 3b. a Rust client trusting another CA is refused
MSGTRANS_WS_INTEROP=server MSGTRANS_WS_INTEROP_ADDR=127.0.0.1:$((P + 2)) MSGTRANS_WS_INTEROP_SESSIONS=1 \
  MSGTRANS_WS_INTEROP_CERT="$C/server.pem" MSGTRANS_WS_INTEROP_KEY="$C/server.key" \
  "$K" --ktest_filter="$F" > "$L/ws3-kotlin-server.log" 2>&1 &
KPID=$!
sleep 2
"$R" ws-client wss://localhost:$((P + 2))/ "$C/other-ca.pem" > "$L/ws3b-rust-client.log" 2>&1
rejected $? "$L/ws3b-rust-client.log" "connect failed" "3b. Rust client trusting another CA -> Kotlin server, wss"
grep -h "\[interop\]" "$L/ws3b-rust-client.log"
"$R" ws-client wss://localhost:$((P + 2))/ "$C/ca.pem" > "$L/ws3-rust-client.log" 2>&1
ok $? "3. Rust client -> Kotlin server, wss (Rust)"
grep -h "\[interop\]" "$L/ws3-rust-client.log"
finish $KPID 45; K3=$RC
ok $K3 "3. Rust client -> Kotlin server, wss (Kotlin)"
grep -h "\[interop\]\|OK \]\|FAILED \]" "$L/ws3-kotlin-server.log"

echo "== $FAILS failure(s)"
[ $FAILS = 0 ]
