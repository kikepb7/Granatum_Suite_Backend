package com.granatum.core

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * V28 on a database that already has data: the rows written under the Spanish schema come out
 * under the English one, nothing lost, with the stored change types translated and row level
 * security still enabled. Plain Flyway, no Spring: it needs to stop at V26 first.
 */
@Testcontainers
class InventoryEnglishSchemaMigrationIT {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    private fun flyway(target: String?) = Flyway.configure()
        .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        .locations("classpath:db/migration")
        .apply { if (target != null) target(target) }
        .load()

    @Test
    fun `V28 keeps every row and translates the stored change types`() {
        flyway(target = "26").migrate()
        val jdbc = JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))

        val category = UUID.randomUUID()
        val material = UUID.randomUUID()
        val user = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO categorias (id, nombre, descripcion, created_at, updated_at) VALUES (?, 'Jarrones', 'Cristal', now(), now())",
            category
        )
        jdbc.update(
            """
            INSERT INTO materiales (id, categoria_id, descripcion, alto, ancho, cantidad, almacen, seccion, imagen_ruta,
                                    fecha_alta, fecha_ultima_modificacion)
            VALUES (?, ?, 'Jarrón', 40, 12, 7, 'B', 3, 'materiales/x/y.jpg', now(), now())
            """.trimIndent(),
            material, category
        )
        val oldTypes = listOf("CANTIDAD", "ESTADO", "UBICACION", "PRECIO_UNITARIO", "PROVEEDOR", "OTRO")
        for (type in oldTypes) {
            jdbc.update(
                """
                INSERT INTO historial_material (id, material_id, usuario_id, tipo_cambio, valor_anterior, valor_nuevo, motivo, fecha)
                VALUES (?, ?, ?, ?, '1', '2', 'Motivo', now())
                """.trimIndent(),
                UUID.randomUUID(), material, user, type
            )
        }

        flyway(target = null).migrate()

        assertEquals(
            mapOf<String, Any?>(
                "name" to "Jarrones", "description" to "Cristal"
            ),
            jdbc.queryForMap("SELECT name, description FROM categories WHERE id = ?", category)
        )
        val row = jdbc.queryForMap(
            "SELECT category_id, description, quantity, warehouse, section, image_path FROM materials WHERE id = ?",
            material
        )
        assertEquals(category, row["category_id"])
        assertEquals("Jarrón", row["description"])
        assertEquals(7, row["quantity"])
        assertEquals("B", row["warehouse"])
        assertEquals(3, (row["section"] as Number).toInt())
        assertEquals("materiales/x/y.jpg", row["image_path"], "stored image paths are opaque and stay as they were")
        assertEquals(
            setOf("QUANTITY", "STATUS", "LOCATION", "UNIT_PRICE", "SUPPLIER", "OTHER"),
            jdbc.queryForList(
                "SELECT change_type FROM material_history WHERE material_id = ? AND user_id = ? AND reason = 'Motivo'",
                String::class.java, material, user
            ).toSet()
        )
        val withRls = jdbc.queryForList(
            """
            SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
             WHERE n.nspname = 'public' AND c.relrowsecurity
            """.trimIndent(),
            String::class.java
        ).toSet()
        assertTrue(withRls.containsAll(setOf("categories", "materials", "material_history")), "$withRls")
    }
}
