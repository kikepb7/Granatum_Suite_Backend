package com.granatum.core.infrastructure.database.entities

import com.granatum.core.domain.type.EstadoFichaje
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
 * record - that lives in [FichajeEventoEntity], which is append-only.
 *
 * The separation is what makes the register tamper-proof while still allowing a
 * shift to be closed: a fichaje is born EN_CURSO and must be updated to record
 * its exit. Constitution principle III (v1.1.0) permits exactly five
 * transitions on this projection and forbids every other write.
 */
@Entity
@Table(name = "fichajes")
@EntityListeners(AuditingEntityListener::class)
class FichajeEntity(
    @Id
    val id: UUID = UUID.randomUUID(),

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "empleado_id", nullable = false)
    var empleado: EmpleadoEntity,

    /**
     * Never changes once recorded, except through an approved correction - not
     * even while the fichaje is still EN_CURSO. An open shift is not licence to
     * rewrite its start.
     */
    @Column(nullable = false)
    var entrada: Instant,

    @Column
    var salida: Instant? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    var estado: EstadoFichaje = EstadoFichaje.EN_CURSO,

    /** Minutes, not decimal hours: no rounding error when summed for a report. */
    @Column(name = "minutos_trabajados")
    var minutosTrabajados: Int? = null,

    /**
     * Only ever flips false -> true. A correction that completes an INCOMPLETO
     * fichaje moves it to CERRADO, but this stays true, so an inspection report
     * can tell a reconstructed day from one closed at the time.
     */
    @Column(name = "fue_incompleto", nullable = false)
    var fueIncompleto: Boolean = false,

    @Embedded
    @AttributeOverrides(
        AttributeOverride(name = "latitud", column = Column(name = "ubicacion_entrada_latitud", precision = 9, scale = 6)),
        AttributeOverride(name = "longitud", column = Column(name = "ubicacion_entrada_longitud", precision = 9, scale = 6)),
        AttributeOverride(name = "precisionMetros", column = Column(name = "ubicacion_entrada_precision_m"))
    )
    var ubicacionEntrada: UbicacionEmbeddable? = null,

    @Embedded
    @AttributeOverrides(
        AttributeOverride(name = "latitud", column = Column(name = "ubicacion_salida_latitud", precision = 9, scale = 6)),
        AttributeOverride(name = "longitud", column = Column(name = "ubicacion_salida_longitud", precision = 9, scale = 6)),
        AttributeOverride(name = "precisionMetros", column = Column(name = "ubicacion_salida_precision_m"))
    )
    var ubicacionSalida: UbicacionEmbeddable? = null
) {
    @OneToMany(
        mappedBy = "fichaje",
        fetch = FetchType.LAZY,
        cascade = [CascadeType.ALL],
        orphanRemoval = true
    )
    var pausas: MutableList<PausaEntity> = mutableListOf()
        protected set

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: Instant

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant

    /**
     * Maintains **both** sides of the bidirectional association. A half-updated
     * graph produces subtle bugs at flush time, so adding a pausa anywhere else
     * than through here is a mistake.
     */
    fun anadirPausa(pausa: PausaEntity) {
        pausa.fichaje = this
        pausas.add(pausa)
    }

    fun eliminarPausa(pausa: PausaEntity) {
        pausas.remove(pausa)
    }

    fun pausaAbierta(): PausaEntity? = pausas.firstOrNull { it.fin == null }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FichajeEntity) return false
        return id == other.id
    }

    override fun hashCode(): Int = javaClass.hashCode()

    /**
     * Does not touch [pausas] (lazy collection) nor either location (personal
     * data). Dereferencing the collection here would risk
     * LazyInitializationException, which this project has already hit once with
     * `MaterialEntity.fotos`.
     */
    override fun toString(): String = "FichajeEntity(id=$id, estado=$estado)"
}
