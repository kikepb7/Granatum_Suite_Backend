package com.granatum.core.service

import com.granatum.core.api.util.RangoFechas
import com.granatum.core.domain.type.Role
import java.time.LocalDate
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
import com.granatum.core.domain.model.ResumenMensual
import com.granatum.core.domain.service.CalculadoraResumenMensual
import com.granatum.core.domain.type.EstadoSolicitud
import com.granatum.core.infrastructure.database.repositories.SolicitudCorreccionFichajeRepository
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
    private val empleadoRepository: EmpleadoRepository,
    private val solicitudRepository: SolicitudCorreccionFichajeRepository
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

    /**
     * A person's shifts within a civil date range (FR-021 to FR-023c).
     *
     * [empleadoIdSolicitado] comes from the request path, and for an EMPLEADO it
     * is compared against [solicitanteId] - the JWT subject - and refused if
     * they differ. The path id is there for readability of the API; it is never
     * the source of authority. Checking it the other way round, trusting the
     * path and ignoring the token, is the single mistake that would turn this
     * into a data breach.
     *
     * Mapping happens inside this transaction: `pausas` is lazy and
     * `open-in-view` is false.
     */
    @Transactional(readOnly = true)
    fun findPorEmpleadoYRango(
        empleadoIdSolicitado: UUID,
        solicitanteId: UUID,
        rol: Role,
        desde: LocalDate,
        hasta: LocalDate
    ): List<FichajeModel> {
        if (hasta.isBefore(desde)) {
            throw ValoresIncoherentesException("'hasta' es anterior a 'desde'")
        }

        if (rol == Role.EMPLEADO && empleadoIdSolicitado != solicitanteId) {
            throw ForbiddenException("Solo puede consultar sus propios fichajes")
        }

        if (!empleadoRepository.findById(empleadoIdSolicitado).isPresent) {
            throw EmpleadoNotFoundException(empleadoIdSolicitado)
        }

        val (inicio, fin) = RangoFechas.rango(desde, hasta)

        // Filtered on the entry instant, so a shift crossing midnight appears
        // on the day it started and not on both.
        return fichajeRepository
            .findAllByEmpleadoIdAndEntradaBetweenOrderByEntradaDesc(
                empleadoIdSolicitado, inicio, fin
            )
            .map { it.toModel() }
    }

    /**
     * Every member of staff's shifts in a range. For ENCARGADO, ADMIN and
     * REPRESENTANTE only; an EMPLEADO has no route to it at all.
     */
    @Transactional(readOnly = true)
    fun findTodosPorRango(rol: Role, desde: LocalDate, hasta: LocalDate): List<FichajeModel> {
        if (rol == Role.EMPLEADO) {
            throw ForbiddenException("Solo puede consultar sus propios fichajes")
        }
        if (hasta.isBefore(desde)) {
            throw ValoresIncoherentesException("'hasta' es anterior a 'desde'")
        }

        val (inicio, fin) = RangoFechas.rango(desde, hasta)
        return fichajeRepository
            .findAllByEntradaBetweenOrderByEntradaDesc(inicio, fin)
            .map { it.toModel() }
    }

    /**
     * A month of someone's register, aggregated (FR-032 to FR-035).
     *
     * Same visibility rules as the plain listing: an EMPLEADO only their own,
     * compared against the JWT subject.
     *
     * Returns the **calculation**. Downloading it - the file format and the
     * cadence of handing it over with a payslip - belongs to the export
     * feature; putting CSV generation here would duplicate what that feature
     * owns.
     */
    @Transactional(readOnly = true)
    fun resumenMensual(
        empleadoIdSolicitado: UUID,
        solicitanteId: UUID,
        rol: Role,
        anio: Int,
        mes: Int
    ): ResumenMensual {
        if (mes !in 1..12) throw ValoresIncoherentesException("el mes debe estar entre 1 y 12")

        if (rol == Role.EMPLEADO && empleadoIdSolicitado != solicitanteId) {
            throw ForbiddenException("Solo puede consultar sus propios fichajes")
        }

        val empleado = empleadoRepository.findById(empleadoIdSolicitado)
            .orElseThrow { EmpleadoNotFoundException(empleadoIdSolicitado) }

        val primero = LocalDate.of(anio, mes, 1)
        val ultimo = primero.withDayOfMonth(primero.lengthOfMonth())
        val (inicio, fin) = RangoFechas.rango(primero, ultimo)

        val fichajes = fichajeRepository
            .findAllByEmpleadoIdAndEntradaBetweenOrderByEntradaDesc(
                empleadoIdSolicitado, inicio, fin
            )
            .map { it.toModel() }

        // Which days carry an approved correction. Resolved with one query over
        // the month rather than one per day, so the summary does not reintroduce
        // the N+1 the listing avoids.
        val corregidos = solicitudRepository
            .findAllByEstado(EstadoSolicitud.APROBADA)
            .map { it.fichaje.id }
            .toSet()

        return CalculadoraResumenMensual.calcular(
            empleadoId = empleadoIdSolicitado,
            anio = anio,
            mes = mes,
            tipoContrato = empleado.tipoContrato,
            fichajes = fichajes,
            fichajesCorregidos = corregidos
        )
    }

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
