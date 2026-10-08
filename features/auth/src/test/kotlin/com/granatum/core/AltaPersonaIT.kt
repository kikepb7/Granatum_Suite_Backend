package com.granatum.core

import com.granatum.core.domain.exception.CredencialesInvalidasException
import com.granatum.core.domain.exception.CuentaYaExisteException
import com.granatum.core.domain.exception.DocumentoInvalidoException
import com.granatum.core.domain.exception.EmailYaRegistradoException
import com.granatum.core.domain.service.PoliticaPassword
import com.granatum.core.domain.type.Role
import com.granatum.core.domain.type.TipoEventoSeguridad
import com.granatum.core.infrastructure.database.repositories.EventoSeguridadRepository
import com.granatum.core.service.AutenticacionService
import com.granatum.core.service.CuentaAccesoService
import com.granatum.core.service.CuentaAccesoService.DatosAlta
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An ADMIN onboards a person in one step (feature 009, US2): FR-004 to FR-009. */
@Testcontainers
@SpringBootTest(classes = [AuthTestApplication::class])
class AltaPersonaIT : BaseAuthIT() {

    @Autowired lateinit var servicio: CuentaAccesoService
    @Autowired lateinit var autenticacion: AutenticacionService
    @Autowired lateinit var eventos: EventoSeguridadRepository

    private fun dniValido(): String {
        val numero = (10_000_000..99_999_999).random()
        return "$numero${"TRWAGMYFPDXBNJZSQVHLCKE"[numero % 23]}"
    }

    private fun alta(email: String = correoUnico(), documento: String = dniValido(), rol: Role = Role.EMPLEADO) =
        DatosAlta("Ana Florista", documento, "Florista", "PARCIAL", LocalDate.of(2026, 10, 1), email, rol)

    @Test
    fun `a new person gets a staff record and an account with a temporary password to change`() {
        val email = correoUnico()

        val hecha = servicio.darDeAlta(alta(email, rol = Role.ENCARGADO))

        assertTrue(hecha.fichaCreada)
        val ficha = directorio.altas.last()
        assertEquals("Florista", ficha.puesto)
        assertEquals("PARCIAL", ficha.tipoContrato)
        assertEquals(hecha.cuenta.empleadoId, directorio.buscarPorDocumento(ficha.documento))

        val cuenta = cuentas.findByEmail(email)!!
        assertEquals(Role.ENCARGADO, cuenta.rol)
        assertTrue(cuenta.requiereCambioPassword, "FR-006: the ADMIN knows it, so it must be changed")
        assertEquals(emptyList(), PoliticaPassword.validar(hecha.passwordTemporal), "FR-005")
        assertFalse(cuenta.passwordHash.contains(hecha.passwordTemporal), "stored as a hash, never in clear")
        assertTrue(
            eventos.findAllByCuentaIdOrderByOcurridoEnDesc(cuenta.id).any { it.tipo == TipoEventoSeguridad.CUENTA_CREADA }
        )
    }

    @Test
    fun `the temporary password signs in, and only for a password change`() {
        val email = correoUnico()
        val hecha = servicio.darDeAlta(alta(email))

        val par = autenticacion.iniciarSesion(email, hecha.passwordTemporal)

        assertTrue(par.requiereCambioPassword)
    }

    /** FR-007: the person may already be on the staff register, without access. */
    @Test
    fun `an existing staff record with that document is linked, not duplicated`() {
        val documento = dniValido()
        val existente = directorio.registrarFicha(documento)
        val altasAntes = directorio.altas.size

        val hecha = servicio.darDeAlta(alta(documento = documento))

        assertFalse(hecha.fichaCreada)
        assertEquals(existente, hecha.cuenta.empleadoId)
        assertEquals(altasAntes, directorio.altas.size)
    }

    @Test
    fun `a staff record that already has an account is refused, and nothing is created`() {
        val documento = dniValido()
        cuentaActiva(empleadoId = directorio.registrarFicha(documento))
        val email = correoUnico()

        assertFailsWith<CuentaYaExisteException> { servicio.darDeAlta(alta(email, documento)) }
        assertNull(cuentas.findByEmail(email))
    }

    /** FR-008, FR-009: refused before the staff record is created, so none is left behind. */
    @Test
    fun `an address already in use is refused without creating the staff record`() {
        val usada = cuentaActiva().email
        val documento = dniValido()
        val altasAntes = directorio.altas.size

        assertFailsWith<EmailYaRegistradoException> { servicio.darDeAlta(alta(usada, documento)) }
        assertEquals(altasAntes, directorio.altas.size)
        assertNull(directorio.buscarPorDocumento(documento))
    }

    @Test
    fun `a DNI with the wrong control letter is refused`() {
        val documento = dniValido()
        val letraMala = if (documento.last() == 'T') 'R' else 'T'

        assertFailsWith<DocumentoInvalidoException> { servicio.darDeAlta(alta(documento = documento.dropLast(1) + letraMala)) }
    }

    @Test
    fun `the temporary password does not work once changed`() {
        val email = correoUnico()
        val hecha = servicio.darDeAlta(alta(email))
        val empleadoId = hecha.cuenta.empleadoId

        servicio.cambiarPasswordDeEmpleado(empleadoId, hecha.passwordTemporal, "Granatum-Propia-2026!")

        assertFailsWith<CredencialesInvalidasException> { autenticacion.iniciarSesion(email, hecha.passwordTemporal) }
        assertFalse(autenticacion.iniciarSesion(email, "Granatum-Propia-2026!").requiereCambioPassword)
    }
}
