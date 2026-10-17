package com.granatum.core.infrastructure.database.entities

import com.granatum.core.domain.type.ShiftStatus
import jakarta.persistence.AttributeOverride
import jakarta.persistence.AttributeOverrides
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant
import java.util.UUID

/**
 * A working day. This is a **projection of current state**, not the evidentiary
 * record - that lives in [ShiftEventEntity], which is append-only.
 *
 * The separation is what makes the register tamper-proof while still allowing a
 * shift to be closed: a fichaje is born IN_PROGRESS and must be updated to record
 * its exit. Constitution principle III (v1.1.0) permits exactly five
 * transitions on this projection and forbids every other write.
 */
@Entity
@Table(name = "shifts")
@EntityListeners(AuditingEntityListener::class)
class ShiftEntity(
    @Id
    val id: UUID = UUID.randomUUID(),

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", nullable = false)
    var employee: EmployeeEntity,

    /**
     * Never changes once recorded, except through an approved correction - not
     * even while the fichaje is still IN_PROGRESS. An open shift is not licence to
     * rewrite its start.
     */
    @Column(nullable = false)
    var clockIn: Instant,

    @Column
    var clockOut: Instant? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    var status: ShiftStatus = ShiftStatus.IN_PROGRESS,

    /** Minutes, not decimal hours: no rounding error when summed for a report. */
    @Column(name = "worked_minutes")
    var workedMinutes: Int? = null,

    /**
     * Only ever flips false -> true. A correction that completes an INCOMPLETE
     * fichaje moves it to CLOSED, but this stays true, so an inspection report
     * can tell a reconstructed day from one closed at the time.
     */
    @Column(name = "was_incomplete", nullable = false)
    var wasIncomplete: Boolean = false,

    @Embedded
    @AttributeOverrides(
        AttributeOverride(name = "latitude", column = Column(name = "clock_in_latitude", precision = 9, scale = 6)),
        AttributeOverride(name = "longitude", column = Column(name = "clock_in_longitude", precision = 9, scale = 6)),
        AttributeOverride(name = "accuracyMeters", column = Column(name = "clock_in_accuracy_meters"))
    )
    var clockInLocation: ShiftLocationEmbeddable? = null,

    @Embedded
    @AttributeOverrides(
        AttributeOverride(name = "latitude", column = Column(name = "clock_out_latitude", precision = 9, scale = 6)),
        AttributeOverride(name = "longitude", column = Column(name = "clock_out_longitude", precision = 9, scale = 6)),
        AttributeOverride(name = "accuracyMeters", column = Column(name = "clock_out_accuracy_meters"))
    )
    var clockOutLocation: ShiftLocationEmbeddable? = null
) {
    @OneToMany(
        mappedBy = "shift",
        fetch = FetchType.LAZY,
        cascade = [CascadeType.ALL],
        orphanRemoval = true
    )
    var breaks: MutableList<ShiftBreakEntity> = mutableListOf()
        protected set

    // Defaulted rather than `lateinit`, matching `inventory`: the field is then
    // never null even in a context without auditing enabled, and auditing
    // overwrites it on insert where it is.
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()

    /**
     * Maintains **both** sides of the bidirectional association. A half-updated
     * graph produces subtle bugs at flush time, so adding a pausa anywhere else
     * than through here is a mistake.
     */
    fun addBreak(shiftBreak: ShiftBreakEntity) {
        shiftBreak.shift = this
        breaks.add(shiftBreak)
    }

    fun removeBreak(shiftBreak: ShiftBreakEntity) {
        breaks.remove(shiftBreak)
    }

    fun openBreak(): ShiftBreakEntity? = breaks.firstOrNull { it.endedAt == null }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ShiftEntity) return false
        return id == other.id
    }

    override fun hashCode(): Int = javaClass.hashCode()

    /**
     * Does not touch [breaks] (lazy collection) nor either location (personal
     * data). Dereferencing the collection here would risk
     * LazyInitializationException, which this project has already hit once with
     * `MaterialEntity.fotos`.
     */
    override fun toString(): String = "ShiftEntity(id=$id, status=$status)"
}
