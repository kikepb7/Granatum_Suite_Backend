package com.granatum.core

import com.granatum.core.domain.type.Role
import com.granatum.core.domain.type.TipoContrato
import com.granatum.core.infrastructure.database.entities.EmpleadoEntity
import com.granatum.core.infrastructure.database.entities.SolicitudCorreccionFichajeEntity
import com.granatum.core.infrastructure.database.repositories.EmpleadoRepository
import com.granatum.core.service.FichajeService
import jakarta.persistence.EntityManagerFactory
import org.hibernate.SessionFactory
import org.hibernate.stat.Statistics
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D-011: the monthly summary reads the corrections of **its** month, and nothing
 * else.
 *
 * The previous version loaded every approved correction in the table - every
 * person, every month, the whole history - on each summary, although its
 * comment said "one query over the month". The query count was indeed one, which
 * is why nothing noticed: the cost was in what that one query brought back, and
 * it grew with the history of the company. The monthly download of the whole
 * staff (feature 003) would have repeated it once per person.
 *
 * So this counts **entities loaded**, not only statements. A test that counted
 * statements alone passed against the broken version.
 *
 * Rows are inserted through JDBC with fixed past dates, for the reason
 * [RetencionIT] gives: the service refuses timestamps outside the clock
 * tolerance, and the month under test must not depend on today's date.
 */
@Testcontainers
@SpringBootTest(classes = [TimetrackingTestApplication::class])
class ResumenMensualConsultasIT {

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
            registry.add("spring.jpa.properties.hibernate.generate_statistics") { "true" }
        }
    }

    @Autowired lateinit var fichajeService: FichajeService
    @Autowired lateinit var empleadoRepository: EmpleadoRepository
    @Autowired lateinit var dataSource: DataSource
    @Autowired lateinit var entityManagerFactory: EntityManagerFactory

    private val estadisticas: Statistics
        get() = entityManagerFactory.unwrap(SessionFactory::class.java).statistics

    private fun nuevoEmpleado(): EmpleadoEntity =
        empleadoRepository.save(
            EmpleadoEntity(
                nombre = "Persona de prueba",
                documentoIdentidad = "T${UUID.randomUUID().toString().take(8).uppercase()}",
                puesto = "Florista",
                tipoContrato = TipoContrato.JORNADA_COMPLETA,
                fechaAlta = LocalDate.parse("2020-01-01"),
                activo = true
            )
        )

    /** A closed eight-hour shift on [fecha], with an approved correction on it. */
    private fun fichajeCorregido(empleado: EmpleadoEntity, fecha: String): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { connection ->
            connection.createStatement().use { s ->
                val entrada = "TIMESTAMPTZ '$fecha 08:00:00 Europe/Madrid'"
                s.execute(
                    """
                    INSERT INTO fichajes
                        (id, empleado_id, entrada, salida, estado, minutos_trabajados,
                         fue_incompleto, created_at, updated_at)
                    VALUES ('$id', '${empleado.id}', $entrada,
                            $entrada + interval '8 hours', 'CERRADO', 480, FALSE,
                            now(), now())
                    """.trimIndent()
                )
                s.execute(
                    """
                    INSERT INTO solicitudes_correccion_fichaje
                        (id, fichaje_id, solicitante_id, motivo, valores_propuestos,
                         valores_originales, estado, resuelta_por_id, resuelta_en, created_at)
                    VALUES ('${UUID.randomUUID()}', '$id', '${empleado.id}',
                            'Motivo de prueba', '{}'::jsonb, '{}'::jsonb, 'APROBADA',
                            '${UUID.randomUUID()}', now(), now())
                    """.trimIndent()
                )
            }
        }
        return id
    }

    private fun resumen(empleado: EmpleadoEntity) =
        fichajeService.resumenMensual(empleado.id, empleado.id, Role.EMPLEADO, 2025, 3)

    @Test
    fun `the summary loads only the corrections of its own month`() {
        val consultado = nuevoEmpleado()
        fichajeCorregido(consultado, "2025-03-10")

        estadisticas.clear()
        val antes = resumen(consultado)
        val sentenciasSinAjenas = estadisticas.prepareStatementCount

        // Fifty approved corrections the summary has no business reading: other
        // people in the same month, and the same person in other months.
        repeat(25) { fichajeCorregido(nuevoEmpleado(), "2025-03-${10 + it % 15}") }
        repeat(25) { fichajeCorregido(consultado, "2024-${(1 + it % 12).toString().padStart(2, '0')}-15") }

        estadisticas.clear()
        val despues = resumen(consultado)
        val cargadas = estadisticas
            .getEntityStatistics(SolicitudCorreccionFichajeEntity::class.java.name)
            .loadCount

        assertTrue(despues.dias.single().corregido, "the day of the month is still flagged")
        assertEquals(antes, despues, "foreign corrections must not change the result")
        assertTrue(
            cargadas <= 1,
            "the summary loaded $cargadas corrections for a month that has one; " +
                "it must not read the rest of the company's history"
        )
        assertEquals(
            sentenciasSinAjenas,
            estadisticas.prepareStatementCount,
            "the number of statements must not grow with corrections outside the month"
        )
    }
}
