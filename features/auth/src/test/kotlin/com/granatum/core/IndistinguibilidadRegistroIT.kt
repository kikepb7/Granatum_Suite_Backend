package com.granatum.core

import com.granatum.core.domain.type.EstadoSolicitudRegistro
import com.granatum.core.domain.type.TipoEventoSeguridad
import com.granatum.core.service.ResultadoRegistro
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Sign-up must not reveal which addresses already have an account (feature 005,
 * US3): FR-006, FR-007, SC-004.
 *
 * ## What the timing test can promise
 *
 * Statistical, not cryptographic - the same caveat as IndistinguibilidadLoginIT.
 * What it has to catch is a code path that skips the hash for a taken address,
 * which answers an order of magnitude faster. With Argon2 at production cost
 * (~110 ms) the remaining difference - one INSERT - is a few milliseconds, and
 * SC-004 asks for under 10%. Medians rather than means: one GC pause would move
 * a mean of 50 samples by more than the INSERT does.
 */
@Testcontainers
@SpringBootTest(classes = [AuthTestApplication::class])
class IndistinguibilidadRegistroIT : BaseRegistroIT() {

    companion object {
        @DynamicPropertySource
        @JvmStatic
        fun costeYCapacidad(registry: DynamicPropertyRegistry) {
            // Production cost: with the module's 1 MiB / t=1 the hash is so cheap
            // that the INSERT alone would dominate and the test would measure the
            // database instead of the property.
            registry.add("auth.password.argon2.memory-kb") { "65536" }
            registry.add("auth.password.argon2.iterations") { "3" }
            // 50 new sign-ups per run plus the warm-up must fit.
            registry.add("auth.registro.max-pendientes") { "1000" }
        }
    }

    private val muestras = 50

    private fun medirMs(bloque: () -> Unit): Double {
        val inicio = System.nanoTime()
        bloque()
        return (System.nanoTime() - inicio) / 1_000_000.0
    }

    private fun mediana(valores: List<Double>): Double = valores.sorted()[valores.size / 2]

    @Test
    fun `an address with an account gets the same answer and nothing approvable is stored`() {
        val existente = cuentaActiva()
        val pendientesAntes = solicitudes.countByEstado(EstadoSolicitudRegistro.PENDIENTE)
        val duplicadosAntes = eventos.countByTipo(TipoEventoSeguridad.REGISTRO_DUPLICADO)

        val nuevo = assertIs<ResultadoRegistro.Pendiente>(registro.registrar(datos()))
        val repetido = assertIs<ResultadoRegistro.Pendiente>(registro.registrar(datos(existente.email)))

        // Same shape: an 8-character code from the same alphabet.
        assertEquals(nuevo.codigoVerificacion.length, repetido.codigoVerificacion.length)
        assertEquals(pendientesAntes + 1, solicitudes.countByEstado(EstadoSolicitudRegistro.PENDIENTE))
        assertTrue(
            solicitudes.findAllByEstadoOrderByCreadaEnAsc(EstadoSolicitudRegistro.PENDIENTE).none { it.email == existente.email }
        )
        assertEquals(duplicadosAntes + 1, eventos.countByTipo(TipoEventoSeguridad.REGISTRO_DUPLICADO))
    }

    /** D-001: the impostor and the real person hold different codes. */
    @Test
    fun `an address with a pending request gets another independent request`() {
        val email = correoUnico()
        val (primera, _) = pendiente(email)
        val (segunda, _) = pendiente(email)

        assertTrue(primera.id != segunda.id)
        assertEquals(
            2,
            solicitudes.findAllByEstadoOrderByCreadaEnAsc(EstadoSolicitudRegistro.PENDIENTE).count { it.email == email }
        )
    }

    @Test
    fun `a new address and a taken one take the same time`() {
        val existente = cuentaActiva()

        // Warm-up: JIT and connection pool, for both paths.
        repeat(5) {
            registro.registrar(datos())
            registro.registrar(datos(existente.email))
        }

        val nuevos = (1..muestras).map { medirMs { registro.registrar(datos()) } }
        val repetidos = (1..muestras).map { medirMs { registro.registrar(datos(existente.email)) } }

        val mNuevos = mediana(nuevos)
        val mRepetidos = mediana(repetidos)
        val diferencia = kotlin.math.abs(mNuevos - mRepetidos) / maxOf(mNuevos, mRepetidos)

        assertTrue(
            diferencia < 0.10,
            "SC-004: the two paths must differ by under 10%, or the response time says " +
                "which addresses have an account. nuevo=%.1fms repetido=%.1fms (%.1f%%)"
                    .format(mNuevos, mRepetidos, diferencia * 100)
        )
    }
}
