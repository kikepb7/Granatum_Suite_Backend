-- Immutable audit trail: no updated_at, and application code never issues
-- UPDATE/DELETE against this table - corrections are new rows, not edits.
CREATE TABLE historial_material (
    id              UUID         PRIMARY KEY,
    material_id     UUID         NOT NULL,
    usuario_id      UUID         NOT NULL,
    tipo_cambio     VARCHAR(30)  NOT NULL,
    valor_anterior  VARCHAR(500),
    valor_nuevo     VARCHAR(500),
    motivo          VARCHAR(500) NOT NULL,
    fecha           TIMESTAMPTZ  NOT NULL,
    CONSTRAINT fk_historial_material_material FOREIGN KEY (material_id) REFERENCES materiales (id)
);

CREATE INDEX idx_historial_material_material_id ON historial_material (material_id);
