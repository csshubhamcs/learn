#!/usr/bin/env bash
# End-to-end functional test against a running user-service + Keycloak.
# Exercises 10 users through the full lifecycle, profile, sections, admin and both deletes.
#
#   ./scripts/e2e.sh
#
# Requires: docker compose stack up, user-service running on :7701.
set -uo pipefail

API=${API:-http://localhost:7701}
KC=${KC:-http://localhost:8080}
REALM=${REALM:-p-platform}
PW='Password123!'
RUN=$(date +%s)

PASS=0; FAIL=0
ok()   { PASS=$((PASS+1)); printf "  \033[32m✓\033[0m %s\n" "$1"; }
bad()  { FAIL=$((FAIL+1)); printf "  \033[31m✗\033[0m %s\n" "$1"; printf "      expected: %s\n      actual:   %s\n" "$2" "$3"; }
check(){ [ "$2" = "$3" ] && ok "$1" || bad "$1" "$2" "$3"; }
head1(){ printf "\n\033[1m%s\033[0m\n" "$1"; }

token() { # token <user> <pass>
  curl -s -X POST "$KC/realms/$REALM/protocol/openid-connect/token" \
    -d client_id=web-app -d grant_type=password \
    -d "username=$1" -d "password=$2" \
  | python3 -c "import sys,json;print(json.load(sys.stdin).get('access_token',''))" 2>/dev/null
}
code() { # code <method> <path> <token> [body]
  local m=$1 p=$2 t=${3:-} b=${4:-}
  if [ -n "$b" ]; then
    curl -s -o /tmp/e2e.out -w "%{http_code}" -X "$m" "$API$p" \
      -H "Authorization: Bearer $t" -H 'Content-Type: application/json' -d "$b"
  else
    curl -s -o /tmp/e2e.out -w "%{http_code}" -X "$m" "$API$p" -H "Authorization: Bearer $t"
  fi
}
body() { cat /tmp/e2e.out; }
# Post a JSON body without any nested-quote escaping. $1=path, $2=json, $3=optional bearer.
patch_json() {
  printf '%s' "$2" > /tmp/e2e.body
  curl -s -o /tmp/e2e.out -w "%{http_code}" -X PATCH "$API$1" \
    -H "Authorization: Bearer $3" -H 'Content-Type: application/json' -d @/tmp/e2e.body
}
post_json() {
  printf '%s' "$2" > /tmp/e2e.body
  if [ -n "${3:-}" ]; then
    curl -s -o /tmp/e2e.out -w "%{http_code}" -X POST "$API$1" \
      -H "Authorization: Bearer $3" -H 'Content-Type: application/json' -d @/tmp/e2e.body
  else
    curl -s -o /tmp/e2e.out -w "%{http_code}" -X POST "$API$1" \
      -H 'Content-Type: application/json' -d @/tmp/e2e.body
  fi
}
jget() { python3 -c "import sys,json;d=json.load(sys.stdin);print(d$1)" 2>/dev/null </tmp/e2e.out; }

head1 "0. Preflight"
check "service is up" "200" "$(curl -s -o /dev/null -w '%{http_code}' $API/actuator/health/readiness)"
ADMIN_T=$(token alice@example.com "$PW")
[ -n "$ADMIN_T" ] && ok "admin token acquired" || bad "admin token" "a token" "empty"

head1 "1. Register 10 users"
declare -a EMAILS IDS TOKENS
for i in $(seq 1 10); do
  E="e2e-$RUN-$i@example.com"; EMAILS+=("$E")
  REG_BODY=$(printf '{"email":"%s","password":"%s"}' "$E" "$PW")
  C=$(post_json /api/v1/auth/register "$REG_BODY")
  if [ "$C" = "201" ]; then IDS+=("$(jget "['id']")"); else bad "register user $i" "201" "$C: $(body|head -c 120)"; IDS+=(""); fi
done
check "all 10 registered" "10" "$(printf '%s\n' "${IDS[@]}" | grep -c .)"

head1 "2. Every user can log in immediately (email verification OFF)"
LOGGED=0
for E in "${EMAILS[@]}"; do T=$(token "$E" "$PW"); TOKENS+=("$T"); [ -n "$T" ] && LOGGED=$((LOGGED+1)); done
check "10/10 obtained tokens with no mail step" "10" "$LOGGED"
U1=${TOKENS[0]}; U1_ID=${IDS[0]}; U1_EMAIL=${EMAILS[0]}

head1 "3. Self-service"
check "GET /users/me"            "200" "$(code GET /api/v1/users/me "$U1")"
check "  returns own email"      "$U1_EMAIL" "$(jget "['email']")"
DUP_BODY=$(printf '{"email":"%s","password":"%s"}' "$U1_EMAIL" "$PW")
DUP_CODE=$(post_json /api/v1/auth/register "$DUP_BODY")
check "duplicate email -> 409"   "409" "$DUP_CODE"

UPPER_EMAIL=$(printf '%s' "$U1_EMAIL" | tr 'a-z' 'A-Z')
CASE_BODY=$(printf '{"email":"%s","password":"%s"}' "$UPPER_EMAIL" "$PW")
CASE_CODE=$(post_json /api/v1/auth/register "$CASE_BODY")
check "duplicate is case-insensitive" "409" "$CASE_CODE"
check "invalid email -> 400"     "400" "$(post_json /api/v1/auth/register '{"email":"nope","password":"Password123!"}')"
check "no token -> 401"          "401" "$(curl -s -o /dev/null -w '%{http_code}' $API/api/v1/users/me)"

head1 "4. Profile — update, change, persist"
check "GET profile (lazy create)" "200" "$(code GET /api/v1/users/me/profile "$U1")"
check "PATCH sets fields"         "200" "$(code PATCH /api/v1/users/me/profile "$U1" '{"displayName":"Shubham","headline":"Engineer","city":"Bengaluru"}')"
check "  displayName applied"     "Shubham"  "$(jget "['displayName']")"
check "PATCH changes one field"   "200" "$(code PATCH /api/v1/users/me/profile "$U1" '{"headline":"Principal Engineer"}')"
check "  headline changed"        "Principal Engineer" "$(jget "['headline']")"
check "  displayName PRESERVED"   "Shubham"  "$(jget "['displayName']")"
check "  city PRESERVED"          "Bengaluru" "$(jget "['city']")"
code GET /api/v1/users/me/profile "$U1" >/dev/null
check "persisted across requests" "Principal Engineer" "$(jget "['headline']")"

head1 "5. Social links — now ordinary nullable profile fields"
SOCIAL_BODY=$(printf '{"instagramUrl":"%s","linkedinUrl":"%s"}' "https://instagram.com/a" "https://linkedin.com/in/a")
check "PATCH sets social links" "200" "$(patch_json /api/v1/users/me/profile "$SOCIAL_BODY" "$U1")"
check "  instagram stored"      "https://instagram.com/a" "$(jget "['instagramUrl']")"
check "  linkedin stored"       "https://linkedin.com/in/a" "$(jget "['linkedinUrl']")"

CHANGE_BODY=$(printf '{"instagramUrl":"%s","websiteUrl":"%s"}' "https://instagram.com/CHANGED" "https://example.com")
check "PATCH changes one link"  "200" "$(patch_json /api/v1/users/me/profile "$CHANGE_BODY" "$U1")"
check "  change applied"        "https://instagram.com/CHANGED" "$(jget "['instagramUrl']")"
check "  linkedin PRESERVED"    "https://linkedin.com/in/a" "$(jget "['linkedinUrl']")"
check "  headline still there"  "Principal Engineer" "$(jget "['headline']")"

BLANK_BODY='{"websiteUrl":""}'
check "empty string clears a field" "200" "$(patch_json /api/v1/users/me/profile "$BLANK_BODY" "$U1")"
check "  website cleared"       "" "$(jget "['websiteUrl']")"
check "  instagram untouched"   "https://instagram.com/CHANGED" "$(jget "['instagramUrl']")"

check "removed section route is gone" "404" "$(code GET /api/v1/users/me/profile/sections/social "$U1")"

head1 "6. Authorization boundaries"
check "user token on admin list -> 403"   "403" "$(code GET /api/v1/admin/users "$U1")"
check "user token on admin get  -> 403"   "403" "$(code GET "/api/v1/admin/users/${IDS[1]}" "$U1")"
check "user cannot delete another user"   "403" "$(code DELETE "/api/v1/admin/users/${IDS[1]}" "$U1")"

head1 "7. Admin"
check "admin lists users"        "200" "$(code GET '/api/v1/admin/users?size=5' "$ADMIN_T")"
check "  page has page=0"        "0"   "$(jget "['page']")"
check "  page has a size"        "5"   "$(jget "['size']")"
check "  page has a total"       "true" "$(python3 -c "import sys,json;print(str(json.load(sys.stdin).get('totalElements') is not None).lower())" </tmp/e2e.out)"
check "  page has hasNext"       "true" "$(python3 -c "import sys,json;print(str(json.load(sys.stdin).get('hasNext') is not None).lower())" </tmp/e2e.out)"
check "admin fetches page 2"     "200" "$(code GET '/api/v1/admin/users?size=5&page=1' "$ADMIN_T")"
check "  page=1 reported"        "1"   "$(jget "['page']")"
check "admin search by email"    "200" "$(code GET "/api/v1/admin/users?q=e2e-$RUN" "$ADMIN_T")"
check "admin gets user by id"    "200" "$(code GET "/api/v1/admin/users/$U1_ID" "$ADMIN_T")"
check "admin filters by status"  "200" "$(code GET "/api/v1/admin/users?q=e2e-$RUN&status=ACTIVE" "$ADMIN_T")"
check "  status filter excludes deleted" "false" "$(python3 -c "import sys,json;d=json.load(sys.stdin);print(str(any(i['status']=='DELETED' for i in d['items'])).lower())" </tmp/e2e.out)"
check "admin sorts by email asc" "200" "$(code GET "/api/v1/admin/users?q=e2e-$RUN&sortBy=email&sortDir=asc" "$ADMIN_T")"
check "invalid sortBy -> 400"    "400" "$(code GET '/api/v1/admin/users?sortBy=passwordHash' "$ADMIN_T")"

head1 "8. Email change — Keycloak's own UPDATE_EMAIL flow"
check "request change -> 202"    "202" "$(code POST /api/v1/users/me/email/change "$U1")"
code GET /api/v1/users/me "$U1" >/dev/null
check "  old email STILL active" "$U1_EMAIL" "$(jget "['email']")"

head1 "9. Soft delete (self)"
U2=${TOKENS[1]}; U2_ID=${IDS[1]}; U2_EMAIL=${EMAILS[1]}
check "DELETE /users/me -> 204"  "204" "$(code DELETE /api/v1/users/me "$U2")"
check "keycloak user DISABLED"   "false" "$(curl -s -H "Authorization: Bearer $(curl -s -X POST $KC/realms/$REALM/protocol/openid-connect/token -d client_id=user-service -d client_secret=user-service-secret -d grant_type=client_credentials | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')" "$KC/admin/realms/$REALM/users/$U2_ID" | python3 -c 'import sys,json;print(str(json.load(sys.stdin).get("enabled")).lower())' 2>/dev/null)"
REUSE_BODY=$(printf '{"email":"%s","password":"%s"}' "$U2_EMAIL" "$PW")
REUSE_CODE=$(post_json /api/v1/auth/register "$REUSE_BODY")
check "email FREED for reuse"    "201" "$REUSE_CODE"
REUSED_ID=$(jget "['id']")
check "  re-registration is a NEW id" "true" "$([ "$REUSED_ID" != "$U2_ID" ] && echo true || echo false)"

head1 "10. Hard delete (admin)"
U3_ID=${IDS[2]}
check "admin hard delete -> 204" "204" "$(code DELETE "/api/v1/admin/users/$U3_ID?hard=true" "$ADMIN_T")"
check "  row gone -> 404"        "404" "$(code GET "/api/v1/admin/users/$U3_ID" "$ADMIN_T")"
SA=$(curl -s -X POST $KC/realms/$REALM/protocol/openid-connect/token -d client_id=user-service -d client_secret=user-service-secret -d grant_type=client_credentials | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')
check "  keycloak user gone"     "404" "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $SA" "$KC/admin/realms/$REALM/users/$U3_ID")"

head1 "11. Cleanup"
for i in 3 4 5 6 7 8 9; do [ -n "${IDS[$i]:-}" ] && code DELETE "/api/v1/admin/users/${IDS[$i]}?hard=true" "$ADMIN_T" >/dev/null; done
ok "remaining test users purged"

printf "\n\033[1m═══ %d passed, %d failed ═══\033[0m\n" "$PASS" "$FAIL"
exit $([ "$FAIL" -eq 0 ] && echo 0 || echo 1)
