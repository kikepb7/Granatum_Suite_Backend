-- Audit of each run of the four-year retention purge (constitution, principle
-- III since v2.0.0). APPEND-ONLY and never itself purged.
--
-- Holds NO personal data on purpose: only counts and a cut-off date. A purge
-- log carrying employee ids or specific shift dates would itself outlive the
-- retention period the purge exists to honour, defeating its own purpose.
-- Counts and a date are enough to show an inspection what was destroyed and
-- when.
CREATE TABLE depuraciones_retencion (
    id                      UUID        PRIMARY KEY,
    ejecutada_en            TIMESTAMPTZ NOT NULL,
    -- Everything with an entry date on or before this was eligible.
    fecha_corte             DATE        NOT NULL,
    fichajes_eliminados     INTEGER     NOT NULL,
    pausas_eliminadas       INTEGER     NOT NULL,
    eventos_eliminados      INTEGER     NOT NULL,
    solicitudes_eliminadas  INTEGER     NOT NULL,
    CONSTRAINT ck_depuraciones_recuentos_no_negativos
        CHECK (fichajes_eliminados >= 0
           AND pausas_eliminadas >= 0
           AND eventos_eliminados >= 0
           AND solicitudes_eliminadas >= 0)
);

CREATE INDEX idx_depuraciones_ejecutada ON depuraciones_retencion (ejecutada_en DESC);

ALTER TABLE depuraciones_retencion ENABLE ROW LEVEL SECURITY;
