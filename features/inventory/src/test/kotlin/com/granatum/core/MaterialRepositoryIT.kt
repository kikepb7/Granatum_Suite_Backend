package com.granatum.core

import com.granatum.core.domain.type.Almacen
import com.granatum.core.infrastructure.database.entities.CategoriaEntity
import com.granatum.core.infrastructure.database.entities.DimensionesEmbeddable
import com.granatum.core.infrastructure.database.entities.LocalizacionEmbeddable
import com.granatum.core.infrastructure.database.entities.MaterialEntity
import com.granatum.core.infrastructure.database.repositories.CategoriaRepository
import com.granatum.core.infrastructure.database.repositories.MaterialRepository
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs the real Flyway migrations - V26 included - against a real Postgres
 * container, then exercises the JPA mapping end to end. Confirms the entities
 * and the SQL migrations agree (Hibernate `ddl-auto: validate` would otherwise
 * only catch this at application boot).
 *
 * `@Transactional` keeps one persistence context open for the whole test (and
 * rolls it back afterwards). The explicit flush + clear below is what makes
 * the reload hit the database instead of the first-level cache.
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

    @Autowired
    lateinit var jdbc: JdbcTemplate

    private fun material(categoria: CategoriaEntity, almacen: Almacen = Almacen.B, seccion: Int = 4) = MaterialEntity(
        categoria = categoria,
        descripcion = "Cilindro de cristal de 40 cm",
        dimensiones = DimensionesEmbeddable(alto = BigDecimal("40.00"), ancho = BigDecimal("15.50")),
        cantidad = 8,
        localizacion = LocalizacionEmbeddable(almacen = almacen, seccion = seccion)
    )

    @Test
    fun `persists and reloads a material with every field of the new model`() {
        val categoria = categoriaRepository.save(CategoriaEntity(nombre = "Cilindros ${UUID.randomUUID()}"))
        val guardado = materialRepository.save(material(categoria).apply { imagenRuta = "materiales/x/y.jpg" })

        entityManager.flush()
        entityManager.clear()

        val reloaded = materialRepository.findById(guardado.id).orElseThrow()

        assertEquals(categoria.id, reloaded.categoria.id)
        assertEquals("Cilindro de cristal de 40 cm", reloaded.descripcion)
        assertEquals(BigDecimal("40.00"), reloaded.dimensiones.alto)
        assertEquals(BigDecimal("15.50"), reloaded.dimensiones.ancho)
        assertEquals(8, reloaded.cantidad)
        assertEquals(Almacen.B, reloaded.localizacion.almacen)
        assertEquals(4, reloaded.localizacion.seccion)
        assertEquals("materiales/x/y.jpg", reloaded.imagenRuta)
    }

    @Test
    fun `filters by warehouse and by warehouse and section`() {
        val categoria = categoriaRepository.save(CategoriaEntity(nombre = "Filtros ${UUID.randomUUID()}"))
        val a1 = materialRepository.save(material(categoria, Almacen.A, 1))
        val a3 = materialRepository.save(material(categoria, Almacen.A, 3))
        val c2 = materialRepository.save(material(categoria, Almacen.C, 2))
        entityManager.flush()

        val enA = materialRepository.findAllByLocalizacionAlmacen(Almacen.A).map { it.id }
        assertTrue(a1.id in enA && a3.id in enA)
        assertFalse(c2.id in enA)

        assertEquals(
            listOf(a3.id),
            materialRepository.findAllByLocalizacionAlmacenAndLocalizacionSeccion(Almacen.A, 3).map { it.id }
                .filter { it in setOf(a1.id, a3.id, c2.id) }
        )
    }

    @Test
    fun `V26 drops material_fotos and every retired column`() {
        assertNull(jdbc.queryForObject("SELECT to_regclass('public.material_fotos')::text", String::class.java))

        val columnas = jdbc.queryForList(
            "SELECT column_name FROM information_schema.columns WHERE table_name = 'materiales'",
            String::class.java
        ).toSet()
        assertEquals(
            setOf(
                "id", "categoria_id", "descripcion", "alto", "ancho", "cantidad", "almacen", "seccion",
                "imagen_ruta", "fecha_alta", "fecha_ultima_modificacion"
            ),
            columnas
        )
    }

    /**
     * The checks guard the rules against any write path, not only the API's
     * validation (research.md D-006), so they are exercised with plain SQL.
     */
    @Test
    fun `the database rejects an invalid warehouse, section, dimension or quantity`() {
        val categoria = categoriaRepository.save(CategoriaEntity(nombre = "Checks ${UUID.randomUUID()}"))
        entityManager.flush()

        fun insertar(almacen: String, seccion: Int, alto: String, cantidad: Int): Result<Int> = runCatching {
            jdbc.execute("SAVEPOINT intento")
            try {
                jdbc.update(
                    """
                    INSERT INTO materiales (id, categoria_id, descripcion, alto, ancho, cantidad, almacen, seccion,
                                            fecha_alta, fecha_ultima_modificacion)
                    VALUES (?, ?, 'x', CAST(? AS NUMERIC), 10, ?, ?, ?, now(), now())
                    """.trimIndent(),
                    UUID.randomUUID(), categoria.id, alto, cantidad, almacen, seccion
                )
            } finally {
                jdbc.execute("ROLLBACK TO SAVEPOINT intento")
            }
        }

        assertTrue(insertar("A", 1, "1", 0).isSuccess, "a valid row is accepted")
        assertTrue(insertar("D", 1, "1", 0).isFailure, "warehouse D")
        assertTrue(insertar("A", 0, "1", 0).isFailure, "section 0")
        assertTrue(insertar("A", 6, "1", 0).isFailure, "section 6")
        assertTrue(insertar("A", 1, "0", 0).isFailure, "height 0")
        assertTrue(insertar("A", 1, "1", -1).isFailure, "quantity -1")
    }
}
