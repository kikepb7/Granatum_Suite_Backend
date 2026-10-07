-- Invoicing (feature 004): the company's own data and the fiscal quarters.

-- One row: what decides whether an invoice was issued or received (FR-029).
CREATE TABLE empresa (
    id               SMALLINT     PRIMARY KEY,
    razon_social     VARCHAR(200) NOT NULL,
    nif              VARCHAR(20)  NOT NULL,
    nif_normalizado  VARCHAR(20)  NOT NULL,
    actualizada_por  UUID         NOT NULL,
    actualizada_en   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_empresa_fila_unica CHECK (id = 1)
);

-- Open or closed. A row is created on first use and then locked with
-- SELECT ... FOR UPDATE by everything that changes an invoice of the quarter or
-- closes it, so an invoice cannot be confirmed while its quarter is being
-- closed (research.md D-016).
CREATE TABLE trimestres (
    anio       SMALLINT NOT NULL,
    trimestre  SMALLINT NOT NULL,
    cerrado    BOOLEAN  NOT NULL DEFAULT FALSE,
    PRIMARY KEY (anio, trimestre),
    CONSTRAINT ck_trimestres_anio CHECK (anio BETWEEN 2000 AND 2100),
    CONSTRAINT ck_trimestres_trimestre CHECK (trimestre BETWEEN 1 AND 4)
);

-- Append-only history of closes and reopenings. A close carries a snapshot of
-- the report totals, so anyone can check later that a declared quarter has
-- not changed; a reopening must say why (FR-032).
CREATE TABLE trimestre_eventos (
    id           UUID         PRIMARY KEY,
    anio         SMALLINT     NOT NULL,
    trimestre    SMALLINT     NOT NULL,
    accion       VARCHAR(10)  NOT NULL,
    motivo       VARCHAR(500),
    totales      JSONB,
    autor_id     UUID         NOT NULL,
    ocurrido_en  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT fk_trimestre_eventos_trimestre
        FOREIGN KEY (anio, trimestre) REFERENCES trimestres (anio, trimestre),
    CONSTRAINT ck_trimestre_eventos_accion CHECK (accion IN ('CIERRE', 'REAPERTURA')),
    CONSTRAINT ck_trimestre_eventos_motivo CHECK ((accion = 'REAPERTURA') = (motivo IS NOT NULL)),
    CONSTRAINT ck_trimestre_eventos_totales CHECK ((accion = 'CIERRE') = (totales IS NOT NULL))
);

CREATE INDEX idx_trimestre_eventos_trimestre ON trimestre_eventos (anio, trimestre, ocurrido_en);

-- Principle VII: deny-by-default for Supabase's data API. Never FORCE: the
-- backend connects as the table owner.
ALTER TABLE empresa ENABLE ROW LEVEL SECURITY;
ALTER TABLE trimestres ENABLE ROW LEVEL SECURITY;
ALTER TABLE trimestre_eventos ENABLE ROW LEVEL SECURITY;
