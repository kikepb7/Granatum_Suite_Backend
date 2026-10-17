package com.granatum.core

import com.granatum.core.domain.contract.InsufficientStockException
import com.granatum.core.domain.exception.DateInPastException
import com.granatum.core.domain.exception.EventFinishedException
import com.granatum.core.domain.model.EventData
import com.granatum.core.domain.model.EventMaterialModel
import com.granatum.core.domain.type.EventStatus
import com.granatum.core.infrastructure.database.entities.EventEntity
import com.granatum.core.infrastructure.database.entities.EventMaterialEntity
import com.granatum.core.infrastructure.database.repositories.EventMaterialRepository
import com.granatum.core.infrastructure.database.repositories.EventRepository
import com.granatum.core.service.EventService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.transaction.support.TransactionOperations
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Feature 011: the rules of [EventService], without a database (principle V). */
class EventServiceTest {

    private val eventRepository = mockk<EventRepository>()
    private val lineRepository = mockk<EventMaterialRepository>(relaxed = true)
    private val inventory = MaterialInventoryFake()

    /** 2027-06-13 10:00 in Madrid. */
    private val clock = Clock.fixed(Instant.parse("2027-06-13T08:00:00Z"), ZoneOffset.UTC)
    private val today = LocalDate.of(2027, 6, 13)
    private val user = UUID.randomUUID()

    private val sut = EventService(eventRepository, lineRepository, inventory, TransactionOperations.withoutTransaction(), clock)

    private val lines = mutableListOf<EventMaterialEntity>()

    private fun event(status: EventStatus = EventStatus.SCHEDULED, date: LocalDate = today) =
        EventEntity(name = "Evento 1", description = "Boda de Juan y María", date = date, status = status, createdBy = user)
            .also { e ->
                every { eventRepository.findByIdForUpdate(e.id) } returns Optional.of(e)
                every { eventRepository.findById(e.id) } returns Optional.of(e)
                every { eventRepository.saveAndFlush(e) } returns e
                every { lineRepository.findAllByEventId(e.id) } answers { lines.toList() }
                every { lineRepository.save(any()) } answers { firstArg<EventMaterialEntity>().also { lines += it } }
                every { lineRepository.delete(any()) } answers { lines -= firstArg<EventMaterialEntity>() }
            }

    @Test
    fun `When an event is created for today then it is scheduled with the trimmed data`() {
        val saved = slot<EventEntity>()
        every { eventRepository.saveAndFlush(capture(saved)) } answers { saved.captured }

        val actual = sut.create(user, EventData("  Evento 1 ", "  Boda de Juan y María ", today))

        assertEquals(expected = "Evento 1" to "Boda de Juan y María", actual = actual.name to actual.description)
        assertEquals(expected = EventStatus.SCHEDULED, actual = actual.status)
        assertEquals(expected = user, actual = actual.createdBy)
    }

    @Test
    fun `When the date has already gone then the event is not created`() {
        assertFailsWith<DateInPastException> { sut.create(user, EventData("Evento 1", null, today.minusDays(1))) }

        verify(exactly = 0) { eventRepository.saveAndFlush(any()) }
    }

    @Test
    fun `When material is assigned then it is taken out of the inventory with the event in the reason`() {
        val event = event()
        val material = inventory.material(quantity = 8)
        val expected = listOf(EventMaterialModel(material, "Jarrones", "Jarrón cilíndrico", 5, "A", 2, null))

        val actual = sut.assignMaterial(event.id, material, 5, user)

        assertEquals(expected = expected, actual = actual.materials)
        assertEquals(expected = 3, actual = inventory.quantity(material))
        assertEquals(expected = "Reservado para el evento «Evento 1» del 13/06/2027", actual = inventory.withdrawals.single().reason)
    }

