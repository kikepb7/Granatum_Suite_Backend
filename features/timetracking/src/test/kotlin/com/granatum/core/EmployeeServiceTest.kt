package com.granatum.core

import com.granatum.core.domain.exception.DuplicateDocumentException
import com.granatum.core.domain.exception.EmployeeNotFoundException
import com.granatum.core.domain.type.ContractType
import com.granatum.core.infrastructure.database.entities.EmployeeEntity
import com.granatum.core.infrastructure.database.repositories.EmployeeRepository
import com.granatum.core.service.EmployeeService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.time.LocalDate
import java.util.Optional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EmployeeServiceTest {

    private val repository = mockk<EmployeeRepository>()
    private val service = EmployeeService(repository)

    @Test
    fun `a duplicate document is rejected before touching the database`() {
        every { repository.existsByIdentityDocument("12345678Z") } returns true

        assertFailsWith<DuplicateDocumentException> {
            service.create(
                "Persona", "Gómez", "12345678Z", "Florista",
                ContractType.FULL_TIME, LocalDate.parse("2026-10-01")
            )
        }
    }

    /**
     * The document is normalised **before** the uniqueness check, which is what
     * stops FR-029 being bypassed with a hyphen. Asserted by checking the value
     * the check was made with, not just the outcome.
     */
    @Test
    fun `the document is normalised before the uniqueness check`() {
        every { repository.existsByIdentityDocument("12345678Z") } returns true

        assertFailsWith<DuplicateDocumentException> {
            service.create(
                "Persona", "Gómez", " 12345678-z ", "Florista",
                ContractType.FULL_TIME, LocalDate.parse("2026-10-01")
            )
        }
    }

    @Test
    fun `the stored document is the normalised form`() {
        every { repository.existsByIdentityDocument(any()) } returns false
        val saved = slot<EmployeeEntity>()
        every { repository.save(capture(saved)) } answers { saved.captured }

        service.create(
            "Persona", "Gómez", "12345678-z", "Florista",
            ContractType.FULL_TIME, LocalDate.parse("2026-10-01")
        )

        assertEquals("12345678Z", saved.captured.identityDocument)
    }

    @Test
    fun `an unknown employee cannot be updated or deactivated`() {
        val id = UUID.randomUUID()
        every { repository.findById(id) } returns Optional.empty()

        assertFailsWith<EmployeeNotFoundException> {
            service.update(
                id, "Nombre", "Gómez", "Puesto",
                ContractType.PART_TIME, LocalDate.parse("2026-10-01")
            )
        }
        assertFailsWith<EmployeeNotFoundException> { service.setActive(id, false) }
    }

    /** FR-030: there is no delete to call, so the service cannot offer one. */
    @Test
    fun `the service exposes no deletion`() {
        val methodNames = EmployeeService::class.java.methods.map { it.name }

        assertEquals(
            emptyList(),
            methodNames.filter { it.startsWith("delete") || it.startsWith("borrar") },
            "removing a person would destroy four years of working-time history"
        )
    }
}
