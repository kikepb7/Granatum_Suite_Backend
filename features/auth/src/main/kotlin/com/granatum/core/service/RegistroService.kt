package com.granatum.core.service

import com.granatum.core.domain.contract.AltaFichaPersonal
import com.granatum.core.domain.contract.FichasPersonal
import com.granatum.core.domain.exception.CodigoArranqueInvalidoException
import com.granatum.core.domain.exception.CuentaYaExisteException
import com.granatum.core.domain.exception.DocumentoInvalidoException
import com.granatum.core.domain.exception.EmailYaRegistradoException
import com.granatum.core.domain.exception.PasswordDebilException
import com.granatum.core.domain.exception.RegistroNoDisponibleException
import com.granatum.core.domain.service.CodigoVerificacion
import com.granatum.core.domain.service.PoliticaPassword
import com.granatum.core.domain.type.EntityId
import com.granatum.core.domain.type.EstadoSolicitudRegistro
import com.granatum.core.domain.type.Role
import com.granatum.core.domain.type.TipoEventoSeguridad
import com.granatum.core.infrastructure.crypto.VerificadorAcotado
import com.granatum.core.infrastructure.database.entities.CuentaAccesoEntity
import com.granatum.core.infrastructure.database.entities.SolicitudRegistroEntity
import com.granatum.core.infrastructure.database.repositories.CuentaAccesoRepository
import com.granatum.core.infrastructure.database.repositories.SolicitudRegistroRepository
import com.granatum.core.validation.NifValidator
import jakarta.persistence.EntityManager
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** What a person sends to sign up. `toString` hides everything personal. */
data class DatosRegistro(
    val email: String,
    val password: String,
    val nombre: String,
    val documento: String,
    val codigoArranque: String? = null
) {
    override fun toString(): String = "DatosRegistro(arranque=${codigoArranque != null})"
}

sealed interface ResultadoRegistro {
    /**
     * The request is waiting for an ADMIN. Also what a duplicate address gets,
     * with a code that matches nothing (FR-006): from the outside the two are
     * the same.
     */
    data class Pendiente(val codigoVerificacion: String) : ResultadoRegistro {
        override fun toString(): String = "Pendiente(codigoVerificacion=***)"
    }

    /** The first ADMIN of the installation, in already (FR-010). */
    data class AdminCreado(val cuentaId: EntityId, val empleadoId: EntityId) : ResultadoRegistro
}

/**
 * Sign-up (feature 005): FR-001 to FR-014.
 *
 * Not transactional at the method level, like [CuentaAccesoService]: Argon2
 * takes ~110 ms and a transaction held across it would pin a connection for the
 * whole hash. The hash runs first, outside; the writes are short.
 */
