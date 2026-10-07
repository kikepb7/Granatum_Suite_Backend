package com.granatum.core.infrastructure.database.entities

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.io.Serializable

@Embeddable
data class TrimestreId(
    @Column(name = "anio", nullable = false)
    val anio: Short = 0,

    @Column(name = "trimestre", nullable = false)
    val trimestre: Short = 0
) : Serializable

/**
 * Whether a fiscal quarter is closed. Locked with `SELECT … FOR UPDATE` by every
 * change to an invoice of the quarter and by closing it (research.md D-016).
 */
@Entity
@Table(name = "trimestres")
class TrimestreEntity(
    @EmbeddedId
    val id: TrimestreId,

    @Column(name = "cerrado", nullable = false)
    var cerrado: Boolean = false
) {
    override fun equals(other: Any?): Boolean = this === other || (other is TrimestreEntity && id == other.id)
    override fun hashCode(): Int = javaClass.hashCode()
    override fun toString(): String = "TrimestreEntity(${id.anio}-T${id.trimestre}, cerrado=$cerrado)"
}
