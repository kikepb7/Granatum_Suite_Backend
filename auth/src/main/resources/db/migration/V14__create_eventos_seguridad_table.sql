-- APPEND-ONLY. Record of access attempts and credential changes (FR-017).
--
-- Never UPDATEd, never DELETEd. No `updated_at` column, deliberately: there is
-- nothing to update. The repository extends Repository<T, ID> and declares only
-- `save` and reads - not calling `delete` is not enough, because an interface
-- that offers it will eventually be used by accident (constitution principle
-- III, last rule; and declared debt nº 2 is the lesson of what happens when it
-- is offered).
--
-- No foreign key to cuentas_acceso, for the same reason as fichaje_eventos in
-- feature 001: nothing must be able to cascade a delete into the immutable
-- table.
--
-- What this table deliberately does NOT contain:
--
--   * No passwords and no tokens, in any form - not even hashed (SC-009).
--   * No email address, not even for an attempt with an unknown one. Storing it
--     would mean keeping a personal datum about someone who is not a user, over
--     an attempt that may not have been theirs. The cost is that email
--     enumeration cannot be detected here; that belongs to the hardening
--     feature.
--   * No source IP address. It is personal data, and per-origin limiting is out
--     of scope by decision of the spec. Collecting it "just in case" is
--     collecting without a purpose.
CREATE TABLE eventos_seguridad (
    id          UUID        PRIMARY KEY,
    -- NULL when the email matched no account - see above.
    cuenta_id   UUID,
    tipo        VARCHAR(40) NOT NULL,
    ocurrido_en TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_eventos_seguridad_tipo CHECK (tipo IN (
        'LOGIN_CORRECTO',
        'LOGIN_FALLIDO',
        'LOGIN_CUENTA_DESCONOCIDA',
        'LOGIN_CUENTA_BLOQUEADA',
        'LOGIN_EMPLEADO_INACTIVO',
        'CUENTA_BLOQUEADA',
        'RENOVACION_CORRECTA',
        'RENOVACION_RECHAZADA',
        -- Distinct from RENOVACION_RECHAZADA because it means something
        -- different: a token that existed and was already used is presented
        -- again. It is the only signal of possible theft this feature leaves.
        'RENOVACION_TOKEN_REUTILIZADO',
        'CIERRE_SESION',
        'PASSWORD_CAMBIADA',
        'PASSWORD_RESTABLECIDA',
        'CUENTA_CREADA'
    ))
);

CREATE INDEX idx_eventos_seguridad_cuenta ON eventos_seguridad (cuenta_id, ocurrido_en);

ALTER TABLE eventos_seguridad ENABLE ROW LEVEL SECURITY;
