package com.granatum.core

import com.granatum.core.infrastructure.database.repositories.HistorialMaterialRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * `historial_material` is append-only, and its repository must not offer a way
 * to break that.
 *
 * Constitution principle III (v2.0.0) requires repositories of append-only
 * tables not to expose mutation at all: not calling `delete` is not enough,
 * because an interface that offers it will eventually be used by accident and
 * nothing would stop it. This was the debt the amendment declared, and this
 * test is what keeps it closed.
 */
class HistorialAppendOnlyTest {

    @Test
    fun `the audit repository exposes no deletion`() {
        val metodos = HistorialMaterialRepository::class.java.methods.map { it.name }

        assertEquals(
            emptyList(),
            metodos.filter { it.startsWith("delete") || it.startsWith("remove") },
            "an append-only audit table must offer no way to remove a row: $metodos"
        )
    }

    @Test
    fun `it does not extend JpaRepository`() {
        assertFalse(
            org.springframework.data.jpa.repository.JpaRepository::class.java
                .isAssignableFrom(HistorialMaterialRepository::class.java),
            "JpaRepository brings delete and deleteAll in by inheritance"
        )
    }

    @Test
    fun `it offers only an insert and a read`() {
        val metodos = HistorialMaterialRepository::class.java.methods.map { it.name }.toSet()

        assertEquals(
            setOf("save", "findAllByMaterialIdOrderByFechaDesc"),
            metodos,
            "anything else is a route to mutating an audit trail: $metodos"
        )
    }
}
