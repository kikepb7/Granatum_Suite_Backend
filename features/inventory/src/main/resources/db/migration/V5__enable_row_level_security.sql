-- Enables RLS on every table created so far (constitution, principle VII).
--
-- Why: Supabase publishes an automatic REST API (PostgREST) over the `public`
-- schema. A table without RLS there is readable from the internet with the
-- anon key, which is public by design. This has nothing to do with the
-- backend's own authorization - it is about what Supabase exposes on the side.
--
-- How: enabling RLS with NO policies means deny-by-default. Any role that is
-- not the table owner (`anon`, `authenticated`, or anything else) sees zero
-- rows and cannot write, regardless of table-level GRANTs.
--
-- Deliberately NOT using FORCE ROW LEVEL SECURITY: that would apply RLS to the
-- table owner too, and this backend connects as the owner. Forcing it would
-- lock the application out of its own data. Owner bypass is the mechanism that
-- lets the API keep doing its authorization in SecurityConfig while PostgREST
-- stays blind.
--
-- Deliberately NOT issuing REVOKE against `anon` / `authenticated`: those
-- roles do not exist on a plain Postgres (local docker-compose, Testcontainers
-- in CI), so naming them would make this migration non-portable. RLS alone
-- already denies them everything, so a second mechanism would add coupling
-- without adding protection.
--
-- New tables must enable RLS in the SAME migration that creates them.
-- RowLevelSecurityIT fails the build if any table in `public` is left without it.

ALTER TABLE categorias         ENABLE ROW LEVEL SECURITY;
ALTER TABLE materiales         ENABLE ROW LEVEL SECURITY;
ALTER TABLE material_fotos     ENABLE ROW LEVEL SECURITY;
ALTER TABLE historial_material ENABLE ROW LEVEL SECURITY;

-- NOT covered here: flyway_schema_history, Flyway's own bookkeeping table,
-- which also lives in `public` and which PostgREST would therefore serve
-- (migration versions, descriptions and timestamps - reconnaissance, though no
-- personal data and no credentials).
--
-- It cannot be done from inside a migration: Flyway holds a lock on that table
-- for the whole duration of its run, so `ALTER TABLE flyway_schema_history`
-- waits on a lock Flyway will not release until the migration finishes - the
-- migration deadlocks against itself and the run hangs forever. Verified the
-- hard way; do not re-add it.
--
-- Mitigate it outside Flyway instead, as a one-off step per environment:
--     ALTER TABLE flyway_schema_history ENABLE ROW LEVEL SECURITY;
-- See README.md ("Row Level Security") for when to run it.
