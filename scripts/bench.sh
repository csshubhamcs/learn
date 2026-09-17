#!/usr/bin/env bash
# Scaling ladder: run the read path at increasing rates and capture what the system is doing
# at each step, so you can see WHERE it stops scaling rather than just whether it hit a target.
#
#   ./scripts/bench.sh                    # 100 -> 250 -> 500 -> 1000 -> 2000
#   RATES="100 1000" ./scripts/bench.sh
set -uo pipefail

API=${API:-http://localhost:7701}
KC=${KC:-http://localhost:8080}
REALM=${REALM:-p-platform}
RATES=${RATES:-"100 250 500 1000 2000"}   # read path ladder
DUR=${DUR:-30s}
OUT=/tmp/bench-$(date +%s); mkdir -p "$OUT"

command -v k6 >/dev/null || { echo "k6 not installed"; exit 1; }
curl -sf "$API/actuator/health/readiness" >/dev/null 2>&1 || echo "note: readiness not 200 (expected if SecurityConfig not yet loaded)"

ADMIN_T=$(curl -s -X POST "$KC/realms/$REALM/protocol/openid-connect/token" \
  -d client_id=web-app -d grant_type=password \
  -d username=alice@example.com -d 'password=Password123!' \
  | python3 -c "import sys,json;print(json.load(sys.stdin).get('access_token',''))" 2>/dev/null)

metric() { # metric <prometheus-name> [label-filter]
  curl -s -H "Authorization: Bearer $ADMIN_T" "$API/actuator/prometheus" 2>/dev/null \
    | grep -E "^$1" | { [ -n "${2:-}" ] && grep "$2" || cat; } \
    | awk '{s+=$NF} END {printf "%.0f", s}'
}
pgconns() {
  docker compose exec -T postgres psql -U postgres -d userdb -tAc \
    "SELECT count(*) FROM pg_stat_activity WHERE datname='userdb';" 2>/dev/null | tr -d ' \n'
}
cpuof() { docker stats --no-stream --format '{{.Name}} {{.CPUPerc}} {{.MemUsage}}' 2>/dev/null | grep -E 'postgres|keycloak' | tr '\n' ' | '; }

echo "═══ Scaling ladder — read path (GET /users/me, local JWT validation) ═══"
echo "cores: $(sysctl -n hw.ncpu 2>/dev/null || nproc)   duration per step: $DUR"
echo

printf "%-8s %-12s %-9s %-9s %-9s %-8s %-9s %-7s %-6s\n" \
  RATE ACHIEVED/s p50ms p95ms p99ms FAIL% DROPPED HEAP_MB PG_CONN
printf '%.0s─' {1..90}; echo

for R in $RATES; do
  SUMMARY_OUT="$OUT/read-$R.json" \
  k6 run -q --no-usage-report \
      -e SCENARIO=read -e RATE="$R" -e API="$API" -e KC="$KC" \
      scripts/load-test.js > "$OUT/k6-$R.log" 2>&1

  if [ ! -f "$OUT/read-$R.json" ]; then
    printf "%-8s %s\n" "$R" "FAILED — see $OUT/k6-$R.log"; continue
  fi
  HEAP=$(( $(metric jvm_memory_used_bytes 'area="heap"') / 1048576 ))
  PGC=$(pgconns)
  python3 - "$OUT/read-$R.json" "$HEAP" "${PGC:-?}" <<'PY'
import json,sys
d=json.load(open(sys.argv[1]))
print("%-8s %-12s %-9s %-9s %-9s %-8s %-9s %-7s %-6s" % (
  d["target_rate"],
  d["achieved_rps"] if d["achieved_rps"] is not None else "-",
  d["p50_ms"], d["p95_ms"], d["p99_ms"],
  round((d["failed_rate"] or 0)*100, 2),
  int(d["dropped"] or 0), sys.argv[2], sys.argv[3]))
PY
  echo "      containers: $(cpuof)" >> "$OUT/containers.log"
  sleep 5
done

echo
echo "═══ Login ceiling — ramping 10 -> 400/s until it stops keeping up ═══"
SUMMARY_OUT="$OUT/login-max.json" k6 run -q --no-usage-report \
  -e SCENARIO=login_max -e API="$API" -e KC="$KC" \
  scripts/load-test.js > "$OUT/k6-login-max.log" 2>&1
if [ -f "$OUT/login-max.json" ]; then
  python3 -c "
import json
d=json.load(open('$OUT/login-max.json'))
print(f\"  sustained {d['achieved_rps']}/s   p95 {d['p95_ms']}ms   p99 {d['p99_ms']}ms   dropped {int(d['dropped'] or 0)}\")"
else echo "  see $OUT/k6-login-max.log"; fi

echo
echo "═══ Concurrent logins — N users hitting login at the same instant ═══"
for V in 50 100 200; do
  SUMMARY_OUT="$OUT/login-c$V.json" k6 run -q --no-usage-report \
    -e SCENARIO=login_concurrent -e VUS=$V -e API="$API" -e KC="$KC" \
    scripts/load-test.js > "$OUT/k6-login-c$V.log" 2>&1
  if [ -f "$OUT/login-c$V.json" ]; then
    python3 -c "
import json
d=json.load(open('$OUT/login-c$V.json'))
print(f\"  {$V:>4} concurrent -> {d['achieved_rps']}/s   p50 {d['p50_ms']}ms   p95 {d['p95_ms']}ms   fail {round((d['failed_rate'] or 0)*100,1)}%\")"
  fi
done

echo
echo "═══ Login path at a fixed rate ═══"
SUMMARY_OUT="$OUT/login.json" k6 run -q --no-usage-report \
  -e SCENARIO=login -e RATE=200 -e API="$API" -e KC="$KC" \
  scripts/load-test.js > "$OUT/k6-login.log" 2>&1
[ -f "$OUT/login.json" ] && python3 -c "
import json,sys
d=json.load(open('$OUT/login.json'))
print(f\"  achieved {d['achieved_rps']}/s  p95 {d['p95_ms']}ms  failed {round((d['failed_rate'] or 0)*100,2)}%\")
print('  This number is SUPPOSED to be far lower than the read path — bcrypt is deliberately slow.')
print('  Keycloak publishes ~15 password logins/sec per vCPU.')" || echo "  login run failed — see $OUT/k6-login.log"

echo
echo "artifacts: $OUT"
echo "Read the table for the KNEE — the rate where achieved stops tracking target, or p95 turns up."
echo "That, not the biggest number you can type, is your capacity."
