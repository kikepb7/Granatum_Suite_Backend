package com.granatum.core.domain.model

import com.granatum.core.domain.event.TipoAviso
import com.granatum.core.domain.type.EntityId
import java.time.Instant

/** A notice as its recipient reads it (feature 008). */
data class Notificacion(
    val id: EntityId,
    val tipo: TipoAviso,
    val referenciaId: EntityId,
    val creadaEn: Instant,
    val leidaEn: Instant?
) {
    /**
     * Fixed per type and composed when read (research D-003): no stored text,
     * so nothing a person wrote - and no name - can travel in a notice (FR-006).
     */
    val mensaje: String get() = MENSAJES.getValue(tipo)

    companion object {
        val MENSAJES: Map<TipoAviso, String> = mapOf(
            TipoAviso.FICHAJE_SIN_SALIDA to "Tienes un fichaje abierto desde hace muchas horas. ¿Has olvidado fichar la salida?",
            TipoAviso.FICHAJE_INCOMPLETO to "Un fichaje tuyo ha quedado incompleto porque no se registró la salida. Puedes solicitar su corrección.",
            TipoAviso.CORRECCION_PENDIENTE to "Hay una solicitud de corrección de fichaje pendiente de resolver.",
            TipoAviso.CORRECCION_APROBADA to "Se ha aprobado una corrección de un fichaje tuyo.",
            TipoAviso.CORRECCION_RECHAZADA to "Se ha rechazado una corrección de un fichaje tuyo.",
            TipoAviso.AUSENCIA_PENDIENTE to "Hay una solicitud de ausencia pendiente de resolver.",
            TipoAviso.AUSENCIA_APROBADA to "Se ha aprobado una ausencia tuya.",
            TipoAviso.AUSENCIA_RECHAZADA to "Se ha rechazado una solicitud de ausencia tuya.",
            TipoAviso.REGISTRO_PENDIENTE to "Hay una solicitud de registro pendiente de aprobar."
        )
    }
}
