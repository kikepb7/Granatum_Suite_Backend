package com.granatum.core.service

import com.granatum.core.domain.exception.EmpleadoInactivoException
import com.granatum.core.domain.exception.ForbiddenException
import com.granatum.core.domain.exception.EmpleadoNotFoundException
import com.granatum.core.domain.exception.FichajeNoEnCursoException
import com.granatum.core.domain.exception.FichajeNotFoundException
import com.granatum.core.domain.exception.FichajeYaEnCursoException
import com.granatum.core.domain.exception.PausaAbiertaAlCerrarException
import com.granatum.core.domain.exception.PausaNoAbiertaException
import com.granatum.core.domain.exception.PausaYaAbiertaException
import com.granatum.core.domain.exception.ValoresIncoherentesException
import com.granatum.core.domain.model.FichajeModel
import com.granatum.core.domain.service.CalculadoraJornada
import com.granatum.core.domain.service.CalculadoraJornada.IntervaloPausa
import com.granatum.core.domain.type.EstadoFichaje
import com.granatum.core.domain.type.TipoPausa
import com.granatum.core.infrastructure.database.entities.FichajeEntity
import com.granatum.core.infrastructure.database.entities.PausaEntity
import com.granatum.core.infrastructure.database.entities.UbicacionEmbeddable
import com.granatum.core.infrastructure.database.mappers.toModel
import com.granatum.core.infrastructure.database.repositories.EmpleadoRepository
import com.granatum.core.infrastructure.database.repositories.FichajeRepository
import com.granatum.core.infrastructure.database.repositories.PausaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Location as it arrives from the edge, without the HTTP DTO coming along. */
data class UbicacionInput(
    val latitud: BigDecimal,
    val longitud: BigDecimal,
    val precisionMetros: Int?
)

/**
 * Orchestrates the working-day lifecycle.
 *
 * Every method returns a [FichajeModel], never an entity, and maps **inside**
 * the transaction: `spring.jpa.open-in-view` is false, so touching the lazy
 * `pausas` collection after returning would throw.
 *
 * The employee is always the authenticated subject, passed in by the
 * controller from the JWT. It is never read from a request body: accepting an
 * id from the payload is how "only your own fichajes" gets bypassed.
 */
@Service
class FichajeService(
    private val fichajeRepository: FichajeRepository,
    private val pausaRepository: PausaRepository,
    private val empleadoRepository: EmpleadoRepository
) {

    @Transactional
    fun registrarEntrada(
        empleadoId: UUID,
        occurredAt: Instant,
        ubicacion: UbicacionInput?
    ): FichajeModel {
        val empleado = empleadoRepository.findById(empleadoId)
            .orElseThrow { EmpleadoNotFoundException(empleadoId) }

        if (!empleado.activo) throw EmpleadoInactivoException()

        // Only EN_CURSO blocks a new shift. An INCOMPLETO fichaje dragged in
        // from a previous day must not (FR-012a): the person forgot to clock
        // out, and locking them out of today's shift would turn one mistake
        // into a second, larger gap in the register.
        //
        // The partial unique index is what actually guarantees uniqueness under
        // concurrency - two simultaneous clock-ins both pass this check,
        // because each reads before either writes. This is here to produce a
        // readable error in the ordinary case.
        fichajeRepository.findByEmpleadoIdAndEstado(empleadoId, EstadoFichaje.EN_CURSO)
            ?.let { throw FichajeYaEnCursoException() }

        val fichaje = fichajeRepository.save(
            FichajeEntity(
                empleado = empleado,
                entrada = occurredAt,
                estado = EstadoFichaje.EN_CURSO,
                ubicacionEntrada = ubicacion?.toEmbeddable()
            )
        )

        return fichaje.toModel()
    }

    @Transactional
    fun iniciarPausa(
        fichajeId: UUID,
        empleadoId: UUID,
        occurredAt: Instant,
        tipo: TipoPausa
    ): FichajeModel {
        val fichaje = cargarFichajeEnCurso(fichajeId, empleadoId)

        if (fichaje.pausaAbierta() != null) throw PausaYaAbiertaException()

        fichaje.anadirPausa(PausaEntity(tipo = tipo, inicio = occurredAt))

        return fichajeRepository.save(fichaje).toModel()
    }

    @Transactional
    fun finalizarPausa(
        fichajeId: UUID,
        empleadoId: UUID,
        occurredAt: Instant
    ): FichajeModel {
        val fichaje = cargarFichajeEnCurso(fichajeId, empleadoId)
        val pausa = fichaje.pausaAbierta() ?: throw PausaNoAbiertaException()

        if (!occurredAt.isAfter(pausa.inicio)) {
            throw ValoresIncoherentesException(
                "el fin de la pausa no es posterior a su inicio"
            )
        }

        pausa.fin = occurredAt
        pausaRepository.save(pausa)

        return fichajeRepository.save(fichaje).toModel()
    }

    @Transactional
    fun registrarSalida(
        fichajeId: UUID,
        empleadoId: UUID,
        occurredAt: Instant,
        ubicacion: UbicacionInput?
    ): FichajeModel {
        val fichaje = cargarFichajeEnCurso(fichajeId, empleadoId)

        // A shift cannot be closed with a pausa still running: the worked time
        // would be computed against a break that never ended.
        if (fichaje.pausaAbierta() != null) throw PausaAbiertaAlCerrarException()

        // Throws if the values could not describe a real shift, rather than
        // storing something incoherent in a record that has to stand up to
        // inspection.
        val minutos = CalculadoraJornada.minutosTrabajados(
            entrada = fichaje.entrada,
            salida = occurredAt,
            pausas = fichaje.pausas.map { IntervaloPausa(it.inicio, it.fin!!) }
        )

        fichaje.salida = occurredAt
        fichaje.ubicacionSalida = ubicacion?.toEmbeddable()
        fichaje.minutosTrabajados = minutos
        fichaje.estado = EstadoFichaje.CERRADO

        return fichajeRepository.save(fichaje).toModel()
    }

    @Transactional(readOnly = true)
    fun findById(fichajeId: UUID): FichajeModel =
        fichajeRepository.findById(fichajeId)
            .orElseThrow { FichajeNotFoundException(fichajeId) }
            .toModel()

    private fun cargarFichajeEnCurso(fichajeId: UUID, empleadoId: UUID): FichajeEntity {
        val fichaje = fichajeRepository.findById(fichajeId)
            .orElseThrow { FichajeNotFoundException(fichajeId) }

        // Ownership is checked against the authenticated subject, so a caller
        // cannot operate on someone else's shift by passing its id.
        if (fichaje.empleado.id != empleadoId) {
            throw ForbiddenException(
                "El fichaje no pertenece al usuario autenticado"
            )
        }

        if (fichaje.estado != EstadoFichaje.EN_CURSO) throw FichajeNoEnCursoException()

        return fichaje
    }

    private fun UbicacionInput.toEmbeddable() = UbicacionEmbeddable(
        latitud = latitud,
        longitud = longitud,
        precisionMetros = precisionMetros
    )
}
