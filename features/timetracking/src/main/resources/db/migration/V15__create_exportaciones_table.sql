-- INSERT-ONLY. One row per export or monthly download of the working-time
-- register, completed or interrupted (FR-025, specs/003-timetracking-export).
--
-- Never UPDATEd: an export is recorded once, when it has finished or failed, so
-- there is no "in progress" row that later changes (FR-027). The only DELETE is
-- the automatic retention purge, bounded by its cut-off, once every record an
-- export covered has itself been purged.
--
-- No foreign keys, for the same reason as fichaje_eventos: nothing must be able
-- to cascade a delete into an immutable table.
--
-- Holds NO exported data (FR-026): no hours, no names, no identity documents,
-- not the file. Identifiers, the range, the row count and the fingerprint only.
CREATE TABLE exportaciones (
    id               UUID        PRIMARY KEY,
    -- The JWT subject of whoever exported.
    solicitante_id   UUID        NOT NULL,
    -- Which version of the file they received: REPRESENTANTE gets no identity
    -- document column (FR-012).
    rol_solicitante  VARCHAR(20) NOT NULL,
    alcance          VARCHAR(10) NOT NULL,
    -- NULL exactly when the whole workforce was exported.
    empleado_id      UUID,
    -- The EFFECTIVE range, already clipped by the retention period (FR-017).
    desde            DATE        NOT NULL,
    hasta            DATE        NOT NULL,
    generada_en      TIMESTAMPTZ NOT NULL,
    -- false when the client disconnected or the export timed out.
    completada       BOOLEAN     NOT NULL,
    -- Data rows written; for an interrupted export, those written before the cut.
    filas            INTEGER     NOT NULL,
    -- SHA-256 of the exact bytes sent, BOM included, in hex. Deliberately NOT
    -- unique: two identical exports share a fingerprint by design (FR-011), and
    -- verifying a file returns every export that produced it. NULL for an
    -- interrupted export - the hash of half a file identifies nothing anyone has.
    huella           VARCHAR(64),
    CONSTRAINT ck_exportaciones_rol
        CHECK (rol_solicitante IN ('ADMIN', 'ENCARGADO', 'EMPLEADO', 'REPRESENTANTE')),
    CONSTRAINT ck_exportaciones_alcance
        CHECK (alcance IN ('PERSONA', 'PLANTILLA', 'MENSUAL')),
    CONSTRAINT ck_exportaciones_empleado
        CHECK ((alcance = 'PLANTILLA') = (empleado_id IS NULL)),
    CONSTRAINT ck_exportaciones_rango CHECK (hasta >= desde),
    CONSTRAINT ck_exportaciones_filas CHECK (filas >= 0),
    CONSTRAINT ck_exportaciones_huella
        CHECK (huella IS NULL OR char_length(huella) = 64),
    CONSTRAINT ck_exportaciones_completada
        CHECK (completada = (huella IS NOT NULL))
);

-- FR-028: verifying a file looks it up by fingerprint.
CREATE INDEX idx_exportaciones_huella ON exportaciones (huella);
-- FR-029: who exported whose data.
CREATE INDEX idx_exportaciones_empleado ON exportaciones (empleado_id, generada_en);
-- The retention purge deletes by end of range.
CREATE INDEX idx_exportaciones_hasta ON exportaciones (hasta);

-- Deny by default to anything that is not the table owner, so Supabase's data
-- API exposes nothing. Never FORCE: the backend connects as the owner.
ALTER TABLE exportaciones ENABLE ROW LEVEL SECURITY;
