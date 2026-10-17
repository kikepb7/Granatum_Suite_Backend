-- English names for the auth module (docs/english-naming.md, 2026-10-18).
--
-- Tables, columns, constraints and indexes are renamed in place, so no row is
-- lost, and the stored enum values (roles, security event types, revocation
-- reasons) are rewritten with their CHECK constraints dropped first and
-- re-created with the new values. V12-V24 are not edited (principle II).
--
-- Row level security is a property of the table and survives a rename: nothing
-- to re-enable. There are no functions, triggers or views over these tables.
--
-- Deploy consequence: access tokens issued before this carry the old role
-- names and stop being valid; everyone signs in again once.

-- accounts (was cuentas_acceso) -------------------------------------------------

ALTER TABLE cuentas_acceso RENAME TO accounts;

ALTER TABLE accounts RENAME COLUMN empleado_id TO employee_id;
ALTER TABLE accounts RENAME COLUMN rol TO role;
ALTER TABLE accounts RENAME COLUMN requiere_cambio_password TO password_change_required;
ALTER TABLE accounts RENAME COLUMN intentos_fallidos TO failed_attempts;
ALTER TABLE accounts RENAME COLUMN nivel_bloqueo TO lockout_level;
ALTER TABLE accounts RENAME COLUMN bloqueada_hasta TO locked_until;

ALTER TABLE accounts RENAME CONSTRAINT cuentas_acceso_pkey TO accounts_pkey;
ALTER TABLE accounts RENAME CONSTRAINT uk_cuentas_acceso_email TO uk_accounts_email;
ALTER TABLE accounts RENAME CONSTRAINT uk_cuentas_acceso_empleado TO uk_accounts_employee;
ALTER TABLE accounts RENAME CONSTRAINT ck_cuentas_acceso_nivel TO ck_accounts_lockout_level;

ALTER TABLE accounts DROP CONSTRAINT ck_cuentas_acceso_rol;
UPDATE accounts SET role = CASE role
    WHEN 'ENCARGADO' THEN 'MANAGER'
    WHEN 'EMPLEADO' THEN 'EMPLOYEE'
    WHEN 'REPRESENTANTE' THEN 'REPRESENTATIVE'
    ELSE role
END;
-- The four roles of constitution principle IV, no more and no fewer.
ALTER TABLE accounts ADD CONSTRAINT ck_accounts_role
    CHECK (role IN ('ADMIN', 'MANAGER', 'EMPLOYEE', 'REPRESENTATIVE'));

-- refresh_sessions (was sesiones_renovacion) ------------------------------------

ALTER TABLE sesiones_renovacion RENAME TO refresh_sessions;

ALTER TABLE refresh_sessions RENAME COLUMN cuenta_id TO account_id;
ALTER TABLE refresh_sessions RENAME COLUMN expira_en TO expires_at;
ALTER TABLE refresh_sessions RENAME COLUMN creada_en TO created_at;
ALTER TABLE refresh_sessions RENAME COLUMN usada_en TO used_at;
ALTER TABLE refresh_sessions RENAME COLUMN revocada_en TO revoked_at;
ALTER TABLE refresh_sessions RENAME COLUMN motivo_revocacion TO revocation_reason;

ALTER TABLE refresh_sessions RENAME CONSTRAINT sesiones_renovacion_pkey TO refresh_sessions_pkey;
ALTER TABLE refresh_sessions RENAME CONSTRAINT sesiones_renovacion_cuenta_id_fkey TO refresh_sessions_account_id_fkey;
ALTER TABLE refresh_sessions RENAME CONSTRAINT uk_sesiones_renovacion_token TO uk_refresh_sessions_token;
ALTER TABLE refresh_sessions RENAME CONSTRAINT ck_sesiones_renovacion_token_hash TO ck_refresh_sessions_token_hash;
-- Its expression follows the column renames on its own.
ALTER TABLE refresh_sessions RENAME CONSTRAINT ck_sesiones_renovacion_revocacion TO ck_refresh_sessions_revocation;

ALTER TABLE refresh_sessions DROP CONSTRAINT ck_sesiones_renovacion_motivo;
UPDATE refresh_sessions SET revocation_reason = 'PASSWORD_CHANGE' WHERE revocation_reason = 'CAMBIO_PASSWORD';
ALTER TABLE refresh_sessions ADD CONSTRAINT ck_refresh_sessions_reason
    CHECK (revocation_reason IS NULL
           OR revocation_reason IN ('LOGOUT', 'RESET', 'PASSWORD_CHANGE'));

