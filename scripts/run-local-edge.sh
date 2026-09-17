#!/usr/bin/env bash
# Stands up the FULL production topology locally: Floci with a real API Gateway and ALB in
# front of the services, exactly as scripts/setup-edge.sh builds it on the Oracle box.
#
#   ./scripts/run-local-edge.sh up      build everything
#   ./scripts/run-local-edge.sh test    e2e + a 1000-user load test through the gateway
#   ./scripts/run-local-edge.sh down    tear the edge down, leave the platform running
#
# ─────────────────────────────────────────────────────────────────────────────
# ONE HOSTNAME, OR NOTHING WORKS
#
# Keycloak stamps its own address into every token as the `iss` claim, and both the gateway
# and the services reject a token whose `iss` is not exactly what they were configured with.
# So Keycloak needs ONE name that resolves from three places: your browser, the service
# containers, and the Floci container.
#
# On the Oracle box Cloudflare provides that name for free. Locally, add one line:
#
#     echo "127.0.0.1 keycloak" | sudo tee -a /etc/hosts
#
# With it: the browser resolves keycloak:8080 to the published port, containers resolve it
# through Docker's DNS, and every token carries the same issuer. Without it you must choose
# between a working browser UI and a working gateway -- you cannot have both.
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail
cd "$(dirname "$0")/.."

