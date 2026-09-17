#!/usr/bin/env bash
# End-to-end functional test for task-service against a running user-service + Keycloak.
# Registers real users through user-service, gets real Keycloak tokens, and drives the
# task API with them -- so ownership and role gating are tested the way a client hits them.
#
#   ./scripts/e2e-tasks.sh
#
# Requires: docker compose stack up, user-service on :7701, task-service on :7702.
set -uo pipefail

USERAPI=${USERAPI:-http://localhost:7701}
TASKAPI=${TASKAPI:-http://localhost:7702}
KC=${KC:-http://localhost:8080}
REALM=${REALM:-p-platform}
PW='Password123!'
RUN=$(date +%s)

PASS=0; FAIL=0
ok()   { PASS=$((PASS+1)); printf "  \033[32m✓\033[0m %s\n" "$1"; }
bad()  { FAIL=$((FAIL+1)); printf "  \033[31m✗\033[0m %s\n" "$1"; printf "      expected: %s\n      actual:   %s\n" "$2" "$3"; }
check(){ [ "$2" = "$3" ] && ok "$1" || bad "$1" "$2" "$3"; }
head1(){ printf "\n\033[1m%s\033[0m\n" "$1"; }
jget() { python3 -c "import sys,json;d=json.load(sys.stdin);print(d$1)" 2>/dev/null; }

token() {
  curl -s -X POST "$KC/realms/$REALM/protocol/openid-connect/token" \
    -d client_id=web-app -d grant_type=password -d "username=$1" -d "password=$2" \
  | jget "['access_token']"
}
# code <method> <path> <token> [body] -- prints HTTP status only
code() {
  local m=$1 p=$2 t=${3:-} b=${4:-}
  if [ -n "$b" ]; then
    curl -s -o /dev/null -w '%{http_code}' -X "$m" "$TASKAPI$p" \
      -H "Authorization: Bearer $t" -H 'Content-Type: application/json' -d "$b"
  else
    curl -s -o /dev/null -w '%{http_code}' -X "$m" "$TASKAPI$p" -H "Authorization: Bearer $t"
  fi
}
# body <method> <path> <token> [body] -- prints response body only
body() {
  local m=$1 p=$2 t=${3:-} b=${4:-}
  if [ -n "$b" ]; then
    curl -s -X "$m" "$TASKAPI$p" -H "Authorization: Bearer $t" \
      -H 'Content-Type: application/json' -d "$b"
  else
    curl -s -X "$m" "$TASKAPI$p" -H "Authorization: Bearer $t"
  fi
}
register() {
  curl -s -o /dev/null -X POST "$USERAPI/api/v1/auth/register" \
    -H 'Content-Type: application/json' \
    -d "$(printf '{"email":"%s","password":"%s"}' "$1" "$PW")"
}

# A service-account token for the Keycloak Admin API. Needed to build a user holding
# ADMIN and *not* USER -- the realm's seeded admin (alice) holds both, so she cannot show
# that a user route refuses an admin-only principal.
sa_token() {
  curl -s -X POST "$KC/realms/$REALM/protocol/openid-connect/token" \
    -d client_id=user-service -d client_secret=user-service-secret \
    -d grant_type=client_credentials | jget "['access_token']"
}
kc() { # kc <method> <path> [body]
  local m=$1 p=$2 b=${3:-}
  if [ -n "$b" ]; then
    curl -s -X "$m" "$KC/admin/realms/$REALM$p" -H "Authorization: Bearer $SA" \
      -H 'Content-Type: application/json' -d "$b"
  else
    curl -s -X "$m" "$KC/admin/realms/$REALM$p" -H "Authorization: Bearer $SA"
  fi
}

BOB="bob-$RUN@test.local"
CAROL="carol-$RUN@test.local"
ADMINONLY="adminonly-$RUN@test.local"

head1 "1. Setup — two real users via user-service"
register "$BOB"; register "$CAROL"
TB=$(token "$BOB" "$PW"); TC=$(token "$CAROL" "$PW")
TA=$(token alice@example.com "$PW")   # the realm's seeded admin -- holds ADMIN *and* USER
[ -n "$TB" ] && ok "bob has a token"   || bad "bob has a token" "non-empty" "empty"
[ -n "$TC" ] && ok "carol has a token" || bad "carol has a token" "non-empty" "empty"
[ -n "$TA" ] && ok "admin has a token" || bad "admin has a token" "non-empty" "empty"

# Build ADMINONLY: register through user-service, then swap the roles. USER is NOT a direct
# mapping -- it is a composite of default-roles-p-platform, which every new user gets. So the
# role to strip is the composite; deleting the USER mapping itself silently does nothing.
SA=$(sa_token)
register "$ADMINONLY"
AO_ID=$(kc GET "/users?email=$ADMINONLY&exact=true" | jget "[0]['id']")
ADMIN_ROLE=$(kc GET /roles/ADMIN)
DEFAULT_ROLE=$(kc GET "/roles/default-roles-p-platform")
kc POST   "/users/$AO_ID/role-mappings/realm" "[$ADMIN_ROLE]" >/dev/null
kc DELETE "/users/$AO_ID/role-mappings/realm" "[$DEFAULT_ROLE]" >/dev/null
TAO=$(token "$ADMINONLY" "$PW")
AO_ROLES=$(echo "$TAO" | cut -d. -f2 | python3 -c "
import sys,base64,json
s=sys.stdin.read().strip(); s+='='*(-len(s)%4)
print(','.join(sorted(r for r in json.loads(base64.urlsafe_b64decode(s))['realm_access']['roles'] if r in ('ADMIN','USER'))))
" 2>/dev/null)
check "admin-only user holds ADMIN and not USER" "ADMIN" "$AO_ROLES"

head1 "2. Create — owner comes from the token, not the payload"
T1=$(body POST /api/v1/tasks "$TB" '{"title":"Bob task one","description":"first","status":"TODO","dueDate":"2026-12-01"}')
ID1=$(echo "$T1" | jget "['id']")
[ -n "$ID1" ] && ok "POST /tasks -> created" || bad "POST /tasks -> created" "an id" "$T1"
check "  title stored"  "Bob task one" "$(echo "$T1" | jget "['title']")"
check "  status stored" "TODO"         "$(echo "$T1" | jget "['status']")"
# a forged userId in the body must be ignored -- the record is unknown to the DTO
FORGED=$(body POST /api/v1/tasks "$TB" '{"title":"forged","status":"TODO","userId":"00000000-0000-0000-0000-000000000000"}')
FID=$(echo "$FORGED" | jget "['id']")
check "forged userId in body ignored" "200" "$(code GET "/api/v1/tasks/$FID" "$TB")"
check "  (bob can still read it)"     "200" "$(code GET "/api/v1/tasks/$FID" "$TB")"
check "blank title -> 400" "400" "$(code POST /api/v1/tasks "$TB" '{"title":"","status":"TODO"}')"
check "no token -> 401"    "401" "$(code GET /api/v1/tasks '')"

head1 "3. Read, update, list"
check "GET own task"  "200" "$(code GET "/api/v1/tasks/$ID1" "$TB")"
U=$(body PUT "/api/v1/tasks/$ID1" "$TB" '{"title":"Bob task one","status":"DOING"}')
check "PUT -> 200"          "DOING"        "$(echo "$U" | jget "['status']")"
check "  PUT is a full replace — description cleared" "None" "$(echo "$U" | jget "['description']")"
L=$(body GET "/api/v1/tasks?page=0&size=20" "$TB")
check "list is paged"       "0"  "$(echo "$L" | jget "['page']")"
N=$(echo "$L" | jget "['totalElements']")
[ "$N" = "2" ] && ok "list shows only bob's 2 tasks" || bad "list shows only bob's 2 tasks" "2" "$N"
check "status filter works" "1" "$(body GET "/api/v1/tasks?status=DOING" "$TB" | jget "['totalElements']")"
check "bad sortBy -> 400"   "400" "$(code GET "/api/v1/tasks?sortBy=DROP%20TABLE" "$TB")"

head1 "4. Ownership — carol must not touch bob's task"
check "carol GET bob's task    -> 403" "403" "$(code GET    "/api/v1/tasks/$ID1" "$TC")"
check "carol PUT bob's task    -> 403" "403" "$(code PUT    "/api/v1/tasks/$ID1" "$TC" '{"title":"stolen","status":"DONE"}')"
check "carol DELETE bob's task -> 403" "403" "$(code DELETE "/api/v1/tasks/$ID1" "$TC")"
check "  bob's task is UNCHANGED"      "DOING" "$(body GET "/api/v1/tasks/$ID1" "$TB" | jget "['status']")"
check "carol's list is empty"          "0"     "$(body GET /api/v1/tasks "$TC" | jget "['totalElements']")"
check "unknown id -> 404" "404" "$(code GET /api/v1/tasks/11111111-1111-1111-1111-111111111111 "$TB")"

head1 "5. Role split — USER and ADMIN routes are disjoint"
check "user token on admin list   -> 403" "403" "$(code GET /api/v1/admin/tasks "$TB")"
check "user token on admin delete -> 403" "403" "$(code DELETE "/api/v1/admin/tasks/$ID1" "$TB")"
check "alice (ADMIN+USER) on user route -> 200" "200" "$(code GET /api/v1/tasks "$TA")"
check "admin-ONLY token on user route    -> 403" "403" "$(code GET /api/v1/tasks "$TAO")"
check "admin lists ALL tasks      -> 200" "200" "$(code GET /api/v1/admin/tasks "$TA")"
ALL=$(body GET "/api/v1/admin/tasks?size=100" "$TA" | jget "['totalElements']")
[ "${ALL:-0}" -ge 2 ] && ok "  admin sees across owners ($ALL)" || bad "  admin sees across owners" ">=2" "$ALL"

head1 "6. Delete"
check "owner deletes own task -> 204" "204" "$(code DELETE "/api/v1/tasks/$FID" "$TB")"
check "  it is gone -> 404"           "404" "$(code GET    "/api/v1/tasks/$FID" "$TB")"
check "admin deletes any task -> 204" "204" "$(code DELETE "/api/v1/admin/tasks/$ID1" "$TA")"
check "  it is gone -> 404"           "404" "$(code GET    "/api/v1/tasks/$ID1" "$TB")"

head1 "7. Cleanup"
[ -n "${AO_ID:-}" ] && kc DELETE "/users/$AO_ID" >/dev/null
for t in "$TB" "$TC"; do curl -s -o /dev/null -X DELETE "$USERAPI/api/v1/users/me" -H "Authorization: Bearer $t"; done
ok "test users soft-deleted"

printf "\n\033[1m═══ %d passed, %d failed ═══\033[0m\n" "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
