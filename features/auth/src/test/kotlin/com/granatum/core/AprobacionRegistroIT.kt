package com.granatum.core

import com.granatum.core.domain.exception.CodigoIncorrectoException
import com.granatum.core.domain.exception.CuentaYaExisteException
import com.granatum.core.domain.exception.DatosFichaRequeridosException
import com.granatum.core.domain.exception.EmailYaRegistradoException
import com.granatum.core.domain.exception.SolicitudNoPendienteException
import com.granatum.core.domain.type.EstadoSolicitudRegistro
import com.granatum.core.domain.type.Role
import com.granatum.core.domain.type.TipoEventoSeguridad
import com.granatum.core.service.AutenticacionService
import com.granatum.core.service.DatosFicha
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Approving a sign-up (feature 005, US2): FR-017 to FR-024. */
@Testcontainers
@SpringBootTest(classes = [AuthTestApplication::class])
class AprobacionRegistroIT : BaseRegistroIT() {

    @Autowired lateinit var autenticacion: AutenticacionService

    private val ficha = DatosFicha("Florista", "PARCIAL", LocalDate.of(2026, 10, 1))

    @Test
    fun `approving links the account to the staff record that has the document`() {
        val documento = dniValido()
        val existente = directorio.registrarFicha(documento)
        val (solicitud, codigo) = pendiente(documento = documento)

        val aprobado = aprobacion.aprobar(solicitud.id, codigo, Role.ENCARGADO, ficha = null, adminId = adminId)

        assertEquals(existente, aprobado.empleadoId)
        assertFalse(aprobado.fichaCreada)
        assertEquals(Role.ENCARGADO, cuentas.findById(aprobado.cuentaId).orElseThrow().rol)
    }

    @Test
    fun `with no staff record, approving creates it and the account together`() {
        val (solicitud, codigo) = pendiente()

        val aprobado = aprobacion.aprobar(solicitud.id, codigo, Role.EMPLEADO, ficha, adminId)

        assertTrue(aprobado.fichaCreada)
        val alta = directorio.altas.last()
        assertEquals("Florista", alta.puesto)
        assertEquals("PARCIAL", alta.tipoContrato)
        assertEquals("Ana Florista", alta.nombre)
    }

    @Test
    fun `with no staff record and no data to create one, it is refused and stays pending`() {
        val (solicitud, codigo) = pendiente()

        assertFailsWith<DatosFichaRequeridosException> {
            aprobacion.aprobar(solicitud.id, codigo, Role.EMPLEADO, ficha = null, adminId = adminId)
        }
        assertEquals(EstadoSolicitudRegistro.PENDIENTE, solicitudes.findById(solicitud.id).orElseThrow().estado)
    }

    /** FR-020, FR-003: the person signs in with the password they chose, nobody else saw it. */
    @Test
    fun `after approval the person signs in with their own password and no forced change`() {
        val email = correoUnico()
        val (solicitud, codigo) = pendiente(email)

        val aprobado = aprobacion.aprobar(solicitud.id, codigo, Role.EMPLEADO, ficha, adminId)

        autenticacion.iniciarSesion(email, password)
        assertFalse(cuentas.findById(aprobado.cuentaId).orElseThrow().requiereCambioPassword)
    }

    /** FR-026: approved data now lives in the account and the staff record. */
    @Test
    fun `an approved request keeps no personal data and records who approved it`() {
        val (solicitud, codigo) = pendiente()

        val aprobado = aprobacion.aprobar(solicitud.id, codigo, Role.EMPLEADO, ficha, adminId)

        val resuelta = solicitudes.findById(solicitud.id).orElseThrow()
        assertEquals(EstadoSolicitudRegistro.APROBADA, resuelta.estado)
        assertEquals(adminId, resuelta.resueltaPor)
        assertEquals(aprobado.cuentaId, resuelta.cuentaId)
        listOf(resuelta.email, resuelta.nombre, resuelta.documentoIdentidad, resuelta.passwordHash, resuelta.codigoHash)
            .forEach { assertNull(it) }
        assertTrue(
            eventos.findAllByCuentaIdOrderByOcurridoEnDesc(aprobado.cuentaId).any { it.tipo == TipoEventoSeguridad.REGISTRO_APROBADO }
        )
    }

    /** D-006: the wrong attempt must count even though the call fails. */
    @Test
    fun `a wrong code is refused and the attempt is kept`() {
        val (solicitud, codigo) = pendiente()

        assertFailsWith<CodigoIncorrectoException> {
            aprobacion.aprobar(solicitud.id, codigoDistintoDe(codigo), Role.EMPLEADO, ficha, adminId)
        }

        val tras = solicitudes.findById(solicitud.id).orElseThrow()
        assertEquals(1, tras.intentosCodigo.toInt())
        assertEquals(EstadoSolicitudRegistro.PENDIENTE, tras.estado)
    }

    /** FR-018, SC-006. */
    @Test
    fun `the fifth wrong code cancels the request, and the right one no longer helps`() {
        val (solicitud, codigo) = pendiente()
        repeat(4) {
            assertFailsWith<CodigoIncorrectoException> {
                aprobacion.aprobar(solicitud.id, codigoDistintoDe(codigo), Role.EMPLEADO, ficha, adminId)
            }
        }

        assertFailsWith<SolicitudNoPendienteException> {
            aprobacion.aprobar(solicitud.id, codigoDistintoDe(codigo), Role.EMPLEADO, ficha, adminId)
        }
        assertEquals(EstadoSolicitudRegistro.ANULADA, solicitudes.findById(solicitud.id).orElseThrow().estado)
        assertFailsWith<SolicitudNoPendienteException> {
            aprobacion.aprobar(solicitud.id, codigo, Role.EMPLEADO, ficha, adminId)
        }
    }

    @Test
    fun `a staff record that already has an account cannot get a second one`() {
        val documento = dniValido()
        val existente = cuentaActiva(empleadoId = directorio.registrarFicha(documento))
        val (solicitud, codigo) = pendiente(documento = documento)

        assertFailsWith<CuentaYaExisteException> {
            aprobacion.aprobar(solicitud.id, codigo, Role.EMPLEADO, ficha, adminId)
        }
        assertEquals(existente.id, cuentas.findByEmpleadoId(existente.empleadoId)!!.id)
    }

    /** An ADMIN gave this address access by the temporary-password route meanwhile. */
    @Test
    fun `an address taken in the meantime is refused and the request cancelled`() {
        val email = correoUnico()
        val (solicitud, codigo) = pendiente(email)
        cuentaActiva(email = email)

        assertFailsWith<EmailYaRegistradoException> {
            aprobacion.aprobar(solicitud.id, codigo, Role.EMPLEADO, ficha, adminId)
        }
        assertEquals(EstadoSolicitudRegistro.ANULADA, solicitudes.findById(solicitud.id).orElseThrow().estado)
    }

    /** FR-024: the row lock serialises them. */
    @Test
    fun `two simultaneous approvals of the same request produce one account`() {
        val email = correoUnico()
        val (solicitud, codigo) = pendiente(email)
        val salida = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)

        val resultados = (1..2).map {
            pool.submit<Result<Any>> {
                salida.await()
                runCatching { aprobacion.aprobar(solicitud.id, codigo, Role.EMPLEADO, ficha, adminId) }
            }
        }
        salida.countDown()
        val hechos = resultados.map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(1, hechos.count { it.isSuccess }, hechos.toString())
        assertTrue(hechos.single { it.isFailure }.exceptionOrNull() is SolicitudNoPendienteException)
        assertTrue(cuentas.existsByEmail(email))
    }
}
