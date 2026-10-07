-- Access credentials for a person. Mutable, but only through the transitions
-- enumerated in specs/002-auth/data-model.md - nothing else writes here.
--
-- No foreign key to `empleados`, deliberately. That table belongs to the
-- `timetracking` module and constitution principle I forbids this feature from
-- depending on it; the check runs through the `DirectorioEmpleados` contract in
-- `common` instead. The database therefore cannot prevent an orphan account
-- (the person may stop existing after the account is created), which is exactly
-- why FR-029c asks for them to be *detected* rather than only prevented.
--
-- There is no DELETE path for this table anywhere in the application: the
-- repository extends Repository<T, ID> and never declares `delete`. Revoking
-- someone's access is done by deactivating the person in `empleados`, which is
-- where that fact lives.
CREATE TABLE cuentas_acceso (
    id                       UUID         PRIMARY KEY,
    -- The person whose working time is recorded. Not this table's own id: the
    -- JWT subject is the empleado id (decided in feature 001), so keeping them
    -- separate is what lets the token identify the person, not the credential.
    empleado_id              UUID         NOT NULL,
    -- 254 is the real maximum length of an address per RFC 5321. Already
    -- normalised on write: trimmed and lowercased with Locale.ROOT. Personal
    -- data: never logged, omitted from the entity's toString().
    email                    VARCHAR(254) NOT NULL,
    -- What this credential is authorised to do.
    --
    -- It lives here and not in `empleados` because it is a property of the
    -- credential, not of the employment: `puesto` says what someone does,
    -- `rol` says what they may read and write. `REPRESENTANTE` makes the
    -- distinction concrete - a workers' legal representative is a recipient of
    -- the register under article 34.9, which has nothing to do with their job
    -- title.
    --
    -- Keeping it here also keeps principle I clean: `auth` decides
    -- authorisation on its own and the `DirectorioEmpleados` contract stays
    -- narrow, answering only whether a person exists and is employed.
    --
    -- NOTE: neither the spec nor the plan said where the role should live -
    -- found while implementing US1, because FR-005 requires the token to carry
    -- it and nothing stored it. See specs/002-auth/data-model.md.
    rol                      VARCHAR(20)  NOT NULL,
    -- Output of DelegatingPasswordEncoder, prefix included:
    -- {argon2}$argon2id$v=19$m=65536,t=3,p=1$<salt>$<hash>
    -- 255 leaves room for a more verbose future algorithm without a migration.
    password_hash            VARCHAR(255) NOT NULL,
    -- Defaults to TRUE because *every* account is born from a temporary
    -- password: there is no sign-up route that does not require the change.
    requiere_cambio_password BOOLEAN      NOT NULL DEFAULT TRUE,
    -- Consecutive failures since the last success or lockout. Reset to 0 when a
    -- lockout is applied, so each new lockout needs another 5 failures
    -- (FR-016c).
    intentos_fallidos        SMALLINT     NOT NULL DEFAULT 0,
    -- Survives the expiry of the lockout: it is what makes the next one last 5
    -- minutes instead of 1 (FR-016a). 0 means never locked.
    nivel_bloqueo            SMALLINT     NOT NULL DEFAULT 0,
    -- NULL = not locked. The lockout lifts by comparison with the clock, with
    -- no process to clear it (FR-016).
    bloqueada_hasta          TIMESTAMPTZ,
    created_at               TIMESTAMPTZ  NOT NULL,
    updated_at               TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uk_cuentas_acceso_email UNIQUE (email),
    -- FR-029d: two accounts must never point at the same person.
    CONSTRAINT uk_cuentas_acceso_empleado UNIQUE (empleado_id),
    CONSTRAINT ck_cuentas_acceso_nivel CHECK (nivel_bloqueo BETWEEN 0 AND 4),
    -- The four roles of constitution principle IV, no more and no fewer.
    CONSTRAINT ck_cuentas_acceso_rol
        CHECK (rol IN ('ADMIN', 'ENCARGADO', 'EMPLEADO', 'REPRESENTANTE'))
);

-- Deny by default for anything that is not the table owner, so Supabase's
-- PostgREST exposes nothing. It matters more here than for any other table in
-- the system: this one holds email addresses and password hashes, and Supabase
-- would publish it to anyone holding the anon key, which is public by design.
-- Never FORCE ROW LEVEL SECURITY: the backend connects as the owner and would
-- lose access to its own data.
ALTER TABLE cuentas_acceso ENABLE ROW LEVEL SECURITY;
