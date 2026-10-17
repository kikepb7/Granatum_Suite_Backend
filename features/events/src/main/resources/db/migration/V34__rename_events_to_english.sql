-- English names for feature 011 (docs/english-naming.md): tables, columns, stored status values,
-- indexes and constraints. Renames only, no data is lost. RLS stays enabled: it belongs to the
-- table, and ALTER TABLE ... RENAME keeps it.

-- Tables
ALTER TABLE eventos RENAME TO events;
ALTER TABLE evento_materiales RENAME TO event_materials;

-- events columns
ALTER TABLE events RENAME COLUMN nombre TO name;
ALTER TABLE events RENAME COLUMN descripcion TO description;
ALTER TABLE events RENAME COLUMN fecha TO date;
ALTER TABLE events RENAME COLUMN estado TO status;
ALTER TABLE events RENAME COLUMN creado_por TO created_by;
ALTER TABLE events RENAME COLUMN finalizado_en TO finished_at;

-- event_materials columns
ALTER TABLE event_materials RENAME COLUMN evento_id TO event_id;
ALTER TABLE event_materials RENAME COLUMN cantidad TO quantity;
ALTER TABLE event_materials RENAME COLUMN descripcion TO description;
ALTER TABLE event_materials RENAME COLUMN categoria TO category;

-- Stored status values: the old CHECKs only allow the Spanish ones, so drop them first.
ALTER TABLE events DROP CONSTRAINT chk_eventos_estado;
ALTER TABLE events DROP CONSTRAINT chk_eventos_finalizado;

UPDATE events SET status = 'SCHEDULED' WHERE status = 'PROGRAMADO';
UPDATE events SET status = 'FINISHED' WHERE status = 'FINALIZADO';

ALTER TABLE events
    ADD CONSTRAINT chk_events_status CHECK (status IN ('SCHEDULED', 'FINISHED'));
ALTER TABLE events
    ADD CONSTRAINT chk_events_finished CHECK ((status = 'FINISHED') = (finished_at IS NOT NULL));

ALTER TABLE event_materials DROP CONSTRAINT chk_evento_materiales_cantidad;
ALTER TABLE event_materials
    ADD CONSTRAINT chk_event_materials_quantity CHECK (quantity > 0);

-- Remaining constraints and indexes (column renames already carried them over).
ALTER TABLE events RENAME CONSTRAINT eventos_pkey TO events_pkey;
ALTER TABLE event_materials RENAME CONSTRAINT evento_materiales_pkey TO event_materials_pkey;
ALTER TABLE event_materials RENAME CONSTRAINT evento_materiales_evento_id_fkey TO event_materials_event_id_fkey;
ALTER TABLE event_materials RENAME CONSTRAINT uq_evento_materiales TO uq_event_materials;

ALTER INDEX idx_eventos_estado_fecha RENAME TO idx_events_status_date;
ALTER INDEX idx_evento_materiales_material RENAME TO idx_event_materials_material;
