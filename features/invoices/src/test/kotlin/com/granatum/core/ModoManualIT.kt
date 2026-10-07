package com.granatum.core

import com.granatum.core.domain.exception.ReconocimientoNoDisponibleException
import com.granatum.core.domain.model.EstadoFactura
import com.granatum.core.domain.port.ReconocedorFacturas
import com.granatum.core.infrastructure.claude.ReconocedorDeshabilitado
import com.granatum.core.service.FacturaLecturas
import com.granatum.core.service.FicheroSubido
import com.granatum.core.service.SubidaFacturas
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * FR-006, SC-008: without an API key nothing is sent anywhere, and invoices
 * are still stored, waiting to be filled in by hand (research.md D-006).
 *
 * No double here: this is the context the module's configuration builds on
 * its own, with the key pinned to an empty value. Filling an invoice in by
 * hand is tested with the corrections, in RevisionFacturasIT.
 */
@Testcontainers
@SpringBootTest(classes = [InvoicesTestApplication::class])
class ModoManualIT {

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

    @Autowired lateinit var reconocedor: ReconocedorFacturas
    @Autowired lateinit var subida: SubidaFacturas
    @Autowired lateinit var lecturas: FacturaLecturas

    @Test
    fun `without a key the real client is never built`() {
        assertIs<ReconocedorDeshabilitado>(reconocedor)
    }

    @Test
    fun `an upload is stored and stays pending, and a retry is refused`() {
        val id = subida.subir(listOf(FicheroSubido(null, Muestras.pdf())), UUID.randomUUID()).single().facturaId!!
        Thread.sleep(500)
        assertEquals(EstadoFactura.PENDIENTE_RECONOCER, lecturas.estado(id))
        assertFailsWith<ReconocimientoNoDisponibleException> { subida.reintentar(id) }
    }
}