NET=learn_default
GWHOST=learnapi.execute-api.localhost.floci.io
KC_URL=${KC_URL:-http://keycloak:8080}

aws() { docker run --rm --network "$NET" \
  -e AWS_ACCESS_KEY_ID=test -e AWS_SECRET_ACCESS_KEY=test -e AWS_REGION=us-east-1 \
  -e AWS_ENDPOINT_URL=http://floci:4566 amazon/aws-cli:latest "$@"; }
say() { printf '\n\033[1m%s\033[0m\n' "$1"; }

floci_ip() { docker inspect floci --format "{{(index .NetworkSettings.Networks \"$NET\").IPAddress}}"; }

case "${1:-up}" in

down)
  docker rm -f floci user-service task-service >/dev/null 2>&1 || true
  echo "edge removed; postgres/keycloak/mailpit still running"
  exit 0 ;;

up)
  say "1 · services as containers (so the ALB can target real container IPs)"
  ./gradlew :user-service:bootJar :task-service:bootJar -q --console=plain
  for svc in user-service task-service; do
    docker build -f "$svc/Dockerfile" -t "$svc:local" --platform linux/arm64 -q . >/dev/null
  done
  for p in 7701 7702; do
    pid=$(lsof -nP -iTCP:$p -sTCP:LISTEN -t 2>/dev/null || true); [ -n "$pid" ] && kill -9 $pid || true
  done
  docker rm -f user-service task-service >/dev/null 2>&1 || true
  run_svc() {
    docker run -d --name "$1" --network "$NET" -p "$2:$2" \
      -e SERVER_PORT="$2" -e DB_URL="jdbc:postgresql://postgres:5432/$3" \
      -e DB_USERNAME=postgres -e DB_PASSWORD=postgres -e DB_POOL_MAX=40 -e DB_POOL_MIN=10 \
      -e KEYCLOAK_URL="$KC_URL" -e KEYCLOAK_REALM=p-platform \
      -e KEYCLOAK_CLIENT_ID=user-service -e KEYCLOAK_CLIENT_SECRET=user-service-secret \
      -e MAIL_HOST=mailpit -e MAIL_PORT=1025 -e CORS_ALLOWED_ORIGINS=http://localhost:3000 \
      -e SPRING_PROFILES_ACTIVE=local -e OTLP_ENDPOINT=http://localhost:4318 -e TRACE_SAMPLE_RATE=0.1 \
      "$1:local" >/dev/null
  }
  run_svc user-service 7701 userdb
  run_svc task-service 7702 taskdb
  for i in $(seq 1 90); do
    a=$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 http://localhost:7701/actuator/health || true)
    b=$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 http://localhost:7702/actuator/health || true)
    [ "$a" = 200 ] && [ "$b" = 200 ] && break; sleep 1
  done
  US=$(docker inspect user-service --format "{{(index .NetworkSettings.Networks \"$NET\").IPAddress}}")
  TS=$(docker inspect task-service --format "{{(index .NetworkSettings.Networks \"$NET\").IPAddress}}")
  echo "  user-service $US:7701 · task-service $TS:7702"

  say "2 · floci"
  docker rm -f floci >/dev/null 2>&1 || true
  docker run -d --name floci --network "$NET" -p 127.0.0.1:4566:4566 \
    -v /var/run/docker.sock:/var/run/docker.sock -v floci-local:/app/data \
    -e FLOCI_STORAGE_MODE=persistent -e FLOCI_HOSTNAME=floci -e FLOCI_BASE_URL=http://floci:4566 \
    floci/floci:latest >/dev/null
  for i in $(seq 1 60); do
    [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 http://localhost:4566/_floci/health || true)" = 200 ] && break; sleep 2
  done
  FIP=$(floci_ip); echo "  floci $FIP:4566"

  say "3 · ALB — target-type ip, because k3s NodePorts are not EC2 instances"
  mk_tg() { aws elbv2 create-target-group --name "$1" --protocol HTTP --port "$2" \
      --target-type ip --vpc-id vpc-1 --health-check-path /actuator/health \
      --health-check-interval-seconds 10 --healthy-threshold-count 2 \
      --query 'TargetGroups[0].TargetGroupArn' --output text | tr -d '\r'; }
  TGU=$(mk_tg user-service-tg 7701); TGT=$(mk_tg task-service-tg 7702)
  aws elbv2 register-targets --target-group-arn "$TGU" --targets "Id=$US,Port=7701" >/dev/null
  aws elbv2 register-targets --target-group-arn "$TGT" --targets "Id=$TS,Port=7702" >/dev/null
  LB=$(aws elbv2 create-load-balancer --name learn-alb --type application --scheme internet-facing \
        --query 'LoadBalancers[0].LoadBalancerArn' --output text | tr -d '\r')
  LSN=$(aws elbv2 create-listener --load-balancer-arn "$LB" --protocol HTTP --port 8088 \
        --default-actions "Type=forward,TargetGroupArn=$TGU" \
        --query 'Listeners[0].ListenerArn' --output text | tr -d '\r')
  for pri in 10:/api/v1/tasks 20:/api/v1/admin/tasks; do
    aws elbv2 create-rule --listener-arn "$LSN" --priority "${pri%%:*}" \
      --conditions "Field=path-pattern,Values=${pri#*:}*" \
      --actions "Type=forward,TargetGroupArn=$TGT" >/dev/null
  done
  echo "  listener :8088 · default → user-service · /tasks* → task-service"

  say "4 · API Gateway"
  aws apigatewayv2 delete-api --api-id learnapi >/dev/null 2>&1 || true
  aws apigatewayv2 create-api --name learn-api --protocol-type HTTP \
    --tags 'floci:override-id=learnapi' >/dev/null
  # CORS on the API: a browser preflight carries no Authorization header, so without this
  # the authorizer answers 401 and the UI fails with a CORS error that never mentions auth.
  aws apigatewayv2 update-api --api-id learnapi --cors-configuration \
    'AllowOrigins=http://localhost:3000,AllowMethods=GET,POST,PUT,PATCH,DELETE,OPTIONS,AllowHeaders=authorization,content-type,MaxAge=600' >/dev/null
  AUTH=$(aws apigatewayv2 create-authorizer --api-id learnapi --name keycloak-jwt \
    --authorizer-type JWT --identity-source '$request.header.Authorization' \
    --jwt-configuration "Issuer=$KC_URL/realms/p-platform,Audience=learn-api" \
    --query AuthorizerId --output text | tr -d '\r')
  ALB="http://$FIP:8088"
  mkint() { aws apigatewayv2 create-integration --api-id learnapi --integration-type HTTP_PROXY \
    --integration-method ANY --payload-format-version 1.0 --integration-uri "$1" \
    --query IntegrationId --output text | tr -d '\r'; }
  route() { local id; id=$(mkint "$2")
    if [ "$3" = open ]; then
      aws apigatewayv2 create-route --api-id learnapi --route-key "$1" --target "integrations/$id" --authorization-type NONE >/dev/null
    else
      aws apigatewayv2 create-route --api-id learnapi --route-key "$1" --target "integrations/$id" --authorization-type JWT --authorizer-id "$AUTH" >/dev/null
    fi; }
  route 'POST /api/v1/auth/register'       "$ALB/api/v1/auth/register"       open
  route 'ANY /api/v1/users/{proxy+}'       "$ALB/api/v1/users/{proxy}"       jwt
  route 'GET /api/v1/admin/users'          "$ALB/api/v1/admin/users"         jwt
  route 'ANY /api/v1/admin/users/{proxy+}' "$ALB/api/v1/admin/users/{proxy}" jwt
  route 'ANY /api/v1/tasks'                "$ALB/api/v1/tasks"               jwt
  route 'ANY /api/v1/tasks/{proxy+}'       "$ALB/api/v1/tasks/{proxy}"       jwt
  route 'ANY /api/v1/admin/tasks'          "$ALB/api/v1/admin/tasks"         jwt
  route 'ANY /api/v1/admin/tasks/{proxy+}' "$ALB/api/v1/admin/tasks/{proxy}" jwt
  aws apigatewayv2 create-stage --api-id learnapi --stage-name '$default' --auto-deploy >/dev/null
  echo "  8 routes · 1 open (register) · \$default stage"

  say "ready"
  echo "  gateway:  http://$GWHOST:4566   (resolves to 127.0.0.1)"
  echo "  no token: $(curl -s -o /dev/null -w '%{http_code}' "http://$GWHOST:4566/api/v1/tasks")  ← expect 401"
  ;;

test)
  FIP=$(floci_ip)
  say "e2e through the gateway"
  docker run --rm --network "$NET" --add-host "$GWHOST:$FIP" -v "$PWD:/work" -w /work learn-testrunner \
    bash -c "API=http://$GWHOST:4566 KC=$KC_URL ./scripts/e2e.sh" | tail -2
  docker run --rm --network "$NET" --add-host "$GWHOST:$FIP" -v "$PWD:/work" -w /work learn-testrunner \
    bash -c "USERAPI=http://$GWHOST:4566 TASKAPI=http://$GWHOST:4566 KC=$KC_URL ./scripts/e2e-tasks.sh" | tail -2
  say "1,000 users through Gateway → ALB → services"
  docker run --rm --network "$NET" --add-host "$GWHOST:$FIP" -v "$PWD/scripts:/s" \
    -e GW="http://$GWHOST:4566" -e KC="$KC_URL" -e USERS=1000 -e VUS=50 -e RUN="load-$(date +%s)" \
    grafana/k6 run --no-usage-report --summary-trend-stats="avg,p(50),p(95),p(99)" /s/load-1k.js
  ;;

*) echo "usage: $0 [up|test|down]"; exit 1 ;;
esac
