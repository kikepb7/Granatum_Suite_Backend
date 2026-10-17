package com.granatum.core

import com.granatum.core.infrastructure.database.repositories.AbsenceRepository
import com.granatum.core.infrastructure.database.repositories.VacationEntitlementRepository
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** FR-020: an absence is cancelled or rejected, never removed - and the interfaces cannot do it. */
class AbsenceNoDeletionTest {

    @Test
    fun `no absence repository offers a way to delete`() {
        listOf(AbsenceRepository::class.java, VacationEntitlementRepository::class.java).forEach { repo ->
            assertEquals(
                emptyList(),
                repo.methods.map { it.name }.filter { it.startsWith("delete") || it.startsWith("remove") },
                "${repo.simpleName} must not expose deletion"
            )
        }
    }
}
