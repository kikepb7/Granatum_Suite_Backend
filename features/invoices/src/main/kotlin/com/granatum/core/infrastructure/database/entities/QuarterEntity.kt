package com.granatum.core.infrastructure.database.entities

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.io.Serializable

@Embeddable
data class QuarterId(
    @Column(name = "year", nullable = false)
    val year: Short = 0,

    @Column(name = "quarter", nullable = false)
    val quarter: Short = 0
) : Serializable

/**
 * Whether a fiscal quarter is closed. Locked with `SELECT … FOR UPDATE` by every
 * change to an invoice of the quarter and by closing it (research.md D-016).
 */
@Entity
@Table(name = "quarters")
class QuarterEntity(
    @EmbeddedId
    val id: QuarterId,

    @Column(name = "closed", nullable = false)
    var closed: Boolean = false
) {
    override fun equals(other: Any?): Boolean = this === other || (other is QuarterEntity && id == other.id)
    override fun hashCode(): Int = javaClass.hashCode()
    override fun toString(): String = "QuarterEntity(${id.year}-T${id.quarter}, closed=$closed)"
}
