package com.granatum.core

import com.granatum.core.domain.contract.DirectorioEmpleados
import com.granatum.core.domain.type.EstadoEmpleado
import com.granatum.core.domain.type.TipoContrato
import com.granatum.core.infrastructure.database.entities.EmpleadoEntity
import com.granatum.core.infrastructure.database.repositories.EmpleadoRepository
import jakarta.persistence.EntityManagerFactory
import org.hibernate.SessionFactory
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
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * This module's side of the `DirectorioEmpleados` contract, which is how `auth`
 * learns whether a person exists and is employed without depending on
 * `timetracking` (constitution principle I).
 *
 * The query-count assertion is not decoration. The contract states that
 * `existentes` must answer in a single query, because the orphan-account sweep
 * in `auth` walks every account: implemented as a loop over `estado` it would be
 * a guaranteed N+1, and nothing in `auth` could detect that from the other side
 * of the interface. The count is taken from Hibernate's statistics, the same way
 * feature 001 asserts its own fetch plans.
 */
@Testcontainers
@SpringBootTest(classes = [TimetrackingTestApplication::class])
class DirectorioEmpleadosIT {

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
            registry.add("spring.jpa.properties.hibernate.generate_statistics") { "true" }
            registry.add("timetracking.incompletos.cron") { "0 0 4 1 1 *" }
            registry.add("timetracking.retencion.cron") { "0 0 4 1 1 *" }
        }
    }

    @Autowired lateinit var directorio: DirectorioEmpleados
    @Autowired lateinit var empleadoRepository: EmpleadoRepository
    @Autowired lateinit var entityManagerFactory: EntityManagerFactory

    private val estadisticas
        get() = entityManagerFactory.unwrap(SessionFactory::class.java).statistics

    private fun nuevoEmpleado(activo: Boolean): EmpleadoEntity =
        empleadoRepository.save(
            EmpleadoEntity(
                nombre = "Persona de prueba",
                documentoIdentidad = "T${UUID.randomUUID().toString().take(8).uppercase()}",
                puesto = "Florista",
                tipoContrato = TipoContrato.JORNADA_COMPLETA,
                fechaAlta = LocalDate.parse("2026-10-01"),
                activo = activo
            )
        )

    @Test
    fun `an active person reports ACTIVO`() {
        val empleado = nuevoEmpleado(activo = true)
        assertEquals(EstadoEmpleado.ACTIVO, directorio.estado(empleado.id))
    }

    /** FR-002 and FR-010 both hang off this answer. */
    @Test
    fun `a deactivated person reports INACTIVO`() {
        val empleado = nuevoEmpleado(activo = false)
        assertEquals(EstadoEmpleado.INACTIVO, directorio.estado(empleado.id))
    }

    /**
     * The case FR-029c is about. `null` and not an exception: "this person does
     * not exist" is an answer the caller has to act on, not a failure.
     */
    @Test
    fun `an unknown id reports null rather than throwing`() {
        assertNull(directorio.estado(UUID.randomUUID()))
    }

    @Test
    fun `existentes keeps only the ids that exist`() {
        val uno = nuevoEmpleado(activo = true)
        val otro = nuevoEmpleado(activo = false)
        val fantasma = UUID.randomUUID()

        val presentes = directorio.existentes(listOf(uno.id, otro.id, fantasma))

        assertEquals(setOf(uno.id, otro.id), presentes)
    }

    /**
     * An inactive person still *exists*. Confusing the two would make the
     * orphan sweep report everyone on leave as an orphan account, and the
     * obvious "fix" would be to delete their credentials - and with them their
     * access to a working-time record the law requires be available to them.
     */
    @Test
    fun `existentes does not filter by employment state`() {
        val inactivo = nuevoEmpleado(activo = false)
        assertEquals(setOf(inactivo.id), directorio.existentes(listOf(inactivo.id)))
    }

    @Test
    fun `existentes answers in a single query for many ids`() {
        val ids = (1..20).map { nuevoEmpleado(activo = true).id }

        estadisticas.clear()
        val presentes = directorio.existentes(ids)

        assertEquals(ids.toSet(), presentes)
        assertEquals(
            1,
            estadisticas.prepareStatementCount,
            "the contract requires one query: the orphan sweep in `auth` walks every " +
                "account, so a loop over `estado` would be a guaranteed N+1 that nothing " +
                "on the other side of the interface could detect"
        )
    }

    /** `IN ()` is a syntax error in Postgres, so the empty case must not reach it. */
    @Test
    fun `an empty input issues no query at all`() {
        estadisticas.clear()
        assertEquals(emptySet(), directorio.existentes(emptyList()))
        assertEquals(0, estadisticas.prepareStatementCount)
    }
}