    @Test
    fun `When the quantity changes then only the difference moves`() {
        val event = event()
        val material = inventory.material(quantity = 8)
        sut.assignMaterial(event.id, material, 5, user)

        sut.assignMaterial(event.id, material, 7, user)
        assertEquals(expected = 1, actual = inventory.quantity(material))

        val actual = sut.assignMaterial(event.id, material, 2, user)
        assertEquals(expected = 6, actual = inventory.quantity(material))
        assertEquals(expected = 2, actual = actual.materials.single().quantity)
        assertEquals(expected = listOf(5, 2), actual = inventory.withdrawals.map { it.quantity })
        assertEquals(expected = listOf(5), actual = inventory.returns.map { it.quantity })
    }

    @Test
    fun `When there is not enough stock then nothing is assigned`() {
        val event = event()
        val material = inventory.material(quantity = 3)

        val actual = assertFailsWith<InsufficientStockException> { sut.assignMaterial(event.id, material, 4, user) }

        assertEquals(expected = 3, actual = actual.available)
        assertTrue(lines.isEmpty())
        assertEquals(expected = 3, actual = inventory.quantity(material))
    }

    @Test
    fun `When a material is removed then all its units go back`() {
        val event = event()
        val material = inventory.material(quantity = 8)
        sut.assignMaterial(event.id, material, 5, user)

        val actual = sut.removeMaterial(event.id, material, user)

        assertEquals(expected = emptyList(), actual = actual.materials)
        assertEquals(expected = 8, actual = inventory.quantity(material))
    }

    @Test
    fun `When an event is finished by hand then its material goes back on behalf of who pressed`() {
        val event = event()
        val material = inventory.material(quantity = 8)
        sut.assignMaterial(event.id, material, 5, user)
        val presser = UUID.randomUUID()

        val actual = sut.finish(event.id, presser)

        assertEquals(expected = EventStatus.FINISHED, actual = actual.status)
        assertEquals(expected = 8, actual = inventory.quantity(material))
        assertEquals(expected = presser, actual = inventory.returns.single().userId)
        assertEquals(expected = 5, actual = actual.materials.single().quantity, message = "a finished event keeps what it took")
    }

    @Test
    fun `When the job finishes an event then the history is written for its creator and says so`() {
        val event = event(date = today.minusDays(1))
        val material = inventory.material(quantity = 8)
        sut.assignMaterial(event.id, material, 5, user)
        every { eventRepository.idsWithStatusBefore(EventStatus.SCHEDULED, today) } returns listOf(event.id)

        val actual = sut.finishOverdue()

        assertEquals(expected = 1, actual = actual)
        val returned = inventory.returns.single()
        assertEquals(expected = user, actual = returned.userId)
        assertEquals(expected = "Devuelto automáticamente al finalizar el evento «Evento 1»", actual = returned.reason)
    }

    @Test
    fun `When a finished event is finished again then nothing moves`() {
        val event = event(status = EventStatus.FINISHED)

        sut.finish(event.id, user)

        assertTrue(inventory.returns.isEmpty())
    }

    @Test
    fun `When a finished event is edited or given material then it is refused`() {
        val event = event(status = EventStatus.FINISHED)
        val material = inventory.material(quantity = 8)

        assertFailsWith<EventFinishedException> { sut.edit(event.id, EventData("Otro", null, today)) }
        assertFailsWith<EventFinishedException> { sut.assignMaterial(event.id, material, 1, user) }
        assertEquals(expected = 8, actual = inventory.quantity(material))
    }

    @Test
    fun `When a scheduled event is deleted then its material goes back`() {
        val event = event()
        val material = inventory.material(quantity = 8)
        sut.assignMaterial(event.id, material, 5, user)
        every { eventRepository.delete(event) } returns Unit

        sut.delete(event.id, user)

        assertEquals(expected = 8, actual = inventory.quantity(material))
        verify { eventRepository.delete(event) }
    }

    @Test
    fun `When a returned material no longer exists then finishing still succeeds`() {
        val event = event()
        val material = inventory.material(quantity = 8)
        sut.assignMaterial(event.id, material, 5, user)
        inventory.delete(material)

        val actual = sut.finish(event.id, user)

        assertEquals(expected = EventStatus.FINISHED, actual = actual.status)
        assertEquals(expected = null, actual = actual.materials.single().warehouse)
    }
}
