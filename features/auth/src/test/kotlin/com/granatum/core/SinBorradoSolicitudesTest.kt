package com.granatum.core

import com.granatum.core.infrastructure.database.repositories.SolicitudRegistroRepository
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * A sign-up request is resolved, never removed: its row is the record of what
 * happened (feature 005, FR-027). Not calling `delete` is not enough - if the
 * interface offered it, a future change could use it without anything noticing,
 * which is why the check is on the interface itself.
 */
class SinBorradoSolicitudesTest {

    @Test
    fun `the sign-up request repository offers no way to delete`() {
        val metodos = SolicitudRegistroRepository::class.java.methods.map { it.name }
        assertEquals(
            emptyList(),
            metodos.filter { it.startsWith("delete") || it.startsWith("remove") },
            "SolicitudRegistroRepository must not expose deletion"
        )
    }
}
