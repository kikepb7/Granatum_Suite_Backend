package com.granatum.core.service

import com.granatum.core.domain.contract.DirectorioEmpleados
import com.granatum.core.domain.exception.AusenciaNoEncontradaException
import com.granatum.core.domain.exception.AusenciaNoModificableException
import com.granatum.core.domain.exception.AusenciaSolapadaException
import com.granatum.core.domain.exception.DatosAusenciaInvalidosException
import com.granatum.core.domain.exception.PersonaAusenciaInactivaException
import com.granatum.core.domain.exception.PersonaAusenciaNoEncontradaException
import com.granatum.core.domain.exception.RangoAusenciaInvalidoException
import com.granatum.core.domain.exception.ResolucionPropiaAusenciaException
import com.granatum.core.domain.exception.SaldoVacacionesInsuficienteException
import com.granatum.core.domain.model.Ausencia
import com.granatum.core.domain.model.CausaPermiso
import com.granatum.core.domain.model.EstadoAusencia
import com.granatum.core.domain.model.SaldoVacaciones
import com.granatum.core.domain.model.TipoAusencia
import com.granatum.core.domain.service.CalculadoraSaldo
import com.granatum.core.domain.type.EntityId
import com.granatum.core.domain.type.EstadoEmpleado
import com.granatum.core.infrastructure.database.entities.AusenciaEntity
import com.granatum.core.infrastructure.database.entities.DerechoVacacionesEntity
import com.granatum.core.infrastructure.database.entities.DerechoVacacionesId
import com.granatum.core.infrastructure.database.repositories.AusenciaRepository
import com.granatum.core.infrastructure.database.repositories.DerechoVacacionesRepository
import jakarta.persistence.EntityManager
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** What someone asks for or registers. `toString` hides the comment (FR-021). */
data class NuevaAusencia(
    val tipo: TipoAusencia,
    val causa: CausaPermiso?,
    val desde: LocalDate,
    val hasta: LocalDate?,
    val comentario: String?
) {
    override fun toString(): String = "NuevaAusencia(tipo=$tipo, desde=$desde, hasta=$hasta)"
}

/**
 * Absences (feature 007): request, register, resolve, cancel, close a sick
 * leave, query, balance and entitlement.
 *
 * ## The per-person lock
 *
 * Creating an absence takes a transaction-scoped advisory lock on the person
 * before checking overlaps and the balance (research.md D-002). Without it two
 * simultaneous requests would both read "no overlap, 12 days left" and both be
 * stored. A database exclusion constraint would need the btree_gist extension,
 * which a migration cannot count on in Supabase - and the balance needs the
 * same serialisation anyway.
 *
 * Resolving and cancelling do not take it: they never add days or ranges.
 * Two resolutions of the same absence at once are stopped by the version.
 */