@Service
class RegistroService(
    private val cuentas: CuentaAccesoRepository,
    private val solicitudes: SolicitudRegistroRepository,
    private val verificador: VerificadorAcotado,
    private val fichas: FichasPersonal,
    private val eventos: RegistradorEventosSeguridad,
    private val transacciones: TransactionTemplate,
    private val entityManager: EntityManager,
    @param:Value("\${auth.registro.codigo-arranque:}") private val codigoArranque: String,
    @param:Value("\${auth.registro.max-pendientes}") private val maxPendientes: Long,
    private val clock: Clock = Clock.systemUTC()
) {

    fun registrar(datos: DatosRegistro): ResultadoRegistro {
        val email = NormalizadorEmail.normalizar(datos.email)
        val documento = NifValidator.normalizar(datos.documento)
        if (!NifValidator.esDniONieValido(documento)) throw DocumentoInvalidoException()

        val incumplidos = PoliticaPassword.validar(datos.password)
        if (incumplidos.isNotEmpty()) throw PasswordDebilException(incumplidos)

        val nombre = datos.nombre.trim()

        return if (datos.codigoArranque != null) {
            arrancar(email, nombre, documento, datos.password, datos.codigoArranque)
        } else {
            solicitar(email, nombre, documento, datos.password)
        }
    }

    /**
     * The ordinary path (FR-003 to FR-008).
     *
     * ## Why the hash runs before looking at the address
     *
     * So that a new address and a taken one cost the same: Argon2 is ~110 ms,
     * inserting a row a few. Checking first and skipping the hash for a taken
     * address would make the response time say what the body hides (FR-006,
     * SC-004, research.md D-003). The same reasoning as the dummy hash on
     * sign-in in feature 002.
     */
    private fun solicitar(email: String, nombre: String, documento: String, password: String): ResultadoRegistro {
        // Checked before the hash: it does not depend on the address, so it
        // reveals nothing, and refusing early saves the expensive part.
        if (solicitudes.countByEstado(EstadoSolicitudRegistro.PENDIENTE) >= maxPendientes) {
            throw RegistroNoDisponibleException()
        }

        val codigo = CodigoVerificacion.generar()
        val hash = verificador.codificar(password)

        if (cuentas.existsByEmail(email)) {
            // FR-007: nothing approvable is stored. The event is the only trace,
            // and it is only visible to whoever reads the database.
            eventos.registrar(TipoEventoSeguridad.REGISTRO_DUPLICADO)
            return ResultadoRegistro.Pendiente(codigo)
        }

        val id = UUID.randomUUID()
        solicitudes.save(
            SolicitudRegistroEntity(
                id = id,
                email = email,
                nombre = nombre,
                documentoIdentidad = documento,
                passwordHash = hash,
                codigoHash = CodigoVerificacion.huella(id, codigo),
                creadaEn = clock.instant()
            )
        )
        eventos.registrar(TipoEventoSeguridad.REGISTRO_SOLICITADO)
        return ResultadoRegistro.Pendiente(codigo)
    }

    /**
     * The first ADMIN (FR-009 to FR-014, research.md D-002).
     *
     * The code is checked before the hash, so a wrong one costs nothing. The
     * "is there an ADMIN yet?" question and the creation run under a
     * transaction-scoped advisory lock: two bootstraps with different addresses
     * would otherwise both read "no" and both create one, and no unique
     * constraint stops two different rows.
     */
    private fun arrancar(
        email: String,
        nombre: String,
        documento: String,
        password: String,
        codigo: String
    ): ResultadoRegistro {
        if (!codigoArranqueCorrecto(codigo)) rechazarArranque()

        val hash = verificador.codificar(password)

        val creado = transacciones.execute {
            entityManager.createNativeQuery("SELECT CAST(pg_advisory_xact_lock(:clave) AS text)")
                .setParameter("clave", CLAVE_BLOQUEO_ARRANQUE)
                .singleResult

            if (cuentas.existsByRol(Role.ADMIN)) return@execute null
            if (cuentas.existsByEmail(email)) throw EmailYaRegistradoException()

            val empleadoId = fichas.buscarPorDocumento(documento) ?: fichas.crear(
                AltaFichaPersonal(
                    nombre = nombre,
                    documento = documento,
                    // research.md D-008: documented defaults the ADMIN corrects
                    // later with PUT /api/empleados/{id}.
                    puesto = PUESTO_PRIMER_ADMIN,
                    tipoContrato = "JORNADA_COMPLETA",
                    fechaAlta = LocalDate.now(clock.withZone(MADRID))
                )
            )
            if (cuentas.existsByEmpleadoId(empleadoId)) throw CuentaYaExisteException()

            val cuenta = cuentas.save(
                CuentaAccesoEntity(
                    empleadoId = empleadoId,
                    email = email,
                    rol = Role.ADMIN,
                    passwordHash = hash,
                    // FR-010: the person chose it; nobody else ever saw it.
                    requiereCambioPassword = false
                )
            )
            // FR-014: a request the boss left before having the code.
            solicitudes.anularPendientesDe(email, documento, excepto = SIN_EXCEPCION, ahora = clock.instant())
            ResultadoRegistro.AdminCreado(cuenta.id, empleadoId)
        } ?: rechazarArranque()

        eventos.registrar(TipoEventoSeguridad.ADMIN_INICIAL_CREADO, creado.cuentaId)
        return creado
    }

    private fun codigoArranqueCorrecto(recibido: String): Boolean =
        codigoArranque.isNotBlank() &&
            MessageDigest.isEqual(recibido.toByteArray(Charsets.UTF_8), codigoArranque.toByteArray(Charsets.UTF_8))

    /** One answer for the three causes (D-002): nothing tells them apart. */
    private fun rechazarArranque(): Nothing {
        eventos.registrar(TipoEventoSeguridad.ARRANQUE_RECHAZADO)
        throw CodigoArranqueInvalidoException()
    }

    companion object {
        const val PUESTO_PRIMER_ADMIN = "Dirección"

        /** Arbitrary, fixed: identifies "creating the first ADMIN" among advisory locks. */
        private const val CLAVE_BLOQUEO_ARRANQUE = 5_005_001L

        /** No request is spared when the first ADMIN cancels the pending ones. */
        private val SIN_EXCEPCION = UUID(0, 0)

        private val MADRID: ZoneId = ZoneId.of("Europe/Madrid")
    }
}
