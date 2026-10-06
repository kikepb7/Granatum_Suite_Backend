package com.granatum.core.domain.type

/**
 * What happened, for the security log (FR-017).
 *
 * Mirrors the `CHECK` constraint in `V14__create_eventos_seguridad_table.sql`;
 * adding a value here without amending the migration makes every insert of it
 * fail, which is the right way round - the database is the one that has to hold.
 */
enum class TipoEventoSeguridad {
    LOGIN_CORRECTO,
    LOGIN_FALLIDO,

    /** No account for that email. Recorded with a null account id, never the email. */
    LOGIN_CUENTA_DESCONOCIDA,

    /** An attempt against an account that was already locked. */
    LOGIN_CUENTA_BLOQUEADA,
    LOGIN_EMPLEADO_INACTIVO,

    /** The moment a lockout is applied, as opposed to an attempt during one. */
    CUENTA_BLOQUEADA,

    RENOVACION_CORRECTA,
    RENOVACION_RECHAZADA,

    /**
     * Distinct from [RENOVACION_RECHAZADA] because it means something different:
     * a token that existed and was already used is presented again. It is the
     * only signal of possible theft this feature leaves, so collapsing the two
     * would erase the one event worth investigating.
     */
    RENOVACION_TOKEN_REUTILIZADO,

    CIERRE_SESION,
    PASSWORD_CAMBIADA,
    PASSWORD_RESTABLECIDA,
    CUENTA_CREADA
}