@Service
class AusenciaService(
    private val ausencias: AusenciaRepository,
    private val derechos: DerechoVacacionesRepository,
    private val directorio: DirectorioEmpleados,
    private val entityManager: EntityManager,
    @param:Value("\${absences.vacaciones.dias-anuales}") private val diasAnuales: Int,
    private val clock: Clock = Clock.systemUTC()
) {

    private val hoy: LocalDate get() = LocalDate.now(clock.withZone(MADRID))

    /**
     * A person asks for holidays or paid leave for themselves (FR-001,
     * FR-002, FR-011). Pending until an ENCARGADO or ADMIN resolves it.
     */
    @Transactional
    fun solicitar(empleadoId: EntityId, nueva: NuevaAusencia): Ausencia {
        // FR-006, FR-007: sick leave is registered by someone else, never
        // self-reported - it is health data and the responsibility of who
        // manages the staff.
        if (nueva.tipo == TipoAusencia.BAJA_MEDICA) throw ResolucionPropiaAusenciaException()
        validar(nueva)
        val hasta = nueva.hasta ?: throw RangoAusenciaInvalidoException("Indica la fecha de fin")
        if (nueva.tipo == TipoAusencia.VACACIONES && nueva.desde.isBefore(hoy)) {
            throw RangoAusenciaInvalidoException("No se piden vacaciones que ya han empezado")
        }
        comprobarPersona(empleadoId)
        return crear(empleadoId, nueva, hasta, EstadoAusencia.PENDIENTE, autor = empleadoId)
    }

    /**
     * An ENCARGADO or ADMIN registers an absence on someone else's behalf,
     * approved from the start: sick leave, or regularising past holidays
     * (FR-006, FR-008, FR-011).
     */
    @Transactional
    fun registrar(autorId: EntityId, empleadoId: EntityId, nueva: NuevaAusencia): Ausencia {
        if (autorId == empleadoId) throw ResolucionPropiaAusenciaException()
        validar(nueva)
        if (nueva.hasta == null && nueva.tipo != TipoAusencia.BAJA_MEDICA) {
            throw RangoAusenciaInvalidoException("Solo una baja médica puede quedar abierta")
        }
        comprobarPersona(empleadoId)
        return crear(empleadoId, nueva, nueva.hasta, EstadoAusencia.APROBADA, autor = autorId)
    }

    @Transactional
    fun aprobar(id: EntityId, autorId: EntityId): Ausencia {
        val ausencia = pendienteDeOtro(id, autorId)
        ausencia.aprobar(autorId, clock.instant())
        return ausencias.save(ausencia).toModel()
    }

    @Transactional
    fun rechazar(id: EntityId, autorId: EntityId, motivo: String): Ausencia {
        val ausencia = pendienteDeOtro(id, autorId)
        ausencia.rechazar(autorId, motivo.trim(), clock.instant())
        return ausencias.save(ausencia).toModel()
    }

    /**
     * The person cancels a pending request, or approved one that has not
     * started (FR-012). Someone else's absence is "not found", not "forbidden":
     * an EMPLEADO must not learn that an id exists.
     */
    @Transactional
    fun cancelar(id: EntityId, quien: EntityId): Ausencia {
        val ausencia = ausencias.findById(id).orElse(null)?.takeIf { it.empleadoId == quien }
            ?: throw AusenciaNoEncontradaException(id)
        when (ausencia.estado) {
            EstadoAusencia.PENDIENTE -> Unit
            EstadoAusencia.APROBADA ->
                if (!ausencia.desde.isAfter(hoy)) throw AusenciaNoModificableException("Esa ausencia ya ha empezado")
            else -> throw AusenciaNoModificableException("Esa ausencia ya está ${ausencia.estado.name.lowercase()}")
        }
        ausencia.cancelar(clock.instant())
        return ausencias.save(ausencia).toModel()
    }

    /** Closes an open sick leave with the date of discharge, once (FR-008). */
    @Transactional
    fun darAlta(id: EntityId, autorId: EntityId, hasta: LocalDate): Ausencia {
        val ausencia = ausencias.findById(id).orElseThrow { AusenciaNoEncontradaException(id) }
        if (ausencia.empleadoId == autorId) throw ResolucionPropiaAusenciaException()
        if (ausencia.tipo != TipoAusencia.BAJA_MEDICA || ausencia.hasta != null || ausencia.estado != EstadoAusencia.APROBADA) {
            throw AusenciaNoModificableException("Solo se da de alta una baja médica abierta")
        }
        validarRango(ausencia.desde, hasta)
        ausencia.cerrarBaja(hasta)
        return ausencias.save(ausencia).toModel()
    }

    /** One absence; someone else's is "not found" for whoever may only see their own (FR-018). */
    @Transactional(readOnly = true)
    fun obtener(id: EntityId, quien: EntityId, veTodas: Boolean): Ausencia =
        ausencias.findById(id).orElse(null)?.takeIf { veTodas || it.empleadoId == quien }?.toModel()
            ?: throw AusenciaNoEncontradaException(id)

    @Transactional(readOnly = true)
    fun buscar(empleadoId: EntityId?, estado: EstadoAusencia?, desde: LocalDate, hasta: LocalDate): List<Ausencia> {
        if (hasta.isBefore(desde)) throw RangoAusenciaInvalidoException("La fecha de fin es anterior a la de inicio")
        return ausencias.buscar(empleadoId, estado, desde, hasta).map { it.toModel() }
    }

    @Transactional(readOnly = true)
    fun saldo(empleadoId: EntityId, anio: Int): SaldoVacaciones =
        CalculadoraSaldo.saldo(anio, derecho(empleadoId, anio), vigentesEn(empleadoId, anio))

    /** The ADMIN sets a person's entitlement for a year (FR-014). */
    @Transactional
    fun fijarDerecho(empleadoId: EntityId, anio: Int, dias: Int, adminId: EntityId) {
        val id = DerechoVacacionesId(empleadoId, anio.toShort())
        val existente = derechos.findById(id).orElse(null)
        if (existente != null) {
            existente.dias = dias.toShort()
            existente.actualizadoPor = adminId
            existente.actualizadoEn = clock.instant()
            derechos.save(existente)
        } else {
            derechos.save(DerechoVacacionesEntity(id, dias.toShort(), adminId, clock.instant()))
        }
    }

    // --- internals ---------------------------------------------------------

    private fun crear(
        empleadoId: EntityId,
        nueva: NuevaAusencia,
        hasta: LocalDate?,
        estado: EstadoAusencia,
        autor: EntityId
    ): Ausencia {
        bloquearPersona(empleadoId)

        // FR-009: an open sick leave runs to "forever" for the overlap check.
        val finParaSolape = hasta ?: SIN_FIN
        if (ausencias.deEmpleadoEnRango(empleadoId, nueva.desde, finParaSolape, VIGENTES).isNotEmpty()) {
            throw AusenciaSolapadaException()
        }

        if (nueva.tipo == TipoAusencia.VACACIONES) {
            val fin = hasta!!
            for (anio in CalculadoraSaldo.anios(nueva.desde, fin)) {
                val saldo = saldo(empleadoId, anio)
                if (CalculadoraSaldo.diasEnAnio(nueva.desde, fin, anio) > saldo.disponibles) {
                    throw SaldoVacacionesInsuficienteException(anio, saldo.disponibles)
                }
            }
        }

        val ahora = clock.instant()
        val aprobada = estado == EstadoAusencia.APROBADA
        return ausencias.save(
            AusenciaEntity(
                empleadoId = empleadoId,
                tipo = nueva.tipo,
                causa = nueva.causa,
                desde = nueva.desde,
                hasta = hasta,
                comentario = nueva.comentario?.trim()?.takeIf { it.isNotEmpty() },
                solicitadaPor = autor,
                solicitadaEn = ahora,
                estadoInicial = estado,
                resueltaPorInicial = if (aprobada) autor else null,
                resueltaEnInicial = if (aprobada) ahora else null
            )
        ).toModel()
    }

    private fun validar(nueva: NuevaAusencia) {
        when (nueva.tipo) {
            TipoAusencia.PERMISO ->
                if (nueva.causa == null) throw DatosAusenciaInvalidosException("Un permiso necesita su causa")
            TipoAusencia.VACACIONES ->
                if (nueva.causa != null) throw DatosAusenciaInvalidosException("Solo un permiso lleva causa")
            TipoAusencia.BAJA_MEDICA -> {
                if (nueva.causa != null) throw DatosAusenciaInvalidosException("Solo un permiso lleva causa")
                // FR-007: no free text on sick leave - it would be health data.
                if (!nueva.comentario.isNullOrBlank()) {
                    throw DatosAusenciaInvalidosException("Una baja médica no admite comentarios")
                }
            }
        }
        nueva.hasta?.let { validarRango(nueva.desde, it) }
    }

    private fun validarRango(desde: LocalDate, hasta: LocalDate) {
        if (hasta.isBefore(desde)) throw RangoAusenciaInvalidoException("La fecha de fin es anterior a la de inicio")
        if (ChronoUnit.DAYS.between(desde, hasta) > MAX_DIAS_ENTRE_EXTREMOS) {
            throw RangoAusenciaInvalidoException("Una ausencia no puede pasar de 366 días; una baja larga se registra abierta")
        }
    }

    private fun comprobarPersona(empleadoId: EntityId) {
        when (directorio.estado(empleadoId)) {
            null -> throw PersonaAusenciaNoEncontradaException(empleadoId)
            EstadoEmpleado.INACTIVO -> throw PersonaAusenciaInactivaException()
            EstadoEmpleado.ACTIVO -> Unit
        }
    }

    private fun pendienteDeOtro(id: EntityId, autorId: EntityId): AusenciaEntity {
        val ausencia = ausencias.findById(id).orElseThrow { AusenciaNoEncontradaException(id) }
        if (ausencia.empleadoId == autorId) throw ResolucionPropiaAusenciaException()
        if (ausencia.estado != EstadoAusencia.PENDIENTE) {
            throw AusenciaNoModificableException("Esa ausencia ya está ${ausencia.estado.name.lowercase()}")
        }
        return ausencia
    }

    private fun derecho(empleadoId: EntityId, anio: Int): Int =
        derechos.findById(DerechoVacacionesId(empleadoId, anio.toShort())).map { it.dias.toInt() }.orElse(diasAnuales)

    private fun vigentesEn(empleadoId: EntityId, anio: Int): List<Ausencia> =
        ausencias.deEmpleadoEnRango(empleadoId, LocalDate.of(anio, 1, 1), LocalDate.of(anio, 12, 31), VIGENTES)
            .map { it.toModel() }

    /** research.md D-002. 7007 namespaces this lock among the product's advisory locks. */
    private fun bloquearPersona(empleadoId: EntityId) {
        entityManager.createNativeQuery("SELECT CAST(pg_advisory_xact_lock(7007, hashtext(CAST(:id AS text))) AS text)")
            .setParameter("id", empleadoId.toString())
            .singleResult
    }

    companion object {
        private val MADRID: ZoneId = ZoneId.of("Europe/Madrid")
        private val VIGENTES = listOf(EstadoAusencia.PENDIENTE, EstadoAusencia.APROBADA)

        /** "No end" for the overlap query. Not LocalDate.MAX: it overflows Postgres's DATE. */
        private val SIN_FIN: LocalDate = LocalDate.of(9999, 12, 31)

        /** 366 days inclusive (research.md D-006). */
        private const val MAX_DIAS_ENTRE_EXTREMOS = 365L
    }
}
