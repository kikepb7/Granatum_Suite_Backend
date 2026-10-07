package com.granatum.core

import com.granatum.core.domain.exception.DocumentoDuplicadoException
import com.granatum.core.domain.exception.EmpleadoNotFoundException
import com.granatum.core.domain.type.TipoContrato
import com.granatum.core.infrastructure.database.entities.EmpleadoEntity
import com.granatum.core.infrastructure.database.repositories.EmpleadoRepository
import com.granatum.core.service.EmpleadoService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.time.LocalDate
import java.util.Optional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EmpleadoServiceTest {

    private val repository = mockk<EmpleadoRepository>()
    private val service = EmpleadoService(repository)

    @Test
    fun `a duplicate document is rejected before touching the database`() {
        every { repository.existsByDocumentoIdentidad("12345678Z") } returns true

        assertFailsWith<DocumentoDuplicadoException> {
            service.crear(
                "Persona", "12345678Z", "Florista",
                TipoContrato.JORNADA_COMPLETA, LocalDate.parse("2026-10-01")
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
        every { repository.existsByDocumentoIdentidad("12345678Z") } returns true

        assertFailsWith<DocumentoDuplicadoException> {
            service.crear(
                "Persona", " 12345678-z ", "Florista",
                TipoContrato.JORNADA_COMPLETA, LocalDate.parse("2026-10-01")
            )
        }
    }

    @Test
    fun `the stored document is the normalised form`() {
        every { repository.existsByDocumentoIdentidad(any()) } returns false
        val guardado = slot<EmpleadoEntity>()
        every { repository.save(capture(guardado)) } answers { guardado.captured }

        service.crear(
            "Persona", "12345678-z", "Florista",
            TipoContrato.JORNADA_COMPLETA, LocalDate.parse("2026-10-01")
        )

        assertEquals("12345678Z", guardado.captured.documentoIdentidad)
    }

    @Test
    fun `an unknown employee cannot be updated or deactivated`() {
        val id = UUID.randomUUID()
        every { repository.findById(id) } returns Optional.empty()

        assertFailsWith<EmpleadoNotFoundException> {
            service.actualizar(
                id, "Nombre", "Puesto",
                TipoContrato.PARCIAL, LocalDate.parse("2026-10-01")
            )
        }
        assertFailsWith<EmpleadoNotFoundException> { service.cambiarActivo(id, false) }
    }

    /** FR-030: there is no delete to call, so the service cannot offer one. */
    @Test
    fun `the service exposes no deletion`() {
        val metodos = EmpleadoService::class.java.methods.map { it.name }

        assertEquals(
            emptyList(),
            metodos.filter { it.startsWith("delete") || it.startsWith("borrar") },
            "removing a person would destroy four years of working-time history"
        )
    }
}
