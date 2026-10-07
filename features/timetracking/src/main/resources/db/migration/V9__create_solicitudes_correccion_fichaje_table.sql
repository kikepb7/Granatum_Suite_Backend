-- The only path by which a finalised fichaje can change value.
--
-- Proposed and original values are JSONB because both are immutable documents
-- written once and read whole; they are never queried field by field. Modelling
-- proposed pausas as a child table would add two more tables with their own
-- lifecycle for no query benefit. The lost schema type-checking is covered by
-- validating the proposal in the service before accepting it.
CREATE TABLE solicitudes_correccion_fichaje (
    id                  UUID         PRIMARY KEY,
    fichaje_id          UUID         NOT NULL REFERENCES fichajes (id),
    solicitante_id      UUID         NOT NULL,
    motivo              VARCHAR(500) NOT NULL,
    valores_propuestos  JSONB        NOT NULL,
    -- Filled in at approval time with the fichaje's state immediately before
    -- applying the change. This is what keeps the original recoverable.
    valores_originales  JSONB,
    estado              VARCHAR(12)  NOT NULL,
    resuelta_por_id     UUID,
    resuelta_en         TIMESTAMPTZ,
    motivo_resolucion   VARCHAR(500),
    created_at          TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_solicitudes_estado
        CHECK (estado IN ('PENDIENTE', 'APROBADA', 'RECHAZADA'))
);

CREATE INDEX idx_solicitudes_fichaje_estado
    ON solicitudes_correccion_fichaje (fichaje_id, estado);

ALTER TABLE solicitudes_correccion_fichaje ENABLE ROW LEVEL SECURITY;
