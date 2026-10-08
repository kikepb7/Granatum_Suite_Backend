package com.granatum.core.infrastructure.directorio

import com.granatum.core.api.validation.DocumentoIdentidadValidator
import com.granatum.core.domain.contract.AltaFichaPersonal
import com.granatum.core.domain.contract.FichasPersonal
import com.granatum.core.domain.type.EntityId
import com.granatum.core.domain.type.TipoContrato
import com.granatum.core.infrastructure.database.repositories.EmpleadoRepository
import com.granatum.core.service.EmpleadoService
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * This module's side of the `FichasPersonal` contract (feature 005).
 *
 * Creation goes through [EmpleadoService.crear], the same path as the manual
 * staff registration of feature 001, so the document is normalised and its
 * uniqueness checked in exactly one place.
 *
 * Default propagation on purpose: the contract requires joining the caller's
 * transaction, so a failure creating the account in `auth` rolls this record
 * back too.
 */
@Component
class FichasPersonalJpa(
    private val empleadoRepository: EmpleadoRepository,
    private val empleadoService: EmpleadoService
) : FichasPersonal {

    @Transactional(readOnly = true)
    override fun buscarPorDocumento(documento: String): EntityId? =
        empleadoRepository.findByDocumentoIdentidad(DocumentoIdentidadValidator.normalizar(documento))?.id

    @Transactional
    override fun crear(alta: AltaFichaPersonal): EntityId =
        empleadoService.crear(
            nombre = alta.nombre,
            documentoIdentidad = alta.documento,
            puesto = alta.puesto,
            // valueOf, not a lenient parse: an unknown contract type is a bug in
            // the caller, which validates it at its edge, and must not become a
            // silently defaulted staff record.
            tipoContrato = TipoContrato.valueOf(alta.tipoContrato),
            fechaAlta = alta.fechaAlta
        ).id
}
