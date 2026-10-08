package com.granatum.core.infrastructure.database.entities

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant
import java.util.UUID

@Embeddable
data class DerechoVacacionesId(
    @Column(name = "empleado_id", nullable = false)
    val empleadoId: UUID = UUID(0, 0),

    @Column(nullable = false)
    val anio: Short = 0
) : Serializable

/** A person's holiday entitlement for one year, when it is not the default (feature 007, V22). */
@Entity
@Table(name = "derechos_vacaciones")
class DerechoVacacionesEntity(
    @EmbeddedId
    val id: DerechoVacacionesId,

    @Column(nullable = false)
    var dias: Short,

    @Column(name = "actualizado_por", nullable = false)
    var actualizadoPor: UUID,

    @Column(name = "actualizado_en", nullable = false)
    var actualizadoEn: Instant
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DerechoVacacionesEntity) return false
        return id == other.id
    }

    override fun hashCode(): Int = javaClass.hashCode()

    override fun toString(): String = "DerechoVacacionesEntity(id=$id, dias=$dias)"
}
