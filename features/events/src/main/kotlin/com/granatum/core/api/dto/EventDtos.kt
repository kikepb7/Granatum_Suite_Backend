package com.granatum.core.api.dto

import com.granatum.core.domain.type.EventStatus
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// Names carry "Event"/"Available" on purpose: every module shares the com.granatum.core.api.dto
// package, and inventory already has its own Material*/Location* DTOs (SinColisionDeClasesIT).

/** Creating or editing an event: the whole of what a person writes. */
data class EventRequest(
    @field:NotBlank
    @field:Size(max = 100)
    val name: String?,

    @field:Size(max = 500)
    val description: String?,

    @field:NotNull
    val date: LocalDate?
)

/** How many units of a material the event takes; to take none, remove it. */
data class EventMaterialQuantityRequest(
    @field:NotNull
    @field:Min(1)
    val quantity: Int?
)

data class EventDto(
    val id: UUID,
    val name: String,
    val description: String?,
    val date: LocalDate,
    val status: EventStatus,
    val materials: List<EventMaterialDto>,
    val createdBy: UUID,
    val finishedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant
)

/**
 * `category` and `description` as they were when assigned; `location` and `imageUrl` as the
 * inventory has them now, `null` when the material no longer exists.
 */
data class EventMaterialDto(
    val materialId: UUID,
    val category: String,
    val description: String,
    val quantity: Int,
    val location: EventLocationDto?,
    val imageUrl: String?
)

/** A material that can be taken to an event, with its free units (FR-012). */
data class AvailableMaterialDto(
    val id: UUID,
    val category: String,
    val description: String,
    val quantity: Int,
    val location: EventLocationDto,
    val imageUrl: String?
)

data class EventLocationDto(
    val warehouse: String,
    val section: Int
)
