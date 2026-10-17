-- Feature 011: events and the materials they take from the inventory
-- (specs/011-events/data-model.md).

CREATE TABLE eventos (
    id             UUID         PRIMARY KEY,
    nombre         VARCHAR(100) NOT NULL,
    descripcion    VARCHAR(500),
    fecha          DATE         NOT NULL,
    estado         VARCHAR(20)  NOT NULL,
    -- The subject of the JWT that created it. No foreign key: no table references
    -- another feature's tables (research.md D-007).
    creado_por     UUID         NOT NULL,
    finalizado_en  TIMESTAMPTZ,
    created_at     TIMESTAMPTZ  NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL,
    CONSTRAINT chk_eventos_estado CHECK (estado IN ('PROGRAMADO', 'FINALIZADO')),
    CONSTRAINT chk_eventos_finalizado CHECK ((estado = 'FINALIZADO') = (finalizado_en IS NOT NULL))
);

-- The daily job looks for scheduled events before today; the list is by state and date.
CREATE INDEX idx_eventos_estado_fecha ON eventos (estado, fecha);

CREATE TABLE evento_materiales (
    id           UUID         PRIMARY KEY,
    evento_id    UUID         NOT NULL REFERENCES eventos (id) ON DELETE CASCADE,
    -- The material's id in the inventory, deliberately without a foreign key
    -- (research.md D-007): while the event is scheduled the inventory refuses to
    -- delete it; afterwards the line keeps saying what it was.
    material_id  UUID         NOT NULL,
    cantidad     INTEGER      NOT NULL,
    descripcion  VARCHAR(500) NOT NULL,
    categoria    VARCHAR(100) NOT NULL,
    CONSTRAINT chk_evento_materiales_cantidad CHECK (cantidad > 0),
    CONSTRAINT uq_evento_materiales UNIQUE (evento_id, material_id)
);

-- UsoMateriales asks whether a material is out at any scheduled event.
CREATE INDEX idx_evento_materiales_material ON evento_materiales (material_id);

-- Principle VII: in the same migration that creates them.
ALTER TABLE eventos ENABLE ROW LEVEL SECURITY;
ALTER TABLE evento_materiales ENABLE ROW LEVEL SECURITY;
