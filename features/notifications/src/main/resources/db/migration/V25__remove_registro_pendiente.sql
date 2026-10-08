-- Feature 009: there are no sign-up requests any more, so there is nothing to
-- notify the ADMINs about. The notices already stored go (they pointed at rows
-- V24 drops), and the type leaves the CHECK so none can be written again.
-- V23 is not edited (principle II).
DELETE FROM notificaciones WHERE tipo = 'REGISTRO_PENDIENTE';

ALTER TABLE notificaciones DROP CONSTRAINT ck_notificaciones_tipo;

ALTER TABLE notificaciones ADD CONSTRAINT ck_notificaciones_tipo CHECK (tipo IN (
    'FICHAJE_SIN_SALIDA', 'FICHAJE_INCOMPLETO',
    'CORRECCION_PENDIENTE', 'CORRECCION_APROBADA', 'CORRECCION_RECHAZADA',
    'AUSENCIA_PENDIENTE', 'AUSENCIA_APROBADA', 'AUSENCIA_RECHAZADA'
));
