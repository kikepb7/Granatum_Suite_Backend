-- English names for the inventory schema (docs/english-naming.md, 2026-10-18):
-- tables, columns, constraints, indexes and the stored change types. Renamed,
-- never recreated, so no row is lost and everything that hangs off these
-- tables stays attached: the row level security V5 enabled (principle VII),
-- material_history's foreign key, and the foreign key events' V27 points at
-- the materials table.
--
-- No SQL function or trigger mentions these tables, so there is none to
-- recreate.

-- --- categories --------------------------------------------------------------

ALTER TABLE categorias RENAME TO categories;
ALTER TABLE categories RENAME COLUMN nombre TO name;
ALTER TABLE categories RENAME COLUMN descripcion TO description;
ALTER TABLE categories RENAME CONSTRAINT categorias_pkey TO categories_pkey;
ALTER TABLE categories RENAME CONSTRAINT uk_categorias_nombre TO uk_categories_name;

-- --- materials ---------------------------------------------------------------

ALTER TABLE materiales RENAME TO materials;
ALTER TABLE materials RENAME COLUMN categoria_id TO category_id;
ALTER TABLE materials RENAME COLUMN descripcion TO description;
ALTER TABLE materials RENAME COLUMN alto TO height;
ALTER TABLE materials RENAME COLUMN ancho TO width;
ALTER TABLE materials RENAME COLUMN cantidad TO quantity;
ALTER TABLE materials RENAME COLUMN almacen TO warehouse;
ALTER TABLE materials RENAME COLUMN seccion TO section;
ALTER TABLE materials RENAME COLUMN imagen_ruta TO image_path;
ALTER TABLE materials RENAME COLUMN fecha_alta TO created_at;
ALTER TABLE materials RENAME COLUMN fecha_ultima_modificacion TO updated_at;

ALTER TABLE materials RENAME CONSTRAINT materiales_pkey TO materials_pkey;
ALTER TABLE materials RENAME CONSTRAINT fk_materiales_categoria TO fk_materials_category;
ALTER INDEX idx_materiales_categoria_id RENAME TO idx_materials_category_id;
ALTER INDEX idx_materiales_localizacion RENAME TO idx_materials_location;

-- Postgres already rewrote these checks to the new column names; they are
-- recreated so that their names, and their text in the schema, say the same.
ALTER TABLE materials
    DROP CONSTRAINT chk_materiales_cantidad,
    DROP CONSTRAINT chk_materiales_dimensiones,
    DROP CONSTRAINT chk_materiales_almacen,
    DROP CONSTRAINT chk_materiales_seccion,
    ADD CONSTRAINT chk_materials_quantity   CHECK (quantity >= 0),
    ADD CONSTRAINT chk_materials_dimensions CHECK (height > 0 AND width > 0),
    -- Fixed, provisional names (research.md D-006): renaming them means
    -- changing these two checks, the Warehouse enum and the request validation.
    ADD CONSTRAINT chk_materials_warehouse  CHECK (warehouse IN ('A', 'B', 'C')),
    ADD CONSTRAINT chk_materials_section    CHECK (section BETWEEN 1 AND 5);

-- --- material_history --------------------------------------------------------

ALTER TABLE historial_material RENAME TO material_history;
ALTER TABLE material_history RENAME COLUMN usuario_id TO user_id;
ALTER TABLE material_history RENAME COLUMN tipo_cambio TO change_type;
ALTER TABLE material_history RENAME COLUMN valor_anterior TO previous_value;
ALTER TABLE material_history RENAME COLUMN valor_nuevo TO new_value;
ALTER TABLE material_history RENAME COLUMN motivo TO reason;
ALTER TABLE material_history RENAME COLUMN fecha TO date;

ALTER TABLE material_history RENAME CONSTRAINT historial_material_pkey TO material_history_pkey;
ALTER TABLE material_history RENAME CONSTRAINT fk_historial_material_material TO fk_material_history_material;
ALTER INDEX idx_historial_material_material_id RENAME TO idx_material_history_material_id;

-- The stored change types, translated one to one. This is the schema's
-- vocabulary changing, not an entry being edited: every row keeps saying the
-- same thing, in the words the application now reads. The application itself
-- still never updates or deletes a history row (constitution principle III).
UPDATE material_history
   SET change_type = CASE change_type
                         WHEN 'CANTIDAD'        THEN 'QUANTITY'
                         WHEN 'ESTADO'          THEN 'STATUS'
                         WHEN 'UBICACION'       THEN 'LOCATION'
                         WHEN 'PRECIO_UNITARIO' THEN 'UNIT_PRICE'
                         WHEN 'PROVEEDOR'       THEN 'SUPPLIER'
                         WHEN 'OTRO'            THEN 'OTHER'
                         ELSE change_type
                     END;

-- There was no check on the change type before; with the vocabulary settled,
-- one keeps any other word out.
ALTER TABLE material_history
    ADD CONSTRAINT chk_material_history_change_type
        CHECK (change_type IN ('QUANTITY', 'STATUS', 'LOCATION', 'UNIT_PRICE', 'SUPPLIER', 'OTHER'));
