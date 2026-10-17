package com.granatum.core.domain.type

/**
 * What happened, for the security log (FR-017).
 *
 * Mirrors the `CHECK` constraint `ck_security_events_type` (last rewritten in
 * `V30__rename_auth_to_english.sql`);
 * adding a value here without amending the migration makes every insert of it
 * fail, which is the right way round - the database is the one that has to hold.
 */
enum class SecurityEventType {
    LOGIN_SUCCEEDED,
    LOGIN_FAILED,

    /** No account for that email. Recorded with a null account id, never the email. */
    LOGIN_UNKNOWN_ACCOUNT,

    /** An attempt against an account that was already locked. */
    LOGIN_ACCOUNT_LOCKED,
    LOGIN_EMPLOYEE_INACTIVE,

    /** The moment a lockout is applied, as opposed to an attempt during one. */
    ACCOUNT_LOCKED,

    REFRESH_SUCCEEDED,
    REFRESH_REJECTED,

    /**
     * Distinct from [REFRESH_REJECTED] because it means something different:
     * a token that existed and was already used is presented again. It is the
     * only signal of possible theft this feature leaves, so collapsing the two
     * would erase the one event worth investigating.
     */
    REFRESH_TOKEN_REUSED,

    LOGOUT,
    PASSWORD_CHANGED,
    PASSWORD_RESET,
    ACCOUNT_CREATED,

    // Feature 005 (V21). The duplicate sign-up has its own type so the event
    // log can tell what the HTTP response deliberately hides (D-003).
    SIGN_UP_REQUESTED,
    SIGN_UP_DUPLICATE,
    INITIAL_ADMIN_CREATED,
    BOOTSTRAP_REJECTED,
    SIGN_UP_APPROVED,
    SIGN_UP_REJECTED,
    SIGN_UP_CANCELLED,
    SIGN_UP_EXPIRED
}
