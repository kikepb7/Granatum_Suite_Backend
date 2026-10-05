package com.granatum.core.service

import com.granatum.core.domain.exception.FichajeNoFinalizadoException
import com.granatum.core.domain.exception.FichajeNotFoundException
import com.granatum.core.domain.exception.ForbiddenException
import com.granatum.core.domain.exception.SolicitudNotFoundException
import com.granatum.core.domain.exception.SolicitudYaResueltaException
import com.granatum.core.domain.model.SolicitudCorreccionModel
import com.granatum.core.domain.model.ValoresFichaje
import com.granatum.core.domain.model.ValoresPausa
import com.granatum.core.domain.service.ValidadorValoresFichaje
import com.granatum.core.domain.type.EstadoFichaje
import com.granatum.core.domain.type.EstadoSolicitud
import com.granatum.core.domain.type.Role
import com.granatum.core.infrastructure.database.ValoresFichajeJson
import com.granatum.core.infrastructure.database.entities.FichajeEntity
import com.granatum.core.infrastructure.database.entities.PausaEntity
import com.granatum.core.infrastructure.database.entities.SolicitudCorreccionFichajeEntity
import com.granatum.core.infrastructure.database.mappers.toModel
import com.granatum.core.infrastructure.database.repositories.FichajeRepository
import com.granatum.core.infrastructure.database.repositories.SolicitudCorreccionFichajeRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * The only path by which a finalised fichaje changes value (constitution
 * principle III). There is no other write path to a CERRADO or INCOMPLETO
 * fichaje anywhere in the module, and none must be added.
 */
