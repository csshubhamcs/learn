-- Runs only when the postgres data volume is initialized for the first time
-- (/docker-entrypoint-initdb.d semantics). One cluster holds all three databases:
--
--   userdb    user-service   (created by POSTGRES_DB, so not repeated here)
--   taskdb    task-service
--   keycloak  Keycloak's own store, under its own role
--
-- Separate databases, separate migration histories, and no cross-service foreign keys --
-- Postgres cannot reference across databases even if someone tried.
--
-- On a volume that already exists (anyone who ran this stack before these were added), this
-- file never executes; create them once by hand instead:
--   docker compose exec -T postgres psql -U postgres -c "CREATE DATABASE taskdb"
--   docker compose exec -T postgres psql -U postgres -c "CREATE ROLE keycloak LOGIN PASSWORD 'keycloak'"
--   docker compose exec -T postgres psql -U postgres -c "CREATE DATABASE keycloak OWNER keycloak"
CREATE DATABASE taskdb;

CREATE ROLE keycloak LOGIN PASSWORD 'keycloak';
CREATE DATABASE keycloak OWNER keycloak;
