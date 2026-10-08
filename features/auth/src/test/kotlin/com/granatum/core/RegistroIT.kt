package com.granatum.core

import com.granatum.core.domain.exception.CredencialesInvalidasException
import com.granatum.core.domain.exception.DocumentoInvalidoException
import com.granatum.core.domain.exception.PasswordDebilException
import com.granatum.core.domain.exception.RegistroNoDisponibleException
import com.granatum.core.domain.type.EstadoSolicitudRegistro
import com.granatum.core.domain.type.TipoEventoSeguridad
import com.granatum.core.service.AutenticacionService
import com.granatum.core.service.DatosRegistro
import com.granatum.core.service.ResultadoRegistro
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import com.granatum.core.domain.event.AvisoDominio
import com.granatum.core.domain.event.TipoAviso
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** An ordinary sign-up (feature 005, US2): FR-001 to FR-005, FR-008, FR-031. */
@Testcontainers
@SpringBootTest(classes = [AuthTestApplication::class])
@TestPropertySource(properties = ["auth.registro.max-pendientes=5"])
@RecordApplicationEvents
class RegistroIT : BaseRegistroIT() {

    @Autowired lateinit var autenticacion: AutenticacionService
    @Autowired lateinit var avisos: ApplicationEvents

    /** Feature 008, FR-004: the ADMINs hear about a stored request - and only a stored one. */
    @Test
    fun `a stored request is announced, a duplicate address is not`() {
        val (solicitud, _) = pendiente()
        assertTrue(avisos.stream(AvisoDominio::class.java).anyMatch { it.tipo == TipoAviso.REGISTRO_PENDIENTE && it.referenciaId == solicitud.id })

        val antes = avisos.stream(AvisoDominio::class.java).count()
        registro.registrar(datos(cuentaActiva().email))
        assertEquals(antes, avisos.stream(AvisoDominio::class.java).count(), "nothing approvable, nothing to announce")
    }

    @Test
    fun `signing up leaves a pending request and hands back an 8-character code`() {
        val email = correoUnico()
        val antes = eventos.countByTipo(TipoEventoSeguridad.REGISTRO_SOLICITADO)

        val resultado = assertIs<ResultadoRegistro.Pendiente>(registro.registrar(datos(email)))

        assertEquals(8, resultado.codigoVerificacion.length)
        val solicitud = solicitudes.findAllByEstadoOrderByCreadaEnAsc(EstadoSolicitudRegistro.PENDIENTE)
            .single { it.email == email }
        assertTrue(solicitud.passwordHash!!.startsWith("{argon2}"))
        assertFalse(solicitud.passwordHash!!.contains(password))
        assertFalse(solicitud.codigoHash!!.contains(resultado.codigoVerificacion), "FR-005: no clear code")
        assertEquals(antes + 1, eventos.countByTipo(TipoEventoSeguridad.REGISTRO_SOLICITADO))
    }

    /** FR-003, FR-031: a pending request gives no access, and says nothing different. */
    @Test
    fun `a pending request cannot sign in, and fails exactly like bad credentials`() {
        val email = correoUnico()
        registro.registrar(datos(email))

        assertFailsWith<CredencialesInvalidasException> { autenticacion.iniciarSesion(email, password) }
        assertEquals(null, cuentas.findByEmail(email))
    }

    @Test
    fun `the address and the document are normalised`() {
        val email = correoUnico()
        val documento = dniValido()

        registro.registrar(DatosRegistro("  ${email.uppercase()} ", password, " Ana ", documento.dropLast(1) + "-" + documento.last().lowercaseChar()))

        val solicitud = solicitudes.findAllByEstadoOrderByCreadaEnAsc(EstadoSolicitudRegistro.PENDIENTE)
            .single { it.email == email }
        assertEquals(documento, solicitud.documentoIdentidad)
        assertEquals("Ana", solicitud.nombre)
    }

    @Test
    fun `a weak password is refused with the policy's requirements`() {
        val e = assertFailsWith<PasswordDebilException> {
            registro.registrar(DatosRegistro(correoUnico(), "corta", "Ana", dniValido()))
        }
        assertTrue(e.requisitos.isNotEmpty())
    }

    @Test
    fun `a DNI with the wrong control letter is refused`() {
        val documento = dniValido()
        val letraMala = if (documento.last() == 'T') 'R' else 'T'

        assertFailsWith<DocumentoInvalidoException> {
            registro.registrar(datos(documento = documento.dropLast(1) + letraMala))
        }
    }

    /** FR-008: the cap is 5 in this class. */
    @Test
    fun `beyond the pending cap a sign-up is refused and nothing is stored`() {
        repeat(5) { registro.registrar(datos()) }
        val email = correoUnico()

        assertFailsWith<RegistroNoDisponibleException> { registro.registrar(datos(email)) }
        assertEquals(5L, solicitudes.countByEstado(EstadoSolicitudRegistro.PENDIENTE))
        assertTrue(solicitudes.findAllByEstadoOrderByCreadaEnAsc(EstadoSolicitudRegistro.PENDIENTE).none { it.email == email })
    }
}
