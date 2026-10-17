package com.granatum.core.infrastructure.database.entities

import com.granatum.core.domain.type.BreakType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * A break within a fichaje. At most one may be open at a time, enforced by a
 * partial unique index in V8 rather than by a service check alone.
 *
 * `shift` is `lateinit` because the owning side is set by
 * [ShiftEntity.addBreak], which keeps both ends of the association in
 * step.
 */
@Entity
@Table(name = "shift_breaks")
class ShiftBreakEntity(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    var type: BreakType,

    @Column(name = "started_at", nullable = false)
    var startedAt: Instant,

    @Column(name = "ended_at")
    var endedAt: Instant? = null
) {
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shift_id", nullable = false)
    lateinit var shift: ShiftEntity

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ShiftBreakEntity) return false
        return id == other.id
    }

    override fun hashCode(): Int = javaClass.hashCode()

    /** Does not touch [shift]: it is a lazy proxy. */
    override fun toString(): String = "ShiftBreakEntity(id=$id, type=$type)"
}
