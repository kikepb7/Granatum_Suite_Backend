package com.granatum.core.service

import com.granatum.core.domain.contract.MaterialInventory
import com.granatum.core.domain.contract.MaterialStock
import com.granatum.core.domain.exception.DateInPastException
import com.granatum.core.domain.exception.EventFinishedException
import com.granatum.core.domain.exception.EventNotFoundException
import com.granatum.core.domain.model.EventData
import com.granatum.core.domain.model.EventModel
import com.granatum.core.domain.type.EntityId
import com.granatum.core.domain.type.EventStatus
import com.granatum.core.infrastructure.database.entities.EventEntity
import com.granatum.core.infrastructure.database.entities.EventMaterialEntity
import com.granatum.core.infrastructure.database.mappers.toModel
import com.granatum.core.infrastructure.database.repositories.EventMaterialRepository
import com.granatum.core.infrastructure.database.repositories.EventRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionOperations
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Events and the materials they take (feature 011).
 *
 * Every change that moves stock runs in one transaction with the event's row locked: the event
 * line and the units taken from or returned to the inventory are saved together, or neither
 * (research.md D-003). Reading what the inventory says about each material - its location and
 * signed image - happens after the transaction, so signing images never holds a connection.
 */
@Service
class EventService(
    private val eventRepository: EventRepository,
    private val lineRepository: EventMaterialRepository,
    private val inventory: MaterialInventory,
    private val transactions: TransactionOperations,
    private val clock: Clock = Clock.systemUTC()
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Scheduled events soonest first; finished ones most recent first; without [status], both. */
    fun list(status: EventStatus? = null): List<EventModel> {
        val entities = when (status) {
            EventStatus.SCHEDULED -> eventRepository.findAllByStatusOrderByDateAscNameAsc(EventStatus.SCHEDULED)
            EventStatus.FINISHED -> eventRepository.findAllByStatusOrderByDateDescNameAsc(EventStatus.FINISHED)
            null -> eventRepository.findAllByStatusOrderByDateAscNameAsc(EventStatus.SCHEDULED) +
                eventRepository.findAllByStatusOrderByDateDescNameAsc(EventStatus.FINISHED)
        }
        val lines = lineRepository.findAllByEventIdIn(entities.map { it.id }).groupBy { it.eventId }
        return withInventory(entities.map { it.toModel(lines[it.id].orEmpty()) })
    }

    fun get(id: EntityId): EventModel {
        val event = eventRepository.findById(id).orElseThrow { EventNotFoundException(id) }
        return withInventory(event.toModel(lineRepository.findAllByEventId(id)))
    }

    fun create(userId: EntityId, data: EventData): EventModel {
        checkDate(data.date)
        val event = eventRepository.saveAndFlush(
            EventEntity(
                name = data.name.trim(),
                description = data.description?.trim()?.ifEmpty { null },
                date = data.date,
                createdBy = userId
            )
        )
        return event.toModel(emptyList())
    }

    fun edit(id: EntityId, data: EventData): EventModel = write(id) { event, lines ->
        checkDate(data.date)
        event.name = data.name.trim()
        event.description = data.description?.trim()?.ifEmpty { null }
        event.date = data.date
        lines
    }

    /**
     * Sets how many units of a material the event takes, moving only the difference: more takes
     * them out of the inventory, fewer puts them back (research.md D-004). When there are not
     * enough units the inventory refuses and nothing changes (FR-005).
     */
    fun assignMaterial(id: EntityId, materialId: EntityId, quantity: Int, userId: EntityId): EventModel =
        write(id) { event, lines ->
            require(quantity > 0) { "La cantidad debe ser positiva" }
            val line = lines.firstOrNull { it.materialId == materialId }
            val difference = quantity - (line?.quantity ?: 0)
            when {
                difference > 0 -> {
                    val material = inventory.withdraw(materialId, difference, userId, reservationReason(event))
                    if (line == null) {
                        return@write lines + lineRepository.save(newLine(event, material, quantity))
                    }
                    line.quantity = quantity
                }
                difference < 0 -> {
                    inventory.putBack(materialId, -difference, userId, "Devuelto del evento «${event.name}»")
                    line!!.quantity = quantity
                }
            }
            lines
        }

    /** Puts every unit of the material back. Removing one the event does not take changes nothing. */
    fun removeMaterial(id: EntityId, materialId: EntityId, userId: EntityId): EventModel =
        write(id) { event, lines ->
            val line = lines.firstOrNull { it.materialId == materialId } ?: return@write lines
            inventory.putBack(materialId, line.quantity, userId, "Devuelto del evento «${event.name}»")
            lineRepository.delete(line)
            lines - line
        }

    /**
     * Finishes the event and puts all its material back (FR-007, FR-008). [userId] is who
     * pressed the button; `null` when the daily job does it, and then the history is written on
     * behalf of whoever created the event (research.md D-006). A finished event is returned as is.
     */
    fun finish(id: EntityId, userId: EntityId?): EventModel {
        val result = transactions.execute {
            val event = eventRepository.findByIdForUpdate(id).orElseThrow { EventNotFoundException(id) }
            val lines = lineRepository.findAllByEventId(id)
            if (event.status == EventStatus.SCHEDULED) {
                val reason = if (userId == null) {
                    "Devuelto automáticamente al finalizar el evento «${event.name}»"
                } else {
                    "Devuelto al finalizar el evento «${event.name}»"
                }
                lines.forEach { inventory.putBack(it.materialId, it.quantity, userId ?: event.createdBy, reason) }
                event.status = EventStatus.FINISHED
                event.finishedAt = Instant.now(clock)
                eventRepository.saveAndFlush(event)
            }
            event.toModel(lines)
        }!!
        return withInventory(result)
    }

    /** A scheduled event puts its material back first (FR-010); a finished one has nothing out. */
    fun delete(id: EntityId, userId: EntityId) {
        transactions.executeWithoutResult {
            val event = eventRepository.findByIdForUpdate(id).orElseThrow { EventNotFoundException(id) }
            val lines = lineRepository.findAllByEventId(id)
            if (event.status == EventStatus.SCHEDULED) {
                lines.forEach { inventory.putBack(it.materialId, it.quantity, userId, "Devuelto al borrar el evento «${event.name}»") }
            }
            lineRepository.deleteAll(lines)
            eventRepository.delete(event)
        }
    }

    /** What can be taken to an event, for a role that has no access to the inventory (FR-012). */
    fun availableMaterials(): List<MaterialStock> =
        inventory.available().sortedWith(compareBy({ it.category.lowercase() }, { it.description.lowercase() }))

    /**
     * Finishes every scheduled event of a day already gone (FR-007), each in its own transaction
     * so that a failure in one does not undo the others. Returns how many it finished.
     */
    fun finishOverdue(): Int {
        val ids = eventRepository.idsWithStatusBefore(EventStatus.SCHEDULED, today())
        return ids.count { id ->
            runCatching { finish(id, userId = null) }
                .onFailure { log.warn("No se pudo finalizar un evento vencido: {}", it.javaClass.simpleName) }
                .isSuccess
        }
    }

    fun today(): LocalDate = LocalDate.now(clock.withZone(ZONE))

    /** Locks the event, refuses a finished one and runs [change] on it and its lines. */
    private fun write(
        id: EntityId,
        change: (EventEntity, List<EventMaterialEntity>) -> List<EventMaterialEntity>
    ): EventModel {
        val result = transactions.execute {
            val event = eventRepository.findByIdForUpdate(id).orElseThrow { EventNotFoundException(id) }
            if (event.status == EventStatus.FINISHED) throw EventFinishedException(id)
            val lines = change(event, lineRepository.findAllByEventId(id))
            // The modification date says when the event last changed, its material included.
            event.updatedAt = Instant.now(clock)
            eventRepository.saveAndFlush(event)
            lineRepository.flush()
            event.toModel(lines)
        }!!
        return withInventory(result)
    }

    private fun checkDate(date: LocalDate) {
        val today = today()
        if (date.isBefore(today)) throw DateInPastException(date, today)
    }

    private fun newLine(event: EventEntity, material: MaterialStock, quantity: Int) = EventMaterialEntity(
        eventId = event.id,
        materialId = material.id,
        quantity = quantity,
        description = material.description.take(DESCRIPTION_MAX),
        category = material.category.take(CATEGORY_MAX)
    )

    private fun reservationReason(event: EventEntity) =
        "Reservado para el evento «${event.name}» del ${event.date.format(DATE_FORMAT)}"

    /** Adds where each material is and its image, as the inventory has them now. */
    private fun withInventory(event: EventModel): EventModel = withInventory(listOf(event)).single()

    private fun withInventory(events: List<EventModel>): List<EventModel> {
        val ids = events.flatMap { e -> e.materials.map { it.materialId } }.toSet()
        if (ids.isEmpty()) return events
        val current = inventory.summarize(ids)
        return events.map { event ->
            event.copy(
                materials = event.materials.map { line ->
                    current[line.materialId]?.let { line.copy(warehouse = it.warehouse, section = it.section, imageUrl = it.imageUrl) } ?: line
                }
            )
        }
    }

    companion object {
        /** "Today" is the civil day in Spain, where the events happen. */
        val ZONE: ZoneId = ZoneId.of("Europe/Madrid")
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        private const val DESCRIPTION_MAX = 500
        private const val CATEGORY_MAX = 100
    }
}
