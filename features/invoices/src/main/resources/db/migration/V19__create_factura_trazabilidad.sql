-- Invoicing (feature 004): what the recognition proposed and what was changed.
-- Both append-only: their repositories have no delete.

-- Every recognition attempt, as it came back (FR-005, research.md D-009). Kept
-- even after the draft is corrected, so precision can be measured by comparing
-- proposal and confirmation, and the real cost by its tokens.
CREATE TABLE factura_reconocimientos (
    id              UUID          PRIMARY KEY,
    factura_id      UUID          NOT NULL REFERENCES facturas (id),
    resultado       VARCHAR(20)   NOT NULL,
    modelo          VARCHAR(60)   NOT NULL,
    propuesta       JSONB,
    campos_dudosos  JSONB,
    tokens_entrada  INTEGER,
    tokens_salida   INTEGER,
    -- The kind of error, never anything from the document.
    error           VARCHAR(200),
    creado_en       TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_reconocimientos_resultado CHECK (
        resultado IN ('RECONOCIDA', 'NO_ES_FACTURA', 'VARIAS_FACTURAS', 'RECHAZADA', 'ERROR')
    )
);

CREATE INDEX idx_reconocimientos_factura ON factura_reconocimientos (factura_id, creado_en);

-- Every change to an invoice already confirmed: the whole invoice as it was
-- before, who changed it and when (FR-016, FR-017).
CREATE TABLE factura_cambios (
    id                  UUID         PRIMARY KEY,
    factura_id          UUID         NOT NULL REFERENCES facturas (id),
    accion              VARCHAR(20)  NOT NULL,
    valores_anteriores  JSONB        NOT NULL,
    autor_id            UUID         NOT NULL,
    ocurrido_en         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_cambios_accion CHECK (accion IN ('CORRECCION', 'DESCARTE', 'RECLASIFICACION'))
);

CREATE INDEX idx_cambios_factura ON factura_cambios (factura_id, ocurrido_en);

ALTER TABLE factura_reconocimientos ENABLE ROW LEVEL SECURITY;
ALTER TABLE factura_cambios ENABLE ROW LEVEL SECURITY;
