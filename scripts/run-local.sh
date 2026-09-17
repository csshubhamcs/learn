#!/usr/bin/env bash
# Start the whole platform locally: Postgres (one cluster, three databases), Keycloak,
# user-service, task-service. Idempotent -- safe to re-run; it replaces whatever is running.
#
#   ./scripts/run-local.sh          start everything
#   ./scripts/run-local.sh stop     stop the two Java services (leaves docker up)
#
# learn-ui is started separately with `npm run dev` in the learn-ui repo.
set -uo pipefail
cd "$(dirname "$0")/.."

LOGS=${LOGS:-/tmp/learn-logs}
mkdir -p "$LOGS"

# Ports are freed by PID, never by pattern: `pkill -f bootRun` does not match a
# `java -jar` started earlier, and a stale jar on the port looks exactly like a code bug.
free_port() {
  local pid; pid=$(lsof -nP -iTCP:"$1" -sTCP:LISTEN -t 2>/dev/null)
  [ -n "$pid" ] && { kill -9 $pid 2>/dev/null; echo "  freed :$1 (was pid $pid)"; }
}

if [ "${1:-start}" = "stop" ]; then
  free_port 7701; free_port 7702; echo "services stopped"; exit 0
fi

echo "== docker =="
docker compose up -d
for i in $(seq 1 60); do
  [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 http://localhost:8080/realms/p-platform)" = "200" ] \
    && { echo "  keycloak ready"; break; }
  sleep 3
done

echo "== build =="
./gradlew build -q --console=plain || { echo "BUILD FAILED"; exit 1; }

echo "== services =="
free_port 7701; free_port 7702

# Shared config. Nothing here has a default in application.properties, so a missing value
# fails the service at startup instead of silently running against the wrong thing.
export KEYCLOAK_URL=http://localhost:8080 KEYCLOAK_REALM=p-platform \
       KEYCLOAK_CLIENT_ID=user-service KEYCLOAK_CLIENT_SECRET=user-service-secret \
       DB_USERNAME=postgres DB_PASSWORD=postgres DB_POOL_MAX=20 DB_POOL_MIN=5 \
       MAIL_HOST=localhost MAIL_PORT=1025 \
       CORS_ALLOWED_ORIGINS=http://localhost:3000 \
       SPRING_PROFILES_ACTIVE=local OTLP_ENDPOINT=http://localhost:4318 TRACE_SAMPLE_RATE=0.1

SERVER_PORT=7701 DB_URL=jdbc:postgresql://localhost:5432/userdb \
  nohup java -jar user-service/build/libs/user-service.jar > "$LOGS/user-service.log" 2>&1 &
SERVER_PORT=7702 DB_URL=jdbc:postgresql://localhost:5432/taskdb \
  nohup java -jar task-service/build/libs/task-service.jar > "$LOGS/task-service.log" 2>&1 &

for i in $(seq 1 90); do
  a=$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 http://localhost:7701/actuator/health)
  b=$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 http://localhost:7702/actuator/health)
  [ "$a" = "200" ] && [ "$b" = "200" ] && break
  sleep 1
done

printf "\n%-14s %s\n" "keycloak"     "http://localhost:8080  (admin/admin)"
printf "%-14s %s\n"   "user-service" "http://localhost:7701  $(curl -s http://localhost:7701/actuator/health)"
printf "%-14s %s\n"   "task-service" "http://localhost:7702  $(curl -s http://localhost:7702/actuator/health)"
printf "%-14s %s\n"   "mailpit"      "http://localhost:8025"
printf "%-14s %s\n"   "logs"         "$LOGS"
