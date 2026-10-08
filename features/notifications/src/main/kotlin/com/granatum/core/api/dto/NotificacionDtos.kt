package com.granatum.core.api.dto

import com.fasterxml.jackson.annotation.JsonInclude
import com.granatum.core.domain.event.TipoAviso
import com.granatum.core.domain.model.Notificacion
import java.time.Instant
import java.util.UUID

/** Feature 008, specs/008-notifications/contracts/README.md. */
data class NotificacionResponse(
    val id: UUID,
    val tipo: TipoAviso,
    val referenciaId: UUID,
    val mensaje: String,
    val creadaEn: Instant,
    @field:JsonInclude(JsonInclude.Include.ALWAYS)
    val leidaEn: Instant?
) {
    companion object {
        fun de(n: Notificacion) = NotificacionResponse(n.id, n.tipo, n.referenciaId, n.mensaje, n.creadaEn, n.leidaEn)
    }
}

data class TotalNoLeidasResponse(val total: Long)
