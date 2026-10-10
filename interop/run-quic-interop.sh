#!/bin/bash
# Interop between msgtrans-kotlin over QUIC (msgtrans-quic's InteropTest in the Kotlin test binary) and msgtrans-rust
# 2.0.0-beta.2 (rust-peer), both directions, then the rejection cases.
# Usage: run-quic-interop.sh <kotlin test.kexe> <rust peer binary> [port base]   (run gen-certs.sh first)
# Only processes started here are stopped (by their recorded PIDs). Exits non-zero if any case has an unexpected result.
set -u
K=$1
R=$2
P=${3:-24533}
D=$(cd "$(dirname "$0")" && pwd)
C=$D/certs
L=$D/logs
F='msgtrans.transport.InteropTest.*'
mkdir -p "$L"
FAILS=0

# ok <exit code> <case>: passes when the exit code is zero.
ok() {
  if [ "$1" = 0 ]; then echo "== PASS $2"; else echo "== FAIL $2 (exit $1)"; FAILS=$((FAILS + 1)); fi
}
# rejected <exit code> <log> <text> <case>: passes when the run failed and its log shows <text>, so a peer that never
# started does not count as a rejection.
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

# 1. Kotlin client -> Rust server
"$R" server 127.0.0.1:$P "$C/server.pem" "$C/server.key" 1 > "$L/1-rust-server.log" 2>&1 &
RPID=$!
sleep 1
MSGTRANS_QUIC_INTEROP=client MSGTRANS_QUIC_INTEROP_ADDR=127.0.0.1:$P MSGTRANS_QUIC_INTEROP_CA="$C/ca.pem" \
  "$K" --ktest_filter="$F" > "$L/1-kotlin-client.log" 2>&1
R1=$?
finish $RPID 15; R1S=$RC
ok $R1 "1. Kotlin client -> Rust server (Kotlin)"
ok $R1S "1. Kotlin client -> Rust server (Rust)"
grep -h "\[interop\]" "$L/1-kotlin-client.log" "$L/1-rust-server.log"

# 2. Rust client -> Kotlin server; 3. a Rust client trusting another CA is refused, and a good one is served afterwards
MSGTRANS_QUIC_INTEROP=server MSGTRANS_QUIC_INTEROP_ADDR=127.0.0.1:$((P + 1)) MSGTRANS_QUIC_INTEROP_CERT="$C/server.pem" \
  MSGTRANS_QUIC_INTEROP_KEY="$C/server.key" MSGTRANS_QUIC_INTEROP_SESSIONS=2 \
  "$K" --ktest_filter="$F" > "$L/2-kotlin-server.log" 2>&1 &
KPID=$!
sleep 2
"$R" client 127.0.0.1:$((P + 1)) localhost "$C/ca.pem" > "$L/2-rust-client.log" 2>&1
ok $? "2. Rust client -> Kotlin server (Rust)"
grep -h "\[interop\]" "$L/2-rust-client.log"
"$R" client 127.0.0.1:$((P + 1)) localhost "$C/other-ca.pem" > "$L/3a-rust-client.log" 2>&1
rejected $? "$L/3a-rust-client.log" "connect failed" "3a. Rust client trusting another CA -> Kotlin server"
grep -h "\[interop\]" "$L/3a-rust-client.log"
"$R" client 127.0.0.1:$((P + 1)) localhost "$C/ca.pem" > "$L/3b-rust-client.log" 2>&1
ok $? "3b. the Kotlin server still serves a good client afterwards"
# 45 s: longer than the 30 s idle timeout, after which a session whose client vanished without a close ends anyway.
finish $KPID 45; K2=$RC
ok $K2 "2/3. Kotlin server"
grep -h "\[interop\]\|OK \]\|FAILED \]" "$L/2-kotlin-server.log"

# 4. Kotlin client trusting another CA -> Rust server
"$R" server 127.0.0.1:$((P + 2)) "$C/server.pem" "$C/server.key" 1 > "$L/4-rust-server.log" 2>&1 &
RPID=$!
sleep 1
MSGTRANS_QUIC_INTEROP=client MSGTRANS_QUIC_INTEROP_ADDR=127.0.0.1:$((P + 2)) MSGTRANS_QUIC_INTEROP_CA="$C/other-ca.pem" \
  "$K" --ktest_filter="$F" > "$L/4-kotlin-client.log" 2>&1
rejected $? "$L/4-kotlin-client.log" "connect failed" "4. Kotlin client trusting another CA -> Rust server"
finish $RPID 1
grep -h "\[interop\]" "$L/4-kotlin-client.log"

echo "== $FAILS failure(s)"
[ $FAILS = 0 ]
