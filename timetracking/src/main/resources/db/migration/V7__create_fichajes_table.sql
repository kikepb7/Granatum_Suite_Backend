-- A working day. This table is a PROJECTION of current state, not the
-- evidentiary record: the immutable facts live in fichaje_eventos. That
-- separation is what makes the register tamper-proof while still allowing a
-- shift to be closed (constitution, principle III).
--
-- Times are TIMESTAMPTZ so the difference between two of them is always real
-- elapsed time. With a zone-less TIMESTAMP, a shift crossing the October DST
-- change would measure an hour too few or too many and the legal record would
-- be wrong.
CREATE TABLE fichajes (
    id                              UUID         PRIMARY KEY,
    empleado_id                     UUID         NOT NULL REFERENCES empleados (id),
    entrada                         TIMESTAMPTZ  NOT NULL,
    salida                          TIMESTAMPTZ,
    estado                          VARCHAR(15)  NOT NULL,
    minutos_trabajados              INTEGER,
    -- Only ever flips false -> true. A correction that completes an INCOMPLETO
    -- fichaje moves it to CERRADO, but this stays true so a reconstructed day
    -- remains distinguishable from one closed at the time.
    fue_incompleto                  BOOLEAN      NOT NULL DEFAULT FALSE,
    ubicacion_entrada_latitud       NUMERIC(9,6),
    ubicacion_entrada_longitud      NUMERIC(9,6),
    ubicacion_entrada_precision_m   INTEGER,
    ubicacion_salida_latitud        NUMERIC(9,6),
    ubicacion_salida_longitud       NUMERIC(9,6),
    ubicacion_salida_precision_m    INTEGER,
    created_at                      TIMESTAMPTZ  NOT NULL,
    updated_at                      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_fichajes_estado
        CHECK (estado IN ('EN_CURSO', 'CERRADO', 'INCOMPLETO')),
    CONSTRAINT ck_fichajes_salida_posterior
        CHECK (salida IS NULL OR salida > entrada),
    CONSTRAINT ck_fichajes_minutos_no_negativos
        CHECK (minutos_trabajados IS NULL OR minutos_trabajados >= 0)
);

-- The dominant query: a person's days within a date range, newest first.
CREATE INDEX idx_fichajes_empleado_entrada ON fichajes (empleado_id, entrada DESC);

-- Only one open day per person. JPA cannot express a partial unique index, so
-- it is written by hand - and it is the only real defence against the mobile
-- app retrying a clock-in: two concurrent requests both pass a service-side
-- check, because each reads before either writes.
CREATE UNIQUE INDEX uk_fichajes_empleado_en_curso
    ON fichajes (empleado_id)
 WHERE estado = 'EN_CURSO';

-- The daily job scans for still-open days; without this it would walk the whole
-- table every night.
CREATE INDEX idx_fichajes_en_curso ON fichajes (estado) WHERE estado = 'EN_CURSO';

ALTER TABLE fichajes ENABLE ROW LEVEL SECURITY;
