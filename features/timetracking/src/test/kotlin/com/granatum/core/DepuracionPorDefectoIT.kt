package com.granatum.core

import com.granatum.core.infrastructure.database.repositories.DepuracionRetencionRepository
import com.granatum.core.infrastructure.database.repositories.EmpleadoRepository
import com.granatum.core.scheduling.DepuracionRetencionJob
import com.granatum.core.service.PlazoConservacion
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FR-024: shipping the monthly download does **not** switch the purge on.
 *
 * Feature 003 meets the condition that kept the purge disabled (FR-031d of
 * feature 001). What has to stay fixed is that meeting it changes nothing by
 * itself: enabling the one operation that destroys records with legal value is
 * an explicit decision per environment. Until now no test pinned the default;
 * `RetencionIT` only exercises the purge switched on.
 *
 * No `timetracking.retencion.*` property is set here, on purpose.
 */
@Testcontainers
@SpringBootTest(classes = [TimetrackingTestApplication::class])
class DepuracionPorDefectoIT {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.jpa.hibernate.ddl-auto") { "validate" }
            registry.add("spring.flyway.enabled") { "true" }
            registry.add("timetracking.incompletos.cron") { "0 0 4 1 1 *" }
        }
    }

    @Autowired lateinit var job: DepuracionRetencionJob
    @Autowired lateinit var depuraciones: DepuracionRetencionRepository
    @Autowired lateinit var plazo: PlazoConservacion
    @Autowired lateinit var empleadoRepository: EmpleadoRepository
    @Autowired lateinit var dataSource: DataSource

    @Test
    fun `without configuration the scheduled purge deletes nothing, even past the period`() {
        val semilla = SemillaRegistro(dataSource, empleadoRepository)
        val persona = semilla.empleado()
        val vencido = plazo.fechaCorte().minusYears(1)
        semilla.fichaje(persona, "$vencido 07:00", "$vencido 15:00")
        val antes = semilla.huellaDelRegistro()
        assertTrue(antes.getValue("fichajes").startsWith("1/"), "an expired shift is there to be purged")

        // The scheduled entry point, not ejecutar(): the switch is what is under test.
        job.depurar()

        assertEquals(antes, semilla.huellaDelRegistro(), "nothing deleted")
        assertEquals(emptyList(), depuraciones.findAllByOrderByEjecutadaEnDesc(), "and no run recorded")
    }
}
