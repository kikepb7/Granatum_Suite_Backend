package com.granatum.core

import com.granatum.core.domain.contract.MaterialUsage
import com.granatum.core.domain.model.EventData
import com.granatum.core.domain.type.EventStatus
import com.granatum.core.infrastructure.database.entities.EventEntity
import com.granatum.core.infrastructure.database.repositories.EventMaterialRepository
import com.granatum.core.infrastructure.database.repositories.EventRepository
import com.granatum.core.scheduling.EventFinishingJob
import com.granatum.core.service.EventService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Feature 011 against a real Postgres: V27 and V34 apply, the JPA mapping matches them, and an
 * event's whole life - material in, material out, finished by the job - leaves stock and rows right.
 */
@Testcontainers
@SpringBootTest(classes = [EventsTestApplication::class])
class EventsIT : BaseEventsIT() {

    @Autowired lateinit var sut: EventService
    @Autowired lateinit var job: EventFinishingJob
    @Autowired lateinit var materialUsage: MaterialUsage
    @Autowired lateinit var eventRepository: EventRepository
    @Autowired lateinit var lineRepository: EventMaterialRepository
    @Autowired lateinit var jdbc: JdbcTemplate

    private val user = UUID.randomUUID()

    private fun newEvent(name: String = "Evento 1") =
        sut.create(user, EventData(name, "Boda de Juan y María", sut.today().plusDays(3)))

    @Test
    fun `When material is assigned and removed then the stock and the lines follow`() {
        val event = newEvent()
        val material = inventory.material(quantity = 8)

        sut.assignMaterial(event.id, material, 5, user)
        assertEquals(expected = 3, actual = inventory.quantity(material))
        assertTrue(materialUsage.isInUse(material))

        val actual = sut.get(event.id)
        assertEquals(expected = listOf(5), actual = actual.materials.map { it.quantity })
        assertEquals(expected = "Jarrones", actual = actual.materials.single().category)

        sut.removeMaterial(event.id, material, user)
        assertEquals(expected = 8, actual = inventory.quantity(material))
        assertFalse(materialUsage.isInUse(material))
        assertTrue(lineRepository.findAllByEventId(event.id).isEmpty())
    }

    @Test
    fun `When the job runs then past events finish, their material returns and nothing moves twice`() {
        val material = inventory.material(quantity = 10)
        val past = eventRepository.saveAndFlush(
            EventEntity(name = "Ayer", description = null, date = sut.today().minusDays(1), createdBy = user)
        )
        val future = newEvent("Mañana")
        sut.assignMaterial(past.id, material, 4, user)
        sut.assignMaterial(future.id, material, 3, user)
        assertEquals(expected = 3, actual = inventory.quantity(material))

        job.finishOverdue()
        job.finishOverdue()

        val actual = sut.get(past.id)
        assertEquals(expected = EventStatus.FINISHED, actual = actual.status)
        assertNotNull(actual.finishedAt)
        assertEquals(expected = 7, actual = inventory.quantity(material))
        assertEquals(expected = EventStatus.SCHEDULED, actual = sut.get(future.id).status)
        assertTrue(materialUsage.isInUse(material), "still out at tomorrow's event")
    }

    @Test
    fun `When events are listed then scheduled come soonest first and finished ones apart`() {
        val later = sut.create(user, EventData("Tarde", null, sut.today().plusDays(30)))
        val soon = sut.create(user, EventData("Pronto", null, sut.today().plusDays(1)))
        sut.finish(later.id, user)

        val scheduled = sut.list(EventStatus.SCHEDULED).map { it.id }
        val finished = sut.list(EventStatus.FINISHED).map { it.id }

        assertTrue(soon.id in scheduled && later.id !in scheduled)
        assertTrue(later.id in finished)
    }

    @Test
    fun `When an event is deleted then its lines go with it and the stock returns`() {
        val event = newEvent()
        val material = inventory.material(quantity = 2)
        sut.assignMaterial(event.id, material, 2, user)

        sut.delete(event.id, user)

        assertEquals(expected = 2, actual = inventory.quantity(material))
        assertEquals(
            expected = 0,
            actual = jdbc.queryForObject("SELECT count(*) FROM event_materials WHERE event_id = ?", Int::class.java, event.id)
        )
    }

    @Test
    fun `When the schema is checked then the database refuses impossible rows`() {
        val event = newEvent()

        val withoutFinishedAt = runCatching {
            jdbc.update("UPDATE events SET status = 'FINISHED' WHERE id = ?", event.id)
        }
        val oldStatus = runCatching {
            jdbc.update("UPDATE events SET status = 'PROGRAMADO' WHERE id = ?", event.id)
        }
        val zeroQuantity = runCatching {
            jdbc.update(
                "INSERT INTO event_materials (id, event_id, material_id, quantity, description, category) VALUES (?, ?, ?, 0, 'x', 'y')",
                UUID.randomUUID(), event.id, UUID.randomUUID()
            )
        }

        assertTrue(withoutFinishedAt.isFailure, "FINISHED without finished_at")
        assertTrue(oldStatus.isFailure, "the Spanish status value is no longer allowed")
        assertTrue(zeroQuantity.isFailure, "a line of zero units")
    }
}
