package com.granatum.core

import com.granatum.core.domain.contract.AltaFichaPersonal
import com.granatum.core.domain.contract.FichasPersonal
import com.granatum.core.domain.type.TipoContrato
import com.granatum.core.infrastructure.database.repositories.EmpleadoRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDate
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * This module's side of the `FichasPersonal` contract, which is how `auth`
 * turns an approved sign-up into a staff record without depending on
 * `timetracking` (feature 005, constitution principle I).
 */
@Testcontainers
@SpringBootTest(classes = [TimetrackingTestApplication::class])
class FichasPersonalJpaIT {

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
            registry.add("timetracking.retencion.cron") { "0 0 4 1 1 *" }
        }

        /** A random DNI with its correct control letter. */
        fun dniValido(): String {
            val numero = Random.nextInt(10_000_000, 99_999_999)
            return "$numero${"TRWAGMYFPDXBNJZSQVHLCKE"[numero % 23]}"
        }
    }

    @Autowired lateinit var fichas: FichasPersonal
    @Autowired lateinit var empleados: EmpleadoRepository

    private fun alta(documento: String, tipo: String = "PARCIAL") = AltaFichaPersonal(
        nombre = "Persona registrada",
        documento = documento,
        puesto = "Florista",
        tipoContrato = tipo,
        fechaAlta = LocalDate.of(2026, 10, 1)
    )

    @Test
    fun `crear leaves an active staff record with the given data`() {
        val id = fichas.crear(alta(dniValido()))

        val ficha = empleados.findById(id).orElseThrow()
        assertTrue(ficha.activo)
        assertEquals(TipoContrato.PARCIAL, ficha.tipoContrato)
        assertEquals("Florista", ficha.puesto)
        assertEquals(LocalDate.of(2026, 10, 1), ficha.fechaAlta)
    }

    @Test
    fun `buscarPorDocumento normalises, so a hyphen or a lowercase letter still finds the record`() {
        val dni = dniValido()
        val id = fichas.crear(alta(dni))

        val escritoDeOtraForma = dni.dropLast(1) + "-" + dni.last().lowercaseChar()
        assertEquals(id, fichas.buscarPorDocumento(escritoDeOtraForma))
    }

    @Test
    fun `buscarPorDocumento answers null when nobody has that document`() {
        assertNull(fichas.buscarPorDocumento(dniValido()))
    }

    @Test
    fun `an unknown contract type is refused rather than defaulted`() {
        assertThrows<IllegalArgumentException> { fichas.crear(alta(dniValido(), tipo = "INDEFINIDO")) }
    }
}
