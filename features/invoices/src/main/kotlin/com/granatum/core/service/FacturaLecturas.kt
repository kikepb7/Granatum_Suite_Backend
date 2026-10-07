package com.granatum.core.service

import com.granatum.core.domain.exception.FacturaNoEncontradaException
import com.granatum.core.domain.model.EstadoFactura
import com.granatum.core.domain.model.Factura
import com.granatum.core.infrastructure.database.aDominio
import com.granatum.core.infrastructure.database.entities.FacturaReconocimientoEntity
import com.granatum.core.infrastructure.database.repositories.FacturaReconocimientoRepository
import com.granatum.core.infrastructure.database.repositories.FacturaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** Reading one invoice, its state and its last recognition. */
@Service
class FacturaLecturas(
    private val facturas: FacturaRepository,
    private val reconocimientos: FacturaReconocimientoRepository
) {
    @Transactional(readOnly = true)
    fun factura(id: UUID): Factura =
        facturas.findById(id).map { it.aDominio() }.orElseThrow { FacturaNoEncontradaException(id) }

    @Transactional(readOnly = true)
    fun estado(id: UUID): EstadoFactura =
        facturas.findById(id).map { it.estado }.orElseThrow { FacturaNoEncontradaException(id) }

    @Transactional(readOnly = true)
    fun ultimoReconocimiento(id: UUID): FacturaReconocimientoEntity? =
        reconocimientos.findFirstByFacturaIdOrderByCreadoEnDesc(id)
}
