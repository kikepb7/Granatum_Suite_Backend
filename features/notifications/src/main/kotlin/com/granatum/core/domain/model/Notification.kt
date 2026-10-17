package com.granatum.core.domain.model

import com.granatum.core.domain.event.NoticeType
import com.granatum.core.domain.type.EntityId
import java.time.Instant

/** A notice as its recipient reads it (feature 008). */
data class Notification(
    val id: EntityId,
    val type: NoticeType,
    val referenceId: EntityId,
    val createdAt: Instant,
    val readAt: Instant?
) {
    /**
     * Fixed per type and composed when read (research D-003): no stored text,
     * so nothing a person wrote - and no name - can travel in a notice (FR-006).
     */
    val message: String get() = MESSAGES.getValue(type)

    companion object {
        val MESSAGES: Map<NoticeType, String> = mapOf(
            NoticeType.SHIFT_WITHOUT_CLOCK_OUT to "Tienes un fichaje abierto desde hace muchas horas. ¿Has olvidado fichar la salida?",
            NoticeType.SHIFT_INCOMPLETE to "Un fichaje tuyo ha quedado incompleto porque no se registró la salida. Puedes solicitar su corrección.",
            NoticeType.CORRECTION_PENDING to "Hay una solicitud de corrección de fichaje pendiente de resolver.",
            NoticeType.CORRECTION_APPROVED to "Se ha aprobado una corrección de un fichaje tuyo.",
            NoticeType.CORRECTION_REJECTED to "Se ha rechazado una corrección de un fichaje tuyo.",
            NoticeType.ABSENCE_PENDING to "Hay una solicitud de ausencia pendiente de resolver.",
            NoticeType.ABSENCE_APPROVED to "Se ha aprobado una ausencia tuya.",
            NoticeType.ABSENCE_REJECTED to "Se ha rechazado una solicitud de ausencia tuya."
        )
    }
}
