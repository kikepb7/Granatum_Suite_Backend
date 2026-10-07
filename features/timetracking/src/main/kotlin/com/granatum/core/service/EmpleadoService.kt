package com.granatum.core.service

import com.granatum.core.api.validation.DocumentoIdentidadValidator
import com.granatum.core.domain.exception.DocumentoDuplicadoException
import com.granatum.core.domain.exception.EmpleadoNotFoundException
import com.granatum.core.domain.model.EmpleadoModel
import com.granatum.core.domain.type.TipoContrato
import com.granatum.core.infrastructure.database.entities.EmpleadoEntity
import com.granatum.core.infrastructure.database.mappers.toModel
import com.granatum.core.infrastructure.database.repositories.EmpleadoRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID

/**
 * Staff management. ADMIN only, enforced at the route in `SecurityConfig`
 * (FR-027): an ENCARGADO runs inventory and approves corrections but does not
 * manage people.
 *
 * **There is no delete.** Removing a person would destroy the working-time
 * history that has to be kept for four years, so deactivation is the only exit
 * (FR-030). The repository does not expose one either, so this is not a matter
 * of discipline.
 */
@Service
class EmpleadoService(
    private val empleadoRepository: EmpleadoRepository
) {

    @Transactional
    fun crear(
        nombre: String,
        documentoIdentidad: String,
        puesto: String,
        tipoContrato: TipoContrato,
        fechaAlta: LocalDate
    ): EmpleadoModel {
        // Normalised before the uniqueness check, or FR-029 could be bypassed
        // with a hyphen: `12345678z` and `12345678-Z` are the same person.
        val documento = DocumentoIdentidadValidator.normalizar(documentoIdentidad)

        if (empleadoRepository.existsByDocumentoIdentidad(documento)) {
            // The message names the field, never the value: it reaches clients
            // and logs, and the document is personal data (principle VI).
            throw DocumentoDuplicadoException()
        }

        return empleadoRepository.save(
            EmpleadoEntity(
                nombre = nombre,
                documentoIdentidad = documento,
                puesto = puesto,
                tipoContrato = tipoContrato,
                fechaAlta = fechaAlta,
                activo = true
            )
        ).toModel()
    }

    /** `documentoIdentidad` and `activo` are not touched here; see the request DTO. */
    @Transactional
    fun actualizar(
        id: UUID,
        nombre: String,
        puesto: String,
        tipoContrato: TipoContrato,
        fechaAlta: LocalDate
    ): EmpleadoModel {
        val empleado = empleadoRepository.findById(id)
            .orElseThrow { EmpleadoNotFoundException(id) }

        empleado.nombre = nombre
        empleado.puesto = puesto
        empleado.tipoContrato = tipoContrato
        empleado.fechaAlta = fechaAlta

        return empleadoRepository.save(empleado).toModel()
    }

    /**
     * Engagement or exit (FR-030).
     *
     * Deactivating stops the person clocking in and leaves their history whole.
     * An open shift is deliberately not blocked here: the daily job will mark it
     * INCOMPLETO, and holding up an administrative action because someone forgot
     * to clock out would make one person's mistake somebody else's problem.
     */
    @Transactional
    fun cambiarActivo(id: UUID, activo: Boolean): EmpleadoModel {
        val empleado = empleadoRepository.findById(id)
            .orElseThrow { EmpleadoNotFoundException(id) }

        empleado.activo = activo

        return empleadoRepository.save(empleado).toModel()
    }

    @Transactional(readOnly = true)
    fun findById(id: UUID): EmpleadoModel =
        empleadoRepository.findById(id)
            .orElseThrow { EmpleadoNotFoundException(id) }
            .toModel()

    @Transactional(readOnly = true)
    fun findAll(activo: Boolean?): List<EmpleadoModel> =
        when (activo) {
            null -> empleadoRepository.findAll()
            else -> empleadoRepository.findAllByActivo(activo)
        }.map { it.toModel() }
}