@Service
class CorreccionService(
    private val solicitudRepository: SolicitudCorreccionFichajeRepository,
    private val fichajeRepository: FichajeRepository,
    private val json: ValoresFichajeJson,
    private val clock: Clock = Clock.systemUTC()
) {

    @Transactional
    fun solicitar(
        fichajeId: UUID,
        solicitanteId: UUID,
        rol: Role,
        motivo: String,
        propuestos: ValoresFichaje
    ): SolicitudCorreccionModel {
        val fichaje = fichajeRepository.findById(fichajeId)
            .orElseThrow { FichajeNotFoundException(fichajeId) }

        // An EMPLEADO may only correct their own. ENCARGADO and ADMIN may act on
        // anyone's; REPRESENTANTE reaches no write path at all.
        if (rol == Role.EMPLEADO && fichaje.empleado.id != solicitanteId) {
            throw ForbiddenException("El fichaje no pertenece al usuario autenticado")
        }
        if (rol == Role.REPRESENTANTE) {
            throw ForbiddenException("El rol REPRESENTANTE no puede solicitar correcciones")
        }

        // Only finalised shifts. An open one is fixed by carrying on with the
        // day, not by filing a correction against it.
        if (fichaje.estado == EstadoFichaje.EN_CURSO) throw FichajeNoFinalizadoException()

        // Rejected here as well as at approval time, so an incoherent proposal
        // never reaches a reviewer's queue in the first place.
        ValidadorValoresFichaje.validar(propuestos)

        val solicitud = solicitudRepository.save(
            SolicitudCorreccionFichajeEntity(
                fichaje = fichaje,
                solicitanteId = solicitanteId,
                motivo = motivo,
                valoresPropuestos = json.escribir(propuestos),
                estado = EstadoSolicitud.PENDIENTE
            )
        )

        return solicitud.toModel(json)
    }

    @Transactional
    fun aprobar(solicitudId: UUID, resolutorId: UUID, rol: Role): SolicitudCorreccionModel {
        val solicitud = cargarResoluble(solicitudId, resolutorId, rol)
        val fichaje = solicitud.fichaje

        val propuestos = json.leer(solicitud.valoresPropuestos)

        // Re-validated at approval time and not only when requested: another
        // correction may have been approved in between, so the proposal has to
        // be coherent against the fichaje as it stands now.
        val minutos = ValidadorValoresFichaje.validar(propuestos)

        // Snapshot the state immediately BEFORE applying. This is what makes
        // the original recoverable (FR-017), and it has to happen first -
        // afterwards the previous values no longer exist anywhere.
        solicitud.valoresOriginales = json.escribir(fichaje.aValores())

        aplicar(fichaje, propuestos, minutos)

        solicitud.estado = EstadoSolicitud.APROBADA
        solicitud.resueltaPorId = resolutorId
        solicitud.resueltaEn = clock.instant()

        fichajeRepository.save(fichaje)
        return solicitudRepository.save(solicitud).toModel(json)
    }

    @Transactional
    fun rechazar(
        solicitudId: UUID,
        resolutorId: UUID,
        rol: Role,
        motivoResolucion: String
    ): SolicitudCorreccionModel {
        val solicitud = cargarResoluble(solicitudId, resolutorId, rol)

        solicitud.estado = EstadoSolicitud.RECHAZADA
        solicitud.resueltaPorId = resolutorId
        solicitud.resueltaEn = clock.instant()
        solicitud.motivoResolucion = motivoResolucion
        // valoresOriginales stays null: nothing was ever applied, so there is no
        // "before" to record.

        return solicitudRepository.save(solicitud).toModel(json)
    }

    @Transactional(readOnly = true)
    fun findByFichaje(fichajeId: UUID): List<SolicitudCorreccionModel> =
        solicitudRepository.findAllByFichajeIdOrderByCreatedAtDesc(fichajeId)
            .map { it.toModel(json) }

    @Transactional(readOnly = true)
    fun findByEstado(estado: EstadoSolicitud): List<SolicitudCorreccionModel> =
        solicitudRepository.findAllByEstado(estado).map { it.toModel(json) }

    private fun cargarResoluble(
        solicitudId: UUID,
        resolutorId: UUID,
        rol: Role
    ): SolicitudCorreccionFichajeEntity {
        val solicitud = solicitudRepository.findById(solicitudId)
            .orElseThrow { SolicitudNotFoundException(solicitudId) }

        // Only ENCARGADO and ADMIN resolve (FR-015).
        if (rol != Role.ENCARGADO && rol != Role.ADMIN) {
            throw ForbiddenException("Solo ENCARGADO o ADMIN pueden resolver una correccion")
        }

        // Nobody resolves their own request, whatever their role.
        //
        // Stricter than FR-015 as originally written, deliberately. That
        // requirement said "prevent the requester from resolving it when their
        // role is EMPLEADO", which is vacuous next to the clause above: an
        // EMPLEADO never reaches this point. The case that can actually happen
        // is an ENCARGADO filing a correction for their own shift and approving
        // it themselves - they are an employee too, with their own fichajes -
        // and that is precisely the self-approval the law's approval step
        // exists to prevent. FR-015a was added to the spec to say so.
        if (solicitud.solicitanteId == resolutorId) {
            throw ForbiddenException("Quien solicita una correccion no puede resolverla")
        }

        // Both APROBADA and RECHAZADA are terminal (FR-019). Checked here, and
        // the state transition below is guarded again by the write itself, so
        // two simultaneous approvals cannot both apply.
        if (solicitud.estado != EstadoSolicitud.PENDIENTE) throw SolicitudYaResueltaException()

        return solicitud
    }

    /**
     * Replaces the shift's times and its whole break list.
     *
     * `orphanRemoval = true` on the association is what makes removing a break
     * actually delete its row; clearing the list without it would leave orphans
     * pointing at the fichaje.
     */
    private fun aplicar(fichaje: FichajeEntity, valores: ValoresFichaje, minutos: Int) {
        fichaje.entrada = valores.entrada
        fichaje.salida = valores.salida
        fichaje.minutosTrabajados = minutos

        fichaje.pausas.clear()
        valores.pausas.forEach { p ->
            fichaje.anadirPausa(PausaEntity(tipo = p.tipo, inicio = p.inicio, fin = p.fin))
        }

        // An INCOMPLETO shift completed by a correction becomes CERRADO, but
        // `fueIncompleto` is deliberately NOT reset (FR-012c, SC-010): a
        // reconstructed day must stay distinguishable from one closed at the
        // time, so an inspection report can say which is which.
        fichaje.estado = EstadoFichaje.CERRADO
    }

    private fun FichajeEntity.aValores() = ValoresFichaje(
        entrada = entrada,
        // A finalised fichaje always has an exit, except an INCOMPLETO one -
        // which is exactly the case a correction supplies it for. Falling back
        // to the entry keeps the snapshot well-formed without inventing a time.
        salida = salida ?: entrada,
        pausas = pausas.mapNotNull { p ->
            p.fin?.let { ValoresPausa(p.tipo, p.inicio, it) }
        }
    )
}
