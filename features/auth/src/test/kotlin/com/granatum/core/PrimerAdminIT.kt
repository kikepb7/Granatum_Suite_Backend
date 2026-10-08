package com.granatum.core

import com.granatum.core.domain.exception.CodigoArranqueInvalidoException
import com.granatum.core.domain.exception.EmailYaRegistradoException
import com.granatum.core.domain.type.EstadoSolicitudRegistro
import com.granatum.core.domain.type.Role
import com.granatum.core.domain.type.TipoEventoSeguridad
import com.granatum.core.infrastructure.database.repositories.EventoSeguridadRepository
import com.granatum.core.infrastructure.database.repositories.SolicitudRegistroRepository
import com.granatum.core.service.AutenticacionService
import com.granatum.core.service.DatosRegistro
import com.granatum.core.service.RegistroService
import com.granatum.core.service.ResultadoRegistro
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The first ADMIN of a fresh installation (feature 005, US1): FR-009 to FR-014.
 *
 * Every test starts from "no ADMIN account": the shared container is reused by
 * other classes, so existing ADMIN accounts are demoted first. Demoting rather
 * than deleting because cuentas_acceso has no delete path anywhere, tests
 * included, and nothing else here depends on those accounts' role.
 */
@Testcontainers
@SpringBootTest(classes = [AuthTestApplication::class])
@TestPropertySource(properties = ["auth.registro.codigo-arranque=${PrimerAdminIT.CODIGO}"])
class PrimerAdminIT : BaseAuthIT() {

    companion object {
        const val CODIGO = "arranque-de-prueba-0123456789"
    }

    @Autowired lateinit var registro: RegistroService
    @Autowired lateinit var solicitudes: SolicitudRegistroRepository
    @Autowired lateinit var eventos: EventoSeguridadRepository
    @Autowired lateinit var autenticacion: AutenticacionService
    @Autowired lateinit var jdbc: JdbcTemplate

    private val password = "Granatum-Jefe-2026!"

    @BeforeEach
    fun sinAdmin() {
        jdbc.update("UPDATE cuentas_acceso SET rol = 'EMPLEADO' WHERE rol = 'ADMIN'")
    }

    private fun datos(
        email: String = correoUnico(),
        documento: String = dniValido(),
        codigo: String? = CODIGO
    ) = DatosRegistro(email, password, "Jefa de Granatum", documento, codigo)

    @Test
    fun `the right code with no ADMIN creates the ADMIN account and a staff record`() {
        val email = correoUnico()
        val documento = dniValido()

        val resultado = registro.registrar(datos(email, documento))

        val creado = assertIs<ResultadoRegistro.AdminCreado>(resultado)
        val cuenta = cuentas.findByEmail(email)!!
        assertEquals(Role.ADMIN, cuenta.rol)
        assertFalse(cuenta.requiereCambioPassword, "the person chose this password")
        assertEquals(creado.empleadoId, cuenta.empleadoId)

        val alta = directorio.altas.last()
        assertEquals("Dirección", alta.puesto)
        assertEquals("JORNADA_COMPLETA", alta.tipoContrato)
        assertEquals(LocalDate.now(ZoneId.of("Europe/Madrid")), alta.fechaAlta)

        // And the password works straight away.
        autenticacion.iniciarSesion(email, password)
        assertTrue(
            eventos.findAllByCuentaIdOrderByOcurridoEnDesc(cuenta.id)
                .any { it.tipo == TipoEventoSeguridad.ADMIN_INICIAL_CREADO }
        )
    }

    @Test
    fun `an existing staff record with that document is linked instead of duplicated`() {
        val documento = dniValido()
        val existente = directorio.registrarFicha(documento)
        val altasAntes = directorio.altas.size

        val resultado = assertIs<ResultadoRegistro.AdminCreado>(registro.registrar(datos(documento = documento)))

        assertEquals(existente, resultado.empleadoId)
        assertEquals(altasAntes, directorio.altas.size, "no second staff record")
    }

    @Test
    fun `once an ADMIN exists the code stops working, even the right one`() {
        registro.registrar(datos())
        val email = correoUnico()

        assertFailsWith<CodigoArranqueInvalidoException> { registro.registrar(datos(email)) }
        assertEquals(null, cuentas.findByEmail(email))
    }

    @Test
    fun `an ADMIN created by the temporary-password route also closes the bootstrap`() {
        cuentaActiva(rol = Role.ADMIN)

        assertFailsWith<CodigoArranqueInvalidoException> { registro.registrar(datos()) }
    }

    @Test
    fun `a wrong code creates nothing and leaves a security event`() {
        val email = correoUnico()

        val antes = eventos.countByTipo(TipoEventoSeguridad.ARRANQUE_RECHAZADO)

        assertFailsWith<CodigoArranqueInvalidoException> { registro.registrar(datos(email, codigo = "otro-codigo")) }

        assertEquals(null, cuentas.findByEmail(email))
        assertEquals(antes + 1, eventos.countByTipo(TipoEventoSeguridad.ARRANQUE_RECHAZADO))
    }

    @Test
    fun `an address that already has an account is refused`() {
        val existente = cuentaActiva()

        assertFailsWith<EmailYaRegistradoException> { registro.registrar(datos(existente.email)) }
        assertEquals(Role.EMPLEADO, cuentas.findByEmail(existente.email)!!.rol)
    }

    @Test
    fun `pending requests with the same address are cancelled`() {
        val email = correoUnico()
        val pendiente = registro.registrar(datos(email, codigo = null))
        assertIs<ResultadoRegistro.Pendiente>(pendiente)
        val id = solicitudes.findAllByEstadoOrderByCreadaEnAsc(EstadoSolicitudRegistro.PENDIENTE)
            .single { it.email == email }.id

        registro.registrar(datos(email))

        val anulada = solicitudes.findById(id).orElseThrow()
        assertEquals(EstadoSolicitudRegistro.ANULADA, anulada.estado)
        assertEquals(null, anulada.email, "a resolved request keeps no personal data")
    }

    /**
     * Without the advisory lock both read "no ADMIN" at once and both become
     * ADMIN: two different rows, so no unique constraint stops it (D-002).
     */
    @Test
    fun `two simultaneous bootstraps with different addresses produce a single ADMIN`() {
        val salida = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val resultados = (1..2).map {
            pool.submit<Result<ResultadoRegistro>> {
                val d = datos()
                salida.await()
                runCatching { registro.registrar(d) }
            }
        }
        salida.countDown()
        val hechos = resultados.map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(1, hechos.count { it.isSuccess }, hechos.toString())
        assertIs<CodigoArranqueInvalidoException>(hechos.single { it.isFailure }.exceptionOrNull())
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM cuentas_acceso WHERE rol = 'ADMIN'", Int::class.java))
    }

    private fun dniValido(): String {
        val numero = (10_000_000..99_999_999).random()
        return "$numero${"TRWAGMYFPDXBNJZSQVHLCKE"[numero % 23]}"
    }
}
