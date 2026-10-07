package com.granatum.core.infrastructure.database.repositories

import com.granatum.core.infrastructure.database.entities.EmpresaEntity
import com.granatum.core.infrastructure.database.entities.FacturaCambioEntity
import com.granatum.core.infrastructure.database.entities.FacturaDocumentoEntity
import com.granatum.core.infrastructure.database.entities.FacturaEntity
import com.granatum.core.infrastructure.database.entities.FacturaReconocimientoEntity
import com.granatum.core.infrastructure.database.entities.TrimestreEntity
import com.granatum.core.infrastructure.database.entities.TrimestreEventoEntity
import com.granatum.core.infrastructure.database.entities.TrimestreId
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.Param
import java.util.Optional
import java.util.UUID

// Invoicing repositories (feature 004). Every one extends Repository<T, ID> and
// declares only what its table admits: **no delete anywhere**. Invoices are
// kept at least six years and discarding one does not remove it (FR-015); the
// histories are append-only. SinBorradoFacturacionIT checks this by reflection
// over a fixed list, so adding a delete fails the build.

interface EmpresaRepository : Repository<EmpresaEntity, Short> {
    fun save(empresa: EmpresaEntity): EmpresaEntity
    fun findById(id: Short): Optional<EmpresaEntity>
}

interface TrimestreRepository : Repository<TrimestreEntity, TrimestreId> {
    fun save(trimestre: TrimestreEntity): TrimestreEntity
    fun findById(id: TrimestreId): Optional<TrimestreEntity>
    fun findAllByIdAnioOrderByIdTrimestre(anio: Short): List<TrimestreEntity>

    /** Makes sure the row exists before it is locked: a quarter is open until someone closes it. */
    @Modifying
    @Query(
        value = "INSERT INTO trimestres (anio, trimestre, cerrado) VALUES (:anio, :trimestre, FALSE) ON CONFLICT DO NOTHING",
        nativeQuery = true
    )
    fun crearSiNoExiste(@Param("anio") anio: Short, @Param("trimestre") trimestre: Short): Int

    /**
     * `SELECT … FOR UPDATE` on the quarter (research.md D-016). Taken by every
     * change to an invoice of the quarter and by closing it, so the two cannot
     * interleave: a close waits for a confirmation in flight, and the other way
     * round. Must be called inside the caller's transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM TrimestreEntity t WHERE t.id = :id")
    fun bloquear(@Param("id") id: TrimestreId): TrimestreEntity
}

interface TrimestreEventoRepository : Repository<TrimestreEventoEntity, UUID> {
    fun save(evento: TrimestreEventoEntity): TrimestreEventoEntity
    fun findAllByAnioOrderByOcurridoEnAsc(anio: Short): List<TrimestreEventoEntity>
    fun findAllByAnioAndTrimestreOrderByOcurridoEnAsc(anio: Short, trimestre: Short): List<TrimestreEventoEntity>
}

interface FacturaRepository : Repository<FacturaEntity, UUID> {
    fun save(factura: FacturaEntity): FacturaEntity
    fun saveAndFlush(factura: FacturaEntity): FacturaEntity
    fun findById(id: UUID): Optional<FacturaEntity>
}

interface FacturaDocumentoRepository : Repository<FacturaDocumentoEntity, UUID> {
    fun save(documento: FacturaDocumentoEntity): FacturaDocumentoEntity
    fun findById(facturaId: UUID): Optional<FacturaDocumentoEntity>
}

interface FacturaReconocimientoRepository : Repository<FacturaReconocimientoEntity, UUID> {
    fun save(reconocimiento: FacturaReconocimientoEntity): FacturaReconocimientoEntity
    fun findAllByFacturaIdOrderByCreadoEnAsc(facturaId: UUID): List<FacturaReconocimientoEntity>
}

interface FacturaCambioRepository : Repository<FacturaCambioEntity, UUID> {
    fun save(cambio: FacturaCambioEntity): FacturaCambioEntity
    fun findAllByFacturaIdOrderByOcurridoEnAsc(facturaId: UUID): List<FacturaCambioEntity>
}
