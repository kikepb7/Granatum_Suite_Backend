package com.granatum.core.domain.model

import com.granatum.core.domain.type.EntityId
import com.granatum.core.domain.type.EventStatus
import java.time.Instant
import java.time.LocalDate

data class EventModel(
    val id: EntityId,
    val name: String,
    val description: String?,
    val date: LocalDate,
    val status: EventStatus,
    val materials: List<EventMaterialModel>,
    val createdBy: EntityId,
    val finishedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant
)

/**
 * A material an event takes. [category] and [description] are those it had when assigned, so a
 * finished event still says what it took if the material is later deleted; [warehouse], [section]
 * and [imageUrl] are the inventory's current ones, `null` when it no longer exists.
 */
data class EventMaterialModel(
    val materialId: EntityId,
    val category: String,
    val description: String,
    val quantity: Int,
    val warehouse: String? = null,
    val section: Int? = null,
    val imageUrl: String? = null
)

/** What a person writes when creating or editing an event. */
data class EventData(
    val name: String,
    val description: String?,
    val date: LocalDate
)
