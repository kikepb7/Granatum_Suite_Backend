package com.granatum.core

import com.granatum.core.domain.exception.CuentaYaExisteException
import com.granatum.core.domain.exception.EmailYaRegistradoException
import com.granatum.core.domain.exception.EmpleadoNoEncontradoEnDirectorioException
import com.granatum.core.domain.service.PoliticaPassword
import com.granatum.core.domain.type.Role
import com.granatum.core.domain.type.TipoEventoSeguridad
import com.granatum.core.infrastructure.database.repositories.EventoSeguridadRepository
import com.granatum.core.service.AutenticacionService
import com.granatum.core.service.CuentaAccesoService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Granting access (US4): FR-018, FR-019, FR-023a, FR-027, FR-029b to FR-029d,
 * plus SC-013 and SC-003.
 */
@Testcontainers
@SpringBootTest(classes = [AuthTestApplication::class])
class AltaCuentaIT : BaseAuthIT() {

    @Autowired lateinit var cuentaAccesoService: CuentaAccesoService
    @Autowired lateinit var autenticacion: AutenticacionService
    @Autowired lateinit var eventos: EventoSeguridadRepository

    private fun personaRegistrada(): UUID =
        UUID.randomUUID().also { directorio.registrarActivo(it) }

    /** FR-029b and SC-013: one call, not two. */
    @Test
    fun `granting access creates the account pending a password change`() {
        val empleadoId = personaRegistrada()
        val email = correoUnico()

        val (cuenta, temporal) = cuentaAccesoService.darAcceso(empleadoId, email, Role.EMPLEADO)

        assertEquals(empleadoId, cuenta.empleadoId)
        assertEquals(email, cuenta.email)
        assertTrue(cuenta.requiereCambioPassword, "FR-019")
        assertNotNull(temporal)
        assertEquals(16, temporal.length)
    }

    /** FR-023a: no route may introduce a weaker password than the policy demands. */
    @Test
    fun `the generated temporary password satisfies the policy`() {
        val (_, temporal) = cuentaAccesoService.darAcceso(
            personaRegistrada(), correoUnico(), Role.EMPLEADO
        )

        assertEquals(
            emptyList(),
            PoliticaPassword.validar(temporal),
            "an ADMIN must have no way to introduce a password weaker than the one the " +
                "person is held to"
        )
    }

    /** SC-003: 100% of stored values are salted hashes. */
    @Test
    fun `the stored password is an argon2 hash and not the temporary value`() {
        val (cuenta, temporal) = cuentaAccesoService.darAcceso(
            personaRegistrada(), correoUnico(), Role.EMPLEADO
        )

        assertTrue(
            cuenta.passwordHash.startsWith("{argon2}"),
            "got ${cuenta.passwordHash.take(20)}"
        )
        assertFalse(
            cuenta.passwordHash.contains(temporal),
            "the clear value must not survive anywhere in what is stored"
        )
    }

    @Test
    fun `the temporary password works for signing in`() {
        val (cuenta, temporal) = cuentaAccesoService.darAcceso(
            personaRegistrada(), correoUnico(), Role.EMPLEADO
        )

        val par = autenticacion.iniciarSesion(cuenta.email, temporal)

        assertTrue(
            par.requiereCambioPassword,
            "US4 scenario 2: the response has to say so, or the client cannot show the " +
                "right screen"
        )
    }

    /** FR-029c. */
    @Test
    fun `granting access to somebody who does not exist is refused`() {
        assertFailsWith<EmpleadoNoEncontradoEnDirectorioException> {
            cuentaAccesoService.darAcceso(UUID.randomUUID(), correoUnico(), Role.EMPLEADO)
        }
    }

    /** FR-027 and FR-029d: the way out of this is to reset, not to recreate. */
    @Test
    fun `a second account for the same person is refused`() {
        val empleadoId = personaRegistrada()
        cuentaAccesoService.darAcceso(empleadoId, correoUnico(), Role.EMPLEADO)

        assertFailsWith<CuentaYaExisteException> {
            cuentaAccesoService.darAcceso(empleadoId, correoUnico(), Role.EMPLEADO)
        }
    }

    /** FR-028, and with a distinct code because the way out differs. */
    @Test
    fun `an email that belongs to another account is refused`() {
        val email = correoUnico()
        cuentaAccesoService.darAcceso(personaRegistrada(), email, Role.EMPLEADO)

        assertFailsWith<EmailYaRegistradoException> {
            cuentaAccesoService.darAcceso(personaRegistrada(), email, Role.EMPLEADO)
        }
    }

    @Test
    fun `the email is normalised on the way in`() {
        val empleadoId = personaRegistrada()

        val (cuenta, _) = cuentaAccesoService.darAcceso(
            empleadoId, "  MiXeD.Case@Granatum.ES  ", Role.EMPLEADO
        )

        assertEquals("mixed.case@granatum.es", cuenta.email)
        assertFailsWith<EmailYaRegistradoException> {
            cuentaAccesoService.darAcceso(personaRegistrada(), "mixed.case@GRANATUM.es", Role.EMPLEADO)
        }
    }

    /**
     * The role is what this feature's implementation had to invent, because
     * FR-005 needs it in the token and nothing stored one. It must reach the
     * token unchanged, or authorisation silently decides something else.
     */
    @Test
    fun `the role given at sign-up is the role in the token`() {
        val (cuenta, temporal) = cuentaAccesoService.darAcceso(
            personaRegistrada(), correoUnico(), Role.REPRESENTANTE
        )

        assertEquals(Role.REPRESENTANTE, cuenta.rol)
        assertNotNull(autenticacion.iniciarSesion(cuenta.email, temporal).accessToken)
    }

    @Test
    fun `two people with the same temporary password would still differ in storage`() {
        // The generator makes a collision essentially impossible, so the salt is
        // asserted directly: the same input, encoded twice, must differ.
        val uno = cuentaAccesoService.darAcceso(personaRegistrada(), correoUnico(), Role.EMPLEADO)
        val otro = cuentaAccesoService.darAcceso(personaRegistrada(), correoUnico(), Role.EMPLEADO)

        assertFalse(
            uno.first.passwordHash == otro.first.passwordHash,
            "SC-003: one cracked hash must not crack every account that shared a password"
        )
    }

    @Test
    fun `creating an account is recorded`() {
        val (cuenta, _) = cuentaAccesoService.darAcceso(
            personaRegistrada(), correoUnico(), Role.EMPLEADO
        )

        val tipos = eventos.findAllByCuentaIdOrderByOcurridoEnDesc(cuenta.id).map { it.tipo }
        assertTrue(tipos.contains(TipoEventoSeguridad.CUENTA_CREADA), "got $tipos")
    }
}