ALTER INDEX idx_sesiones_renovacion_cuenta RENAME TO idx_refresh_sessions_account;

-- security_events (was eventos_seguridad) ----------------------------------------
--
-- Still append-only for the application: rewriting the type of rows already
-- written is a one-off schema migration, not a code path.

ALTER TABLE eventos_seguridad RENAME TO security_events;

ALTER TABLE security_events RENAME COLUMN cuenta_id TO account_id;
ALTER TABLE security_events RENAME COLUMN tipo TO type;
ALTER TABLE security_events RENAME COLUMN ocurrido_en TO occurred_at;

ALTER TABLE security_events RENAME CONSTRAINT eventos_seguridad_pkey TO security_events_pkey;

ALTER TABLE security_events DROP CONSTRAINT ck_eventos_seguridad_tipo;
UPDATE security_events SET type = CASE type
    WHEN 'LOGIN_CORRECTO' THEN 'LOGIN_SUCCEEDED'
    WHEN 'LOGIN_FALLIDO' THEN 'LOGIN_FAILED'
    WHEN 'LOGIN_CUENTA_DESCONOCIDA' THEN 'LOGIN_UNKNOWN_ACCOUNT'
    WHEN 'LOGIN_CUENTA_BLOQUEADA' THEN 'LOGIN_ACCOUNT_LOCKED'
    WHEN 'LOGIN_EMPLEADO_INACTIVO' THEN 'LOGIN_EMPLOYEE_INACTIVE'
    WHEN 'CUENTA_BLOQUEADA' THEN 'ACCOUNT_LOCKED'
    WHEN 'RENOVACION_CORRECTA' THEN 'REFRESH_SUCCEEDED'
    WHEN 'RENOVACION_RECHAZADA' THEN 'REFRESH_REJECTED'
    WHEN 'RENOVACION_TOKEN_REUTILIZADO' THEN 'REFRESH_TOKEN_REUSED'
    WHEN 'CIERRE_SESION' THEN 'LOGOUT'
    WHEN 'PASSWORD_CAMBIADA' THEN 'PASSWORD_CHANGED'
    WHEN 'PASSWORD_RESTABLECIDA' THEN 'PASSWORD_RESET'
    WHEN 'CUENTA_CREADA' THEN 'ACCOUNT_CREATED'
    WHEN 'REGISTRO_SOLICITADO' THEN 'SIGN_UP_REQUESTED'
    WHEN 'REGISTRO_DUPLICADO' THEN 'SIGN_UP_DUPLICATE'
    WHEN 'ADMIN_INICIAL_CREADO' THEN 'INITIAL_ADMIN_CREATED'
    WHEN 'ARRANQUE_RECHAZADO' THEN 'BOOTSTRAP_REJECTED'
    WHEN 'REGISTRO_APROBADO' THEN 'SIGN_UP_APPROVED'
    WHEN 'REGISTRO_RECHAZADO' THEN 'SIGN_UP_REJECTED'
    WHEN 'REGISTRO_ANULADO' THEN 'SIGN_UP_CANCELLED'
    WHEN 'REGISTRO_CADUCADO' THEN 'SIGN_UP_EXPIRED'
    ELSE type
END;
ALTER TABLE security_events ADD CONSTRAINT ck_security_events_type CHECK (type IN (
    'LOGIN_SUCCEEDED',
    'LOGIN_FAILED',
    'LOGIN_UNKNOWN_ACCOUNT',
    'LOGIN_ACCOUNT_LOCKED',
    'LOGIN_EMPLOYEE_INACTIVE',
    'ACCOUNT_LOCKED',
    'REFRESH_SUCCEEDED',
    'REFRESH_REJECTED',
    'REFRESH_TOKEN_REUSED',
    'LOGOUT',
    'PASSWORD_CHANGED',
    'PASSWORD_RESET',
    'ACCOUNT_CREATED',
    'SIGN_UP_REQUESTED',
    'SIGN_UP_DUPLICATE',
    'INITIAL_ADMIN_CREATED',
    'BOOTSTRAP_REJECTED',
    'SIGN_UP_APPROVED',
    'SIGN_UP_REJECTED',
    'SIGN_UP_CANCELLED',
    'SIGN_UP_EXPIRED'
));

ALTER INDEX idx_eventos_seguridad_cuenta RENAME TO idx_security_events_account;
