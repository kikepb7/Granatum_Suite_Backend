-- English names for the notices (docs/english-naming.md). The table, its
-- columns and the stored type values move to English without losing a row;
-- V23 and V25 are not edited (principle II).
--
-- Renaming keeps the data, the primary key, the unique key's columns and row
-- level security (principle VII): RLS belongs to the table, not to its name.
-- No SQL function or trigger refers to this table.

ALTER TABLE notificaciones RENAME TO notifications;

ALTER TABLE notifications RENAME COLUMN destinatario_id TO recipient_id;
ALTER TABLE notifications RENAME COLUMN tipo            TO type;
ALTER TABLE notifications RENAME COLUMN referencia_id   TO reference_id;
ALTER TABLE notifications RENAME COLUMN creada_en       TO created_at;
ALTER TABLE notifications RENAME COLUMN leida_en        TO read_at;

-- The CHECK lists the Spanish values: drop it before rewriting them.
ALTER TABLE notifications DROP CONSTRAINT ck_notificaciones_tipo;

UPDATE notifications SET type = CASE type
    WHEN 'FICHAJE_SIN_SALIDA'   THEN 'SHIFT_WITHOUT_CLOCK_OUT'
    WHEN 'FICHAJE_INCOMPLETO'   THEN 'SHIFT_INCOMPLETE'
    WHEN 'CORRECCION_PENDIENTE' THEN 'CORRECTION_PENDING'
    WHEN 'CORRECCION_APROBADA'  THEN 'CORRECTION_APPROVED'
    WHEN 'CORRECCION_RECHAZADA' THEN 'CORRECTION_REJECTED'
    WHEN 'AUSENCIA_PENDIENTE'   THEN 'ABSENCE_PENDING'
    WHEN 'AUSENCIA_APROBADA'    THEN 'ABSENCE_APPROVED'
    WHEN 'AUSENCIA_RECHAZADA'   THEN 'ABSENCE_REJECTED'
    ELSE type
END;

ALTER TABLE notifications ADD CONSTRAINT ck_notifications_type CHECK (type IN (
    'SHIFT_WITHOUT_CLOCK_OUT', 'SHIFT_INCOMPLETE',
    'CORRECTION_PENDING', 'CORRECTION_APPROVED', 'CORRECTION_REJECTED',
    'ABSENCE_PENDING', 'ABSENCE_APPROVED', 'ABSENCE_REJECTED'
));

-- Constraint and index names follow the table.
ALTER TABLE notifications RENAME CONSTRAINT notificaciones_pkey TO notifications_pkey;
ALTER TABLE notifications RENAME CONSTRAINT uk_notificaciones TO uk_notifications;
ALTER INDEX idx_notificaciones_bandeja RENAME TO idx_notifications_inbox;
ALTER INDEX idx_notificaciones_creada RENAME TO idx_notifications_created_at;
