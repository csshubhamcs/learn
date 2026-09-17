#!/usr/bin/env bash
# Export the OpenAPI spec and build browsable API docs.
#
#   ./scripts/api-docs.sh          boot the app, export spec, build static HTML
#   ./scripts/api-docs.sh --spec   export the spec only (app must already be running)
#
# Output: docs/openapi.json  and  docs/api.html (Redoc, self-contained)
set -uo pipefail
API=${API:-http://localhost:7701}
OUT=docs; mkdir -p "$OUT"

started_here=0
if ! curl -sf "$API/actuator/health/readiness" >/dev/null 2>&1; then
  [ "${1:-}" = "--spec" ] && { echo "app not running on $API"; exit 1; }
  echo "starting app..."
  (nohup ./gradlew :user-service:bootRun --no-daemon -q > /tmp/apidocs-app.log 2>&1 &)
  started_here=1
  until curl -sf "$API/actuator/health/readiness" >/dev/null 2>&1; do sleep 3; done
fi

echo "exporting spec..."
curl -sf "$API/v3/api-docs" -o "$OUT/openapi.json" || { echo "failed to fetch /v3/api-docs"; exit 1; }
python3 -m json.tool "$OUT/openapi.json" > "$OUT/openapi.pretty.json" && mv "$OUT/openapi.pretty.json" "$OUT/openapi.json"

python3 - "$OUT/openapi.json" <<'PY'
import json,sys
spec=json.load(open(sys.argv[1]))
paths=spec.get("paths",{})

# What we audit, and what we deliberately do NOT:
#   REQUIRED per endpoint: summary, description, and its success response.
#   NOT required per endpoint: 401/403/500. Those apply to every authenticated
#   operation and are added once by an OpenApiCustomizer. Repeating them as
#   annotations on every method is copy-paste noise, and declaring an error an
#   endpoint cannot actually return (503 on a read that never calls Keycloak)
#   is wrong documentation, not thorough documentation.
GLOBAL = {"401","403","500"}
ops=gaps=0; issues=[]
for path,methods in paths.items():
    for verb,op in methods.items():
        if verb not in ("get","post","put","patch","delete"): continue
        ops+=1
        miss=[]
        if not op.get("summary"):     miss.append("summary")
        if not op.get("description"): miss.append("description")
        codes={c for c in op.get("responses",{}) if c.isdigit()}
        if not any(c.startswith("2") for c in codes): miss.append("success response")
        if miss:
            gaps+=1; issues.append(f"  {verb.upper():6} {path}  -> {', '.join(miss)}")

comps=spec.get("components",{}).get("schemas",{})
shared_error = any(k.lower().startswith("apierror") for k in comps)

print(f"\n{ops} operations | {ops-gaps} documented | {gaps} with gaps")
print(f"shared ApiError schema component: {'yes' if shared_error else 'NO -- error bodies are being inlined per response'}")
globals_present = sum(1 for p_ in paths.values() for o in p_.readOperations() ) if False else None
if issues:
    print("\nGaps:"); print("\n".join(issues))
    print("\nEach endpoint needs @Operation(summary, description). Error codes that are")
    print("universal come from the OpenApiCustomizer; annotate only codes unique to the")
    print("endpoint (409 on register, 410 on expired token, 503 where Keycloak is called).")
else:
    print("Every endpoint has a summary, a description and a documented success response.")
PY

if command -v npx >/dev/null 2>&1; then
  echo "building static docs with redocly..."
  npx --yes @redocly/cli@latest build-docs "$OUT/openapi.json" -o "$OUT/api.html" 2>/dev/null \
    && echo "wrote $OUT/api.html (self-contained, open it directly)" \
    || echo "redocly build failed -- spec is still at $OUT/openapi.json"
else
  echo "npx unavailable; spec at $OUT/openapi.json (import into redocly.com or any OpenAPI viewer)"
fi

echo "Swagger UI (while running): $API/swagger-ui.html"
[ "$started_here" = "1" ] && { pkill -f "com.learn.userservice.UserServiceApplication" 2>/dev/null; echo "stopped app"; }
exit 0
