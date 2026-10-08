-- In-app notices (feature 008). One row per recipient.
--
-- No text column on purpose: the message is fixed per type and composed when
-- read, so no free text - and no personal data it could carry - is ever stored
-- (research D-003). What a notice is about is an id; the recipient fetches the
-- details through the routes that already decide what they may see.
--
-- Unlike the working-time register, these rows are deleted: a notice has no
-- legal value, and LimpiezaNotificacionesJob removes old ones (D-005).
CREATE TABLE notificaciones (
    id              UUID        PRIMARY KEY,
    -- The recipient's token subject: the person's staff id.
    destinatario_id UUID        NOT NULL,
    tipo            VARCHAR(24) NOT NULL,
    referencia_id   UUID        NOT NULL,
    creada_en       TIMESTAMPTZ NOT NULL,
    leida_en        TIMESTAMPTZ,
    CONSTRAINT ck_notificaciones_tipo CHECK (tipo IN (
        'FICHAJE_SIN_SALIDA', 'FICHAJE_INCOMPLETO',
        'CORRECCION_PENDIENTE', 'CORRECCION_APROBADA', 'CORRECCION_RECHAZADA',
        'AUSENCIA_PENDIENTE', 'AUSENCIA_APROBADA', 'AUSENCIA_RECHAZADA',
        'REGISTRO_PENDIENTE'
    )),
    -- The same notice never twice (FR-007): the forgotten clock-out check sees
    -- the same open shift every half hour (D-002).
    CONSTRAINT uk_notificaciones UNIQUE (destinatario_id, tipo, referencia_id)
);

-- The inbox, newest first; and the clean-up by age.
CREATE INDEX idx_notificaciones_bandeja ON notificaciones (destinatario_id, creada_en DESC);
CREATE INDEX idx_notificaciones_creada ON notificaciones (creada_en);

-- Who is told what about whom: Supabase's data API must not serve it. Never
-- FORCE: the backend connects as the owner.
ALTER TABLE notificaciones ENABLE ROW LEVEL SECURITY;
