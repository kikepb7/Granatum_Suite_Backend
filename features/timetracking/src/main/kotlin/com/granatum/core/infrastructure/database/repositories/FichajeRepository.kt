package com.granatum.core.infrastructure.database.repositories

import com.granatum.core.domain.type.EstadoFichaje
import com.granatum.core.infrastructure.database.entities.FichajeEntity
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.Optional
import java.util.UUID

/**
 * Deliberately extends [Repository] and not `JpaRepository`, so no deletion
 * operation exists on the normal path (constitution principle III).
 *
 * The four-year retention purge still has to delete, but it will not do so
 * through here. It gets its own repository whose only deletion method is a
 * `@Modifying` query bounded by the cut-off date, so the method itself is
 * incapable of removing a record whose retention period is still running.
 * Exposing `delete(entity)` here would make that guarantee a matter of
 * discipline instead of a matter of type.
 */
interface FichajeRepository : Repository<FichajeEntity, UUID> {
    fun save(fichaje: FichajeEntity): FichajeEntity

    fun findById(id: UUID): Optional<FichajeEntity>

    /** Used to reject a second clock-in; the partial unique index is the real guard. */
    fun findByEmpleadoIdAndEstado(empleadoId: UUID, estado: EstadoFichaje): FichajeEntity?

    /**
     * The daily job's query: shifts still open whose entry predates the cut-off.
     * Served by the partial index on `estado = 'EN_CURSO'`.
     */
    fun findAllByEstadoAndEntradaBefore(
        estado: EstadoFichaje,
        corte: Instant
    ): List<FichajeEntity>

    /**
     * A person's shifts within a range, breaks included.
     *
     * The `@EntityGraph` is what keeps this from being 1 + N queries. Without
     * it, a month of one person's days is ~22 extra selects, and a manager
     * asking for the whole workforce multiplies that by the headcount - which
     * is SC-008 missed by construction rather than by bad luck.
     *
     * **Not paginated, deliberately.** A collection fetch join cannot be
     * paginated in the database: Hibernate would pull every row into memory and
     * paginate there, which is worse than not paginating at all. The date range
     * bounds the result instead. If pagination is ever needed, the fix is to
     * fetch the ids first and load the breaks in a second query - not to add
     * `Pageable` to this method.
     */
    @EntityGraph(attributePaths = ["pausas"])
    fun findAllByEmpleadoIdAndEntradaBetweenOrderByEntradaDesc(
        empleadoId: UUID,
        desde: Instant,
        hasta: Instant
    ): List<FichajeEntity>

    /** Same, for every member of staff: the manager's and representative's view. */
    @EntityGraph(attributePaths = ["pausas"])
    fun findAllByEntradaBetweenOrderByEntradaDesc(
        desde: Instant,
        hasta: Instant
    ): List<FichajeEntity>

    /**
     * One batch of an export: the ids of a person's next [pagina] shifts with
     * entry in `[inicio, fin)` after the key `(entradaPrevia, idPrevio)`, in
     * `(entrada, id)` order (D-002).
     *
     * By key and not by `OFFSET`, whose cost grows with every page; the walk is
     * served by `idx_fichajes_empleado_entrada`.
     *
     * Ids only, and the shifts with their breaks in [findAllConPausasByIdIn].
     * Fetching the `pausas` collection here with an `@EntityGraph` would make
     * Hibernate drop the `LIMIT` from the SQL and paginate **in memory** after
     * reading every row of the range - the opposite of a batch.
     */
    @Query(
        """
        SELECT f.id FROM FichajeEntity f
         WHERE f.empleado.id = :empleadoId
           AND f.entrada >= :inicio AND f.entrada < :fin
           AND (f.entrada > :entradaPrevia OR (f.entrada = :entradaPrevia AND f.id > :idPrevio))
         ORDER BY f.entrada, f.id
        """
    )
    fun findIdsSiguienteLote(
        @Param("empleadoId") empleadoId: UUID,
        @Param("inicio") inicio: Instant,
        @Param("fin") fin: Instant,
        @Param("entradaPrevia") entradaPrevia: Instant,
        @Param("idPrevio") idPrevio: UUID,
        pagina: Pageable
    ): List<UUID>

    /** The shifts of one batch with their breaks, in a single query. Unordered: the caller keeps the order of the ids. */
    @Query("SELECT DISTINCT f FROM FichajeEntity f LEFT JOIN FETCH f.pausas WHERE f.id IN :ids")
    fun findAllConPausasByIdIn(@Param("ids") ids: Collection<UUID>): List<FichajeEntity>

    /**
     * Everyone with at least one shift entering in `[inicio, fin)`, active or
     * not: someone who has left still has a register to hand over (FR-015).
     */
    @Query("SELECT DISTINCT f.empleado.id FROM FichajeEntity f WHERE f.entrada >= :inicio AND f.entrada < :fin")
    fun findEmpleadoIdsConFichajesEntre(
        @Param("inicio") inicio: Instant,
        @Param("fin") fin: Instant
    ): List<UUID>
}
