-- One open session on one device. An account may have several (FR-012).
--
-- A foreign key IS appropriate here, unlike in V12: both tables belong to this
-- feature and this migration.
CREATE TABLE sesiones_renovacion (
    id                UUID        PRIMARY KEY,
    cuenta_id         UUID        NOT NULL REFERENCES cuentas_acceso (id) ON DELETE RESTRICT,
    -- SHA-256 of the opaque token value, in hexadecimal. The value itself is
    -- never stored (FR-011).
    --
    -- VARCHAR(64) with a length CHECK rather than CHAR(64). CHAR was the first
    -- choice - a SHA-256 in hex is always exactly 64 characters - and
    -- `ddl-auto: validate` rejected it: Postgres reports CHAR as `bpchar`, while
    -- a JPA String column maps to `varchar`, so the context would not start.
    -- That is principle II doing its job, and the fix is the better column
    -- anyway: `bpchar` is blank-padded on comparison, so a value with trailing
    -- spaces would compare equal to one without, and the CHECK keeps the
    -- fixed-width guarantee without that surprise.
    --
    -- A fast hash is correct here and would be wrong for a password. The ban on
    -- MD5/SHA in constitution principle VI exists because passwords have little
    -- entropy and must be expensive to guess; a refresh token is 256 random
    -- bits, so there is nothing to guess, and a slow hash would only make every
    -- legitimate renewal more expensive.
    token_hash        VARCHAR(64) NOT NULL,
    expira_en         TIMESTAMPTZ NOT NULL,
    creada_en         TIMESTAMPTZ NOT NULL,
    -- Not null = already rotated. This is the single-use mark (FR-008).
    usada_en          TIMESTAMPTZ,
    -- Not null = invalidated without being used.
    revocada_en       TIMESTAMPTZ,
    motivo_revocacion VARCHAR(20),
    CONSTRAINT uk_sesiones_renovacion_token UNIQUE (token_hash),
    CONSTRAINT ck_sesiones_renovacion_token_hash CHECK (char_length(token_hash) = 64),
    CONSTRAINT ck_sesiones_renovacion_motivo
        CHECK (motivo_revocacion IS NULL
               OR motivo_revocacion IN ('LOGOUT', 'RESET', 'CAMBIO_PASSWORD')),
    -- The two columns only make sense together: a revoked row always carries a
    -- reason, and a reason without a revocation date would be a row nobody can
    -- interpret.
    CONSTRAINT ck_sesiones_renovacion_revocacion
        CHECK ((revocada_en IS NULL) = (motivo_revocacion IS NULL))
);

-- Used by the mass revocation of FR-025 and by the purge job.
CREATE INDEX idx_sesiones_renovacion_cuenta ON sesiones_renovacion (cuenta_id);

-- `usada_en` and `revocada_en` are kept apart rather than collapsed into one
-- state column because they mean different things: "rotated normally" versus
-- "cut short by a logout or a password reset". The second is the one that
-- matters in an investigation, and merging them would erase it.

ALTER TABLE sesiones_renovacion ENABLE ROW LEVEL SECURITY;
