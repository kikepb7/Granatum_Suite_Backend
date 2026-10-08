-- What a person leaves behind when they sign up (feature 005). A sign-up gives
-- no access on its own: it waits here until an ADMIN approves it with the
-- verification code the person tells them in person
-- (specs/005-staff-registration research.md D-001).
--
-- Personal data (email, name, identity document, password hash) exists ONLY
-- while the request is PENDIENTE. Every final state empties it - approved data
-- now lives in cuentas_acceso and empleados; rejected, expired or cancelled data
-- has no purpose left (GDPR art. 5.1.e). The row itself is never deleted: it is
-- the record of what happened, who resolved it and when.
CREATE TABLE solicitudes_registro (
    id                  UUID         PRIMARY KEY,
    estado              VARCHAR(12)  NOT NULL,
    -- Normalised on write: trimmed and lowercased, like cuentas_acceso.email.
    -- Not unique: two pending requests with the same address are legitimate -
    -- the impostor and the real person hold different codes, and only the one
    -- whose code the ADMIN hears gets approved (D-001).
    email               VARCHAR(254),
    -- Same size as empleados.nombre, where it ends up.
    nombre              VARCHAR(150),
    -- Normalised and already validated as a DNI or NIE.
    documento_identidad VARCHAR(20),
    -- Output of the same PasswordEncoder as cuentas_acceso (Argon2): approving
    -- copies it to the account, so the person signs in with the password they
    -- chose and nobody else ever saw it.
    password_hash       VARCHAR(255),
    -- SHA-256(id || code) in hex. The code itself is only ever in the sign-up
    -- response.
    codigo_hash         VARCHAR(64),
    -- Five wrong codes cancel the request (FR-018).
    intentos_codigo     SMALLINT     NOT NULL DEFAULT 0,
    creada_en           TIMESTAMPTZ  NOT NULL,
    resuelta_en         TIMESTAMPTZ,
    -- The ADMIN's token subject; NULL when the system resolved it (expiry,
    -- automatic cancellation).
    resuelta_por        UUID,
    -- The account an approved request produced. Same module, so a real foreign
    -- key; there is none to empleados, which belongs to timetracking.
    cuenta_id           UUID         REFERENCES cuentas_acceso (id),
    CONSTRAINT ck_solicitudes_registro_estado
        CHECK (estado IN ('PENDIENTE', 'APROBADA', 'RECHAZADA', 'CADUCADA', 'ANULADA')),
    CONSTRAINT ck_solicitudes_registro_intentos CHECK (intentos_codigo BETWEEN 0 AND 5),
    -- A pending request is complete...
    CONSTRAINT ck_solicitudes_registro_pendiente_completa CHECK (
        estado <> 'PENDIENTE' OR (
            email IS NOT NULL AND nombre IS NOT NULL AND documento_identidad IS NOT NULL
            AND password_hash IS NOT NULL AND codigo_hash IS NOT NULL
        )
    ),
    -- ...and a resolved one keeps no personal data (FR-026, SC-005). Enforced
    -- here so that a code path forgetting to empty a field fails loudly instead
    -- of quietly keeping someone's DNI.
    CONSTRAINT ck_solicitudes_registro_resuelta_sin_datos CHECK (
        estado = 'PENDIENTE' OR (
            email IS NULL AND nombre IS NULL AND documento_identidad IS NULL
            AND password_hash IS NULL AND codigo_hash IS NULL
        )
    ),
    CONSTRAINT ck_solicitudes_registro_resuelta_en CHECK ((estado = 'PENDIENTE') = (resuelta_en IS NULL)),
    CONSTRAINT ck_solicitudes_registro_cuenta CHECK ((estado = 'APROBADA') = (cuenta_id IS NOT NULL))
);

-- The ADMIN's list and the expiry sweep.
CREATE INDEX idx_solicitudes_registro_estado ON solicitudes_registro (estado, creada_en);
-- Cancelling the other pending requests of the same person on approval.
CREATE INDEX idx_solicitudes_registro_email_pendiente
    ON solicitudes_registro (email) WHERE estado = 'PENDIENTE';
CREATE INDEX idx_solicitudes_registro_documento_pendiente
    ON solicitudes_registro (documento_identidad) WHERE estado = 'PENDIENTE';

-- Names, DNIs, emails and password hashes: Supabase's data API must not serve
-- them to the anon key. Never FORCE: the backend connects as the owner.
ALTER TABLE solicitudes_registro ENABLE ROW LEVEL SECURITY;
