#!/usr/bin/env bash
# Bulk-load N users straight into Postgres to test how the service behaves at data scale.
#
#   ./scripts/seed-users.sh 1000000
#
# Inserts directly rather than through /auth/register on purpose: registration is bounded by
# Keycloak's bcrypt (~107/s measured), so a million users would take ~2.6 hours and would be
# measuring Keycloak, not us. These rows have no Keycloak counterpart, which is fine -- they
# exist to exercise OUR indexes, pagination and search.
set -euo pipefail
N=${1:-1000000}
echo "generating $N rows..."
python3 - "$N" > /tmp/users.csv <<'PY'
import sys, uuid, random, datetime
n = int(sys.argv[1])
base = datetime.datetime(2024, 1, 1, tzinfo=datetime.timezone.utc)
domains = ["example.com","test.org","mail.net","corp.io","dev.co"]
first = ["alex","sam","jordan","riley","casey","morgan","taylor","jamie","avery","quinn"]
w = sys.stdout.write
for i in range(n):
    uid = uuid.uuid4()
    email = f"{random.choice(first)}.{i}@{random.choice(domains)}"
    ts = (base + datetime.timedelta(seconds=i * 3)).isoformat()
    status = "ACTIVE" if i % 50 else "SUSPENDED"
    w(f"{uid}\t{status}\t{email}\t{email.lower()}\t\\N\t\\N\t\\N\t{ts}\t{ts}\n")
PY
echo "loading into postgres..."
time docker compose exec -T postgres psql -U postgres -d userdb -c \
  "COPY users (id,status,email,email_normalized,last_login_at,deleted_at,deleted_by,created_at,updated_at) FROM STDIN;" < /tmp/users.csv
docker compose exec -T postgres psql -U postgres -d userdb -c "ANALYZE users;"
docker compose exec -T postgres psql -U postgres -d userdb -tAc "SELECT count(*) FROM users;" | xargs echo "total rows:"
rm -f /tmp/users.csv
