package com.granatum.core

import com.granatum.core.domain.exception.AusenciaSolapadaException
import com.granatum.core.domain.model.CausaPermiso
import com.granatum.core.domain.model.EstadoAusencia
import com.granatum.core.domain.model.TipoAusencia
import com.granatum.core.infrastructure.database.repositories.AusenciaRepository
import com.granatum.core.service.AusenciaService
import com.granatum.core.service.NuevaAusencia
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** No two standing absences of one person overlap (feature 007, US2): FR-009, SC-002. */
@Testcontainers
@SpringBootTest(classes = [AbsencesTestApplication::class])
class SolapamientoAusenciasIT : BaseAusenciasIT() {

    @Autowired lateinit var servicio: AusenciaService
    @Autowired lateinit var repositorio: AusenciaRepository

    private val anio get() = LocalDate.now(ZoneId.of("Europe/Madrid")).year + 1
    private fun dia(mes: Int, d: Int) = LocalDate.of(anio, mes, d)

    private fun vacaciones(desde: LocalDate, hasta: LocalDate) =
        NuevaAusencia(TipoAusencia.VACACIONES, null, desde, hasta, null)

    private fun permiso(desde: LocalDate, hasta: LocalDate) =
        NuevaAusencia(TipoAusencia.PERMISO, CausaPermiso.MUDANZA, desde, hasta, null)

    @Test
    fun `an approved or a pending absence blocks the days it covers`() {
        val persona = directorio.activa()
        val aprobada = servicio.solicitar(persona, vacaciones(dia(8, 3), dia(8, 14)))
        servicio.aprobar(aprobada.id, UUID.randomUUID())
        servicio.solicitar(persona, vacaciones(dia(9, 1), dia(9, 2)))

        assertFailsWith<AusenciaSolapadaException> { servicio.solicitar(persona, permiso(dia(8, 10), dia(8, 11))) }
        assertFailsWith<AusenciaSolapadaException> { servicio.solicitar(persona, permiso(dia(9, 2), dia(9, 3))) }
        // Touching edges overlap: both ends are included.
        assertFailsWith<AusenciaSolapadaException> { servicio.solicitar(persona, permiso(dia(8, 14), dia(8, 14))) }
    }

    @Test
    fun `rejected or cancelled days are free again`() {
        val persona = directorio.activa()
        val rechazada = servicio.solicitar(persona, vacaciones(dia(8, 3), dia(8, 14)))
        servicio.rechazar(rechazada.id, UUID.randomUUID(), "No")
        val cancelada = servicio.solicitar(persona, vacaciones(dia(10, 5), dia(10, 9)))
        servicio.cancelar(cancelada.id, persona)

        servicio.solicitar(persona, vacaciones(dia(8, 3), dia(8, 14)))
        servicio.solicitar(persona, vacaciones(dia(10, 5), dia(10, 9)))
    }

    @Test
    fun `an open sick leave blocks every day after its start`() {
        val persona = directorio.activa()
        servicio.registrar(UUID.randomUUID(), persona, NuevaAusencia(TipoAusencia.BAJA_MEDICA, null, dia(3, 1), null, null))

        assertFailsWith<AusenciaSolapadaException> { servicio.solicitar(persona, vacaciones(dia(11, 2), dia(11, 3))) }
        servicio.solicitar(persona, permiso(dia(2, 26), dia(2, 27)))
    }

    /**
     * SC-002. Without the per-person advisory lock every thread reads "no
     * overlap" before any has written, and several are stored (research.md
     * D-002).
     */
    @Test
    fun `twenty simultaneous overlapping requests of one person admit exactly one`() {
        val persona = directorio.activa()
        val salida = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(20)

        val resultados = (1..20).map { i ->
            pool.submit<Result<Any>> {
                salida.await()
                runCatching { servicio.solicitar(persona, permiso(dia(5, 1 + i % 3), dia(5, 10))) }
            }
        }
        salida.countDown()
        val hechos = resultados.map { it.get(60, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(1, hechos.count { it.isSuccess }, hechos.filter { it.isFailure }.map { it.exceptionOrNull()?.javaClass?.simpleName }.toString())
        hechos.filter { it.isFailure }.forEach { assertEquals<Class<*>>(AusenciaSolapadaException::class.java, it.exceptionOrNull()!!.javaClass) }
        assertEquals(
            1,
            repositorio.deEmpleadoEnRango(persona, dia(5, 1), dia(5, 10), listOf(EstadoAusencia.PENDIENTE, EstadoAusencia.APROBADA)).size
        )
    }

    @Test
    fun `two different people do not block each other`() {
        val una = directorio.activa()
        val otra = directorio.activa()

        servicio.solicitar(una, vacaciones(dia(7, 1), dia(7, 5)))
        servicio.solicitar(otra, vacaciones(dia(7, 1), dia(7, 5)))
    }
}
