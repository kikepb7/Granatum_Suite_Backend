-- Absences: holidays, paid leave and sick leave (feature 007).
--
-- No foreign key to empleados: that table belongs to timetracking, and
-- constitution principle I forbids this module from depending on it. Whether
-- the person exists and is active is checked through the DirectorioEmpleados
-- contract when an absence is created.
--
-- Nothing is ever deleted (FR-020): an absence is cancelled or rejected, and the
-- repositories expose no delete.
CREATE TABLE ausencias (
    id             UUID         PRIMARY KEY,
    empleado_id    UUID         NOT NULL,
    tipo           VARCHAR(12)  NOT NULL,
    -- Paid leave only, and required there (FR-002).
    causa          VARCHAR(24),
    desde          DATE         NOT NULL,
    -- NULL only for an open sick leave, closed later with the date of discharge.
    hasta          DATE,
    estado         VARCHAR(10)  NOT NULL,
    comentario     VARCHAR(500),
    motivo_rechazo VARCHAR(500),
    solicitada_por UUID         NOT NULL,
    solicitada_en  TIMESTAMPTZ  NOT NULL,
    resuelta_por   UUID,
    resuelta_en    TIMESTAMPTZ,
    cancelada_en   TIMESTAMPTZ,
    version        INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT ck_ausencias_tipo CHECK (tipo IN ('VACACIONES', 'PERMISO', 'BAJA_MEDICA')),
    CONSTRAINT ck_ausencias_estado CHECK (estado IN ('PENDIENTE', 'APROBADA', 'RECHAZADA', 'CANCELADA')),
    CONSTRAINT ck_ausencias_causa CHECK (
        (tipo = 'PERMISO') = (causa IS NOT NULL)
        AND (causa IS NULL OR causa IN (
            'MATRIMONIO', 'NACIMIENTO', 'FALLECIMIENTO_FAMILIAR', 'ENFERMEDAD_FAMILIAR',
            'MUDANZA', 'DEBER_INEXCUSABLE', 'OTRO'
        ))
    ),
    CONSTRAINT ck_ausencias_hasta CHECK (hasta IS NOT NULL OR tipo = 'BAJA_MEDICA'),
    -- Inclusive range, at most 366 days (research.md D-006).
    CONSTRAINT ck_ausencias_rango CHECK (hasta IS NULL OR (hasta >= desde AND hasta - desde <= 365)),
    -- A sick leave keeps no free text: the type is already health data (GDPR
    -- art. 9) and the minimum the calendar needs; nothing more is stored
    -- (FR-007, research.md D-003).
    CONSTRAINT ck_ausencias_baja_sin_texto CHECK (tipo <> 'BAJA_MEDICA' OR comentario IS NULL),
    CONSTRAINT ck_ausencias_rechazo CHECK ((estado = 'RECHAZADA') = (motivo_rechazo IS NOT NULL)),
    -- Pending: nobody resolved it yet. Cancelled: it may or may not have been
    -- approved before, so both are allowed there.
    CONSTRAINT ck_ausencias_pendiente CHECK (estado <> 'PENDIENTE' OR (resuelta_en IS NULL AND resuelta_por IS NULL)),
    CONSTRAINT ck_ausencias_resuelta CHECK (
        estado NOT IN ('APROBADA', 'RECHAZADA') OR (resuelta_en IS NOT NULL AND resuelta_por IS NOT NULL)
    ),
    CONSTRAINT ck_ausencias_cancelada CHECK ((estado = 'CANCELADA') = (cancelada_en IS NOT NULL))
);

-- A person's absences (overlap check, own list) and the staff calendar by range.
CREATE INDEX idx_ausencias_empleado ON ausencias (empleado_id, desde);
CREATE INDEX idx_ausencias_rango ON ausencias (desde, hasta);
CREATE INDEX idx_ausencias_estado ON ausencias (estado);

-- The yearly holiday entitlement when it differs from the configured default
-- (FR-014). No row means the default.
CREATE TABLE derechos_vacaciones (
    empleado_id     UUID        NOT NULL,
    anio            SMALLINT    NOT NULL,
    dias            SMALLINT    NOT NULL,
    actualizado_por UUID        NOT NULL,
    actualizado_en  TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (empleado_id, anio),
    CONSTRAINT ck_derechos_vacaciones_anio CHECK (anio BETWEEN 2000 AND 2100),
    CONSTRAINT ck_derechos_vacaciones_dias CHECK (dias BETWEEN 0 AND 366)
);

-- Who is off, when and why (sick leave included): Supabase's data API must not
-- serve it to the anon key. Never FORCE: the backend connects as the owner.
ALTER TABLE ausencias ENABLE ROW LEVEL SECURITY;
ALTER TABLE derechos_vacaciones ENABLE ROW LEVEL SECURITY;
