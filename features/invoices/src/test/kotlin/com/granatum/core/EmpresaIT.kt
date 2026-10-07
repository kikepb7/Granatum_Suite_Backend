package com.granatum.core

import com.granatum.core.domain.exception.EmpresaSinConfigurarException
import com.granatum.core.domain.exception.NifInvalidoException
import com.granatum.core.service.EmpresaService
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * FR-029: the company's own data, which decides whether an invoice was issued
 * or received (research.md D-014).
 */
@Testcontainers
@SpringBootTest(classes = [InvoicesTestApplication::class])
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class EmpresaIT {

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
        }
    }

    @Autowired lateinit var empresa: EmpresaService

    private val admin = UUID.randomUUID()

    @Test
    @Order(1)
    fun `before it is configured, reading it says so with a 404`() {
        val e = assertFailsWith<EmpresaSinConfigurarException> { empresa.consultar() }
        assertEquals(HttpStatus.NOT_FOUND, e.estado)
    }

    @Test
    @Order(2)
    fun `a NIF with a wrong control is refused`() {
        assertFailsWith<NifInvalidoException> { empresa.guardar("Floristeria Granatum S.L.", "B12345675", admin) }
    }

    @Test
    @Order(3)
    fun `a valid NIF is stored as written and normalised, and replaces the previous data`() {
        empresa.guardar("Primera S.L.", "B-1234 5674", admin)
        val guardada = empresa.guardar("Floristeria Granatum S.L.", "ESB12345674", admin)

        assertEquals("Floristeria Granatum S.L.", guardada.razonSocial)
        assertEquals("ESB12345674", guardada.nif, "as written")
        assertEquals("B12345674", guardada.nifNormalizado, "VAT prefix and punctuation removed")
        assertEquals(guardada, empresa.consultar())
    }

    /** No key in the test configuration: invoices are filled in by hand (research.md D-006). */
    @Test
    @Order(4)
    fun `recognition is reported inactive without an API key`() {
        assertFalse(empresa.consultar().reconocimientoActivo)
    }
}
