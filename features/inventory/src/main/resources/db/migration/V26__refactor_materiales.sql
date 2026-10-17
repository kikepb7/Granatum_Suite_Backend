-- Feature 010: a material is now exactly category, description, height and
-- width (cm), quantity, location (warehouse A/B/C + section 1-5), an optional
-- image and its two dates (specs/010-inventory-materials-refactor).
--
-- The existing rows are test data (spec FR-022), and the new columns are
-- NOT NULL with no sensible value for them - a description or a location
-- cannot be made up - so they are deleted rather than converted
-- (research.md D-007). historial_material goes first because its rows
-- reference the materials being deleted. That is this migration emptying test
-- data, not the application mutating an audit trail: the repository still
-- offers no UPDATE or DELETE (constitution principle III).
DELETE FROM historial_material;
DELETE FROM material_fotos;
DELETE FROM materiales;

-- Photos by URL are replaced by one image in Supabase Storage, referenced by
-- materiales.imagen_ruta.
DROP TABLE material_fotos;

-- Altered, not recreated: this keeps historial_material's foreign key and the
-- row level security V5 enabled on materiales (principle VII) without
-- declaring either again.
ALTER TABLE materiales
    DROP CONSTRAINT chk_materiales_cantidad,
    DROP COLUMN nombre,
    DROP COLUMN cantidad_total,
    DROP COLUMN diametro,
    DROP COLUMN unidad_medida,
    DROP COLUMN color,
    DROP COLUMN material_fisico,
    DROP COLUMN estado,
    DROP COLUMN precio_unitario,
    DROP COLUMN proveedor,
    DROP COLUMN ubicacion;

ALTER TABLE materiales RENAME COLUMN cantidad_disponible TO cantidad;

ALTER TABLE materiales
    ADD COLUMN descripcion  VARCHAR(500) NOT NULL,
    ADD COLUMN almacen      VARCHAR(1)   NOT NULL,
    ADD COLUMN seccion      SMALLINT     NOT NULL,
    -- Path of the object in the private bucket; never leaves through HTTP
    -- (clients get a temporary signed URL instead, research.md D-005).
    ADD COLUMN imagen_ruta  VARCHAR(300),
    ADD CONSTRAINT chk_materiales_cantidad    CHECK (cantidad >= 0),
    ADD CONSTRAINT chk_materiales_dimensiones CHECK (alto > 0 AND ancho > 0),
    -- Fixed, provisional names (research.md D-006): renaming them means
    -- changing these two checks, the Almacen enum and the request validation.
    ADD CONSTRAINT chk_materiales_almacen     CHECK (almacen IN ('A', 'B', 'C')),
    ADD CONSTRAINT chk_materiales_seccion     CHECK (seccion BETWEEN 1 AND 5);

CREATE INDEX idx_materiales_localizacion ON materiales (almacen, seccion);
