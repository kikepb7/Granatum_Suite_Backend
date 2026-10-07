package com.granatum.core

import com.granatum.core.domain.type.EstadoMaterial
import com.granatum.core.domain.type.UnidadMedida
import com.granatum.core.infrastructure.database.entities.CategoriaEntity
import com.granatum.core.infrastructure.database.entities.MaterialEntity
import com.granatum.core.infrastructure.database.entities.TamanoEmbeddable
import com.granatum.core.infrastructure.database.repositories.CategoriaRepository
import com.granatum.core.infrastructure.database.repositories.MaterialRepository
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import kotlin.test.assertEquals

/**
 * Runs the real Flyway migrations against a real Postgres container, then
 * exercises the JPA mapping end to end. Confirms the entities and the SQL
 * migrations agree (Hibernate `ddl-auto: validate` would otherwise only
 * catch this at application boot).
 *
 * `@Transactional` keeps one persistence context open for the whole test (and
 * rolls it back afterwards), so the lazy `fotos` @ElementCollection can still
 * be read after the reload. The explicit flush + clear below is what makes
 * that reload hit the database instead of the first-level cache.
 */
@Testcontainers
@Transactional
@SpringBootTest(classes = [InventoryTestApplication::class])
class MaterialRepositoryIT {

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

    @Autowired
    lateinit var categoriaRepository: CategoriaRepository

    @Autowired
    lateinit var materialRepository: MaterialRepository

    @Autowired
    lateinit var entityManager: EntityManager

    @Test
    fun `persists and reloads a material with its categoria and tamano`() {
        val categoria = categoriaRepository.save(CategoriaEntity(nombre = "Cilindros", descripcion = "Cilindros de cristal"))

        val material = materialRepository.save(
            MaterialEntity(
                nombre = "Cilindro alto 40cm",
                categoria = categoria,
                cantidadDisponible = 8,
                cantidadTotal = 10,
                tamano = TamanoEmbeddable(
                    alto = BigDecimal("40.00"),
                    ancho = BigDecimal("15.00"),
                    diametro = BigDecimal("15.00"),
                    unidadMedida = UnidadMedida.CM
                ),
                color = "Transparente",
                materialFisico = "Cristal",
                estado = EstadoMaterial.NUEVO,
                ubicacion = "Almacen A - Estante 3",
                precioUnitario = BigDecimal("12.50"),
                proveedor = "Cristaleria Sur",
                fotos = mutableListOf("https://example.com/foto1.jpg")
            )
        )

        entityManager.flush()
        entityManager.clear()

        val reloaded = materialRepository.findById(material.id).orElseThrow()

        assertEquals("Cilindro alto 40cm", reloaded.nombre)
        assertEquals(categoria.id, reloaded.categoria.id)
        assertEquals(BigDecimal("40.00"), reloaded.tamano.alto)
        assertEquals(1, reloaded.fotos.size)
        assertEquals(EstadoMaterial.NUEVO, reloaded.estado)
    }
}
