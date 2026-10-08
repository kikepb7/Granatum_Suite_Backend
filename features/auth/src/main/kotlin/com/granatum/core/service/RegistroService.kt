package com.granatum.core.service

import com.granatum.core.domain.contract.AltaFichaPersonal
import com.granatum.core.domain.contract.FichasPersonal
import com.granatum.core.domain.exception.CodigoArranqueInvalidoException
import com.granatum.core.domain.exception.CuentaYaExisteException
import com.granatum.core.domain.exception.DocumentoInvalidoException
import com.granatum.core.domain.exception.EmailYaRegistradoException
import com.granatum.core.domain.exception.PasswordDebilException
import com.granatum.core.domain.service.PoliticaPassword
import com.granatum.core.domain.type.EntityId
import com.granatum.core.domain.type.Role
import com.granatum.core.domain.type.TipoEventoSeguridad
import com.granatum.core.infrastructure.crypto.VerificadorAcotado
import com.granatum.core.infrastructure.database.entities.CuentaAccesoEntity
import com.granatum.core.infrastructure.database.repositories.CuentaAccesoRepository
import com.granatum.core.validation.NifValidator
import jakarta.persistence.EntityManager
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/** What the owner sends to sign up. `toString` hides everything personal. */
data class DatosRegistro(
    val email: String,
    val password: String,
    val nombre: String,
    val documento: String,
    val codigoArranque: String
) {
    override fun toString(): String = "DatosRegistro(arranque=***)"
}

/** The owner's ADMIN account and staff record, created on the spot. */
data class AdminCreado(val cuentaId: EntityId, val empleadoId: EntityId)

/**
 * Sign-up, which only the business owner can do (feature 009; feature 005 US1).
 *
 * The owner signs up once, with the deploy-time bootstrap code, while the
 * installation has no ADMIN; everyone else is onboarded by an ADMIN through
 * [CuentaAccesoService.darDeAlta] and receives a temporary password. There are
 * no pending requests any more: feature 009 removed 005's self sign-up with
 * approval.
 *
 * Not transactional at the method level: Argon2 takes ~110 ms and a
 * transaction held across it would pin a connection for the whole hash.
 */
@Service
class RegistroService(
    private val cuentas: CuentaAccesoRepository,
    private val verificador: VerificadorAcotado,
    private val fichas: FichasPersonal,
    private val eventos: RegistradorEventosSeguridad,
    private val transacciones: TransactionTemplate,
    private val entityManager: EntityManager,
    @param:Value("\${auth.registro.codigo-arranque:}") private val codigoArranque: String,
    private val clock: Clock = Clock.systemUTC()
) {

    /**
     * The first ADMIN (005 FR-009 to FR-013, research.md D-002).
     *
     * The code is checked before anything else, so a wrong one costs nothing -
     * not even the hash. "Is there an ADMIN yet?" and the creation run under a
     * transaction-scoped advisory lock: two bootstraps with different addresses
     * would otherwise both read "no" and both create one, and no unique
     * constraint stops two different rows.
     */
    fun registrar(datos: DatosRegistro): AdminCreado {
        if (!codigoArranqueCorrecto(datos.codigoArranque)) rechazarArranque()

        val email = NormalizadorEmail.normalizar(datos.email)
        val documento = NifValidator.normalizar(datos.documento)
        if (!NifValidator.esDniONieValido(documento)) throw DocumentoInvalidoException()
        val incumplidos = PoliticaPassword.validar(datos.password)
        if (incumplidos.isNotEmpty()) throw PasswordDebilException(incumplidos)

        val hash = verificador.codificar(datos.password)

        val creado = transacciones.execute {
            entityManager.createNativeQuery("SELECT CAST(pg_advisory_xact_lock(:clave) AS text)")
                .setParameter("clave", CLAVE_BLOQUEO_ARRANQUE)
                .singleResult

            if (cuentas.existsByRol(Role.ADMIN)) return@execute null
            if (cuentas.existsByEmail(email)) throw EmailYaRegistradoException()

            val empleadoId = fichas.buscarPorDocumento(documento) ?: fichas.crear(
                AltaFichaPersonal(
                    nombre = datos.nombre.trim(),
                    documento = documento,
                    // 005 research.md D-008: documented defaults the ADMIN
                    // corrects later with PUT /api/empleados/{id}.
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
                    // The owner chose it; nobody else ever saw it.
                    requiereCambioPassword = false
                )
            )
            AdminCreado(cuenta.id, empleadoId)
        } ?: rechazarArranque()

        eventos.registrar(TipoEventoSeguridad.ADMIN_INICIAL_CREADO, creado.cuentaId)
        return creado
    }

    private fun codigoArranqueCorrecto(recibido: String): Boolean =
        codigoArranque.isNotBlank() &&
            MessageDigest.isEqual(recibido.toByteArray(Charsets.UTF_8), codigoArranque.toByteArray(Charsets.UTF_8))

    /** One answer for the three causes (005 D-002): nothing tells them apart. */
    private fun rechazarArranque(): Nothing {
        eventos.registrar(TipoEventoSeguridad.ARRANQUE_RECHAZADO)
        throw CodigoArranqueInvalidoException()
    }

    companion object {
        const val PUESTO_PRIMER_ADMIN = "Dirección"

        /** Arbitrary, fixed: identifies "creating the first ADMIN" among advisory locks. */
        private const val CLAVE_BLOQUEO_ARRANQUE = 5_005_001L

        private val MADRID: ZoneId = ZoneId.of("Europe/Madrid")
    }
}
