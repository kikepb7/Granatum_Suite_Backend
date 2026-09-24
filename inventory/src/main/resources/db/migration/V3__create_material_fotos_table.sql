CREATE TABLE material_fotos (
    material_id UUID         NOT NULL,
    foto_url    VARCHAR(500) NOT NULL,
    CONSTRAINT fk_material_fotos_material FOREIGN KEY (material_id) REFERENCES materiales (id) ON DELETE CASCADE
);

CREATE INDEX idx_material_fotos_material_id ON material_fotos (material_id);
