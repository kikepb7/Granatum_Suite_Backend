package com.granatum.core.service

import com.granatum.core.domain.contract.AltaFichaPersonal
import com.granatum.core.domain.contract.FichasPersonal
import com.granatum.core.domain.exception.CodigoIncorrectoException
import com.granatum.core.domain.exception.CuentaYaExisteException
import com.granatum.core.domain.exception.DatosFichaRequeridosException
import com.granatum.core.domain.exception.EmailYaRegistradoException
import com.granatum.core.domain.exception.SolicitudNoEncontradaException
import com.granatum.core.domain.exception.SolicitudNoPendienteException
import com.granatum.core.domain.model.RegistroAprobado
import com.granatum.core.domain.model.SolicitudRegistroPendiente
import com.granatum.core.domain.service.CodigoVerificacion
import com.granatum.core.domain.type.EntityId
import com.granatum.core.domain.type.EstadoSolicitudRegistro
import com.granatum.core.domain.type.Role
import com.granatum.core.domain.type.TipoEventoSeguridad
import com.granatum.core.infrastructure.database.entities.CuentaAccesoEntity
import com.granatum.core.infrastructure.database.entities.SolicitudRegistroEntity
import com.granatum.core.infrastructure.database.repositories.CuentaAccesoRepository
import com.granatum.core.infrastructure.database.repositories.SolicitudRegistroRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.LocalDate

/** What a new staff record needs when no record has the request's document. */
data class DatosFicha(val puesto: String, val tipoContrato: String, val fechaAlta: LocalDate)

/**
 * The ADMIN's side of sign-up (feature 005): list, approve, reject. FR-015 to
 * FR-024. The route rule (ADMIN only) is in `SecurityConfig`.
 */
@Service
class AprobacionRegistroService(
    private val solicitudes: SolicitudRegistroRepository,
    private val cuentas: CuentaAccesoRepository,
    private val fichas: FichasPersonal,
    private val eventos: RegistradorEventosSeguridad,
    private val transacciones: TransactionTemplate,
    private val clock: Clock = Clock.systemUTC()
) {

    /**
     * Pending requests, oldest first (FR-016). The staff-record lookup is one
     * query per request; the list is capped at `auth.registro.max-pendientes`
     * (50), so this is bounded and does not need a batch contract.
     */
    @Transactional(readOnly = true)
    fun listarPendientes(): List<SolicitudRegistroPendiente> =
        solicitudes.findAllByEstadoOrderByCreadaEnAsc(EstadoSolicitudRegistro.PENDIENTE).map {
            SolicitudRegistroPendiente(
                id = it.id,
                email = it.email!!,
                nombre = it.nombre!!,
                documentoIdentidad = it.documentoIdentidad!!,
                creadaEn = it.creadaEn,
                empleadoExistenteId = fichas.buscarPorDocumento(it.documentoIdentidad!!)
            )
        }

    /**
     * Approves a request (FR-017 to FR-024, research.md D-006).
     *
     * ## Why outcomes come back from the transaction instead of being thrown in it
     *
     * A wrong code must be **counted** even though the call fails - otherwise the
     * five-attempt limit would never be reached, since every failure would roll
     * its own increment back. The same for the request cancelled because its
     * address was taken. So those outcomes are values: the transaction commits
     * them, and the exception is thrown afterwards. Real failures that change
     * nothing (missing staff data, a record that already has an account) are
     * thrown inside and roll back as usual.
     */
    fun aprobar(
        solicitudId: EntityId,
        codigo: String,
        rol: Role,
        ficha: DatosFicha?,
        adminId: EntityId
    ): RegistroAprobado {
        val resultado = transacciones.execute {
            val solicitud = bloquearPendiente(solicitudId)
            val ahora = clock.instant()

            if (!CodigoVerificacion.coincide(solicitud.id, codigo, solicitud.codigoHash!!)) {
                val anulada = solicitud.registrarCodigoIncorrecto(ahora)
                solicitudes.save(solicitud)
                return@execute if (anulada) Aprobacion.AnuladaPorCodigos else Aprobacion.CodigoIncorrecto
            }

            val email = solicitud.email!!
            val documento = solicitud.documentoIdentidad!!

            if (cuentas.existsByEmail(email)) {
                solicitud.resolver(EstadoSolicitudRegistro.ANULADA, ahora, por = adminId)
                solicitudes.save(solicitud)
                return@execute Aprobacion.EmailTomado
            }

            val existente = fichas.buscarPorDocumento(documento)
            if (existente != null && cuentas.existsByEmpleadoId(existente)) throw CuentaYaExisteException()

            val empleadoId = existente ?: fichas.crear(
                ficha?.let { AltaFichaPersonal(solicitud.nombre!!, documento, it.puesto, it.tipoContrato, it.fechaAlta) }
                    ?: throw DatosFichaRequeridosException()
            )

            val cuenta = cuentas.save(
                CuentaAccesoEntity(
                    empleadoId = empleadoId,
                    email = email,
                    rol = rol,
                    // FR-020: the hash computed at sign-up. The person signs in
                    // with the password they chose; nobody else ever saw it.
                    passwordHash = solicitud.passwordHash!!,
                    requiereCambioPassword = false
                )
            )
            solicitud.resolver(EstadoSolicitudRegistro.APROBADA, ahora, por = adminId, cuenta = cuenta.id)
            solicitudes.save(solicitud)
            // FR-022: the impostor's request, or an older one of the same person.
            solicitudes.anularPendientesDe(email, documento, excepto = solicitud.id, ahora = ahora)

            Aprobacion.Hecha(RegistroAprobado(solicitud.id, cuenta.id, empleadoId, rol, fichaCreada = existente == null))
        }!!

        return when (resultado) {
            is Aprobacion.Hecha -> resultado.aprobado.also {
                eventos.registrar(TipoEventoSeguridad.REGISTRO_APROBADO, it.cuentaId)
            }
            Aprobacion.CodigoIncorrecto -> throw CodigoIncorrectoException()
            Aprobacion.AnuladaPorCodigos -> {
                eventos.registrar(TipoEventoSeguridad.REGISTRO_ANULADO)
                throw SolicitudNoPendienteException()
            }
            Aprobacion.EmailTomado -> {
                eventos.registrar(TipoEventoSeguridad.REGISTRO_ANULADO)
                throw EmailYaRegistradoException()
            }
        }
    }

    /** Rejects a request and empties its personal data (FR-026). */
    fun rechazar(solicitudId: EntityId, adminId: EntityId) {
        transacciones.executeWithoutResult {
            val solicitud = bloquearPendiente(solicitudId)
            solicitud.resolver(EstadoSolicitudRegistro.RECHAZADA, clock.instant(), por = adminId)
            solicitudes.save(solicitud)
        }
        eventos.registrar(TipoEventoSeguridad.REGISTRO_RECHAZADO)
    }

    /** `SELECT ... FOR UPDATE`: whoever comes second sees it resolved (FR-023, FR-024). */
    private fun bloquearPendiente(id: EntityId): SolicitudRegistroEntity {
        val solicitud = solicitudes.bloquear(id) ?: throw SolicitudNoEncontradaException(id)
        if (!solicitud.pendiente) throw SolicitudNoPendienteException()
        return solicitud
    }

    private sealed interface Aprobacion {
        data class Hecha(val aprobado: RegistroAprobado) : Aprobacion
        data object CodigoIncorrecto : Aprobacion
        data object AnuladaPorCodigos : Aprobacion
        data object EmailTomado : Aprobacion
    }
}
