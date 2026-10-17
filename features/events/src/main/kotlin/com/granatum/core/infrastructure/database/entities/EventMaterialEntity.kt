package com.granatum.core.infrastructure.database.entities

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

/**
 * A material an event takes. Refers to the event by id rather than by association: lines are
 * read in batches for several events, and changed one at a time, never through the event.
 */
@Entity
@Table(name = "event_materials")
class EventMaterialEntity(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(nullable = false, updatable = false)
    val eventId: UUID,

    @Column(nullable = false, updatable = false)
    val materialId: UUID,

    @Column(nullable = false)
    var quantity: Int,

    @Column(nullable = false, length = 500)
    val description: String,

    @Column(nullable = false, length = 100)
    val category: String
)
