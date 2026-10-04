-- Staff whose working time is recorded.
--
-- `id` is deliberately the JWT subject: the token already carries a UUID
-- subject, so making it the empleado id turns the "only your own fichajes"
-- check into a direct comparison with no lookup that could drift out of sync.
--
-- There is no DELETE path for this table anywhere in the application. Removing
-- a person would destroy their working-time history, which must be kept for
-- four years; deactivation is `activo = false`.
CREATE TABLE empleados (
    id                   UUID         PRIMARY KEY,
    nombre               VARCHAR(150) NOT NULL,
    documento_identidad  VARCHAR(20)  NOT NULL,
    puesto               VARCHAR(100) NOT NULL,
    tipo_contrato        VARCHAR(20)  NOT NULL,
    fecha_alta           DATE         NOT NULL,
    activo               BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ  NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uk_empleados_documento UNIQUE (documento_identidad),
    CONSTRAINT ck_empleados_tipo_contrato
        CHECK (tipo_contrato IN ('JORNADA_COMPLETA', 'PARCIAL', 'POR_HORAS'))
);

-- Deny by default for anything that is not the table owner, so Supabase's
-- PostgREST exposes nothing. See V5__enable_row_level_security.sql for why
-- FORCE ROW LEVEL SECURITY must never be used here.
ALTER TABLE empleados ENABLE ROW LEVEL SECURITY;
