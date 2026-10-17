-- Absences in English (docs/english-naming.md, 2026-10-18).
--
-- Renames V22's tables, columns, indexes and constraints, translates the
-- stored enum values and rewrites the CHECKs that name them. No data is lost:
-- everything is renamed or updated in place. RLS survives a table rename, so
-- both tables keep it (principle VII). V22 created no function, trigger,
-- policy or exclusion constraint for these tables (overlaps are serialised by
-- an advisory lock in AbsenceService), so there is nothing else to recreate.
--
-- Date columns are from_date / to_date: FROM and TO are reserved words in SQL.

-- --- ausencias -> absences ---------------------------------------------------

ALTER TABLE ausencias RENAME TO absences;

ALTER TABLE absences RENAME COLUMN empleado_id    TO employee_id;
ALTER TABLE absences RENAME COLUMN tipo           TO type;
ALTER TABLE absences RENAME COLUMN causa          TO cause;
ALTER TABLE absences RENAME COLUMN desde          TO from_date;
ALTER TABLE absences RENAME COLUMN hasta          TO to_date;
ALTER TABLE absences RENAME COLUMN estado         TO status;
ALTER TABLE absences RENAME COLUMN comentario     TO comment;
ALTER TABLE absences RENAME COLUMN motivo_rechazo TO rejection_reason;
ALTER TABLE absences RENAME COLUMN solicitada_por TO requested_by;
ALTER TABLE absences RENAME COLUMN solicitada_en  TO requested_at;
ALTER TABLE absences RENAME COLUMN resuelta_por   TO resolved_by;
ALTER TABLE absences RENAME COLUMN resuelta_en    TO resolved_at;
ALTER TABLE absences RENAME COLUMN cancelada_en   TO cancelled_at;

ALTER TABLE absences RENAME CONSTRAINT ausencias_pkey TO absences_pkey;

ALTER INDEX idx_ausencias_empleado RENAME TO idx_absences_employee;
ALTER INDEX idx_ausencias_rango    RENAME TO idx_absences_range;
ALTER INDEX idx_ausencias_estado   RENAME TO idx_absences_status;

-- The CHECKs that name stored values go before the values change, and come
-- back with the English ones.
ALTER TABLE absences
    DROP CONSTRAINT ck_ausencias_tipo,
    DROP CONSTRAINT ck_ausencias_estado,
    DROP CONSTRAINT ck_ausencias_causa,
    DROP CONSTRAINT ck_ausencias_hasta,
    DROP CONSTRAINT ck_ausencias_baja_sin_texto,
    DROP CONSTRAINT ck_ausencias_rechazo,
    DROP CONSTRAINT ck_ausencias_pendiente,
    DROP CONSTRAINT ck_ausencias_resuelta,
    DROP CONSTRAINT ck_ausencias_cancelada;

-- Names no value: only renamed.
ALTER TABLE absences RENAME CONSTRAINT ck_ausencias_rango TO ck_absences_range;

UPDATE absences SET
    type = CASE type
        WHEN 'VACACIONES'  THEN 'VACATION'
        WHEN 'PERMISO'     THEN 'LEAVE'
        WHEN 'BAJA_MEDICA' THEN 'SICK_LEAVE'
        ELSE type
    END,
    status = CASE status
        WHEN 'PENDIENTE' THEN 'PENDING'
        WHEN 'APROBADA'  THEN 'APPROVED'
        WHEN 'RECHAZADA' THEN 'REJECTED'
        WHEN 'CANCELADA' THEN 'CANCELLED'
        ELSE status
    END,
    cause = CASE cause
        WHEN 'MATRIMONIO'             THEN 'MARRIAGE'
        WHEN 'NACIMIENTO'             THEN 'BIRTH'
        WHEN 'FALLECIMIENTO_FAMILIAR' THEN 'FAMILY_DEATH'
        WHEN 'ENFERMEDAD_FAMILIAR'    THEN 'FAMILY_ILLNESS'
        WHEN 'MUDANZA'                THEN 'HOUSE_MOVE'
        WHEN 'DEBER_INEXCUSABLE'      THEN 'PUBLIC_DUTY'
        WHEN 'OTRO'                   THEN 'OTHER'
        ELSE cause
    END;

ALTER TABLE absences
    ADD CONSTRAINT ck_absences_type CHECK (type IN ('VACATION', 'LEAVE', 'SICK_LEAVE')),
    ADD CONSTRAINT ck_absences_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    -- Paid leave only, and required there (FR-002).
    ADD CONSTRAINT ck_absences_cause CHECK (
        (type = 'LEAVE') = (cause IS NOT NULL)
        AND (cause IS NULL OR cause IN (
            'MARRIAGE', 'BIRTH', 'FAMILY_DEATH', 'FAMILY_ILLNESS',
            'HOUSE_MOVE', 'PUBLIC_DUTY', 'OTHER'
        ))
    ),
    -- NULL only for an open sick leave, closed later with the date of discharge.
    ADD CONSTRAINT ck_absences_to_date CHECK (to_date IS NOT NULL OR type = 'SICK_LEAVE'),
    -- A sick leave keeps no free text: the type is already health data (GDPR
    -- art. 9) (FR-007, research.md D-003).
    ADD CONSTRAINT ck_absences_sick_leave_no_text CHECK (type <> 'SICK_LEAVE' OR comment IS NULL),
    ADD CONSTRAINT ck_absences_rejection CHECK ((status = 'REJECTED') = (rejection_reason IS NOT NULL)),
    ADD CONSTRAINT ck_absences_pending CHECK (status <> 'PENDING' OR (resolved_at IS NULL AND resolved_by IS NULL)),
    ADD CONSTRAINT ck_absences_resolved CHECK (
        status NOT IN ('APPROVED', 'REJECTED') OR (resolved_at IS NOT NULL AND resolved_by IS NOT NULL)
    ),
    ADD CONSTRAINT ck_absences_cancelled CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL));

-- --- derechos_vacaciones -> vacation_entitlements -----------------------------

ALTER TABLE derechos_vacaciones RENAME TO vacation_entitlements;

ALTER TABLE vacation_entitlements RENAME COLUMN empleado_id     TO employee_id;
ALTER TABLE vacation_entitlements RENAME COLUMN anio            TO year;
ALTER TABLE vacation_entitlements RENAME COLUMN dias            TO days;
ALTER TABLE vacation_entitlements RENAME COLUMN actualizado_por TO updated_by;
ALTER TABLE vacation_entitlements RENAME COLUMN actualizado_en  TO updated_at;

ALTER TABLE vacation_entitlements RENAME CONSTRAINT derechos_vacaciones_pkey TO vacation_entitlements_pkey;
-- They name no stored value: the column renames carry over into them.
ALTER TABLE vacation_entitlements RENAME CONSTRAINT ck_derechos_vacaciones_anio TO ck_vacation_entitlements_year;
ALTER TABLE vacation_entitlements RENAME CONSTRAINT ck_derechos_vacaciones_dias TO ck_vacation_entitlements_days;
