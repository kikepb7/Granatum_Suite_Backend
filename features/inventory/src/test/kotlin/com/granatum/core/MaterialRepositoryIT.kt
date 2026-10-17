package com.granatum.core

import com.granatum.core.domain.type.Warehouse
import com.granatum.core.infrastructure.database.entities.CategoryEntity
import com.granatum.core.infrastructure.database.entities.MaterialDimensionsEmbeddable
import com.granatum.core.infrastructure.database.entities.MaterialEntity
import com.granatum.core.infrastructure.database.entities.MaterialLocationEmbeddable
import com.granatum.core.infrastructure.database.repositories.CategoryRepository
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
 * Runs the real Flyway migrations - V26 and V28 included - against a real Postgres
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
    lateinit var categoryRepository: CategoryRepository

    @Autowired
    lateinit var materialRepository: MaterialRepository

    @Autowired
    lateinit var entityManager: EntityManager

    @Autowired
    lateinit var jdbc: JdbcTemplate

    private fun material(category: CategoryEntity, warehouse: Warehouse = Warehouse.B, section: Int = 4) = MaterialEntity(
        category = category,
        description = "Cilindro de cristal de 40 cm",
        dimensions = MaterialDimensionsEmbeddable(height = BigDecimal("40.00"), width = BigDecimal("15.50")),
        quantity = 8,
        location = MaterialLocationEmbeddable(warehouse = warehouse, section = section)
    )

    @Test
    fun `persists and reloads a material with every field of the new model`() {
        val category = categoryRepository.save(CategoryEntity(name = "Cilindros ${UUID.randomUUID()}"))
        val saved = materialRepository.save(material(category).apply { imagePath = "materials/x/y.jpg" })

        entityManager.flush()
        entityManager.clear()

        val reloaded = materialRepository.findById(saved.id).orElseThrow()

        assertEquals(category.id, reloaded.category.id)
        assertEquals("Cilindro de cristal de 40 cm", reloaded.description)
        assertEquals(BigDecimal("40.00"), reloaded.dimensions.height)
        assertEquals(BigDecimal("15.50"), reloaded.dimensions.width)
        assertEquals(8, reloaded.quantity)
        assertEquals(Warehouse.B, reloaded.location.warehouse)
        assertEquals(4, reloaded.location.section)
        assertEquals("materials/x/y.jpg", reloaded.imagePath)
    }

    @Test
    fun `filters by warehouse and by warehouse and section`() {
        val category = categoryRepository.save(CategoryEntity(name = "Filtros ${UUID.randomUUID()}"))
        val a1 = materialRepository.save(material(category, Warehouse.A, 1))
        val a3 = materialRepository.save(material(category, Warehouse.A, 3))
        val c2 = materialRepository.save(material(category, Warehouse.C, 2))
        entityManager.flush()

        val inA = materialRepository.findAllByLocationWarehouse(Warehouse.A).map { it.id }
        assertTrue(a1.id in inA && a3.id in inA)
        assertFalse(c2.id in inA)

        assertEquals(
            listOf(a3.id),
            materialRepository.findAllByLocationWarehouseAndLocationSection(Warehouse.A, 3).map { it.id }
                .filter { it in setOf(a1.id, a3.id, c2.id) }
        )
    }

    private fun columnsOf(table: String): Set<String> = jdbc.queryForList(
        "SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' AND table_name = ?",
        String::class.java,
        table
    ).toSet()

    @Test
    fun `V26 dropped material_fotos and V28 left only English tables and columns`() {
        assertNull(jdbc.queryForObject("SELECT to_regclass('public.material_fotos')::text", String::class.java))
        for (old in listOf("categorias", "materiales", "historial_material")) {
            assertNull(jdbc.queryForObject("SELECT to_regclass('public.$old')::text", String::class.java), old)
        }

        assertEquals(
            setOf(
                "id", "category_id", "description", "height", "width", "quantity", "warehouse", "section",
                "image_path", "created_at", "updated_at"
            ),
            columnsOf("materials")
        )
        assertEquals(setOf("id", "name", "description", "created_at", "updated_at"), columnsOf("categories"))
        assertEquals(
            setOf("id", "material_id", "user_id", "change_type", "previous_value", "new_value", "reason", "date"),
            columnsOf("material_history")
        )
    }

    /**
     * The checks guard the rules against any write path, not only the API's
     * validation (research.md D-006), so they are exercised with plain SQL.
     */
    @Test
    fun `the database rejects an invalid warehouse, section, dimension, quantity or change type`() {
        val category = categoryRepository.save(CategoryEntity(name = "Checks ${UUID.randomUUID()}"))
        entityManager.flush()

        fun attempt(statement: () -> Unit): Result<Unit> = runCatching {
            jdbc.execute("SAVEPOINT attempt")
            try {
                statement()
            } finally {
                jdbc.execute("ROLLBACK TO SAVEPOINT attempt")
            }
        }

        fun insert(warehouse: String, section: Int, height: String, quantity: Int): Result<Unit> = attempt {
            jdbc.update(
                """
                INSERT INTO materials (id, category_id, description, height, width, quantity, warehouse, section,
                                       created_at, updated_at)
                VALUES (?, ?, 'x', CAST(? AS NUMERIC), 10, ?, ?, ?, now(), now())
                """.trimIndent(),
                UUID.randomUUID(), category.id, height, quantity, warehouse, section
            )
        }

        assertTrue(insert("A", 1, "1", 0).isSuccess, "a valid row is accepted")
        assertTrue(insert("D", 1, "1", 0).isFailure, "warehouse D")
        assertTrue(insert("A", 0, "1", 0).isFailure, "section 0")
        assertTrue(insert("A", 6, "1", 0).isFailure, "section 6")
        assertTrue(insert("A", 1, "0", 0).isFailure, "height 0")
        assertTrue(insert("A", 1, "1", -1).isFailure, "quantity -1")

        val material = materialRepository.save(material(category))
        entityManager.flush()
        fun history(changeType: String): Result<Unit> = attempt {
            jdbc.update(
                """
                INSERT INTO material_history (id, material_id, user_id, change_type, reason, date)
                VALUES (?, ?, ?, ?, 'x', now())
                """.trimIndent(),
                UUID.randomUUID(), material.id, UUID.randomUUID(), changeType
            )
        }
        assertTrue(history("QUANTITY").isSuccess, "an English change type is accepted")
        assertTrue(history("CANTIDAD").isFailure, "the Spanish change type is gone")
    }
}
