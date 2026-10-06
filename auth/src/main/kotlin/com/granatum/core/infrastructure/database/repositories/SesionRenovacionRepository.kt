package com.granatum.core.infrastructure.database.repositories

import com.granatum.core.infrastructure.database.entities.SesionRenovacionEntity
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Sessions. Extends [Repository] rather than `JpaRepository` for the same
 * reason as the others: the only deletion this table admits is the scheduled
 * purge below, and it is spelled out as a bounded query rather than inherited
 * as a general-purpose `delete`.
 *
 * ## Why every modifying query declares `@Transactional`
 *
 * A `@Modifying` JPQL statement needs an active transaction, and a method
 * declared with `@Query` on a `Repository` interface is **read-only by
 * default** - `SimpleJpaRepository` only annotates its own inherited methods.
 * Without this, each of these fails at runtime with "No active transaction for
 * update or delete query", and only on the paths whose caller happens to have
 * none.
 *
 * Declaring it here gives each query its own short transaction and joins the
 * caller's when there is one, which is what both kinds of caller need: the
 * services that keep the ~110 ms Argon2 hash outside any transaction have none
 * to offer, and `SesionService.rotar` has one that must be joined so the
 * conditional UPDATE and the new session row commit together.
 */
interface SesionRenovacionRepository : Repository<SesionRenovacionEntity, UUID> {

    fun save(sesion: SesionRenovacionEntity): SesionRenovacionEntity

    fun findByTokenHash(tokenHash: String): SesionRenovacionEntity?

    /**
     * Claims the token for rotation, and **is** the single-use guarantee.
     *
     * The whole "still usable" condition travels in the `WHERE`, so the database
     * decides the winner: of two simultaneous requests presenting the same
     * token, one updates a row and the other updates none. Reading the row,
     * checking it in Kotlin and writing afterwards leaves a window in which both
     * pass the check and two token pairs are issued - which is exactly the hole
     * task T060 of feature 001 had to be rewritten to close, and SC-004 asks for
     * 100%, which only this gives.
     *
     * @return rows affected: 1 means this caller claimed it, 0 means it was
     * already used, revoked, expired or never existed.
     */
    // flushAutomatically + clearAutomatically because the caller reads the row
    // before this runs: without clearing, the stale entity would stay in the
    // persistence context with `usadaEn = null` while the database says
    // otherwise, and any later read in the same transaction would see the lie.
    //
    // Both flags require a transaction to be already joined, so they are only
    // safe here because `SesionService.rotar` is always transactional. The two
    // revocation queries below deliberately do without them for exactly that
    // reason - see their comments.
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """
        UPDATE SesionRenovacionEntity s
           SET s.usadaEn = :ahora
         WHERE s.tokenHash = :tokenHash
           AND s.usadaEn IS NULL
           AND s.revocadaEn IS NULL
           AND s.expiraEn > :ahora
        """
    )
    fun marcarUsadaSiVigente(
        @Param("tokenHash") tokenHash: String,
        @Param("ahora") ahora: Instant
    ): Int

    /**
     * Logout (FR-009). Affects **only** this row, so the person's other
     * sessions keep working (FR-012, SC-006).
     *
     * @return rows affected; 0 is not an error - logging out twice is not a
     * failure, and a 404 there would distinguish existing tokens from
     * non-existent ones.
     */
    // No flushAutomatically/clearAutomatically here, unlike above: those flags
    // need a transaction already joined, and this is also called from
    // non-transactional code - `CuentaAccesoService` keeps the ~110 ms Argon2
    // hash outside any transaction on purpose, so it has none to join. Nothing
    // is re-read after this call either, so there is nothing to go stale.
    @Transactional
    @Modifying
    @Query(
        """
        UPDATE SesionRenovacionEntity s
           SET s.revocadaEn = :ahora, s.motivoRevocacion = :motivo
         WHERE s.tokenHash = :tokenHash
           AND s.usadaEn IS NULL
           AND s.revocadaEn IS NULL
        """
    )
    fun revocarPorTokenHash(
        @Param("tokenHash") tokenHash: String,
        @Param("motivo") motivo: String,
        @Param("ahora") ahora: Instant
    ): Int

    /**
     * Every live session of one account, for a password reset (FR-025, SC-007)
     * or a password change. Already-used rows are left alone: they cannot be
     * presented again anyway, and rewriting them would lose the record of how
     * they ended.
     */
    // Same as above: callers may hold no transaction.
    @Transactional
    @Modifying
    @Query(
        """
        UPDATE SesionRenovacionEntity s
           SET s.revocadaEn = :ahora, s.motivoRevocacion = :motivo
         WHERE s.cuenta.id = :cuentaId
           AND s.usadaEn IS NULL
           AND s.revocadaEn IS NULL
        """
    )
    fun revocarTodasDeCuenta(
        @Param("cuentaId") cuentaId: UUID,
        @Param("motivo") motivo: String,
        @Param("ahora") ahora: Instant
    ): Int

    fun countByCuentaIdAndUsadaEnIsNullAndRevocadaEnIsNull(cuentaId: UUID): Long

    /**
     * The only deletion this module performs, and the only one it can: there is
     * no endpoint and no role that reaches it, only the scheduled job.
     *
     * Bounded by `:corte` by construction, and restricted to rows that are
     * already dead - used, revoked or expired. A live session is untouchable
     * however old it is.
     *
     * This does not brush against principle III, which protects the
     * working-time register; a consumed token proves nothing that
     * `eventos_seguridad` does not already hold. Without it the table grows
     * without bound: renewing every 15 minutes, one person generates roughly
     * 35,000 rows a year.
     */
    @Transactional
    @Modifying
    @Query(
        """
        DELETE FROM SesionRenovacionEntity s
         WHERE (s.usadaEn IS NOT NULL AND s.usadaEn < :corte)
            OR (s.revocadaEn IS NOT NULL AND s.revocadaEn < :corte)
            OR (s.expiraEn < :corte)
        """
    )
    fun purgarMuertasAntesDe(@Param("corte") corte: Instant): Int
}
