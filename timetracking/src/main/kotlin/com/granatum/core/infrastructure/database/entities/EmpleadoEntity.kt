package com.granatum.core.infrastructure.database.entities

import com.granatum.core.domain.type.TipoContrato
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * A member of staff. [id] is the JWT subject, which is what lets the
 * "only your own fichajes" rule be a direct comparison instead of a lookup.
 *
 * Plain `class`, never a `data class`: a data class generates
 * equals/hashCode over every field, so an entity's hash changes when it is
 * mutated and its membership of any Set is corrupted, and its copy() produces
 * detached duplicates of a managed entity.
 */
@Entity
@Table(name = "empleados")
@EntityListeners(AuditingEntityListener::class)
class EmpleadoEntity(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(nullable = false, length = 150)
    var nombre: String,

    @Column(name = "documento_identidad", nullable = false, length = 20, unique = true)
    var documentoIdentidad: String,

    @Column(nullable = false, length = 100)
    var puesto: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_contrato", nullable = false, length = 20)
    var tipoContrato: TipoContrato,

    @Column(name = "fecha_alta", nullable = false)
    var fechaAlta: LocalDate,

    @Column(nullable = false)
    var activo: Boolean = true
) {
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: Instant

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EmpleadoEntity) return false
        return id == other.id
    }

    /**
     * Class-constant on purpose: keeps the equals/hashCode contract stable
     * while the entity mutates, at the cost of degrading a HashSet of entities
     * to a list. The collections here are tiny, so the cost is irrelevant.
     */
    override fun hashCode(): Int = javaClass.hashCode()

    /** `documentoIdentidad` is deliberately absent: it is personal data. */
    override fun toString(): String = "EmpleadoEntity(id=$id, activo=$activo)"
}
