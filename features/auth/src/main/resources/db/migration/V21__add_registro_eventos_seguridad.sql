-- The security events of feature 005 (specs/005-staff-registration research.md
-- D-009). V14 is not edited (principle II): its CHECK is replaced by one with
-- the thirteen original types plus the eight new ones. None carries personal
-- data - cuenta_id when there is an account, nothing when there is not.
ALTER TABLE eventos_seguridad DROP CONSTRAINT ck_eventos_seguridad_tipo;

ALTER TABLE eventos_seguridad ADD CONSTRAINT ck_eventos_seguridad_tipo CHECK (tipo IN (
    'LOGIN_CORRECTO',
    'LOGIN_FALLIDO',
    'LOGIN_CUENTA_DESCONOCIDA',
    'LOGIN_CUENTA_BLOQUEADA',
    'LOGIN_EMPLEADO_INACTIVO',
    'CUENTA_BLOQUEADA',
    'RENOVACION_CORRECTA',
    'RENOVACION_RECHAZADA',
    'RENOVACION_TOKEN_REUTILIZADO',
    'CIERRE_SESION',
    'PASSWORD_CAMBIADA',
    'PASSWORD_RESTABLECIDA',
    'CUENTA_CREADA',
    'REGISTRO_SOLICITADO',
    'REGISTRO_DUPLICADO',
    'ADMIN_INICIAL_CREADO',
    'ARRANQUE_RECHAZADO',
    'REGISTRO_APROBADO',
    'REGISTRO_RECHAZADO',
    'REGISTRO_ANULADO',
    'REGISTRO_CADUCADO'
));
