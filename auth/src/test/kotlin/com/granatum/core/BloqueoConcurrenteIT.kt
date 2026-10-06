package com.granatum.core

import com.granatum.core.domain.exception.CredencialesInvalidasException
import com.granatum.core.service.AutenticacionService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Five simultaneous failures must end in a lockout, not in none.
 *
 * ## What this catches
 *
 * Without the `PESSIMISTIC_WRITE` re-read in `GestorBloqueoCuenta`, two threads
 * both reading `intentos_fallidos = 4` would both write `5`, and the account
 * would finish with a counter of five and **no lockout** - the one outcome the
 * protection cannot allow, and the one an attacker reaches by simply sending
 * their guesses in parallel, which is what a credential-stuffing tool does by
 * default.
 *
 * ## What it deliberately does not assert
 *
 * The exact final counter. Between the unlocked read that decides the fast
 * rejection and the short transaction that applies the transition there is a
 * documented window, so an extra attempt can be counted or skipped depending on
 * interleaving. That changes nothing: the invariant is that the account ends up
 * locked, and FR-016c stops the stragglers from extending it.
 *
 * ## Verified by mutation
 *
 * Dropping `@Lock(PESSIMISTIC_WRITE)` from `findWithLockById` turns all three
 * assertions below red - the account ends unlocked and **still accepts the
 * correct password** - while all nine tests in `BloqueoIT` stay green. That is
 * what this file is worth: without it the suite would pass over a lockout that
 * any parallel guessing tool walks straight through.
 */
@Testcontainers
@SpringBootTest(classes = [AuthTestApplication::class])
class BloqueoConcurrenteIT : BaseAuthIT() {

    @Autowired lateinit var autenticacion: AutenticacionService

    private val password = "Granatum-2026!"

    private fun recargar(id: UUID) = cuentas.findById(id).orElseThrow()

    private fun fallarEnParalelo(email: String, hilos: Int) {
        val salida = CountDownLatch(1)
        val listos = CountDownLatch(hilos)
        val terminados = CountDownLatch(hilos)
        val pool = Executors.newFixedThreadPool(hilos)

        repeat(hilos) { i ->
            pool.submit {
                listos.countDown()
                salida.await(10, TimeUnit.SECONDS)
                runCatching { autenticacion.iniciarSesion(email, "incorrecta-$i") }
                terminados.countDown()
            }
        }

        assertTrue(listos.await(10, TimeUnit.SECONDS))
        salida.countDown()
        assertTrue(terminados.await(60, TimeUnit.SECONDS), "every thread must finish")
        pool.shutdown()
    }

    @Test
    fun `five simultaneous failures end in a lockout`() {
        val cuenta = cuentaActiva(password = password)

        fallarEnParalelo(cuenta.email, 5)

        val estado = recargar(cuenta.id).estadoBloqueo
        assertNotNull(
            estado.bloqueadaHasta,
            "without the row lock, two threads reading 4 both write 5 and the account " +
                "ends with the counter full and no lockout - which is exactly what a " +
                "parallel credential-stuffing tool produces"
        )
        assertEquals(1, estado.nivelBloqueo.toInt())
    }

    /** And the lockout actually holds afterwards, correct password included. */
    @Test
    fun `the account is refused after the simultaneous failures`() {
        val cuenta = cuentaActiva(password = password)

        fallarEnParalelo(cuenta.email, 5)

        assertFailsWith<CredencialesInvalidasException> {
            autenticacion.iniciarSesion(cuenta.email, password)
        }
    }

    /**
     * More threads than the threshold must not escalate past level one: the
     * stragglers arrive with the lockout already active, and FR-016c says they
     * change nothing.
     */
    @Test
    fun `twenty simultaneous failures still produce only a level one lockout`() {
        val cuenta = cuentaActiva(password = password)

        fallarEnParalelo(cuenta.email, 20)

        assertEquals(
            1,
            recargar(cuenta.id).estadoBloqueo.nivelBloqueo.toInt(),
            "twenty parallel guesses must cost one minute, not an hour: otherwise " +
                "flooding is a cheap way to escalate somebody else's lockout"
        )
    }
}
